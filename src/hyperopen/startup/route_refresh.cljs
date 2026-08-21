(ns hyperopen.startup.route-refresh
  (:require [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.funding-comparison.actions :as funding-comparison-actions]
            [hyperopen.leaderboard.actions :as leaderboard-actions]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.referrals.actions :as referrals-actions]
            [hyperopen.router :as router]
            [hyperopen.staking.actions :as staking-actions]
            [hyperopen.subaccounts.actions :as subaccounts-actions]
            [hyperopen.vaults.infrastructure.routes :as vault-routes]))

(defn current-route-path
  [state]
  (router/normalize-path (or (get-in state [:router :path])
                             "/trade")))

(defn spot-catalog-route?
  "Routes that render spot instruments whose readable names or wire token ids
   only resolve from the full, spot-inclusive asset-selector market catalog.

   Trade and portfolio qualify because the account-info panel (open orders /
   positions / balances / trade history) lists spot coins that would otherwise
   render as raw provider symbols like `@230` instead of USDH.

   Sub-accounts qualifies for a different reason: the Send Tokens dialog must
   name a spot token on the wire as `NAME:0x<hash>`, and spotMeta is the only
   source that joins a balance row's numeric token index to that id. Without it
   every non-USDC token in the dropdown renders as \"unavailable\" and cannot be
   sent."
  [route]
  (or (router/trade-route? route)
      (portfolio-routes/portfolio-route? route)
      (subaccounts-actions/subaccounts-route? route)))

(defn spot-catalog-markets-needed?
  "True when `route` needs the full (spot-inclusive) catalog but its spot
   metadata has not loaded.

   Gated on `[:spot :meta]` — the data actually needed — rather than on
   `[:asset-selector :phase]`. `begin-asset-selector-load` advances the phase to
   `:full` *before* the request is issued and `apply-asset-selector-error` never
   rolls it back, so a rejected load (an `/info` rate limit on spotMeta, say)
   would otherwise latch the phase at `:full` and make every later demand path a
   permanent no-op for the rest of the page's life.

   `:loading?` is set in the same synchronous `swap!` as the phase, so it still
   dedupes the route-change bucket against the post-render idle bucket, while
   leaving a failed load retryable on the next route change."
  [state route]
  (and (spot-catalog-route? route)
       (nil? (get-in state [:spot :meta]))
       (not (get-in state [:asset-selector :loading?]))))

(defn current-route-refresh-effects
  [state new-address]
  (let [route (current-route-path state)]
    (cond-> (into []
                  (concat
                   (cond
                     (leaderboard-actions/leaderboard-route? route)
                     [[:actions/load-leaderboard-route route]]

                     (vault-routes/vault-route? route)
                     [[:actions/load-vault-route route]]

                     (funding-comparison-actions/funding-comparison-route? route)
                     [[:actions/load-funding-comparison-route route]]

                     (staking-actions/staking-route? route)
                     [[:actions/load-staking-route route]]

                     (referrals-actions/referrals-route? route)
                     [[:actions/load-referrals-route route]]

                     (api-wallets-actions/api-wallet-route? route)
                     [[:actions/load-api-wallet-route route]]

                     (subaccounts-actions/subaccounts-route? route)
                     [[:actions/load-subaccounts-route route]]

                     (portfolio-routes/portfolio-optimize-route? route)
                     [[:actions/load-portfolio-optimizer-route route]]

                     :else [])
                   (when (and (portfolio-routes/portfolio-route? route)
                              (seq (portfolio-actions/selected-portfolio-vault-benchmark-addresses state)))
                     [[:actions/load-vault-route route]])))
      (and new-address
           (portfolio-routes/portfolio-route? route)
           (not (portfolio-routes/portfolio-optimize-route? route)))
      (conj [:actions/select-portfolio-chart-tab
             (get-in state [:portfolio-ui :chart-tab])])

      (and new-address
           (not (subaccounts-actions/subaccounts-route? route)))
      (conj [:effects/api-load-subaccounts]))))
