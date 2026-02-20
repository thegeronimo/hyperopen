(ns hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]))

(deftest open-order-overlays-render-lines-and-inline-cancel-test
  (let [document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        unsubscribes* (atom 0)
        subscribe-fn (fn [_] nil)
        unsubscribe-fn (fn [_]
                         (swap! unsubscribes* inc))
        time-scale #js {:subscribeVisibleTimeRangeChange subscribe-fn
                        :unsubscribeVisibleTimeRangeChange unsubscribe-fn
                        :subscribeVisibleLogicalRangeChange subscribe-fn
                        :unsubscribeVisibleLogicalRangeChange unsubscribe-fn
                        :subscribeSizeChange subscribe-fn
                        :unsubscribeSizeChange unsubscribe-fn}
        chart #js {:timeScale (fn [] time-scale)
                   :subscribeCrosshairMove subscribe-fn
                   :unsubscribeCrosshairMove unsubscribe-fn
                   :subscribeClick subscribe-fn
                   :unsubscribeClick unsubscribe-fn}
        main-series #js {:priceToCoordinate (fn [price]
                                              (* 2 price))
                         :subscribeDataChanged subscribe-fn
                         :unsubscribeDataChanged unsubscribe-fn}
        chart-obj #js {:chart chart
                       :mainSeries main-series}
        canceled-oids* (atom [])
        orders [{:coin "SOL"
                 :oid 11
                 :side "B"
                 :type "limit"
                 :sz "1.00"
                 :px "60.0"}
                {:coin "SOL"
                 :oid 12
                 :side "A"
                 :type "limit"
                 :sz "2.25"
                 :px "61.5"}]]
    (chart-interop/sync-open-order-overlays!
     chart-obj
     container
     orders
     {:document document
      :on-cancel-order (fn [order]
                         (swap! canceled-oids* conj (:oid order)))
      :format-price (fn [price _raw]
                      (str "P" price))
      :format-size (fn [size]
                     (str "S" size))})
    (is (= 1 (alength (.-children container))))
    (let [overlay-root (aget (.-children container) 0)
          text (str/join " " (fake-dom/collect-text-content overlay-root))
          cancel-button (fake-dom/find-dom-node overlay-root
                                       #(= "button" (some-> (.-tagName %) str/lower-case)))]
      (is (str/includes? text "Limit S1.00 at $P60.0"))
      (is (str/includes? text "Limit S2.25 at $P61.5"))
      (is (some? cancel-button))
      (fake-dom/click-dom-node! cancel-button))
    (is (= [12] @canceled-oids*))
    (chart-interop/clear-open-order-overlays! chart-obj)
    (is (= 0 (alength (.-children container))))
    (is (= 6 @unsubscribes*))))

(deftest open-order-overlays-inline-cancel-pointerdown-dispatches-once-test
  (let [document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        subscribe-fn (fn [_] nil)
        time-scale #js {:subscribeVisibleTimeRangeChange subscribe-fn
                        :unsubscribeVisibleTimeRangeChange subscribe-fn
                        :subscribeVisibleLogicalRangeChange subscribe-fn
                        :unsubscribeVisibleLogicalRangeChange subscribe-fn
                        :subscribeSizeChange subscribe-fn
                        :unsubscribeSizeChange subscribe-fn}
        chart #js {:timeScale (fn [] time-scale)
                   :subscribeCrosshairMove subscribe-fn
                   :unsubscribeCrosshairMove subscribe-fn
                   :subscribeClick subscribe-fn
                   :unsubscribeClick subscribe-fn}
        main-series #js {:priceToCoordinate (fn [price]
                                              (* 2 price))
                         :subscribeDataChanged subscribe-fn
                         :unsubscribeDataChanged subscribe-fn}
        chart-obj #js {:chart chart
                       :mainSeries main-series}
        canceled-oids* (atom [])
        order {:coin "SOL"
               :oid 11
               :side "B"
               :type "limit"
               :sz "1.00"
               :px "60.0"}]
    (chart-interop/sync-open-order-overlays!
     chart-obj
     container
     [order]
     {:document document
      :on-cancel-order (fn [order*]
                         (swap! canceled-oids* conj (:oid order*)))})
    (let [overlay-root (aget (.-children container) 0)
          cancel-button (fake-dom/find-dom-node overlay-root
                                       #(= "button" (some-> (.-tagName %) str/lower-case)))]
      (is (some? cancel-button))
      (fake-dom/pointer-down-dom-node! cancel-button)
      (fake-dom/click-dom-node! cancel-button))
    (is (= [11] @canceled-oids*))
    (chart-interop/clear-open-order-overlays! chart-obj)))

(deftest open-order-overlays-sync-skips-rerender-for-unchanged-identities-test
  (let [document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        subscribe-fn (fn [_] nil)
        time-scale #js {:subscribeVisibleTimeRangeChange subscribe-fn
                        :unsubscribeVisibleTimeRangeChange subscribe-fn
                        :subscribeVisibleLogicalRangeChange subscribe-fn
                        :unsubscribeVisibleLogicalRangeChange subscribe-fn
                        :subscribeSizeChange subscribe-fn
                        :unsubscribeSizeChange subscribe-fn}
        chart #js {:timeScale (fn [] time-scale)
                   :subscribeCrosshairMove subscribe-fn
                   :unsubscribeCrosshairMove subscribe-fn
                   :subscribeClick subscribe-fn
                   :unsubscribeClick subscribe-fn}
        price-to-coordinate-calls* (atom 0)
        main-series #js {:priceToCoordinate (fn [price]
                                              (swap! price-to-coordinate-calls* inc)
                                              (* 2 price))
                         :subscribeDataChanged subscribe-fn
                         :unsubscribeDataChanged subscribe-fn}
        chart-obj #js {:chart chart
                       :mainSeries main-series}
        orders [{:coin "SOL" :oid 101 :side "B" :type "limit" :sz "1.0" :px "10"}
                {:coin "SOL" :oid 102 :side "A" :type "limit" :sz "2.0" :px "11"}]
        format-price (fn [price _raw] (str price))
        format-size (fn [size] (str size))
        on-cancel-order (fn [_] nil)]
    (chart-interop/sync-open-order-overlays!
     chart-obj
     container
     orders
     {:document document
      :format-price format-price
      :format-size format-size
      :on-cancel-order on-cancel-order})
    (is (= 2 @price-to-coordinate-calls*))
    (chart-interop/sync-open-order-overlays!
     chart-obj
     container
     orders
     {:document document
      :format-price format-price
      :format-size format-size
      :on-cancel-order on-cancel-order})
    (is (= 2 @price-to-coordinate-calls*))
    (chart-interop/sync-open-order-overlays!
     chart-obj
     container
     (mapv identity orders)
     {:document document
      :format-price format-price
      :format-size format-size
      :on-cancel-order on-cancel-order})
    (is (= 4 @price-to-coordinate-calls*))
    (chart-interop/clear-open-order-overlays! chart-obj)))
