(ns hyperopen.domain.token-pricing-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.asset-selector.markets :as asset-selector-markets]
            [hyperopen.domain.token-pricing :as token-pricing]
            [hyperopen.views.account-equity.pricing :as view-pricing]))

(def ^:private market-by-key
  {"spot:HYPE" {:market-type :spot :base "HYPE" :quote "USDC" :mark "44.68"}
   "spot:USDX" {:market-type :spot :base "USDC" :quote "USDX" :mark "0.5"}})

(deftest token-price-usd-resolves-rows-markets-and-stables-test
  (is (= 44.68 (token-pricing/token-price-usd {} market-by-key "hype")))
  (is (= 2 (token-pricing/token-price-usd {} market-by-key "USDX"))
      "an inverted USDC market converts through 1/mark")
  (is (= 1 (token-pricing/token-price-usd {} {} "USDH")))
  (is (= 0.5 (token-pricing/token-price-usd
              (token-pricing/balance-rows-by-token [{:coin "PURR" :total-balance 10 :usdc-value 5}])
              {}
              "PURR")))
  (is (nil? (token-pricing/token-price-usd {} {} "PURR")) "unpriced is nil, never 0"))

(deftest market-token-price-usd-reads-the-state-catalogue-test
  (is (= 44.68 (token-pricing/market-token-price-usd
                {:asset-selector {:market-by-key market-by-key}} "HYPE")))
  (is (nil? (token-pricing/market-token-price-usd {} "HYPE"))))

(deftest the-view-namespace-delegates-to-the-domain-resolver-test
  (is (identical? view-pricing/token-price-usd token-pricing/token-price-usd))
  (is (identical? view-pricing/balance-rows-by-token token-pricing/balance-rows-by-token)))

(deftest markets-are-resolved-only-when-needed-test
  ;; `resolve-market-by-coin` can scan the whole catalogue, and the funding
  ;; modal prices USDC on every render.
  (let [scans (atom 0)]
    (with-redefs [asset-selector-markets/resolve-market-by-coin
                  (fn [& _] (swap! scans inc) nil)]
      (is (= 1 (token-pricing/token-price-usd {} market-by-key "USDC")))
      (is (= 1 (token-pricing/market-token-price-usd
                {:asset-selector {:market-by-key market-by-key}} "usdc")))
      (is (= 0.5 (token-pricing/token-price-usd
                  (token-pricing/balance-rows-by-token [{:coin "PURR" :total-balance 10
                                                         :usdc-value 5}])
                  market-by-key
                  "PURR")))
      (is (= 0 @scans) "USDC and a priced balance row never scan the markets")
      (is (= 2 (token-pricing/token-price-usd {} market-by-key "USDX"))
          "other stables still prefer their market")
      (is (nil? (token-pricing/token-price-usd {} market-by-key "PURR")))
      (is (= 1 @scans) "an unpriced token still searches the markets"))))
