(ns hyperopen.portfolio.optimizer.application.universe-candidates-vault-ordering-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]))

(defn- reset-universe-candidates-cache-fixture
  [f]
  (universe-candidates/reset-universe-candidates-cache!)
  (f)
  (universe-candidates/reset-universe-candidates-cache!))

(use-fixtures :each reset-universe-candidates-cache-fixture)

(defn- market-keys
  [markets]
  (mapv :key markets))

(deftest candidate-markets-sorts-vault-search-results-by-tvl-before-exact-name-test
  (let [exact-hlp-address "0x4444444444444444444444444444444444444444"
        large-hlp-address "0x5555555555555555555555555555555555555555"
        mid-hlp-address "0x6666666666666666666666666666666666666666"
        state {:asset-selector {:markets []}
               :vaults {:merged-index-rows [{:name "HLP"
                                             :vault-address exact-hlp-address
                                             :relationship {:type :normal}
                                             :tvl 0}
                                            {:name "Hyperliquid HLP Provider"
                                             :vault-address large-hlp-address
                                             :relationship {:type :normal}
                                             :tvl 397000000}
                                            {:name "HLP Rule"
                                             :vault-address mid-hlp-address
                                             :relationship {:type :normal}
                                             :tvl 5}]}}
        candidates (universe-candidates/candidate-markets state [] "hlp")]
    (is (= [(str "vault:" large-hlp-address)
            (str "vault:" mid-hlp-address)
            (str "vault:" exact-hlp-address)]
           (market-keys candidates)))))
