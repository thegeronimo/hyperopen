(ns hyperopen.views.trading-chart.utils.chart-interop.answer-lines-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.runtime-state :as chart-runtime]
            [hyperopen.views.trading-chart.utils.chart-interop.answer-lines :as answer-lines]
            [hyperopen.views.trading-chart.utils.chart-interop.axis-debug :as axis-debug]))

(deftest line-points-are-close-prices-by-time-test
  (is (= [{:time 1700000000 :value 0.46}
          {:time 1700086400 :value 0.5}]
         (answer-lines/line-points [{:t 1700086400000 :c "0.50"}
                                    {:t 1700000000000 :c "0.46"}
                                    {:t 1700086400000 :c "0.50"}
                                    {:t nil :c "0.4"}
                                    {:t 1700172800000 :c "x"}])))
  (is (= [] (answer-lines/line-points []))))

(deftest the-percent-format-steps-the-scale-in-quarters-test
  (let [fmt answer-lines/price-format]
    (is (= "custom" (aget fmt "type")))
    (is (= 0.25 (aget fmt "minMove")) "the tick builder never steps below minMove")
    (is (= "50.3%" ((aget fmt "formatter") 0.503)))
    (is (= ["100%" "75%" "50%" "25%" "0%"]
           (vec ((aget fmt "tickmarksFormatter") #js [1 0.75 0.5 0.25 0]))))))

(deftest axis-debug-reproduces-the-price-scale-tick-span-test
  ;; 0–100% pane with the provider's pixel margins: quarters at any usual height
  (doseq [height [220 300 600]]
    (let [span (axis-debug/tick-span {:high 1.0625 :low -0.0625 :height height :font-size 12 :min-move 0.25})]
      (is (= 0.25 span))
      (is (= [1 0.75 0.5 0.25 0] (axis-debug/tick-prices 1.0625 -0.0625 span)))))
  ;; a short pane drops to halves rather than crowding labels
  (is (= 0.5 (axis-debug/tick-span {:high 1.04 :low -0.04 :height 80 :font-size 12 :min-move 0.25})))
  ;; a decimal outcome book: 0.00001 dollar ticks, 300 px for 8 cents
  (let [span (axis-debug/tick-span {:high 0.56 :low 0.48 :height 300 :font-size 12 :min-move 0.00001})]
    (is (= 0.01 span))
    (is (<= 7 (count (axis-debug/tick-prices 0.56 0.48 span)) 9))))

(defn- fake-chart
  [log]
  #js {:addSeries (fn [_kind options]
                    (let [id (aget options "title")]
                      #js {:setData (fn [data] (swap! log conj [id :set-data (alength data)]))
                           :update (fn [_] (swap! log conj [id :update]))
                           :applyOptions (fn [_] (swap! log conj [id :apply]))}))
       :removeSeries (fn [_] (swap! log conj [:remove]))
       :timeScale (fn [] #js {:fitContent (fn [] (swap! log conj [:fit]))})})

(defn- line [coin status rows]
  {:coin coin :short-label coin :color "#123456" :width 2 :status status :rows rows})

(deftest a-timeframe-switch-never-shows-old-rows-test
  (let [log (atom [])
        node #js {}
        rows-a [{:t 1000 :c "0.5"} {:t 2000 :c "0.6"}]
        rows-b [{:t 1000 :c "0.2"}]]
    (chart-runtime/set-state! node {:chart (fake-chart log) :series-by-coin {} :timeframe :1d})
    (answer-lines/sync-lines! node {:timeframe :1d :lines [(line "A" :ready rows-a) (line "B" :ready rows-b)]})
    (reset! log [])
    (answer-lines/sync-lines! node {:timeframe :1h :lines [(line "A" :loading []) (line "B" :failed [])]})
    (is (= #{["A" :set-data 0] ["B" :set-data 0]} (set (filter #(= :set-data (second %)) @log)))
        "both old lines are cleared on the new time axis")
    (is (not-any? #{[:fit]} @log) "nothing left to fit")
    (let [{:keys [series-by-coin]} (chart-runtime/get-state node)]
      (is (nil? (get-in series-by-coin ["A" :rows])))
      (is (nil? (get-in series-by-coin ["B" :rows]))))
    (reset! log [])
    (answer-lines/sync-lines! node {:timeframe :1h :lines [(line "A" :ready rows-a) (line "B" :failed [])]})
    (is (= [["A" :set-data 2] [:fit]] @log) "the failed line stays empty; the loaded one draws and fits")))

(deftest loading-keeps-and-empty-clears-within-a-timeframe-test
  (let [log (atom [])
        node #js {}
        rows [{:t 1000 :c "0.5"}]]
    (chart-runtime/set-state! node {:chart (fake-chart log) :series-by-coin {} :timeframe :1d})
    (answer-lines/sync-lines! node {:timeframe :1d :lines [(line "A" :ready rows)]})
    (reset! log [])
    (answer-lines/sync-lines! node {:timeframe :1d :lines [(line "A" :loading [])]})
    (is (= [] @log) "a refetch in flight keeps the line")
    (answer-lines/sync-lines! node {:timeframe :1d :lines [(line "A" :empty [])]})
    (is (= [["A" :set-data 0]] @log) "no trades in range: drawn as nothing")))

(deftest plan-requests-are-debounced-last-wins-test
  (let [timers (atom {})
        next-id (atom 0)
        dispatched (atom [])
        node #js {}]
    (chart-runtime/set-state! node {:dispatch-fn (fn [_ actions] (swap! dispatched conj actions))})
    (binding [answer-lines/*set-timeout* (fn [f ms]
                                           (is (= answer-lines/sync-debounce-ms ms))
                                           (let [id (swap! next-id inc)] (swap! timers assoc id f) id))
              answer-lines/*clear-timeout* (fn [id] (swap! timers dissoc id))]
      (answer-lines/request-sync! node {:request-key [:e :1h] :plan-key [:e :1d]})
      (answer-lines/request-sync! node {:request-key [:e :1h] :plan-key [:e :1d]})
      (is (= 1 (count @timers)) "the same request is scheduled once")
      (answer-lines/request-sync! node {:request-key [:e :4h] :plan-key [:e :1d]})
      (answer-lines/request-sync! node {:request-key [:e :5m] :plan-key [:e :1d]})
      (is (= [3] (keys @timers)) "each newer request cancels the pending one")
      ((get @timers 3))
      (is (= [[[:actions/sync-outcome-answer-chart]]] @dispatched) "one sync for the last flip")
      (answer-lines/request-sync! node {:request-key [:e :1d] :plan-key [:e :1d]})
      (is (= 1 (count @dispatched))))))
