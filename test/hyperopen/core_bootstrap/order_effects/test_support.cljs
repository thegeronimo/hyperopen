(ns hyperopen.core-bootstrap.order-effects.test-support
  (:require [clojure.string :as str]
            [hyperopen.api.default :as api]
            [hyperopen.core-bootstrap.test-support.fixtures :as fixtures]))

(def clear-order-feedback-toast-timeout!
  fixtures/clear-order-feedback-toast-timeout!)

(def twap-cancel-request
  {:action {:type "twapCancel"
            :a 12
            :t 77}})

(defn test-show-toast!
  [store kind message]
  (swap! store assoc-in [:ui :toast] {:kind kind
                                      :message message}))

(defn test-exchange-response-error
  [resp]
  (or (get-in resp [:response :data])
      (get-in resp [:response :error])
      (:error resp)
      (:status resp)))

(defn test-runtime-error-message
  [err]
  (or (.-message err)
      (str err)))

(defn position-submit-deps
  [dispatched]
  {:dispatch! (fn [_store _evt actions]
                (swap! dispatched conj actions))
   :exchange-response-error test-exchange-response-error
   :runtime-error-message test-runtime-error-message
   :show-toast! test-show-toast!})

(defn install-account-refresh-mocks!
  [refresh-calls clearinghouse-calls dex-names]
  (let [original-request-open-orders api/request-frontend-open-orders!
        original-request-clearinghouse-state api/request-clearinghouse-state!
        original-ensure-perp-dexs-data api/ensure-perp-dexs-data!]
    (set! api/request-frontend-open-orders!
          (fn request-frontend-open-orders-mock
            ([address]
             (request-frontend-open-orders-mock address {}))
            ([address opts]
             (request-frontend-open-orders-mock address (:dex opts) (dissoc opts :dex)))
            ([address dex opts]
             (swap! refresh-calls conj [address dex opts])
             (js/Promise.resolve []))))
    (set! api/request-clearinghouse-state!
          (fn request-clearinghouse-state-mock
            ([address dex]
             (request-clearinghouse-state-mock address dex {}))
            ([address dex opts]
             (swap! clearinghouse-calls conj [address dex opts])
             (js/Promise.resolve {:assetPositions []}))))
    (set! api/ensure-perp-dexs-data!
          (fn ensure-perp-dexs-data-mock
            ([_store]
             (ensure-perp-dexs-data-mock nil {}))
            ([_store _opts]
             (js/Promise.resolve (vec dex-names)))))
    (fn restore-account-refresh-mocks! []
      (set! api/request-frontend-open-orders! original-request-open-orders)
      (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
      (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data))))

(defn base-submit-order-store
  ([] (base-submit-order-store {}))
  ([overrides]
   (merge {:wallet {:address "0xabc"
                    :agent {:status :ready}}
           :order-form {}
           :order-form-runtime {:submitting? false
                                :error nil}
           :ui {:toast nil}}
          overrides)))

(defn base-cancel-order-store
  ([] (base-cancel-order-store {}))
  ([overrides]
   (merge {:wallet {:address "0xabc"
                    :agent {:status :ready}}
           :orders {:open-orders []
                    :open-orders-snapshot []
                    :open-orders-snapshot-by-dex {}}
           :ui {:toast nil}}
          overrides)))

(defn base-position-store
  ([modal-key]
   (base-position-store modal-key {}))
  ([modal-key overrides]
   (merge {:wallet {:address "0xabc"
                    :agent {:status :ready}}
           :positions-ui {modal-key {:submitting? false
                                     :error nil}}
           :ui {:toast nil}}
          overrides)))
