(ns hyperopen.startup.route-refresh-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.startup.route-refresh :as route-refresh]))

(deftest current-route-path-defaults-to-trade-when-route-is-missing-test
  (is (= "/trade"
         (route-refresh/current-route-path {}))))

(deftest current-route-refresh-effects-target-only-the-current-route-test
  (testing "trade route stays quiet"
    (is (= []
           (route-refresh/current-route-refresh-effects
            {:router {:path "/trade"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "leaderboard route refreshes only leaderboard"
    (is (= [[:actions/load-leaderboard-route "/leaderboard"]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/leaderboard"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "vault detail route refreshes only vaults"
    (is (= [[:actions/load-vault-route "/vaults/0xabc"]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/vaults/0xabc"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "funding comparison route refreshes only funding comparison"
    (is (= [[:actions/load-funding-comparison-route "/funding-comparison"]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/funding-comparison"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "staking route refreshes only staking"
    (is (= [[:actions/load-staking-route "/staking"]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/staking"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "api route refreshes only api wallets"
    (is (= [[:actions/load-api-wallet-route "/api"]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/api"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
  (testing "optimizer scenario route refreshes only optimizer scenario state"
    (is (= [[:actions/load-portfolio-optimizer-route "/portfolio/optimize/scn_route"]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/portfolio/optimize/scn_route"}}
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))))

(deftest current-route-refresh-effects-preserve-portfolio-chart-bootstrap-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
    (is (= [[:actions/select-portfolio-chart-tab :returns]]
           (route-refresh/current-route-refresh-effects
            {:router {:path "/portfolio"}
             :portfolio-ui {:chart-tab :returns}}
            address)))
    (is (= [[:actions/select-portfolio-chart-tab :returns]]
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
            [:actions/select-portfolio-chart-tab :returns]]
           (route-refresh/current-route-refresh-effects state address)))))
