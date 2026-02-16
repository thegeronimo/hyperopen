(ns hyperopen.api.fetch-compat-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.fetch-compat :as fetch-compat]))

(defn- reject-promise
  [message]
  (js/Promise.reject (js/Error. message)))

(deftest fetch-asset-contexts-applies-success-and-error-projections-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-asset-contexts! (fn [_opts]
                                           (js/Promise.resolve {:BTC {:idx 0}}))
                :apply-asset-contexts-success (fn [state rows]
                                                (assoc state :asset-contexts rows))
                :apply-asset-contexts-error (fn [state err]
                                              (assoc state :asset-contexts-error (str err)))}]
      (-> (fetch-compat/fetch-asset-contexts! deps store {:priority :high})
          (.then (fn [rows]
                   (is (= {:BTC {:idx 0}} rows))
                   (is (= {:BTC {:idx 0}} (:asset-contexts @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-asset-contexts-applies-error-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-asset-contexts! (fn [_opts]
                                           (reject-promise "asset contexts unavailable"))
                :apply-asset-contexts-success (fn [state rows]
                                                (assoc state :asset-contexts rows))
                :apply-asset-contexts-error (fn [state err]
                                              (assoc state :asset-contexts-error (.-message err)))}]
      (-> (fetch-compat/fetch-asset-contexts! deps store {:priority :high})
          (.then (fn [_]
                   (is false "Expected fetch-asset-contexts! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"asset contexts unavailable" (str err)))
                    (is (= "asset contexts unavailable"
                           (:asset-contexts-error @store)))
                    (done)))))))

(deftest fetch-perp-dexs-applies-success-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-perp-dexs! (fn [_opts]
                                      (js/Promise.resolve ["dex-a" "dex-b"]))
                :apply-perp-dexs-success (fn [state names]
                                           (assoc state :perp-dexs names))
                :apply-perp-dexs-error (fn [state err]
                                         (assoc state :perp-dexs-error (str err)))}]
      (-> (fetch-compat/fetch-perp-dexs! deps store {:priority :high})
          (.then (fn [dexs]
                   (is (= ["dex-a" "dex-b"] dexs))
                   (is (= ["dex-a" "dex-b"] (:perp-dexs @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-perp-dexs-applies-error-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-perp-dexs! (fn [_opts]
                                      (reject-promise "perp dexs unavailable"))
                :apply-perp-dexs-success (fn [state names]
                                           (assoc state :perp-dexs names))
                :apply-perp-dexs-error (fn [state err]
                                         (assoc state :perp-dexs-error (.-message err)))}]
      (-> (fetch-compat/fetch-perp-dexs! deps store {:priority :high})
          (.then (fn [_]
                   (is false "Expected fetch-perp-dexs! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"perp dexs unavailable" (str err)))
                    (is (= "perp dexs unavailable"
                           (:perp-dexs-error @store)))
                    (done)))))))

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

(deftest fetch-frontend-open-orders-defaults-nil-opts-test
  (async done
    (let [store (atom {})
          calls (atom [])
          deps {:log-fn (fn [& _] nil)
                :request-frontend-open-orders! (fn [address opts]
                                                 (swap! calls conj [address opts])
                                                 (js/Promise.resolve [{:oid 1}]))
                :apply-open-orders-success (fn [state dex rows]
                                             (assoc state :open-orders [dex rows]))
                :apply-open-orders-error (fn [state err]
                                           (assoc state :open-orders-error (str err)))}]
      (-> (fetch-compat/fetch-frontend-open-orders! deps store "0xabc" nil)
          (.then (fn [rows]
                   (is (= [{:oid 1}] rows))
                   (is (= [["0xabc" {}]] @calls))
                   (is (= [nil rows] (:open-orders @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-frontend-open-orders-applies-error-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-frontend-open-orders! (fn [_address _opts]
                                                 (reject-promise "open orders failed"))
                :apply-open-orders-success (fn [state _dex rows]
                                             (assoc state :open-orders rows))
                :apply-open-orders-error (fn [state err]
                                           (assoc state :open-orders-error (.-message err)))}]
      (-> (fetch-compat/fetch-frontend-open-orders! deps store "0xabc" {:dex "dex-a"})
          (.then (fn [_]
                   (is false "Expected fetch-frontend-open-orders! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"open orders failed" (str err)))
                    (is (= "open orders failed" (:open-orders-error @store)))
                    (done)))))))

(deftest fetch-user-fills-applies-error-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-user-fills! (fn [_address _opts]
                                       (reject-promise "fills failed"))
                :apply-user-fills-success (fn [state rows]
                                            (assoc state :fills rows))
                :apply-user-fills-error (fn [state err]
                                          (assoc state :fills-error (.-message err)))}]
      (-> (fetch-compat/fetch-user-fills! deps store "0xabc" {})
          (.then (fn [_]
                   (is false "Expected fetch-user-fills! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"fills failed" (str err)))
                    (is (= "fills failed" (:fills-error @store)))
                    (done)))))))

(deftest fetch-historical-orders-logs-and-rejects-on-error-test
  (async done
    (let [logged (atom [])
          deps {:log-fn (fn [& args]
                          (swap! logged conj args))
                :request-historical-orders! (fn [_address _opts]
                                              (reject-promise "historical orders failed"))}]
      (-> (fetch-compat/fetch-historical-orders! deps "0xabc" {})
          (.then (fn [_]
                   (is false "Expected fetch-historical-orders! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"historical orders failed" (str err)))
                    (is (= 1 (count @logged)))
                    (is (= "Error fetching historical orders:"
                           (ffirst @logged)))
                    (done)))))))

(deftest fetch-spot-meta-applies-success-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-spot-meta! (fn [_opts]
                                      (js/Promise.resolve {:tokens [{:coin "USDC"}]}))
                :begin-spot-meta-load (fn [state]
                                        (assoc state :spot-meta-status :loading))
                :apply-spot-meta-success (fn [state meta]
                                           (assoc state :spot-meta meta))
                :apply-spot-meta-error (fn [state err]
                                         (assoc state :spot-meta-error (str err)))}]
      (-> (fetch-compat/fetch-spot-meta! deps store {:priority :high})
          (.then (fn [meta]
                   (is (= {:tokens [{:coin "USDC"}]} meta))
                   (is (= :loading (:spot-meta-status @store)))
                   (is (= meta (:spot-meta @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-spot-meta-applies-error-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-spot-meta! (fn [_opts]
                                      (reject-promise "spot meta failed"))
                :begin-spot-meta-load (fn [state]
                                        (assoc state :spot-meta-status :loading))
                :apply-spot-meta-success (fn [state meta]
                                           (assoc state :spot-meta meta))
                :apply-spot-meta-error (fn [state err]
                                         (assoc state :spot-meta-error (.-message err)))}]
      (-> (fetch-compat/fetch-spot-meta! deps store {})
          (.then (fn [_]
                   (is false "Expected fetch-spot-meta! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"spot meta failed" (str err)))
                    (is (= :loading (:spot-meta-status @store)))
                    (is (= "spot meta failed" (:spot-meta-error @store)))
                    (done)))))))

(deftest ensure-perp-dexs-applies-error-projection-test
  (async done
    (let [store (atom {})
          deps {:ensure-perp-dexs-data! (fn [_store _opts]
                                          (reject-promise "ensure perp failed"))
                :apply-perp-dexs-success (fn [state names]
                                           (assoc state :perp-dexs names))
                :apply-perp-dexs-error (fn [state err]
                                         (assoc state :perp-dexs-error (.-message err)))}]
      (-> (fetch-compat/ensure-perp-dexs! deps store {})
          (.then (fn [_]
                   (is false "Expected ensure-perp-dexs! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"ensure perp failed" (str err)))
                    (is (= "ensure perp failed" (:perp-dexs-error @store)))
                    (done)))))))

(deftest ensure-spot-meta-applies-success-projection-test
  (async done
    (let [store (atom {})
          deps {:ensure-spot-meta-data! (fn [_store _opts]
                                          (js/Promise.resolve {:tokens 7}))
                :apply-spot-meta-success (fn [state meta]
                                           (assoc state :spot-meta meta))
                :apply-spot-meta-error (fn [state err]
                                         (assoc state :spot-meta-error (str err)))}]
      (-> (fetch-compat/ensure-spot-meta! deps store {:priority :high})
          (.then (fn [meta]
                   (is (= {:tokens 7} meta))
                   (is (= meta (:spot-meta @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest ensure-spot-meta-applies-error-projection-test
  (async done
    (let [store (atom {})
          deps {:ensure-spot-meta-data! (fn [_store _opts]
                                          (reject-promise "ensure spot failed"))
                :apply-spot-meta-success (fn [state meta]
                                           (assoc state :spot-meta meta))
                :apply-spot-meta-error (fn [state err]
                                         (assoc state :spot-meta-error (.-message err)))}]
      (-> (fetch-compat/ensure-spot-meta! deps store {})
          (.then (fn [_]
                   (is false "Expected ensure-spot-meta! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"ensure spot failed" (str err)))
                    (is (= "ensure spot failed" (:spot-meta-error @store)))
                    (done)))))))

(deftest fetch-asset-selector-markets-bootstrap-applies-projections-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-asset-selector-markets! (fn [_store _opts]
                                                   (js/Promise.resolve
                                                    {:phase :bootstrap
                                                     :spot-meta {:tokens [{:coin "USDC"}]}
                                                     :market-state {:markets [{:key "perp:BTC"}]}}))
                :begin-asset-selector-load (fn [state phase]
                                             (assoc state :selector-load phase))
                :apply-spot-meta-success (fn [state meta]
                                           (assoc state :spot-meta meta))
                :apply-asset-selector-success (fn [state phase market-state]
                                                (assoc state :selector-state [phase market-state]))
                :apply-asset-selector-error (fn [state err]
                                              (assoc state :selector-error (str err)))}]
      (-> (fetch-compat/fetch-asset-selector-markets! deps store {:phase :bootstrap})
          (.then (fn [markets]
                   (is (= [{:key "perp:BTC"}] markets))
                   (is (= :bootstrap (:selector-load @store)))
                   (is (= {:tokens [{:coin "USDC"}]} (:spot-meta @store)))
                   (is (= [:bootstrap {:markets [{:key "perp:BTC"}]}]
                          (:selector-state @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-asset-selector-markets-full-phase-skips-spot-meta-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-asset-selector-markets! (fn [_store _opts]
                                                   (js/Promise.resolve
                                                    {:phase :full
                                                     :spot-meta {:tokens [{:coin "USDC"}]}
                                                     :market-state {:markets [{:key "spot:BTC"}]}}))
                :begin-asset-selector-load (fn [state phase]
                                             (assoc state :selector-load phase))
                :apply-spot-meta-success nil
                :apply-asset-selector-success (fn [state phase market-state]
                                                (assoc state :selector-state [phase market-state]))
                :apply-asset-selector-error (fn [state err]
                                              (assoc state :selector-error (str err)))}]
      (-> (fetch-compat/fetch-asset-selector-markets! deps store {})
          (.then (fn [markets]
                   (is (= [{:key "spot:BTC"}] markets))
                   (is (= :full (:selector-load @store)))
                   (is (nil? (:spot-meta @store)))
                   (is (= [:full {:markets [{:key "spot:BTC"}]}]
                          (:selector-state @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-asset-selector-markets-applies-error-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-asset-selector-markets! (fn [_store _opts]
                                                   (reject-promise "asset selector failed"))
                :begin-asset-selector-load (fn [state phase]
                                             (assoc state :selector-load phase))
                :apply-spot-meta-success (fn [state meta]
                                           (assoc state :spot-meta meta))
                :apply-asset-selector-success (fn [state _phase market-state]
                                                (assoc state :selector-state market-state))
                :apply-asset-selector-error (fn [state err]
                                              (assoc state :selector-error (.-message err)))}]
      (-> (fetch-compat/fetch-asset-selector-markets! deps store {:phase :full})
          (.then (fn [_]
                   (is false "Expected fetch-asset-selector-markets! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"asset selector failed" (str err)))
                    (is (= "asset selector failed" (:selector-error @store)))
                    (done)))))))

(deftest fetch-spot-clearinghouse-state-skips-when-address-missing-test
  (async done
    (let [store (atom {})
          calls (atom 0)
          deps {:log-fn (fn [& _] nil)
                :request-spot-clearinghouse-state! (fn [_address _opts]
                                                     (swap! calls inc)
                                                     (js/Promise.resolve {}))
                :begin-spot-balances-load (fn [state]
                                            (assoc state :spot-balances-loading? true))
                :apply-spot-balances-success (fn [state data]
                                               (assoc state :spot-balances data))
                :apply-spot-balances-error (fn [state err]
                                             (assoc state :spot-balances-error (str err)))}]
      (-> (fetch-compat/fetch-spot-clearinghouse-state! deps store nil {:priority :high})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (is (nil? (:spot-balances-loading? @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-spot-clearinghouse-state-applies-success-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-spot-clearinghouse-state! (fn [_address _opts]
                                                     (js/Promise.resolve {:balances [{:coin "USDC"}]}))
                :begin-spot-balances-load (fn [state]
                                            (assoc state :spot-balances-loading? true))
                :apply-spot-balances-success (fn [state data]
                                               (assoc state :spot-balances data))
                :apply-spot-balances-error (fn [state err]
                                             (assoc state :spot-balances-error (str err)))}]
      (-> (fetch-compat/fetch-spot-clearinghouse-state! deps store "0xabc" {})
          (.then (fn [data]
                   (is (= {:balances [{:coin "USDC"}]} data))
                   (is (= true (:spot-balances-loading? @store)))
                   (is (= data (:spot-balances @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-spot-clearinghouse-state-applies-error-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-spot-clearinghouse-state! (fn [_address _opts]
                                                     (reject-promise "spot balances failed"))
                :begin-spot-balances-load (fn [state]
                                            (assoc state :spot-balances-loading? true))
                :apply-spot-balances-success (fn [state data]
                                               (assoc state :spot-balances data))
                :apply-spot-balances-error (fn [state err]
                                             (assoc state :spot-balances-error (.-message err)))}]
      (-> (fetch-compat/fetch-spot-clearinghouse-state! deps store "0xabc" {})
          (.then (fn [_]
                   (is false "Expected fetch-spot-clearinghouse-state! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"spot balances failed" (str err)))
                    (is (= "spot balances failed" (:spot-balances-error @store)))
                    (done)))))))

(deftest fetch-user-abstraction-projects-normalized-mode-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}})
          deps {:log-fn (fn [& _] nil)
                :request-user-abstraction! (fn [_address _opts]
                                             (js/Promise.resolve "portfolioMargin"))
                :normalize-user-abstraction-mode (fn [_] :unified)
                :apply-user-abstraction-snapshot
                (fn [state requested-address snapshot]
                  (assoc state :projection [requested-address snapshot]))}]
      (-> (fetch-compat/fetch-user-abstraction! deps store "0xAbC" {})
          (.then (fn [snapshot]
                   (is (= {:mode :unified
                           :abstraction-raw "portfolioMargin"}
                          snapshot))
                   (is (= ["0xabc" snapshot] (:projection @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-user-abstraction-returns-classic-when-address-missing-test
  (async done
    (let [store (atom {})
          called (atom 0)
          deps {:log-fn (fn [& _] nil)
                :request-user-abstraction! (fn [_address _opts]
                                             (swap! called inc)
                                             (js/Promise.resolve "portfolioMargin"))
                :normalize-user-abstraction-mode (fn [_] :unified)
                :apply-user-abstraction-snapshot (fn [state requested-address snapshot]
                                                   (assoc state :projection [requested-address snapshot]))}]
      (-> (fetch-compat/fetch-user-abstraction! deps store nil {})
          (.then (fn [snapshot]
                   (is (= {:mode :classic
                           :abstraction-raw nil}
                          snapshot))
                   (is (= 0 @called))
                   (is (nil? (:projection @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-user-abstraction-rejects-on-request-error-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-user-abstraction! (fn [_address _opts]
                                             (reject-promise "abstraction failed"))
                :normalize-user-abstraction-mode (fn [_] :unified)
                :apply-user-abstraction-snapshot (fn [state requested-address snapshot]
                                                   (assoc state :projection [requested-address snapshot]))}]
      (-> (fetch-compat/fetch-user-abstraction! deps store "0xAbC" {})
          (.then (fn [_]
                   (is false "Expected fetch-user-abstraction! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"abstraction failed" (str err)))
                    (is (nil? (:projection @store)))
                    (done)))))))

(deftest fetch-clearinghouse-state-applies-success-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-clearinghouse-state! (fn [_address _dex _opts]
                                                (js/Promise.resolve {:positions []}))
                :apply-perp-dex-clearinghouse-success (fn [state dex data]
                                                        (assoc-in state [:clearinghouse dex] data))
                :apply-perp-dex-clearinghouse-error (fn [state err]
                                                      (assoc state :clearinghouse-error (str err)))}]
      (-> (fetch-compat/fetch-clearinghouse-state! deps store "0xabc" "dex-a" {:priority :high})
          (.then (fn [data]
                   (is (= {:positions []} data))
                   (is (= data (get-in @store [:clearinghouse "dex-a"])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-clearinghouse-state-applies-error-projection-test
  (async done
    (let [store (atom {})
          deps {:log-fn (fn [& _] nil)
                :request-clearinghouse-state! (fn [_address _dex _opts]
                                                (reject-promise "clearinghouse failed"))
                :apply-perp-dex-clearinghouse-success (fn [state dex data]
                                                        (assoc-in state [:clearinghouse dex] data))
                :apply-perp-dex-clearinghouse-error (fn [state err]
                                                      (assoc state :clearinghouse-error (.-message err)))}]
      (-> (fetch-compat/fetch-clearinghouse-state! deps store "0xabc" "dex-a" {})
          (.then (fn [_]
                   (is false "Expected fetch-clearinghouse-state! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"clearinghouse failed" (str err)))
                    (is (= "clearinghouse failed" (:clearinghouse-error @store)))
                    (done)))))))

(deftest fetch-perp-dex-clearinghouse-states-batches-per-dex-test
  (async done
    (let [calls (atom [])
          deps {:fetch-clearinghouse-state! (fn [_store address dex opts]
                                              (swap! calls conj [address dex opts])
                                              (js/Promise.resolve {:dex dex}))}]
      (-> (fetch-compat/fetch-perp-dex-clearinghouse-states! deps
                                                              (atom {})
                                                              "0xabc"
                                                              ["dex-a" "dex-b"]
                                                              {:priority :high})
          (.then (fn [results]
                   (is (= [{:dex "dex-a"} {:dex "dex-b"}]
                          (js->clj results :keywordize-keys true)))
                   (is (= [["0xabc" "dex-a" {:priority :high}]
                           ["0xabc" "dex-b" {:priority :high}]]
                          @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-perp-dex-clearinghouse-states-skips-when-input-missing-test
  (async done
    (let [calls (atom 0)
          deps {:fetch-clearinghouse-state! (fn [& _]
                                              (swap! calls inc)
                                              (js/Promise.resolve :ok))}]
      (-> (js/Promise.all
           #js [(fetch-compat/fetch-perp-dex-clearinghouse-states! deps (atom {}) nil ["dex-a"] {})
                (fetch-compat/fetch-perp-dex-clearinghouse-states! deps (atom {}) "0xabc" [] {})])
          (.then (fn [results]
                   (is (= [nil nil] (js->clj results)))
                   (is (= 0 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))
