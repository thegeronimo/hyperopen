(ns hyperopen.views.trade.order-form-view.feedback-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form.test-support :refer [base-state
                                                                   collect-strings
                                                                   find-first-node]]
            [hyperopen.views.trade.order-form-feedback :as feedback]
            [hyperopen.views.trade.order-form-view :as view]
            [hyperopen.views.trade.order-form-vm :as order-form-vm]))

(deftest twap-preview-formats-runtime-and-suborder-details-test
  (let [state (base-state {:type :twap
                           :size "6"
                           :twap {:minutes "90"}})
        preview (feedback/twap-preview state
                                       (get state :order-form)
                                       "BTC")]
    (is (= "1h 30m" (:runtime preview)))
    ;; 6 BTC at the fixture's best ask of 101 is $606 of notional. The venue will not let
    ;; a clip fall below $10, so it works this as 60 clips rather than the 181 a flat 30s
    ;; cadence would imply, spacing them ~92s apart.
    (is (= "60" (:order-count preview)))
    (is (= "~2m" (:frequency preview)))
    (is (re-find #" BTC$" (:size-per-suborder preview)))))

(deftest twap-preview-uses-the-thirty-second-floor-for-large-orders-test
  (let [state (base-state {:type :twap
                           :size "600"
                           :twap {:minutes "90"}})
        preview (feedback/twap-preview state (get state :order-form) "BTC")]
    ;; $60,600 over 90 minutes clears the $10 clip floor at every 30s slot, so the
    ;; spacing floor binds and the count is the classic 1 + 2*minutes.
    (is (= "181" (:order-count preview)))
    (is (= "30s" (:frequency preview)))))

(deftest twap-preview-reports-a-bound-when-the-notional-is-unknown-test
  (let [state (base-state {:type :twap
                           :size ""
                           :twap {:minutes "1440"}})
        preview (feedback/twap-preview state (get state :order-form) "BTC")]
    ;; With no size the venue count cannot be derived, so the ceiling the 30-second floor
    ;; allows is shown as a bound rather than presented as the schedule.
    (is (= "up to 2881" (:order-count preview)))
    (is (= "30s+" (:frequency preview)))
    (is (= "--" (:size-per-suborder preview)))))

(deftest twap-preview-runtime-label-includes-days-test
  (let [state (base-state {:type :twap
                           :size "6"
                           :twap {:days "2" :hours "3" :minutes "4"}})
        preview (feedback/twap-preview state (get state :order-form) "BTC")]
    (is (= "2d 3h 4m" (:runtime preview)))))

(deftest spectate-stop-affordance-renders-stop-action-test
  (let [affordance (feedback/spectate-mode-stop-affordance)
        stop-button (find-first-node affordance
                                     (fn [node]
                                       (= "order-form-spectate-mode-stop-button"
                                          (get-in node [1 :data-role]))))
        strings (set (collect-strings affordance))]
    (is (contains? strings "Stop Spectate Mode"))
    (is (= [[:actions/stop-spectate-mode]]
           (get-in stop-button [1 :on :click])))))

(deftest tpsl-panel-model-reflects-open-unit-dropdown-state-test
  (let [state (base-state {:type :limit
                           :size "1"
                           :price "100"}
                          {:tpsl-panel-open? true
                           :tpsl-unit-dropdown-open? true})
        vm (order-form-vm/order-form-vm state)
        panel (feedback/tpsl-panel-model state
                                         (get state :order-form)
                                         (get-in state [:order-form :side])
                                         (:ui-leverage vm)
                                         (:controls vm))]
    (is (= true (:unit-dropdown-open? panel)))
    (is (= :usd (:unit panel)))
    (is (contains? panel :tp-offset))
    (is (contains? panel :sl-offset))))

(deftest order-form-skips-hidden-tpsl-panel-model-test
  (let [calls (atom 0)
        original feedback/tpsl-panel-model]
    (with-redefs [feedback/tpsl-panel-model (fn [& args]
                                              (swap! calls inc)
                                              (apply original args))]
      (view/order-form-view (base-state {:type :limit :size "1" :price "100"}))
      (is (zero? @calls))
      (view/order-form-view (base-state {:type :limit :size "1" :price "100"}
                                        {:tpsl-panel-open? true}))
      (is (= 1 @calls)))))

(deftest order-form-skips-twap-preview-unless-twap-mode-is-active-test
  (let [calls (atom 0)
        original feedback/twap-preview]
    (with-redefs [feedback/twap-preview (fn [& args]
                                          (swap! calls inc)
                                          (apply original args))]
      (view/order-form-view (base-state {:type :limit}))
      (is (zero? @calls))
      (view/order-form-view (base-state {:type :twap
                                         :size "6"
                                         :twap {:minutes "90"}}))
      (is (= 1 @calls)))))

(deftest order-form-builds-twap-preview-for-registry-driven-twap-sections-test
  (let [calls (atom 0)
        original feedback/twap-preview]
    (with-redefs [order-form-vm/order-type-sections (fn [_order-type] [:twap])
                  feedback/twap-preview (fn [& args]
                                          (swap! calls inc)
                                          (apply original args))]
      (view/order-form-view (base-state {:type :stop-limit
                                         :size "6"
                                         :twap {:minutes "90"}}))
      (is (= 1 @calls)))))
