(ns hyperopen.startup.restore
  (:require [clojure.string :as str]
            [hyperopen.i18n.locale :as i18n-locale]
            [hyperopen.account.context :as account-context]
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

(defn restore-ghost-mode-preferences!
  [store]
  (let [watchlist (parse-watchlist-storage
                   (platform/local-storage-get
                    account-context/ghost-watchlist-storage-key))
        search-input (some-> (platform/local-storage-get
                              account-context/ghost-last-search-storage-key)
                             str
                             str/trim)]
    (swap! store
           (fn [state]
             (-> state
                 (assoc-in [:account-context :watchlist] watchlist)
                 (assoc-in [:account-context :watchlist-loaded?] true)
                 (assoc-in [:account-context :ghost-ui :last-search] (or search-input ""))
                 (assoc-in [:account-context :ghost-ui :search] (or search-input ""))
                 (assoc-in [:account-context :ghost-ui :label] "")
                 (assoc-in [:account-context :ghost-ui :editing-watchlist-address] nil)
                 (assoc-in [:account-context :ghost-ui :search-error] nil))))))

(defn restore-active-asset!
  [store {:keys [connected?-fn dispatch! load-active-market-display-fn]}]
  (when (nil? (:active-asset @store))
    (let [stored-asset (platform/local-storage-get "active-asset")
          asset (if (seq stored-asset) stored-asset "BTC")
          cached-market (load-active-market-display-fn asset)]
      (swap! store
             (fn [state]
               (cond-> (assoc state :active-asset asset :selected-asset asset)
                 (map? cached-market) (assoc :active-market cached-market))))
      (when-not (seq stored-asset)
        (platform/local-storage-set! "active-asset" asset))
      (when (connected?-fn)
        (dispatch! store nil [[:actions/subscribe-to-asset asset]])))))
