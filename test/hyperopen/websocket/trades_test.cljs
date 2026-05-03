(ns hyperopen.websocket.trades-test
  (:require [cljs.test :refer [use-fixtures]
             :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.trades-policy :as policy]))

(defn- reset-trades-fixture [f]
  (reset! trades/trades-state {:subscriptions #{}
                               :trades []
                               :trades-by-coin {}})
  (reset! trades/trades-buffer {:pending [] :timer nil})
  (f)
  (reset! trades/trades-state {:subscriptions #{}
                               :trades []
                               :trades-by-coin {}})
  (reset! trades/trades-buffer {:pending [] :timer nil}))

(use-fixtures :each reset-trades-fixture)

(deftest subscribe-trades-sends-one-subscription-per-symbol-test
  (let [sent-messages (atom [])]
    (with-redefs [ws-client/send-message! (fn [message]
                                            (swap! sent-messages conj message)
                                            true)]
      (trades/subscribe-trades! "#11")
      (trades/subscribe-trades! "#11"))
    (is (= [{:method "subscribe"
             :subscription {:type "trades"
                            :coin "#11"}}]
           @sent-messages))
    (is (= #{"#11"} (:subscriptions @trades/trades-state)))))

(deftest update-candles-from-trades-normalizes-filters-and-sorts-before-upsert-test
  (let [store (atom {:active-asset "BTC"
                     :chart-options {:selected-timeframe :1m}
                     :candles {"BTC" {:1m []}}})
        incoming [{:time-ms 30 :price 3 :coin "ETH"}
                  {:time-ms 10 :price 1 :coin "BTC"}
                  {:time-ms nil :price 4 :coin "BTC"}
                  {:time-ms 20 :price nil :coin "BTC"}
                  {:time-ms 15 :price 1.5 :coin nil}
                  {:time-ms 5 :price 0.5 :coin "BTC"}]]
    (with-redefs [policy/normalize-trade identity
                  policy/upsert-candle (fn [acc _ trade _]
                                         (conj (vec acc) trade))]
      (@#'trades/update-candles-from-trades! store incoming))
    (is (= [{:time-ms 5 :price 0.5 :coin "BTC"}
            {:time-ms 10 :price 1 :coin "BTC"}
            {:time-ms 15 :price 1.5 :coin nil}]
           (mapv #(dissoc % :size) (get-in @store [:candles "BTC" :1m]))))))

(deftest update-candles-from-trades-preserves-map-entry-shape-when-updating-data-test
  (let [store (atom {:active-asset "BTC"
                     :chart-options {:selected-timeframe :1m}
                     :candles {"BTC" {:1m {:meta :keep
                                           :data []}}}})
        incoming [{:time-ms 200 :price 2 :coin "BTC"}
                  {:time-ms 100 :price 1 :coin "BTC"}]]
    (with-redefs [policy/normalize-trade identity
                  policy/upsert-candle (fn [acc _ trade _]
                                         (conj (vec acc) trade))]
      (@#'trades/update-candles-from-trades! store incoming))
    (let [entry (get-in @store [:candles "BTC" :1m])]
      (is (= :keep (:meta entry)))
      (is (= [{:time-ms 100 :price 1 :coin "BTC"}
              {:time-ms 200 :price 2 :coin "BTC"}]
             (mapv #(dissoc % :size) (:data entry)))))))

(deftest ingest-trades-coin-cache-merges-batches-in-time-order-test
  (@#'trades/ingest-trades! [{:coin "BTC" :px "3" :sz "0.1" :time 1700000003}
                             {:coin "BTC" :px "1" :sz "0.1" :time 1700000001}])
  (@#'trades/ingest-trades! [{:coin "BTC" :px "4" :sz "0.1" :time 1700000004}
                             {:coin "BTC" :px "2" :sz "0.1" :time 1700000002}])
  (is (= [1700000004000 1700000003000 1700000002000 1700000001000]
         (mapv :time-ms (trades/get-recent-trades-for-coin "BTC"))))
  (is (= ["4" "3" "2" "1"]
         (mapv :price-raw (trades/get-recent-trades-for-coin "BTC")))))

(deftest ingest-trades-coin-cache-is-bounded-to-max-recent-trades-test
  (let [base-time 1700000000
        incoming (mapv (fn [offset]
                         {:coin "BTC"
                          :px (str offset)
                          :sz "0.1"
                          :time (+ base-time offset)})
                       (reverse (range 120)))]
    (@#'trades/ingest-trades! incoming))
  (let [recent (trades/get-recent-trades-for-coin "BTC")]
    (is (= 100 (count recent)))
    (is (= 1700000119000 (:time-ms (first recent))))
    (is (= 1700000020000 (:time-ms (last recent))))))

(deftest normalize-trade-for-view-preserves-raw-fields-and-alias-precedence-test
  (let [normalized (@#'trades/normalize-trade-for-view
                    {:coin "BTC"
                     :symbol "ETH"
                     :asset "SOL"
                     :px "61500.1"
                     :price "60000.0"
                     :p "59000.0"
                     :sz "0.03"
                     :size "0.02"
                     :s "0.01"
                     :side "B"
                     :dir "A"
                     :time 1700000001
                     :t 1700000002
                     :ts 1700000003
                     :timestamp 1700000004
                     :tid 42
                     :id 7})]
    (is (= {:coin "BTC"
            :price 61500.1
            :price-raw "61500.1"
            :size 0.03
            :size-raw "0.03"
            :side "B"
            :time-ms 1700000001000
            :tid 42}
           normalized))))

(deftest normalize-trade-for-view-falls-back-through-aliases-test
  (let [normalized (@#'trades/normalize-trade-for-view
                    {:asset "ETH"
                     :price "3010.5"
                     :size "0.2"
                     :dir "A"
                     :timestamp 1700000100000
                     :id "trade-7"})]
    (is (= {:coin "ETH"
            :price 3010.5
            :price-raw "3010.5"
            :size 0.2
            :size-raw "0.2"
            :side "A"
            :time-ms 1700000100000
            :tid "trade-7"}
           normalized))))

(deftest schedule-candle-update-incrementally-merges-pending-trades-test
  (let [store (atom {:active-asset "BTC"
                     :chart-options {:selected-timeframe :1m}
                     :candles {"BTC" {:1m []}}})
        timeout-callback (atom nil)
        flushed-pending (atom nil)]
    (with-redefs [platform/set-timeout! (fn [f _delay-ms]
                                          (reset! timeout-callback f)
                                          :timer-1)
                  trades/update-candles-from-normalized-trades! (fn [_ pending]
                                                                  (reset! flushed-pending pending))]
      (@#'trades/schedule-candle-update! store [{:coin "BTC" :px "3" :sz "1" :time 1700000003}
                                                {:coin "BTC" :px "1" :sz "1" :time 1700000001}])
      (@#'trades/schedule-candle-update! store [{:coin "BTC" :px "2" :sz "1" :time 1700000002}])
      (is (= [1700000001000 1700000002000 1700000003000]
             (mapv :time-ms (:pending @trades/trades-buffer))))
      (is (= :timer-1 (:timer @trades/trades-buffer)))
      (@timeout-callback))
    (is (= [1700000001000 1700000002000 1700000003000]
           (mapv :time-ms @flushed-pending)))
    (is (= {:pending [] :timer nil}
           @trades/trades-buffer))))

(deftest create-trades-handler-updates-coin-indexed-display-cache-test
  (let [handler (trades/create-trades-handler (atom {}))]
    (with-redefs [trades/schedule-candle-update! (fn [& _] nil)]
      (handler {:channel "trades"
                :data [{:coin "ETH" :px "3010.5" :sz "0.2" :side "B" :time 1700000001}
                       {:coin "BTC" :px "61500.1" :sz "0.03" :side "A" :time 1700000003}
                       {:coin "BTC" :px "61499.9" :sz "0.01" :side "B" :time 1700000002}]}))
    (is (= 2 (count (trades/get-recent-trades-for-coin "BTC"))))
    (is (= ["61500.1" "61499.9"]
           (mapv :price-raw (trades/get-recent-trades-for-coin "BTC"))))
    (is (= [1700000003000 1700000002000]
           (mapv :time-ms (trades/get-recent-trades-for-coin "BTC"))))
    (is (= "3010.5"
           (:price-raw (first (trades/get-recent-trades-for-coin "ETH")))))))

(deftest clear-trades-clears-coin-indexed-cache-test
  (reset! trades/trades-state {:subscriptions #{}
                               :trades [{:coin "BTC" :px "1"}]
                               :trades-by-coin {"BTC" [{:coin "BTC" :price-raw "1"}]}})
  (trades/clear-trades!)
  (is (= [] (trades/get-recent-trades)))
  (is (= [] (trades/get-recent-trades-for-coin "BTC"))))
