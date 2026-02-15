(ns hyperopen.core-bootstrap-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is use-fixtures]]
            [nexus.registry :as nxr]
            [hyperopen.api.default :as api]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.account.history.effects :as account-history-effects]
            [hyperopen.app.bootstrap :as app-bootstrap]
            [hyperopen.app.startup :as app-startup]
            [hyperopen.core :as app-core]
            [hyperopen.core.compat :as core]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.state.trading :as trading]
            [hyperopen.telemetry.console-warning :as console-warning]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.address-watcher :as address-watcher]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.websocket.user :as user-ws]
            [hyperopen.websocket.webdata2 :as webdata2]))

(defn- reset-startup-runtime! []
  (swap! runtime-state/runtime
         assoc
         :startup {:deferred-scheduled? false
                   :bootstrapped-address nil
                   :summary-logged? false}))

(use-fixtures
  :once
  {:before (fn []
             (app-bootstrap/ensure-runtime-bootstrapped!
              runtime-state/runtime
              #(app-bootstrap/bootstrap-runtime!
                {:runtime runtime-state/runtime
                 :store app-core/store})))
   :after (fn [])})

(use-fixtures
  :each
  {:before (fn []
             (reset-startup-runtime!)
             (swap! app-core/store assoc :active-asset nil))
   :after (fn []
            (reset-startup-runtime!))})

(defn- extract-saved-order-form [effects]
  (or (some (fn [effect]
              (when (= :effects/save-many (first effect))
                (some (fn [[path value]]
                        (when (= [:order-form] path) value))
                      (second effect))))
            effects)
      (some (fn [effect]
              (when (and (= :effects/save (first effect))
                         (= [:order-form] (second effect)))
                (nth effect 2)))
            effects)))

(defn- extract-saved-order-form-ui [effects]
  (or (some (fn [effect]
              (when (= :effects/save-many (first effect))
                (some (fn [[path value]]
                        (when (= [:order-form-ui] path) value))
                      (second effect))))
            effects)
      (some (fn [effect]
              (when (and (= :effects/save (first effect))
                         (= [:order-form-ui] (second effect)))
                (nth effect 2)))
            effects)))

(defn- with-test-local-storage [f]
  (let [original-local-storage (.-localStorage js/globalThis)
        storage (atom {})]
    (set! (.-localStorage js/globalThis)
          #js {:setItem (fn [key value]
                          (swap! storage assoc (str key) (str value)))
               :getItem (fn [key]
                          (get @storage (str key)))
               :removeItem (fn [key]
                             (swap! storage dissoc (str key)))
               :clear (fn []
                        (reset! storage {}))})
    (try
      (f)
      (finally
        (set! (.-localStorage js/globalThis) original-local-storage)))))

(defn- with-test-navigator [navigator-value f]
  (let [navigator-prop "navigator"
        original-navigator-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis navigator-prop)]
    (js/Object.defineProperty js/globalThis navigator-prop
                              #js {:value navigator-value
                                   :configurable true})
    (try
      (f)
      (finally
        (if original-navigator-descriptor
          (js/Object.defineProperty js/globalThis navigator-prop original-navigator-descriptor)
          (js/Reflect.deleteProperty js/globalThis navigator-prop))))))

(defn- clear-wallet-copy-feedback-timeout! []
  (when-let [timeout-id (get-in @runtime-state/runtime [:timeouts :wallet-copy])]
    (js/clearTimeout timeout-id)
    (swap! runtime-state/runtime assoc-in [:timeouts :wallet-copy] nil)))

(defn- clear-order-feedback-toast-timeout! []
  (when-let [timeout-id (get-in @runtime-state/runtime [:timeouts :order-toast])]
      (js/clearTimeout timeout-id)
      (swap! runtime-state/runtime assoc-in [:timeouts :order-toast] nil)))

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

(deftest mark-loaded-asset-icon-promotes-key-to-loaded-and-clears-missing-test
  (let [state {:asset-selector {:loaded-icons #{}
                                :missing-icons #{"perp:BTC"}}}
        effects (core/mark-loaded-asset-icon state "perp:BTC")]
    (is (= [[:effects/queue-asset-icon-status {:market-key "perp:BTC"
                                               :status :loaded}]]
           effects))))

(deftest mark-loaded-asset-icon-noop-when-key-already-loaded-and-not-missing-test
  (let [state {:asset-selector {:loaded-icons #{"perp:BTC"}
                                :missing-icons #{}}}
        effects (core/mark-loaded-asset-icon state "perp:BTC")]
    (is (= [] effects))))

(deftest mark-missing-asset-icon-promotes-key-to-missing-and-clears-loaded-test
  (let [state {:asset-selector {:loaded-icons #{"perp:BTC"}
                                :missing-icons #{}}}
        effects (core/mark-missing-asset-icon state "perp:BTC")]
    (is (= [[:effects/queue-asset-icon-status {:market-key "perp:BTC"
                                               :status :missing}]]
           effects))))

(deftest mark-missing-asset-icon-noop-when-key-already-missing-and-not-loaded-test
  (let [state {:asset-selector {:loaded-icons #{}
                                :missing-icons #{"perp:BTC"}}}
        effects (core/mark-missing-asset-icon state "perp:BTC")]
    (is (= [] effects))))

(deftest apply-asset-icon-status-updates-merges-statuses-test
  (let [state {:asset-selector {:loaded-icons #{"perp:BTC"}
                                :missing-icons #{"perp:ETH"}}}
        result (core/apply-asset-icon-status-updates state {"perp:BTC" :missing
                                                            "perp:ETH" :loaded
                                                            "perp:SOL" :loaded})]
    (is (= #{"perp:ETH" "perp:SOL"} (:loaded-icons result)))
    (is (= #{"perp:BTC"} (:missing-icons result)))
    (is (= true (:changed? result)))))

(deftest queue-asset-icon-status-batches-updates-into-single-flush-test
  (let [store (atom {:asset-selector {:loaded-icons #{}
                                      :missing-icons #{}}})
        scheduled-callback (atom nil)]
    (swap! runtime-state/runtime assoc-in [:asset-icons :pending] {})
    (swap! runtime-state/runtime assoc-in [:asset-icons :flush-handle] nil)
    (with-redefs [effect-adapters/schedule-animation-frame! (fn [f]
                                                               (reset! scheduled-callback f)
                                                               :raf-id)]
      (core/queue-asset-icon-status nil store {:market-key "perp:BTC" :status :loaded})
      (core/queue-asset-icon-status nil store {:market-key "perp:BTC" :status :missing})
      (core/queue-asset-icon-status nil store {:market-key "perp:ETH" :status :loaded})
      (is (fn? @scheduled-callback))
      (@scheduled-callback)
      (is (= #{"perp:ETH"} (get-in @store [:asset-selector :loaded-icons])))
      (is (= #{"perp:BTC"} (get-in @store [:asset-selector :missing-icons])))
      (is (= {} (get-in @runtime-state/runtime [:asset-icons :pending])))
      (is (nil? (get-in @runtime-state/runtime [:asset-icons :flush-handle]))))))

(deftest maybe-increase-asset-selector-render-limit-expands-near-bottom-test
  (let [state {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                                :render-limit 120}}
        effects (core/maybe-increase-asset-selector-render-limit state 5100)]
    (is (= [[:effects/save [:asset-selector :render-limit] 200]]
           effects))))

(deftest maybe-increase-asset-selector-render-limit-noop-when-not-near-bottom-test
  (let [state {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                                :render-limit 120}}
        effects (core/maybe-increase-asset-selector-render-limit state 0)]
    (is (= [] effects))))

(deftest increase-asset-selector-render-limit-steps-forward-test
  (let [state {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                                :render-limit 120}}
        effects (core/increase-asset-selector-render-limit state)]
    (is (= [[:effects/save [:asset-selector :render-limit] 200]]
           effects))))

(deftest show-all-asset-selector-markets-expands-to-total-test
  (let [state {:asset-selector {:markets (vec (repeat 622 {:key "perp:T"}))
                                :render-limit 120}}
        effects (core/show-all-asset-selector-markets state)]
    (is (= [[:effects/save [:asset-selector :render-limit] 622]]
           effects))))

(deftest account-bootstrap-two-stage-and-guarded-test
  (async done
    (let [stage-a-calls (atom [])
          stage-b-calls (atom [])
          original-request-open-orders api/request-frontend-open-orders!
          original-request-user-fills api/request-user-fills!
          original-request-spot-state api/request-spot-clearinghouse-state!
          original-request-user-abstraction api/request-user-abstraction!
          original-ensure-perp-dexs-data api/ensure-perp-dexs-data!
          original-fetch-and-merge-funding-history account-history-effects/fetch-and-merge-funding-history!
          original-stage-b app-startup/stage-b-account-bootstrap!]
      (swap! app-core/store assoc-in [:wallet :address] "0xabc")
      (set! api/request-frontend-open-orders!
            (fn request-frontend-open-orders-mock
              ([address]
               (request-frontend-open-orders-mock address {}))
              ([address opts]
               (request-frontend-open-orders-mock address (:dex opts) (dissoc opts :dex)))
              ([address dex opts]
               (swap! stage-a-calls conj [:open-orders [address dex opts]])
               (js/Promise.resolve nil))))
      (set! api/request-user-fills!
            (fn request-user-fills-mock
              ([address]
               (request-user-fills-mock address {}))
              ([address opts]
               (swap! stage-a-calls conj [:fills [address opts]])
               (js/Promise.resolve nil))))
      (set! api/request-spot-clearinghouse-state!
            (fn request-spot-clearinghouse-state-mock
              ([address]
               (request-spot-clearinghouse-state-mock address {}))
              ([address opts]
               (swap! stage-a-calls conj [:spot [address opts]])
               (js/Promise.resolve nil))))
      (set! api/request-user-abstraction!
            (fn request-user-abstraction-mock
              ([address]
               (request-user-abstraction-mock address {}))
              ([address opts]
               (swap! stage-a-calls conj [:abstraction [address opts]])
               (js/Promise.resolve "default"))))
      (set! api/ensure-perp-dexs-data!
            (fn ensure-perp-dexs-data-mock
              ([store]
               (ensure-perp-dexs-data-mock store {}))
              ([_ _]
               (js/Promise.resolve ["dex-1" "dex-2"]))))
      (set! account-history-effects/fetch-and-merge-funding-history!
            (fn [_store address opts]
              (swap! stage-a-calls conj [:fundings [address opts]])
              (js/Promise.resolve nil)))
      (set! app-startup/stage-b-account-bootstrap!
            (fn [_system address dexs]
              (swap! stage-b-calls conj [address dexs])))
        (letfn [(restore! []
                (set! api/request-frontend-open-orders! original-request-open-orders)
                (set! api/request-user-fills! original-request-user-fills)
                (set! api/request-spot-clearinghouse-state! original-request-spot-state)
                (set! api/request-user-abstraction! original-request-user-abstraction)
                (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
                (set! account-history-effects/fetch-and-merge-funding-history! original-fetch-and-merge-funding-history)
                (set! app-startup/stage-b-account-bootstrap! original-stage-b))]
        (app-startup/bootstrap-account-data!
         {:runtime runtime-state/runtime
          :store app-core/store}
         "0xabc")
        (js/setTimeout
         (fn []
           (is (= 5 (count @stage-a-calls)))
           (is (some #(= :abstraction (first %)) @stage-a-calls))
           (is (= [["0xabc" ["dex-1" "dex-2"]]] @stage-b-calls))
           ;; Same address should not trigger stage A/B again.
           (app-startup/bootstrap-account-data!
            {:runtime runtime-state/runtime
             :store app-core/store}
            "0xabc")
           (js/setTimeout
            (fn []
              (is (= 5 (count @stage-a-calls)))
              (is (= 1 (count @stage-b-calls)))
              (restore!)
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
        (is (nil? (get-in @test-store [:spot :clearinghouse-state])))))))

(deftest select-account-info-tab-funding-history-saves-selection-before-fetch-test
  (let [state {:account-info {:selected-tab :balances
                              :funding-history {:filters {:coin-set #{}
                                                          :start-time-ms 0
                                                          :end-time-ms 1000}
                                                :request-id 2}}
               :orders {:fundings-raw []}}
        effects (core/select-account-info-tab state :funding-history)
        immediate (first effects)
        path-values (second immediate)]
    (is (= :effects/save-many (first immediate)))
    (is (= [:account-info :selected-tab]
           (-> path-values first first)))
    (is (= :funding-history
           (-> path-values first second)))
    (is (= [:effects/api-fetch-user-funding-history 3]
           (second effects)))))

(deftest apply-funding-history-filters-resets-pagination-and-refetches-only-on-time-range-change-test
  (let [base-state {:account-info {:funding-history {:filters {:coin-set #{}
                                                          :start-time-ms 1000
                                                          :end-time-ms 2000}
                                                    :draft-filters {:coin-set #{"BTC"}
                                                                    :start-time-ms 1000
                                                                    :end-time-ms 2000}
                                                    :page 4
                                                    :page-input "4"
                                                    :request-id 5}}
                    :orders {:fundings-raw []}}
        no-refetch (core/apply-funding-history-filters base-state)
        with-refetch (core/apply-funding-history-filters
                      (assoc-in base-state
                                [:account-info :funding-history :draft-filters :end-time-ms]
                                3000))]
    (is (some #(= [[:account-info :funding-history :page] 1] %)
              (-> no-refetch first second)))
    (is (some #(= [[:account-info :funding-history :page-input] "1"] %)
              (-> no-refetch first second)))
    (is (not-any? #(= :effects/api-fetch-user-funding-history (first %))
                  no-refetch))
    (is (some #(= :effects/api-fetch-user-funding-history (first %))
              with-refetch))))

(deftest sort-funding-history-toggles-direction-on-same-column-test
  (let [state {:account-info {:funding-history {:sort {:column "Time"
                                                       :direction :desc}
                                                :page 3
                                                :page-input "3"}}}
        effects (core/sort-funding-history state "Time")]
    (is (= [[:effects/save-many [[[:account-info :funding-history :sort]
                                  {:column "Time" :direction :asc}]
                                 [[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           effects))))

(deftest sort-funding-history-uses-mixed-default-direction-for-new-columns-test
  (let [state {:account-info {:funding-history {:sort {:column "Time"
                                                       :direction :desc}
                                                :page 2
                                                :page-input "2"}}}
        coin-effects (core/sort-funding-history state "Coin")
        payment-effects (core/sort-funding-history state "Payment")]
    (is (= [[:effects/save-many [[[:account-info :funding-history :sort]
                                  {:column "Coin" :direction :asc}]
                                 [[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           coin-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :sort]
                                  {:column "Payment" :direction :desc}]
                                 [[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           payment-effects))))

(deftest view-all-funding-history-resets-pagination-before-fetch-test
  (let [state {:account-info {:funding-history {:filters {:coin-set #{"BTC"}
                                                          :start-time-ms 1000
                                                          :end-time-ms 2000}
                                                :request-id 3
                                                :page 7
                                                :page-input "7"}}
               :orders {:fundings-raw []}}
        effects (core/view-all-funding-history state)
        path-values (-> effects first second)]
    (is (some #(= [[:account-info :funding-history :page] 1] %) path-values))
    (is (some #(= [[:account-info :funding-history :page-input] "1"] %) path-values))
    (is (= [:effects/api-fetch-user-funding-history 4]
           (second effects)))))

(deftest funding-history-pagination-page-size-normalizes-and-persists-test
  (with-test-local-storage
    (fn []
      (let [state {:account-info {:funding-history {:page-size 25
                                                    :page 8
                                                    :page-input "8"}}}
            effects (core/set-funding-history-page-size state "100")]
        (is (= [[:effects/save-many [[[:account-info :funding-history :page-size] 100]
                                     [[:account-info :funding-history :page] 1]
                                     [[:account-info :funding-history :page-input] "1"]]]
                [:effects/local-storage-set "funding-history-page-size" "100"]]
               effects))
        (let [invalid-effects (core/set-funding-history-page-size state "13")]
          (is (= [[:effects/save-many [[[:account-info :funding-history :page-size] 50]
                                       [[:account-info :funding-history :page] 1]
                                       [[:account-info :funding-history :page-input] "1"]]]
                  [:effects/local-storage-set "funding-history-page-size" "50"]]
                 invalid-effects)))))))

(deftest funding-history-pagination-set-page-clamps-and-syncs-input-test
  (let [state {:account-info {:funding-history {:page 2
                                                :page-input "2"}}}
        within (core/set-funding-history-page state 3 5)
        too-high (core/set-funding-history-page state 99 5)
        too-low (core/set-funding-history-page state -2 5)]
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 3]
                                 [[:account-info :funding-history :page-input] "3"]]]]
           within))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 5]
                                 [[:account-info :funding-history :page-input] "5"]]]]
           too-high))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           too-low))))

(deftest funding-history-pagination-next-prev-and-input-apply-test
  (let [state {:account-info {:funding-history {:page 2
                                                :page-input "2"}}}
        next-effects (core/next-funding-history-page state 3)
        prev-effects (core/prev-funding-history-page state 3)
        at-end-effects (core/next-funding-history-page
                        {:account-info {:funding-history {:page 3 :page-input "3"}}}
                        3)
        typed-state {:account-info {:funding-history {:page 1 :page-input "12"}}}
        apply-effects (core/apply-funding-history-page-input typed-state 4)
        invalid-apply-effects (core/apply-funding-history-page-input
                               {:account-info {:funding-history {:page 1 :page-input "abc"}}}
                               4)
        keydown-effects (core/handle-funding-history-page-input-keydown typed-state "Enter" 4)
        keydown-nop (core/handle-funding-history-page-input-keydown typed-state "Escape" 4)]
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 3]
                                 [[:account-info :funding-history :page-input] "3"]]]]
           next-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           prev-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 3]
                                 [[:account-info :funding-history :page-input] "3"]]]]
           at-end-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 4]
                                 [[:account-info :funding-history :page-input] "4"]]]]
           apply-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           invalid-apply-effects))
    (is (= apply-effects keydown-effects))
    (is (= [] keydown-nop))))

(deftest restore-funding-history-pagination-settings-uses-defaults-and-stored-size-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "funding-history-page-size" "100")
      (let [store (atom {:account-info {:funding-history {:page-size 25
                                                          :page 4
                                                          :page-input "4"}}})]
        (core/restore-funding-history-pagination-settings! store)
        (is (= {:page-size 100
                :page 1
                :page-input "1"}
               (select-keys (get-in @store [:account-info :funding-history])
                            [:page-size :page :page-input]))))
      (.setItem js/localStorage "funding-history-page-size" "13")
      (let [store (atom {:account-info {:funding-history {}}})]
        (core/restore-funding-history-pagination-settings! store)
        (is (= 50
               (get-in @store [:account-info :funding-history :page-size])))))))

(deftest trade-history-pagination-page-size-normalizes-and-persists-test
  (with-test-local-storage
    (fn []
      (let [state {:account-info {:trade-history {:page-size 25
                                                  :page 8
                                                  :page-input "8"}}}
            effects (core/set-trade-history-page-size state "100")]
        (is (= [[:effects/save-many [[[:account-info :trade-history :page-size] 100]
                                     [[:account-info :trade-history :page] 1]
                                     [[:account-info :trade-history :page-input] "1"]]]
                [:effects/local-storage-set "trade-history-page-size" "100"]]
               effects))
        (let [invalid-effects (core/set-trade-history-page-size state "13")]
          (is (= [[:effects/save-many [[[:account-info :trade-history :page-size] 50]
                                       [[:account-info :trade-history :page] 1]
                                       [[:account-info :trade-history :page-input] "1"]]]
                  [:effects/local-storage-set "trade-history-page-size" "50"]]
                 invalid-effects)))))))

(deftest trade-history-pagination-set-page-clamps-and-syncs-input-test
  (let [state {:account-info {:trade-history {:page 2
                                              :page-input "2"}}}
        within (core/set-trade-history-page state 3 5)
        too-high (core/set-trade-history-page state 99 5)
        too-low (core/set-trade-history-page state -2 5)]
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 3]
                                 [[:account-info :trade-history :page-input] "3"]]]]
           within))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 5]
                                 [[:account-info :trade-history :page-input] "5"]]]]
           too-high))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           too-low))))

(deftest trade-history-pagination-next-prev-and-input-apply-test
  (let [state {:account-info {:trade-history {:page 2
                                              :page-input "2"}}}
        next-effects (core/next-trade-history-page state 3)
        prev-effects (core/prev-trade-history-page state 3)
        at-end-effects (core/next-trade-history-page
                        {:account-info {:trade-history {:page 3 :page-input "3"}}}
                        3)
        typed-state {:account-info {:trade-history {:page 1 :page-input "12"}}}
        apply-effects (core/apply-trade-history-page-input typed-state 4)
        invalid-apply-effects (core/apply-trade-history-page-input
                               {:account-info {:trade-history {:page 1 :page-input "abc"}}}
                               4)
        keydown-effects (core/handle-trade-history-page-input-keydown typed-state "Enter" 4)
        keydown-nop (core/handle-trade-history-page-input-keydown typed-state "Escape" 4)]
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 3]
                                 [[:account-info :trade-history :page-input] "3"]]]]
           next-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           prev-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 3]
                                 [[:account-info :trade-history :page-input] "3"]]]]
           at-end-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 4]
                                 [[:account-info :trade-history :page-input] "4"]]]]
           apply-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           invalid-apply-effects))
    (is (= apply-effects keydown-effects))
    (is (= [] keydown-nop))))

(deftest sort-trade-history-toggles-direction-on-same-column-test
  (let [state {:account-info {:trade-history {:sort {:column "Time"
                                                     :direction :desc}
                                              :page 3
                                              :page-input "3"}}}
        effects (core/sort-trade-history state "Time")]
    (is (= [[:effects/save-many [[[:account-info :trade-history :sort]
                                  {:column "Time" :direction :asc}]
                                 [[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           effects))))

(deftest sort-trade-history-uses-mixed-default-direction-for-new-columns-test
  (let [state {:account-info {:trade-history {:sort {:column "Time"
                                                     :direction :desc}
                                              :page 2
                                              :page-input "2"}}}
        coin-effects (core/sort-trade-history state "Coin")
        value-effects (core/sort-trade-history state "Trade Value")]
    (is (= [[:effects/save-many [[[:account-info :trade-history :sort]
                                  {:column "Coin" :direction :asc}]
                                 [[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           coin-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :sort]
                                  {:column "Trade Value" :direction :desc}]
                                 [[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           value-effects))))

(deftest restore-trade-history-pagination-settings-uses-defaults-and-stored-size-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "trade-history-page-size" "100")
      (let [store (atom {:account-info {:trade-history {:page-size 25
                                                        :page 4
                                                        :page-input "4"}}})]
        (core/restore-trade-history-pagination-settings! store)
        (is (= {:page-size 100
                :page 1
                :page-input "1"}
               (select-keys (get-in @store [:account-info :trade-history])
                            [:page-size :page :page-input]))))
      (.setItem js/localStorage "trade-history-page-size" "13")
      (let [store (atom {:account-info {:trade-history {}}})]
        (core/restore-trade-history-pagination-settings! store)
        (is (= 50
               (get-in @store [:account-info :trade-history :page-size])))))))

(deftest select-account-info-tab-order-history-saves-selection-before-fetch-test
  (let [state {:account-info {:selected-tab :balances
                              :order-history {:request-id 2}}}
        effects (core/select-account-info-tab state :order-history)
        immediate (first effects)
        path-values (second immediate)]
    (is (= :effects/save-many (first immediate)))
    (is (= [:account-info :selected-tab]
           (-> path-values first first)))
    (is (= :order-history
           (-> path-values first second)))
    (is (= [:effects/api-fetch-historical-orders 3]
           (second effects)))))

(deftest sort-order-history-toggles-direction-on-same-column-test
  (let [state {:account-info {:order-history {:sort {:column "Time"
                                                     :direction :desc}}}}
        effects (core/sort-order-history state "Time")]
    (is (= [[:effects/save-many [[[:account-info :order-history :sort]
                                  {:column "Time" :direction :asc}]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           effects))))

(deftest sort-order-history-uses-mixed-default-direction-for-new-columns-test
  (let [state {:account-info {:order-history {:sort {:column "Time"
                                                     :direction :desc}}}}
        coin-effects (core/sort-order-history state "Coin")
        oid-effects (core/sort-order-history state "Order ID")]
    (is (= [[:effects/save-many [[[:account-info :order-history :sort]
                                  {:column "Coin" :direction :asc}]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           coin-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :sort]
                                  {:column "Order ID" :direction :desc}]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           oid-effects))))

(deftest order-history-filter-actions-update-paths-and-close-dropdown-test
  (let [state {:account-info {:order-history {:filter-open? false
                                              :status-filter :all}}}
        toggle-effects (core/toggle-order-history-filter-open state)
        set-effects (core/set-order-history-status-filter
                     {:account-info {:order-history {:filter-open? true
                                                     :status-filter :all}}}
                     :filled)
        set-invalid-effects (core/set-order-history-status-filter
                             {:account-info {:order-history {:filter-open? true
                                                             :status-filter :all}}}
                             :unknown)]
    (is (= [[:effects/save [:account-info :order-history :filter-open?] true]]
           toggle-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :status-filter] :filled]
                                 [[:account-info :order-history :filter-open?] false]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           set-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :status-filter] :all]
                                 [[:account-info :order-history :filter-open?] false]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           set-invalid-effects))))

(deftest order-history-pagination-page-size-normalizes-and-persists-test
  (with-test-local-storage
    (fn []
      (let [state {:account-info {:order-history {:page-size 25
                                                  :page 8
                                                  :page-input "8"}}}
            effects (core/set-order-history-page-size state "100")]
        (is (= [[:effects/save-many [[[:account-info :order-history :page-size] 100]
                                     [[:account-info :order-history :page] 1]
                                     [[:account-info :order-history :page-input] "1"]]]
                [:effects/local-storage-set "order-history-page-size" "100"]]
               effects))
        (let [invalid-effects (core/set-order-history-page-size state "13")]
          (is (= [[:effects/save-many [[[:account-info :order-history :page-size] 50]
                                       [[:account-info :order-history :page] 1]
                                       [[:account-info :order-history :page-input] "1"]]]
                  [:effects/local-storage-set "order-history-page-size" "50"]]
                 invalid-effects)))))))

(deftest order-history-pagination-set-page-clamps-and-syncs-input-test
  (let [state {:account-info {:order-history {:page 2
                                              :page-input "2"}}}
        within (core/set-order-history-page state 3 5)
        too-high (core/set-order-history-page state 99 5)
        too-low (core/set-order-history-page state -2 5)]
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 3]
                                 [[:account-info :order-history :page-input] "3"]]]]
           within))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 5]
                                 [[:account-info :order-history :page-input] "5"]]]]
           too-high))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           too-low))))

(deftest order-history-pagination-next-prev-and-input-apply-test
  (let [state {:account-info {:order-history {:page 2
                                              :page-input "2"}}}
        next-effects (core/next-order-history-page state 3)
        prev-effects (core/prev-order-history-page state 3)
        at-end-effects (core/next-order-history-page
                        {:account-info {:order-history {:page 3 :page-input "3"}}}
                        3)
        typed-state {:account-info {:order-history {:page 1 :page-input "12"}}}
        apply-effects (core/apply-order-history-page-input typed-state 4)
        invalid-apply-effects (core/apply-order-history-page-input
                               {:account-info {:order-history {:page 1 :page-input "abc"}}}
                               4)
        keydown-effects (core/handle-order-history-page-input-keydown typed-state "Enter" 4)
        keydown-nop (core/handle-order-history-page-input-keydown typed-state "Escape" 4)]
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 3]
                                 [[:account-info :order-history :page-input] "3"]]]]
           next-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           prev-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 3]
                                 [[:account-info :order-history :page-input] "3"]]]]
           at-end-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 4]
                                 [[:account-info :order-history :page-input] "4"]]]]
           apply-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           invalid-apply-effects))
    (is (= apply-effects keydown-effects))
    (is (= [] keydown-nop))))

(deftest restore-order-history-pagination-settings-uses-defaults-and-stored-size-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "order-history-page-size" "100")
      (let [store (atom {:account-info {:order-history {:page-size 25
                                                        :page 4
                                                        :page-input "4"}}})]
        (core/restore-order-history-pagination-settings! store)
        (is (= {:page-size 100
                :page 1
                :page-input "1"}
               (select-keys (get-in @store [:account-info :order-history])
                            [:page-size :page :page-input]))))
      (.setItem js/localStorage "order-history-page-size" "13")
      (let [store (atom {:account-info {:order-history {}}})]
        (core/restore-order-history-pagination-settings! store)
        (is (= 50
               (get-in @store [:account-info :order-history :page-size])))))))

(deftest restore-active-asset-hydrates-cached-active-market-display-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "active-asset" "ETH")
      (.setItem js/localStorage
                "active-market-display"
                (js/JSON.stringify
                 (clj->js {:coin "ETH"
                           :symbol "ETH-USDC"
                           :base "ETH"
                           :quote "USDC"
                           :market-type "perp"
                           :dex "hyna"
                           :maxLeverage "25"})))
      (let [store (atom {:active-asset nil
                         :selected-asset nil
                         :active-market nil})]
        (with-redefs [ws-client/connected? (fn [] false)]
          (core/restore-active-asset! store))
        (is (= "ETH" (:active-asset @store)))
        (is (= "ETH-USDC" (get-in @store [:active-market :symbol])))
        (is (= :perp (get-in @store [:active-market :market-type])))
        (is (= 25 (get-in @store [:active-market :maxLeverage])))))))

(deftest restore-active-asset-ignores-mismatched-cached-market-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "active-asset" "ETH")
      (.setItem js/localStorage
                "active-market-display"
                (js/JSON.stringify (clj->js {:coin "BTC"
                                             :symbol "BTC-USDC"
                                             :market-type "perp"
                                             :maxLeverage 40})))
      (let [store (atom {:active-asset nil
                         :selected-asset nil
                         :active-market nil})]
        (with-redefs [ws-client/connected? (fn [] false)]
          (core/restore-active-asset! store))
        (is (= "ETH" (:active-asset @store)))
        (is (nil? (:active-market @store)))))))

(deftest subscribe-active-asset-persists-active-market-display-cache-test
  (with-test-local-storage
    (fn []
      (let [market {:key "perp:ETH"
                    :coin "ETH"
                    :symbol "ETH-USDC"
                    :base "ETH"
                    :quote "USDC"
                    :market-type :perp
                    :maxLeverage 25}
            store (atom {:asset-selector {:market-by-key {"perp:ETH" market}}
                         :chart-options {:selected-timeframe :1d}
                         :active-assets {:contexts {}
                                         :loading false}
                         :active-market nil})]
        (with-redefs [active-ctx/subscribe-active-asset-ctx! (fn [_] nil)
                      effect-adapters/fetch-candle-snapshot (fn [& _] nil)]
          (core/subscribe-active-asset nil store "ETH"))
        (let [cached (js->clj (js/JSON.parse (.getItem js/localStorage "active-market-display"))
                              :keywordize-keys true)]
          (is (= "ETH" (:coin cached)))
          (is (= "ETH-USDC" (:symbol cached)))
          (is (= "perp" (:market-type cached)))
          (is (= 25 (:maxLeverage cached))))))))

(deftest active-market-store-projection-persists-display-cache-test
  (with-test-local-storage
    (fn []
      (let [original-state @app-core/store
            market {:key "perp:ETH"
                    :coin "ETH"
                    :symbol "ETH-USDC"
                    :base "ETH"
                    :quote "USDC"
                    :market-type :perp
                    :dex "hyna"
                    :maxLeverage 25}]
        (try
          (swap! app-core/store assoc :active-market nil)
          (swap! app-core/store assoc :active-market market)
          (let [cached (js->clj (js/JSON.parse (.getItem js/localStorage "active-market-display"))
                                :keywordize-keys true)]
            (is (= "ETH" (:coin cached)))
            (is (= "ETH-USDC" (:symbol cached)))
            (is (= "perp" (:market-type cached)))
            (is (= 25 (:maxLeverage cached))))
          (finally
            (reset! app-core/store original-state)))))))

(deftest restore-asset-selector-markets-cache-hydrates-symbols-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage
                "asset-selector-markets-cache"
                (js/JSON.stringify
                 (clj->js [{:key "perp:ETH"
                            :coin "ETH"
                            :symbol "ETH-USDC"
                            :base "ETH"
                            :quote "USDC"
                            :market-type "perp"
                            :category "crypto"
                            :hip3? false
                            :maxLeverage "25"}
                           {:key "spot:PURR/USDC"
                            :coin "PURR/USDC"
                            :symbol "PURR/USDC"
                            :base "PURR"
                            :quote "USDC"
                            :market-type "spot"
                            :category "spot"
                            :hip3? false}])))
      (let [store (atom {:active-asset "ETH"
                         :active-market nil
                         :asset-selector {:markets []
                                          :market-by-key {}
                                          :phase :bootstrap}})]
        (core/restore-asset-selector-markets-cache! store)
        (is (= 2 (count (get-in @store [:asset-selector :markets]))))
        (is (= :perp (get-in @store [:asset-selector :market-by-key "perp:ETH" :market-type])))
        (is (= :spot (get-in @store [:asset-selector :market-by-key "spot:PURR/USDC" :market-type])))
        (is (= "ETH-USDC" (get-in @store [:asset-selector :market-by-key "perp:ETH" :symbol])))
        (is (= "ETH" (get-in @store [:active-market :coin])))
        (is (= 25 (get-in @store [:active-market :maxLeverage])))
        (is (= true (get-in @store [:asset-selector :cache-hydrated?])))))))

(deftest restore-asset-selector-markets-cache-keeps-existing-markets-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage
                "asset-selector-markets-cache"
                (js/JSON.stringify
                 (clj->js [{:key "perp:ETH"
                            :coin "ETH"
                            :symbol "ETH-USDC"
                            :base "ETH"
                            :market-type "perp"}])))
      (let [existing-market {:key "perp:BTC"
                             :coin "BTC"
                             :symbol "BTC-USDC"
                             :base "BTC"
                             :market-type :perp}
            store (atom {:asset-selector {:markets [existing-market]
                                          :market-by-key {"perp:BTC" existing-market}
                                          :phase :full}})]
        (core/restore-asset-selector-markets-cache! store)
        (is (= [existing-market]
               (get-in @store [:asset-selector :markets])))
        (is (= {"perp:BTC" existing-market}
               (get-in @store [:asset-selector :market-by-key])))))))

(deftest asset-selector-markets-store-projection-persists-cache-test
  (with-test-local-storage
    (fn []
      (let [original-state @app-core/store
            markets [{:key "perp:ETH"
                      :coin "ETH"
                      :symbol "ETH-USDC"
                      :base "ETH"
                      :quote "USDC"
                      :market-type :perp
                      :category :crypto
                      :hip3? false
                      :mark 1900.1
                      :volume24h 1000}
                     {:key "spot:PURR/USDC"
                      :coin "PURR/USDC"
                      :symbol "PURR/USDC"
                      :base "PURR"
                      :quote "USDC"
                      :market-type :spot
                      :category :spot
                      :mark 0.21
                      :volume24h 2000}]]
        (try
          (swap! app-core/store assoc-in [:asset-selector :sort-by] :volume)
          (swap! app-core/store assoc-in [:asset-selector :sort-direction] :desc)
          (swap! app-core/store assoc-in [:asset-selector :markets] [])
          (swap! app-core/store assoc-in [:asset-selector :market-by-key] {})
          (swap! app-core/store assoc-in [:asset-selector :markets] markets)
          (let [cached (js->clj (js/JSON.parse (.getItem js/localStorage "asset-selector-markets-cache"))
                                :keywordize-keys true)]
            (is (= 2 (count cached)))
            (is (= "PURR/USDC" (:symbol (first cached))))
            (is (= "ETH-USDC" (:symbol (second cached))))
            (is (= "spot" (:market-type (first cached))))
            (is (= [0 1] (mapv :cache-order cached)))
            (is (nil? (:mark (first cached)))))
          (finally
            (reset! app-core/store original-state)))))))

(deftest refresh-order-history-emits-request-then-fetch-with-tab-aware-loading-test
  (let [selected-state {:account-info {:selected-tab :order-history
                                       :order-history {:request-id 5}}}
        background-state {:account-info {:selected-tab :balances
                                         :order-history {:request-id 5}}}
        selected-effects (core/refresh-order-history selected-state)
        background-effects (core/refresh-order-history background-state)]
    (is (= :effects/save-many (ffirst selected-effects)))
    (is (= [:effects/api-fetch-historical-orders 6]
           (second selected-effects)))
    (is (= true
           (-> selected-effects first second (nth 1) second)))
    (is (= false
           (-> background-effects first second (nth 1) second)))
    (is (= [:effects/api-fetch-historical-orders 6]
           (second background-effects)))))

(deftest select-asset-closes-dropdown-first-and-removes-duplicate-effects-test
  (let [market {:key :perp/BTC
                :coin "BTC"}
        effects (core/select-asset {:active-asset "ETH"
                                    :asset-selector {:visible-dropdown :asset-selector}
                                    :orderbook-ui {:price-aggregation-dropdown-visible? true
                                                   :size-unit-dropdown-visible? true}}
                                   market)]
    (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                                 [[:asset-selector :scroll-top] 0]
                                 [[:asset-selector :render-limit] 120]
                                 [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                 [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                 [[:active-market] market]
                                 [[:order-form-ui] {:price-input-focused? false}]]]
            [:effects/unsubscribe-active-asset "ETH"]
            [:effects/unsubscribe-orderbook "ETH"]
            [:effects/unsubscribe-trades "ETH"]
            [:effects/subscribe-active-asset "BTC"]
            [:effects/subscribe-orderbook "BTC"]
            [:effects/subscribe-trades "BTC"]]
           effects))
    (is (not-any? #(= (first %) :effects/fetch-candle-snapshot) effects))
    (is (not-any? #(and (= (first %) :effects/save)
                        (= (second %) [:asset-selector :visible-dropdown]))
                  effects))))

(deftest select-asset-without-current-asset-still-batches-immediate-ui-close-test
  (let [market {:key :perp/SOL
                :coin "SOL"}
        effects (core/select-asset {:active-asset nil} market)]
    (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                                 [[:asset-selector :scroll-top] 0]
                                 [[:asset-selector :render-limit] 120]
                                 [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                 [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                 [[:active-market] market]
                                 [[:order-form-ui] {:price-input-focused? false}]]]
            [:effects/subscribe-active-asset "SOL"]
            [:effects/subscribe-orderbook "SOL"]
            [:effects/subscribe-trades "SOL"]]
           effects))))

(deftest select-asset-resolves-legacy-spot-id-to-canonical-coin-test
  (let [resolved-market {:key "spot:@1"
                         :coin "@1"
                         :symbol "HYPE/USDC"
                         :market-type :spot}
        effects (core/select-asset {:active-asset "ETH"
                                    :asset-selector {:visible-dropdown :asset-selector
                                                     :market-by-key {"spot:@1" resolved-market}}
                                    :orderbook-ui {:price-aggregation-dropdown-visible? true
                                                   :size-unit-dropdown-visible? true}}
                                   "1")]
    (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                                 [[:asset-selector :scroll-top] 0]
                                 [[:asset-selector :render-limit] 120]
                                 [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                 [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                 [[:active-market] resolved-market]
                                 [[:order-form-ui] {:price-input-focused? false}]]]
            [:effects/unsubscribe-active-asset "ETH"]
            [:effects/unsubscribe-orderbook "ETH"]
            [:effects/unsubscribe-trades "ETH"]
            [:effects/subscribe-active-asset "@1"]
            [:effects/subscribe-orderbook "@1"]
            [:effects/subscribe-trades "@1"]]
           effects))
    (is (= :effects/save-many (ffirst effects)))
    (is (not-any? #(= (first %) :effects/fetch-candle-snapshot) effects))))

(deftest select-asset-resets-price-input-focus-lock-to-dynamic-state-test
  (let [market {:key :perp/BTC
                :coin "BTC"}
        order-form (assoc (trading/default-order-form)
                          :type :limit
                          :price "70155")
        effects (core/select-asset {:active-asset "ETH"
                                    :order-form order-form
                                    :order-form-ui {:price-input-focused? true}
                                    :asset-selector {:visible-dropdown :asset-selector}
                                    :orderbook-ui {:price-aggregation-dropdown-visible? true
                                                   :size-unit-dropdown-visible? true}}
                                   market)
        save-many-path-values (-> effects first second)
        saved-order-form (some (fn [[path value]]
                                 (when (= [:order-form] path)
                                   value))
                               save-many-path-values)
        saved-order-form-ui (some (fn [[path value]]
                                    (when (= [:order-form-ui] path)
                                      value))
                                  save-many-path-values)]
    (is (= :effects/save-many (ffirst effects)))
    (is (some? saved-order-form))
    (is (some? saved-order-form-ui))
    (is (= false (:price-input-focused? saved-order-form-ui)))
    (is (= "" (:price saved-order-form)))))

(deftest websocket-diagnostics-ui-actions-emit-deterministic-effects-test
  (is (= [[:effects/save-many [[[:websocket-ui :diagnostics-open?] true]
                               [[:websocket-ui :reveal-sensitive?] false]
                               [[:websocket-ui :copy-status] nil]]]
          [:effects/refresh-websocket-health]]
         (core/toggle-ws-diagnostics {:websocket-ui {:diagnostics-open? false}})))
  (is (= [[:effects/save-many [[[:websocket-ui :diagnostics-open?] false]
                               [[:websocket-ui :reveal-sensitive?] false]
                               [[:websocket-ui :copy-status] nil]]]]
         (core/toggle-ws-diagnostics {:websocket-ui {:diagnostics-open? true}})))
  (is (= [[:effects/save-many [[[:websocket-ui :diagnostics-open?] false]
                               [[:websocket-ui :reveal-sensitive?] false]
                               [[:websocket-ui :copy-status] nil]]]]
         (core/close-ws-diagnostics {})))
  (is (= [[:effects/save [:websocket-ui :reveal-sensitive?] false]]
         (core/toggle-ws-diagnostics-sensitive {:websocket-ui {:reveal-sensitive? true}})))
  (is (= [[:effects/confirm-ws-diagnostics-reveal]]
         (core/toggle-ws-diagnostics-sensitive {:websocket-ui {:reveal-sensitive? false}})))
  (is (= [[:effects/save-many [[[:websocket-ui :diagnostics-open?] false]
                               [[:websocket-ui :reveal-sensitive?] false]
                               [[:websocket-ui :copy-status] nil]]]
          [:effects/save [:websocket-ui :reconnect-cooldown-until-ms] 7000]
          [:effects/reconnect-websocket]]
         (core/ws-diagnostics-reconnect-now {:websocket-ui {:diagnostics-open? true}
                                             :websocket {:health {:generated-at-ms 2000
                                                                  :transport {:state :connected}}}})))
  (is (= [[:effects/copy-websocket-diagnostics]]
         (core/ws-diagnostics-copy {})))
  (is (= [[:effects/save [:websocket-ui :show-surface-freshness-cues?] true]]
         (core/set-show-surface-freshness-cues {} true)))
  (is (= [[:effects/save [:websocket-ui :show-surface-freshness-cues?] false]]
         (core/set-show-surface-freshness-cues {} nil)))
  (is (= [[:effects/save [:websocket-ui :show-surface-freshness-cues?] true]]
         (core/toggle-show-surface-freshness-cues
           {:websocket-ui {:show-surface-freshness-cues? false}})))
  (is (= [[:effects/save [:websocket-ui :show-surface-freshness-cues?] false]]
         (core/toggle-show-surface-freshness-cues
           {:websocket-ui {:show-surface-freshness-cues? true}})))
  (is (= [[:effects/ws-reset-subscriptions {:group :market_data :source :manual}]]
         (core/ws-diagnostics-reset-market-subscriptions
           {:websocket-ui {:reset-in-progress? false}
            :websocket {:health {:generated-at-ms 2000
                                 :transport {:state :connected}}}})))
  (is (= [[:effects/ws-reset-subscriptions {:group :orders_oms :source :manual}]]
         (core/ws-diagnostics-reset-orders-subscriptions
           {:websocket-ui {:reset-in-progress? false}
            :websocket {:health {:generated-at-ms 2000
                                 :transport {:state :connected}}}})))
  (is (= [[:effects/ws-reset-subscriptions {:group :all :source :manual}]]
         (core/ws-diagnostics-reset-all-subscriptions
           {:websocket-ui {:reset-in-progress? false}
            :websocket {:health {:generated-at-ms 2000
                                 :transport {:state :connected}}}}))))

(deftest websocket-diagnostics-reconnect-guard-prevents-duplicate-reconnect-test
  (is (= []
         (core/ws-diagnostics-reconnect-now {:websocket-ui {:reconnect-cooldown-until-ms 9000}
                                             :websocket {:health {:generated-at-ms 5000
                                                                  :transport {:state :connected}}}})))
  (is (= []
         (core/ws-diagnostics-reconnect-now {:websocket-ui {:reconnect-cooldown-until-ms nil}
                                             :websocket {:health {:generated-at-ms 5000
                                                                  :transport {:state :reconnecting}}}}))))

(deftest websocket-diagnostics-reset-guard-prevents-duplicate-or-unsafe-reset-test
  (is (= []
         (core/ws-diagnostics-reset-market-subscriptions
           {:websocket-ui {:reset-in-progress? true}
            :websocket {:health {:generated-at-ms 5000
                                 :transport {:state :connected}}}})))
  (is (= []
         (core/ws-diagnostics-reset-orders-subscriptions
           {:websocket-ui {:reset-cooldown-until-ms 9000}
            :websocket {:health {:generated-at-ms 5000
                                 :transport {:state :connected}}}})))
  (is (= []
         (core/ws-diagnostics-reset-all-subscriptions
           {:websocket-ui {:reset-cooldown-until-ms nil}
            :websocket {:health {:generated-at-ms 5000
                                 :transport {:state :reconnecting}}}}))))

(deftest copy-wallet-address-action-emits-copy-effect-when-address-present-test
  (is (= [[:effects/copy-wallet-address "0xabc"]]
         (core/copy-wallet-address-action {:wallet {:address "0xabc"}})))
  (is (= [[:effects/copy-wallet-address nil]]
         (core/copy-wallet-address-action {:wallet {:address nil}}))))

(deftest disconnect-wallet-action-emits-disconnect-effect-test
  (is (= [[:effects/disconnect-wallet]]
         (core/disconnect-wallet-action {}))))

(deftest should-auto-enable-agent-trading-predicate-guards-connect-state-test
  (let [predicate @#'hyperopen.core.compat/should-auto-enable-agent-trading?]
    (is (true? (predicate {:wallet {:connected? true
                                    :address "0xAbC"
                                    :agent {:status :not-ready}}}
                          "0xabc")))
    (is (false? (predicate {:wallet {:connected? false
                                     :address "0xabc"
                                     :agent {:status :not-ready}}}
                           "0xabc")))
    (is (false? (predicate {:wallet {:connected? true
                                     :address "0xabc"
                                     :agent {:status :ready}}}
                           "0xabc")))
    (is (false? (predicate {:wallet {:connected? true
                                     :address "0xabc"
                                     :agent {:status :not-ready}}}
                           "0xdef")))))

(deftest handle-wallet-connected-dispatches-enable-agent-trading-when-eligible-test
  (let [store (atom {:wallet {:connected? true
                              :address "0xabc"
                              :agent {:status :not-ready}}})
        dispatched (atom [])
        original-queue-microtask js/queueMicrotask]
    (set! js/queueMicrotask (fn [f] (f)))
    (try
      (with-redefs [nxr/dispatch (fn [_ _ actions]
                                   (swap! dispatched conj actions))]
        (core/handle-wallet-connected store "0xabc")
        (is (= [[[:actions/enable-agent-trading]]]
               @dispatched)))
      (finally
        (set! js/queueMicrotask original-queue-microtask)))))

(deftest handle-wallet-connected-skips-dispatch-when-not-eligible-test
  (let [store (atom {:wallet {:connected? true
                              :address "0xabc"
                              :agent {:status :ready}}})
        dispatched (atom [])
        original-queue-microtask js/queueMicrotask]
    (set! js/queueMicrotask (fn [f] (f)))
    (try
      (with-redefs [nxr/dispatch (fn [_ _ actions]
                                   (swap! dispatched conj actions))]
        (core/handle-wallet-connected store "0xabc")
        (is (= [] @dispatched)))
      (finally
        (set! js/queueMicrotask original-queue-microtask)))))

(deftest copy-wallet-address-effect-writes-to-clipboard-when-available-test
  (async done
    (let [written (atom nil)
          navigator-prop "navigator"
          original-navigator-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis navigator-prop)
          fake-clipboard #js {:writeText (fn [payload]
                                           (reset! written payload)
                                           (js/Promise.resolve true))}
          fake-navigator #js {:clipboard fake-clipboard}
          store (atom {:wallet {:copy-feedback nil}})]
      (clear-wallet-copy-feedback-timeout!)
      (js/Object.defineProperty js/globalThis navigator-prop
                                #js {:value fake-navigator
                                     :configurable true})
      (core/copy-wallet-address nil store "0xabc")
      (js/setTimeout
       (fn []
         (try
           (is (= "0xabc" @written))
           (is (= :success (get-in @store [:wallet :copy-feedback :kind])))
           (is (= "Address copied to clipboard"
                  (get-in @store [:wallet :copy-feedback :message])))
           (finally
             (clear-wallet-copy-feedback-timeout!)
             (if original-navigator-descriptor
               (js/Object.defineProperty js/globalThis navigator-prop original-navigator-descriptor)
               (js/Reflect.deleteProperty js/globalThis navigator-prop))
             (done))))
       0))))

(deftest copy-wallet-address-effect-shows-error-feedback-when-clipboard-unavailable-test
  (let [navigator-prop "navigator"
        original-navigator-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis navigator-prop)
        store (atom {:wallet {:copy-feedback nil}})]
    (clear-wallet-copy-feedback-timeout!)
    (js/Object.defineProperty js/globalThis navigator-prop
                              #js {:value #js {}
                                   :configurable true})
    (try
      (core/copy-wallet-address nil store "0xabc")
      (is (= :error (get-in @store [:wallet :copy-feedback :kind])))
      (is (= "Clipboard unavailable"
             (get-in @store [:wallet :copy-feedback :message])))
      (finally
        (clear-wallet-copy-feedback-timeout!)
        (if original-navigator-descriptor
          (js/Object.defineProperty js/globalThis navigator-prop original-navigator-descriptor)
          (js/Reflect.deleteProperty js/globalThis navigator-prop))))))

(deftest api-submit-order-effect-shows-success-toast-and-refreshes-history-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :order-form {:submitting? false
                                    :error "old-error"}
                       :ui {:toast nil}})
          dispatched (atom [])
          original-submit-order trading-api/submit-order!
          original-dispatch nxr/dispatch]
      (clear-order-feedback-toast-timeout!)
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"})))
      (set! nxr/dispatch
            (fn [_store _evt actions]
              (swap! dispatched conj actions)))
      (core/api-submit-order nil store {:action {:type "order"
                                                 :orders []
                                                 :grouping "na"}})
      (js/setTimeout
       (fn []
         (try
           (is (false? (get-in @store [:order-form :submitting?])))
           (is (nil? (get-in @store [:order-form :error])))
           (is (= :success (get-in @store [:ui :toast :kind])))
           (is (= "Order submitted."
                  (get-in @store [:ui :toast :message])))
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (finally
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (done))))
       0))))

(deftest api-cancel-order-effect-shows-success-toast-and-refreshes-open-orders-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :orders {:open-orders [{:order {:coin "BTC" :oid 22}}]
                                :open-orders-snapshot []
                                :open-orders-snapshot-by-dex {}}
                       :ui {:toast nil}})
          dispatched (atom [])
          refresh-calls (atom [])
          original-cancel-order trading-api/cancel-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
          original-ensure-perp-dexs-data api/ensure-perp-dexs-data!]
      (clear-order-feedback-toast-timeout!)
      (set! trading-api/cancel-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"
                                   :response {:type "cancel"
                                              :data {:statuses ["success"]}}})))
      (set! nxr/dispatch
            (fn [_store _evt actions]
              (swap! dispatched conj actions)))
      (set! api/request-frontend-open-orders!
            (fn request-frontend-open-orders-mock
              ([address]
               (request-frontend-open-orders-mock address {}))
              ([address opts]
               (request-frontend-open-orders-mock address (:dex opts) (dissoc opts :dex)))
              ([address dex opts]
               (swap! refresh-calls conj [address dex opts])
               (js/Promise.resolve []))))
      (set! api/ensure-perp-dexs-data!
            (fn ensure-perp-dexs-data-mock
              ([_store]
               (ensure-perp-dexs-data-mock nil {}))
              ([_store _opts]
               (js/Promise.resolve ["dex-a"]))))
      (core/api-cancel-order nil store {:action {:type "cancel"
                                                 :cancels [{:a 0 :o 22}]}})
      (js/setTimeout
       (fn []
         (try
           (is (nil? (get-in @store [:orders :cancel-error])))
           (is (= []
                  (get-in @store [:orders :open-orders])))
           (is (= :success
                  (get-in @store [:ui :toast :kind])))
           (is (= "Order canceled."
                  (get-in @store [:ui :toast :message])))
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (is (= 2 (count @refresh-calls)))
           (finally
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/cancel-order! original-cancel-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
             (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
             (done))))
       0))))

(deftest ws-reset-subscriptions-effect-targets-group-and-avoids-duplicates-test
  (let [address "0x1234567890abcdef1234567890abcdef12345678"
        sends (atom [])
        live-health {:generated-at-ms 1700000000000
                     :transport {:state :connected
                                 :freshness :live}
                     :streams {["trades" "BTC" nil nil nil]
                               {:group :market_data
                                :topic "trades"
                                :subscribed? true
                                :descriptor {:type "trades" :coin "BTC"}}
                               ["dup-trades" "BTC" nil nil nil]
                               {:group :market_data
                                :topic "trades"
                                :subscribed? true
                                :descriptor {:type "trades" :coin "BTC"}}
                               ["openOrders" nil address nil nil]
                               {:group :orders_oms
                                :topic "openOrders"
                                :subscribed? true
                                :descriptor {:type "openOrders" :user address}}}}
        store (atom {:websocket {:health {:generated-at-ms 1
                                          :transport {:state :connected}
                                          :streams {}}}
                     :websocket-ui {:reset-in-progress? false
                                    :reset-cooldown-until-ms nil
                                    :reset-counts {:market_data 0 :orders_oms 0 :all 0}
                                    :diagnostics-timeline []}})]
    (with-redefs [ws-client/get-health-snapshot (fn [] live-health)
                  ws-client/send-message! (fn [payload]
                                            (swap! sends conj payload)
                                            true)]
      (core/ws-reset-subscriptions nil store {:group :market_data :source :manual})
      (is (= [{:method "unsubscribe" :subscription {:type "trades" :coin "BTC"}}
              {:method "subscribe" :subscription {:type "trades" :coin "BTC"}}]
             @sends))
      (is (false? (get-in @store [:websocket-ui :reset-in-progress?])))
      (is (= 1 (get-in @store [:websocket-ui :reset-counts :market_data])))
      (is (number? (get-in @store [:websocket-ui :reset-cooldown-until-ms])))
      (is (= :reset-market
             (get-in @store [:websocket-ui :diagnostics-timeline 0 :event]))))))

(deftest ws-reset-subscriptions-effect-uses-current-snapshot-and-noops-when-unsafe-test
  (let [sends (atom [])
        stale-health {:generated-at-ms 1000
                      :transport {:state :connected}
                      :streams {["trades" "ETH" nil nil nil]
                                {:group :market_data
                                 :topic "trades"
                                 :subscribed? true
                                 :descriptor {:type "trades" :coin "ETH"}}}}
        reconnecting-health {:generated-at-ms 2000
                             :transport {:state :reconnecting}
                             :streams {["trades" "BTC" nil nil nil]
                                       {:group :market_data
                                        :topic "trades"
                                        :subscribed? true
                                        :descriptor {:type "trades" :coin "BTC"}}}}
        store (atom {:websocket {:health stale-health}
                     :websocket-ui {:reset-in-progress? false
                                    :reset-cooldown-until-ms nil
                                    :reset-counts {:market_data 0 :orders_oms 0 :all 0}
                                    :diagnostics-timeline []}})]
    (with-redefs [ws-client/get-health-snapshot (fn [] reconnecting-health)
                  ws-client/send-message! (fn [payload]
                                            (swap! sends conj payload)
                                            true)]
      (core/ws-reset-subscriptions nil store {:group :market_data :source :manual})
      (is (empty? @sends))
      (is (= 0 (get-in @store [:websocket-ui :reset-counts :market_data]))))))

(deftest copy-websocket-diagnostics-redacts-sensitive-fields-test
  (async done
    (let [address "0x1234567890abcdef1234567890abcdef12345678"
          written (atom nil)
          navigator-prop "navigator"
          original-navigator-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis navigator-prop)
          fake-clipboard #js {:writeText (fn [payload]
                                           (reset! written payload)
                                           (js/Promise.resolve true))}
          fake-navigator #js {:clipboard fake-clipboard}
          health {:generated-at-ms 1700000000000
                  :transport {:state :connected
                              :freshness :live
                              :last-close {:code 1000
                                           :reason "ok"}}
                  :groups {:orders_oms {:worst-status :offline}
                           :market_data {:worst-status :idle}
                           :account {:worst-status :n-a}}
                  :streams {["openOrders" nil address nil nil]
                            {:group :orders_oms
                             :topic "openOrders"
                             :status :offline
                             :last-payload-at-ms 1699999999000
                             :stale-threshold-ms nil
                             :descriptor {:type "openOrders"
                                          :user address
                                          :token "secret-token"
                                          :meta {:authorization "Bearer token"
                                                 :entries [{:address address}]}}}}}
          store (atom {:websocket {:health health}
                       :websocket-ui {:copy-status nil}})]
      (js/Object.defineProperty js/globalThis navigator-prop
                                #js {:value fake-navigator
                                     :configurable true})
      (core/copy-websocket-diagnostics nil store)
      (js/setTimeout
        (fn []
          (try
            (is (string? @written))
            (is (not (str/includes? @written address)))
            (is (str/includes? @written "<redacted>"))
            (is (= :success (get-in @store [:websocket-ui :copy-status :kind])))
            (is (= "Copied (redacted)"
                   (get-in @store [:websocket-ui :copy-status :message])))
            (let [decoded (js->clj (js/JSON.parse @written) :keywordize-keys true)]
              (is (= runtime-state/app-version (get-in decoded [:app :version])))
              (is (map? (:counters decoded)))
              (is (= "<redacted>" (get-in decoded [:streams 0 :descriptor :user])))
              (is (= "<redacted>" (get-in decoded [:streams 0 :descriptor :token])))
              (is (= "<redacted>" (get-in decoded [:streams 0 :descriptor :meta :authorization])))
              (is (= "<redacted>" (get-in decoded [:streams 0 :descriptor :meta :entries 0 :address]))))
            (finally
              (if original-navigator-descriptor
                (js/Object.defineProperty js/globalThis navigator-prop original-navigator-descriptor)
                (js/Reflect.deleteProperty js/globalThis navigator-prop))
              (done))))
        0))))

(deftest copy-websocket-diagnostics-fallback-status-when-clipboard-unavailable-test
  (let [navigator-prop "navigator"
        original-navigator-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis navigator-prop)
        store (atom {:websocket {:health {:generated-at-ms 1700000000000
                                          :transport {:state :connected
                                                      :freshness :live}
                                          :groups {:orders_oms {:worst-status :idle}
                                                   :market_data {:worst-status :live}
                                                   :account {:worst-status :n-a}}
                                          :streams {["openOrders" nil "0x1234567890abcdef1234567890abcdef12345678" nil nil]
                                                    {:group :orders_oms
                                                     :topic "openOrders"
                                                     :status :n-a
                                                     :descriptor {:type "openOrders"
                                                                  :user "0x1234567890abcdef1234567890abcdef12345678"}}}}}
                     :websocket-ui {:copy-status nil}})]
    (js/Object.defineProperty js/globalThis navigator-prop
                              #js {:value #js {}
                                   :configurable true})
    (try
      (core/copy-websocket-diagnostics nil store)
      (let [status (get-in @store [:websocket-ui :copy-status])]
        (is (= :error (:kind status)))
        (is (str/includes? (:message status) "Couldn't access clipboard"))
        (is (string? (:fallback-json status)))
        (is (str/includes? (:fallback-json status) "<redacted>")))
      (finally
        (if original-navigator-descriptor
          (js/Object.defineProperty js/globalThis navigator-prop original-navigator-descriptor)
          (js/Reflect.deleteProperty js/globalThis navigator-prop))))))

(deftest sync-websocket-health-fingerprint-updates-and-skips-now-only-churn-test
  (async done
    (let [original-connection @ws-client/connection-state
          original-runtime @ws-client/stream-runtime
          store (atom {:websocket {:health {}}
                       :websocket-ui {:diagnostics-open? false}})]
      (reset! ws-client/connection-state
              {:status :connected
               :attempt 0
               :next-retry-at-ms nil
               :last-close nil
               :last-activity-at-ms 100
               :now-ms 1000
               :online? true
               :transport/state :connected
               :transport/last-recv-at-ms 900
               :transport/connected-at-ms 900
               :transport/expected-traffic? false
               :transport/freshness :live
               :queue-size 0
               :ws nil})
      (reset! ws-client/stream-runtime
              {:tier-depth {:market 0 :lossless 0}
               :metrics {:market-coalesced 0
                         :market-dispatched 0
                         :lossless-dispatched 0
                         :ingress-parse-errors 0}
               :now-ms 1000
               :streams {}
               :transport {:state :connected
                           :online? true
                           :last-recv-at-ms 900
                           :connected-at-ms 900
                           :expected-traffic? false
                           :freshness :live
                           :attempt 0
                           :last-close nil}
               :market-coalesce {:pending {}
                                 :timer nil}})
      (swap! runtime-state/runtime
             (fn [state]
               (-> state
                   (assoc-in [:websocket-health :fingerprint] nil)
                   (assoc-in [:websocket-health :writes] 0))))
      (core/sync-websocket-health! store :force? true)
      (js/setTimeout
        (fn []
          (is (= 1000 (get-in @store [:websocket :health :generated-at-ms])))
          (is (= 1 (get-in @runtime-state/runtime [:websocket-health :writes])))
          (swap! ws-client/stream-runtime assoc :now-ms 2000)
          (core/sync-websocket-health! store)
          (js/setTimeout
            (fn []
              (is (= 1000 (get-in @store [:websocket :health :generated-at-ms])))
              (is (= 1 (get-in @runtime-state/runtime [:websocket-health :writes])))
              (swap! ws-client/connection-state assoc :transport/freshness :delayed)
              (core/sync-websocket-health! store)
              (js/setTimeout
                (fn []
                  (try
                    (is (= 2 (get-in @runtime-state/runtime [:websocket-health :writes])))
                    (finally
                      (reset! ws-client/connection-state original-connection)
                      (reset! ws-client/stream-runtime original-runtime)
                      (done))))
                0))
            0))
        0))))

(deftest sync-websocket-health-auto-recover-is-flagged-and-cooldown-protected-test
  (let [dispatches (atom [])
        flag-prop "ENABLE_WS_AUTO_RECOVER"
        original-flag-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis flag-prop)
        health {:generated-at-ms 1700000000000
                :transport {:state :connected
                            :freshness :live
                            :expected-traffic? true
                            :attempt 0}
                :groups {:orders_oms {:worst-status :idle}
                         :market_data {:worst-status :delayed}
                         :account {:worst-status :idle}}
                :streams {["l2Book" "BTC" nil nil nil]
                          {:group :market_data
                           :topic "l2Book"
                           :status :delayed
                           :subscribed? true
                           :last-payload-at-ms (- 1700000000000 45000)
                           :stale-threshold-ms 5000
                           :descriptor {:type "l2Book" :coin "BTC"}}}}
        store (atom {:websocket {:health {}}
                     :websocket-ui {:reset-in-progress? false
                                    :auto-recover-cooldown-until-ms nil
                                    :auto-recover-count 0
                                    :diagnostics-timeline []}})]
    (swap! runtime-state/runtime assoc-in [:websocket-health :fingerprint] nil)
    (js/Object.defineProperty js/globalThis flag-prop
                              #js {:value true
                                   :configurable true})
    (try
      (with-redefs [ws-client/get-health-snapshot (fn [] health)
                    nxr/dispatch (fn [_ _ effects]
                                   (swap! dispatches conj effects))]
        (core/sync-websocket-health! store)
        (core/sync-websocket-health! store)
        (is (= 1 (count @dispatches)))
        (is (= [[:actions/ws-diagnostics-reset-market-subscriptions :auto-recover]]
               (first @dispatches)))
        (is (= 1 (get-in @store [:websocket-ui :auto-recover-count])))
        (is (number? (get-in @store [:websocket-ui :auto-recover-cooldown-until-ms]))))
      (finally
        (if original-flag-descriptor
          (js/Object.defineProperty js/globalThis flag-prop original-flag-descriptor)
          (js/Reflect.deleteProperty js/globalThis flag-prop))))))

(deftest sync-websocket-health-auto-recover-skips-unsupported-states-test
  (let [dispatches (atom [])
        flag-prop "ENABLE_WS_AUTO_RECOVER"
        original-flag-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis flag-prop)
        offline-health {:generated-at-ms 1700000000000
                        :transport {:state :disconnected
                                    :freshness :offline
                                    :expected-traffic? true}
                        :groups {:orders_oms {:worst-status :idle}
                                 :market_data {:worst-status :offline}
                                 :account {:worst-status :idle}}
                        :streams {["l2Book" "BTC" nil nil nil]
                                  {:group :market_data
                                   :topic "l2Book"
                                   :status :delayed
                                   :subscribed? true
                                   :last-payload-at-ms (- 1700000000000 45000)
                                   :stale-threshold-ms 5000
                                   :descriptor {:type "l2Book" :coin "BTC"}}}}
        event-driven-health {:generated-at-ms 1700000001000
                             :transport {:state :connected
                                         :freshness :live
                                         :expected-traffic? false}
                             :groups {:orders_oms {:worst-status :n-a}
                                      :market_data {:worst-status :idle}
                                      :account {:worst-status :n-a}}
                             :streams {["openOrders" nil "0xabc" nil nil]
                                       {:group :orders_oms
                                        :topic "openOrders"
                                        :status :n-a
                                        :subscribed? true
                                        :last-payload-at-ms 1700000000500
                                        :stale-threshold-ms nil
                                        :descriptor {:type "openOrders" :user "0xabc"}}}}
        store (atom {:websocket {:health {}}
                     :websocket-ui {:reset-in-progress? false
                                    :auto-recover-cooldown-until-ms nil
                                    :auto-recover-count 0
                                    :diagnostics-timeline []}})]
    (swap! runtime-state/runtime assoc-in [:websocket-health :fingerprint] nil)
    (js/Object.defineProperty js/globalThis flag-prop
                              #js {:value true
                                   :configurable true})
    (try
      (with-redefs [nxr/dispatch (fn [_ _ effects]
                                   (swap! dispatches conj effects))]
        (with-redefs [ws-client/get-health-snapshot (fn [] offline-health)]
          (core/sync-websocket-health! store))
        (with-redefs [ws-client/get-health-snapshot (fn [] event-driven-health)]
          (core/sync-websocket-health! store)))
      (is (empty? @dispatches))
      (is (= 0 (get-in @store [:websocket-ui :auto-recover-count])))
      (finally
        (if original-flag-descriptor
          (js/Object.defineProperty js/globalThis flag-prop original-flag-descriptor)
          (js/Reflect.deleteProperty js/globalThis flag-prop))))))

(deftest toggle-timeframes-dropdown-opens-timeframes-and-closes-other-chart-menus-test
  (let [effects (core/toggle-timeframes-dropdown
                 {:chart-options {:timeframes-dropdown-visible false
                                  :chart-type-dropdown-visible true
                                  :indicators-dropdown-visible true}})]
    (is (= [[:effects/save-many [[[:chart-options :timeframes-dropdown-visible] true]
                                 [[:chart-options :chart-type-dropdown-visible] false]
                                 [[:chart-options :indicators-dropdown-visible] false]]]]
           effects))))

(deftest toggle-chart-type-dropdown-opens-chart-type-and-closes-other-chart-menus-test
  (let [effects (core/toggle-chart-type-dropdown
                 {:chart-options {:timeframes-dropdown-visible true
                                  :chart-type-dropdown-visible false
                                  :indicators-dropdown-visible true}})]
    (is (= [[:effects/save-many [[[:chart-options :timeframes-dropdown-visible] false]
                                 [[:chart-options :chart-type-dropdown-visible] true]
                                 [[:chart-options :indicators-dropdown-visible] false]]]]
           effects))))

(deftest toggle-indicators-dropdown-opens-indicators-and-closes-other-chart-menus-test
  (let [effects (core/toggle-indicators-dropdown
                 {:chart-options {:timeframes-dropdown-visible true
                                  :chart-type-dropdown-visible true
                                  :indicators-dropdown-visible false}})]
    (is (= [[:effects/save-many [[[:chart-options :indicators-search-term] ""]
                                 [[:chart-options :timeframes-dropdown-visible] false]
                                 [[:chart-options :chart-type-dropdown-visible] false]
                                 [[:chart-options :indicators-dropdown-visible] true]]]]
           effects))))

(deftest update-indicators-search-saves-string-value-test
  (let [effects (core/update-indicators-search {} "sma")]
    (is (= [[:effects/save [:chart-options :indicators-search-term] "sma"]]
           effects))))

(deftest toggle-open-indicators-dropdown-clears-search-and-closes-all-chart-menus-test
  (let [effects (core/toggle-indicators-dropdown
                 {:chart-options {:timeframes-dropdown-visible true
                                  :chart-type-dropdown-visible false
                                  :indicators-dropdown-visible true
                                  :indicators-search-term "moving"}})]
    (is (= [[:effects/save-many [[[:chart-options :indicators-search-term] ""]
                                 [[:chart-options :timeframes-dropdown-visible] false]
                                 [[:chart-options :chart-type-dropdown-visible] false]
                                 [[:chart-options :indicators-dropdown-visible] false]]]]
           effects))))

(deftest toggle-open-chart-menu-closes-all-chart-menus-test
  (let [effects (core/toggle-timeframes-dropdown
                 {:chart-options {:timeframes-dropdown-visible true
                                  :chart-type-dropdown-visible false
                                  :indicators-dropdown-visible false}})]
    (is (= [[:effects/save-many [[[:chart-options :timeframes-dropdown-visible] false]
                                 [[:chart-options :chart-type-dropdown-visible] false]
                                 [[:chart-options :indicators-dropdown-visible] false]]]]
           effects))))

(deftest select-chart-timeframe-emits-batched-projection-before-single-fetch-test
  (with-test-local-storage
    (fn []
      (let [effects (core/select-chart-timeframe
                     {:chart-options {:timeframes-dropdown-visible true
                                      :chart-type-dropdown-visible true
                                      :indicators-dropdown-visible true}}
                     :5m)]
        (is (= [[:effects/save-many [[[:chart-options :selected-timeframe] :5m]
                                     [[:chart-options :timeframes-dropdown-visible] false]
                                     [[:chart-options :chart-type-dropdown-visible] false]
                                     [[:chart-options :indicators-dropdown-visible] false]]]
                [:effects/local-storage-set "chart-timeframe" "5m"]
                [:effects/fetch-candle-snapshot :interval :5m]]
               effects))
        (is (= 1 (count (filter #(= :effects/fetch-candle-snapshot (first %)) effects))))
        (is (= :effects/save-many (ffirst effects)))
        (is (= :effects/fetch-candle-snapshot (first (last effects))))))))

(deftest select-chart-type-emits-single-batched-projection-and-no-network-effects-test
  (with-test-local-storage
    (fn []
      (let [effects (core/select-chart-type
                     {:chart-options {:timeframes-dropdown-visible true
                                      :chart-type-dropdown-visible true
                                      :indicators-dropdown-visible true}}
                     :line)]
        (is (= [[:effects/save-many [[[:chart-options :selected-chart-type] :line]
                                     [[:chart-options :timeframes-dropdown-visible] false]
                                     [[:chart-options :chart-type-dropdown-visible] false]
                                     [[:chart-options :indicators-dropdown-visible] false]]]
                [:effects/local-storage-set "chart-type" "line"]]
               effects))
        (is (= 2 (count effects)))
        (is (not-any? #(= :effects/fetch-candle-snapshot (first %)) effects))
        (is (not-any? #(= :effects/subscribe-active-asset (first %)) effects))))))

(deftest local-storage-effect-interpreters-persist-string-and-json-values-test
  (with-test-local-storage
    (fn []
      (core/local-storage-set nil nil "sample-key" "sample-value")
      (is (= "sample-value" (.getItem js/localStorage "sample-key")))
      (core/local-storage-set-json nil nil "sample-json" {:a 1 :b "two"})
      (is (= {:a 1 :b "two"}
             (js->clj (js/JSON.parse (.getItem js/localStorage "sample-json"))
                      :keywordize-keys true))))))

(deftest select-order-entry-mode-market-emits-single-batched-projection-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :entry-mode :pro
                                  :type :stop-market)
               :order-form-ui {:pro-order-type-dropdown-open? true}}
        effects (core/select-order-entry-mode state :market)
        saved-form (extract-saved-order-form effects)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (map? saved-form))
    (is (= :market (:entry-mode saved-form)))
    (is (= :market (:type saved-form)))
    (is (= false (:pro-order-type-dropdown-open? saved-ui)))))

(deftest select-order-entry-mode-limit-forces-limit-type-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :type :stop-limit
                                  :entry-mode :pro)
               :order-form-ui {:pro-order-type-dropdown-open? true}}
        effects (core/select-order-entry-mode state :limit)
        saved-form (extract-saved-order-form effects)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (map? saved-form))
    (is (= :limit (:entry-mode saved-form)))
    (is (= :limit (:type saved-form)))
    (is (= false (:pro-order-type-dropdown-open? saved-ui)))))

(deftest select-order-entry-mode-pro-sets-pro-entry-and-normalized-pro-type-test
  (let [state {:order-form (assoc (trading/default-order-form) :type :limit)}
        effects (core/select-order-entry-mode state :pro)
        saved-form (extract-saved-order-form effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (map? saved-form))
    (is (= :pro (:entry-mode saved-form)))
    (is (= :stop-market (:type saved-form)))))

(deftest select-pro-order-type-closes-dropdown-and-persists-pro-selection-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :entry-mode :pro
                                  :type :stop-market)
               :order-form-ui {:pro-order-type-dropdown-open? true}}
        effects (core/select-pro-order-type state :scale)
        saved-form (extract-saved-order-form effects)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= :pro (:entry-mode saved-form)))
    (is (= :scale (:type saved-form)))
    (is (= false (:pro-order-type-dropdown-open? saved-ui)))))

(deftest toggle-pro-order-type-dropdown-flips-open-flag-test
  (let [closed-state {:order-form (trading/default-order-form)
                      :order-form-ui {:pro-order-type-dropdown-open? false}}
        open-state {:order-form (trading/default-order-form)
                    :order-form-ui {:pro-order-type-dropdown-open? true}}
        closed-effects (core/toggle-pro-order-type-dropdown closed-state)
        open-effects (core/toggle-pro-order-type-dropdown open-state)]
    (is (= [[:effects/save-many [[[:order-form-ui :pro-order-type-dropdown-open?] true]]]]
           closed-effects))
    (is (= [[:effects/save-many [[[:order-form-ui :pro-order-type-dropdown-open?] false]]]]
           open-effects))))

(deftest close-pro-order-type-dropdown-forces-open-flag-false-test
  (let [state {:order-form (trading/default-order-form)
               :order-form-ui {:pro-order-type-dropdown-open? true}}
        effects (core/close-pro-order-type-dropdown state)]
    (is (= [[:effects/save-many [[[:order-form-ui :pro-order-type-dropdown-open?] false]]]]
           effects))))

(deftest handle-pro-order-type-dropdown-keydown-closes-only-on-escape-test
  (let [state {:order-form (trading/default-order-form)
               :order-form-ui {:pro-order-type-dropdown-open? true}}
        escape-effects (core/handle-pro-order-type-dropdown-keydown state "Escape")
        enter-effects (core/handle-pro-order-type-dropdown-keydown state "Enter")]
    (is (= [[:effects/save-many [[[:order-form-ui :pro-order-type-dropdown-open?] false]]]]
           escape-effects))
    (is (= [] enter-effects))))

(deftest toggle-order-tpsl-panel-noops-for-scale-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :entry-mode :pro
                                  :type :scale)
               :order-form-ui {:tpsl-panel-open? false}}
        effects (core/toggle-order-tpsl-panel state)]
    (is (= [] effects))))

(deftest set-order-size-percent-emits-single-batched-projection-and-no-network-effects-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 100 :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                               :totalMarginUsed "250"}}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "100")}
        effects (core/set-order-size-percent state 25)
        saved-form (-> effects first second first second)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= 25 (:size-percent saved-form)))
    (is (not-any? #(= (first %) :effects/api-submit-order) effects))
    (is (not-any? #(= (first %) :effects/subscribe-orderbook) effects))))

(deftest set-order-size-display-preserves-user-entered-value-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 100 :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                               :totalMarginUsed "250"}}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "")}
        effects (core/set-order-size-display state "202")
        saved-form (-> effects first second first second)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= "202" (:size-display saved-form)))
    (is (= "2" (:size saved-form)))
    (is (not-any? #(= (first %) :effects/api-submit-order) effects))))

(deftest set-order-size-display-truncates-canonical-size-to-market-decimals-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 70179 :maxLeverage 40 :szDecimals 5}
               :orderbooks {"BTC" {:bids [{:px "70150"}]
                                   :asks [{:px "70160"}]}}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                               :totalMarginUsed "250"}}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "70179")}
        effects (core/set-order-size-display state "2")
        saved-form (-> effects first second first second)
        summary (trading/order-summary state saved-form)]
    (is (= "2" (:size-display saved-form)))
    (is (= "0.00002" (:size saved-form)))
    (is (<= (js/Math.abs (- 1.4 (:order-value summary))) 0.01))))

(deftest focus-order-price-input-locks-price-and-captures-current-fallback-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 70000 :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "70120"} {:px "70150"} {:px "70090"}]
                                   :asks [{:px "70240"} {:px "70160"} {:px "70210"}]}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "")
               :order-form-ui {:price-input-focused? false}}
        effects (core/focus-order-price-input state)
        saved-form (extract-saved-order-form effects)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= true (:price-input-focused? saved-ui)))
    (is (= "70155" (:price saved-form)))))

(deftest focus-order-price-input-does-not-overwrite-manual-price-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 70000 :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "70120"}]
                                   :asks [{:px "70160"}]}}
               :order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :price "70133.5")
               :order-form-ui {:price-input-focused? false}}
        effects (core/focus-order-price-input state)
        saved-form (extract-saved-order-form effects)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= true (:price-input-focused? saved-ui)))
    (is (= "70133.5" (:price saved-form)))))

(deftest blur-order-price-input-releases-focus-lock-without-mutating-price-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :price "70155")
               :order-form-ui {:price-input-focused? true}}
        effects (core/blur-order-price-input state)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= false (:price-input-focused? saved-ui)))))

(deftest set-order-price-to-mid-uses-best-bid-ask-midpoint-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 70000 :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "70120"} {:px "70150"} {:px "70090"}]
                                   :asks [{:px "70240"} {:px "70160"} {:px "70210"}]}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "")}
        effects (core/set-order-price-to-mid state)
        saved-form (-> effects first second first second)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= "70155" (:price saved-form)))
    (is (not-any? #(= (first %) :effects/api-submit-order) effects))))

(deftest submit-order-emits-single-api-submit-order-effect-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :market-type :perp}
               :asset-contexts {:BTC {:idx 0}}
               :wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :ready
                                :storage-mode :session
                                :agent-address "0xagent"}}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :side :buy
                                  :size "1"
                                  :price "100")}
        effects (core/submit-order state)
        api-submit-effects (filter #(= (first %) :effects/api-submit-order) effects)]
    (is (= 1 (count api-submit-effects)))))

(deftest submit-order-limit-with-blank-price-uses-fallback-and-emits-single-submit-effect-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :market-type :perp}
               :asset-contexts {:BTC {:idx 0}}
               :wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :ready
                                :storage-mode :session
                                :agent-address "0xagent"}}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :side :buy
                                  :size "1"
                                  :price "")}
        effects (core/submit-order state)
        api-submit-effects (filter #(= (first %) :effects/api-submit-order) effects)
        saved-form (some (fn [effect]
                           (when (and (= :effects/save (first effect))
                                      (= [:order-form] (second effect)))
                             (nth effect 2)))
                         effects)]
    (is (= 1 (count api-submit-effects)))
    (is (seq (:price saved-form)))))

(deftest submit-order-requires-agent-ready-session-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :market-type :perp}
               :asset-contexts {:BTC {:idx 0}}
               :wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :not-ready
                                :storage-mode :session}}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :side :buy
                                  :size "1"
                                  :price "100")}
        effects (core/submit-order state)]
    (is (not-any? #(= (first %) :effects/api-submit-order) effects))
    (is (= [[:effects/save [:order-form :error] "Enable trading before submitting orders."]]
           effects))))

(deftest cancel-order-requires-agent-ready-session-test
  (let [state {:wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :not-ready
                                :storage-mode :session}}
               :asset-contexts {:BTC {:idx 0}}}
        order {:coin "BTC"
               :oid 101}
        effects (core/cancel-order state order)]
    (is (= [[:effects/save [:orders :cancel-error] "Enable trading before cancelling orders."]]
           effects))))

(deftest cancel-order-ready-agent-emits-single-api-cancel-effect-test
  (let [state {:wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :ready
                                :storage-mode :session
                                :agent-address "0xagent"}}
               :asset-contexts {:BTC {:idx 0}}}
        order {:coin "BTC"
               :oid 202}
        effects (core/cancel-order state order)
        cancel-effects (filter #(= (first %) :effects/api-cancel-order) effects)]
    (is (= 1 (count cancel-effects)))))

(deftest cancel-order-falls-back-to-asset-selector-market-index-test
  (let [state {:wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :ready
                                :storage-mode :session
                                :agent-address "0xagent"}}
               :asset-contexts {}
               :asset-selector {:market-by-key {"perp:SOL" {:coin "SOL"
                                                            :idx 12}}}}
        order {:coin "SOL"
               :oid "307891000622"}
        effects (core/cancel-order state order)]
    (is (= [[:effects/api-cancel-order {:action {:type "cancel"
                                                 :cancels [{:a 12 :o 307891000622}]}}]]
           effects))))

(deftest prune-canceled-open-orders-removes-canceled-oid-across-all-sources-test
  (let [state {:orders {:open-orders [{:order {:coin "BTC" :oid 101}}
                                      {:order {:coin "ETH" :oid 102}}]
                        :open-orders-snapshot {:orders [{:order {:coin "BTC" :oid 101}}
                                                        {:order {:coin "SOL" :oid 103}}]}
                        :open-orders-snapshot-by-dex {"dex-a" [{:order {:coin "BTC" :oid 101}}]
                                                      "dex-b" [{:order {:coin "XRP" :oid 104}}]}}}
        request {:action {:type "cancel"
                          :cancels [{:a 0 :o 101}]}}
        next-state (core/prune-canceled-open-orders state request)]
    (is (= #{102}
           (->> (get-in next-state [:orders :open-orders])
                (map #(get-in % [:order :oid]))
                set)))
    (is (= #{103}
           (->> (get-in next-state [:orders :open-orders-snapshot :orders])
                (map #(get-in % [:order :oid]))
                set)))
    (is (= []
           (get-in next-state [:orders :open-orders-snapshot-by-dex "dex-a"])))
    (is (= #{104}
           (->> (get-in next-state [:orders :open-orders-snapshot-by-dex "dex-b"])
                (map #(get-in % [:order :oid]))
                set)))))

(deftest enable-agent-trading-action-emits-approving-projection-before-effect-test
  (let [state {:wallet {:connected? true
                        :address "0xabc"
                        :agent {:storage-mode :session}}}
        effects (core/enable-agent-trading-action state)
        [save-effect io-effect] effects
        path-values (second save-effect)]
    (is (= :effects/save-many (first save-effect)))
    (is (= [[:wallet :agent :status] :approving]
           (first path-values)))
    (is (= [[:wallet :agent :error] nil]
           (second path-values)))
    (is (= [:effects/enable-agent-trading {:storage-mode :session}] io-effect))))

(deftest enable-agent-trading-action-errors-when-wallet-is-not-connected-test
  (let [state {:wallet {:connected? false
                        :address nil
                        :agent {:storage-mode :session}}}
        effects (core/enable-agent-trading-action state)
        [save-effect] effects
        path-values (second save-effect)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (first save-effect)))
    (is (= [[:wallet :agent :status] :error]
           (first path-values)))
    (is (= [[:wallet :agent :error] "Connect your wallet before enabling trading."]
           (second path-values)))))

(deftest set-agent-storage-mode-action-emits-effect-when-mode-changes-test
  (let [state {:wallet {:agent {:storage-mode :session}}}
        effects (core/set-agent-storage-mode-action state :local)]
    (is (= [[:effects/set-agent-storage-mode :local]]
           effects))))

(deftest set-agent-storage-mode-action-noops-when-mode-is-unchanged-test
  (let [state {:wallet {:agent {:storage-mode :session}}}
        effects (core/set-agent-storage-mode-action state :session)]
    (is (= [] effects))))

(deftest set-agent-storage-mode-effect-clears-sessions-and-resets-agent-state-test
  (let [store (atom {:wallet {:connected? true
                              :address "0xabc"
                              :agent {:status :ready
                                      :storage-mode :session
                                      :agent-address "0xagent"
                                      :nonce-cursor 1700000001111}}})
        cleared (atom [])
        persisted-modes (atom [])]
    (with-redefs [agent-session/clear-agent-session-by-mode!
                  (fn [wallet-address storage-mode]
                    (swap! cleared conj [wallet-address storage-mode])
                    true)
                  agent-session/persist-storage-mode-preference!
                  (fn [storage-mode]
                    (swap! persisted-modes conj storage-mode)
                    true)]
      (core/set-agent-storage-mode nil store :local)
      (is (= [["0xabc" :session]
              ["0xabc" :local]]
             @cleared))
      (is (= [:local] @persisted-modes))
      (is (= :not-ready (get-in @store [:wallet :agent :status])))
      (is (= :local (get-in @store [:wallet :agent :storage-mode])))
      (is (str/includes? (str (get-in @store [:wallet :agent :error]))
                         "Enable Trading again.")))))

(deftest restore-agent-storage-mode-applies-preference-before-wallet-bootstrap-test
  (let [store (atom {:wallet {:agent {:storage-mode :session}}})]
    (with-redefs [agent-session/load-storage-mode-preference
                  (fn [] :local)]
      (core/restore-agent-storage-mode! store)
      (is (= :local
             (get-in @store [:wallet :agent :storage-mode]))))))

(deftest enable-agent-trading-effect-sets-ready-state-on-success-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :approving
                                        :storage-mode :session}}})
          persisted (atom nil)
          original-create agent-session/create-agent-credentials!
          original-build-action agent-session/build-approve-agent-action
          original-approve trading-api/approve-agent!
          original-persist agent-session/persist-agent-session-by-mode!]
      (set! agent-session/create-agent-credentials!
            (fn []
              {:private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
               :agent-address "0x9999999999999999999999999999999999999999"}))
      (set! agent-session/build-approve-agent-action
            (fn [agent-address nonce & _]
              {:type "approveAgent"
               :agentAddress agent-address
               :nonce nonce
               :hyperliquidChain "Mainnet"
               :signatureChainId "0x66eee"}))
      (set! trading-api/approve-agent!
            (fn [_ owner-address action]
              (is (= "0xabc" owner-address))
              (is (= "approveAgent" (:type action)))
              (js/Promise.resolve
               #js {:json (fn []
                            (js/Promise.resolve #js {:status "ok"}))})))
      (set! agent-session/persist-agent-session-by-mode!
            (fn [wallet-address storage-mode session]
              (reset! persisted [wallet-address storage-mode session])))
      (letfn [(restore! []
                (set! agent-session/create-agent-credentials! original-create)
                (set! agent-session/build-approve-agent-action original-build-action)
                (set! trading-api/approve-agent! original-approve)
                (set! agent-session/persist-agent-session-by-mode! original-persist))]
        (core/enable-agent-trading nil store {:storage-mode :session})
        (js/setTimeout
         (fn []
           (is (= :ready (get-in @store [:wallet :agent :status])))
           (is (= "0x9999999999999999999999999999999999999999"
                  (get-in @store [:wallet :agent :agent-address])))
           (is (number? (get-in @store [:wallet :agent :last-approved-at])))
           (is (nil? (get-in @store [:wallet :agent :private-key])))
           (is (= "0xabc" (first @persisted)))
           (is (= :session (second @persisted)))
           (restore!)
           (done))
         0)))))

(deftest enable-agent-trading-effect-sets-error-state-on-failure-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :approving
                                        :storage-mode :session}}})
          persisted (atom nil)
          original-create agent-session/create-agent-credentials!
          original-build-action agent-session/build-approve-agent-action
          original-approve trading-api/approve-agent!
          original-persist agent-session/persist-agent-session-by-mode!]
      (set! agent-session/create-agent-credentials!
            (fn []
              {:private-key "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
               :agent-address "0x8888888888888888888888888888888888888888"}))
      (set! agent-session/build-approve-agent-action
            (fn [agent-address nonce & _]
              {:type "approveAgent"
               :agentAddress agent-address
               :nonce nonce
               :hyperliquidChain "Mainnet"
               :signatureChainId "0x66eee"}))
      (set! trading-api/approve-agent!
            (fn [_ _ _]
              (js/Promise.resolve
               #js {:json (fn []
                            (js/Promise.resolve #js {:status "err"
                                                     :error "bad sig"}))})))
      (set! agent-session/persist-agent-session-by-mode!
            (fn [wallet-address storage-mode session]
              (reset! persisted [wallet-address storage-mode session])))
      (letfn [(restore! []
                (set! agent-session/create-agent-credentials! original-create)
                (set! agent-session/build-approve-agent-action original-build-action)
                (set! trading-api/approve-agent! original-approve)
                (set! agent-session/persist-agent-session-by-mode! original-persist))]
        (core/enable-agent-trading nil store {:storage-mode :session})
        (js/setTimeout
         (fn []
           (is (= :error (get-in @store [:wallet :agent :status])))
           (is (str/includes? (str (get-in @store [:wallet :agent :error]))
                              "bad sig"))
           (is (nil? @persisted))
           (restore!)
           (done))
         0)))))

(deftest enable-agent-trading-effect-sets-error-state-on-sync-exception-test
  (let [store (atom {:wallet {:address "0xabc"
                              :agent {:status :approving
                                      :storage-mode :session}}})
        original-create agent-session/create-agent-credentials!]
    (set! agent-session/create-agent-credentials!
          (fn []
            (throw (js/Error. "secure random unavailable"))))
    (try
      (core/enable-agent-trading nil store {:storage-mode :session})
      (is (= :error (get-in @store [:wallet :agent :status])))
      (is (str/includes? (str (get-in @store [:wallet :agent :error]))
                         "secure random unavailable"))
      (finally
        (set! agent-session/create-agent-credentials! original-create)))))
