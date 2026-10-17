(ns hyperopen.trading.orderbook-price-pick-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.order.actions :as order-actions]
            [hyperopen.state.trading :as trading]
            [hyperopen.state.trading.test-support :as support]
            [hyperopen.trading.orderbook-price-pick :as price-pick]))

(defn- state-with-form [form-overrides ui-overrides]
  (assoc support/base-state
         :order-form (merge (trading/default-order-form) form-overrides)
         :order-form-ui ui-overrides))

(defn- after [state transition]
  (support/apply-order-form-transition state transition))

(deftest market-form-switches-to-limit-at-the-clicked-price-test
  (let [state (state-with-form {:type :market :price ""} {:entry-mode :market})
        next-state (after state (price-pick/set-order-price-from-orderbook state "101"))
        form (trading/order-form-draft next-state)]
    (is (= :limit (:entry-mode form)))
    (is (= :limit (:type form)))
    (is (= "101" (:price form)))))

(deftest limit-form-keeps-its-mode-and-takes-the-price-test
  (let [state (state-with-form {:type :limit :price "95"} {:entry-mode :limit})
        form (trading/order-form-draft
              (after state (price-pick/set-order-price-from-orderbook state "99")))]
    (is (= :limit (:entry-mode form)))
    (is (= "99" (:price form)))))

(deftest pro-order-type-is-kept-test
  (let [state (state-with-form {:type :stop-limit :price "95"} {:entry-mode :pro})
        form (trading/order-form-draft
              (after state (price-pick/set-order-price-from-orderbook state "99")))]
    (is (= :pro (:entry-mode form)))
    (is (= :stop-limit (:type form)))
    (is (= "99" (:price form)))))

(deftest raw-price-is-not-reinterpreted-in-the-users-locale-test
  ;; Typed input in de-DE reads "." as a thousands separator, so "1.234" would become
  ;; 1234. A clicked level carries the venue's canonical decimal and is kept verbatim.
  (let [state (-> (state-with-form {:type :limit :price ""} {:entry-mode :limit})
                  (assoc-in [:ui :locale] "de-DE"))
        form (trading/order-form-draft
              (after state (price-pick/set-order-price-from-orderbook state "1.234")))]
    (is (= "1.234" (:price form)))))

(deftest trailing-fraction-zeros-are-dropped-test
  (let [state (state-with-form {:type :limit :price ""} {:entry-mode :limit})
        price-for (fn [px]
                    (:price (trading/order-form-draft
                             (after state (price-pick/set-order-price-from-orderbook state px)))))]
    (is (= "82342" (price-for "82342.0")))
    (is (= "0.01234" (price-for "0.012340")))
    (is (= "3876.5" (price-for "3876.5")))
    (is (= "100" (price-for "100")))))

(deftest clears-a-stale-submit-error-test
  (let [state (-> (state-with-form {:type :limit :price "95"} {:entry-mode :limit})
                  (assoc :order-form-runtime {:error "Price too far from mark"}))
        transition (price-pick/set-order-price-from-orderbook state "99")]
    (is (nil? (get-in transition [:order-form-runtime :error])))))

(deftest rejects-prices-that-are-not-positive-plain-decimals-test
  (let [state (state-with-form {:type :limit :price "95"} {:entry-mode :limit})]
    (doseq [bad [nil "" "  " "0" "-5" "1,234" "abc" "1e3"]]
      (testing (pr-str bad)
        (is (nil? (price-pick/set-order-price-from-orderbook state bad)))
        (is (= [] (order-actions/set-order-price-from-orderbook state bad)))))))

(deftest action-emits-one-batched-save-test
  (let [state (state-with-form {:type :market :price ""} {:entry-mode :market})
        effects (order-actions/set-order-price-from-orderbook state "101")
        saved (into {} (second (first effects)))]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= "101" (get-in saved [[:order-form] :price])))
    (is (= :limit (get-in saved [[:order-form-ui] :entry-mode])))))
