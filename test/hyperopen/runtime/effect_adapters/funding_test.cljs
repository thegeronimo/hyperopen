(ns hyperopen.runtime.effect-adapters.funding-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.funding.effects :as funding-workflow-effects]
            [hyperopen.funding.history-cache :as funding-cache]
            [hyperopen.funding-comparison.effects :as funding-comparison-effects]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.funding :as funding-adapters]
            [hyperopen.runtime.effect-adapters.common :as common]
            [hyperopen.test-support.async :as async-support]))

(deftest facade-funding-adapters-delegate-to-funding-module-test
  (is (identical? funding-adapters/sync-active-asset-funding-predictability
                  effect-adapters/sync-active-asset-funding-predictability))
  (is (identical? funding-adapters/api-fetch-predicted-fundings-effect
                  effect-adapters/api-fetch-predicted-fundings-effect))
  (is (identical? funding-adapters/api-fetch-hyperunit-fee-estimate-effect
                  effect-adapters/api-fetch-hyperunit-fee-estimate-effect))
  (is (identical? funding-adapters/api-fetch-hyperunit-withdrawal-queue-effect
                  effect-adapters/api-fetch-hyperunit-withdrawal-queue-effect)))

(deftest funding-submit-wrappers-inject-order-toast-seam-test
  (let [runtime-store (atom {})
        request {:coin "BTC"}
        send-call (atom nil)
        transfer-call (atom nil)
        withdraw-call (atom nil)
        deposit-call (atom nil)]
    (letfn [(capture-send-call!
              [ctx store* request* opts]
              (reset! send-call {:ctx ctx
                                 :store store*
                                 :request request*
                                 :opts opts}))
            (capture-transfer-call!
              [ctx store* request* opts]
              (reset! transfer-call {:ctx ctx
                                     :store store*
                                     :request request*
                                     :opts opts}))
            (capture-withdraw-call!
              [ctx store* request* opts]
              (reset! withdraw-call {:ctx ctx
                                     :store store*
                                     :request request*
                                     :opts opts}))
            (capture-deposit-call!
              [ctx store* request* opts]
              (reset! deposit-call {:ctx ctx
                                    :store store*
                                    :request request*
                                    :opts opts}))]
      (with-redefs [funding-adapters/api-submit-funding-send-effect capture-send-call!
                    funding-adapters/api-submit-funding-transfer-effect capture-transfer-call!
                    funding-adapters/api-submit-funding-withdraw-effect capture-withdraw-call!
                    funding-adapters/api-submit-funding-deposit-effect capture-deposit-call!]
        (effect-adapters/api-submit-funding-send-effect nil runtime-store request)
        (effect-adapters/api-submit-funding-transfer-effect nil runtime-store request)
        (effect-adapters/api-submit-funding-withdraw-effect nil runtime-store request)
        (effect-adapters/api-submit-funding-deposit-effect nil runtime-store request)))
    (doseq [{:keys [ctx store request opts]} [@send-call
                                              @transfer-call
                                              @withdraw-call
                                              @deposit-call]]
      (is (nil? ctx))
      (is (identical? runtime-store store))
      (is (= {:coin "BTC"} request))
      (is (fn? (:show-toast! opts))))
    ((:show-toast! (:opts @send-call)) runtime-store :success "Funding submitted")
    (is (= {:kind :success
            :message "Funding submitted"}
           (get-in @runtime-store [:ui :toast])))))

(deftest sync-active-asset-funding-predictability-projects-loading-and-success-test
  (async done
    (let [store (atom {:active-assets {:contexts {}
                                       :loading false}})
          request-calls (atom [])
          start-ms (platform/now-ms)
          rows [{:time-ms (- start-ms (* 25 60 60 1000))
                 :funding-rate-raw 0.001}
                {:time-ms (- start-ms (* 60 60 1000))
                 :funding-rate-raw 0.002}]]
      (with-redefs [funding-cache/sync-market-funding-history-cache!
                    (fn [coin]
                      (swap! request-calls conj coin)
                      (js/Promise.resolve {:rows rows}))]
        (let [promise (effect-adapters/sync-active-asset-funding-predictability nil store "btc")]
          (is (= true
                 (get-in @store [:active-assets :funding-predictability :loading-by-coin "BTC"])))
          (-> promise
              (.then (fn [_]
                       (is (= ["BTC"] @request-calls))
                       (is (= false
                              (get-in @store [:active-assets :funding-predictability :loading-by-coin "BTC"])))
                       (let [summary (get-in @store [:active-assets :funding-predictability :by-coin "BTC"])
                             loaded-at-ms (get-in @store [:active-assets :funding-predictability :loaded-at-ms-by-coin "BTC"])]
                         (is (= 2 (:sample-count summary)))
                         (is (number? (:mean summary)))
                         (is (number? (:stddev summary)))
                         (is (number? loaded-at-ms))
                         (is (>= loaded-at-ms start-ms)))
                       (is (nil?
                            (get-in @store [:active-assets :funding-predictability :error-by-coin "BTC"])))
                       (done)))
              (.catch (async-support/unexpected-error done))))))))

(deftest sync-active-asset-funding-predictability-projects-error-without-clearing-last-summary-test
  (async done
    (let [store (atom {:active-assets {:contexts {}
                                       :loading false
                                       :funding-predictability {:by-coin {"BTC" {:mean 0.001}}
                                                                :loading-by-coin {}
                                                                :error-by-coin {}
                                                                :loaded-at-ms-by-coin {}}}})
          start-ms (platform/now-ms)]
      (with-redefs [funding-cache/sync-market-funding-history-cache!
                    (fn [_coin]
                      (js/Promise.reject (js/Error. "boom")))]
        (let [promise (effect-adapters/sync-active-asset-funding-predictability nil store "BTC")]
          (-> promise
              (.then (fn [_]
                       (is (= false
                              (get-in @store [:active-assets :funding-predictability :loading-by-coin "BTC"])))
                       (is (= "boom"
                              (get-in @store [:active-assets :funding-predictability :error-by-coin "BTC"])))
                       (is (= {:mean 0.001}
                              (get-in @store [:active-assets :funding-predictability :by-coin "BTC"])))
                       (let [loaded-at-ms (get-in @store [:active-assets :funding-predictability :loaded-at-ms-by-coin "BTC"])]
                         (is (number? loaded-at-ms))
                         (is (>= loaded-at-ms start-ms)))
                       (done)))
              (.catch (async-support/unexpected-error done))))))))

(deftest funding-fetch-adapters-wire-workflow-dependencies-test
  (let [store (atom {})
        calls (atom [])]
    (with-redefs [funding-comparison-effects/api-fetch-predicted-fundings!
                  (fn [deps]
                    (swap! calls conj [:predicted deps])
                    :predicted-result)
                  funding-workflow-effects/api-fetch-hyperunit-fee-estimate!
                  (fn [deps]
                    (swap! calls conj [:fee-estimate deps])
                    :fee-estimate-result)
                  funding-workflow-effects/api-fetch-hyperunit-withdrawal-queue!
                  (fn [deps]
                    (swap! calls conj [:withdrawal-queue deps])
                    :withdrawal-queue-result)]
      (is (= :predicted-result
             (funding-adapters/api-fetch-predicted-fundings-effect nil store)))
      (is (= :fee-estimate-result
             (funding-adapters/api-fetch-hyperunit-fee-estimate-effect nil store)))
      (is (= :withdrawal-queue-result
             (funding-adapters/api-fetch-hyperunit-withdrawal-queue-effect nil store))))
    (let [captured (into {} (map (juxt first second) @calls))]
      (is (= store (get-in captured [:predicted :store])))
      (is (identical? api/request-predicted-fundings!
                      (get-in captured [:predicted :request-predicted-fundings!])))
      (is (identical? api-projections/begin-funding-comparison-load
                      (get-in captured [:predicted :begin-funding-comparison-load])))
      (is (identical? api-projections/apply-funding-comparison-success
                      (get-in captured [:predicted :apply-funding-comparison-success])))
      (is (identical? api-projections/apply-funding-comparison-error
                      (get-in captured [:predicted :apply-funding-comparison-error])))

      (is (= store (get-in captured [:fee-estimate :store])))
      (is (identical? api/request-hyperunit-estimate-fees!
                      (get-in captured [:fee-estimate :request-hyperunit-estimate-fees!])))
      (is (identical? platform/now-ms
                      (get-in captured [:fee-estimate :now-ms-fn])))
      (is (identical? common/runtime-error-message
                      (get-in captured [:fee-estimate :runtime-error-message])))

      (is (= store (get-in captured [:withdrawal-queue :store])))
      (is (identical? api/request-hyperunit-withdrawal-queue!
                      (get-in captured [:withdrawal-queue :request-hyperunit-withdrawal-queue!])))
      (is (identical? platform/now-ms
                      (get-in captured [:withdrawal-queue :now-ms-fn])))
      (is (identical? common/runtime-error-message
                      (get-in captured [:withdrawal-queue :runtime-error-message])))))

(deftest sync-active-asset-funding-predictability-skips-invalid-coins-test
  (async done
    (let [store (atom {:unchanged? true})
          request-calls (atom 0)]
      (with-redefs [funding-cache/normalize-coin (constantly nil)
                    funding-cache/sync-market-funding-history-cache!
                    (fn [& _]
                      (swap! request-calls inc)
                      (js/Promise.resolve {:rows []}))]
        (-> (funding-adapters/sync-active-asset-funding-predictability nil store :invalid)
            (.then (fn [result]
                     (is (nil? result))
                     (is (= 0 @request-calls))
                     (is (= {:unchanged? true} @store))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest sync-active-asset-funding-predictability-string-error-falls-back-to-str-test
  (async done
    (let [store (atom {:active-assets {:funding-predictability {:by-coin {}
                                                                :loading-by-coin {}
                                                                :error-by-coin {}
                                                                :loaded-at-ms-by-coin {}}}})
          start-ms (platform/now-ms)]
      (with-redefs [funding-cache/sync-market-funding-history-cache!
                    (fn [_coin]
                      (js/Promise.reject "plain failure"))]
        (-> (funding-adapters/sync-active-asset-funding-predictability nil store "BTC")
            (.then (fn [_]
                     (is (= false
                            (get-in @store [:active-assets :funding-predictability :loading-by-coin "BTC"])))
                     (is (= "plain failure"
                            (get-in @store [:active-assets :funding-predictability :error-by-coin "BTC"])))
                     (let [loaded-at-ms
                           (get-in @store [:active-assets :funding-predictability :loaded-at-ms-by-coin "BTC"])]
                       (is (number? loaded-at-ms))
                       (is (>= loaded-at-ms start-ms)))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest funding-submit-adapters-inject-default-and-custom-toast-seams-test
  (let [store (atom {})
        calls (atom [])
        custom-show-toast! (fn [& _] :custom-toast)]
    (with-redefs [funding-workflow-effects/api-submit-funding-transfer!
                  (fn [deps]
                    (swap! calls conj [:transfer-default deps])
                    :transfer-result)
                  funding-workflow-effects/api-submit-funding-send!
                  (fn [deps]
                    (swap! calls conj [:send-default deps])
                    :send-result)
                  funding-workflow-effects/api-submit-funding-withdraw!
                  (fn [deps]
                    (swap! calls conj [:withdraw-default deps])
                    :withdraw-result)
                  funding-workflow-effects/api-submit-funding-deposit!
                  (fn [deps]
                    (swap! calls conj [:deposit-default deps])
                    :deposit-result)]
      (is (= :transfer-result
             (funding-adapters/api-submit-funding-transfer-effect nil store {:id :transfer-default})))
      (is (= :send-result
             (funding-adapters/api-submit-funding-send-effect nil store {:id :send-default})))
      (is (= :withdraw-result
             (funding-adapters/api-submit-funding-withdraw-effect nil store {:id :withdraw-default})))
      (is (= :deposit-result
             (funding-adapters/api-submit-funding-deposit-effect nil store {:id :deposit-default}))))
    (with-redefs [funding-workflow-effects/api-submit-funding-transfer!
                  (fn [deps]
                    (swap! calls conj [:transfer-custom deps])
                    :transfer-result)
                  funding-workflow-effects/api-submit-funding-send!
                  (fn [deps]
                    (swap! calls conj [:send-custom deps])
                    :send-result)
                  funding-workflow-effects/api-submit-funding-withdraw!
                  (fn [deps]
                    (swap! calls conj [:withdraw-custom deps])
                    :withdraw-result)
                  funding-workflow-effects/api-submit-funding-deposit!
                  (fn [deps]
                    (swap! calls conj [:deposit-custom deps])
                    :deposit-result)]
      (is (= :transfer-result
             (funding-adapters/api-submit-funding-transfer-effect
              nil
              store
              {:id :transfer-custom}
              {:show-toast! custom-show-toast!})))
      (is (= :send-result
             (funding-adapters/api-submit-funding-send-effect
              nil
              store
              {:id :send-custom}
              {:show-toast! custom-show-toast!})))
      (is (= :withdraw-result
             (funding-adapters/api-submit-funding-withdraw-effect
              nil
              store
              {:id :withdraw-custom}
              {:show-toast! custom-show-toast!})))
      (is (= :deposit-result
             (funding-adapters/api-submit-funding-deposit-effect
              nil
              store
              {:id :deposit-custom}
              {:show-toast! custom-show-toast!}))))
    (let [captured (into {} (map (juxt first second) @calls))
          transfer-default (:transfer-default captured)
          transfer-custom (:transfer-custom captured)
          send-default (:send-default captured)
          send-custom (:send-custom captured)
          withdraw-default (:withdraw-default captured)
          withdraw-custom (:withdraw-custom captured)
          deposit-default (:deposit-default captured)
          deposit-custom (:deposit-custom captured)]
      (doseq [deps [transfer-default send-default withdraw-default]]
        (is (= store (:store deps)))
        (is (identical? nxr/dispatch (:dispatch! deps)))
        (is (identical? common/exchange-response-error
                        (:exchange-response-error deps)))
        (is (identical? common/runtime-error-message
                        (:runtime-error-message deps))))
      (is (= store (:store deposit-default)))
      (is (identical? nxr/dispatch (:dispatch! deposit-default)))
      (is (identical? common/runtime-error-message
                      (:runtime-error-message deposit-default)))
      (is (nil? (:exchange-response-error deposit-default)))

      (is (nil? ((:show-toast! transfer-default) store :info "ignored")))
      (is (identical? custom-show-toast! (:show-toast! transfer-custom)))
      (is (identical? custom-show-toast! (:show-toast! send-custom)))
      (is (identical? custom-show-toast! (:show-toast! withdraw-custom)))
      (is (identical? custom-show-toast! (:show-toast! deposit-custom)))
      (is (identical? api/request-hyperunit-operations!
                      (:request-hyperunit-operations! withdraw-default)))
      (is (identical? api/request-hyperunit-withdrawal-queue!
                      (:request-hyperunit-withdrawal-queue! withdraw-default)))
      (is (identical? platform/set-timeout! (:set-timeout-fn withdraw-default)))
      (is (identical? platform/now-ms (:now-ms-fn withdraw-default)))
      (is (identical? api/request-hyperunit-operations!
                      (:request-hyperunit-operations! deposit-default)))
      (is (identical? platform/set-timeout! (:set-timeout-fn deposit-default)))
      (is (identical? platform/now-ms (:now-ms-fn deposit-default)))))))
