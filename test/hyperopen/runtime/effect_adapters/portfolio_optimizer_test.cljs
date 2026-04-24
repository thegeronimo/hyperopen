(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer :as portfolio-optimizer-adapters]
            [hyperopen.test-support.async :as async-support]))

(deftest facade-portfolio-optimizer-adapter-delegates-to-owner-module-test
  (is (identical? portfolio-optimizer-adapters/run-portfolio-optimizer-effect
                  effect-adapters/run-portfolio-optimizer-effect))
  (is (identical? portfolio-optimizer-adapters/load-portfolio-optimizer-history-effect
                  effect-adapters/load-portfolio-optimizer-history-effect))
  (is (identical? portfolio-optimizer-adapters/save-portfolio-optimizer-scenario-effect
                  effect-adapters/save-portfolio-optimizer-scenario-effect)))

(deftest run-portfolio-optimizer-effect-calls-run-bridge-with-runtime-store-test
  (let [calls (atom [])
        store (atom {})
        request {:scenario-id "scenario-1"}
        signature {:scenario-id "scenario-1" :revision 1}]
    (with-redefs [portfolio-optimizer-adapters/*request-run!*
                  (fn [payload]
                    (swap! calls conj payload)
                    "run-1")]
      (is (= "run-1"
             (portfolio-optimizer-adapters/run-portfolio-optimizer-effect
              :ctx
              store
              request
              signature
              {:computed-at-ms 123})))
      (is (= [{:request request
               :request-signature signature
               :computed-at-ms 123
               :store store}]
             @calls)))))

(deftest load-portfolio-optimizer-history-effect-persists-success-for-current-request-test
  (async done
    (let [calls (atom [])
          store (atom {:portfolio {:optimizer
                                    {:draft {:universe [{:instrument-id "perp:BTC"
                                                         :market-type :perp
                                                         :coin "BTC"}]}
                                     :runtime {:as-of-ms 3000
                                               :stale-after-ms 60000}}}})
          bundle {:candle-history-by-coin {"BTC" [{:time 1000 :close "100"}
                                                  {:time 2000 :close "110"}]}
                  :funding-history-by-coin {"BTC" [{:time-ms 1000
                                                    :funding-rate-raw 0.001}]}
                  :warnings [{:code :funding-partial}]
                  :request-plan {:candle-requests [{:coin "BTC"}]}}]
      (with-redefs [portfolio-optimizer-adapters/*request-history-bundle!*
                    (fn [deps request]
                      (swap! calls conj {:deps deps
                                         :request request})
                      (js/Promise.resolve bundle))
                    portfolio-optimizer-adapters/*now-ms* (fn [] 12345)]
        (let [promise (portfolio-optimizer-adapters/load-portfolio-optimizer-history-effect
                       nil
                       store
                       {:bars 90})]
          (is (= :loading
                 (get-in @store [:portfolio :optimizer :history-load-state :status])))
          (-> promise
              (.then (fn [result]
                       (is (= bundle result))
                       (is (= 1 (count @calls)))
                       (is (= [{:instrument-id "perp:BTC"
                                :market-type :perp
                                :coin "BTC"}]
                              (get-in @calls [0 :request :universe])))
                       (is (= 90 (get-in @calls [0 :request :bars])))
                       (is (fn? (get-in @calls [0 :deps :request-candle-snapshot!])))
                       (is (fn? (get-in @calls [0 :deps :request-market-funding-history!])))
                       (is (= {"BTC" [{:time 1000 :close "100"}
                                      {:time 2000 :close "110"}]}
                              (get-in @store
                                      [:portfolio :optimizer :history-data :candle-history-by-coin])))
                       (is (= {"BTC" [{:time-ms 1000
                                       :funding-rate-raw 0.001}]}
                              (get-in @store
                                      [:portfolio :optimizer :history-data :funding-history-by-coin])))
                       (is (= {:status :succeeded
                               :request-signature (get-in @store
                                                          [:portfolio
                                                           :optimizer
                                                           :history-load-state
                                                           :request-signature])
                               :started-at-ms 12345
                               :completed-at-ms 12345
                               :error nil
                               :warnings [{:code :funding-partial}]}
                              (get-in @store [:portfolio :optimizer :history-load-state])))
                       (done)))
              (.catch (async-support/unexpected-error done))))))))

(deftest save-portfolio-optimizer-scenario-effect-persists-record-index-and-store-state-test
  (async done
    (let [calls (atom [])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          solved-run {:result {:status :solved
                               :expected-return 0.18
                               :volatility 0.42}
                      :computed-at-ms 2000}
          store (atom {:wallet {:address address}
                       :portfolio {:optimizer
                                   {:draft {:name "Core Hedge"
                                            :objective {:kind :max-sharpe}
                                            :return-model {:kind :historical-mean}
                                            :risk-model {:kind :ledoit-wolf}
                                            :metadata {:dirty? true}}
                                    :scenario-index {:ordered-ids []
                                                     :by-id {}}
                                    :last-successful-run solved-run}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (fn [] 3000)
                    portfolio-optimizer-adapters/*next-scenario-id* (fn [_now-ms] "scn_3000")
                    portfolio-optimizer-adapters/*load-scenario-index!* (fn [addr]
                                                                          (swap! calls conj [:load-index addr])
                                                                          (js/Promise.resolve nil))
                    portfolio-optimizer-adapters/*save-scenario!* (fn [scenario-id record]
                                                                     (swap! calls conj [:save-scenario scenario-id record])
                                                                     (js/Promise.resolve true))
                    portfolio-optimizer-adapters/*save-scenario-index!* (fn [addr index]
                                                                          (swap! calls conj [:save-index addr index])
                                                                          (js/Promise.resolve true))]
        (-> (portfolio-optimizer-adapters/save-portfolio-optimizer-scenario-effect nil store)
            (.then (fn [record]
                     (is (= "scn_3000" (:id record)))
                     (is (= :saved (:status record)))
                     (is (= solved-run (:saved-run record)))
                     (is (= false (get-in @store [:portfolio :optimizer :draft :metadata :dirty?])))
                     (is (= "scn_3000" (get-in @store [:portfolio :optimizer :active-scenario :loaded-id])))
                     (is (= ["scn_3000"]
                            (get-in @store [:portfolio :optimizer :scenario-index :ordered-ids])))
                     (is (= :saved
                            (get-in @store [:portfolio :optimizer :scenario-save-state :status])))
                     (is (= [:load-index address] (first @calls)))
                     (is (= :save-scenario (ffirst (drop 1 @calls))))
                     (is (= :save-index (ffirst (drop 2 @calls))))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest load-portfolio-optimizer-history-effect-preserves-data-on-error-test
  (async done
    (let [store (atom {:portfolio {:optimizer
                                    {:draft {:universe [{:instrument-id "perp:BTC"
                                                         :market-type :perp
                                                         :coin "BTC"}]}
                                     :history-data {:candle-history-by-coin {"BTC" [:old]}}}}})]
      (with-redefs [portfolio-optimizer-adapters/*request-history-bundle!*
                    (fn [_deps _request]
                      (js/Promise.reject (js/Error. "history boom")))
                    portfolio-optimizer-adapters/*now-ms* (fn [] 222)]
        (let [promise (portfolio-optimizer-adapters/load-portfolio-optimizer-history-effect nil store)]
          (-> promise
              (.then (fn [_]
                       (is (= {"BTC" [:old]}
                              (get-in @store
                                      [:portfolio :optimizer :history-data :candle-history-by-coin])))
                       (is (= :failed
                              (get-in @store [:portfolio :optimizer :history-load-state :status])))
                       (is (= "history boom"
                              (get-in @store [:portfolio :optimizer :history-load-state :error :message])))
                       (done)))
              (.catch (async-support/unexpected-error done))))))))
