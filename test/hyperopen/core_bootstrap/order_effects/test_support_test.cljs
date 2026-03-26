(ns hyperopen.core-bootstrap.order-effects.test-support-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.default :as api]
            [hyperopen.core-bootstrap.order-effects.test-support :as support]
            [hyperopen.test-support.async :as async-support]))

(deftest helper-error-and-toast-fallbacks-test
  (let [store (atom {:ui {:toast nil}})]
    (support/test-show-toast! store :success "Submitted")
    (is (= {:kind :success
            :message "Submitted"}
           (get-in @store [:ui :toast])))

    (is (= "nested-data"
           (support/test-exchange-response-error
            {:response {:data "nested-data"
                        :error "nested-error"}
             :error "outer-error"
             :status "ignored"})))
    (is (= "nested-error"
           (support/test-exchange-response-error
            {:response {:error "nested-error"}
             :error "outer-error"
             :status "ignored"})))
    (is (= "outer-error"
           (support/test-exchange-response-error
            {:error "outer-error"
             :status "ignored"})))
    (is (= 503
           (support/test-exchange-response-error
            {:status 503})))

    (is (= "boom"
           (support/test-runtime-error-message (js/Error. "boom"))))
    (is (= "{:kind :timeout}"
           (support/test-runtime-error-message {:kind :timeout})))))

(deftest position-submit-deps-wire-helpers-and-dispatch-test
  (let [dispatched (atom [])
        store (atom (support/base-position-store :margin-modal))
        deps (support/position-submit-deps dispatched)]
    ((:dispatch! deps) store nil [[:actions/demo]])
    ((:show-toast! deps) store :info "Queued")

    (is (= [[[:actions/demo]]]
           @dispatched))
    (is (= {:kind :info
            :message "Queued"}
           (get-in @store [:ui :toast])))
    (is (= "nested"
           ((:exchange-response-error deps)
            {:response {:data "nested"}
             :error "outer"})))
    (is (= "transport"
           ((:runtime-error-message deps) (js/Error. "transport"))))))

(deftest install-account-refresh-mocks-exercises-all-arities-and-restores-test
  (async done
    (let [refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-request-open-orders api/request-frontend-open-orders!
          original-request-clearinghouse-state api/request-clearinghouse-state!
          original-ensure-perp-dexs-data api/ensure-perp-dexs-data!
          restore-mocks! (support/install-account-refresh-mocks! refresh-calls
                                                                 clearinghouse-calls
                                                                 ["dex-a" "dex-b"])
          fail! (fn [err]
                  (restore-mocks!)
                  ((async-support/unexpected-error done) err))]
      (-> (js/Promise.all
           #js[(api/request-frontend-open-orders! "0xabc")
               (api/request-frontend-open-orders! "0xabc" {:dex "dex-a"
                                                           :priority :low})
               (api/request-frontend-open-orders! "0xabc" "dex-b" {:priority :high})
               (api/request-clearinghouse-state! "0xabc" "dex-a")
               (api/request-clearinghouse-state! "0xabc" "dex-b" {:priority :low})
               (api/ensure-perp-dexs-data! nil)
               (api/ensure-perp-dexs-data! :store {:priority :low})])
          (.then (fn [results]
                   (try
                     (is (= []
                            (aget results 0)))
                     (is (= []
                            (aget results 1)))
                     (is (= []
                            (aget results 2)))
                     (is (= {:assetPositions []}
                            (aget results 3)))
                     (is (= {:assetPositions []}
                            (aget results 4)))
                     (is (= ["dex-a" "dex-b"]
                            (aget results 5)))
                     (is (= ["dex-a" "dex-b"]
                            (aget results 6)))

                     (is (= [["0xabc" nil {}]
                             ["0xabc" "dex-a" {:priority :low}]
                             ["0xabc" "dex-b" {:priority :high}]]
                            @refresh-calls))
                     (is (= [["0xabc" "dex-a" {}]
                             ["0xabc" "dex-b" {:priority :low}]]
                            @clearinghouse-calls))
                     (finally
                       (restore-mocks!)
                       (is (identical? original-request-open-orders
                                       api/request-frontend-open-orders!))
                       (is (identical? original-request-clearinghouse-state
                                       api/request-clearinghouse-state!))
                       (is (identical? original-ensure-perp-dexs-data
                                       api/ensure-perp-dexs-data!))
                       (done)))))
          (.catch fail!)))))

(deftest base-store-builders-cover-default-and-override-arities-test
  (let [submit-default (support/base-submit-order-store)
        submit-override (support/base-submit-order-store
                         {:wallet {:address "0xdef"
                                   :agent {:status :ready}}
                          :ui {:toast {:kind :info}}})
        cancel-default (support/base-cancel-order-store)
        cancel-override (support/base-cancel-order-store
                         {:orders {:open-orders [{:order {:oid 22}}]}
                          :ui {:toast {:kind :error}}})
        position-default (support/base-position-store :tpsl-modal)
        position-override (support/base-position-store
                           :margin-modal
                           {:positions-ui {:margin-modal {:submitting? true
                                                          :error "old-error"}}
                            :wallet {:address "0xdef"
                                     :agent {:status :ready}}})]
    (is (= "0xabc"
           (get-in submit-default [:wallet :address])))
    (is (= {:submitting? false
            :error nil}
           (:order-form-runtime submit-default)))
    (is (= {:kind :info}
           (get-in submit-override [:ui :toast])))

    (is (= []
           (get-in cancel-default [:orders :open-orders])))
    (is (= {}
           (get-in cancel-default [:orders :open-orders-snapshot-by-dex])))
    (is (= [{:order {:oid 22}}]
           (get-in cancel-override [:orders :open-orders])))
    (is (= {:kind :error}
           (get-in cancel-override [:ui :toast])))

    (is (= {:submitting? false
            :error nil}
           (get-in position-default [:positions-ui :tpsl-modal])))
    (is (= "0xdef"
           (get-in position-override [:wallet :address])))
    (is (= {:submitting? true
            :error "old-error"}
           (get-in position-override [:positions-ui :margin-modal])))))
