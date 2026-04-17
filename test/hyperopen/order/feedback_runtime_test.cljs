(ns hyperopen.order.feedback-runtime-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.order.feedback-runtime :as feedback-runtime]))

(defn- make-store []
  (atom {:ui {:toast nil
              :toasts []}}))

(deftest set-order-feedback-toast-trims-and-stacks-toasts-test
  (let [store (make-store)
        first-toast (feedback-runtime/set-order-feedback-toast! store :success "  Order placed.  ")
        second-toast (feedback-runtime/set-order-feedback-toast! store :error "Order canceled.")]
    (is (= {:kind :error
            :message "Order canceled."}
           (get-in @store [:ui :toast])))
    (is (= 2 (count (get-in @store [:ui :toasts]))))
    (is (string? (:id first-toast)))
    (is (string? (:id second-toast)))
    (is (not= (:id first-toast) (:id second-toast)))

    (feedback-runtime/set-order-feedback-toast! store :error "   ")
    (is (nil? (get-in @store [:ui :toast])))
    (is (empty? (get-in @store [:ui :toasts])))))

(deftest clear-order-feedback-toast-clears-by-id-or-all-test
  (let [store (make-store)
        first-toast (feedback-runtime/set-order-feedback-toast! store :success "Order placed.")
        second-toast (feedback-runtime/set-order-feedback-toast! store :success "Order filled.")]
    (feedback-runtime/clear-order-feedback-toast! store (:id second-toast))
    (is (= {:kind :success
            :message "Order placed."}
           (get-in @store [:ui :toast])))
    (is (= [(:id first-toast)]
           (mapv :id (get-in @store [:ui :toasts]))))

    (feedback-runtime/clear-order-feedback-toast! store)
    (is (nil? (get-in @store [:ui :toast])))
    (is (empty? (get-in @store [:ui :toasts])))))

(deftest clear-order-feedback-toast-timeout-clears-target-and-all-for-atom-storage-test
  (let [cleared (atom [])
        timeout-id-atom (atom {:a :timeout-a
                               :b :timeout-b})]
    (feedback-runtime/clear-order-feedback-toast-timeout!
     timeout-id-atom
     (fn [timeout-id]
       (swap! cleared conj timeout-id))
     :a)
    (is (= [:timeout-a] @cleared))
    (is (= {:b :timeout-b} @timeout-id-atom))

    (feedback-runtime/clear-order-feedback-toast-timeout!
     timeout-id-atom
     (fn [timeout-id]
       (swap! cleared conj timeout-id)))
    (is (= [:timeout-a :timeout-b] @cleared))
    (is (= {} @timeout-id-atom))))

(deftest schedule-order-feedback-toast-clear-sets-target-timeout-and-clears-target-toast-test
  (let [captured-callback (atom nil)
        cleared-timeouts (atom [])
        store (make-store)
        timeout-id-atom (atom {:old-id :old-timeout})
        old-toast (feedback-runtime/set-order-feedback-toast! store :success "Old toast.")
        new-toast (feedback-runtime/set-order-feedback-toast! store :success "New toast.")]
    (feedback-runtime/schedule-order-feedback-toast-clear!
     {:store store
      :toast-id (:id new-toast)
      :order-feedback-toast-timeout-id timeout-id-atom
      :clear-order-feedback-toast! feedback-runtime/clear-order-feedback-toast!
      :clear-order-feedback-toast-timeout!
      (fn
        ([] (feedback-runtime/clear-order-feedback-toast-timeout!
             timeout-id-atom
             (fn [timeout-id]
               (swap! cleared-timeouts conj timeout-id))))
        ([toast-id] (feedback-runtime/clear-order-feedback-toast-timeout!
                     timeout-id-atom
                     (fn [timeout-id]
                       (swap! cleared-timeouts conj timeout-id))
                     toast-id)))
      :order-feedback-toast-duration-ms 3500
      :set-timeout-fn (fn [callback _delay-ms]
                        (reset! captured-callback callback)
                        :new-timeout)})
    (is (= [] @cleared-timeouts))
    (is (= {:old-id :old-timeout
            (:id new-toast) :new-timeout}
           @timeout-id-atom))

    (@captured-callback)
    (is (= {:kind :success
            :message "Old toast."}
           (get-in @store [:ui :toast])))
    (is (= [(:id old-toast)]
           (mapv :id (get-in @store [:ui :toasts]))))
    (is (= {:old-id :old-timeout} @timeout-id-atom))))

(deftest schedule-order-feedback-toast-clear-supports-runtime-timeout-storage-test
  (let [captured-callback (atom nil)
        cleared-timeouts (atom [])
        store (make-store)
        toast (feedback-runtime/set-order-feedback-toast! store :success "Order placed.")
        runtime (atom {:timeouts {:order-toast {:old-id :old-timeout}}})]
    (feedback-runtime/schedule-order-feedback-toast-clear!
     {:store store
      :runtime runtime
      :toast-id (:id toast)
      :clear-order-feedback-toast! feedback-runtime/clear-order-feedback-toast!
      :clear-order-feedback-toast-timeout!
      (fn
        ([] (feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
             runtime
             (fn [timeout-id]
               (swap! cleared-timeouts conj timeout-id))))
        ([toast-id] (feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
                     runtime
                     (fn [timeout-id]
                       (swap! cleared-timeouts conj timeout-id))
                     toast-id)))
      :order-feedback-toast-duration-ms 3500
      :set-timeout-fn (fn [callback _delay-ms]
                        (reset! captured-callback callback)
                        :new-timeout)})
    (is (= [] @cleared-timeouts))
    (is (= {:old-id :old-timeout
            (:id toast) :new-timeout}
           (get-in @runtime [:timeouts :order-toast])))

    (@captured-callback)
    (is (nil? (get-in @store [:ui :toast])))
    (is (empty? (get-in @store [:ui :toasts])))
    (is (= {:old-id :old-timeout}
           (get-in @runtime [:timeouts :order-toast])))))

(deftest show-order-feedback-toast-schedules-clear-only-when-message-present-test
  (let [schedule-calls (atom [])
        store (make-store)
        schedule-fn (fn [_store toast-id]
                      (swap! schedule-calls conj toast-id))]
    (feedback-runtime/show-order-feedback-toast! store :success "Order canceled." schedule-fn)
    (is (= {:kind :success
            :message "Order canceled."}
           (get-in @store [:ui :toast])))
    (is (= 1 (count @schedule-calls)))
    (is (string? (first @schedule-calls)))

    (feedback-runtime/show-order-feedback-toast!
     store
     :success
     {:headline "Bought 8.56 HYPE"
      :subline "At average price of $31.969"
      :toast-surface :trade-confirmation
      :variant :pill
      :fills [{:id "fill-1"}]}
     schedule-fn)
    (is (= "Bought 8.56 HYPE"
           (get-in @store [:ui :toast :headline])))
    (is (= "At average price of $31.969"
           (get-in @store [:ui :toast :subline])))
    (is (= {:toast-surface :trade-confirmation
            :variant :pill
            :fills [{:id "fill-1"}]}
           (select-keys (get-in @store [:ui :toast])
                        [:toast-surface :variant :fills])))
    (is (= 2 (count @schedule-calls)))

    (feedback-runtime/show-order-feedback-toast! store :error "   " schedule-fn)
    (is (nil? (get-in @store [:ui :toast])))
    (is (empty? (get-in @store [:ui :toasts])))
    (is (= 2 (count @schedule-calls)))))

(deftest show-order-feedback-toast-skips-clear-schedule-for-persistent-toast-test
  (let [store (make-store)
        schedule-calls (atom [])]
    (is (some?
         (feedback-runtime/show-order-feedback-toast!
          store
          :success
          {:message "Activity"
           :auto-timeout? false}
          (fn [& args]
            (swap! schedule-calls conj args)))))
    (is (empty? @schedule-calls))
    (is (= false
           (get-in @store [:ui :toast :auto-timeout?])))))
