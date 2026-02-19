(ns hyperopen.websocket.coverage-low-functions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.schema.contracts :as contracts]
            [hyperopen.telemetry :as telemetry]))

(defn- reset-telemetry-state!
  []
  (reset! @#'hyperopen.telemetry/event-log [])
  (reset! @#'hyperopen.telemetry/event-seq 0))

(deftest ws-platform-low-function-coverage-smoke-test
  (let [orig-confirm (.-confirm js/globalThis)
        orig-set-timeout (.-setTimeout js/globalThis)
        orig-set-interval (.-setInterval js/globalThis)
        orig-clear-timeout (.-clearTimeout js/globalThis)
        orig-clear-interval (.-clearInterval js/globalThis)
        calls (atom [])]
    (try
      (set! (.-confirm js/globalThis) (fn [msg]
                                        (swap! calls conj [:confirm msg])
                                        true))
      (set! (.-setTimeout js/globalThis) (fn [f ms]
                                           (swap! calls conj [:set-timeout ms])
                                           (when (fn? f) (f))
                                           :timeout-id))
      (set! (.-setInterval js/globalThis) (fn [f ms]
                                            (swap! calls conj [:set-interval ms])
                                            (when (fn? f) (f))
                                            :interval-id))
      (set! (.-clearTimeout js/globalThis) (fn [id]
                                             (swap! calls conj [:clear-timeout id])
                                             :cleared-timeout))
      (set! (.-clearInterval js/globalThis) (fn [id]
                                              (swap! calls conj [:clear-interval id])
                                              :cleared-interval))

      (is (number? (platform/now-ms)))
      (is (number? (platform/random-value)))
      (is (true? (platform/confirm! "ws confirm")))
      (is (= :timeout-id (platform/set-timeout! (fn [] nil) 5)))
      (is (= :interval-id (platform/set-interval! (fn [] nil) 7)))
      (is (= :cleared-timeout (platform/clear-timeout! :timeout-id)))
      (is (= :cleared-interval (platform/clear-interval! :interval-id)))
      (platform/local-storage-set! "hyperopen-ws" "1")
      (platform/local-storage-get "hyperopen-ws")
      (platform/local-storage-remove! "hyperopen-ws")
      (let [orig-queue-microtask (.-queueMicrotask js/globalThis)
            orig-request-animation-frame (.-requestAnimationFrame js/globalThis)]
        (try
          (set! (.-queueMicrotask js/globalThis) nil)
          (set! (.-requestAnimationFrame js/globalThis) nil)
          (with-redefs [hyperopen.platform/set-timeout! (fn [f _]
                                                          (f)
                                                          :fallback)]
            (is (= :fallback (platform/queue-microtask! (fn [] nil))))
            (is (= :fallback (platform/request-animation-frame! (fn [_] nil)))))
          (finally
            (set! (.-queueMicrotask js/globalThis) orig-queue-microtask)
            (set! (.-requestAnimationFrame js/globalThis) orig-request-animation-frame))))
      (is (seq @calls))
      (finally
        (set! (.-confirm js/globalThis) orig-confirm)
        (set! (.-setTimeout js/globalThis) orig-set-timeout)
        (set! (.-setInterval js/globalThis) orig-set-interval)
        (set! (.-clearTimeout js/globalThis) orig-clear-timeout)
        (set! (.-clearInterval js/globalThis) orig-clear-interval)))))

(deftest ws-telemetry-low-function-coverage-smoke-test
  (reset-telemetry-state!)
  (with-redefs [hyperopen.telemetry/dev-enabled? (constantly true)
                hyperopen.platform/now-ms (constantly 777)]
    (telemetry/clear-events!)
    (is (= [] (telemetry/events)))
    (is (= :ws/event (:event (telemetry/emit! :ws/event {:phase :ws})) ))
    (telemetry/log! "ws" :coverage {:ok true})
    (is (= 2 (count (telemetry/events))))
    (is (string? (telemetry/events-json)))
    (telemetry/clear-events!)
    (is (= [] (telemetry/events))))
  (with-redefs [hyperopen.telemetry/dev-enabled? (constantly false)]
    (is (nil? (telemetry/emit! :disabled {})))))

(deftest ws-schema-contracts-low-function-coverage-smoke-test
  (let [ctx {:suite :ws}
        valid-state {:active-asset nil
                     :active-market nil
                     :asset-selector {:markets []
                                      :market-by-key {}
                                      :favorites #{}
                                      :loaded-icons #{}
                                      :missing-icons #{}}
                     :wallet {:agent {}}
                     :websocket {}
                     :websocket-ui {}
                     :router {:path "/"}
                     :order-form {}
                     :order-form-ui {:pro-order-type-dropdown-open? false
                                     :size-unit-dropdown-open? false
                                     :price-input-focused? false
                                     :tpsl-panel-open? false
                                     :entry-mode :limit
                                     :ui-leverage 20
                                     :size-input-mode :quote
                                     :size-input-source :manual
                                     :size-display ""}
                     :order-form-runtime {:submitting? false
                                          :error nil}}
        signed {:action {:type "order"}
                :nonce 1
                :signature {:r "0x1" :s "0x2" :v 27}}]
    (is (set? (contracts/contracted-action-ids)))
    (is (set? (contracts/contracted-effect-ids)))
    (is (set? (contracts/action-ids-using-any-args)))

    (is (= [[:wallet :address] "0xabc"]
           (contracts/assert-action-args! :actions/update-order-form
                                          [[:wallet :address] "0xabc"]
                                          ctx)))
    (is (= [12]
           (contracts/assert-action-args! :actions/next-order-history-page
                                          [12]
                                          ctx)))
    (is (thrown-with-msg?
         js/Error
         #"action payload"
         (contracts/assert-action-args! :actions/next-order-history-page
                                        ["12"]
                                        ctx)))

    (is (= [12]
           (contracts/assert-effect-args! :effects/api-fetch-user-funding-history
                                          [12]
                                          ctx)))
    (is (= [:interval :1m :bars 3]
           (contracts/assert-effect-args! :effects/fetch-candle-snapshot
                                          [:interval :1m :bars 3]
                                          ctx)))
    (is (= []
           (contracts/assert-effect-args! :effects/fetch-asset-selector-markets [] ctx)))

    (is (= [:effects/connect-wallet]
           (contracts/assert-effect-call! [:effects/connect-wallet]
                                          ctx)))
    (is (= [[:effects/connect-wallet]
            [:effects/disconnect-wallet]]
           (contracts/assert-emitted-effects! [[:effects/connect-wallet]
                                               [:effects/disconnect-wallet]]
                                              ctx)))

    (is (= valid-state (contracts/assert-app-state! valid-state ctx)))
    (is (= signed (contracts/assert-signed-exchange-payload! signed ctx)))))
