(ns hyperopen.router
  (:require [clojure.string :as str]))

(def ^:private default-route
  "/trade")

(def ^:private trade-route-prefix
  "/trade")

(def ^:private trade-route-prefix-with-separator
  "/trade/")

(def ^:private trade-market-query-param
  "market")

(def ^:private trade-tab-query-param
  "tab")

(def ^:private trade-spectate-query-param
  "spectate")

(defonce ^:private popstate-cleanup
  (atom nil))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- parse-absolute-url-path
  [text]
  (when (and (string? text)
             (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://" text))
    (try
      (some-> (js/URL. text) .-pathname)
      (catch :default _ nil))))

(defn- split-path-from-query-fragment
  [text]
  (or (first (str/split (or text "") #"[?#]" 2))
      ""))

(defn- ensure-leading-slash
  [path]
  (let [path* (or path "")]
    (if (seq path*)
      (if (str/starts-with? path* "/")
        path*
        (str "/" path*))
      "/")))

(defn- trim-trailing-slashes
  [path]
  (loop [path* (or path "")]
    (if (and (> (count path*) 1)
             (str/ends-with? path* "/"))
      (recur (subs path* 0 (dec (count path*))))
      path*)))

(defn normalize-path [path]
  (let [path* (if (string? path)
                path
                (str (or path "")))
        trimmed (str/trim path*)
        url-path (or (parse-absolute-url-path trimmed)
                     trimmed)
        normalized (-> url-path
                       split-path-from-query-fragment
                       ensure-leading-slash
                       trim-trailing-slashes)]
    (if (or (= normalized "")
            (= normalized "/"))
      default-route
      normalized)))

(defn- normalize-search
  [search]
  (let [search* (some-> search str str/trim)]
    (if-not (seq search*)
      ""
      (let [without-fragment (or (first (str/split search* #"#" 2))
                                 "")
            query-index (.indexOf without-fragment "?")
            query-text (if (>= query-index 0)
                         (subs without-fragment query-index)
                         without-fragment)]
        (if (str/starts-with? query-text "?")
          query-text
          (str "?" query-text))))))

(defn query-param-value
  [search param]
  (when-let [param* (non-blank-text param)]
    (-> (js/URLSearchParams. (normalize-search search))
        (.get param*)
        non-blank-text)))

(defn trade-route?
  [path]
  (str/starts-with? (normalize-path path) trade-route-prefix))

(defn trade-route-market-from-search
  [search]
  (query-param-value search trade-market-query-param))

(defn trade-route-tab-from-search
  [search]
  (query-param-value search trade-tab-query-param))

(defn- decode-trade-route-asset
  [raw-asset]
  (try
    (js/decodeURIComponent raw-asset)
    (catch :default _
      nil)))

(defn trade-route-asset
  [path]
  (let [path* (normalize-path path)]
    (when (str/starts-with? path* trade-route-prefix-with-separator)
      (let [raw-asset (subs path* (count trade-route-prefix-with-separator))
            raw-asset* (some-> raw-asset str/trim)]
        (when (seq raw-asset*)
          (decode-trade-route-asset raw-asset*))))))

(defn trade-route-asset-or-market
  [path search]
  (or (trade-route-market-from-search search)
      (trade-route-asset path)))

(defn- encode-trade-route-asset
  [asset]
  (-> (js/encodeURIComponent asset)
      (str/replace #"%3A" ":")
      (str/replace #"%2F" "/")))

(defn trade-route-path
  [asset]
  (let [asset* (some-> asset str str/trim)]
    (if (seq asset*)
      (str trade-route-prefix-with-separator
           (encode-trade-route-asset asset*))
      default-route)))

(defn- query-param-token
  [value]
  (cond
    (keyword? value) (non-blank-text (name value))
    :else (non-blank-text value)))

(defn trade-browser-path
  [{:keys [market tab spectate]}]
  (let [params (js/URLSearchParams.)]
    (when-let [market* (query-param-token market)]
      (.set params trade-market-query-param market*))
    (when-let [tab* (query-param-token tab)]
      (.set params trade-tab-query-param tab*))
    (when-let [spectate* (query-param-token spectate)]
      (.set params trade-spectate-query-param spectate*))
    (let [query-text (.toString params)]
      (if (seq query-text)
        (str default-route "?" query-text)
        default-route))))

(defn normalize-location-path
  [pathname hash]
  (let [pathname* (if (string? pathname)
                    pathname
                    (str (or pathname "")))
        hash* (if (string? hash) (str/trim hash) "")
        hash-path (when (str/starts-with? hash* "#/")
                    (subs hash* 1))
        candidate-path (if (or (= pathname* "")
                               (= pathname* "/"))
                         (or hash-path pathname*)
                         pathname*)]
    (normalize-path candidate-path)))

(defn set-route!
  ([store path]
   (set-route! store path nil))
  ([store path on-route-change]
   (let [normalized-path (normalize-path path)]
     (swap! store assoc :router {:path normalized-path})
     (when (fn? on-route-change)
       (on-route-change normalized-path)))))

(defn current-path []
  (let [location (some-> js/globalThis .-location)]
    (normalize-location-path
     (some-> location .-pathname)
     (some-> location .-hash))))

(defn- install-popstate-listener!
  [store on-route-change]
  (let [window-object (when (exists? js/window) js/window)
        add-event-listener (some-> window-object (.-addEventListener))
        remove-event-listener (some-> window-object (.-removeEventListener))]
    (when (and (fn? add-event-listener)
               (fn? remove-event-listener))
      (when-let [cleanup @popstate-cleanup]
        (cleanup)
        (reset! popstate-cleanup nil))
      (let [handler (fn [_]
                      (set-route! store (current-path) on-route-change))]
        (.addEventListener window-object "popstate" handler)
        (reset! popstate-cleanup
                (fn []
                  (.removeEventListener window-object "popstate" handler)))))))

(defn init!
  ([store]
   (init! store {}))
  ([store {:keys [on-route-change
                  skip-route-set?]
           :or {skip-route-set? false}}]
   (when-not skip-route-set?
     (set-route! store (current-path) on-route-change))
   (install-popstate-listener! store on-route-change)))
