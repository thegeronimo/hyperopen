(ns hyperopen.telemetry.console-preload-debug-api-test
  (:require [cljs.test :refer-macros [deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.validation :as runtime-validation]
            [hyperopen.system :as app-system]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.telemetry.console-preload :as console-preload]
            [hyperopen.views.account-info.vm :as account-info-vm]
            [hyperopen.views.trade.order-form-vm :as order-form-vm]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.client-compat :as ws-client-compat]
            [hyperopen.websocket.market-projection-runtime :as market-projection-runtime]))

(defn- has-own?
  [obj key]
  (.call (.-hasOwnProperty js/Object.prototype) obj key))

(deftest dispatch-many-accepts-clojure-sequences-and-normalizes-js-objects-test
  (let [store (atom {})
        dispatched (atom [])]
    (with-redefs [app-system/store store
                  nxr/dispatch (fn [_runtime-store _event actions]
                                 (reset! dispatched actions))]
      (let [api (@#'console-preload/debug-api)
            dispatch-many! (aget api "dispatchMany")
            config (doto #js {}
                     (aset "mode" ":fast"))]
        (dispatch-many! (list (list ":actions/stop-spectate-mode")
                              [":actions/toggle-asset-dropdown" config]))
        (is (= [[:actions/stop-spectate-mode]
                [:actions/toggle-asset-dropdown {:mode :fast}]]
               @dispatched))))))

(deftest selector-helpers-and-element-rect-api-cover-present-missing-and-invalid-selectors-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        api (@#'console-preload/debug-api)
        node (doto #js {}
               (aset "getBoundingClientRect"
                     (fn []
                       #js {:left 10
                            :right 30
                            :top 5
                            :bottom 25
                            :width 20
                            :height 20})))
        document (js-obj
                  "querySelectorAll"
                  (fn [selector]
                    (cond
                      (= selector "[data-parity-id=\"trade-submit-order-button\"]")
                      #js [node #js {}]

                      (= selector "[data-parity-id=\"missing\"]")
                      #js []

                      (= selector "broken[")
                      (throw (js/Error. "bad selector"))

                      :else
                      #js [])))]
    (try
      (set! (.-document js/globalThis) document)
      (let [info (js->clj ((aget api "elementRect") "trade-submit-order-button") :keywordize-keys true)
            missing (js->clj ((aget api "elementRect") "missing") :keywordize-keys true)
            selector-nodes @#'console-preload/selector-nodes
            node-rect @#'console-preload/node-rect]
        (is (= {:parity-id "trade-submit-order-button"
                :present true
                :count 2
                :rect {:left 10
                       :right 30
                       :top 5
                       :bottom 25
                       :width 20
                       :height 20}}
               info))
        (is (= {:parity-id "missing"
                :present false
                :count 0
                :rect nil}
               missing))
        (is (= [] (selector-nodes "broken[")))
        (is (nil? (node-rect #js {}))))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))))))

(deftest oracle-api-covers-funding-account-asset-position-and-effect-order-paths-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        store (atom {:active-asset "ETH"
                     :trade-ui {:mobile-surface :positions}
                     :account-info {:selected-tab :fills}
                     :asset-selector {:visible-dropdown :mobile
                                      :selected-market-key "ETH-PERP"
                                      :search-term "eth"}})
        api (@#'console-preload/debug-api)
        oracle! (aget api "oracle")
        current-funding-vm (atom {:modal {:open? true
                                          :title "Deposit ETH"}
                                  :content {:kind :deposit/review}
                                  :deposit {:selected-asset {:key :eth}}})
        document (js-obj
                  "querySelectorAll"
                  (fn [selector]
                    (cond
                      (= selector "[data-role='funding-modal']") #js [#js {}]
                      (= selector "[data-funding-mobile-sheet-surface='true']") #js [#js {}]
                      (= selector "[data-parity-id=\"trade-mobile-account-panel\"]") #js [#js {}]
                      (= selector "[data-parity-id=\"trade-mobile-surface-tabs\"]") #js []
                      (= selector "[data-parity-id=\"asset-selector-desktop\"]") #js [#js {}]
                      (= selector "[data-parity-id=\"asset-selector-mobile\"]") #js [#js {} #js {}]
                      (= selector "[data-position-margin-surface='true']") #js [#js {}]
                      (= selector "[data-role='position-margin-mobile-sheet-layer']") #js [#js {}]
                      :else #js [])))]
    (try
      (set! (.-document js/globalThis) document)
      (with-redefs [app-system/store store
                    funding-actions/funding-modal-view-model (fn [_] @current-funding-vm)
                    order-form-vm/order-form-vm (fn [_]
                                                  {:submit {}})
                    account-info-vm/account-info-vm (fn [_]
                                                      {:positions [{:coin "ETH"
                                                                    :size 1}]})
                    runtime-validation/debug-action-effect-traces-snapshot
                    (fn []
                      [{:action-id :actions/start-spectate-mode
                        :covered? true
                        :heavy-effect-count 1
                        :projection-effect-count 2
                        :projection-before-heavy true
                        :phase-order-valid true
                        :duplicate-heavy-effect-ids [:effects/dup]
                        :effect-ids [:effects/dup :effects/final]
                        :captured-at-ms 1234}])]
        (let [funding (js->clj (oracle! "funding-modal" #js {}) :keywordize-keys true)
              account-surface (js->clj (oracle! "account-surface" #js {}) :keywordize-keys true)
              asset-selector (js->clj (oracle! "asset-selector" #js {}) :keywordize-keys true)
              first-position (js->clj (oracle! "first-position" #js {}) :keywordize-keys true)
              position-overlay (js->clj (oracle! "position-overlay" #js {:surface "MARGIN"}) :keywordize-keys true)
              position-overlay-missing (js->clj (oracle! "position-overlay" #js {:surface "mystery"}) :keywordize-keys true)
              effect-order (js->clj (oracle! "effect-order" {:action-id ":actions/start-spectate-mode"}) :keywordize-keys true)
              missing-effect-order (js->clj (oracle! "effect-order" {:actionId ":actions/not-real"}) :keywordize-keys true)
              parity (js->clj (oracle! "PARITY-ELEMENT" #js {:parityId "asset-selector-mobile"}) :keywordize-keys true)]
          (is (= {:open true
                  :title "Deposit ETH"
                  :contentKind ":deposit/review"
                  :selectedDepositAssetKey "eth"
                  :modalCount 1
                  :mobileSheetCount 1
                  :presentationMode "mobile-sheet"}
                 funding))
          (is (= {:mobileSurface "positions"
                  :selectedTab "fills"
                  :mobileAccountPanelPresent true
                  :mobileSurfaceTabsPresent false}
                 account-surface))
          (is (= {:visibleDropdown "mobile"
                  :activeAsset "ETH"
                  :selectedMarketKey "ETH-PERP"
                  :searchTerm "eth"
                  :desktopPresent true
                  :mobilePresent true}
                 asset-selector))
          (is (= {:present true
                  :positionData {:coin "ETH"
                                 :size 1}}
                 first-position))
          (is (= {:surface "margin"
                  :open true
                  :surfaceCount 1
                  :mobileSheetLayerCount 1
                  :presentationMode "mobile-sheet"}
                 position-overlay))
          (is (= {:surface "mystery"
                  :open false
                  :surfaceCount 0
                  :mobileSheetLayerCount 0
                  :presentationMode "closed"}
                 position-overlay-missing))
          (is (= {:present true
                  :actionId ":actions/start-spectate-mode"
                  :covered true
                  :heavyEffectCount 1
                  :projectionEffectCount 2
                  :projectionBeforeHeavy true
                  :phaseOrderValid true
                  :duplicateHeavyEffectIds [":effects/dup"]
                  :effectIds [":effects/dup" ":effects/final"]
                  :capturedAtMs 1234}
                 effect-order))
          (is (= {:present false
                  :actionId ":actions/not-real"
                  :covered false
                  :heavyEffectCount 0
                  :projectionEffectCount 0
                  :projectionBeforeHeavy false
                  :phaseOrderValid false
                  :duplicateHeavyEffectIds []
                  :effectIds []}
                 missing-effect-order))
          (is (= {:parity-id "asset-selector-mobile"
                  :present true
                  :count 2
                  :rect nil}
                 parity))))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))))))

(deftest oracle-api-rejects-unknown-oracle-name-test
  (let [api (@#'console-preload/debug-api)
        oracle! (aget api "oracle")]
    (is (thrown-with-msg?
         js/Error
         #"Unknown QA oracle"
         (oracle! "not-real" #js {})))))

(deftest snapshot-and-download-api-cover-json-output-and-delegate-hooks-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        orig-url (.-URL js/globalThis)
        had-url? (has-own? js/globalThis "URL")
        orig-blob (.-Blob js/globalThis)
        had-blob? (has-own? js/globalThis "Blob")
        created-payloads (atom [])
        clicked-downloads (atom [])
        revoked-urls (atom [])
        clear-calls (atom [])
        append-count (atom 0)
        remove-count (atom 0)
        link (js-obj)
        store (atom {:router {:path "/trade"}
                     :active-asset "BTC"
                     :wallet {:connected? true
                              :address "0xabc"}})
        runtime (atom {:mode :debug})]
    (try
      (aset link "click" (fn []
                           (swap! clicked-downloads conj {:href (.-href link)
                                                          :download (.-download link)})))
      (aset link "remove" (fn []
                            (swap! remove-count inc)))
      (set! (.-Blob js/globalThis)
            (fn [parts opts]
              (js-obj "parts" parts
                      "opts" opts)))
      (set! (.-URL js/globalThis)
            (js-obj
             "createObjectURL" (fn [blob]
                                 (let [payload (aget (aget blob "parts") 0)
                                       index (count @created-payloads)
                                       url (str "blob://" index)]
                                   (swap! created-payloads conj payload)
                                   url))
             "revokeObjectURL" (fn [url]
                                 (swap! revoked-urls conj url))))
      (set! (.-document js/globalThis)
            (js-obj
             "querySelectorAll" (fn [_selector] #js [])
             "createElement" (fn [_tag] link)
             "body" (js-obj
                     "appendChild" (fn [el]
                                     (is (identical? link el))
                                     (swap! append-count inc)))))
      (with-redefs [platform/now-ms (fn [] 1700000000000)
                    app-system/store store
                    app-system/runtime runtime
                    funding-actions/funding-modal-view-model (fn [_]
                                                               {:modal {}
                                                                :content {}})
                    order-form-vm/order-form-vm (fn [_]
                                                  {:submit {}})
                    ws-client/runtime-view (atom {:view :ok})
                    ws-client/runtime-state (atom {:status :connected})
                    ws-client-compat/compat-projections (fn []
                                                          {:compat true})
                    market-projection-runtime/market-projection-telemetry-snapshot
                    (fn []
                      {:projection true})
                    ws-client/get-flight-recording (fn []
                                                     [{:kind :raw}])
                    ws-client/get-flight-recording-redacted (fn []
                                                              [{:kind :tick}
                                                               {:kind :fill}])
                    ws-client/replay-flight-recording (fn []
                                                        {:replayed 2})
                    ws-client/clear-flight-recording! (fn []
                                                        (swap! clear-calls conj :flight)
                                                        :cleared-flight)
                    runtime-validation/debug-action-effect-traces-snapshot
                    (fn []
                      [{:action-id :actions/start-spectate-mode}
                       {:action-id :actions/stop-spectate-mode}])
                    telemetry/events (fn []
                                       [{:event :ready}
                                        {:event :steady}])
                    telemetry/events-json (fn []
                                            "EVENTS_JSON")
                    telemetry/clear-events! (fn []
                                              (swap! clear-calls conj :events)
                                              :cleared-events)]
        (let [api (@#'console-preload/debug-api)
              snapshot (js->clj ((aget api "snapshot")) :keywordize-keys true)
              qa-snapshot (js->clj ((aget api "qaSnapshot")) :keywordize-keys true)
              snapshot-json (js->clj (js/JSON.parse ((aget api "snapshotJson"))) :keywordize-keys true)
              qa-snapshot-json (js->clj (js/JSON.parse ((aget api "qaSnapshotJson"))) :keywordize-keys true)
              flight-recording (js->clj ((aget api "flightRecording")) :keywordize-keys true)
              flight-recording-redacted (js->clj ((aget api "flightRecordingRedacted")) :keywordize-keys true)
              replayed (js->clj ((aget api "replayFlightRecording")) :keywordize-keys true)
              events (js->clj ((aget api "events")) :keywordize-keys true)
              downloaded-snapshot (atom nil)
              downloaded-flight (atom nil)]
          (is (= 1700000000000 (:captured-at-ms snapshot)))
          (is (= "/trade" (get-in snapshot [:app-state :router :path])))
          (is (= "BTC" (get-in snapshot [:app-state :active-asset])))
          (is (= true (get-in snapshot [:app-state :wallet :connected?])))
          (is (= "0xabc" (get-in snapshot [:app-state :wallet :address])))
          (is (= "debug" (get-in snapshot [:runtime-state :mode])))
          (is (= {:compat true} (get-in snapshot [:websocket :compat-projections])))
          (is (= [{:kind "tick"}
                  {:kind "fill"}]
                 (get-in snapshot [:websocket :flight-recording])))
          (is (= 2 (get-in snapshot [:telemetry :event-count])))
          (is (= [{:event "ready"}
                  {:event "steady"}]
                 (get-in snapshot [:telemetry :events])))
          (is (= "/trade" (:route qa-snapshot)))
          (is (= "BTC" (:activeAsset qa-snapshot)))
          (is (= 2 (count (:recentActionEffectTraces qa-snapshot))))
          (is (= 2 (get-in qa-snapshot [:websocket :flightRecordingCount])))
          (is (= 2 (get-in qa-snapshot [:telemetry :event-count])))
          (is (= "/trade" (get-in snapshot-json [:app-state :router :path])))
          (is (= "debug" (get-in snapshot-json [:runtime-state :mode])))
          (is (= "/trade" (:route qa-snapshot-json)))
          (is (= [{:kind "raw"}] flight-recording))
          (is (= [{:kind "tick"}
                  {:kind "fill"}]
                 flight-recording-redacted))
          (is (= {:replayed 2} replayed))
          (is (= [{:event "ready"}
                  {:event "steady"}]
                 events))
          (is (= "EVENTS_JSON" ((aget api "eventsJson"))))
          (is (= :cleared-events ((aget api "clearEvents"))))
          (is (= :cleared-flight ((aget api "clearFlightRecording"))))
          (is (true? ((aget api "downloadSnapshot"))))
          (is (true? ((aget api "downloadFlightRecording"))))
          (reset! downloaded-snapshot
                  (js->clj (js/JSON.parse (first @created-payloads)) :keywordize-keys true))
          (reset! downloaded-flight
                  (js->clj (js/JSON.parse (second @created-payloads)) :keywordize-keys true))
          (is (= [:events :flight] @clear-calls))
          (is (= 2 @append-count))
          (is (= 2 @remove-count))
          (is (= [{:href "blob://0"
                   :download "hyperopen-debug-snapshot-1700000000000.json"}
                  {:href "blob://1"
                   :download "hyperopen-flight-recording-1700000000000.json"}]
                 @clicked-downloads))
          (is (= ["blob://0" "blob://1"] @revoked-urls))
          (is (= 2 (count @created-payloads)))
          (is (= "/trade" (get-in @downloaded-snapshot [:app-state :router :path])))
          (is (= [{:kind "tick"}
                  {:kind "fill"}]
                 @downloaded-flight))))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))
        (if had-url?
          (set! (.-URL js/globalThis) orig-url)
          (js-delete js/globalThis "URL"))
        (if had-blob?
          (set! (.-Blob js/globalThis) orig-blob)
          (js-delete js/globalThis "Blob"))))))

(deftest download-apis-return-nil-without-document-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        api (@#'console-preload/debug-api)]
    (try
      (js-delete js/globalThis "document")
      (is (nil? ((aget api "downloadSnapshot"))))
      (is (nil? ((aget api "downloadFlightRecording"))))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))))))
