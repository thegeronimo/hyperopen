(ns hyperopen.api.market-metadata.facade-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.market-metadata.facade :as market-metadata]))

(defn- reject-promise
  [message]
  (js/Promise.reject (js/Error. message)))

(deftest fetch-and-apply-perp-dex-metadata-projects-success-and-returns-names-test
  (async done
    (let [store (atom {})
          payload [{:name "dex-a" :deployerFeeScale "0.5"}
                   "dex-b"]
          deps {:store store
                :log-fn (fn [& _] nil)
                :request-perp-dexs! (fn [_opts]
                                      (js/Promise.resolve payload))
                :apply-perp-dexs-success (fn [state projected]
                                           (assoc state :projected projected))
                :apply-perp-dexs-error (fn [state err]
                                         (assoc state :error (.-message err)))}]
      (-> (market-metadata/fetch-and-apply-perp-dex-metadata! deps {:priority :high})
          (.then (fn [dex-names]
                   (is (= ["dex-a" "dex-b"] dex-names))
                   (is (= payload (:projected @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-and-apply-perp-dex-metadata-projects-error-and-rejects-test
  (async done
    (let [store (atom {})
          logs (atom [])
          deps {:store store
                :log-fn (fn [& args]
                          (swap! logs conj args))
                :request-perp-dexs! (fn [_opts]
                                      (reject-promise "fetch failed"))
                :apply-perp-dexs-success (fn [state payload]
                                           (assoc state :projected payload))
                :apply-perp-dexs-error (fn [state err]
                                         (assoc state :error (.-message err)))}]
      (-> (market-metadata/fetch-and-apply-perp-dex-metadata! deps {:priority :low})
          (.then (fn [_]
                   (is false "Expected facade fetch to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"fetch failed" (str err)))
                    (is (= "fetch failed" (:error @store)))
                    (is (= "Error fetching perp DEX list:"
                           (ffirst @logs)))
                    (done)))))))

(deftest ensure-and-apply-perp-dex-metadata-projects-success-and-returns-names-test
  (async done
    (let [store (atom {})
          calls (atom [])
          payload {:dex-names ["dex-a"]
                   :fee-config-by-name {"dex-a" {:deployer-fee-scale 0.1}}}
          deps {:store store
                :ensure-perp-dexs-data! (fn [store* opts]
                                          (swap! calls conj [store* opts])
                                          (js/Promise.resolve payload))
                :apply-perp-dexs-success (fn [state projected]
                                           (assoc state :projected projected))
                :apply-perp-dexs-error (fn [state err]
                                         (assoc state :error (.-message err)))}]
      (-> (market-metadata/ensure-and-apply-perp-dex-metadata! deps {:priority :high})
          (.then (fn [dex-names]
                   (is (= ["dex-a"] (vec dex-names)))
                   (is (= payload (:projected @store)))
                   (is (= [[store {:priority :high}]] @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest ensure-and-apply-perp-dex-metadata-projects-error-and-rejects-test
  (async done
    (let [store (atom {})
          deps {:store store
                :ensure-perp-dexs-data! (fn [_store _opts]
                                          (reject-promise "ensure failed"))
                :apply-perp-dexs-success (fn [state payload]
                                           (assoc state :projected payload))
                :apply-perp-dexs-error (fn [state err]
                                         (assoc state :error (.-message err)))}]
      (-> (market-metadata/ensure-and-apply-perp-dex-metadata! deps {:priority :low})
          (.then (fn [_]
                   (is false "Expected facade ensure to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"ensure failed" (str err)))
                    (is (= "ensure failed" (:error @store)))
                    (done)))))))

(deftest ensure-perp-dex-names-normalizes-and-filters-nil-test
  (async done
    (let [calls (atom [])
          deps {:ensure-perp-dexs-data! (fn [opts]
                                          (swap! calls conj opts)
                                          (js/Promise.resolve ["dex-a" nil {:name "dex-b"} {:name ""}]))}]
      (-> (market-metadata/ensure-perp-dex-names! deps {:priority :low})
          (.then (fn [dex-names]
                   (is (= ["dex-a" "dex-b"] dex-names))
                   (is (= [{:priority :low}] @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))
