(ns hyperopen.api.fetch-compat-orders-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.fetch-compat :as fetch-compat]))

(defn- reject-promise
  [message]
  (js/Promise.reject (js/Error. message)))

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
