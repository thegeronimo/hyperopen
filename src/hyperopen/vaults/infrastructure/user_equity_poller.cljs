(ns hyperopen.vaults.infrastructure.user-equity-poller
  "Refreshes the active trade account's vault NAV while its panel is visible."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.platform :as platform]))

(def default-interval-ms 60000)

(defonce ^:private installed-timer (atom nil))

(defn- document-visible?
  []
  (or (not (exists? js/document))
      (not= "hidden" (.-visibilityState js/document))))

(defn- trade-route-active?
  [state]
  (let [route (some-> (get-in state [:router :path]) str str/trim)]
    (or (str/blank? route)
        (str/starts-with? route "/trade"))))

(defn stop-user-equity-poller!
  []
  (when-let [{:keys [id clear-interval-fn]} @installed-timer]
    (clear-interval-fn id)
    (reset! installed-timer nil)))

(defn install-user-equity-poller!
  "Install a single, low-frequency refresh for one effective account. Replacing
  an installed timer clears it first, which makes account switches and reloads
  teardown-safe."
  [{:keys [store
           address
           fetch-user-vault-equities!
           visible?-fn
           set-interval-fn
           clear-interval-fn
           interval-ms]
    :or {visible?-fn document-visible?
         set-interval-fn platform/set-interval!
         clear-interval-fn platform/clear-interval!
         interval-ms default-interval-ms}}]
  (stop-user-equity-poller!)
  (let [requested-address (account-context/normalize-address address)
        tick! (fn []
                (let [state @store
                      active-address (account-context/effective-account-address state)
                      loading-address (get-in state [:vaults :loading :user-equities-for-address])
                      loading? (true? (get-in state [:vaults :loading :user-equities?]))]
                  (when (and requested-address
                             (= requested-address active-address)
                             (not= :unified (get-in state [:account :mode]))
                             (visible?-fn)
                             (trade-route-active? state)
                             (not loading?)
                             (not= requested-address loading-address))
                    (when-let [request (fetch-user-vault-equities!
                                        store requested-address {:priority :low})]
                      (.catch request (fn [_] nil))))))]
    (when (and store
               requested-address
               (fn? fetch-user-vault-equities!)
               (not= :unified (get-in @store [:account :mode])))
      (reset! installed-timer {:id (set-interval-fn tick! interval-ms)
                               :clear-interval-fn clear-interval-fn}))
    store))
