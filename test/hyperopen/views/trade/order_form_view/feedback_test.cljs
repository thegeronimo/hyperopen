(ns hyperopen.views.trade.order-form-view.feedback-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form.test-support :refer [base-state
                                                                   collect-strings
                                                                   find-first-node]]
            [hyperopen.views.trade.order-form-feedback :as feedback]
            [hyperopen.views.trade.order-form-view :as view]
            [hyperopen.views.trade.order-form-vm :as order-form-vm]))

(deftest unsupported-market-banner-renders-copy-test
  (let [banner (feedback/unsupported-market-banner "Spot trading is not supported yet.")
        strings (set (collect-strings banner))]
    (is (contains? strings "Spot trading is not supported yet."))))

(deftest twap-preview-formats-runtime-and-suborder-details-test
  (let [state (base-state {:type :twap
                           :size "6"
                           :twap {:minutes "90"}})
        preview (feedback/twap-preview state
                                       (get state :order-form)
                                       "BTC")]
    (is (= "1h 30m" (:runtime preview)))
    (is (= "30s" (:frequency preview)))
    (is (not= "--" (:order-count preview)))
    (is (pos? (js/parseFloat (:order-count preview))))
    (is (re-find #" BTC$" (:size-per-suborder preview)))))

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
