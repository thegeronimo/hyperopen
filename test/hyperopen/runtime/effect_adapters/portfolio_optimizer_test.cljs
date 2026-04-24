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
                  effect-adapters/save-portfolio-optimizer-scenario-effect))
  (is (identical? portfolio-optimizer-adapters/load-portfolio-optimizer-scenario-index-effect
                  effect-adapters/load-portfolio-optimizer-scenario-index-effect))
  (is (identical? portfolio-optimizer-adapters/load-portfolio-optimizer-scenario-effect
                  effect-adapters/load-portfolio-optimizer-scenario-effect))
  (is (identical? portfolio-optimizer-adapters/archive-portfolio-optimizer-scenario-effect
                  effect-adapters/archive-portfolio-optimizer-scenario-effect))
  (is (identical? portfolio-optimizer-adapters/duplicate-portfolio-optimizer-scenario-effect
                  effect-adapters/duplicate-portfolio-optimizer-scenario-effect)))

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

(deftest load-portfolio-optimizer-scenario-index-effect-loads-address-scoped-index-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          index {:ordered-ids ["scn_02" "scn_01"]
                 :by-id {"scn_02" {:id "scn_02"
                                    :name "Fresh Run"
                                    :status :saved}
                         "scn_01" {:id "scn_01"
                                    :name "Core Hedge"
                                    :status :partially-executed}}}
          calls (atom [])
          store (atom {:wallet {:address address}
                       :portfolio {:optimizer {:scenario-index {:ordered-ids []
                                                                 :by-id {}}}}})]
      (with-redefs [portfolio-optimizer-adapters/*load-scenario-index!*
                    (fn [addr]
                      (swap! calls conj [:load-index addr])
                      (js/Promise.resolve index))
                    portfolio-optimizer-adapters/*now-ms* (fn [] 4000)]
        (-> (portfolio-optimizer-adapters/load-portfolio-optimizer-scenario-index-effect
             nil
             store)
            (.then (fn [loaded-index]
                     (is (= index loaded-index))
                     (is (= [[:load-index address]] @calls))
                     (is (= index
                            (get-in @store [:portfolio :optimizer :scenario-index])))
                     (is (= {:status :loaded
                             :started-at-ms 4000
                             :completed-at-ms 4000
                             :error nil}
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index-load-state])))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest load-portfolio-optimizer-scenario-effect-hydrates-workspace-state-test
  (async done
    (let [scenario-record {:schema-version 1
                           :id "scn_01"
                           :name "Core Hedge"
                           :status :saved
                           :config {:id "scn_01"
                                    :name "Core Hedge"
                                    :objective {:kind :max-sharpe}
                                    :return-model {:kind :historical-mean}
                                    :risk-model {:kind :ledoit-wolf}
                                    :metadata {:dirty? false}}
                           :saved-run {:computed-at-ms 2000
                                       :result {:status :solved
                                                :expected-return 0.18
                                                :volatility 0.42}}
                           :updated-at-ms 3000}
          store (atom {:portfolio {:optimizer {}}})
          calls (atom [])]
      (with-redefs [portfolio-optimizer-adapters/*load-scenario!*
                    (fn [scenario-id]
                      (swap! calls conj [:load-scenario scenario-id])
                      (js/Promise.resolve scenario-record))
                    portfolio-optimizer-adapters/*now-ms* (fn [] 4100)]
        (-> (portfolio-optimizer-adapters/load-portfolio-optimizer-scenario-effect
             nil
             store
             "scn_01")
            (.then (fn [loaded-record]
                     (is (= scenario-record loaded-record))
                     (is (= [[:load-scenario "scn_01"]] @calls))
                     (is (= (:config scenario-record)
                            (get-in @store [:portfolio :optimizer :draft])))
                     (is (= (:saved-run scenario-record)
                            (get-in @store [:portfolio :optimizer :last-successful-run])))
                     (is (= {:loaded-id "scn_01"
                             :status :saved
                             :read-only? false}
                            (get-in @store [:portfolio :optimizer :active-scenario])))
                     (is (= {:status :loaded
                             :scenario-id "scn_01"
                             :started-at-ms 4100
                             :completed-at-ms 4100
                             :error nil}
                            (get-in @store
                                    [:portfolio :optimizer :scenario-load-state])))
                     (is (= "Core Hedge"
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index :by-id "scn_01" :name])))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest archive-portfolio-optimizer-scenario-effect-updates-record-index-and-active-state-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          scenario-record {:schema-version 1
                           :id "scn_01"
                           :name "Core Hedge"
                           :address address
                           :status :saved
                           :config {:id "scn_01"
                                    :name "Core Hedge"
                                    :status :saved
                                    :metadata {:dirty? false
                                               :updated-at-ms 3000}}
                           :saved-run {:result {:status :solved}}
                           :updated-at-ms 3000}
          scenario-index {:ordered-ids ["scn_02" "scn_01"]
                          :by-id {"scn_02" {:id "scn_02"
                                             :name "Other"
                                             :status :saved}
                                  "scn_01" {:id "scn_01"
                                             :name "Core Hedge"
                                             :status :saved}}}
          store (atom {:wallet {:address address}
                       :portfolio {:optimizer {:active-scenario {:loaded-id "scn_01"
                                                                  :status :saved}
                                               :draft (:config scenario-record)
                                               :scenario-index scenario-index}}})
          calls (atom [])]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (fn [] 5000)
                    portfolio-optimizer-adapters/*load-scenario!* (fn [scenario-id]
                                                                    (swap! calls conj [:load-scenario scenario-id])
                                                                    (js/Promise.resolve scenario-record))
                    portfolio-optimizer-adapters/*load-scenario-index!* (fn [addr]
                                                                          (swap! calls conj [:load-index addr])
                                                                          (js/Promise.resolve scenario-index))
                    portfolio-optimizer-adapters/*save-scenario!* (fn [scenario-id record]
                                                                     (swap! calls conj [:save-scenario scenario-id record])
                                                                     (js/Promise.resolve true))
                    portfolio-optimizer-adapters/*save-scenario-index!* (fn [addr index]
                                                                          (swap! calls conj [:save-index addr index])
                                                                          (js/Promise.resolve true))]
        (-> (portfolio-optimizer-adapters/archive-portfolio-optimizer-scenario-effect
             nil
             store
             "scn_01")
            (.then (fn [archived-record]
                     (is (= :archived (:status archived-record)))
                     (is (= :archived
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index :by-id "scn_01" :status])))
                     (is (= ["scn_02" "scn_01"]
                            (get-in @store [:portfolio :optimizer :scenario-index :ordered-ids])))
                     (is (= :archived
                            (get-in @store [:portfolio :optimizer :active-scenario :status])))
                     (is (= :archived
                            (get-in @store [:portfolio :optimizer :draft :status])))
                     (is (= :archived
                            (get-in @store
                                    [:portfolio :optimizer :scenario-archive-state :status])))
                     (is (= [[:load-scenario "scn_01"]
                             [:load-index address]
                             [:save-scenario "scn_01" archived-record]
                             [:save-index address
                              (get-in @store [:portfolio :optimizer :scenario-index])]]
                            @calls))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest duplicate-portfolio-optimizer-scenario-effect-creates-new-record-and-index-entry-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          scenario-record {:schema-version 1
                           :id "scn_01"
                           :name "Core Hedge"
                           :address address
                           :status :partially-executed
                           :config {:id "scn_01"
                                    :name "Core Hedge"
                                    :status :partially-executed
                                    :metadata {:dirty? false
                                               :updated-at-ms 3000}}
                           :saved-run {:result {:status :solved
                                                :expected-return 0.18
                                                :volatility 0.42}}
                           :execution-ledger [{:row-id "row-1"}]
                           :updated-at-ms 3000}
          scenario-index {:ordered-ids ["scn_01"]
                          :by-id {"scn_01" {:id "scn_01"
                                             :name "Core Hedge"
                                             :status :partially-executed}}}
          store (atom {:wallet {:address address}
                       :portfolio {:optimizer {:scenario-index scenario-index}}})
          calls (atom [])]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (fn [] 5000)
                    portfolio-optimizer-adapters/*next-scenario-id* (fn [_now-ms] "scn_5000")
                    portfolio-optimizer-adapters/*load-scenario!* (fn [scenario-id]
                                                                    (swap! calls conj [:load-scenario scenario-id])
                                                                    (js/Promise.resolve scenario-record))
                    portfolio-optimizer-adapters/*load-scenario-index!* (fn [addr]
                                                                          (swap! calls conj [:load-index addr])
                                                                          (js/Promise.resolve scenario-index))
                    portfolio-optimizer-adapters/*save-scenario!* (fn [scenario-id record]
                                                                     (swap! calls conj [:save-scenario scenario-id record])
                                                                     (js/Promise.resolve true))
                    portfolio-optimizer-adapters/*save-scenario-index!* (fn [addr index]
                                                                          (swap! calls conj [:save-index addr index])
                                                                          (js/Promise.resolve true))]
        (-> (portfolio-optimizer-adapters/duplicate-portfolio-optimizer-scenario-effect
             nil
             store
             "scn_01")
            (.then (fn [duplicated-record]
                     (is (= "scn_5000" (:id duplicated-record)))
                     (is (= "Copy of Core Hedge" (:name duplicated-record)))
                     (is (= [] (:execution-ledger duplicated-record)))
                     (is (= ["scn_5000" "scn_01"]
                            (get-in @store [:portfolio :optimizer :scenario-index :ordered-ids])))
                     (is (= "Copy of Core Hedge"
                            (get-in @store
                                    [:portfolio :optimizer :scenario-index :by-id "scn_5000" :name])))
                     (is (= :duplicated
                            (get-in @store [:portfolio :optimizer :scenario-duplicate-state :status])))
                     (is (= [[:load-scenario "scn_01"]
                             [:load-index address]
                             [:save-scenario "scn_5000" duplicated-record]
                             [:save-index address
                              (get-in @store [:portfolio :optimizer :scenario-index])]]
                            @calls))
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
