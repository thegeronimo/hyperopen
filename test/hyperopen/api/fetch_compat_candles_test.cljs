(ns hyperopen.api.fetch-compat-candles-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.fetch-compat :as fetch-compat]))

(defn- reject-promise
  [message]
  (js/Promise.reject (js/Error. message)))

(deftest fetch-candle-snapshot-skips-when-active-asset-missing-test
  (async done
    (let [store (atom {})
          requests (atom 0)
          deps {:log-fn (fn [& _] nil)
                :request-candle-snapshot! (fn [& _]
                                            (swap! requests inc)
                                            (js/Promise.resolve []))
                :apply-candle-snapshot-success (fn [state _asset _interval data]
                                                 (assoc state :candles data))
                :apply-candle-snapshot-error (fn [state _asset _interval err]
                                               (assoc state :candle-error (str err)))}]
      (-> (fetch-compat/fetch-candle-snapshot! deps store {:interval :1m
                                                            :bars 50
                                                            :priority :high})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @requests))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-candle-snapshot-applies-success-projection-test
  (async done
    (let [store (atom {:active-asset "BTC"})
          deps {:log-fn (fn [& _] nil)
                :request-candle-snapshot! (fn [asset & {:keys [interval bars priority]}]
                                            (is (= "BTC" asset))
                                            (is (= :1h interval))
                                            (is (= 20 bars))
                                            (is (= :high priority))
                                            (js/Promise.resolve [{:time 1 :close 2.0}]))
                :apply-candle-snapshot-success (fn [state asset interval data]
                                                 (assoc-in state [:candles asset interval] data))
                :apply-candle-snapshot-error (fn [state _asset _interval err]
                                               (assoc state :candle-error (str err)))}]
      (-> (fetch-compat/fetch-candle-snapshot! deps store {:interval :1h
                                                            :bars 20
                                                            :priority :high})
          (.then (fn [data]
                   (is (= [{:time 1 :close 2.0}] data))
                   (is (= data
                          (get-in @store [:candles "BTC" :1h])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-candle-snapshot-applies-error-projection-test
  (async done
    (let [store (atom {:active-asset "ETH"})
          deps {:log-fn (fn [& _] nil)
                :request-candle-snapshot! (fn [& _]
                                            (reject-promise "candle fetch failed"))
                :apply-candle-snapshot-success (fn [state _asset _interval data]
                                                 (assoc state :candles data))
                :apply-candle-snapshot-error (fn [state asset interval err]
                                               (assoc state :candle-error {:asset asset
                                                                           :interval interval
                                                                           :message (.-message err)}))}]
      (-> (fetch-compat/fetch-candle-snapshot! deps store {:interval :5m
                                                            :bars 10
                                                            :priority :low})
          (.then (fn [_]
                   (is false "Expected fetch-candle-snapshot! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"candle fetch failed" (str err)))
                    (is (= {:asset "ETH"
                            :interval :5m
                            :message "candle fetch failed"}
                           (:candle-error @store)))
                    (done)))))))
