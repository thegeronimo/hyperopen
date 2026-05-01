(ns hyperopen.api.fetch-compat-asset-selector-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.fetch-compat :as fetch-compat]))

(defn- reject-promise
  [message]
  (js/Promise.reject (js/Error. message)))

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
