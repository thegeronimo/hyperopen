(ns hyperopen.views.trade-view.hyperevm-line-repaint-test
  "/trade keeps raw HyperEVM state out of the Account Equity panel slice.
   Its existing line model and its slim grouped-total summary arrive from
   account-surface exports computed against full state. The panel repaints
   when shown funds change, and not when a poll only rewrites timestamps or
   gas price over the same balances."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.account-equity.hyperevm-line :as hyperevm-line]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections.hyperevm-funds :as hyperevm-funds]
            [hyperopen.views.trade-view :as trade-view]
            [hyperopen.views.trade.test-support :as support]))

(use-fixtures :each
  (fn [f]
    (hyperevm-funds/reset-hyperevm-funds-cache!)
    (derived-cache/reset-derived-cache!)
    (account-equity-view/reset-account-equity-metrics-cache!)
    (f)
    (hyperevm-funds/reset-hyperevm-funds-cache!)
    (derived-cache/reset-derived-cache!)
    (account-equity-view/reset-account-equity-metrics-cache!)))

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

(defn- total-exports
  [metrics projection-calls]
  {:account-equity-metrics account-equity-view/account-equity-metrics
   ;; The export sees full state. /trade must carry only this stable summary
   ;; into its memoized equity slice, never the raw :hyperevm subtree.
   :account-equity-hyperevm-funds
   (fn [state]
     (swap! projection-calls conj state)
     (select-keys (hyperevm-funds/hyperevm-funds state)
                  [:status :address :usd :unpriced-count]))
   :hyperevm-line-model hyperevm-line/hyperevm-line-model
   :account-equity-view (fn [_state opts]
                          (swap! metrics conj (:metrics opts))
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

(defn- equity-state
  [native-wei]
  (let [evm (fixture/state)]
    (-> (base)
        (assoc :webdata2 (:webdata2 evm)
               :vaults {:user-equities []
                        :user-equities-for-address fixture/owner
                        :loading {:user-equities? false}
                        :errors {:user-equities nil}})
        (read-at 100 native-wei fixture/gas-price-wei))))

(defn- close?
  [actual expected]
  (< (js/Math.abs (- actual expected)) 1e-9))

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

(deftest desktop-grouped-total-reacts-to-an-account-owned-hyperevm-balance-through-the-cached-slice-test
  (support/with-viewport-width
    1280
    (fn []
      (let [metrics (atom [])
            projection-calls (atom [])
            first-read (equity-state "12500000000000000000")
            same-value (read-at first-read 200 "12500000000000000000" "150000000")
            changed-balance (read-at same-value 300 "13500000000000000000" "150000000")
            switched-account (assoc changed-balance
                                   :account-context {:spectate-mode {:active? true
                                                                     :address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}})]
        (support/with-account-surface-exports
          (total-exports metrics projection-calls)
          (fn []
            (trade-view/trade-view first-read)
            (trade-view/trade-view same-value)
            ;; Gas/timestamp churn did not change the exported summary, so it
            ;; must not repaint the memoized Account Equity surface.
            (is (= 1 (count @metrics)))
            (trade-view/trade-view changed-balance)
            (trade-view/trade-view switched-account)
            (let [[first-total changed-total switched-total] @metrics]
              (is (= 3 (count @metrics)))
              (is (= 4 (count @projection-calls)))
              (is (every? #(contains? % :hyperevm) @projection-calls)
                  "the lazy summary export receives full state")
              (is (number? (:hyperevm-equity first-total)))
              (is (number? (:hyperevm-equity changed-total)))
              (is (= (:account-value-display first-total)
                     (:account-value-display changed-total)))
              (is (not= (:hyperevm-equity first-total)
                        (:hyperevm-equity changed-total)))
              (when (and (number? (:hyperevm-equity first-total))
                         (number? (:hyperevm-equity changed-total)))
                (is (close? (- (:total-account-value-display changed-total)
                               (:total-account-value-display first-total))
                            (- (:hyperevm-equity changed-total)
                               (:hyperevm-equity first-total)))))
              ;; A's last good response must not remain in the grouped total
              ;; when B becomes the effective account without a B read.
              (is (nil? (:hyperevm-equity switched-total)))
              (is (nil? (:total-account-value-display switched-total))))))))))
