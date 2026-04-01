(ns hyperopen.runtime.action-adapters.navigation
  (:require [hyperopen.account.spectate-mode-links :as spectate-mode-links]
            [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.funding-comparison.actions :as funding-comparison-actions]
            [hyperopen.leaderboard.actions :as leaderboard-actions]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.route-modules :as route-modules]
            [hyperopen.router :as router]
            [hyperopen.staking.actions :as staking-actions]
            [hyperopen.trade-modules :as trade-modules]
            [hyperopen.trading-indicators-modules :as trading-indicators-modules]
            [hyperopen.vaults.actions :as vault-actions]))

(def ^:private projection-effect-ids
  #{:effects/save
    :effects/save-many})

(defn- projection-effect?
  [effect]
  (contains? projection-effect-ids (first effect)))

(defn- split-projection-effects
  [effects]
  (let [effects* (vec (or effects []))]
    {:projection-effects (into [] (filter projection-effect? effects*))
     :follow-up-effects (into [] (remove projection-effect? effects*))}))

(defn- entering-portfolio-route?
  [state normalized-path]
  (let [current-route (router/normalize-path (get-in state [:router :path]))]
    (and (portfolio-routes/portfolio-route? normalized-path)
         (not (portfolio-routes/portfolio-route? current-route)))))

(defn- portfolio-route-effects
  [state normalized-path]
  (if (entering-portfolio-route? state normalized-path)
    (portfolio-actions/select-portfolio-chart-tab
     state
     (get-in state [:portfolio-ui :chart-tab]))
    []))

(defn- navigation-browser-path
  [state normalized-path]
  (spectate-mode-links/internal-route-href state normalized-path))

(defn- trade-chart-module-effect
  [state normalized-path]
  (when (and (router/trade-route? normalized-path)
             (not (trade-modules/trade-chart-ready? state))
             (not (trade-modules/trade-chart-loading? state)))
    [:effects/load-trade-chart-module]))

(defn- trading-indicators-module-effect
  [state normalized-path]
  (when (and (router/trade-route? normalized-path)
             (seq (get-in state [:chart-options :active-indicators]))
             (not (trading-indicators-modules/trading-indicators-ready? state))
             (not (trading-indicators-modules/trading-indicators-loading? state)))
    [:effects/load-trading-indicators-module]))

(defn- route-loader-effects
  [state normalized-path]
  (into []
        (concat (leaderboard-actions/load-leaderboard-route state normalized-path)
                (vault-actions/load-vault-route state normalized-path)
                (funding-comparison-actions/load-funding-comparison-route state normalized-path)
                (staking-actions/load-staking-route state normalized-path)
                (api-wallets-actions/load-api-wallet-route state normalized-path))))

(defn- route-projection-and-follow-up-effects
  [state normalized-path]
  (split-projection-effects
   (into []
         (concat (portfolio-route-effects state normalized-path)
                 (route-loader-effects state normalized-path)))))

(defn- deferred-route-effects
  [state normalized-path]
  (let [module-effect (when-let [_module-id (route-modules/route-module-id normalized-path)]
                        [:effects/load-route-module normalized-path])
        trade-chart-effect (trade-chart-module-effect state normalized-path)
        trading-indicators-effect (trading-indicators-module-effect state normalized-path)]
    (cond-> []
      module-effect (conj module-effect)
      trade-chart-effect (conj trade-chart-effect)
      trading-indicators-effect (conj trading-indicators-effect))))

(defn- browser-navigation-effect
  [browser-path replace?]
  (if replace?
    [:effects/replace-state browser-path]
    [:effects/push-state browser-path]))

(defn navigate
  [state path & [opts]]
  (let [normalized-path (router/normalize-path path)
        browser-path (navigation-browser-path state normalized-path)
        replace? (boolean (:replace? opts))
        {:keys [projection-effects follow-up-effects]}
        (route-projection-and-follow-up-effects state normalized-path)]
    (into [[:effects/save [:router :path] normalized-path]]
          (concat projection-effects
                  [(browser-navigation-effect browser-path replace?)]
                  (deferred-route-effects state normalized-path)
                  follow-up-effects))))

(defn load-vault-route-action
  [state path]
  (vault-actions/load-vault-route state path))

(defn load-funding-comparison-route-action
  [state path]
  (funding-comparison-actions/load-funding-comparison-route state path))

(defn load-staking-route-action
  [state path]
  (staking-actions/load-staking-route state path))

(defn load-api-wallet-route-action
  [state path]
  (api-wallets-actions/load-api-wallet-route state path))
