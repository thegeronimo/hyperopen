(ns hyperopen.websocket.order-feedback-runtime-coverage-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.order.feedback-runtime :as feedback-runtime]))

(deftest ws-set-order-feedback-toast-covers-trim-empty-and-nil-test
  (let [store (atom {:ui {:toast nil}})]
    (feedback-runtime/set-order-feedback-toast! store :success "  Order placed.  ")
    (is (= {:kind :success
            :message "Order placed."}
           (get-in @store [:ui :toast])))

    (feedback-runtime/set-order-feedback-toast! store :error "   ")
    (is (nil? (get-in @store [:ui :toast])))

    (feedback-runtime/set-order-feedback-toast! store :error nil)
    (is (nil? (get-in @store [:ui :toast])))))

(deftest ws-clear-order-feedback-toast-and-timeouts-covers-present-and-missing-ids-test
  (let [store (atom {:ui {:toast {:kind :success
                                  :message "Order placed."}}})
        timeout-id-atom (atom :timeout-1)
        runtime (atom {:timeouts {:order-toast :runtime-timeout-1}})
        cleared-timeouts (atom [])]
    (feedback-runtime/clear-order-feedback-toast! store)
    (is (nil? (get-in @store [:ui :toast])))

    (feedback-runtime/clear-order-feedback-toast-timeout!
     timeout-id-atom
     (fn [timeout-id]
       (swap! cleared-timeouts conj timeout-id)))
    (is (= [:timeout-1] @cleared-timeouts))
    (is (nil? @timeout-id-atom))

    (feedback-runtime/clear-order-feedback-toast-timeout!
     timeout-id-atom
     (fn [timeout-id]
       (swap! cleared-timeouts conj timeout-id)))
    (is (= [:timeout-1] @cleared-timeouts))

    (feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
     runtime
     (fn [timeout-id]
       (swap! cleared-timeouts conj timeout-id)))
    (is (= [:timeout-1 :runtime-timeout-1] @cleared-timeouts))
    (is (nil? (get-in @runtime [:timeouts :order-toast])))

    (feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
     runtime
     (fn [timeout-id]
       (swap! cleared-timeouts conj timeout-id)))
    (is (= [:timeout-1 :runtime-timeout-1] @cleared-timeouts))))

(deftest ws-schedule-order-feedback-toast-clear-covers-runtime-and-atom-storage-branches-test
  (let [captured-non-runtime-callback (atom nil)
        captured-runtime-callback (atom nil)
        non-runtime-store (atom {:ui {:toast {:kind :success
                                              :message "Placed"}}})
        runtime-store (atom {:ui {:toast {:kind :success
                                          :message "Placed"}}})
        timeout-id-atom (atom :old-timeout)
        runtime (atom {:timeouts {:order-toast :runtime-old-timeout}})
        non-runtime-cleared (atom [])
        runtime-cleared (atom [])]
    (feedback-runtime/schedule-order-feedback-toast-clear!
     {:store non-runtime-store
      :order-feedback-toast-timeout-id timeout-id-atom
      :clear-order-feedback-toast! feedback-runtime/clear-order-feedback-toast!
      :clear-order-feedback-toast-timeout!
      (fn []
        (feedback-runtime/clear-order-feedback-toast-timeout!
         timeout-id-atom
         (fn [timeout-id]
           (swap! non-runtime-cleared conj timeout-id))))
      :order-feedback-toast-duration-ms 3500
      :set-timeout-fn (fn [callback delay-ms]
                        (is (= 3500 delay-ms))
                        (reset! captured-non-runtime-callback callback)
                        :new-timeout)})
    (is (= [:old-timeout] @non-runtime-cleared))
    (is (= :new-timeout @timeout-id-atom))

    (@captured-non-runtime-callback)
    (is (nil? (get-in @non-runtime-store [:ui :toast])))
    (is (nil? @timeout-id-atom))

    (feedback-runtime/schedule-order-feedback-toast-clear!
     {:store runtime-store
      :runtime runtime
      :order-feedback-toast-timeout-id timeout-id-atom
      :clear-order-feedback-toast! feedback-runtime/clear-order-feedback-toast!
      :clear-order-feedback-toast-timeout!
      (fn []
        (feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
         runtime
         (fn [timeout-id]
           (swap! runtime-cleared conj timeout-id))))
      :order-feedback-toast-duration-ms 1000
      :set-timeout-fn (fn [callback delay-ms]
                        (is (= 1000 delay-ms))
                        (reset! captured-runtime-callback callback)
                        :runtime-new-timeout)})
    (is (= [:runtime-old-timeout] @runtime-cleared))
    (is (= :runtime-new-timeout (get-in @runtime [:timeouts :order-toast])))

    (@captured-runtime-callback)
    (is (nil? (get-in @runtime-store [:ui :toast])))
    (is (nil? (get-in @runtime [:timeouts :order-toast])))))

(deftest ws-show-order-feedback-toast-schedules-only-for-non-blank-messages-test
  (let [schedule-calls (atom 0)
        store (atom {:ui {:toast nil}})
        schedule-fn (fn [_store]
                      (swap! schedule-calls inc))]
    (feedback-runtime/show-order-feedback-toast! store :success "Order canceled." schedule-fn)
    (is (= {:kind :success
            :message "Order canceled."}
           (get-in @store [:ui :toast])))
    (is (= 1 @schedule-calls))

    (feedback-runtime/show-order-feedback-toast! store :error "   " schedule-fn)
    (is (nil? (get-in @store [:ui :toast])))
    (is (= 1 @schedule-calls))))

(deftest ws-feedback-runtime-covers-js-function-call-fallback-paths-test
  (let [store (atom {:ui {:toast {:kind :success
                                  :message "Placed"}}})
        runtime (atom {:timeouts {:order-toast :runtime-old-timeout}})
        timeout-id-atom (atom :old-timeout)
        clear-timeout-fn-js (js/Function.
                             "timeoutId"
                             "globalThis.__wsFeedbackCleared.push(timeoutId);")
        clear-order-feedback-toast-js (js/Function.
                                       "storeValue"
                                       "return hyperopen.order.feedback_runtime.clear_order_feedback_toast_BANG_(storeValue);")
        clear-scheduled-timeout-js (js/Function.
                                    "return hyperopen.order.feedback_runtime.clear_order_feedback_toast_timeout_BANG_(globalThis.__wsFeedbackTimeoutAtom, globalThis.__wsFeedbackClearTimeoutFn);")
        set-timeout-fn-js (js/Function.
                           "callback"
                           "delayMs"
                           "globalThis.__wsFeedbackCapturedCallback = callback; globalThis.__wsFeedbackCapturedDelay = delayMs; return 'js-new-timeout';")
        cleared-timeouts #js []]
    (set! (.-__wsFeedbackCleared js/globalThis) cleared-timeouts)
    (set! (.-__wsFeedbackTimeoutAtom js/globalThis) timeout-id-atom)
    (set! (.-__wsFeedbackClearTimeoutFn js/globalThis) clear-timeout-fn-js)
    (set! (.-__wsFeedbackCapturedCallback js/globalThis) nil)
    (set! (.-__wsFeedbackCapturedDelay js/globalThis) nil)
    (try
      (feedback-runtime/clear-order-feedback-toast-timeout!
       timeout-id-atom
       clear-timeout-fn-js)
      (is (= 1 (.-length cleared-timeouts)))
      (is (nil? @timeout-id-atom))

      (feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
       runtime
       clear-timeout-fn-js)
      (is (= 2 (.-length cleared-timeouts)))
      (is (nil? (get-in @runtime [:timeouts :order-toast])))

      (reset! timeout-id-atom :old-scheduled-timeout)
      (feedback-runtime/schedule-order-feedback-toast-clear!
       {:store store
        :order-feedback-toast-timeout-id timeout-id-atom
        :clear-order-feedback-toast! clear-order-feedback-toast-js
        :clear-order-feedback-toast-timeout! clear-scheduled-timeout-js
        :order-feedback-toast-duration-ms 50
        :set-timeout-fn set-timeout-fn-js})
      (is (= 3 (.-length cleared-timeouts)))
      (is (= "js-new-timeout" @timeout-id-atom))
      (is (= 50 (.-__wsFeedbackCapturedDelay js/globalThis)))

      (let [captured-callback (.-__wsFeedbackCapturedCallback js/globalThis)]
        (is (fn? captured-callback))
        (captured-callback))
      (is (nil? (get-in @store [:ui :toast])))
      (is (nil? @timeout-id-atom))
      (finally
        (set! (.-__wsFeedbackCleared js/globalThis) nil)
        (set! (.-__wsFeedbackTimeoutAtom js/globalThis) nil)
        (set! (.-__wsFeedbackClearTimeoutFn js/globalThis) nil)
        (set! (.-__wsFeedbackCapturedCallback js/globalThis) nil)
        (set! (.-__wsFeedbackCapturedDelay js/globalThis) nil)))))
