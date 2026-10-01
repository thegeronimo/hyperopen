(ns hyperopen.views.trade-view.hyperevm-line-repaint-test
  "/trade renders the Account Equity panel memoized on a slice with no
   HyperEVM keys, so its HyperEVM line arrives as a model precomputed from
   the full state and passed in the panel's opts, on desktop and on the
   mobile account surface. The panel repaints when the line would change
   and not when a poll rewrites timestamps, status or the gas price over
   the same balances."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.views.account-equity.hyperevm-line :as hyperevm-line]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections.hyperevm-funds :as hyperevm-funds]
            [hyperopen.views.trade-view :as trade-view]
            [hyperopen.views.trade.test-support :as support]))

(use-fixtures :each
  (fn [f]
    (hyperevm-funds/reset-hyperevm-funds-cache!)
    (derived-cache/reset-derived-cache!)
    (f)
    (hyperevm-funds/reset-hyperevm-funds-cache!)
    (derived-cache/reset-derived-cache!)))

(defn- base
  []
  (let [evm (fixture/state)]
    (-> (support/active-asset-state)
        (assoc :wallet (:wallet evm)
               :spot (:spot evm)
               :router {:path "/trade"}
               :hyperevm (assoc (:hyperevm evm) :balances {:by-address {}}))
        ;; Spot markets price HYPE, so a native HYPE change moves the line.
        (update-in [:asset-selector :market-by-key]
                   merge (get-in evm [:asset-selector :market-by-key])))))

(defn- counting-exports
  [calls models]
  {:account-equity-metrics (fn [_state] {:account-value-display 1})
   :hyperevm-line-model hyperevm-line/hyperevm-line-model
   :account-equity-view (fn [_state opts]
                          (swap! calls inc)
                          (swap! models conj (:hyperevm-line opts))
                          [:div {:data-role "stub-account-equity"}])
   :funding-actions-view (fn [& _args]
                           [:div {:data-role "stub-funding-actions"}])})

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

(deftest desktop-equity-panel-repaints-only-when-the-hyperevm-line-changes-test
  (support/with-viewport-width
    1280
    (fn []
      (let [calls (atom 0)
            models (atom [])
            unread (base)
            loading (hyperevm-balances/apply-loading unread {:addresses [fixture/owner]
                                                             :requested-at-ms 100})
            first-read (read-at unread 100 "12500000000000000000" "100000000")
            refreshing (hyperevm-balances/apply-loading first-read {:addresses [fixture/owner]
                                                                    :requested-at-ms 200})
            same-again (read-at first-read 200 "12500000000000000000" "120000000")
            spent-gas (read-at same-again 300 "12490000000000000000" "120000000")]
        (support/with-account-surface-exports
          (counting-exports calls models)
          (fn []
            (trade-view/trade-view unread)
            (is (= 1 @calls))
            (is (false? (:visible? (last @models))) "nothing read yet: the line is hidden")
            (trade-view/trade-view loading)
            (is (= 1 @calls) "a first read in flight changes nothing shown")
            (trade-view/trade-view first-read)
            (is (= 2 @calls) "the first read brings the line")
            (is (true? (:visible? (last @models))))
            (is (= hyperevm-line/move-action (:move-action (last @models))))
            (trade-view/trade-view refreshing)
            (trade-view/trade-view same-again)
            (is (= 2 @calls) "a poll over the same balances, gas price included, does not repaint")
            (trade-view/trade-view spent-gas)
            (is (= 3 @calls) "a balance change repaints")))))))

(deftest the-mobile-account-surface-gets-the-line-model-test
  (support/with-viewport-width
    430
    (fn []
      (let [calls (atom 0)
            models (atom [])
            state (-> (base)
                      (read-at 100 "12500000000000000000" "100000000")
                      (support/with-mobile-surface :account))]
        (support/with-account-surface-exports
          (counting-exports calls models)
          (fn []
            (trade-view/trade-view state)
            (is (= 1 @calls))
            (is (true? (:visible? (last @models))))
            (is (= (hyperevm-line/hyperevm-line-model state) (last @models)))))))))
