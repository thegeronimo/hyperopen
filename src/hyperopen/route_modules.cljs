(ns hyperopen.route-modules
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.borrow-lend.actions :as borrow-lend-actions]
            [hyperopen.funding-comparison.actions :as funding-comparison-actions]
            [hyperopen.leaderboard.actions :as leaderboard-actions]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.referrals.actions :as referrals-actions]
            [hyperopen.router :as router]
            [hyperopen.staking.actions :as staking-actions]
            [hyperopen.subaccounts.actions :as subaccounts-actions]
            [hyperopen.vaults.infrastructure.routes :as vault-routes]
            [shadow.loader :as loader]))

(def ^:private module-name-by-id
  {:portfolio "portfolio_route"
   :leaderboard "leaderboard_route"
   :funding-comparison "funding_comparison_route"
   :referrals "referrals_route"
   :staking "staking_route"
   :earn "earn_route"
   :api-wallets "api_wallets_route"
   :subaccounts "subaccounts_route"
   :vaults "vaults_route"})

(def ^:private exported-view-paths-by-module
  {:portfolio [["hyperopen" "views" "portfolio_view" "route_view"]]
   :leaderboard [["hyperopen" "views" "leaderboard_view" "route_view"]]
   :funding-comparison [["hyperopen" "views" "funding_comparison_view" "route_view"]]
   :referrals [["hyperopen" "views" "referrals_view" "route_view"]]
   :staking [["hyperopen" "views" "staking_view" "route_view"]]
   :earn [["hyperopen" "views" "earn_view" "route_view"]]
   :api-wallets [["hyperopen" "views" "api_wallets_view" "route_view"]]
   :subaccounts [["hyperopen" "views" "subaccounts_view" "route_view"]]
   :vaults [["hyperopen" "views" "vaults" "list_view" "route_view"]
            ["hyperopen" "views" "vaults" "detail_view" "route_view"]]})

(def ^:private exported-runtime-paths-by-module
  {:portfolio {:action-deps ["hyperopen" "portfolio" "route_runtime_module" "action_deps"]
               :effect-deps ["hyperopen" "portfolio" "route_runtime_module" "effect_deps"]}
   :vaults {:action-deps ["hyperopen" "vaults" "route_runtime_module" "action_deps"]
            :effect-deps ["hyperopen" "vaults" "route_runtime_module" "effect_deps"]}})

(def ^:private vaults-startup-preview-restore-path
  ["hyperopen" "views" "vaults" "startup_preview" "restore_startup_preview_BANG_"])

(defonce ^:private resolved-route-views (atom {}))
(defonce ^:private resolved-route-runtime-exports (atom {}))

(declare resolve-module-view)

(declare resolve-module-runtime-exports)

(declare cached-or-exported-route-runtime-exports)

(defn default-state
  []
  {:loaded #{}
   :loading nil
   :errors {}})

(defn route-module-id
  [path]
  (let [route (router/normalize-path path)]
    (cond
      (router/trade-route? route) nil
      (portfolio-routes/portfolio-route? route) :portfolio
      (leaderboard-actions/leaderboard-route? route) :leaderboard
      (funding-comparison-actions/funding-comparison-route? route) :funding-comparison
      (referrals-actions/referrals-route? route) :referrals
      (staking-actions/staking-route? route) :staking
      (borrow-lend-actions/earn-route? route) :earn
      (api-wallets-actions/api-wallet-route? route) :api-wallets
      (subaccounts-actions/subaccounts-route? route) :subaccounts
      (vault-routes/vault-detail-route? route) :vaults
      (vault-routes/vault-route? route) :vaults
      :else nil)))

(defn resolved-route-view
  [module-id]
  (get @resolved-route-views module-id))

(defn- route-runtime-required?
  [module-id]
  (contains? exported-runtime-paths-by-module module-id))

(defn- resolved-view-ready?
  [module-id resolved-view]
  (case module-id
    :vaults (or (fn? (:list resolved-view))
                (fn? (:detail resolved-view)))
    (fn? resolved-view)))

(defn- cached-or-exported-view
  [module-id]
  (let [cached-view (resolved-route-view module-id)]
    (cond
      (resolved-view-ready? module-id cached-view)
      cached-view

      (some? cached-view)
      (do
        (swap! resolved-route-views dissoc module-id)
        nil)

      :else
      (when-let [resolved-view (resolve-module-view module-id)]
        (when (resolved-view-ready? module-id resolved-view)
          (swap! resolved-route-views assoc module-id resolved-view)
          resolved-view)))))

(defn- route-runtime-ready?
  [module-id]
  (if (route-runtime-required? module-id)
    (some? (cached-or-exported-route-runtime-exports module-id))
    true))

(defn- route-module-ready?
  [module-id]
  (and (some? (cached-or-exported-view module-id))
       (route-runtime-ready? module-id)))

(defn route-ready?
  [state path]
  (if-let [module-id (route-module-id path)]
    (route-module-ready? module-id)
    true))

(defn route-loading?
  [state path]
  (= (get-in state [:route-modules :loading])
     (route-module-id path)))

(defn route-error
  [state path]
  (get-in state [:route-modules :errors (route-module-id path)]))

(defn- resolve-exported-view
  [path-segments]
  (let [root (or (some-> js/goog .-global)
                 js/globalThis)]
    (reduce (fn [acc segment]
              (when acc
                (gobj/get acc segment)))
            root
            path-segments)))

(defn- module-name
  [module-id]
  (get module-name-by-id module-id))

(defn- load-shadow-module!
  [module-id]
  (if-let [shadow-module-name (module-name module-id)]
    (if (loader/loaded? shadow-module-name)
      (js/Promise.resolve shadow-module-name)
      (loader/load shadow-module-name))
    (js/Promise.reject
     (js/Error. (str "Unknown route module: " module-id)))))

(defn- resolve-module-view
  [module-id]
  (let [paths (get exported-view-paths-by-module module-id)
        views (mapv resolve-exported-view paths)]
    (case module-id
      :vaults {:list (nth views 0 nil)
               :detail (nth views 1 nil)}
      (nth views 0 nil))))

(defn- route-runtime-exports-ready?
  [runtime-exports]
  (and (fn? (:action-deps runtime-exports))
       (fn? (:effect-deps runtime-exports))))

(defn- resolve-module-runtime-exports
  [module-id]
  (when-let [{:keys [action-deps effect-deps]}
             (get exported-runtime-paths-by-module module-id)]
    {:action-deps (resolve-exported-view action-deps)
     :effect-deps (resolve-exported-view effect-deps)}))

(defn- resolve-loaded-route-runtime-exports!
  [module-id]
  (when (route-runtime-required? module-id)
    (let [runtime-exports (resolve-module-runtime-exports module-id)]
      (when-not (route-runtime-exports-ready? runtime-exports)
        (throw
         (js/Error.
          (str "Loaded route module without exported runtime: " module-id))))
      (swap! resolved-route-runtime-exports assoc module-id runtime-exports)
      runtime-exports)))

(defn- cached-or-exported-route-runtime-exports
  [module-id]
  (let [cached-exports (get @resolved-route-runtime-exports module-id)]
    (cond
      (route-runtime-exports-ready? cached-exports)
      cached-exports

      (some? cached-exports)
      (do
        (swap! resolved-route-runtime-exports dissoc module-id)
        nil)

      :else
      (when-let [runtime-exports (resolve-module-runtime-exports module-id)]
        (when (route-runtime-exports-ready? runtime-exports)
          (swap! resolved-route-runtime-exports assoc module-id runtime-exports)
          runtime-exports)))))

(defn resolved-route-runtime-action-deps
  [module-id]
  (when-let [action-deps-fn
             (:action-deps (cached-or-exported-route-runtime-exports module-id))]
    (action-deps-fn)))

(defn resolved-route-runtime-effect-deps
  [runtime module-id]
  (when-let [effect-deps-fn
             (:effect-deps (cached-or-exported-route-runtime-exports module-id))]
    (effect-deps-fn runtime)))

(defn- route-runtime-handler-error
  [kind module-id handler-path]
  (js/Error.
   (str "Route runtime "
        (name kind)
        " handler unavailable: "
        module-id
        " "
        (pr-str handler-path))))

(defn- maybe-restore-vaults-list-preview!
  [store path]
  (when (= :list
           (:kind (vault-routes/parse-vault-route path)))
    (when-let [restore-fn (resolve-exported-view vaults-startup-preview-restore-path)]
      (when (fn? restore-fn)
        (restore-fn store)))))

(defn render-route-view
  [state path]
  (when-let [module-id (route-module-id path)]
    (let [view (cached-or-exported-view module-id)]
      (case module-id
        :vaults (if (vault-routes/vault-detail-route? path)
                  (when-let [detail-view (:detail view)]
                    (detail-view state))
                  (when-let [list-view (:list view)]
                    (list-view state)))
        (when (fn? view)
          (view state))))))

(defn mark-route-module-loading
  [state path]
  (let [module-id (route-module-id path)]
    (if module-id
      (-> state
          (assoc-in [:route-modules :loading] module-id)
          (update-in [:route-modules :errors] dissoc module-id))
      (assoc-in state [:route-modules :loading] nil))))

(defn mark-route-module-loaded
  [state module-id]
  (if module-id
    (-> state
        (update-in [:route-modules :loaded] (fnil conj #{}) module-id)
        (assoc-in [:route-modules :loading] nil)
        (update-in [:route-modules :errors] dissoc module-id))
    (assoc-in state [:route-modules :loading] nil)))

(defn mark-route-module-failed
  [state module-id err]
  (let [message (or (some-> err .-message)
                    (some-> err str str/trim not-empty)
                    "Failed to load route.")]
    (-> state
        (assoc-in [:route-modules :loading] nil)
        (assoc-in [:route-modules :errors module-id] message))))

(defn load-route-module!
  [store path]
    (if-let [module-id (route-module-id path)]
      (if (route-module-ready? module-id)
        (do
          (when (= module-id :vaults)
            (maybe-restore-vaults-list-preview! store path))
          (swap! store mark-route-module-loaded module-id)
          (js/Promise.resolve (cached-or-exported-view module-id)))
      (let [resolve-loaded-view!
            (fn []
              (let [resolved-view (resolve-module-view module-id)]
                (when-not (resolved-view-ready? module-id resolved-view)
                  (throw (js/Error.
                          (str "Loaded route module without exported view: " module-id))))
                (swap! resolved-route-views assoc module-id resolved-view)
                (resolve-loaded-route-runtime-exports! module-id)
                (when (= module-id :vaults)
                  (maybe-restore-vaults-list-preview! store path))
                (swap! store mark-route-module-loaded module-id)
                resolved-view))]
        (swap! store mark-route-module-loading path)
        (try
          (-> (load-shadow-module! module-id)
              (.then (fn [_]
                       (resolve-loaded-view!)))
              (.catch (fn [err]
                        (swap! store mark-route-module-failed module-id err)
                        (js/Promise.reject err))))
          (catch :default err
            (swap! store mark-route-module-failed module-id err)
            (js/Promise.reject err)))))
    (do
      (swap! store mark-route-module-loaded nil)
      (js/Promise.resolve nil))))

(defn dispatch-route-actions-after-load!
  [store dispatch! path actions]
  (when (and (fn? dispatch!)
             (seq actions))
    (if-let [module-id (route-module-id path)]
      (if (route-module-ready? module-id)
        (dispatch! store nil actions)
        (-> (load-route-module! store path)
            (.then (fn [_]
                     (dispatch! store nil actions)))))
      (dispatch! store nil actions))))

(defn- lazy-route-action-handler
  [module-id handler-path]
  (fn [& args]
    (if-let [handler (get-in (resolved-route-runtime-action-deps module-id)
                             handler-path)]
      (apply handler args)
      (throw (route-runtime-handler-error :action module-id handler-path)))))

(defn lazy-route-action-leaf-deps
  [module-id group-key handler-keys]
  {group-key
   (into {}
         (map (fn [handler-key]
                [handler-key
                 (lazy-route-action-handler module-id [group-key handler-key])]))
         handler-keys)})

(defn- lazy-route-effect-handler
  [runtime module-id handler-path]
  (fn [ctx store & args]
    (if-let [handler (get-in (resolved-route-runtime-effect-deps runtime module-id)
                             handler-path)]
      (apply handler ctx store args)
      (-> (load-shadow-module! module-id)
          (.then (fn [_]
                   (if-let [loaded-handler
                            (get-in (resolved-route-runtime-effect-deps runtime module-id)
                                    handler-path)]
                     (apply loaded-handler ctx store args)
                     (throw (route-runtime-handler-error
                             :effect
                             module-id
                             handler-path)))))))))

(defn lazy-route-effect-leaf-deps
  [runtime module-id group-key handler-keys]
  {group-key
   (into {}
         (map (fn [handler-key]
                [handler-key
                 (lazy-route-effect-handler runtime
                                            module-id
                                            [group-key handler-key])]))
         handler-keys)})
