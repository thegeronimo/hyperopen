(ns hyperopen.api.fetch-compat-market-metadata-test
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
