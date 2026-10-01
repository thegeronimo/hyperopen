(ns hyperopen.views.trade-view.hyperevm-slice-repaint-test
  "The /trade account panel is memoized on a state slice compared with `=`.
   HyperEVM balances reach it as a projected slice: the panel repaints when a
   read changes what the Balances tab shows, and not when a poll rewrites
   timestamps, status or the gas price over the same balances."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.views.trade-view :as trade-view]
            [hyperopen.views.trade.test-support :as support]))

(defn- base
  []
  (let [evm (fixture/state)]
    (-> (support/active-asset-state)
        (assoc :wallet (:wallet evm)
               :hyperevm (assoc (:hyperevm evm) :balances {:by-address {}}))
        (assoc-in [:spot :meta] (get-in evm [:spot :meta])))))

(defn- counting-account-info-exports
  [calls]
  {:account-info-view (fn [& _args]
                        (swap! calls inc)
                        [:div {:data-role "stub-account-info"}])})

(defn- read-at
  [state requested-at-ms native-wei gas-price-wei]
  (-> state
      (hyperevm-balances/apply-loading {:addresses [fixture/owner]
                                        :requested-at-ms requested-at-ms})
      (hyperevm-balances/apply-success fixture/owner
                                       requested-at-ms
                                       {:native-wei native-wei
                                        :token-units (:token-units fixture/evm-entry)
                                        :gas-price-wei gas-price-wei}
                                       (:token-indexes fixture/evm-entry)
                                       (inc requested-at-ms))))

(deftest account-panel-repaints-only-when-hyperevm-balances-change-test
  (support/with-viewport-width
    1280
    (fn []
      (let [calls (atom 0)
            unread (base)
            loading (hyperevm-balances/apply-loading unread {:addresses [fixture/owner]
                                                             :requested-at-ms 100})
            first-read (read-at unread 100 "12500000000000000000" "100000000")
            refreshing (hyperevm-balances/apply-loading first-read {:addresses [fixture/owner]
                                                                    :requested-at-ms 200})
            same-again (read-at first-read 200 "12500000000000000000" "120000000")
            spent-gas (read-at same-again 300 "12490000000000000000" "120000000")]
        (support/with-account-surface-exports
          (counting-account-info-exports calls)
          (fn []
            (trade-view/trade-view unread)
            (is (= 1 @calls))
            (trade-view/trade-view loading)
            (is (= 1 @calls) "a read in flight changes nothing shown")
            (trade-view/trade-view first-read)
            (is (= 2 @calls) "the first read brings the HyperEVM rows")
            (trade-view/trade-view refreshing)
            (trade-view/trade-view same-again)
            (is (= 2 @calls) "a poll over the same balances, gas price included, does not repaint")
            (trade-view/trade-view spent-gas)
            (is (= 3 @calls) "a balance change repaints")))))))
