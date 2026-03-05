(ns hyperopen.websocket.order-feedback-runtime-coverage-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.order.feedback-runtime :as feedback-runtime]))

(deftest ws-set-order-feedback-toast-covers-trim-empty-and-nil-test
  (let [store (atom {:ui {:toast nil
                          :toasts []}})]
    (feedback-runtime/set-order-feedback-toast! store :success "  Order placed.  ")
    (is (= {:kind :success
            :message "Order placed."}
           (get-in @store [:ui :toast])))
    (is (= 1 (count (get-in @store [:ui :toasts]))))

    (feedback-runtime/set-order-feedback-toast!
     store
     :success
     {:headline "Bought 8.56 HYPE"
      :subline "At average price of $31.969"})
    (is (= "Bought 8.56 HYPE"
           (get-in @store [:ui :toast :headline])))
    (is (= 2 (count (get-in @store [:ui :toasts]))))

    (feedback-runtime/set-order-feedback-toast! store :error "   ")
    (is (nil? (get-in @store [:ui :toast])))
    (is (empty? (get-in @store [:ui :toasts])))

    (feedback-runtime/set-order-feedback-toast! store :error nil)
    (is (nil? (get-in @store [:ui :toast])))
    (is (empty? (get-in @store [:ui :toasts])))))

(deftest ws-clear-order-feedback-toast-and-timeouts-covers-present-and-missing-ids-test
  (let [store (atom {:ui {:toast {:kind :success
                                  :message "Order placed."}
                          :toasts [{:id :a
                                    :kind :success
                                    :message "Order placed."}]}})
        timeout-id-atom (atom {:a :timeout-1
                               :b :timeout-2})
        runtime (atom {:timeouts {:order-toast {:runtime-a :runtime-timeout-1
                                                :runtime-b :runtime-timeout-2}}})
        cleared-timeouts (atom [])]
    (feedback-runtime/clear-order-feedback-toast! store :a)
    (is (nil? (get-in @store [:ui :toast])))
    (is (empty? (get-in @store [:ui :toasts])))

    (feedback-runtime/clear-order-feedback-toast-timeout!
     timeout-id-atom
     (fn [timeout-id]
       (swap! cleared-timeouts conj timeout-id))
     :a)
    (is (= [:timeout-1] @cleared-timeouts))
    (is (= {:b :timeout-2} @timeout-id-atom))

    (feedback-runtime/clear-order-feedback-toast-timeout!
     timeout-id-atom
     (fn [timeout-id]
       (swap! cleared-timeouts conj timeout-id)))
    (is (= [:timeout-1 :timeout-2] @cleared-timeouts))
    (is (= {} @timeout-id-atom))

    (feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
     runtime
     (fn [timeout-id]
       (swap! cleared-timeouts conj timeout-id))
     :runtime-a)
    (is (= [:timeout-1 :timeout-2 :runtime-timeout-1] @cleared-timeouts))
    (is (= {:runtime-b :runtime-timeout-2}
           (get-in @runtime [:timeouts :order-toast])))

    (feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
     runtime
     (fn [timeout-id]
       (swap! cleared-timeouts conj timeout-id)))
    (is (= [:timeout-1 :timeout-2 :runtime-timeout-1 :runtime-timeout-2]
           @cleared-timeouts))
    (is (= {} (get-in @runtime [:timeouts :order-toast])))))

(deftest ws-schedule-order-feedback-toast-clear-covers-runtime-and-atom-storage-branches-test
  (let [captured-non-runtime-callback (atom nil)
        captured-runtime-callback (atom nil)
        non-runtime-store (atom {:ui {:toast {:kind :success
                                              :message "Placed"}
                                      :toasts [{:id :non-runtime-id
                                                :kind :success
                                                :message "Placed"}]}})
        runtime-store (atom {:ui {:toast {:kind :success
                                          :message "Placed"}
                                  :toasts [{:id :runtime-id
                                            :kind :success
                                            :message "Placed"}]}})
        timeout-id-atom (atom {:non-runtime-id :old-timeout})
        runtime (atom {:timeouts {:order-toast {:runtime-id :runtime-old-timeout}}})
        non-runtime-cleared (atom [])
        runtime-cleared (atom [])]
    (feedback-runtime/schedule-order-feedback-toast-clear!
     {:store non-runtime-store
      :toast-id :non-runtime-id
      :order-feedback-toast-timeout-id timeout-id-atom
      :clear-order-feedback-toast! feedback-runtime/clear-order-feedback-toast!
      :clear-order-feedback-toast-timeout!
      (fn
        ([] (feedback-runtime/clear-order-feedback-toast-timeout!
             timeout-id-atom
             (fn [timeout-id]
               (swap! non-runtime-cleared conj timeout-id))))
        ([toast-id] (feedback-runtime/clear-order-feedback-toast-timeout!
                     timeout-id-atom
                     (fn [timeout-id]
                       (swap! non-runtime-cleared conj timeout-id))
                     toast-id)))
      :order-feedback-toast-duration-ms 3500
      :set-timeout-fn (fn [callback delay-ms]
                        (is (= 3500 delay-ms))
                        (reset! captured-non-runtime-callback callback)
                        :new-timeout)})
    (is (= [:old-timeout] @non-runtime-cleared))
    (is (= {:non-runtime-id :new-timeout} @timeout-id-atom))

    (@captured-non-runtime-callback)
    (is (nil? (get-in @non-runtime-store [:ui :toast])))
    (is (empty? (get-in @non-runtime-store [:ui :toasts])))
    (is (= {} @timeout-id-atom))

    (feedback-runtime/schedule-order-feedback-toast-clear!
     {:store runtime-store
      :runtime runtime
      :toast-id :runtime-id
      :order-feedback-toast-timeout-id timeout-id-atom
      :clear-order-feedback-toast! feedback-runtime/clear-order-feedback-toast!
      :clear-order-feedback-toast-timeout!
      (fn
        ([] (feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
             runtime
             (fn [timeout-id]
               (swap! runtime-cleared conj timeout-id))))
        ([toast-id] (feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
                     runtime
                     (fn [timeout-id]
                       (swap! runtime-cleared conj timeout-id))
                     toast-id)))
      :order-feedback-toast-duration-ms 1000
      :set-timeout-fn (fn [callback delay-ms]
                        (is (= 1000 delay-ms))
                        (reset! captured-runtime-callback callback)
                        :runtime-new-timeout)})
    (is (= [:runtime-old-timeout] @runtime-cleared))
    (is (= {:runtime-id :runtime-new-timeout}
           (get-in @runtime [:timeouts :order-toast])))

    (@captured-runtime-callback)
    (is (nil? (get-in @runtime-store [:ui :toast])))
    (is (empty? (get-in @runtime-store [:ui :toasts])))
    (is (= {} (get-in @runtime [:timeouts :order-toast])))))

(deftest ws-feedback-runtime-covers-js-function-call-fallback-paths-test
  (let [store (atom {:ui {:toast {:kind :success
                                  :message "Placed"}
                          :toasts [{:id :legacy
                                    :kind :success
                                    :message "Placed"}]}})
        runtime (atom {:timeouts {:order-toast {:legacy :runtime-old-timeout}}})
        timeout-id-atom (atom {:legacy :old-timeout})
        clear-timeout-fn-js (js/Function.
                             "timeoutId"
                             "globalThis.__wsFeedbackCleared.push(timeoutId);")
        clear-order-feedback-toast-js (js/Function.
                                       "storeValue"
                                       "toastId"
                                       "if (arguments.length > 1) { return hyperopen.order.feedback_runtime.clear_order_feedback_toast_BANG_(storeValue, toastId); }\nreturn hyperopen.order.feedback_runtime.clear_order_feedback_toast_BANG_(storeValue);")
        clear-scheduled-timeout-js (js/Function.
                                    "toastId"
                                    "if (arguments.length > 0) {\n  return hyperopen.order.feedback_runtime.clear_order_feedback_toast_timeout_BANG_(globalThis.__wsFeedbackTimeoutAtom, globalThis.__wsFeedbackClearTimeoutFn, toastId);\n}\nreturn hyperopen.order.feedback_runtime.clear_order_feedback_toast_timeout_BANG_(globalThis.__wsFeedbackTimeoutAtom, globalThis.__wsFeedbackClearTimeoutFn);")
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
       clear-timeout-fn-js
       :legacy)
      (is (= 1 (.-length cleared-timeouts)))
      (is (= {} @timeout-id-atom))

      (feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
       runtime
       clear-timeout-fn-js
       :legacy)
      (is (= 2 (.-length cleared-timeouts)))
      (is (= {} (get-in @runtime [:timeouts :order-toast])))

      (reset! timeout-id-atom {:legacy :old-scheduled-timeout})
      (feedback-runtime/schedule-order-feedback-toast-clear!
       {:store store
        :toast-id :legacy
        :order-feedback-toast-timeout-id timeout-id-atom
        :clear-order-feedback-toast! clear-order-feedback-toast-js
        :clear-order-feedback-toast-timeout! clear-scheduled-timeout-js
        :order-feedback-toast-duration-ms 50
        :set-timeout-fn set-timeout-fn-js})
      (is (= 3 (.-length cleared-timeouts)))
      (is (= {:legacy "js-new-timeout"} @timeout-id-atom))
      (is (= 50 (.-__wsFeedbackCapturedDelay js/globalThis)))

      (let [captured-callback (.-__wsFeedbackCapturedCallback js/globalThis)]
        (is (fn? captured-callback))
        (captured-callback))
      (is (nil? (get-in @store [:ui :toast])))
      (is (empty? (get-in @store [:ui :toasts])))
      (is (= {} @timeout-id-atom))
      (finally
        (set! (.-__wsFeedbackCleared js/globalThis) nil)
        (set! (.-__wsFeedbackTimeoutAtom js/globalThis) nil)
        (set! (.-__wsFeedbackClearTimeoutFn js/globalThis) nil)
        (set! (.-__wsFeedbackCapturedCallback js/globalThis) nil)
        (set! (.-__wsFeedbackCapturedDelay js/globalThis) nil)))))
