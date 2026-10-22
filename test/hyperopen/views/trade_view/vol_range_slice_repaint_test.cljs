(ns hyperopen.views.trade-view.vol-range-slice-repaint-test
  "The order-form panel is memoized on a fixed slice of app-db. These tests pin
  that Vol Range draft edits and the ACTIVE coin's daily candles reach the slice
  (so the ticket repaints) and that another coin's candles do not."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade-view :as trade-view]
            [hyperopen.vol-range.state-fixtures :as sf]))

(def ^:private slice @#'trade-view/order-form-view-state)

(defn- state [] (sf/base-state))

(deftest slice-changes-with-vol-range-draft-and-active-candles-test
  (let [base (state)
        coin (:active-asset base)]
    (is (not= (slice base)
              (slice (assoc-in base [:vol-range :ui :review-open?] true)))
        "[:vol-range ...] changes must repaint the order form")
    (is (not= (slice base)
              (slice (assoc-in base [:candles coin :1d 0 :h] "999")))
        "the active coin's 1d candles must repaint the order form")
    (is (not= (slice base)
              (slice (assoc-in base [:order-form :scale :vol-range :buy :quantity] "41"))))))

(deftest slice-ignores-other-coin-candles-and-other-intervals-test
  (let [base (state)]
    (is (= (slice base)
           (slice (assoc-in base [:candles "OTHER" :1d] [{:t 1 :h "1" :l "1"}])))
        "another coin's candles must not repaint")
    (is (= (slice base)
           (slice (assoc-in base [:candles (:active-asset base) :1h] [{:t 1}])))
        "other intervals of the active coin must not repaint")))

(deftest slice-omits-vol-range-inputs-in-price-range-mode-test
  (let [base (assoc-in (state) [:order-form :scale :mode] :price-range)]
    (is (nil? (:candles (slice base))))
    (is (= (slice base)
           (slice (assoc-in base [:candles (:active-asset base) :1d 0 :h] "999"))))
    (is (= (slice base)
           (slice (assoc-in base [:portfolio :user-fees] {:userAddRate "0.0001"})))
        "the legacy order form keeps its pre-Vol-Range slice (VR-01)")
    (is (nil? (:account-context (slice base))))))

(deftest slice-carries-fee-quote-and-account-context-in-volatility-mode-test
  (let [base (state)]
    (is (not= (slice base)
              (slice (assoc-in base [:portfolio :user-fees] {:userAddRate "0.0001"}))))
    (is (not= (slice base)
              (slice (assoc-in base [:account-context :spectate-mode :active?] true)))
        "spectate state must reach the ticket so it renders read-only")
    (is (not= (slice base)
              (slice (assoc-in base [:account-context :trading-account :subaccount-address] "0xabc")))
        "a selected subaccount must reach the ticket's balance snapshot")))

(deftest slice-ignores-unrelated-orders-slices-and-tracks-open-orders-test
  (let [base (state)]
    (is (= (slice base)
           (slice (assoc-in base [:orders :fills] [{:oid 1}])))
        "fills must not repaint")
    (is (= (slice base)
           (slice (assoc-in base [:orders :order-history] [{:oid 2}]))))
    (is (not= (slice base)
              (slice (assoc-in base [:orders :open-orders] [{:oid 3}]))))
    (let [pr (assoc-in base [:order-form :scale :mode] :price-range)]
      (is (= (slice pr) (slice (assoc-in pr [:orders :open-orders] [{:oid 3}])))
          "no order slices in ordinary modes"))))
