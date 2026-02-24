(ns hyperopen.startup.runtime-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.platform :as platform]
            [hyperopen.startup.runtime :as startup-runtime]))

(defn- with-global-property
  [property-name value f]
  (let [original-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis property-name)]
    (js/Object.defineProperty js/globalThis property-name
                              #js {:value value
                                   :configurable true
                                   :writable true})
    (try
      (f)
      (finally
        (if original-descriptor
          (js/Object.defineProperty js/globalThis property-name original-descriptor)
          (js/Reflect.deleteProperty js/globalThis property-name))))))

(deftest default-startup-runtime-state-shape-test
  (is (= {:deferred-scheduled? false
          :bootstrapped-address nil
          :summary-logged? false}
         (startup-runtime/default-startup-runtime-state))))

(deftest schedule-idle-or-timeout-covers-idle-and-fallback-branches-test
  (testing "requestIdleCallback path is preferred when available"
    (let [idle-calls (atom [])
          ran? (atom false)]
      (with-global-property
        "window"
        #js {:requestIdleCallback (fn [callback opts]
                                    (swap! idle-calls conj (js->clj opts :keywordize-keys true))
                                    (callback #js {:didTimeout false})
                                    :idle-id)}
        (fn []
          (with-redefs [platform/set-timeout! (fn [_ _]
                                                (throw (js/Error. "fallback should not run")))]
            (startup-runtime/schedule-idle-or-timeout! 123 #(reset! ran? true))
            (is (true? @ran?))
            (is (= [{:timeout 123}] @idle-calls)))))))
  (testing "fallback timeout branch runs when idle callback is unavailable"
    (let [fallback-calls (atom [])]
      (with-redefs [platform/set-timeout! (fn [f delay-ms]
                                            (swap! fallback-calls conj delay-ms)
                                            (f)
                                            :timeout-id)]
        (with-global-property "window" #js {}
          (fn []
            (is (= :timeout-id
                   (startup-runtime/schedule-idle-or-timeout! 77 (fn [] nil))))
            (is (= [77] @fallback-calls))))))))

(deftest schedule-startup-summary-log-covers-idempotent-and-runtime-fallback-branches-test
  (let [store (atom {:websocket {:status :connected}
                    :asset-selector {:loading? false
                                     :phase :bootstrap
                                     :loaded-at-ms 11}})
        logs (atom [])
        timer-callback (atom nil)
        timer-calls (atom 0)
        startup-runtime-atom (atom {:summary-logged? false})]
    (with-redefs [platform/set-timeout! (fn [f _]
                                          (swap! timer-calls inc)
                                          (reset! timer-callback f)
                                          :timer-id)]
      (startup-runtime/schedule-startup-summary-log!
       {:startup-runtime startup-runtime-atom
        :store store
        :get-request-stats (fn [] {:pending 1})
        :delay-ms 5
        :log-fn (fn [& args]
                  (swap! logs conj args))})
      (is (true? (:summary-logged? @startup-runtime-atom)))
      (is (= 1 @timer-calls))
      (@timer-callback)
      (is (= 1 (count @logs)))
      (startup-runtime/schedule-startup-summary-log!
       {:startup-runtime startup-runtime-atom
        :store store
        :get-request-stats (fn [] {:pending 2})
        :delay-ms 5
        :log-fn (fn [& args]
                  (swap! logs conj args))})
      (is (= 1 @timer-calls))))
  (let [runtime (atom {:startup {:summary-logged? false}})]
    (with-redefs [platform/set-timeout! (fn [_ _]
                                          :timer-id)]
      (startup-runtime/schedule-startup-summary-log!
       {:runtime runtime
        :store (atom {:websocket {:status :connected}
                      :asset-selector {:loading? false :phase :bootstrap :loaded-at-ms 0}})
        :get-request-stats (fn [] {:pending 0})
        :delay-ms 1
        :log-fn (fn [& _] nil)})
      (is (true? (get-in @runtime [:startup :summary-logged?]))))))

(deftest register-icon-service-worker-covers-success-failure-and-unsupported-branches-test
  (async done
    (let [logs (atom [])]
      (with-global-property
        "navigator"
        #js {:serviceWorker
             #js {:register (fn [_path]
                              (js/Promise.resolve #js {:scope "/"}))}}
        (fn []
          (startup-runtime/register-icon-service-worker!
           {:icon-service-worker-path "/icons-sw.js"
            :log-fn (fn [& args]
                      (swap! logs conj args))})))
      (js/setTimeout
       (fn []
         (is (= "Registered icon cache service worker."
                (ffirst @logs)))
         (with-global-property
           "navigator"
           #js {:serviceWorker
                #js {:register (fn [_path]
                                 (js/Promise.reject (js/Error. "register-failed")))}}
           (fn []
             (startup-runtime/register-icon-service-worker!
              {:icon-service-worker-path "/icons-sw.js"
               :log-fn (fn [& args]
                         (swap! logs conj args))})))
         (js/setTimeout
          (fn []
            (is (= "Service worker registration failed:"
                   (first (nth @logs 1))))
            (with-global-property
              "navigator"
              #js {}
              (fn []
                (startup-runtime/register-icon-service-worker!
                 {:icon-service-worker-path "/icons-sw.js"
                  :log-fn (fn [& args]
                            (swap! logs conj args))})))
            (js/setTimeout
             (fn []
               (is (= 2 (count @logs)))
               (done))
             0))
          0))
       0))))

(deftest install-asset-selector-shortcuts-registers-global-keydown-and-dispatches-supported-shortcuts-test
  (let [registered-handlers (atom {})
        removed-handlers (atom [])
        dispatch-calls (atom [])
        prevent-default-calls (atom 0)
        store (atom {:asset-selector {:visible-dropdown nil}})
        dispatch! (fn [store-arg _ctx effects]
                    (swap! dispatch-calls conj {:store store-arg
                                                :effects effects}))]
    (with-global-property
      "window"
      #js {:addEventListener (fn [event-name handler]
                               (swap! registered-handlers assoc event-name handler))
           :removeEventListener (fn [event-name handler]
                                  (swap! removed-handlers conj [event-name handler]))}
      (fn []
        (startup-runtime/install-asset-selector-shortcuts!
         {:store store
          :dispatch! dispatch!})
        (let [keydown-handler (get @registered-handlers "keydown")]
          (is (fn? keydown-handler))
          (keydown-handler #js {:key "k"
                                :metaKey true
                                :ctrlKey false
                                :preventDefault (fn []
                                                  (swap! prevent-default-calls inc))})
          (is (= 1 @prevent-default-calls))
          (is (= [[:actions/handle-asset-selector-shortcut "k" true false nil]]
                 (-> @dispatch-calls first :effects)))
          (swap! store assoc-in [:asset-selector :visible-dropdown] :asset-selector)
          (keydown-handler #js {:key "Escape"
                                :metaKey false
                                :ctrlKey false
                                :preventDefault (fn [] nil)})
          (is (= [[:actions/handle-asset-selector-shortcut "Escape" false false nil]]
                 (-> @dispatch-calls second :effects)))
          (startup-runtime/install-asset-selector-shortcuts!
           {:store store
            :dispatch! dispatch!})
          (is (= 1 (count @removed-handlers))))))))

(deftest stage-b-account-bootstrap-covers-shape-guard-and-empty-dex-branches-test
  (let [timeouts (atom [])
        open-orders-calls (atom [])
        clearinghouse-calls (atom [])
        store (atom {:wallet {:address "0xabc"}})]
    (with-redefs [platform/set-timeout! (fn [f delay-ms]
                                          (swap! timeouts conj delay-ms)
                                          (f)
                                          :timeout-id)]
      (startup-runtime/stage-b-account-bootstrap!
       {:store store
        :address "0xabc"
        :dexs ["dex-a" "dex-b"]
        :per-dex-stagger-ms 25
        :fetch-frontend-open-orders! (fn [_store address opts]
                                       (swap! open-orders-calls conj [address opts]))
        :fetch-clearinghouse-state! (fn [_store address dex opts]
                                      (swap! clearinghouse-calls conj [address dex opts]))})
      (is (= [25 50] @timeouts))
      (is (= [["0xabc" {:dex "dex-a" :priority :low}]
              ["0xabc" {:dex "dex-b" :priority :low}]]
             @open-orders-calls))
      (is (= [["0xabc" "dex-a" {:priority :low}]
              ["0xabc" "dex-b" {:priority :low}]]
             @clearinghouse-calls))
      (startup-runtime/stage-b-account-bootstrap!
       {:store store
        :address "0xabc"
        :dexs {:dex-names ["dex-c" " " nil "dex-d"]
               :fee-config-by-name {"dex-c" {:deployer-fee-scale 0.1}
                                    "dex-d" {:deployer-fee-scale 0.2}}}
        :per-dex-stagger-ms 25
        :fetch-frontend-open-orders! (fn [_store address opts]
                                       (swap! open-orders-calls conj [address opts]))
        :fetch-clearinghouse-state! (fn [_store address dex opts]
                                      (swap! clearinghouse-calls conj [address dex opts]))})
      (is (= [25 50 25 50] @timeouts))
      (is (= [["0xabc" {:dex "dex-a" :priority :low}]
              ["0xabc" {:dex "dex-b" :priority :low}]
              ["0xabc" {:dex "dex-c" :priority :low}]
              ["0xabc" {:dex "dex-d" :priority :low}]]
             @open-orders-calls))
      (is (= [["0xabc" "dex-a" {:priority :low}]
              ["0xabc" "dex-b" {:priority :low}]
              ["0xabc" "dex-c" {:priority :low}]
              ["0xabc" "dex-d" {:priority :low}]]
             @clearinghouse-calls))
      (swap! store assoc-in [:wallet :address] "0xnew")
      (startup-runtime/stage-b-account-bootstrap!
       {:store store
        :address "0xold"
        :dexs ["dex-x"]
        :per-dex-stagger-ms 25
        :fetch-frontend-open-orders! (fn [_store address opts]
                                       (swap! open-orders-calls conj [address opts]))
        :fetch-clearinghouse-state! (fn [_store address dex opts]
                                      (swap! clearinghouse-calls conj [address dex opts]))})
      (is (= 4 (count @open-orders-calls)))
      (is (= 4 (count @clearinghouse-calls)))
      (startup-runtime/stage-b-account-bootstrap!
       {:store store
        :address "0xnew"
        :dexs nil
        :per-dex-stagger-ms 25
        :fetch-frontend-open-orders! (fn [& _] (throw (js/Error. "should not run")))
        :fetch-clearinghouse-state! (fn [& _] (throw (js/Error. "should not run")))})
      (is (= [25 50 25 50 25] @timeouts)))))

(deftest bootstrap-account-data-covers-nil-repeat-success-and-error-branches-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                      :orders {:open-orders-snapshot-by-dex {"dex-old" [1]}
                               :fundings-raw [1]
                               :fundings [1]
                               :order-history [1]}
                      :perp-dex-clearinghouse {"dex-old" {:positions [1]}}})
          startup-runtime-atom (atom {:bootstrapped-address nil})
          stage-a-calls (atom [])
          stage-b-calls (atom [])
          log-calls (atom [])
          ensure-mode (atom :resolve)
          deps {:startup-runtime startup-runtime-atom
                :store store
                :fetch-frontend-open-orders! (fn [_store address opts]
                                               (swap! stage-a-calls conj [:open-orders address opts]))
                :fetch-user-fills! (fn [_store address opts]
                                     (swap! stage-a-calls conj [:fills address opts]))
                :fetch-spot-clearinghouse-state! (fn [_store address opts]
                                                   (swap! stage-a-calls conj [:spot address opts]))
                :fetch-user-abstraction! (fn [_store address opts]
                                           (swap! stage-a-calls conj [:abstraction address opts]))
                :fetch-portfolio! (fn [_store address opts]
                                    (swap! stage-a-calls conj [:portfolio address opts]))
                :fetch-user-fees! (fn [_store address opts]
                                    (swap! stage-a-calls conj [:user-fees address opts]))
                :fetch-and-merge-funding-history! (fn [_store address opts]
                                                    (swap! stage-a-calls conj [:fundings address opts]))
                :ensure-perp-dexs! (fn [_store _opts]
                                     (if (= :resolve @ensure-mode)
                                       (js/Promise.resolve {:dex-names ["dex-a" "dex-b"]
                                                            :fee-config-by-name {"dex-a" {:deployer-fee-scale 0.1}
                                                                                 "dex-b" {:deployer-fee-scale 0.2}}})
                                       (js/Promise.reject (js/Error. "ensure-failed"))))
                :stage-b-account-bootstrap! (fn [address dexs]
                                             (swap! stage-b-calls conj [address dexs]))
                :log-fn (fn [& args]
                          (swap! log-calls conj args))}]
      (startup-runtime/bootstrap-account-data! (assoc deps :address nil))
      (is (empty? @stage-a-calls))
      (startup-runtime/bootstrap-account-data! (assoc deps :address "0xabc"))
      (js/setTimeout
       (fn []
         (is (= 7 (count @stage-a-calls)))
         (is (= [["0xabc" ["dex-a" "dex-b"]]] @stage-b-calls))
         (is (= "0xabc" (:bootstrapped-address @startup-runtime-atom)))
         (is (= {} (get-in @store [:orders :open-orders-snapshot-by-dex])))
         (is (= [] (get-in @store [:orders :fundings-raw])))
         (is (= [] (get-in @store [:orders :fundings])))
         (is (= [] (get-in @store [:orders :order-history])))
         (startup-runtime/bootstrap-account-data! (assoc deps :address "0xabc"))
         (js/setTimeout
         (fn []
            (is (= 7 (count @stage-a-calls)))
            (is (= 1 (count @stage-b-calls)))
            (reset! ensure-mode :reject)
            (startup-runtime/bootstrap-account-data! (assoc deps :address "0xdef"))
            (js/setTimeout
             (fn []
               (is (= "0xdef" (:bootstrapped-address @startup-runtime-atom)))
               (is (= 14 (count @stage-a-calls)))
               (is (= 1 (count @stage-b-calls)))
               (is (= "Error bootstrapping per-dex account data:"
                      (first (last @log-calls))))
               (done))
             0))
          0))
       0))))

(deftest install-address-handlers-covers-bootstrap-and-clear-branches-test
  (let [store (atom {:orders {:open-orders-snapshot-by-dex {"dex-a" [{}]}
                              :fundings-raw [1]
                              :fundings [2]
                              :order-history [3]}
                     :perp-dex-clearinghouse {"dex-a" {:assetPositions []}}
                     :portfolio {:summary-by-key {:day {:vlm 10}}
                                 :user-fees {:dailyUserVlm [[0 1]]}
                                 :loading? true
                                 :user-fees-loading? true
                                 :error "portfolio-error"
                                 :user-fees-error "user-fees-error"
                                 :loaded-at-ms 1
                                 :user-fees-loaded-at-ms 2}
                     :spot {:clearinghouse-state {:time 1}}
                     :account {:mode :unified
                               :abstraction-raw "raw"}})
        startup-runtime-atom (atom {:bootstrapped-address "0xold"})
        init-calls (atom [])
        handler-calls (atom [])
        sync-calls (atom 0)
        bootstrap-calls (atom [])]
    (startup-runtime/install-address-handlers!
     {:store store
      :startup-runtime startup-runtime-atom
      :bootstrap-account-data! (fn [new-address]
                                 (swap! bootstrap-calls conj new-address))
      :init-with-webdata2! (fn [store-arg subscribe-fn unsubscribe-fn]
                             (swap! init-calls conj [store-arg subscribe-fn unsubscribe-fn]))
      :add-handler! (fn [handler]
                      (swap! handler-calls conj handler))
      :sync-current-address! (fn [store-arg]
                               (swap! sync-calls inc)
                               store-arg)
      :create-user-handler (fn [subscribe-fn unsubscribe-fn]
                             {:kind :user-handler
                              :subscribe subscribe-fn
                              :unsubscribe unsubscribe-fn})
      :subscribe-user! (fn [& _] nil)
      :unsubscribe-user! (fn [& _] nil)
      :subscribe-webdata2! (fn [& _] nil)
      :unsubscribe-webdata2! (fn [& _] nil)
      :address-handler-reify (fn [on-change handler-name]
                               {:kind :address-handler
                                :name handler-name
                                :on-change on-change})
      :address-handler-name "startup-account-bootstrap-handler"})
    (is (= 1 (count @init-calls)))
    (is (= 2 (count @handler-calls)))
    (is (= 1 @sync-calls))
    (let [address-handler (last @handler-calls)]
      ((:on-change address-handler) "0xabc")
      (is (= ["0xabc"] @bootstrap-calls))
      ((:on-change address-handler) nil)
      (is (nil? (:bootstrapped-address @startup-runtime-atom)))
      (is (= {} (get-in @store [:orders :open-orders-snapshot-by-dex])))
      (is (= [] (get-in @store [:orders :fundings-raw])))
      (is (= [] (get-in @store [:orders :fundings])))
      (is (= [] (get-in @store [:orders :order-history])))
      (is (= {} (get-in @store [:perp-dex-clearinghouse])))
      (is (nil? (get-in @store [:spot :clearinghouse-state])))
      (is (= {} (get-in @store [:portfolio :summary-by-key])))
      (is (nil? (get-in @store [:portfolio :user-fees])))
      (is (false? (get-in @store [:portfolio :loading?])))
      (is (false? (get-in @store [:portfolio :user-fees-loading?])))
      (is (nil? (get-in @store [:portfolio :error])))
      (is (nil? (get-in @store [:portfolio :user-fees-error])))
      (is (nil? (get-in @store [:portfolio :loaded-at-ms])))
      (is (nil? (get-in @store [:portfolio :user-fees-loaded-at-ms])))
      (is (= {:mode :classic
              :abstraction-raw nil}
             (:account @store))))))

(deftest critical-deferred-and-stream-initialization-cover-remaining-runtime-branches-test
  (async done
    (let [mark-calls (atom [])
          deferred-callbacks (atom [])
          deps {:store (atom {:active-asset "BTC"})
                :ws-url "wss://example.test/ws"
                :log-fn (fn [& _] nil)
                :init-connection! (fn [url]
                                    (swap! mark-calls conj [:init-connection url]))
                :init-active-ctx! (fn [_store]
                                    (swap! mark-calls conj :init-active-ctx))
                :init-orderbook! (fn [_store]
                                   (swap! mark-calls conj :init-orderbook))
                :init-trades! (fn [_store]
                                (swap! mark-calls conj :init-trades))
                :init-user-ws! (fn [_store]
                                 (swap! mark-calls conj :init-user-ws))
                :init-webdata2! (fn [_store]
                                  (swap! mark-calls conj :init-webdata2))
                :dispatch! (fn [_store _runtime effects]
                             (swap! mark-calls conj [:dispatch effects]))
                :install-address-handlers! (fn []
                                             (swap! mark-calls conj :install-address-handlers))
                :start-critical-bootstrap! (fn []
                                             (swap! mark-calls conj :start-critical))
                :schedule-deferred-bootstrap! (fn []
                                                (swap! mark-calls conj :schedule-deferred))}
          startup-runtime-atom (atom {:deferred-scheduled? false})]
      (startup-runtime/initialize-remote-data-streams! deps)
      (is (some #(= [:init-connection "wss://example.test/ws"] %) @mark-calls))
      (is (some #(= [:dispatch [[:actions/subscribe-to-asset "BTC"]]] %) @mark-calls))
      (swap! (:store deps) assoc :active-asset nil)
      (startup-runtime/initialize-remote-data-streams! deps)
      (is (= 1 (count (filter #(= [:dispatch [[:actions/subscribe-to-asset "BTC"]]] %) @mark-calls))))
      (with-redefs [platform/set-timeout! (fn [_f _delay-ms]
                                            :timer-id)]
        (startup-runtime/start-critical-bootstrap!
         {:store (:store deps)
          :fetch-asset-contexts! (fn [_store _opts] (js/Promise.resolve :ctx))
          :fetch-asset-selector-markets! (fn [_store _opts] (js/Promise.resolve :markets))
          :mark-performance! (fn [mark]
                               (swap! mark-calls conj [:mark mark]))})
        (startup-runtime/run-deferred-bootstrap!
         {:store (:store deps)
          :fetch-asset-selector-markets! (fn [_store _opts] (js/Promise.resolve :full))
          :mark-performance! (fn [mark]
                               (swap! mark-calls conj [:mark mark]))})
        (js/setTimeout
         (fn []
           (is (some #(= [:mark "app:critical-data:ready"] %) @mark-calls))
           (is (some #(= [:mark "app:full-bootstrap:ready"] %) @mark-calls))
           (startup-runtime/schedule-deferred-bootstrap!
            {:startup-runtime startup-runtime-atom
             :schedule-idle-or-timeout! (fn [callback]
                                          (swap! deferred-callbacks conj callback)
                                          :scheduled)
             :run-deferred-bootstrap! (fn []
                                        (swap! mark-calls conj :run-deferred))})
           (startup-runtime/schedule-deferred-bootstrap!
            {:startup-runtime startup-runtime-atom
             :schedule-idle-or-timeout! (fn [callback]
                                          (swap! deferred-callbacks conj callback)
                                          :scheduled)
             :run-deferred-bootstrap! (fn []
                                        (swap! mark-calls conj :run-deferred))})
           (is (= 1 (count @deferred-callbacks)))
           ((first @deferred-callbacks))
           (is (some #{:run-deferred} @mark-calls))
           (let [runtime-atom (atom {:startup {:deferred-scheduled? false}})
                 fallback-calls (atom 0)]
             (startup-runtime/schedule-deferred-bootstrap!
              {:runtime runtime-atom
               :schedule-idle-or-timeout! (fn [_]
                                            (swap! fallback-calls inc)
                                            :scheduled)
               :run-deferred-bootstrap! (fn [] nil)})
             (is (= true (get-in @runtime-atom [:startup :deferred-scheduled?])))
             (is (= 1 @fallback-calls)))
           (done))
         0)))))
