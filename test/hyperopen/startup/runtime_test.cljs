(ns hyperopen.startup.runtime-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.platform :as platform]
            [hyperopen.startup.runtime :as startup-runtime]
            [hyperopen.wallet.address-watcher :as address-watcher]))

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

(deftest reify-address-handler-implements-address-change-handler-contract-test
  (let [calls (atom [])
        handler (startup-runtime/reify-address-handler
                 (fn [new-address]
                   (swap! calls conj new-address))
                 "startup-handler")]
    (is (satisfies? address-watcher/IAddressChangeHandler handler))
    (is (= "startup-handler"
           (address-watcher/get-handler-name handler)))
    (address-watcher/on-address-changed handler nil "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    (is (= ["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"] @calls))))

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

(deftest yield-to-main-prefers-scheduler-yield-and-falls-back-to-timeout-test
  (async done
    (let [yield-calls (atom 0)
          fallback-delays (atom [])
          fail! (fn [label err]
                  (is false (str label err))
                  (done))]
      (with-global-property
        "scheduler"
        #js {:yield (fn []
                      (swap! yield-calls inc)
                      (js/Promise.resolve :yielded))}
        (fn []
          (-> (startup-runtime/yield-to-main!)
              (.then
               (fn [result]
                 (is (= :yielded result))
                 (is (= 1 @yield-calls))
                 (with-global-property
                   "scheduler"
                   #js {}
                   (fn []
                     (with-redefs [platform/set-timeout! (fn [f delay-ms]
                                                           (swap! fallback-delays conj delay-ms)
                                                           (f)
                                                           :timeout-id)]
                       (-> (startup-runtime/yield-to-main!)
                           (.then (fn [_]
                                    (is (= [0] @fallback-delays))
                                    (done)))
                           (.catch (fn [err]
                                     (fail! "Unexpected fallback yield error: " err)))))))))
              (.catch (fn [err]
                        (fail! "Unexpected scheduler yield error: " err)))))))))

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
        :get-request-stats (fn []
                             {:pending 1
                              :started-by-type-source {"frontendOpenOrders" {"startup/stage-b" 2}}
                              :completed-by-type-source {"frontendOpenOrders" {"startup/stage-b" 2}}
                              :rate-limited-by-type-source {"frontendOpenOrders" {"startup/stage-b" 0}}
                              :latency-ms-by-type-source {"frontendOpenOrders" {"startup/stage-b" {:count 2
                                                                                                    :total-ms 20
                                                                                                    :max-ms 15}}}})
        :delay-ms 5
        :log-fn (fn [& args]
                  (swap! logs conj args))})
      (is (true? (:summary-logged? @startup-runtime-atom)))
      (is (= 1 @timer-calls))
      (@timer-callback)
      (is (= 1 (count @logs)))
      (let [[message payload] (first @logs)
            payload* (js->clj payload :keywordize-keys true)]
        (is (= "Startup summary (+5s):" message))
        (is (= 1
               (get-in payload* [:request-stats :pending])))
        (is (= [{:request-type "frontendOpenOrders"
                 :request-source "startup/stage-b"
                 :started 2
                 :completed 2
                 :rate-limited 0
                 :latency-ms {:count 2 :total-ms 20 :max-ms 15}
                 :avg-latency-ms 10}]
               (:request-hotspots payload*))))
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
        store (atom {:asset-selector {:visible-dropdown nil}
                     :account-context {:spectate-mode {:active? false
                                                    :address nil}}})
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
          (swap! store assoc-in [:account-context :spectate-mode]
                 {:active? true
                  :address "0x1234567890abcdef1234567890abcdef12345678"})
          (keydown-handler #js {:key "x"
                                :metaKey true
                                :ctrlKey false
                                :shiftKey false
                                :target #js {:tagName "DIV"}
                                :preventDefault (fn []
                                                  (swap! prevent-default-calls inc))})
          (is (= 1 @prevent-default-calls))
          (is (= 1 (count @dispatch-calls)))
          (keydown-handler #js {:key "x"
                                :metaKey true
                                :ctrlKey false
                                :shiftKey true
                                :target #js {:tagName "DIV"}
                                :preventDefault (fn []
                                                  (swap! prevent-default-calls inc))})
          (is (= 2 @prevent-default-calls))
          (is (= [[:actions/stop-spectate-mode]]
                 (-> @dispatch-calls second :effects)))
          (keydown-handler #js {:key "x"
                                :metaKey true
                                :ctrlKey false
                                :shiftKey true
                                :target #js {:tagName "INPUT"}
                                :preventDefault (fn []
                                                  (swap! prevent-default-calls inc))})
          (is (= 2 @prevent-default-calls))
          (is (= 2 (count @dispatch-calls)))
          (keydown-handler #js {:key ","
                                :metaKey false
                                :ctrlKey false
                                :shiftKey false
                                :target #js {:tagName "DIV"}
                                :preventDefault (fn []
                                                  (swap! prevent-default-calls inc))})
          (is (= 2 @prevent-default-calls))
          (is (= 2 (count @dispatch-calls)))
          (keydown-handler #js {:key ","
                                :metaKey false
                                :ctrlKey false
                                :shiftKey false
                                :target #js {:tagName "TEXTAREA"}
                                :preventDefault (fn []
                                                  (swap! prevent-default-calls inc))})
          (is (= 2 @prevent-default-calls))
          (is (= 2 (count @dispatch-calls)))
          (swap! store assoc-in [:asset-selector :visible-dropdown] :asset-selector)
          (keydown-handler #js {:key "Escape"
                                :metaKey false
                                :ctrlKey false
                                :preventDefault (fn [] nil)})
          (is (= [[:actions/handle-asset-selector-shortcut "Escape" false false nil]]
                 (-> @dispatch-calls (nth 2) :effects)))
          (startup-runtime/install-asset-selector-shortcuts!
           {:store store
            :dispatch! dispatch!})
          (is (= 1 (count @removed-handlers))))))))

(deftest install-position-tpsl-clickaway-registers-mousedown-and-closes-on-outside-click-test
  (let [registered-handlers (atom {})
        removed-handlers (atom [])
        dispatch-calls (atom [])
        store (atom {:positions-ui {:tpsl-modal {:open? true}
                                    :reduce-popover {:open? true}
                                    :margin-modal {:open? true}}
                     :account-context {:spectate-ui {:modal-open? true}}})
        dispatch! (fn [store-arg _ctx effects]
                    (swap! dispatch-calls conj {:store store-arg
                                                :effects effects}))
        inside-panel-target #js {:closest (fn [selector]
                                            (when (= selector "[data-position-tpsl-surface='true']")
                                              #js {}))}
        inside-trigger-target #js {:closest (fn [selector]
                                              (when (= selector "[data-position-tpsl-trigger='true']")
                                                #js {}))}
        inside-reduce-panel-target #js {:closest (fn [selector]
                                                   (when (= selector "[data-position-reduce-surface='true']")
                                                     #js {}))}
        inside-reduce-trigger-target #js {:closest (fn [selector]
                                                     (when (= selector "[data-position-reduce-trigger='true']")
                                                       #js {}))}
        inside-margin-panel-target #js {:closest (fn [selector]
                                                   (when (= selector "[data-position-margin-surface='true']")
                                                     #js {}))}
        inside-margin-trigger-target #js {:closest (fn [selector]
                                                     (when (= selector "[data-position-margin-trigger='true']")
                                                       #js {}))}
        inside-spectate-panel-target #js {:closest (fn [selector]
                                                  (when (= selector "[data-spectate-mode-surface='true']")
                                                    #js {}))}
        inside-spectate-trigger-target #js {:closest (fn [selector]
                                                    (when (= selector "[data-spectate-mode-trigger='true']")
                                                      #js {}))}
        outside-target #js {:closest (fn [_selector] nil)}]
    (with-global-property
      "window"
      #js {:addEventListener (fn [event-name handler]
                               (swap! registered-handlers assoc event-name handler))
           :removeEventListener (fn [event-name handler]
                                  (swap! removed-handlers conj [event-name handler]))}
      (fn []
        (startup-runtime/install-position-tpsl-clickaway!
         {:store store
          :dispatch! dispatch!})
        (let [mousedown-handler (get @registered-handlers "mousedown")]
          (is (fn? mousedown-handler))
          (mousedown-handler #js {:target inside-panel-target})
          (mousedown-handler #js {:target inside-trigger-target})
          (mousedown-handler #js {:target inside-reduce-panel-target})
          (mousedown-handler #js {:target inside-reduce-trigger-target})
          (mousedown-handler #js {:target inside-margin-panel-target})
          (mousedown-handler #js {:target inside-margin-trigger-target})
          (mousedown-handler #js {:target inside-spectate-panel-target})
          (mousedown-handler #js {:target inside-spectate-trigger-target})
          (is (empty? @dispatch-calls))
          (mousedown-handler #js {:target outside-target})
          (is (= [[:actions/close-position-tpsl-modal]
                  [:actions/close-position-reduce-popover]
                  [:actions/close-position-margin-modal]
                  [:actions/close-spectate-mode-modal]]
                 (-> @dispatch-calls first :effects)))
          (reset! dispatch-calls [])
          (swap! store assoc-in [:positions-ui :tpsl-modal :open?] false)
          (swap! store assoc-in [:positions-ui :reduce-popover :open?] false)
          (swap! store assoc-in [:positions-ui :margin-modal :open?] false)
          (swap! store assoc-in [:account-context :spectate-ui :modal-open?] false)
          (mousedown-handler #js {:target outside-target})
          (is (empty? @dispatch-calls))
          (startup-runtime/install-position-tpsl-clickaway!
           {:store store
            :dispatch! dispatch!})
          (is (= 1 (count @removed-handlers))))))))

(deftest install-position-tpsl-clickaway-dismisses-expanded-trade-blotter-on-outside-click-test
  (let [registered-handlers (atom {})
        dispatch-calls (atom [])
        store (atom {:ui {:toasts [{:id "trade-blotter"
                                    :toast-surface :trade-confirmation
                                    :expanded? true}
                                   {:id "regular-toast" :message "Order placed"}]}})
        dispatch! (fn [store-arg _ctx effects]
                    (swap! dispatch-calls conj {:store store-arg :effects effects}))
        inside-blotter-target #js {:closest (fn [selector]
                                              (when (= selector "[data-trade-blotter-surface='true']") #js {}))}
        outside-target #js {:closest (fn [_selector] nil)}]
    (with-global-property
      "window"
      #js {:addEventListener (fn [event-name handler] (swap! registered-handlers assoc event-name handler))
           :removeEventListener (fn [_event-name _handler] nil)}
      (fn []
        (startup-runtime/install-position-tpsl-clickaway!
         {:store store
          :dispatch! dispatch!})
        (let [mousedown-handler (get @registered-handlers "mousedown")]
          (is (fn? mousedown-handler))
          (mousedown-handler #js {:target inside-blotter-target})
          (is (empty? @dispatch-calls))
          (mousedown-handler #js {:target outside-target})
          (is (= [[:actions/dismiss-order-feedback-toast "trade-blotter"]]
                 (-> @dispatch-calls first :effects))))))))

(deftest stage-b-account-bootstrap-covers-shape-guard-and-empty-dex-branches-test
  (let [timeouts (atom [])
        open-orders-calls (atom [])
        clearinghouse-calls (atom [])
        store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}})]
    (with-redefs [platform/set-timeout! (fn [f delay-ms]
                                          (swap! timeouts conj delay-ms)
                                          (f)
                                          :timeout-id)]
      (startup-runtime/stage-b-account-bootstrap!
       {:store store
        :address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        :dexs ["dex-a" "dex-b"]
        :per-dex-stagger-ms 25
        :fetch-frontend-open-orders! (fn [_store address opts]
                                       (swap! open-orders-calls conj [address opts]))
        :fetch-clearinghouse-state! (fn [_store address dex opts]
                                      (swap! clearinghouse-calls conj [address dex opts]))})
      (is (= [25 50] @timeouts))
      (is (= [["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {:dex "dex-a" :priority :low}]
              ["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {:dex "dex-b" :priority :low}]]
             @open-orders-calls))
      (is (= [["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" "dex-a" {:priority :low}]
              ["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" "dex-b" {:priority :low}]]
             @clearinghouse-calls))
      (startup-runtime/stage-b-account-bootstrap!
       {:store store
        :address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        :dexs {:dex-names ["dex-c" " " nil "dex-d"]
               :fee-config-by-name {"dex-c" {:deployer-fee-scale 0.1}
                                    "dex-d" {:deployer-fee-scale 0.2}}}
        :per-dex-stagger-ms 25
        :fetch-frontend-open-orders! (fn [_store address opts]
                                       (swap! open-orders-calls conj [address opts]))
        :fetch-clearinghouse-state! (fn [_store address dex opts]
                                      (swap! clearinghouse-calls conj [address dex opts]))})
      (is (= [25 50 25 50] @timeouts))
      (is (= [["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {:dex "dex-a" :priority :low}]
              ["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {:dex "dex-b" :priority :low}]
              ["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {:dex "dex-c" :priority :low}]
              ["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {:dex "dex-d" :priority :low}]]
             @open-orders-calls))
      (is (= [["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" "dex-a" {:priority :low}]
              ["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" "dex-b" {:priority :low}]
              ["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" "dex-c" {:priority :low}]
              ["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" "dex-d" {:priority :low}]]
             @clearinghouse-calls))
      (swap! store assoc-in [:wallet :address] "0xcccccccccccccccccccccccccccccccccccccccc")
      (startup-runtime/stage-b-account-bootstrap!
       {:store store
        :address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
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
        :address "0xcccccccccccccccccccccccccccccccccccccccc"
        :dexs nil
        :per-dex-stagger-ms 25
        :fetch-frontend-open-orders! (fn [& _] (throw (js/Error. "should not run")))
        :fetch-clearinghouse-state! (fn [& _] (throw (js/Error. "should not run")))})
      (is (= [25 50 25 50 25] @timeouts)))))

(deftest stage-b-account-bootstrap-uses-effective-address-for-stale-guard-test
  (let [calls (atom [])
        store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                     :account-context {:spectate-mode {:active? true
                                                    :address "0xcccccccccccccccccccccccccccccccccccccccc"}}})]
    (with-redefs [platform/set-timeout! (fn [f _delay-ms]
                                          (f)
                                          :timeout-id)]
      (startup-runtime/stage-b-account-bootstrap!
       {:store store
        :address "0xcccccccccccccccccccccccccccccccccccccccc"
        :dexs ["dex-a"]
        :per-dex-stagger-ms 25
        :fetch-frontend-open-orders! (fn [_store address opts]
                                       (swap! calls conj [:open-orders address opts]))
        :fetch-clearinghouse-state! (fn [_store address dex opts]
                                      (swap! calls conj [:clearinghouse address dex opts]))}))
    (is (= [[:open-orders "0xcccccccccccccccccccccccccccccccccccccccc"
             {:dex "dex-a" :priority :low}]
            [:clearinghouse "0xcccccccccccccccccccccccccccccccccccccccc"
             "dex-a"
             {:priority :low}]]
           @calls))))

(deftest stage-b-account-bootstrap-skips-open-orders-when-open-orders-stream-live-test
  (let [open-orders-calls (atom [])
        clearinghouse-calls (atom [])
        address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        store (atom {:wallet {:address address}
                     :websocket {:health {:transport {:state :connected
                                                      :freshness :live}
                                          :streams {"stream-1" {:subscribed? true
                                                                :status :live
                                                                :topic "openOrders"
                                                                :descriptor {:type "openOrders"
                                                                             :user "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}}}}})]
    (with-redefs [platform/set-timeout! (fn [f _delay-ms]
                                          (f)
                                          :timeout-id)]
      (startup-runtime/stage-b-account-bootstrap!
       {:store store
        :address address
        :dexs ["dex-a" "dex-b"]
        :per-dex-stagger-ms 25
        :fetch-frontend-open-orders! (fn [_store fetch-address opts]
                                       (swap! open-orders-calls conj [fetch-address opts]))
        :fetch-clearinghouse-state! (fn [_store fetch-address dex opts]
                                      (swap! clearinghouse-calls conj [fetch-address dex opts]))}))
    (is (empty? @open-orders-calls))
    (is (= [[address "dex-a" {:priority :low}]
            [address "dex-b" {:priority :low}]]
           @clearinghouse-calls))))

(deftest stage-b-account-bootstrap-skips-open-orders-when-open-orders-stream-event-driven-test
  (let [open-orders-calls (atom [])
        clearinghouse-calls (atom [])
        address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        store (atom {:wallet {:address address}
                     :websocket {:health {:transport {:state :connected
                                                      :freshness :live}
                                          :streams {"stream-1" {:subscribed? true
                                                                :status :n-a
                                                                :topic "openOrders"
                                                                :descriptor {:type "openOrders"
                                                                             :user "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}}}}})]
    (with-redefs [platform/set-timeout! (fn [f _delay-ms]
                                          (f)
                                          :timeout-id)]
      (startup-runtime/stage-b-account-bootstrap!
       {:store store
        :address address
        :dexs ["dex-a" "dex-b"]
        :per-dex-stagger-ms 25
        :fetch-frontend-open-orders! (fn [_store fetch-address opts]
                                       (swap! open-orders-calls conj [fetch-address opts]))
        :fetch-clearinghouse-state! (fn [_store fetch-address dex opts]
                                      (swap! clearinghouse-calls conj [fetch-address dex opts]))}))
    (is (empty? @open-orders-calls))
    (is (= [[address "dex-a" {:priority :low}]
            [address "dex-b" {:priority :low}]]
           @clearinghouse-calls))))

(deftest stage-b-account-bootstrap-skips-per-dex-clearinghouse-when-stream-usable-test
  (let [open-orders-calls (atom [])
        clearinghouse-calls (atom [])
        sync-calls (atom [])
        address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        store (atom {:wallet {:address address}
                     :websocket {:health {:transport {:state :connected
                                                      :freshness :live}
                                          :streams {"open-orders-stream" {:subscribed? true
                                                                          :status :live
                                                                          :topic "openOrders"
                                                                          :descriptor {:type "openOrders"
                                                                                       :user address}}
                                                    "dex-a-clearinghouse-stream" {:subscribed? true
                                                                                  :status :live
                                                                                  :topic "clearinghouseState"
                                                                                  :descriptor {:type "clearinghouseState"
                                                                                               :user address
                                                                                               :dex "dex-a"}}
                                                    "dex-b-clearinghouse-stream" {:subscribed? true
                                                                                  :status :n-a
                                                                                  :topic "clearinghouseState"
                                                                                  :descriptor {:type "clearinghouseState"
                                                                                               :user address
                                                                                               :dex "dex-b"}}}}}})]
    (with-redefs [platform/set-timeout! (fn [f _delay-ms]
                                          (f)
                                          :timeout-id)]
      (startup-runtime/stage-b-account-bootstrap!
       {:store store
        :address address
        :dexs ["dex-a" "dex-b"]
        :per-dex-stagger-ms 25
        :sync-perp-dex-clearinghouse-subscriptions! (fn [sync-address dex-names]
                                                      (swap! sync-calls conj [sync-address dex-names]))
        :fetch-frontend-open-orders! (fn [_store fetch-address opts]
                                       (swap! open-orders-calls conj [fetch-address opts]))
        :fetch-clearinghouse-state! (fn [_store fetch-address dex opts]
                                      (swap! clearinghouse-calls conj [fetch-address dex opts]))}))
    (is (empty? @open-orders-calls))
    (is (empty? @clearinghouse-calls))
    (is (= [[address ["dex-a" "dex-b"]]]
           @sync-calls))))

(deftest bootstrap-account-data-covers-nil-repeat-success-and-error-branches-test
  (async done
    (let [store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                      :webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "BTC"}}]}
                                 :open-orders [{:coin "BTC" :oid 91}]
                                 :fills [{:tid 77}]
                                 :fundings [{:id "fund-1"}]
                                 :fundings-raw [{:id "fund-1"}]}
                      :account-info {:selected-tab :balances
                                     :funding-history {:loading? true
                                                       :error "stale"}
                                     :order-history {:request-id 0}}
                      :orders {:open-orders [{:coin "BTC" :oid 92}]
                               :open-orders-snapshot [{:coin "BTC" :oid 93}]
                               :open-orders-snapshot-by-dex {"dex-old" [1]}
                               :fills [{:tid 78}]
                               :fundings-raw [1]
                               :fundings [1]
                               :order-history [1]
                               :twap-states [{:coin "BTC"}]
                               :twap-history [{:time 123}]
                               :twap-slice-fills [{:tid 79}]
                               :pending-cancel-oids #{11}}
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
                :fetch-historical-orders! (fn [_store request-id opts]
                                            (swap! stage-a-calls conj [:order-history request-id opts]))
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
                :startup-stream-backfill-delay-ms 0
                :log-fn (fn [& args]
                          (swap! log-calls conj args))}]
      (startup-runtime/bootstrap-account-data! (assoc deps :address nil))
      (is (empty? @stage-a-calls))
      (startup-runtime/bootstrap-account-data! (assoc deps :address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
      (js/setTimeout
       (fn []
         (is (= 8 (count @stage-a-calls)))
         (is (= [:order-history 1 {:priority :low}]
                (first @stage-a-calls)))
         (let [fundings-call (first (filter #(= :fundings (first %)) @stage-a-calls))
               fundings-opts (nth fundings-call 2)]
           (is (map? fundings-opts))
           (is (number? (:start-time-ms fundings-opts)))
           (is (number? (:end-time-ms fundings-opts)))
           (is (= :high (:priority fundings-opts)))
           (is (<= (:start-time-ms fundings-opts)
                   (:end-time-ms fundings-opts))))
         (is (= [["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" ["dex-a" "dex-b"]]] @stage-b-calls))
         (is (= "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" (:bootstrapped-address @startup-runtime-atom)))
         (is (nil? (:webdata2 @store)))
         (is (= [] (get-in @store [:orders :open-orders])))
         (is (= false (get-in @store [:orders :open-orders-hydrated?])))
         (is (= [] (get-in @store [:orders :open-orders-snapshot])))
         (is (= 1 (get-in @store [:account-info :order-history :request-id])))
         (is (= {} (get-in @store [:orders :open-orders-snapshot-by-dex])))
         (is (= [] (get-in @store [:orders :fills])))
         (is (= [] (get-in @store [:orders :fundings-raw])))
         (is (= [] (get-in @store [:orders :fundings])))
         (is (= [] (get-in @store [:orders :order-history])))
         (is (= [] (get-in @store [:orders :twap-states])))
         (is (= [] (get-in @store [:orders :twap-history])))
         (is (= [] (get-in @store [:orders :twap-slice-fills])))
         (is (nil? (get-in @store [:orders :pending-cancel-oids])))
         (is (nil? (get-in @store [:spot :clearinghouse-state])))
         (is (= false (get-in @store [:account-info :funding-history :loading?])))
         (is (nil? (get-in @store [:account-info :funding-history :error])))
         (startup-runtime/bootstrap-account-data! (assoc deps :address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
         (js/setTimeout
         (fn []
            (is (= 8 (count @stage-a-calls)))
            (is (= 1 (count @stage-b-calls)))
            (reset! ensure-mode :reject)
            (swap! store assoc-in [:wallet :address] "0xffffffffffffffffffffffffffffffffffffffff")
            (startup-runtime/bootstrap-account-data! (assoc deps :address "0xffffffffffffffffffffffffffffffffffffffff"))
            (js/setTimeout
             (fn []
               (js/setTimeout
                (fn []
                  (is (= "0xffffffffffffffffffffffffffffffffffffffff" (:bootstrapped-address @startup-runtime-atom)))
                  (is (= 16 (count @stage-a-calls)))
                  (is (= 1 (count @stage-b-calls)))
                  (is (= "Error bootstrapping per-dex account data:"
                         (first (last @log-calls))))
                  (done))
                0))
             0))
          0))
       0))))

(deftest bootstrap-account-data-ws-first-skips-stream-covered-fetches-when-streams-live-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          stage-a-calls (atom [])
          store (atom {:wallet {:address address}
                       :account-info {:order-history {:request-id 0}}
                       :orders {:open-orders-hydrated? true}
                       :websocket {:health {:transport {:state :connected
                                                        :freshness :live}
                                            :streams {"open-orders-stream" {:subscribed? true
                                                                            :status :live
                                                                            :topic "openOrders"
                                                                            :descriptor {:type "openOrders"
                                                                                         :user address}}
                                                      "fills-stream" {:subscribed? true
                                                                      :status :live
                                                                      :topic "userFills"
                                                                      :descriptor {:type "userFills"
                                                                                   :user address}}
                                                      "fundings-stream" {:subscribed? true
                                                                         :status :live
                                                                         :topic "userFundings"
                                                                         :descriptor {:type "userFundings"
                                                                                      :user address}}}}}})
          startup-runtime-atom (atom {:bootstrapped-address nil})]
      (startup-runtime/bootstrap-account-data!
       {:startup-runtime startup-runtime-atom
        :store store
        :address address
        :startup-stream-backfill-delay-ms 0
        :fetch-frontend-open-orders! (fn [_store fetch-address opts]
                                       (swap! stage-a-calls conj [:open-orders fetch-address opts]))
        :fetch-user-fills! (fn [_store fetch-address opts]
                             (swap! stage-a-calls conj [:fills fetch-address opts]))
        :fetch-spot-clearinghouse-state! (fn [_store fetch-address opts]
                                           (swap! stage-a-calls conj [:spot fetch-address opts]))
        :fetch-user-abstraction! (fn [_store fetch-address opts]
                                   (swap! stage-a-calls conj [:abstraction fetch-address opts]))
        :fetch-portfolio! (fn [_store fetch-address opts]
                            (swap! stage-a-calls conj [:portfolio fetch-address opts]))
        :fetch-user-fees! (fn [_store fetch-address opts]
                            (swap! stage-a-calls conj [:user-fees fetch-address opts]))
        :fetch-historical-orders! (fn [_store request-id opts]
                                    (swap! stage-a-calls conj [:order-history request-id opts]))
        :fetch-and-merge-funding-history! (fn [_store fetch-address opts]
                                            (swap! stage-a-calls conj [:fundings fetch-address opts]))
        :ensure-perp-dexs! (fn [_store _opts]
                             (js/Promise.resolve []))
        :stage-b-account-bootstrap! (fn [& _] nil)
        :log-fn (fn [& _] nil)})
      (js/setTimeout
       (fn []
         (is (some #(= :order-history (first %)) @stage-a-calls))
         (is (some #(= :spot (first %)) @stage-a-calls))
         (is (some #(= :abstraction (first %)) @stage-a-calls))
         (is (some #(= :portfolio (first %)) @stage-a-calls))
         (is (some #(= :user-fees (first %)) @stage-a-calls))
         (is (some #(= :open-orders (first %)) @stage-a-calls))
         (is (not (some #(= :fills (first %)) @stage-a-calls)))
         (is (not (some #(= :fundings (first %)) @stage-a-calls)))
         (done))
       0))))

(deftest bootstrap-account-data-ws-first-skips-stream-covered-fetches-when-streams-event-driven-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          stage-a-calls (atom [])
          store (atom {:wallet {:address address}
                       :account-info {:order-history {:request-id 0}}
                       :orders {:open-orders-hydrated? true}
                       :websocket {:health {:transport {:state :connected
                                                        :freshness :live}
                                            :streams {"open-orders-stream" {:subscribed? true
                                                                            :status :n-a
                                                                            :topic "openOrders"
                                                                            :descriptor {:type "openOrders"
                                                                                         :user address}}
                                                      "fills-stream" {:subscribed? true
                                                                      :status :n-a
                                                                      :topic "userFills"
                                                                      :descriptor {:type "userFills"
                                                                                   :user address}}
                                                      "fundings-stream" {:subscribed? true
                                                                         :status :n-a
                                                                         :topic "userFundings"
                                                                         :descriptor {:type "userFundings"
                                                                                      :user address}}}}}})
          startup-runtime-atom (atom {:bootstrapped-address nil})]
      (startup-runtime/bootstrap-account-data!
       {:startup-runtime startup-runtime-atom
        :store store
        :address address
        :startup-stream-backfill-delay-ms 0
        :fetch-frontend-open-orders! (fn [_store fetch-address opts]
                                       (swap! stage-a-calls conj [:open-orders fetch-address opts]))
        :fetch-user-fills! (fn [_store fetch-address opts]
                             (swap! stage-a-calls conj [:fills fetch-address opts]))
        :fetch-spot-clearinghouse-state! (fn [_store fetch-address opts]
                                           (swap! stage-a-calls conj [:spot fetch-address opts]))
        :fetch-user-abstraction! (fn [_store fetch-address opts]
                                   (swap! stage-a-calls conj [:abstraction fetch-address opts]))
        :fetch-portfolio! (fn [_store fetch-address opts]
                            (swap! stage-a-calls conj [:portfolio fetch-address opts]))
        :fetch-user-fees! (fn [_store fetch-address opts]
                            (swap! stage-a-calls conj [:user-fees fetch-address opts]))
        :fetch-historical-orders! (fn [_store request-id opts]
                                    (swap! stage-a-calls conj [:order-history request-id opts]))
        :fetch-and-merge-funding-history! (fn [_store fetch-address opts]
                                            (swap! stage-a-calls conj [:fundings fetch-address opts]))
        :ensure-perp-dexs! (fn [_store _opts]
                             (js/Promise.resolve []))
        :stage-b-account-bootstrap! (fn [& _] nil)
        :log-fn (fn [& _] nil)})
      (js/setTimeout
       (fn []
         (is (some #(= :order-history (first %)) @stage-a-calls))
         (is (some #(= :spot (first %)) @stage-a-calls))
         (is (some #(= :abstraction (first %)) @stage-a-calls))
         (is (some #(= :portfolio (first %)) @stage-a-calls))
         (is (some #(= :user-fees (first %)) @stage-a-calls))
         (is (some #(= :open-orders (first %)) @stage-a-calls))
         (is (not (some #(= :fills (first %)) @stage-a-calls)))
         (is (not (some #(= :fundings (first %)) @stage-a-calls)))
         (done))
       0))))

(deftest bootstrap-account-data-flag-off-fetches-stream-backed-surfaces-immediately-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          stage-a-calls (atom [])
          scheduled-delays (atom [])
          websocket-health {:transport {:state :connected
                                        :freshness :live}
                            :streams {"open-orders-stream" {:subscribed? true
                                                            :status :live
                                                            :topic "openOrders"
                                                            :descriptor {:type "openOrders"
                                                                         :user address}}
                                      "fills-stream" {:subscribed? true
                                                      :status :live
                                                      :topic "userFills"
                                                      :descriptor {:type "userFills"
                                                                   :user address}}
                                      "fundings-stream" {:subscribed? true
                                                         :status :live
                                                         :topic "userFundings"
                                                         :descriptor {:type "userFundings"
                                                                      :user address}}}}
          store (atom {:wallet {:address address}
                       :account-info {:order-history {:request-id 0}}
                       :websocket {:migration-flags {:startup-bootstrap-ws-first? false}
                                   :health websocket-health}})
          startup-runtime-atom (atom {:bootstrapped-address nil})]
      (with-redefs [platform/set-timeout! (fn [_f delay-ms]
                                            (swap! scheduled-delays conj delay-ms)
                                            :timeout-id)
                    platform/now-ms (fn []
                                      100000)]
        (startup-runtime/bootstrap-account-data!
         {:startup-runtime startup-runtime-atom
          :store store
          :address address
          :startup-stream-backfill-delay-ms 999
          :startup-funding-history-lookback-ms 60000
          :fetch-frontend-open-orders! (fn [_store fetch-address opts]
                                         (swap! stage-a-calls conj [:open-orders fetch-address opts]))
          :fetch-user-fills! (fn [_store fetch-address opts]
                               (swap! stage-a-calls conj [:fills fetch-address opts]))
          :fetch-spot-clearinghouse-state! (fn [_store fetch-address opts]
                                             (swap! stage-a-calls conj [:spot fetch-address opts]))
          :fetch-user-abstraction! (fn [_store fetch-address opts]
                                     (swap! stage-a-calls conj [:abstraction fetch-address opts]))
          :fetch-portfolio! (fn [_store fetch-address opts]
                              (swap! stage-a-calls conj [:portfolio fetch-address opts]))
          :fetch-user-fees! (fn [_store fetch-address opts]
                              (swap! stage-a-calls conj [:user-fees fetch-address opts]))
          :fetch-historical-orders! (fn [_store request-id opts]
                                      (swap! stage-a-calls conj [:order-history request-id opts]))
          :fetch-and-merge-funding-history! (fn [_store fetch-address opts]
                                              (swap! stage-a-calls conj [:fundings fetch-address opts]))
          :ensure-perp-dexs! (fn [_store _opts]
                               (js/Promise.resolve []))
          :stage-b-account-bootstrap! (fn [& _] nil)
          :log-fn (fn [& _] nil)}))
      (js/setTimeout
       (fn []
         (is (some #(= :open-orders (first %)) @stage-a-calls))
         (is (some #(= :fills (first %)) @stage-a-calls))
         (is (some #(= :fundings (first %)) @stage-a-calls))
         (is (some #(= [:fundings address {:priority :high
                                           :start-time-ms 40000
                                           :end-time-ms 100000}]
                       %)
                   @stage-a-calls))
         ;; Startup WS-first disabled means no delayed stream-backed fallback scheduling.
         (is (= [] @scheduled-delays))
         (done))
       0))))

(deftest bootstrap-account-data-ws-first-runs-delayed-fallback-when-streams-are-not-live-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          stage-a-calls (atom [])
          scheduled-delays (atom [])
          startup-runtime-atom (atom {:bootstrapped-address nil})
          store (atom {:wallet {:address address}
                       :account-info {:order-history {:request-id 0}}})]
      (with-redefs [platform/set-timeout! (fn [f delay-ms]
                                            (swap! scheduled-delays conj delay-ms)
                                            (f)
                                            :timeout-id)
                    platform/now-ms (fn []
                                      100000)]
        (startup-runtime/bootstrap-account-data!
         {:startup-runtime startup-runtime-atom
          :store store
          :address address
          :startup-stream-backfill-delay-ms 123
          :startup-funding-history-lookback-ms 60000
          :fetch-frontend-open-orders! (fn [_store fetch-address opts]
                                         (swap! stage-a-calls conj [:open-orders fetch-address opts]))
          :fetch-user-fills! (fn [_store fetch-address opts]
                               (swap! stage-a-calls conj [:fills fetch-address opts]))
          :fetch-spot-clearinghouse-state! (fn [_store fetch-address opts]
                                             (swap! stage-a-calls conj [:spot fetch-address opts]))
          :fetch-user-abstraction! (fn [_store fetch-address opts]
                                     (swap! stage-a-calls conj [:abstraction fetch-address opts]))
          :fetch-portfolio! (fn [_store fetch-address opts]
                              (swap! stage-a-calls conj [:portfolio fetch-address opts]))
          :fetch-user-fees! (fn [_store fetch-address opts]
                              (swap! stage-a-calls conj [:user-fees fetch-address opts]))
          :fetch-historical-orders! (fn [_store request-id opts]
                                      (swap! stage-a-calls conj [:order-history request-id opts]))
          :fetch-and-merge-funding-history! (fn [_store fetch-address opts]
                                              (swap! stage-a-calls conj [:fundings fetch-address opts]))
          :ensure-perp-dexs! (fn [_store _opts]
                               (js/Promise.resolve []))
          :stage-b-account-bootstrap! (fn [& _] nil)
          :log-fn (fn [& _] nil)}))
      (js/setTimeout
       (fn []
         (is (= [123 123 123] @scheduled-delays))
         (is (some #(= :open-orders (first %)) @stage-a-calls))
         (is (some #(= :fills (first %)) @stage-a-calls))
         (is (some #(= :fundings (first %)) @stage-a-calls))
         (is (some #(= [:fundings address {:priority :high
                                           :start-time-ms 40000
                                           :end-time-ms 100000}]
                       %)
                   @stage-a-calls))
         (done))
       0))))

(deftest install-address-handlers-covers-bootstrap-and-clear-branches-test
  (let [store (atom {:webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "BTC"}}]}
                                :open-orders [{:coin "BTC" :oid 11}]
                                :fills [{:tid 21}]
                                :fundings [{:id "fund-1"}]
                                :fundings-raw [{:id "fund-1"}]}
                     :account-info {:funding-history {:loading? true
                                                      :error "stale-funding"}
                                    :order-history {:request-id 7
                                                    :loading? true
                                                    :error "stale"}}
                     :orders {:open-orders [{:coin "BTC" :oid 12}]
                              :open-orders-snapshot [{:coin "BTC" :oid 13}]
                              :open-orders-snapshot-by-dex {"dex-a" [{}]}
                              :recently-canceled-oids #{12}
                              :recently-canceled-order-keys #{{:oid 12 :asset-id 0}}
                              :fills [{:tid 22}]
                              :fundings-raw [1]
                              :fundings [2]
                              :order-history [3]
                              :twap-states [{:coin "BTC"}]
                              :twap-history [{:time 1000}]
                              :twap-slice-fills [{:tid 23}]
                              :pending-cancel-oids #{12}}
                     :perp-dex-clearinghouse {"dex-a" {:assetPositions []}}
                     :portfolio {:summary-by-key {:day {:vlm 10}}
                                 :user-fees {:dailyUserVlm [[0 1]]}
	                                 :loading? true
	                                 :user-fees-loading? true
	                                 :user-fees-loading-for-address "0xabc"
	                                 :error "portfolio-error"
	                                 :user-fees-error "user-fees-error"
	                                 :user-fees-error-for-address "0xabc"
	                                 :loaded-at-ms 1
	                                 :user-fees-loaded-at-ms 2
	                                 :user-fees-loaded-for-address "0xabc"}
                     :spot {:clearinghouse-state {:time 1}}
                     :account {:mode :unified
                               :abstraction-raw "raw"}})
        startup-runtime-atom (atom {:bootstrapped-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})
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
      ((:on-change address-handler) "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
      (is (= ["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"] @bootstrap-calls))
      ((:on-change address-handler) nil)
      (is (nil? (:bootstrapped-address @startup-runtime-atom)))
      (is (nil? (:webdata2 @store)))
      (is (= [] (get-in @store [:orders :open-orders])))
      (is (= false (get-in @store [:orders :open-orders-hydrated?])))
      (is (= [] (get-in @store [:orders :open-orders-snapshot])))
      (is (= {} (get-in @store [:orders :open-orders-snapshot-by-dex])))
      (is (= #{} (get-in @store [:orders :recently-canceled-oids])))
      (is (= #{} (get-in @store [:orders :recently-canceled-order-keys])))
      (is (= [] (get-in @store [:orders :fills])))
      (is (= [] (get-in @store [:orders :fundings-raw])))
      (is (= [] (get-in @store [:orders :fundings])))
      (is (= [] (get-in @store [:orders :order-history])))
      (is (= [] (get-in @store [:orders :twap-states])))
      (is (= [] (get-in @store [:orders :twap-history])))
      (is (= [] (get-in @store [:orders :twap-slice-fills])))
      (is (nil? (get-in @store [:orders :pending-cancel-oids])))
      (is (= 8 (get-in @store [:account-info :order-history :request-id])))
      (is (false? (get-in @store [:account-info :order-history :loading?])))
      (is (nil? (get-in @store [:account-info :order-history :error])))
      (is (false? (get-in @store [:account-info :funding-history :loading?])))
      (is (nil? (get-in @store [:account-info :funding-history :error])))
      (is (= {} (get-in @store [:perp-dex-clearinghouse])))
      (is (nil? (get-in @store [:spot :clearinghouse-state])))
      (is (= {} (get-in @store [:portfolio :summary-by-key])))
      (is (nil? (get-in @store [:portfolio :user-fees])))
      (is (false? (get-in @store [:portfolio :loading?])))
	      (is (false? (get-in @store [:portfolio :user-fees-loading?])))
	      (is (nil? (get-in @store [:portfolio :user-fees-loading-for-address])))
	      (is (nil? (get-in @store [:portfolio :error])))
	      (is (nil? (get-in @store [:portfolio :user-fees-error])))
	      (is (nil? (get-in @store [:portfolio :user-fees-error-for-address])))
	      (is (nil? (get-in @store [:portfolio :loaded-at-ms])))
	      (is (nil? (get-in @store [:portfolio :user-fees-loaded-at-ms])))
	      (is (nil? (get-in @store [:portfolio :user-fees-loaded-for-address])))
      (is (= {:mode :classic
              :abstraction-raw nil}
             (:account @store))))))

(deftest reload-address-handlers-replaces-startup-owned-handlers-without-syncing-current-address-test
  (let [store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}})
        stop-calls (atom [])
        remove-calls (atom [])
        init-calls (atom [])
        added-handlers (atom [])
        sync-calls (atom 0)]
    (startup-runtime/reload-address-handlers!
     {:store store
      :bootstrap-account-data! (fn [& _] nil)
      :init-with-webdata2! (fn [store-arg subscribe-fn unsubscribe-fn]
                             (swap! init-calls conj [store-arg subscribe-fn unsubscribe-fn]))
      :add-handler! (fn [handler]
                      (swap! added-handlers conj handler))
      :remove-handler! (fn [handler-name]
                         (swap! remove-calls conj handler-name))
      :stop-watching! (fn [store-arg]
                        (swap! stop-calls conj store-arg))
      :sync-current-address! (fn [_store-arg]
                               (swap! sync-calls inc))
      :create-user-handler (fn [_subscribe-fn _unsubscribe-fn]
                             {:kind :user-handler})
      :subscribe-user! (fn [& _] nil)
      :unsubscribe-user! (fn [& _] nil)
      :subscribe-webdata2! (fn [& _] nil)
      :unsubscribe-webdata2! (fn [& _] nil)
      :address-handler-reify (fn [_on-change handler-name]
                               {:kind :address-handler
                                :name handler-name})
      :address-handler-name "startup-account-bootstrap-handler"})
    (is (= [store] @stop-calls))
    (is (= ["webdata2-subscription-handler"
            "user-ws-subscription-handler"
            "startup-account-bootstrap-handler"]
           @remove-calls))
    (is (= 1 (count @init-calls)))
    (is (= 2 (count @added-handlers)))
    (is (zero? @sync-calls))))

(deftest critical-deferred-and-stream-initialization-cover-remaining-runtime-branches-test
  (async done
    (let [mark-calls (atom [])
          deferred-callbacks (atom [])
          deps {:store (atom {:active-asset "BTC"
                              :router {:path "/trade"}})
                :ws-url "wss://example.test/ws"
                :log-fn (fn [& _] nil)
                :init-connection! (fn [url]
                                    (swap! mark-calls conj [:init-connection url]))
                :init-active-ctx! (fn [_store]
                                    (swap! mark-calls conj :init-active-ctx))
                :init-candles! (fn [_store]
                                 (swap! mark-calls conj :init-candles))
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
      (is (some #{:init-candles} @mark-calls))
      (is (some #(= [:dispatch [[:actions/subscribe-to-asset "BTC"]]] %) @mark-calls))
      (is (not-any? (fn [entry]
                      (and (vector? entry)
                           (= :dispatch (first entry))
                           (let [effects (second entry)]
                             (and (vector? effects)
                                  (vector? (first effects))
                                  (contains? #{:actions/load-leaderboard-route
                                               :actions/load-vault-route
                                               :actions/load-funding-comparison-route
                                               :actions/load-staking-route
                                               :actions/load-api-wallet-route}
                                             (ffirst effects))))))
                    @mark-calls))
      (swap! (:store deps) assoc
             :active-asset nil
             :router {:path "/leaderboard"})
      (startup-runtime/initialize-remote-data-streams! deps)
      (is (= 1 (count (filter #(= [:dispatch [[:actions/subscribe-to-asset "BTC"]]] %) @mark-calls))))
      (is (= 1 (count (filter #(= [:dispatch [[:actions/load-leaderboard-route "/leaderboard"]]] %) @mark-calls))))
      (is (zero? (count (filter #(= [:dispatch [[:actions/load-vault-route "/leaderboard"]]] %) @mark-calls))))
      (is (zero? (count (filter #(= [:dispatch [[:actions/load-funding-comparison-route "/leaderboard"]]] %) @mark-calls))))
      (is (zero? (count (filter #(= [:dispatch [[:actions/load-staking-route "/leaderboard"]]] %) @mark-calls))))
      (is (zero? (count (filter #(= [:dispatch [[:actions/load-api-wallet-route "/leaderboard"]]] %) @mark-calls))))
      (is (= 2 (count (filter #{:schedule-deferred} @mark-calls))))
      (let [critical-context-fetches (atom [])
            bootstrap-selector-fetches (atom [])
            deferred-selector-fetches (atom [])]
        (with-redefs [platform/set-timeout! (fn [_f _delay-ms]
                                              :timer-id)]
          (startup-runtime/start-critical-bootstrap!
           {:store (:store deps)
            :fetch-asset-contexts! (fn [_store opts]
                                     (swap! critical-context-fetches conj opts)
                                     (js/Promise.resolve :ctx))
            :fetch-asset-selector-markets! (fn [_store opts]
                                             (swap! bootstrap-selector-fetches conj opts)
                                             (js/Promise.resolve :bootstrap))
            :mark-performance! (fn [mark]
                                 (swap! mark-calls conj [:mark mark]))})
          (startup-runtime/run-deferred-bootstrap!
           {:store (:store deps)
            :fetch-asset-selector-markets! (fn [_store opts]
                                             (swap! deferred-selector-fetches conj opts)
                                             (js/Promise.resolve :full))
            :mark-performance! (fn [mark]
                                 (swap! mark-calls conj [:mark mark]))})
          (js/setTimeout
           (fn []
             (is (= [{:priority :high}] @critical-context-fetches))
             (is (= [{:phase :bootstrap}] @bootstrap-selector-fetches))
             (is (= [{:phase :full}] @deferred-selector-fetches))
             (is (some #(= [:mark "app:critical-data:ready"] %) @mark-calls))
             (is (some #(= [:mark "app:full-bootstrap:ready"] %) @mark-calls))
             (let [skipped-fetches (atom []) skipped-marks (atom [])]
               (-> (startup-runtime/run-deferred-bootstrap!
                    {:store (atom {:asset-selector {:cache-hydrated? true
                                                    :markets [{:key "outcome:0" :coin "#0" :market-type :outcome}]}})
                     :fetch-asset-selector-markets! (fn [_store opts]
                                                      (swap! skipped-fetches conj opts)
                                                      (js/Promise.resolve :unexpected))
                     :mark-performance! (fn [mark]
                                          (swap! skipped-marks conj mark))})
                   (.then
                    (fn []
                      (is (= [] @skipped-fetches))
                      (is (= ["app:full-bootstrap:ready"] @skipped-marks))
                      (let [forced-fetches (atom [])
                            forced-marks (atom [])]
                        (-> (startup-runtime/run-deferred-bootstrap!
                             {:store (atom {:asset-selector {:cache-hydrated? true}
                                            :active-market {:coin "xyz:SILVER"
                                                            :market-type :perp}})
                              :fetch-asset-selector-markets! (fn [_store opts]
                                                               (swap! forced-fetches conj opts)
                                                               (js/Promise.resolve :full))
                              :mark-performance! (fn [mark]
                                                   (swap! forced-marks conj mark))})
                            (.then
                             (fn []
                               (is (= [{:phase :full}] @forced-fetches))
                               (is (= ["app:full-bootstrap:ready"] @forced-marks))))))
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
                      (done)))
                   (.catch
                    (fn [err]
                      (is false (str "Unexpected deferred bootstrap error: " err))
                      (done))))))
           0))))))
