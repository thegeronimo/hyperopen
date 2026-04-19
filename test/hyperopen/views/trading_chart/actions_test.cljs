(ns hyperopen.views.trading-chart.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [replicant.core :as replicant-core]
            [hyperopen.system :as app-system]
            [hyperopen.views.trading-chart.actions :as actions]))

(deftest chart-actions-owner-preserves-dispatch-and-prefill-contract-test
  (let [dispatch-calls (atom [])
        render-dispatch (fn [event action-vectors]
                          (swap! dispatch-calls conj [event action-vectors]))
        order {:coin "BTC"
               :oid 42
               :side "A"
               :type "limit"
               :sz "1"
               :px "100"}]
    (binding [replicant-core/*dispatch* render-dispatch]
      (is (identical? render-dispatch (actions/current-dispatch-fn)))
      ((actions/cancel-order-callback (actions/current-dispatch-fn)) order)
      ((actions/hide-volume-indicator-callback (actions/current-dispatch-fn))))
    (let [[[cancel-event cancel-actions]
           [hide-event hide-actions]]
          @dispatch-calls]
      (is (= :chart-order-overlay-cancel (:replicant/trigger cancel-event)))
      (is (= [[:actions/cancel-order order]] cancel-actions))
      (is (= :chart-volume-indicator-remove (:replicant/trigger hide-event)))
      (is (= [[:actions/hide-volume-indicator]] hide-actions))))

  (let [store (atom {:wallet {:connected? true
                              :address "0xabc"
                              :agent {:status :not-ready
                                      :storage-mode :session}}})
        original-store app-system/store
        order {:coin "BTC"
               :oid 43
               :side "A"
               :type "limit"
               :sz "1"
               :px "101"}]
    (try
      (set! app-system/store store)
      (binding [replicant-core/*dispatch* nil]
        (actions/dispatch-chart-cancel-order! order))
      (is (= "Enable trading before cancelling orders."
             (get-in @store [:orders :cancel-error])))
      (finally
        (set! app-system/store original-store))))

  (let [anchor {:left 10
                :right 20
                :top 30
                :bottom 40
                :width 10
                :height 10
                :viewport-width 1200
                :viewport-height 800}
        position-data {:position {:coin "BTC"
                                  :szi "1"
                                  :entryPx "100"
                                  :liquidationPx "90"}
                       :dex "xyz"}
        suggestion {:mode :add
                    :amount 2.5
                    :current-liquidation-price 90
                    :target-liquidation-price 85
                    :anchor anchor}]
    (is (= [[:actions/select-account-info-tab :positions]
            [:actions/open-position-margin-modal
             {:position {:coin "BTC"
                         :szi "1"
                         :entryPx "100"
                         :liquidationPx "90"}
              :dex "xyz"
              :prefill-source :chart-liquidation-drag
              :prefill-margin-mode :add
              :prefill-margin-amount 2.5
              :prefill-liquidation-target-price 85
              :prefill-liquidation-current-price 90}
             anchor]]
           (actions/chart-liquidation-drag-prefill-actions position-data suggestion))))

  (let [dispatch-calls (atom [])
        dispatch-fn (fn [event action-vectors]
                      (swap! dispatch-calls conj [event action-vectors]))]
    ((actions/cancel-order-callback dispatch-fn) :not-an-order)
    (actions/dispatch-chart-liquidation-drag-margin-preview!
     dispatch-fn
     :not-position-data
     {:mode :add})
    (actions/dispatch-chart-liquidation-drag-margin-confirm!
     dispatch-fn
     {:position {:coin "BTC"}}
     :not-a-suggestion)
    (is (empty? @dispatch-calls))))
