(ns hyperopen.runtime.effect-adapters.funding-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.history-cache :as funding-cache]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.funding :as funding-adapters]
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
        transfer-call (atom nil)
        withdraw-call (atom nil)
        deposit-call (atom nil)]
    (letfn [(capture-transfer-call!
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
      (with-redefs [funding-adapters/api-submit-funding-transfer-effect capture-transfer-call!
                    funding-adapters/api-submit-funding-withdraw-effect capture-withdraw-call!
                    funding-adapters/api-submit-funding-deposit-effect capture-deposit-call!]
        (effect-adapters/api-submit-funding-transfer-effect nil runtime-store request)
        (effect-adapters/api-submit-funding-withdraw-effect nil runtime-store request)
        (effect-adapters/api-submit-funding-deposit-effect nil runtime-store request)))
    (doseq [{:keys [ctx store request opts]} [@transfer-call
                                              @withdraw-call
                                              @deposit-call]]
      (is (nil? ctx))
      (is (identical? runtime-store store))
      (is (= {:coin "BTC"} request))
      (is (fn? (:show-toast! opts))))
    ((:show-toast! (:opts @transfer-call)) runtime-store :success "Funding submitted")
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
