(ns hyperopen.order.feedback-runtime-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.order.feedback-runtime :as feedback-runtime]))

(deftest set-order-feedback-toast-trims-message-and-clears-on-blank-test
  (let [store (atom {:ui {:toast nil}})]
    (feedback-runtime/set-order-feedback-toast! store :success "  Order placed.  ")
    (is (= {:kind :success
            :message "Order placed."}
           (get-in @store [:ui :toast])))
    (feedback-runtime/set-order-feedback-toast! store :error "   ")
    (is (nil? (get-in @store [:ui :toast])))))

(deftest clear-order-feedback-toast-timeout-clears-id-and-resets-atom-test
  (let [cleared (atom [])
        timeout-id-atom (atom :timeout-1)]
    (feedback-runtime/clear-order-feedback-toast-timeout!
     timeout-id-atom
     (fn [timeout-id]
       (swap! cleared conj timeout-id)))
    (is (= [:timeout-1] @cleared))
    (is (nil? @timeout-id-atom))))

(deftest schedule-order-feedback-toast-clear-sets-timeout-and-clears-toast-test
  (let [captured-callback (atom nil)
        cleared-timeouts (atom [])
        store (atom {:ui {:toast {:kind :success
                                  :message "Order placed."}}})
        timeout-id-atom (atom :old-timeout)]
    (feedback-runtime/schedule-order-feedback-toast-clear!
     {:store store
      :order-feedback-toast-timeout-id timeout-id-atom
      :clear-order-feedback-toast! feedback-runtime/clear-order-feedback-toast!
      :clear-order-feedback-toast-timeout!
      (fn []
        (feedback-runtime/clear-order-feedback-toast-timeout!
         timeout-id-atom
         (fn [timeout-id]
           (swap! cleared-timeouts conj timeout-id))))
      :order-feedback-toast-duration-ms 3500
      :set-timeout-fn (fn [callback _delay-ms]
                        (reset! captured-callback callback)
                        :new-timeout)})
    (is (= [:old-timeout] @cleared-timeouts))
    (is (= :new-timeout @timeout-id-atom))
    (@captured-callback)
    (is (nil? (get-in @store [:ui :toast])))
    (is (nil? @timeout-id-atom))))

(deftest schedule-order-feedback-toast-clear-supports-runtime-timeout-storage-test
  (let [captured-callback (atom nil)
        cleared-timeouts (atom [])
        store (atom {:ui {:toast {:kind :success
                                  :message "Order placed."}}})
        runtime (atom {:timeouts {:order-toast :old-timeout}})]
    (feedback-runtime/schedule-order-feedback-toast-clear!
     {:store store
      :runtime runtime
      :clear-order-feedback-toast! feedback-runtime/clear-order-feedback-toast!
      :clear-order-feedback-toast-timeout!
      (fn []
        (feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
         runtime
         (fn [timeout-id]
           (swap! cleared-timeouts conj timeout-id))))
      :order-feedback-toast-duration-ms 3500
      :set-timeout-fn (fn [callback _delay-ms]
                        (reset! captured-callback callback)
                        :new-timeout)})
    (is (= [:old-timeout] @cleared-timeouts))
    (is (= :new-timeout (get-in @runtime [:timeouts :order-toast])))
    (@captured-callback)
    (is (nil? (get-in @store [:ui :toast])))
    (is (nil? (get-in @runtime [:timeouts :order-toast])))))

(deftest show-order-feedback-toast-schedules-clear-only-when-message-present-test
  (let [schedule-calls (atom 0)
        store (atom {:ui {:toast nil}})
        schedule-fn (fn [_]
                      (swap! schedule-calls inc))]
    (feedback-runtime/show-order-feedback-toast! store :success "Order canceled." schedule-fn)
    (is (= {:kind :success
            :message "Order canceled."}
           (get-in @store [:ui :toast])))
    (is (= 1 @schedule-calls))
    (feedback-runtime/show-order-feedback-toast! store :error "   " schedule-fn)
    (is (nil? (get-in @store [:ui :toast])))
    (is (= 1 @schedule-calls))))
