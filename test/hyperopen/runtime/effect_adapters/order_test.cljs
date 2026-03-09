(ns hyperopen.runtime.effect-adapters.order-test
  (:require [cljs.test :refer-macros [deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.order.effects :as order-effects]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.common :as common]
            [hyperopen.runtime.effect-adapters.order :as order-adapters]
            [hyperopen.runtime.state :as runtime-state]))

(defn- make-toast-store []
  (atom {:ui {:toast nil
              :toasts []}}))

(deftest facade-order-adapters-delegate-to-order-module-test
  (is (identical? order-adapters/api-submit-order effect-adapters/api-submit-order))
  (is (identical? order-adapters/api-cancel-order effect-adapters/api-cancel-order))
  (is (identical? order-adapters/api-submit-position-tpsl effect-adapters/api-submit-position-tpsl))
  (is (identical? order-adapters/api-submit-position-margin effect-adapters/api-submit-position-margin))
  (is (identical? order-adapters/make-api-submit-order effect-adapters/make-api-submit-order))
  (is (identical? order-adapters/make-api-cancel-order effect-adapters/make-api-cancel-order))
  (is (identical? order-adapters/make-api-submit-position-tpsl effect-adapters/make-api-submit-position-tpsl))
  (is (identical? order-adapters/make-api-submit-position-margin effect-adapters/make-api-submit-position-margin)))

(deftest order-feedback-toast-adapters-manage-store-and-runtime-timeouts-test
  (let [store (make-toast-store)
        explicit-runtime (atom {:timeouts {:order-toast {}}})
        original-order-toast (get-in @runtime-state/runtime [:timeouts :order-toast])]
    (try
      (order-adapters/show-order-feedback-toast! store :success "Placed")
      (let [default-toast-id (-> @store :ui :toasts first :id)]
        (is (string? default-toast-id))
        (is (= {:kind :success
                :message "Placed"}
               (get-in @store [:ui :toast])))
        (is (contains? (get-in @runtime-state/runtime [:timeouts :order-toast])
                       default-toast-id))
        (order-adapters/clear-order-feedback-toast-timeout!
         runtime-state/runtime
         default-toast-id)
        (is (not (contains? (get-in @runtime-state/runtime [:timeouts :order-toast])
                            default-toast-id))))
      (order-adapters/clear-order-feedback-toast! store)
      (is (nil? (get-in @store [:ui :toast])))
      (is (empty? (get-in @store [:ui :toasts])))

      (order-adapters/show-order-feedback-toast! explicit-runtime store :error "Canceled")
      (let [explicit-toast-id (-> @store :ui :toasts first :id)]
        (is (string? explicit-toast-id))
        (is (= {:kind :error
                :message "Canceled"}
               (get-in @store [:ui :toast])))
        (is (contains? (get-in @explicit-runtime [:timeouts :order-toast])
                       explicit-toast-id))
        (order-adapters/clear-order-feedback-toast-timeout! explicit-runtime)
        (is (= {} (get-in @explicit-runtime [:timeouts :order-toast]))))
      (finally
        (order-adapters/clear-order-feedback-toast! store)
        (swap! runtime-state/runtime assoc-in [:timeouts :order-toast] original-order-toast)))))

(deftest order-api-adapters-inject-runtime-aware-effect-deps-test
  (let [store (make-toast-store)
        explicit-runtime (atom {:timeouts {:order-toast {}}})
        original-order-toast (get-in @runtime-state/runtime [:timeouts :order-toast])
        calls (atom [])]
    (with-redefs [order-effects/api-submit-order
                  (fn [deps ctx store* request]
                    (swap! calls conj [:submit deps ctx store* request])
                    :submit-result)
                  order-effects/api-cancel-order
                  (fn [deps ctx store* request]
                    (swap! calls conj [:cancel deps ctx store* request])
                    :cancel-result)
                  order-effects/api-submit-position-tpsl
                  (fn [deps ctx store* request]
                    (swap! calls conj [:tpsl deps ctx store* request])
                    :tpsl-result)
                  order-effects/api-submit-position-margin
                  (fn [deps ctx store* request]
                    (swap! calls conj [:margin deps ctx store* request])
                    :margin-result)]
      (is (= :submit-result
             (order-adapters/api-submit-order :submit-default store {:id :submit-default})))
      (is (= :submit-result
             (order-adapters/api-submit-order explicit-runtime :submit-explicit store {:id :submit-explicit})))
      (is (= :cancel-result
             (order-adapters/api-cancel-order :cancel-default store {:id :cancel-default})))
      (is (= :cancel-result
             (order-adapters/api-cancel-order explicit-runtime :cancel-explicit store {:id :cancel-explicit})))
      (is (= :tpsl-result
             (order-adapters/api-submit-position-tpsl :tpsl-default store {:id :tpsl-default})))
      (is (= :tpsl-result
             (order-adapters/api-submit-position-tpsl explicit-runtime :tpsl-explicit store {:id :tpsl-explicit})))
      (is (= :margin-result
             (order-adapters/api-submit-position-margin :margin-default store {:id :margin-default})))
      (is (= :margin-result
             (order-adapters/api-submit-position-margin explicit-runtime :margin-explicit store {:id :margin-explicit}))))
    (let [captured
          (into {}
                (map (fn [[kind deps ctx store* request]]
                       [(:id request) {:kind kind
                                       :deps deps
                                       :ctx ctx
                                       :store store*}]))
                @calls)]
      (doseq [request-id [:submit-default
                          :submit-explicit
                          :cancel-default
                          :cancel-explicit
                          :tpsl-default
                          :tpsl-explicit
                          :margin-default
                          :margin-explicit]]
        (let [{deps :deps captured-store :store} (get captured request-id)]
          (is (= store captured-store))
          (is (identical? nxr/dispatch (:dispatch! deps)))
          (is (identical? common/exchange-response-error
                          (:exchange-response-error deps)))
          (is (identical? order-effects/prune-canceled-open-orders
                          (:prune-canceled-open-orders-fn deps)))
          (is (identical? common/runtime-error-message
                          (:runtime-error-message deps)))
          (is (fn? (:show-toast! deps)))))
      (try
        ((:show-toast! (:deps (get captured :submit-default))) store :success "Placed")
        (let [default-toast-id (-> @store :ui :toasts first :id)]
          (is (contains? (get-in @runtime-state/runtime [:timeouts :order-toast])
                         default-toast-id))
          (order-adapters/clear-order-feedback-toast-timeout!
           runtime-state/runtime
           default-toast-id))
        (order-adapters/clear-order-feedback-toast! store)

        ((:show-toast! (:deps (get captured :submit-explicit))) store :error "Canceled")
        (let [explicit-toast-id (-> @store :ui :toasts first :id)]
          (is (contains? (get-in @explicit-runtime [:timeouts :order-toast])
                         explicit-toast-id))
          (order-adapters/clear-order-feedback-toast-timeout!
           explicit-runtime
           explicit-toast-id))
        (finally
          (order-adapters/clear-order-feedback-toast! store)
          (swap! runtime-state/runtime assoc-in [:timeouts :order-toast] original-order-toast)
          (swap! explicit-runtime assoc-in [:timeouts :order-toast] {}))))))

(deftest order-api-factories-bind-runtime-test
  (let [runtime (atom {:timeouts {:order-toast {}}})
        store (make-toast-store)
        calls (atom [])]
    (with-redefs [order-effects/api-submit-order
                  (fn [deps ctx store* request]
                    (swap! calls conj [:submit deps ctx store* request])
                    :submit-result)
                  order-effects/api-cancel-order
                  (fn [deps ctx store* request]
                    (swap! calls conj [:cancel deps ctx store* request])
                    :cancel-result)
                  order-effects/api-submit-position-tpsl
                  (fn [deps ctx store* request]
                    (swap! calls conj [:tpsl deps ctx store* request])
                    :tpsl-result)
                  order-effects/api-submit-position-margin
                  (fn [deps ctx store* request]
                    (swap! calls conj [:margin deps ctx store* request])
                    :margin-result)]
      (is (= :submit-result
             ((order-adapters/make-api-submit-order runtime) :submit store {:id 1})))
      (is (= :cancel-result
             ((order-adapters/make-api-cancel-order runtime) :cancel store {:id 2})))
      (is (= :tpsl-result
             ((order-adapters/make-api-submit-position-tpsl runtime) :tpsl store {:id 3})))
      (is (= :margin-result
             ((order-adapters/make-api-submit-position-margin runtime) :margin store {:id 4}))))
    (is (= [[:submit :submit]
            [:cancel :cancel]
            [:tpsl :tpsl]
            [:margin :margin]]
           (mapv (fn [[kind _deps ctx _store _request]]
                   [kind ctx])
                 @calls)))
    (let [deps (-> @calls first second)]
      ((:show-toast! deps) store :success "Placed")
      (is (= 1 (count (get-in @runtime [:timeouts :order-toast]))))
      (order-adapters/clear-order-feedback-toast-timeout! runtime)
      (order-adapters/clear-order-feedback-toast! store)
      (is (= {} (get-in @runtime [:timeouts :order-toast]))))))
