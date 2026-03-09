(ns hyperopen.core-bootstrap.runtime-startup-test
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [hyperopen.api.default :as api]
            [hyperopen.app.bootstrap :as app-bootstrap]
            [hyperopen.app.startup :as app-startup]
            [hyperopen.core :as app-core]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.startup.collaborators :as startup-collaborators]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.telemetry.console-warning :as console-warning]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.wallet.address-watcher :as address-watcher]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.websocket.user :as user-ws]
            [hyperopen.websocket.webdata2 :as webdata2]
            [hyperopen.core-bootstrap.test-support.browser-mocks :as browser-mocks]
            [hyperopen.core-bootstrap.test-support.fixtures :as fixtures]))

(use-fixtures :once fixtures/runtime-bootstrap-fixture)
(use-fixtures :each fixtures/per-test-runtime-fixture)

(def with-test-navigator browser-mocks/with-test-navigator)

(deftest initialize-remote-data-streams-phased-bootstrap-test
  (let [phases (atom [])
        critical-fetches (atom 0)
        deferred-callback (atom nil)]
    (with-redefs [ws-client/init-connection! (fn [_] nil)
                  active-ctx/init! (fn [_] nil)
                  orderbook/init! (fn [_] nil)
                  trades/init! (fn [_] nil)
                  user-ws/init! (fn [_] nil)
                  webdata2/init! (fn [_] nil)
                  address-watcher/init-with-webdata2! (fn [& _] nil)
                  address-watcher/add-handler! (fn [& _] nil)
                  address-watcher/sync-current-address! (fn [& _] nil)
                  api/request-asset-contexts! (fn request-asset-contexts-mock
                                                ([]
                                                 (request-asset-contexts-mock {}))
                                                ([_opts]
                                                 (swap! critical-fetches inc)
                                                 (js/Promise.resolve {})))
                  api/request-asset-selector-markets! (fn [store opts]
                                                        (let [phase (if (= :bootstrap (:phase opts))
                                                                      :bootstrap
                                                                      :full)]
                                                          (swap! phases conj phase)
                                                          (js/Promise.resolve {:phase phase
                                                                               :market-state {:markets []
                                                                                              :market-by-key {}
                                                                                              :active-market nil
                                                                                              :loaded-at-ms 1}})))
                  app-startup/schedule-idle-or-timeout! (fn [f]
                                                          (reset! deferred-callback f)
                                                          :scheduled)]
      (app-core/initialize-remote-data-streams!)
      (is (= 1 @critical-fetches))
      (is (= [:bootstrap] @phases))
      (is (fn? @deferred-callback))
      (@deferred-callback)
      (is (= [:bootstrap :full] @phases)))))

(deftest ensure-runtime-bootstrapped-runs-bootstrap-once-test
  (let [calls (atom 0)]
    (swap! runtime-state/runtime assoc :runtime-bootstrapped? false)
    (app-bootstrap/ensure-runtime-bootstrapped!
     runtime-state/runtime
     (fn []
       (swap! calls inc)))
    (app-bootstrap/ensure-runtime-bootstrapped!
     runtime-state/runtime
     (fn []
       (swap! calls inc)))
    (is (= 1 @calls))
    (is (true? (runtime-state/runtime-bootstrapped? runtime-state/runtime)))
    (swap! runtime-state/runtime assoc :runtime-bootstrapped? true)))

(deftest init-runs-startup-sequence-once-test
  (let [ensure-calls (atom 0)
        bootstrap-calls (atom 0)
        startup-calls (atom 0)
        warning-calls (atom 0)
        original-runtime-bootstrapped? (:runtime-bootstrapped? @runtime-state/runtime)
        original-app-started? (:app-started? @runtime-state/runtime)]
    (try
      (swap! runtime-state/runtime assoc
             :runtime-bootstrapped? false
             :app-started? false)
      (with-redefs [app-bootstrap/ensure-runtime-bootstrapped! (fn [_runtime bootstrap-fn]
                                                                  (swap! ensure-calls inc)
                                                                  (bootstrap-fn))
                    app-bootstrap/bootstrap-runtime! (fn [_]
                                                       (swap! bootstrap-calls inc))
                    app-startup/init! (fn [_]
                                        (swap! startup-calls inc))
                    console-warning/emit-warning! (fn []
                                                    (swap! warning-calls inc))]
        (app-core/init)
        (app-core/init))
      (is (= 1 @ensure-calls))
      (is (= 1 @bootstrap-calls))
      (is (= 1 @startup-calls))
      (is (= 1 @warning-calls))
      (finally
        (swap! runtime-state/runtime assoc
               :runtime-bootstrapped? original-runtime-bootstrapped?
               :app-started? original-app-started?)))))

(deftest reload-reruns-bootstrap-and-reload-bindings-test
  (let [bootstrap-calls (atom [])
        reload-binding-calls (atom [])
        render-calls (atom [])
        connected-handler-calls (atom 0)
        runtime (atom {:runtime-bootstrapped? false})
        store (atom {:reload :state})]
    (with-redefs [app-bootstrap/bootstrap-runtime! (fn [system]
                                                     (swap! bootstrap-calls conj system))
                  app-startup/reload-runtime-bindings! (fn [system]
                                                         (swap! reload-binding-calls conj system))
                  app-bootstrap/render-app! (fn [state]
                                              (swap! render-calls conj state))
                  telemetry/log! (fn [& _] nil)
                  wallet/set-on-connected-handler! (fn [_handler]
                                                     (swap! connected-handler-calls inc))]
      (app-bootstrap/reload! {:runtime runtime
                              :store store})
      (app-bootstrap/reload! {:runtime runtime
                              :store store})
      (is (= 2 (count @bootstrap-calls)))
      (is (= 2 (count @reload-binding-calls)))
      (is (= [{:reload :state}
              {:reload :state}]
             @render-calls))
      (is (= 2 @connected-handler-calls))
      (is (true? (:runtime-bootstrapped? @runtime))))))

(deftest make-system-creates-isolated-store-and-runtime-atoms-test
  (let [system-a (app-core/make-system)
        system-b (app-core/make-system)]
    (swap! (:store system-a) assoc :marker :a)
    (swap! (:runtime system-a) assoc :marker :a)
    (is (nil? (:marker @(:store system-b))))
    (is (nil? (:marker @(:runtime system-b))))))

(deftest register-icon-service-worker-registers-when-supported-test
  (let [registered-paths (atom [])]
    (with-test-navigator
      #js {:serviceWorker
           #js {:register (fn [script-path]
                            (swap! registered-paths conj script-path)
                            (js/Promise.resolve #js {:scope "/"}))}}
      (fn []
        (app-startup/register-icon-service-worker!
         {:runtime runtime-state/runtime
          :store app-core/store})
        (is (= [runtime-state/icon-service-worker-path] @registered-paths))))))

(deftest register-icon-service-worker-skips-when-unsupported-test
  (with-test-navigator
    #js {}
    (fn []
      (is (nil? (app-startup/register-icon-service-worker!
                 {:runtime runtime-state/runtime
                  :store app-core/store}))))))

(deftest account-bootstrap-two-stage-and-guarded-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          stage-a-calls (atom [])
          stage-b-calls (atom [])
          test-store (atom {:wallet {:address address}})
          system {:runtime runtime-state/runtime
                  :store test-store}]
      (with-redefs [startup-collaborators/startup-base-deps
                    (fn [deps]
                      (merge
                       deps
                       {:log-fn (fn [& _] nil)
                        :fetch-frontend-open-orders! (fn [_store address opts]
                                                       (swap! stage-a-calls conj [:open-orders [address opts]])
                                                       (js/Promise.resolve nil))
                        :fetch-user-fills! (fn [_store address opts]
                                             (swap! stage-a-calls conj [:fills [address opts]])
                                             (js/Promise.resolve nil))
                        :fetch-spot-clearinghouse-state! (fn [_store address opts]
                                                           (swap! stage-a-calls conj [:spot [address opts]])
                                                           (js/Promise.resolve nil))
                        :fetch-user-abstraction! (fn [_store address opts]
                                                   (swap! stage-a-calls conj [:abstraction [address opts]])
                                                   (js/Promise.resolve {:mode :classic
                                                                        :abstraction-raw nil}))
                        :fetch-portfolio! (fn [_store address opts]
                                            (swap! stage-a-calls conj [:portfolio [address opts]])
                                            (js/Promise.resolve {}))
                        :fetch-user-fees! (fn [_store address opts]
                                            (swap! stage-a-calls conj [:user-fees [address opts]])
                                            (js/Promise.resolve nil))
                        :fetch-historical-orders! (fn [_store request-id opts]
                                                    (swap! stage-a-calls conj [:order-history [request-id opts]])
                                                    (js/Promise.resolve nil))
                        :fetch-and-merge-funding-history! (fn [_store address opts]
                                                            (swap! stage-a-calls conj [:fundings [address opts]])
                                                            (js/Promise.resolve nil))
                        :ensure-perp-dexs! (fn [_store _opts]
                                             (js/Promise.resolve {:dex-names ["dex-1" "dex-2"]
                                                                  :fee-config-by-name {"dex-1" {:deployer-fee-scale 0.1}
                                                                                       "dex-2" {:deployer-fee-scale 0.2}}}))
                        :stage-b-account-bootstrap! (fn [address dexs]
                                                      (swap! stage-b-calls conj [address dexs]))
                        :startup-stream-backfill-delay-ms 0}))]
        (app-startup/bootstrap-account-data! system address)
        (js/setTimeout
         (fn []
           (is (= 8 (count @stage-a-calls)))
           (is (some #(= :abstraction (first %)) @stage-a-calls))
           (is (some #(= :portfolio (first %)) @stage-a-calls))
           (is (some #(= :user-fees (first %)) @stage-a-calls))
           (is (some #(= :order-history (first %)) @stage-a-calls))
           (is (= [[address ["dex-1" "dex-2"]]] @stage-b-calls))
           ;; Same address should not trigger stage A/B again.
           (app-startup/bootstrap-account-data! system address)
           (js/setTimeout
            (fn []
              (is (= 8 (count @stage-a-calls)))
              (is (= 1 (count @stage-b-calls)))
              (done))
            0))
         0)))))

(deftest install-address-handlers-clears-account-mode-on-address-removal-test
  (let [captured-handlers (atom [])
        test-store (atom {:orders {:open-orders-snapshot-by-dex {"dex-a" [{}]}
                                   :fundings-raw [1]
                                   :fundings [2]
                                   :order-history [3]}
                          :perp-dex-clearinghouse {"dex-a" {:assetPositions []}}
                          :portfolio {:summary-by-key {:day {:vlm 1}}
                                      :user-fees {:dailyUserVlm [[0 1]]}
                                      :loading? true
                                      :user-fees-loading? true
                                      :error "portfolio-error"
                                      :user-fees-error "user-fees-error"
                                      :loaded-at-ms 1
                                      :user-fees-loaded-at-ms 2}
                          :spot {:clearinghouse-state {:time 1}}
                          :account {:mode :unified
                                    :abstraction-raw "unifiedAccount"}})]
    (with-redefs [address-watcher/init-with-webdata2! (fn [& _] nil)
                  address-watcher/add-handler! (fn [handler]
                                                 (swap! captured-handlers conj handler))
                  address-watcher/sync-current-address! (fn [& _] nil)]
      (app-startup/install-address-handlers!
       {:runtime runtime-state/runtime
        :store test-store})
      (let [startup-handler (last @captured-handlers)]
        (address-watcher/on-address-changed startup-handler "0xabc" nil)
        (is (= {:mode :classic
                :abstraction-raw nil}
               (:account @test-store)))
        (is (= {} (get-in @test-store [:orders :open-orders-snapshot-by-dex])))
        (is (= [] (get-in @test-store [:orders :fundings-raw])))
        (is (= [] (get-in @test-store [:orders :fundings])))
        (is (= [] (get-in @test-store [:orders :order-history])))
        (is (= {} (get-in @test-store [:perp-dex-clearinghouse])))
        (is (nil? (get-in @test-store [:spot :clearinghouse-state])))
        (is (= {} (get-in @test-store [:portfolio :summary-by-key])))
        (is (nil? (get-in @test-store [:portfolio :user-fees])))
        (is (false? (get-in @test-store [:portfolio :loading?])))
        (is (false? (get-in @test-store [:portfolio :user-fees-loading?])))
        (is (nil? (get-in @test-store [:portfolio :error])))
        (is (nil? (get-in @test-store [:portfolio :user-fees-error])))
        (is (nil? (get-in @test-store [:portfolio :loaded-at-ms])))
        (is (nil? (get-in @test-store [:portfolio :user-fees-loaded-at-ms])))))))
