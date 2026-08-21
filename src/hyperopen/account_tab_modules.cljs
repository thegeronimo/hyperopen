(ns hyperopen.account-tab-modules
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.router :as router]
            [shadow.loader :as loader]))

(def ^:private built-in-account-tabs
  #{:balances
    :positions
    :outcomes
    :open-orders
    :twap
    :trade-history
    :funding-history
    :order-history})

(def ^:private module-id-by-tab
  {:positions :positions-outcomes
   :outcomes :positions-outcomes
   :open-orders :orders
   :order-history :orders
   :trade-history :activity
   :twap :activity
   :funding-history :funding-history})

(def ^:private module-name-by-id
  {:positions-outcomes "account_positions_outcomes"
   :orders "account_orders"
   :activity "account_activity"
   :funding-history "account_funding_history"})

(def ^:private exported-paths-by-tab
  {:positions ["hyperopen" "views" "account_positions_outcomes_module" "positions_tab_renderer"]
   :outcomes ["hyperopen" "views" "account_positions_outcomes_module" "outcomes_tab_renderer"]
   :open-orders ["hyperopen" "views" "account_orders_module" "open_orders_tab_renderer"]
   :order-history ["hyperopen" "views" "account_orders_module" "order_history_tab_renderer"]
   :trade-history ["hyperopen" "views" "account_activity_module" "trade_history_tab_renderer"]
   :twap ["hyperopen" "views" "account_activity_module" "twap_tab_renderer"]
   :funding-history ["hyperopen" "views" "account_funding_history_module" "funding_history_tab_renderer"]})

(defonce ^:private resolved-tab-renderers* (atom {}))

(declare cached-or-exported-renderer)
(declare tab-ready? tab-loading?)

(defn reset-account-tab-module-state!
  []
  (reset! resolved-tab-renderers* {}))

(defn default-state
  []
  {:loaded #{}
   :loading #{}
   :errors {}})

(defn module-id-for-tab
  [tab]
  (get module-id-by-tab tab))

(defn lazy-tab?
  [tab]
  (some? (module-id-for-tab tab)))

(defn- portfolio-built-in-tab
  [state]
  (let [tab (get-in state [:portfolio-ui :account-info-tab])]
    (when (contains? built-in-account-tabs tab)
      tab)))

(defn route-visible-account-tab
  [state normalized-path]
  (cond
    (router/trade-route? normalized-path)
    (or (get-in state [:account-info :selected-tab]) :balances)

    (portfolio-routes/portfolio-route? normalized-path)
    (portfolio-built-in-tab state)

    :else nil))

(defn route-tab-module-effect
  [state normalized-path]
  (when-let [tab (route-visible-account-tab state normalized-path)]
    (when (and (lazy-tab? tab)
               (not (tab-ready? state tab))
               (not (tab-loading? state tab)))
      [:effects/load-account-tab-module tab])))

(defn resolved-tab-renderer
  [tab]
  (cached-or-exported-renderer tab))

(defn- resolve-exported-renderer
  [path-segments]
  (let [root (or (some-> js/goog .-global)
                 js/globalThis)]
    (reduce (fn [acc segment]
              (when acc
                (gobj/get acc segment)))
            root
            path-segments)))

(defn- resolve-module-renderer
  [tab]
  (when-let [path-segments (get exported-paths-by-tab tab)]
    (resolve-exported-renderer path-segments)))

(defn- cached-or-exported-renderer
  [tab]
  (let [cached-renderer (get @resolved-tab-renderers* tab)]
    (cond
      (fn? cached-renderer)
      cached-renderer

      (some? cached-renderer)
      (do
        (swap! resolved-tab-renderers* dissoc tab)
        nil)

      :else
      (when-let [resolved-renderer (resolve-module-renderer tab)]
        (when (fn? resolved-renderer)
          (swap! resolved-tab-renderers* assoc tab resolved-renderer)
          resolved-renderer)))))

(defn tab-ready?
  [_state tab]
  (some? (cached-or-exported-renderer tab)))

(defn tab-loading?
  [state tab]
  (contains? (get-in state [:account-tab-modules :loading] #{})
             (module-id-for-tab tab)))

(defn tab-error
  [state tab]
  (get-in state [:account-tab-modules :errors (module-id-for-tab tab)]))

(defn mark-account-tab-module-loading
  [state tab]
  (if-let [module-id (module-id-for-tab tab)]
    (-> state
        (update-in [:account-tab-modules :loading] (fnil conj #{}) module-id)
        (update-in [:account-tab-modules :errors] dissoc module-id))
    state))

(defn mark-account-tab-module-loaded
  [state tab]
  (if-let [module-id (module-id-for-tab tab)]
    (-> state
        (update-in [:account-tab-modules :loaded] (fnil conj #{}) module-id)
        (update-in [:account-tab-modules :loading] disj module-id)
        (update-in [:account-tab-modules :errors] dissoc module-id))
    state))

(defn mark-account-tab-module-failed
  [state tab err]
  (if-let [module-id (module-id-for-tab tab)]
    (let [message (or (some-> err .-message)
                      (some-> err str str/trim not-empty)
                      "Failed to load account tab module.")]
      (-> state
          (update-in [:account-tab-modules :loading] disj module-id)
          (assoc-in [:account-tab-modules :errors module-id] message)))
    state))

(def module-load-timeout-ms
  "Upper bound on a single chunk request. Public so tests can shorten it.

   shadow's module loader never rejects on a stalled or unusable response, so
   without this the failure branch below is unreachable: a tab whose chunk never
   arrives sits on its pending state forever with nothing to retry. The most
   common way to get there is a deploy that rotates chunk hashes underneath a
   long-lived tab. This bound exists to make failure reportable, not to make
   loading faster -- a healthy chunk resolves in tens of milliseconds."
  10000)

(defn- with-load-timeout
  [load-promise module-name]
  (let [timer* (atom nil)
        clear-timer! (fn []
                       (when-let [timer @timer*]
                         (reset! timer* nil)
                         (js/clearTimeout timer)))]
    (-> (js/Promise.race
         #js [(js/Promise.resolve load-promise)
              (js/Promise.
               (fn [_resolve reject]
                 (reset! timer*
                         (js/setTimeout
                          (fn []
                            (reject (js/Error. (str "Timed out loading account tab module: "
                                                    module-name))))
                          module-load-timeout-ms))))])
        (.then (fn [value]
                 (clear-timer!)
                 value)
               (fn [err]
                 (clear-timer!)
                 (js/Promise.reject err))))))

(defn load-account-tab-module!
  [store tab]
  (if-let [module-id (module-id-for-tab tab)]
    (if-let [existing-renderer (cached-or-exported-renderer tab)]
      (do
        (swap! store mark-account-tab-module-loaded tab)
        (js/Promise.resolve existing-renderer))
      (let [module-name (get module-name-by-id module-id)
            resolve-loaded-renderer!
            (fn []
              (let [resolved-renderer (resolve-module-renderer tab)]
                (when-not (fn? resolved-renderer)
                  (throw (js/Error.
                          (str "Loaded account tab module without exported renderer: "
                               (name module-id)
                               "/"
                               (name tab)))))
                (swap! resolved-tab-renderers* assoc tab resolved-renderer)
                (swap! store mark-account-tab-module-loaded tab)
                resolved-renderer))]
        (swap! store mark-account-tab-module-loading tab)
        (try
          (if (loader/loaded? module-name)
            (js/Promise.resolve (resolve-loaded-renderer!))
            (-> (with-load-timeout (loader/load module-name) module-name)
                (.then (fn [_]
                         (resolve-loaded-renderer!)))
                (.catch (fn [err]
                          (swap! store mark-account-tab-module-failed tab err)
                          (js/Promise.reject err)))))
          (catch :default err
            (swap! store mark-account-tab-module-failed tab err)
            (js/Promise.reject err)))))
    (js/Promise.resolve nil)))
