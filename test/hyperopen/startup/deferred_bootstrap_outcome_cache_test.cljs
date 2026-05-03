(ns hyperopen.startup.deferred-bootstrap-outcome-cache-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.startup.runtime :as startup-runtime]))

(deftest deferred-bootstrap-refreshes-cache-hydrated-selector-when-outcomes-are-missing-test
  (async done
    (let [fetches (atom [])]
      (-> (startup-runtime/run-deferred-bootstrap!
           {:store (atom {:asset-selector {:cache-hydrated? true
                                           :markets [{:key "perp:BTC"
                                                      :coin "BTC"
                                                      :market-type :perp
                                                      :maxLeverage 50}]}})
            :fetch-asset-selector-markets! (fn [_store opts]
                                             (swap! fetches conj opts)
                                             (js/Promise.resolve :full))
            :mark-performance! (fn [_mark])})
          (.then
           (fn []
             (is (= [{:phase :full}] @fetches))
             (let [skipped-fetches (atom [])]
               (-> (startup-runtime/run-deferred-bootstrap!
                    {:store (atom {:asset-selector {:cache-hydrated? true
                                                    :markets [{:key "perp:BTC"
                                                               :coin "BTC"
                                                               :market-type :perp
                                                               :maxLeverage 50}
                                                              {:key "outcome:0"
                                                               :coin "#0"
                                                               :market-type :outcome}]}})
                     :fetch-asset-selector-markets! (fn [_store opts]
                                                      (swap! skipped-fetches conj opts)
                                                      (js/Promise.resolve :unexpected))
                     :mark-performance! (fn [_mark])})
                   (.then
                    (fn []
                      (is (= [] @skipped-fetches))
                      (done)))))))
          (.catch
           (fn [err]
             (is false (str err))
             (done)))))))

(deftest deferred-bootstrap-refreshes-cache-hydrated-selector-on-portfolio-optimize-route-test
  (async done
    (let [fetches (atom [])]
      (-> (startup-runtime/run-deferred-bootstrap!
           {:store (atom {:router {:path "/portfolio/optimize/new"}
                          :asset-selector {:cache-hydrated? true
                                           :markets [{:key "perp:BTC"
                                                      :coin "BTC"
                                                      :market-type :perp
                                                      :maxLeverage 50}
                                                     {:key "outcome:0"
                                                      :coin "#0"
                                                      :market-type :outcome}]}})
            :fetch-asset-selector-markets! (fn [_store opts]
                                             (swap! fetches conj opts)
                                             (js/Promise.resolve :full))
            :mark-performance! (fn [_mark])})
          (.then
           (fn []
             (is (= [{:phase :full}] @fetches))
             (done)))
          (.catch
           (fn [err]
             (is false (str err))
             (done)))))))
