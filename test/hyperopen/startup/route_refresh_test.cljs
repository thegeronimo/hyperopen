(ns hyperopen.startup.route-refresh-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.startup.route-refresh :as route-refresh]))

(deftest current-route-path-defaults-to-trade-when-route-is-missing-test
  (is (= "/trade"
         (route-refresh/current-route-path {}))))

(deftest current-route-refresh-effects-target-route-and-global-account-state-test
  (testing "trade route refreshes global subaccount header state"
    (is (= [[:effects/api-load-subaccounts]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/trade"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "leaderboard route refreshes only leaderboard"
    (is (= [[:actions/load-leaderboard-route "/leaderboard"]
            [:effects/api-load-subaccounts]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/leaderboard"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "vault detail route refreshes only vaults"
    (is (= [[:actions/load-vault-route "/vaults/0xabc"]
            [:effects/api-load-subaccounts]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/vaults/0xabc"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "funding comparison route refreshes only funding comparison"
    (is (= [[:actions/load-funding-comparison-route "/funding-comparison"]
            [:effects/api-load-subaccounts]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/funding-comparison"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "staking route refreshes only staking"
    (is (= [[:actions/load-staking-route "/staking"]
            [:effects/api-load-subaccounts]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/staking"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "referrals route refreshes only referrals"
    (is (= [[:actions/load-referrals-route "/join/ABC123"]
            [:effects/api-load-subaccounts]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/join/ABC123"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "api route refreshes only api wallets"
    (is (= [[:actions/load-api-wallet-route "/api"]
            [:effects/api-load-subaccounts]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/api"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "subaccounts route refreshes only subaccounts"
    (is (= [[:actions/load-subaccounts-route "/subAccounts"]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/subAccounts"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "optimizer scenario route refreshes only optimizer scenario state"
    (is (= [[:actions/load-portfolio-optimizer-route "/portfolio/optimize/scn_route"]
            [:effects/api-load-subaccounts]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/portfolio/optimize/scn_route"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))))

(deftest current-route-refresh-effects-preserve-portfolio-chart-bootstrap-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
    (is (= [[:actions/select-portfolio-chart-tab :returns]
            [:effects/api-load-subaccounts]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/portfolio"}
             :portfolio-ui {:chart-tab :returns}}
            address)))
    (is (= [[:actions/select-portfolio-chart-tab :returns]
            [:effects/api-load-subaccounts]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/portfolio/trader/0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
             :portfolio-ui {:chart-tab :returns}}
            address)))
    (is (= []
           (route-refresh/current-route-refresh-effects
            {:router {:path "/portfolio"}
             :portfolio-ui {:chart-tab :returns}}
            nil)))
    (is (= [[:actions/load-portfolio-optimizer-route "/portfolio/optimize/scn_01"]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/portfolio/optimize/scn_01"}
             :portfolio-ui {:chart-tab :returns}}
            nil)))))

(deftest spot-catalog-route?-test
  (testing "routes that render spot instruments need the spot-inclusive catalog"
    (is (true? (boolean (route-refresh/spot-catalog-route? "/trade"))))
    (is (true? (boolean (route-refresh/spot-catalog-route? "/portfolio"))))
    (is (true? (boolean (route-refresh/spot-catalog-route?
                         "/portfolio/trader/0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")))))
  (testing "sub-accounts needs it for Send Tokens wire token ids"
    ;; Regression: the Send Tokens dialog resolves a spot balance's numeric token
    ;; index to a `NAME:0x<hash>` wire token id through spotMeta. Without the
    ;; catalog every non-USDC token rendered "unavailable" and could not be sent.
    (is (true? (boolean (route-refresh/spot-catalog-route? "/subAccounts")))))
  (testing "routes that render no spot instruments do not"
    (is (not (route-refresh/spot-catalog-route? "/leaderboard")))
    (is (not (route-refresh/spot-catalog-route? "/vaults/0xabc")))
    (is (not (route-refresh/spot-catalog-route? "/staking")))))

(deftest spot-catalog-markets-needed?-test
  (testing "spot-catalog routes need the full catalog while spot meta is missing"
    ;; Regression: bootstrap builds a perp-only catalog, so spot open orders
    ;; (coin like \"@230\") would otherwise render as the raw provider symbol
    ;; instead of a readable name (USDH).
    (is (true? (route-refresh/spot-catalog-markets-needed? {} "/trade")))
    (is (true? (route-refresh/spot-catalog-markets-needed? {} "/portfolio")))
    (is (true? (route-refresh/spot-catalog-markets-needed?
                {}
                "/portfolio/trader/0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")))
    (is (true? (route-refresh/spot-catalog-markets-needed? {} "/subAccounts"))))
  (testing "no-op once spot metadata has loaded"
    (is (false? (route-refresh/spot-catalog-markets-needed?
                 {:spot {:meta {:tokens []}}} "/trade")))
    (is (false? (route-refresh/spot-catalog-markets-needed?
                 {:spot {:meta {:tokens []}}} "/subAccounts"))))
  (testing "no-op while a catalog load is already in flight"
    ;; `begin-asset-selector-load` sets :loading? in the same synchronous swap!
    ;; as the phase, so this dedupes the route-change bucket against the
    ;; post-render :idle bucket.
    (is (false? (route-refresh/spot-catalog-markets-needed?
                 {:asset-selector {:loading? true}} "/trade")))
    (is (false? (route-refresh/spot-catalog-markets-needed?
                 {:asset-selector {:loading? true}} "/subAccounts"))))
  (testing "a failed load stays retryable instead of latching off"
    ;; Regression: the gate used to read `(not= :full phase)`. The phase is
    ;; advanced before the request and `apply-asset-selector-error` never rolls
    ;; it back, so one rejected load (an /info rate limit, say) made every later
    ;; demand path a permanent no-op for the rest of the page's life.
    (is (true? (route-refresh/spot-catalog-markets-needed?
                {:asset-selector {:phase :full :loading? false}
                 :spot {:meta nil}}
                "/subAccounts")))
    (is (true? (route-refresh/spot-catalog-markets-needed?
                {:asset-selector {:phase :full :loading? false}
                 :spot {:meta nil}}
                "/trade"))))
  (testing "routes without spot instruments never request the full catalog from here"
    (is (false? (route-refresh/spot-catalog-markets-needed? {} "/leaderboard")))
    (is (false? (route-refresh/spot-catalog-markets-needed? {} "/staking")))))

(deftest current-route-refresh-effects-loads-portfolio-vault-benchmark-support-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        benchmark-address "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303"
        state {:router {:path "/portfolio"}
               :portfolio-ui {:chart-tab :returns
                              :returns-benchmark-coins ["BTC"
                                                        "HYPE"
                                                        (str "vault:" benchmark-address)]}}]
    (is (= [[:actions/load-vault-route "/portfolio"]]
           (route-refresh/current-route-refresh-effects state nil)))
    (is (= [[:actions/load-vault-route "/portfolio"]
            [:actions/select-portfolio-chart-tab :returns]
            [:effects/api-load-subaccounts]]
           (route-refresh/current-route-refresh-effects state address)))))
