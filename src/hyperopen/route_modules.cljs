(ns hyperopen.route-modules
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.funding-comparison.actions :as funding-comparison-actions]
            [hyperopen.leaderboard.actions :as leaderboard-actions]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.router :as router]
            [hyperopen.staking.actions :as staking-actions]
            [hyperopen.vaults.infrastructure.routes :as vault-routes]
            [shadow.loader :as loader]))

(def ^:private module-name-by-id
  {:portfolio "portfolio_route"
   :leaderboard "leaderboard_route"
   :funding-comparison "funding_comparison_route"
   :staking "staking_route"
   :api-wallets "api_wallets_route"
   :vaults "vaults_route"})

(def ^:private exported-view-paths-by-module
  {:portfolio [["hyperopen" "views" "portfolio_view" "route_view"]]
   :leaderboard [["hyperopen" "views" "leaderboard_view" "route_view"]]
   :funding-comparison [["hyperopen" "views" "funding_comparison_view" "route_view"]]
   :staking [["hyperopen" "views" "staking_view" "route_view"]]
   :api-wallets [["hyperopen" "views" "api_wallets_view" "route_view"]]
   :vaults [["hyperopen" "views" "vaults" "list_view" "route_view"]
            ["hyperopen" "views" "vaults" "detail_view" "route_view"]]})

(def ^:private vaults-startup-preview-restore-path
  ["hyperopen" "views" "vaults" "startup_preview" "restore_startup_preview_BANG_"])

(defonce ^:private resolved-route-views (atom {}))

(declare resolve-module-view)

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
      (staking-actions/staking-route? route) :staking
      (api-wallets-actions/api-wallet-route? route) :api-wallets
      (vault-routes/vault-detail-route? route) :vaults
      (vault-routes/vault-route? route) :vaults
      :else nil)))

(defn resolved-route-view
  [module-id]
  (get @resolved-route-views module-id))

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

(defn route-ready?
  [state path]
  (if-let [module-id (route-module-id path)]
    (some? (cached-or-exported-view module-id))
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

(defn- resolve-module-view
  [module-id]
  (let [paths (get exported-view-paths-by-module module-id)
        views (mapv resolve-exported-view paths)]
    (case module-id
      :vaults {:list (nth views 0 nil)
               :detail (nth views 1 nil)}
      (nth views 0 nil))))

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
    (if-let [existing-view (cached-or-exported-view module-id)]
      (do
        (when (= module-id :vaults)
          (maybe-restore-vaults-list-preview! store path))
        (swap! store mark-route-module-loaded module-id)
        (js/Promise.resolve existing-view))
      (let [module-name (get module-name-by-id module-id)
            resolve-loaded-view!
            (fn []
              (let [resolved-view (resolve-module-view module-id)]
                (when-not (resolved-view-ready? module-id resolved-view)
                  (throw (js/Error.
                          (str "Loaded route module without exported view: " module-id))))
                (swap! resolved-route-views assoc module-id resolved-view)
                (when (= module-id :vaults)
                  (maybe-restore-vaults-list-preview! store path))
                (swap! store mark-route-module-loaded module-id)
                resolved-view))]
        (swap! store mark-route-module-loading path)
        (try
          (if (loader/loaded? module-name)
            (js/Promise.resolve (resolve-loaded-view!))
            (-> (loader/load module-name)
                (.then (fn [_]
                         (resolve-loaded-view!)))
                (.catch (fn [err]
                          (swap! store mark-route-module-failed module-id err)
                          (js/Promise.reject err)))))
          (catch :default err
            (swap! store mark-route-module-failed module-id err)
            (js/Promise.reject err)))))
    (do
      (swap! store mark-route-module-loaded nil)
      (js/Promise.resolve nil))))
