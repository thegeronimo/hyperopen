(ns hyperopen.telemetry.console-preload-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [nexus.registry :as nxr]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.system :as app-system]
            [hyperopen.telemetry.console-preload :as console-preload]
            [hyperopen.views.trade.order-form-vm :as order-form-vm]
            [hyperopen.wallet.core :as wallet-core]))

(deftest console-preload-installs-debug-global-in-debug-build-test
  (let [api (aget js/globalThis "HYPEROPEN_DEBUG")]
    (is (some? api))
    (is (fn? (aget api "registeredActionIds")))
    (is (fn? (aget api "dispatch")))))

(deftest registered-action-ids-api-returns-stable-string-ids-test
  (let [api (@#'console-preload/debug-api)
        ids ((aget api "registeredActionIds"))
        ids* (js->clj ids)]
    (is (array? ids))
    (is (some #{":actions/start-spectate-mode"} ids*))
    (is (some #{":actions/stop-spectate-mode"} ids*))
    (is (= ids* (sort ids*)))))

(deftest dispatch-api-normalizes-supported-action-id-strings-and-delegates-test
  (let [store (atom {:account-context {}})
        dispatched (atom [])]
    (with-redefs [app-system/store store
                  nxr/dispatch (fn [runtime-store event actions]
                                 (swap! dispatched conj [runtime-store event actions]))]
      (let [api (@#'console-preload/debug-api)
            dispatch! (aget api "dispatch")
            start-result (dispatch! #js [":actions/start-spectate-mode" "0xabc"])
            stop-result (dispatch! #js ["actions/stop-spectate-mode"])]
        (is (= [[store nil [[:actions/start-spectate-mode "0xabc"]]]
                [store nil [[:actions/stop-spectate-mode]]]]
               @dispatched))
        (is (= {:dispatched true
                :actionId ":actions/start-spectate-mode"
                :argCount 1}
               (js->clj start-result :keywordize-keys true)))
        (is (= {:dispatched true
                :actionId ":actions/stop-spectate-mode"
                :argCount 0}
               (js->clj stop-result :keywordize-keys true)))))))

(deftest dispatch-api-rejects-malformed-and-unregistered-actions-test
  (let [api (@#'console-preload/debug-api)
        dispatch! (aget api "dispatch")]
    (testing "non-array inputs fail fast"
      (is (thrown-with-msg?
           js/Error
           #"expected an action vector"
           (dispatch! "not-an-action-vector"))))
    (testing "empty vectors fail fast"
      (is (thrown-with-msg?
           js/Error
           #"expected an action vector"
           (dispatch! #js []))))
    (testing "unknown action ids are rejected"
      (is (thrown-with-msg?
           js/Error
           #"unregistered action id"
           (dispatch! #js [":actions/not-real"]))))))

(deftest debug-api-exposes-qa-scenario-helpers-test
  (let [api (@#'console-preload/debug-api)]
    (is (fn? (aget api "qaSnapshot")))
    (is (fn? (aget api "dispatchMany")))
    (is (fn? (aget api "waitForIdle")))
    (is (fn? (aget api "elementRect")))
    (is (fn? (aget api "oracle")))
    (is (fn? (aget api "qaReset")))
    (is (fn? (aget api "setWalletConnectedHandlerMode")))
    (is (fn? (aget api "installWalletSimulator")))
    (is (fn? (aget api "installExchangeSimulator")))))

(deftest dispatch-many-normalizes-keyword-like-args-before-dispatch-test
  (let [store (atom {})
        dispatched (atom [])]
    (with-redefs [app-system/store store
                  nxr/dispatch (fn [_runtime-store _event actions]
                                 (reset! dispatched actions))]
      (let [api (@#'console-preload/debug-api)
            dispatch-many! (aget api "dispatchMany")]
        (dispatch-many! #js [#js [":actions/toggle-asset-dropdown" ":asset-selector"]
                             #js [":actions/select-order-entry-mode" ":limit"]])
        (is (= [[:actions/toggle-asset-dropdown :asset-selector]
                [:actions/select-order-entry-mode :limit]]
               @dispatched))))))

(deftest normalize-debug-wire-value-handles-keywords-collections-js-arrays-and-plain-objects-test
  (let [normalize-wire-value @#'console-preload/normalize-debug-wire-value
        js-object (doto #js {}
                    (aset "mode" ":fast")
                    (aset "labels" #js [":one" "two"]))
        normalized (normalize-wire-value
                    {:keyword :already
                     :keyword-string ":hello"
                     :plain-string "world"
                     :vector [":a" "b"]
                     :seq '(":c" "d")
                     :array #js [":e" "f"]
                     :object js-object
                     ":map-key" ":map-value"
                     :number 42})]
    (is (= :already (:keyword normalized)))
    (is (= :hello (:keyword-string normalized)))
    (is (= "world" (:plain-string normalized)))
    (is (= [:a "b"] (:vector normalized)))
    (is (= [:c "d"] (:seq normalized)))
    (is (= [:e "f"] (:array normalized)))
    (is (= {:mode :fast
            :labels [:one "two"]}
           (:object normalized)))
    (is (= :map-value (get normalized :map-key)))
    (is (= 42 (:number normalized)))))

(deftest wait-for-idle-resolves-true-after-quiet-period-test
  (async done
    (let [original-state @app-system/store
          wait-for-idle @#'console-preload/wait-for-idle]
      (try
        (swap! app-system/store assoc :router {:path "/idle-stable"})
        (-> (wait-for-idle #js {:quietMs 10
                                :timeoutMs 25
                                :pollMs 1})
            (.then (fn [result]
                     (try
                       (let [result* (js->clj result :keywordize-keys true)]
                         (is (true? (:settled result*)))
                         (is (>= (:quietForMs result*) 10))
                         (is (>= (:elapsedMs result*) 10)))
                       (finally
                         (reset! app-system/store original-state)
                         (done)))))
            (.catch (fn [err]
                      (reset! app-system/store original-state)
                      (is false (str "Unexpected waitForIdle settle-path error: " err))
                      (done))))
        (catch :default err
          (reset! app-system/store original-state)
          (is false (str "Unexpected waitForIdle setup error: " err))
          (done))))))

(deftest wait-for-idle-times-out-false-when-digest-keeps-changing-test
  (async done
    (let [original-state @app-system/store
          wait-for-idle @#'console-preload/wait-for-idle
          interval-id (atom nil)]
      (try
        (swap! app-system/store assoc :router {:path "/idle-one"})
        (reset! interval-id
                (js/setInterval
                 (fn []
                   (swap! app-system/store update-in [:router :path]
                          (fn [path]
                            (if (= path "/idle-one")
                              "/idle-two"
                              "/idle-one"))))
                 1))
        (-> (wait-for-idle {:quiet-ms 20
                            :timeout-ms 5
                            :poll-ms 1})
            (.then (fn [result]
                     (try
                       (let [result* (js->clj result :keywordize-keys true)]
                         (is (false? (:settled result*)))
                         (is (>= (:elapsedMs result*) 20))
                         (is (< (:quietForMs result*) 20)))
                       (finally
                         (js/clearInterval @interval-id)
                         (reset! app-system/store original-state)
                         (done)))))
            (.catch (fn [err]
                      (js/clearInterval @interval-id)
                      (reset! app-system/store original-state)
                      (is false (str "Unexpected waitForIdle timeout-path error: " err))
                      (done))))
        (catch :default err
          (when @interval-id
            (js/clearInterval @interval-id))
          (reset! app-system/store original-state)
          (is false (str "Unexpected waitForIdle timeout setup error: " err))
          (done))))))

(deftest oracle-api-surfaces-wallet-and-order-form-state-test
  (let [store (atom {:active-asset "BTC"
                     :wallet {:connected? true
                              :address "0xabc"
                              :chain-id "0xa4b1"
                              :agent {:status :not-ready}}
                     :orders {:cancel-error "Enable trading before cancelling orders."}})
        api (@#'console-preload/debug-api)
        oracle! (aget api "oracle")]
    (with-redefs [app-system/store store
                  order-form-vm/order-form-vm (fn [_]
                                                {:side :buy
                                                 :type :limit
                                                 :entry-mode :limit
                                                 :size-display "1"
                                                 :error "Enable trading before submitting orders."
                                                 :submit {:disabled? true
                                                          :reason :agent-not-ready
                                                          :error-message "Enable trading before submitting orders."
                                                          :tooltip "Enable trading before submitting orders."}})]
      (let [wallet (js->clj (oracle! "wallet-status" #js {}) :keywordize-keys true)
            order-form (js->clj (oracle! "order-form" #js {}) :keywordize-keys true)]
        (is (= {:connected true
                :address "0xabc"
                :chainId "0xa4b1"
                :connecting false
                :error nil
                :agentStatus "not-ready"
                :agentAddress nil
                :agentError nil}
               wallet))
        (is (= "BTC" (:activeAsset order-form)))
        (is (= "buy" (:side order-form)))
        (is (= "limit" (:type order-form)))
        (is (= "1" (:sizeDisplay order-form)))
        (is (= "Enable trading before submitting orders." (:runtimeError order-form)))
        (is (= "Enable trading before cancelling orders." (:cancelError order-form)))
        (is (= "agent-not-ready" (:submitReason order-form)))
        (is (true? (:submitDisabled order-form)))))))

(deftest funding-modal-oracle-reads-selected-asset-from-view-model-test
  (let [store (atom {})
        api (@#'console-preload/debug-api)
        oracle! (aget api "oracle")]
    (with-redefs [app-system/store store
                  funding-actions/funding-modal-view-model
                  (fn [_]
                    {:modal {:open? true
                             :title "Deposit USDC"}
                     :content {:kind :deposit/amount}
                     :deposit-selected-asset {:key :usdc}})]
      (let [funding (js->clj (oracle! "funding-modal" #js {}) :keywordize-keys true)]
        (is (= true (:open funding)))
        (is (= "Deposit USDC" (:title funding)))
        (is (= ":deposit/amount" (:contentKind funding)))
        (is (= "usdc" (:selectedDepositAssetKey funding)))))))

(deftest set-wallet-connected-handler-mode-suppresses-and-restores-handler-test
  (let [api (@#'console-preload/debug-api)
        set-mode! (aget api "setWalletConnectedHandlerMode")
        handler (fn [_store _address] :handled)]
    (wallet-core/set-on-connected-handler! handler)
    (try
      (let [suppressed (js->clj (set-mode! "suppress") :keywordize-keys true)]
        (is (true? (:suppressed suppressed)))
        (is (false? (:hasHandler suppressed)))
        (is (nil? (wallet-core/current-on-connected-handler))))
      (let [restored (js->clj (set-mode! "passthrough") :keywordize-keys true)]
        (is (false? (:suppressed restored)))
        (is (true? (:hasHandler restored)))
        (is (identical? handler (wallet-core/current-on-connected-handler))))
      (finally
        (wallet-core/clear-on-connected-handler!)
        (set-mode! "passthrough")))))
