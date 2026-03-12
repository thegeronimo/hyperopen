(ns hyperopen.startup.restore
  (:require [clojure.string :as str]
            [hyperopen.i18n.locale :as i18n-locale]
            [hyperopen.account.context :as account-context]
            [hyperopen.account.spectate-mode-links :as spectate-mode-links]
            [hyperopen.router :as router]
            [hyperopen.platform :as platform]
            [hyperopen.wallet.agent-session :as agent-session]))

(defn restore-agent-storage-mode!
  [store]
  (let [storage-mode (agent-session/load-storage-mode-preference)]
    (swap! store assoc-in [:wallet :agent :storage-mode] storage-mode)))

(defn restore-ui-locale-preference!
  [store]
  (swap! store
         assoc-in
         [:ui :locale]
         (i18n-locale/resolve-preferred-locale)))

(defn- parse-watchlist-storage
  [raw]
  (let [raw* (some-> raw str str/trim)]
    (if-not (seq raw*)
      []
      (let [parsed (try
                     (js->clj (js/JSON.parse raw*))
                     (catch :default _
                       nil))]
        (cond
          (sequential? parsed)
          (account-context/normalize-watchlist parsed)

          (string? raw*)
          (account-context/normalize-watchlist
           (map str/trim (str/split raw* #",")))

          :else
          [])))))

(def ^:private legacy-spectate-watchlist-storage-keys
  ["shadow-mode-watchlist:v1"
   "ghost-mode-watchlist:v1"])

(def ^:private legacy-spectate-last-search-storage-keys
  ["shadow-mode-last-search:v1"
   "ghost-mode-last-search:v1"])

(defn- read-renamed-storage
  [new-key legacy-keys]
  (let [new-value (platform/local-storage-get new-key)]
    (if (some? new-value)
      {:value new-value
       :legacy-key nil}
      (or (some (fn [legacy-key]
                  (let [legacy-value (platform/local-storage-get legacy-key)]
                    (when (some? legacy-value)
                      {:value legacy-value
                       :legacy-key legacy-key})))
                legacy-keys)
          {:value nil
           :legacy-key nil}))))

(defn- remove-legacy-storage!
  [legacy-keys]
  (doseq [legacy-key legacy-keys]
    (platform/local-storage-remove! legacy-key)))

(defn- migrate-watchlist-storage!
  [watchlist]
  (platform/local-storage-set!
   account-context/spectate-watchlist-storage-key
   (js/JSON.stringify (clj->js watchlist)))
  (remove-legacy-storage! legacy-spectate-watchlist-storage-keys))

(defn- migrate-last-search-storage!
  [search-input]
  (platform/local-storage-set!
   account-context/spectate-last-search-storage-key
   (or search-input ""))
  (remove-legacy-storage! legacy-spectate-last-search-storage-keys))

(defn restore-spectate-mode-preferences!
  [store]
  (let [{watchlist-raw :value
         legacy-watchlist-key :legacy-key}
        (read-renamed-storage account-context/spectate-watchlist-storage-key
                              legacy-spectate-watchlist-storage-keys)
        {search-raw :value
         legacy-search-key :legacy-key}
        (read-renamed-storage account-context/spectate-last-search-storage-key
                              legacy-spectate-last-search-storage-keys)
        watchlist (parse-watchlist-storage watchlist-raw)
        search-input (some-> search-raw
                             str
                             str/trim)]
    (when legacy-watchlist-key
      (migrate-watchlist-storage! watchlist))
    (when legacy-search-key
      (migrate-last-search-storage! search-input))
    (swap! store
           (fn [state]
             (-> state
                 (assoc-in [:account-context :watchlist] watchlist)
                 (assoc-in [:account-context :watchlist-loaded?] true)
                 (assoc-in [:account-context :spectate-ui :last-search] (or search-input ""))
                 (assoc-in [:account-context :spectate-ui :search] (or search-input ""))
                 (assoc-in [:account-context :spectate-ui :label] "")
                 (assoc-in [:account-context :spectate-ui :editing-watchlist-address] nil)
                 (assoc-in [:account-context :spectate-ui :search-error] nil))))))

(defn restore-spectate-mode-url!
  ([store]
   (restore-spectate-mode-url! store
                               (some-> js/globalThis .-location .-search)
                               (platform/now-ms)))
  ([store search now-ms]
   (when-let [address (spectate-mode-links/spectate-address-from-search search)]
     (swap! store
            (fn [state]
              (-> state
                  (assoc-in [:account-context :spectate-mode :active?] true)
                  (assoc-in [:account-context :spectate-mode :address] address)
                  (assoc-in [:account-context :spectate-mode :started-at-ms] now-ms)
                  (assoc-in [:account-context :spectate-ui :search] address)
                  (assoc-in [:account-context :spectate-ui :last-search] address)
                  (assoc-in [:account-context :spectate-ui :label] "")
                  (assoc-in [:account-context :spectate-ui :editing-watchlist-address] nil)
                  (assoc-in [:account-context :spectate-ui :search-error] nil)))))))

(defn restore-active-asset!
  [store {:keys [connected?-fn dispatch! load-active-market-display-fn]}]
  (when (nil? (:active-asset @store))
    (let [route-asset (router/trade-route-asset (get-in @store [:router :path]))
          stored-asset (platform/local-storage-get "active-asset")
          asset (cond
                  (seq route-asset) route-asset
                  (seq stored-asset) stored-asset
                  :else "BTC")
          cached-market (load-active-market-display-fn asset)]
      (swap! store
             (fn [state]
               (cond-> (assoc state :active-asset asset :selected-asset asset)
                 (map? cached-market) (assoc :active-market cached-market))))
      (when (or (seq route-asset)
                (not (seq stored-asset)))
        (platform/local-storage-set! "active-asset" asset))
      (when (connected?-fn)
        (dispatch! store nil [[:actions/subscribe-to-asset asset]])))))
