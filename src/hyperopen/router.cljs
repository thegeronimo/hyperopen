(ns hyperopen.router
  (:require [clojure.string :as str]))

(def ^:private default-route
  "/trade")

(def ^:private trade-route-prefix
  "/trade")

(def ^:private trade-route-prefix-with-separator
  "/trade/")

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

(defn trade-route?
  [path]
  (str/starts-with? (normalize-path path) trade-route-prefix))

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

(defn set-route! [store path]
  (swap! store assoc :router {:path (normalize-path path)}))

(defn current-path []
  (let [location (some-> js/globalThis .-location)]
    (normalize-location-path
     (some-> location .-pathname)
     (some-> location .-hash))))

(defn init! [store]
  (set-route! store (current-path))
  (.addEventListener js/window "popstate"
                     (fn [_]
                       (set-route! store (current-path)))))
