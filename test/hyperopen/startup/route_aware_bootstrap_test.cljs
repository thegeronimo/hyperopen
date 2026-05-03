(ns hyperopen.startup.route-aware-bootstrap-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account.surface-service :as account-surface-service]
            [hyperopen.platform :as platform]
            [hyperopen.startup.runtime :as startup-runtime]))

(deftest bootstrap-account-data-defers-hidden-surfaces-on-portfolio-performance-route-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        store (atom {:wallet {:address address}
                     :router {:path "/portfolio"}
                     :portfolio-ui {:account-info-tab :performance-metrics}
                     :account-info {:order-history {:request-id 0
                                                    :loading? false}}})
        startup-runtime-atom (atom {:bootstrapped-address nil})
        historical-order-calls (atom [])
        surface-bootstrap-calls (atom [])]
    (with-redefs [account-surface-service/bootstrap-account-surfaces!
                  (fn [surface-deps]
                    (swap! surface-bootstrap-calls conj surface-deps))]
      (startup-runtime/bootstrap-account-data!
       {:startup-runtime startup-runtime-atom
        :store store
        :address address
        :fetch-historical-orders! (fn [_store request-id opts]
                                    (swap! historical-order-calls conj [request-id opts]))
        :log-fn (fn [& _] nil)}))
    (is (= [] @historical-order-calls))
    (is (= 0 (get-in @store [:account-info :order-history :request-id])))
    (is (false? (get-in @store [:account-info :order-history :loading?])))
    (is (= 1 (count @surface-bootstrap-calls)))
    (let [surface-deps (first @surface-bootstrap-calls)]
      (is (= address (:address surface-deps)))
      (is (true? (:defer-non-visible-account-surfaces? surface-deps)))
      (is (number? (:non-visible-account-bootstrap-delay-ms surface-deps))))))

(deftest bootstrap-account-data-keeps-eager-surfaces-off-portfolio-performance-route-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        store (atom {:wallet {:address address}
                     :router {:path "/portfolio"}
                     :portfolio-ui {:account-info-tab :open-orders}
                     :account-info {:order-history {:request-id 0
                                                    :loading? false}}})
        startup-runtime-atom (atom {:bootstrapped-address nil})
        historical-order-calls (atom [])
        surface-bootstrap-calls (atom [])]
    (with-redefs [account-surface-service/bootstrap-account-surfaces!
                  (fn [surface-deps]
                    (swap! surface-bootstrap-calls conj surface-deps))]
      (startup-runtime/bootstrap-account-data!
       {:startup-runtime startup-runtime-atom
        :store store
        :address address
        :fetch-historical-orders! (fn [_store request-id opts]
                                    (swap! historical-order-calls conj [request-id opts]))
        :log-fn (fn [& _] nil)}))
    (is (= [[1 {:priority :low}]]
           @historical-order-calls))
    (is (= 1 (get-in @store [:account-info :order-history :request-id])))
    (is (true? (get-in @store [:account-info :order-history :loading?])))
    (is (= 1 (count @surface-bootstrap-calls)))
    (is (not (true? (:defer-non-visible-account-surfaces?
                     (first @surface-bootstrap-calls)))))))

(deftest start-critical-bootstrap-skips-immediate-asset-fetches-on-portfolio-performance-route-test
  (async done
    (let [store (atom {:router {:path "/portfolio"}
                       :portfolio-ui {:account-info-tab :performance-metrics}})
          context-fetches (atom [])
          selector-fetches (atom [])
          mark-calls (atom [])]
      (-> (startup-runtime/start-critical-bootstrap!
           {:store store
            :fetch-asset-contexts! (fn [_store opts]
                                     (swap! context-fetches conj opts)
                                     (js/Promise.resolve :ctx))
            :fetch-asset-selector-markets! (fn [_store opts]
                                             (swap! selector-fetches conj opts)
                                             (js/Promise.resolve :selector))
            :mark-performance! (fn [mark]
                                 (swap! mark-calls conj mark))})
          (.then
           (fn []
             (is (= [] @context-fetches))
             (is (= [] @selector-fetches))
             (is (= ["app:critical-data:ready"] @mark-calls))
             (done)))
          (.catch
           (fn [err]
             (is false (str "Unexpected critical bootstrap error: " err))
             (done)))))))

(deftest start-critical-bootstrap-fetches-asset-selector-on-portfolio-optimize-route-test
  (async done
    (let [store (atom {:router {:path "/portfolio/optimize/new"}
                       :portfolio-ui {:account-info-tab :performance-metrics}})
          context-fetches (atom [])
          selector-fetches (atom [])
          mark-calls (atom [])]
      (-> (startup-runtime/start-critical-bootstrap!
           {:store store
            :fetch-asset-contexts! (fn [_store opts]
                                     (swap! context-fetches conj opts)
                                     (js/Promise.resolve :ctx))
            :fetch-asset-selector-markets! (fn [_store opts]
                                             (swap! selector-fetches conj opts)
                                             (js/Promise.resolve :selector))
            :mark-performance! (fn [mark]
                                 (swap! mark-calls conj mark))})
          (.then
           (fn []
             (is (= [{:priority :high}] @context-fetches))
             (is (= [{:phase :bootstrap}] @selector-fetches))
             (is (= ["app:critical-data:ready"] @mark-calls))
             (done)))
          (.catch
           (fn [err]
             (is false (str "Unexpected critical bootstrap error: " err))
             (done)))))))

(deftest schedule-deferred-bootstrap-uses-timeout-delay-on-portfolio-performance-route-test
  (let [store (atom {:router {:path "/portfolio"}
                     :portfolio-ui {:account-info-tab :performance-metrics}})
        startup-runtime-atom (atom {:deferred-scheduled? false})
        idle-calls (atom [])
        timeout-calls (atom [])
        run-calls (atom 0)]
    (with-redefs [platform/set-timeout! (fn [callback delay-ms]
                                          (swap! timeout-calls conj delay-ms)
                                          (callback)
                                          :timeout-id)]
      (startup-runtime/schedule-deferred-bootstrap!
       {:store store
        :startup-runtime startup-runtime-atom
        :deferred-bootstrap-delay-ms 2345
        :schedule-idle-or-timeout! (fn [callback]
                                     (swap! idle-calls conj callback))
        :run-deferred-bootstrap! (fn []
                                   (swap! run-calls inc))}))
    (is (= [] @idle-calls))
    (is (= [2345] @timeout-calls))
    (is (= 1 @run-calls))
    (is (true? (:deferred-scheduled? @startup-runtime-atom)))))

(deftest schedule-deferred-bootstrap-keeps-idle-scheduler-off-performance-route-test
  (let [store (atom {:router {:path "/portfolio"}
                     :portfolio-ui {:account-info-tab :open-orders}})
        startup-runtime-atom (atom {:deferred-scheduled? false})
        idle-calls (atom [])
        run-calls (atom 0)]
    (startup-runtime/schedule-deferred-bootstrap!
     {:store store
      :startup-runtime startup-runtime-atom
      :deferred-bootstrap-delay-ms 2345
      :schedule-idle-or-timeout! (fn [callback]
                                   (swap! idle-calls conj callback)
                                   (callback))
      :run-deferred-bootstrap! (fn []
                                 (swap! run-calls inc))})
    (is (= 1 (count @idle-calls)))
    (is (= 1 @run-calls))
    (is (true? (:deferred-scheduled? @startup-runtime-atom)))))

(deftest start-critical-bootstrap-keeps-immediate-asset-fetches-off-performance-route-test
  (async done
    (let [store (atom {:router {:path "/portfolio"}
                       :portfolio-ui {:account-info-tab :open-orders}})
          context-fetches (atom [])
          selector-fetches (atom [])
          mark-calls (atom [])]
      (-> (startup-runtime/start-critical-bootstrap!
           {:store store
            :fetch-asset-contexts! (fn [_store opts]
                                     (swap! context-fetches conj opts)
                                     (js/Promise.resolve :ctx))
            :fetch-asset-selector-markets! (fn [_store opts]
                                             (swap! selector-fetches conj opts)
                                             (js/Promise.resolve :selector))
            :mark-performance! (fn [mark]
                                 (swap! mark-calls conj mark))})
          (.then
           (fn []
             (is (= [{:priority :high}] @context-fetches))
             (is (= [{:phase :bootstrap}] @selector-fetches))
             (is (= ["app:critical-data:ready"] @mark-calls))
             (done)))
          (.catch
           (fn [err]
             (is false (str "Unexpected critical bootstrap error: " err))
             (done)))))))
