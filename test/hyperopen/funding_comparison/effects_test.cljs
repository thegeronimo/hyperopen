(ns hyperopen.funding-comparison.effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding-comparison.effects :as effects]))

(deftest api-fetch-predicted-fundings-applies-begin-and-success-projections-test
  (async done
    (let [request-calls (atom [])
          store (atom {})]
      (-> (effects/api-fetch-predicted-fundings!
           {:store store
            :request-predicted-fundings! (fn [opts]
                                           (swap! request-calls conj opts)
                                           (js/Promise.resolve [["BTC" []]]))
            :begin-funding-comparison-load (fn [state]
                                             (assoc state :loading? true))
            :apply-funding-comparison-success (fn [state rows]
                                                (assoc state :rows rows))
            :apply-funding-comparison-error (fn [state err]
                                              (assoc state :error err))
            :opts {:priority :high}})
          (.then (fn [rows]
                   (is (= [["BTC" []]] rows))
                   (is (= [{:priority :high}] @request-calls))
                   (is (= true (:loading? @store)))
                   (is (= [["BTC" []]] (:rows @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected predicted fundings success-path error")
                    (done)))))))

(deftest api-fetch-predicted-fundings-applies-error-projection-test
  (async done
    (let [store (atom {})]
      (-> (effects/api-fetch-predicted-fundings!
           {:store store
            :request-predicted-fundings! (fn [_opts]
                                           (js/Promise.reject (js/Error. "boom")))
            :begin-funding-comparison-load (fn [state]
                                             (assoc state :loading? true))
            :apply-funding-comparison-success (fn [state rows]
                                                (assoc state :rows rows))
            :apply-funding-comparison-error (fn [state err]
                                              (assoc state :error (.-message err)))})
          (.then (fn [_]
                   (is false "Expected predicted fundings request to reject")
                   (done)))
          (.catch (fn [err]
                    (is (= "boom" (.-message err)))
                    (is (= true (:loading? @store)))
                    (is (= "boom" (:error @store)))
                    (done)))))))
