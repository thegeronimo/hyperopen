(ns hyperopen.vol-range.preview-gate-test
  "Regression: the A0 preview capability gates the whole feature (HO-SPEC-014
  1.2, 15.1, 15.2). With it off, ordinary Scale is exactly what main renders:
  no mode control, a persisted :volatility-range reads as :price-range, the
  ticket is hidden and submit-policy does not treat the form as preview-only."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.config :as config]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.vol-range-draft :as draft]
            [hyperopen.vol-range.actions :as actions]
            [hyperopen.vol-range.application.projections :as projections]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.state-fixtures :as sf]
            [hyperopen.views.trade.order-form-component-primitives :as primitives]
            [hyperopen.views.trade.order-form-type-extensions :as type-extensions]
            [hyperopen.views.trade.order-form-vm :as vm]))

(defn- off [f] (with-redefs [config/vol-range-preview-enabled? (fn [] false)] (f)))

(defn- tree-roles [node]
  (cond (map? node) (when-let [r (:data-role node)] [r])
        (coll? node) (mapcat tree-roles node)
        :else []))

(deftest test-builds-turn-the-preview-on-and-the-flag-is-injectable-test
  (is (true? (contracts/preview-enabled?)) "test builds enable the preview via :closure-defines")
  (off #(is (false? (contracts/preview-enabled?)))))

(deftest persisted-volatility-mode-reads-as-price-range-when-off-test
  (let [form (:order-form (sf/base-state))]
    (is (= :volatility-range (draft/scale-mode form)))
    (off #(is (= :price-range (draft/scale-mode form))))))

(deftest scale-section-renders-exactly-as-main-when-off-test
  (let [on-start [[:a :start]] on-end [[:a :end]] on-count [[:a :count]] on-skew [[:a :skew]]
        form {:scale {:start "1" :end "2" :count 5 :skew "1.00"}}
        callbacks {:on-set-scale-start on-start :on-set-scale-end on-end
                   :on-set-scale-count on-count :on-set-scale-skew on-skew
                   :on-select-scale-mode (fn [_] [[:a :mode]])
                   :scale-mode :price-range
                   :vol-range-eligible? true}
        main [:div {:class ["space-y-2"]}
              (primitives/section-label "Scale")
              (primitives/input "1" on-start :placeholder "Start price")
              (primitives/input "2" on-end :placeholder "End price")
              [:div {:class ["grid" "grid-cols-2" "gap-2"]}
               (primitives/inline-labeled-scale-input "Total Orders" 5 on-count)
               (primitives/inline-labeled-scale-input "Size Skew" "1.00" on-skew)]]
        render #(first (type-extensions/render-order-type-sections :scale form callbacks))]
    (testing "with the preview on the mode control is present"
      (is (some #{"vol-range-mode-control"} (tree-roles (render)))))
    (testing "with the preview off the Scale section is exactly the main markup"
      (off #(do (is (= main (render)))
                (is (not-any? #{"vol-range-mode-control"} (tree-roles (render)))))))
    (testing "a persisted volatility mode cannot hide the price fields when off"
      (off #(is (= main (first (type-extensions/render-order-type-sections
                                :scale form (assoc callbacks :scale-mode :volatility-range)))))))))

(deftest ticket-and-vm-hidden-when-off-test
  (with-redefs [hyperopen.vol-range.application.snapshots/fee-snapshot sf/with-synthetic-fees]
    (let [state (sf/base-state)]
      (is (true? (projections/ticket-visible? state)))
      (off (fn []
             (is (false? (boolean (projections/ticket-visible? state))))
             (let [view-model (vm/order-form-vm-at state sf/now-ms)]
               (is (= :price-range (:scale-mode view-model)))
               (is (nil? (:vol-range view-model)))
               (is (false? (get-in view-model [:controls :show-vol-range?])))
               (is (true? (get-in view-model [:controls :show-scale-preview?])))
               (is (true? (get-in view-model [:controls :show-post-only?])))))))))

(deftest submit-policy-ignores-persisted-volatility-mode-when-off-test
  (let [state (-> (sf/base-state)
                  (assoc-in [:order-form :size] "10")
                  (assoc-in [:order-form :scale :start] "70")
                  (assoc-in [:order-form :scale :end] "75"))
        form (trading/order-form-draft state)]
    (is (= :vol-range-preview-only (:reason (trading/submit-policy state form {:mode :view}))))
    (off #(is (not= :vol-range-preview-only (:reason (trading/submit-policy state form {:mode :view})))))))

(deftest select-volatility-range-is-a-no-op-when-off-test
  (off (fn []
         (is (= [] (actions/select-scale-mode (sf/base-state) :volatility-range sf/now-ms)))
         (is (= [:effects/save-many]
                (mapv first (actions/select-scale-mode (sf/base-state) :price-range sf/now-ms)))))))
