(ns hyperopen.account.surface-service-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account.surface-service :as surface-service]))

(def ^:private address
  "0x1111111111111111111111111111111111111111")

(deftest schedule-stream-backed-fallback-skips-live-streams-and-stale-addresses-test
  (let [scheduled-callback (atom nil)
        fetch-calls (atom [])
        store (atom {:wallet {:address address}})]
    (surface-service/schedule-stream-backed-fallback!
     {:store store
      :address address
      :topic "openOrders"
      :fetch-fn (fn [_store fetch-address opts]
                  (swap! fetch-calls conj [fetch-address opts]))
      :opts {:priority :high}
      :startup-stream-backfill-delay-ms 12
      :set-timeout-fn (fn [callback _delay-ms]
                        (reset! scheduled-callback callback)
                        :timeout-id)})
    (is (fn? @scheduled-callback))
    (swap! store assoc-in [:wallet :address]
           "0x2222222222222222222222222222222222222222")
    (@scheduled-callback)
    (is (= [] @fetch-calls))
    (reset! scheduled-callback nil)
    (reset! fetch-calls [])
    (swap! store assoc
           :wallet {:address address}
           :websocket {:health {:transport {:state :connected
                                            :freshness :live}
                                :streams {["openOrders" nil address nil nil]
                                          {:topic "openOrders"
                                           :status :live
                                           :subscribed? true
                                           :descriptor {:type "openOrders"
                                                        :user address}}}}})
    (surface-service/schedule-stream-backed-fallback!
     {:store store
      :address address
      :topic "openOrders"
      :fetch-fn (fn [_store fetch-address opts]
                  (swap! fetch-calls conj [fetch-address opts]))
      :opts {:priority :high}
      :startup-stream-backfill-delay-ms 12
      :set-timeout-fn (fn [callback _delay-ms]
                        (reset! scheduled-callback callback)
                        :timeout-id)})
    (is (nil? @scheduled-callback))
    (is (= [] @fetch-calls))))

(deftest refresh-after-user-fill-respects-stream-and-ready-snapshot-coverage-test
  (async done
    (let [dex "vault"
          refresh-open-orders-calls (atom [])
          refresh-default-clearinghouse-calls (atom [])
          refresh-spot-calls (atom [])
          refresh-perp-dex-calls (atom [])
          sync-calls (atom [])
          store (atom
                 {:wallet {:address address}
                  :router {:path "/trade"}
                  :account-info {:selected-tab :balances}
                  :perp-dex-clearinghouse {dex {:account-value "1"}}
                  :websocket {:health {:transport {:state :connected
                                                   :freshness :live}
                                       :streams {["openOrders" nil address nil nil]
                                                 {:topic "openOrders"
                                                  :status :n-a
                                                  :subscribed? true
                                                  :descriptor {:type "openOrders"
                                                               :user address}}
                                                 ["webData2" nil address nil nil]
                                                 {:topic "webData2"
                                                  :status :n-a
                                                  :subscribed? true
                                                  :descriptor {:type "webData2"
                                                               :user address}}
                                                 ["clearinghouseState" nil address dex nil]
                                                 {:topic "clearinghouseState"
                                                 :status :idle
                                                  :subscribed? true
                                                  :descriptor {:type "clearinghouseState"
                                                               :user address
                                                               :dex dex}}}}}})]
      (surface-service/refresh-after-user-fill!
       {:store store
        :address address
        :ensure-perp-dexs! (fn [_store _opts]
                             (js/Promise.resolve [dex]))
        :sync-perp-dex-clearinghouse-subscriptions! (fn [sync-address dex-names]
                                                      (swap! sync-calls conj [sync-address dex-names]))
        :refresh-open-orders! (fn [_store refresh-address refresh-dex opts]
                                (swap! refresh-open-orders-calls conj [refresh-address refresh-dex opts]))
        :refresh-default-clearinghouse! (fn [_store refresh-address opts]
                                          (swap! refresh-default-clearinghouse-calls conj [refresh-address opts]))
        :refresh-spot-clearinghouse! (fn [_store refresh-address opts]
                                       (swap! refresh-spot-calls conj [refresh-address opts]))
        :refresh-perp-dex-clearinghouse! (fn [_store refresh-address refresh-dex opts]
                                           (swap! refresh-perp-dex-calls conj [refresh-address refresh-dex opts]))})
      (js/setTimeout
       (fn []
         (is (= [] @refresh-open-orders-calls))
         (is (= [] @refresh-default-clearinghouse-calls))
         (is (= [[address {:priority :high
                           :force-refresh? true}]]
                @refresh-spot-calls))
         (is (= [] @refresh-perp-dex-calls))
         (is (= [[address [dex]]]
                @sync-calls))
         (done))
       0))))

(deftest refresh-after-order-mutation-forces-spot-refresh-when-requested-test
  (async done
    (let [refresh-spot-calls (atom [])
          store (atom {:wallet {:address address}
                       :websocket {:health {:transport {:state :connected
                                                        :freshness :live}
                                            :streams {["openOrders" nil address nil nil]
                                                      {:topic "openOrders"
                                                       :status :live
                                                       :subscribed? true
                                                       :descriptor {:type "openOrders"
                                                                    :user address}}
                                                      ["webData2" nil address nil nil]
                                                      {:topic "webData2"
                                                       :status :live
                                                       :subscribed? true
                                                       :descriptor {:type "webData2"
                                                                    :user address}}}}}})]
      (surface-service/refresh-after-order-mutation!
       {:store store
        :address address
        :refresh-spot? true
        :ensure-perp-dexs! (fn [_store _opts]
                             (js/Promise.resolve []))
        :refresh-spot-clearinghouse! (fn [_store refresh-address opts]
                                       (swap! refresh-spot-calls conj [refresh-address opts]))})
      (js/setTimeout
       (fn []
         (is (= [[address {:priority :high
                           :force-refresh? true}]]
                @refresh-spot-calls))
         (done))
       0))))

(deftest refresh-after-order-mutation-keeps-per-dex-backstop-until-snapshot-ready-test
  (async done
    (let [dex "vault"
          refresh-open-orders-calls (atom [])
          refresh-default-clearinghouse-calls (atom [])
          refresh-perp-dex-calls (atom [])
          store (atom
                 {:wallet {:address address}
                  :websocket
                  {:health
                   {:transport {:state :connected
                                :freshness :live}
                    :streams {["openOrders" nil address nil nil]
                              {:topic "openOrders"
                               :status :live
                               :subscribed? true
                               :descriptor {:type "openOrders"
                                            :user address}}
                              ["webData2" nil address nil nil]
                              {:topic "webData2"
                               :status :live
                               :subscribed? true
                               :descriptor {:type "webData2"
                                            :user address}}
                              ["clearinghouseState" nil address dex nil]
                              {:topic "clearinghouseState"
                               :status :live
                               :subscribed? true
                               :descriptor {:type "clearinghouseState"
                                            :user address
                                            :dex dex}}}}}})]
      (surface-service/refresh-after-order-mutation!
       {:store store
        :address address
        :ensure-perp-dexs! (fn [_store _opts]
                             (js/Promise.resolve [dex]))
        :refresh-open-orders! (fn [_store refresh-address refresh-dex opts]
                                (swap! refresh-open-orders-calls conj [refresh-address refresh-dex opts]))
        :refresh-default-clearinghouse! (fn [_store refresh-address opts]
                                          (swap! refresh-default-clearinghouse-calls conj [refresh-address opts]))
        :refresh-perp-dex-clearinghouse! (fn [_store refresh-address refresh-dex opts]
                                           (swap! refresh-perp-dex-calls conj [refresh-address refresh-dex opts]))})
      (js/setTimeout
       (fn []
         (is (= [[address dex {:priority :low}]]
                @refresh-open-orders-calls))
         (is (= [] @refresh-default-clearinghouse-calls))
         ;; A healthy subscription alone is not enough for the order path; until the
         ;; local per-dex snapshot is seeded, keep the REST backstop.
         (is (= [[address dex {:priority :low}]]
                @refresh-perp-dex-calls))
         (done))
       0))))

(deftest schedule-stream-backed-fallback-fetches-when-open-orders-stream-is-live-but-surface-not-hydrated-test
  (let [scheduled-callback (atom nil)
        fetch-calls (atom [])
        store (atom {:wallet {:address address}
                     :orders {:open-orders []
                              :open-orders-hydrated? false}
                     :websocket {:health {:transport {:state :connected
                                                      :freshness :live}
                                          :streams {["openOrders" nil address nil nil]
                                                    {:topic "openOrders"
                                                     :status :live
                                                     :subscribed? true
                                                     :descriptor {:type "openOrders"
                                                                  :user address}}}}}})]
    (surface-service/schedule-stream-backed-fallback!
     {:store store
      :address address
      :topic "openOrders"
      :fetch-fn (fn [_store fetch-address opts]
                  (swap! fetch-calls conj [fetch-address opts]))
      :opts {:priority :high}
      :surface-hydrated? #(true? (get-in % [:orders :open-orders-hydrated?]))
      :startup-stream-backfill-delay-ms 12
      :set-timeout-fn (fn [callback _delay-ms]
                        (reset! scheduled-callback callback)
                        :timeout-id)})
    (is (fn? @scheduled-callback))
    (@scheduled-callback)
    (is (= [[address {:priority :high}]]
           @fetch-calls))
    (reset! scheduled-callback nil)
    (reset! fetch-calls [])
    (swap! store assoc-in [:orders :open-orders-hydrated?] true)
    (surface-service/schedule-stream-backed-fallback!
     {:store store
      :address address
      :topic "openOrders"
      :fetch-fn (fn [_store fetch-address opts]
                  (swap! fetch-calls conj [fetch-address opts]))
      :opts {:priority :high}
      :surface-hydrated? #(true? (get-in % [:orders :open-orders-hydrated?]))
      :startup-stream-backfill-delay-ms 12
      :set-timeout-fn (fn [callback _delay-ms]
                        (reset! scheduled-callback callback)
                        :timeout-id)})
    (is (nil? @scheduled-callback))
    (is (= [] @fetch-calls))))

(deftest bootstrap-account-surfaces-skips-live-clearinghouse-subscriptions-on-trader-portfolio-route-test
  (async done
    (let [dex "vault"
          sync-calls (atom [])
          clearinghouse-calls (atom [])
          store (atom {:wallet {:address address}
                       :router {:path (str "/portfolio/trader/" address)}
                       :websocket {:migration-flags {:startup-bootstrap-ws-first? false}}})]
      (surface-service/bootstrap-account-surfaces!
       {:store store
        :address address
        :fetch-frontend-open-orders! (fn [_store _address _opts] nil)
        :fetch-user-fills! (fn [_store _address _opts] nil)
        :fetch-spot-clearinghouse-state! (fn [_store _address _opts] nil)
        :fetch-user-abstraction! (fn [_store _address _opts] nil)
        :fetch-portfolio! (fn [_store _address _opts] nil)
        :fetch-user-fees! (fn [_store _address _opts] nil)
        :fetch-and-merge-funding-history! (fn [_store _address _opts] nil)
        :ensure-perp-dexs! (fn [_store _opts]
                             (js/Promise.resolve [dex]))
        :set-timeout-fn (fn [callback _delay-ms]
                          (callback)
                          :timeout-id)
        :sync-perp-dex-clearinghouse-subscriptions! (fn [sync-address dex-names]
                                                      (swap! sync-calls conj [sync-address dex-names]))
        :fetch-clearinghouse-state! (fn [_store fetch-address fetch-dex opts]
                                      (swap! clearinghouse-calls conj [fetch-address fetch-dex opts]))})
      (js/setTimeout
       (fn []
         (is (= [] @sync-calls))
         (is (= [[address dex {:priority :low}]]
                @clearinghouse-calls))
         (done))
       0))))

(deftest bootstrap-account-surfaces-defers-non-visible-account-surfaces-test
  (async done
    (let [deferred-delay-ms 37
          funding-opts {:priority :high
                        :start-time-ms 1000
                        :end-time-ms 2000}
          store (atom {:wallet {:address address}
                       :websocket {:migration-flags {:startup-bootstrap-ws-first? false}}})
          visible-calls (atom [])
          hidden-calls (atom [])
          ensure-calls (atom [])
          stage-b-calls (atom [])
          scheduled-callbacks (atom [])]
      (surface-service/bootstrap-account-surfaces!
       {:store store
        :address address
        :defer-non-visible-account-surfaces? true
        :non-visible-account-bootstrap-delay-ms deferred-delay-ms
        :startup-funding-request-opts funding-opts
        :set-timeout-fn (fn [callback delay-ms]
                          (swap! scheduled-callbacks conj {:callback callback
                                                           :delay-ms delay-ms})
                          :timeout-id)
        :fetch-frontend-open-orders! (fn [_store fetch-address opts]
                                       (swap! hidden-calls conj [:open-orders fetch-address opts]))
        :fetch-user-fills! (fn [_store fetch-address opts]
                             (swap! hidden-calls conj [:fills fetch-address opts]))
        :fetch-spot-clearinghouse-state! (fn [_store fetch-address opts]
                                           (swap! visible-calls conj [:spot fetch-address opts]))
        :fetch-user-abstraction! (fn [_store fetch-address opts]
                                   (swap! visible-calls conj [:abstraction fetch-address opts]))
        :fetch-portfolio! (fn [_store fetch-address opts]
                            (swap! visible-calls conj [:portfolio fetch-address opts]))
        :fetch-user-fees! (fn [_store fetch-address opts]
                            (swap! visible-calls conj [:user-fees fetch-address opts]))
        :fetch-and-merge-funding-history! (fn [_store fetch-address opts]
                                            (swap! hidden-calls conj [:fundings fetch-address opts]))
        :ensure-perp-dexs! (fn [_store opts]
                             (swap! ensure-calls conj opts)
                             (js/Promise.resolve ["dex-a"]))
        :stage-b-account-bootstrap! (fn [stage-address dex-names]
                                      (swap! stage-b-calls conj [stage-address dex-names]))})
      (is (= [[:spot address {:priority :high}]
              [:abstraction address {:priority :high}]
              [:portfolio address {:priority :high}]
              [:user-fees address {:priority :high}]]
             @visible-calls))
      (is (= [] @hidden-calls))
      (is (= [] @ensure-calls))
      (is (= [] @stage-b-calls))
      (is (= [deferred-delay-ms]
             (mapv :delay-ms @scheduled-callbacks)))
      (when-let [callback (:callback (first @scheduled-callbacks))]
        (callback))
      (js/setTimeout
       (fn []
         (is (= [[:open-orders address {:priority :low}]
                 [:fills address {:priority :low}]
                 [:fundings address (assoc funding-opts :priority :low)]]
                @hidden-calls))
         (is (= [{:priority :low}]
                @ensure-calls))
         (is (= [[address ["dex-a"]]]
                @stage-b-calls))
         (done))
       0))))

(deftest bootstrap-account-surfaces-deferred-warmup-skips-stale-address-test
  (async done
    (let [store (atom {:wallet {:address address}
                       :websocket {:migration-flags {:startup-bootstrap-ws-first? false}}})
          hidden-calls (atom [])
          ensure-calls (atom [])
          stage-b-calls (atom [])
          scheduled-callbacks (atom [])]
      (surface-service/bootstrap-account-surfaces!
       {:store store
        :address address
        :defer-non-visible-account-surfaces? true
        :non-visible-account-bootstrap-delay-ms 12
        :set-timeout-fn (fn [callback delay-ms]
                          (swap! scheduled-callbacks conj {:callback callback
                                                           :delay-ms delay-ms})
                          :timeout-id)
        :fetch-frontend-open-orders! (fn [_store fetch-address opts]
                                       (swap! hidden-calls conj [:open-orders fetch-address opts]))
        :fetch-user-fills! (fn [_store fetch-address opts]
                             (swap! hidden-calls conj [:fills fetch-address opts]))
        :fetch-spot-clearinghouse-state! (fn [& _] nil)
        :fetch-user-abstraction! (fn [& _] nil)
        :fetch-portfolio! (fn [& _] nil)
        :fetch-user-fees! (fn [& _] nil)
        :fetch-and-merge-funding-history! (fn [_store fetch-address opts]
                                            (swap! hidden-calls conj [:fundings fetch-address opts]))
        :ensure-perp-dexs! (fn [_store opts]
                             (swap! ensure-calls conj opts)
                             (js/Promise.resolve ["dex-a"]))
        :stage-b-account-bootstrap! (fn [stage-address dex-names]
                                      (swap! stage-b-calls conj [stage-address dex-names]))})
      (swap! store assoc-in [:wallet :address]
             "0x2222222222222222222222222222222222222222")
      (when-let [callback (:callback (first @scheduled-callbacks))]
        (callback))
      (js/setTimeout
       (fn []
         (is (= [] @hidden-calls))
         (is (= [] @ensure-calls))
         (is (= [] @stage-b-calls))
         (done))
       0))))
