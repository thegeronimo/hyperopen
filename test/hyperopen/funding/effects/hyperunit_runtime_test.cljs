(ns hyperopen.funding.effects.hyperunit-runtime-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.application.hyperunit-query :as hyperunit-query]
            [hyperopen.funding.application.lifecycle-guards :as lifecycle-guards]
            [hyperopen.funding.application.lifecycle-polling :as lifecycle-polling]
            [hyperopen.funding.domain.lifecycle :as funding-lifecycle]
            [hyperopen.funding.domain.lifecycle-operations :as lifecycle-ops]
            [hyperopen.funding.effects.hyperunit-runtime :as hyperunit-runtime]))

(deftest funding-effect-hyperunit-runtime-token-and-modal-wrappers-cover_poll_state_and_overloads-test
  (let [poll-key [:effects-test (str (js/Date.now))]
        poll-token #js {:id "token"}
        modal-store (atom {})
        wrapper-calls (atom {})]
    (hyperunit-runtime/install-lifecycle-poll-token! poll-key poll-token)
    (is (true? (hyperunit-runtime/lifecycle-poll-token-active? poll-key poll-token)))
    (hyperunit-runtime/clear-lifecycle-poll-token! poll-key poll-token)
    (is (false? (hyperunit-runtime/lifecycle-poll-token-active? poll-key poll-token)))
    (with-redefs [lifecycle-guards/modal-active-for-withdraw-queue?
                  (fn
                    ([store]
                     (swap! wrapper-calls assoc :one-arg store)
                     :single-arity)
                    ([store expected-asset-key]
                     (swap! wrapper-calls assoc :two-arg [store expected-asset-key])
                     :two-arity))]
      (is (= :single-arity
             (hyperunit-runtime/modal-active-for-withdraw-queue? modal-store)))
      (is (= :two-arity
             (hyperunit-runtime/modal-active-for-withdraw-queue? modal-store :usdc))))
    (is (= modal-store (:one-arg @wrapper-calls)))
    (is (= [modal-store :usdc] (:two-arg @wrapper-calls)))))

(deftest funding-effect-hyperunit-runtime-query-and-polling-wrappers-inject-expected-dependencies-test
  (let [store (atom {:wallet {:chain-id "0x66eee"}})
        seen (atom {})]
    (with-redefs [hyperunit-query/select-existing-hyperunit-deposit-address
                  (fn [deps operations-response source-chain asset destination-address]
                    (swap! seen assoc
                           :select
                           {:deps deps
                            :operations-response operations-response
                            :source-chain source-chain
                            :asset asset
                            :destination-address destination-address})
                    :selected-result)
                  hyperunit-query/request-existing-hyperunit-deposit-address!
                  (fn [deps base-url base-urls destination-address source-chain asset]
                    (swap! seen assoc
                           :request
                           {:deps deps
                            :base-url base-url
                            :base-urls base-urls
                            :destination-address destination-address
                            :source-chain source-chain
                            :asset asset})
                    :requested-result)
                  hyperunit-query/prefetch-selected-hyperunit-deposit-address!
                  (fn [deps store*]
                    (swap! seen assoc
                           :prefetch
                           {:deps deps
                            :store store*})
                    :prefetched-result)
                  lifecycle-ops/lifecycle-next-delay-ms
                  (fn [config now-ms lifecycle]
                    (swap! seen assoc
                           :delay
                           {:config config
                            :now-ms now-ms
                            :lifecycle lifecycle})
                    :delay-result)
                  lifecycle-ops/operation->lifecycle
                  (fn [normalize-lifecycle operation direction asset-key now-ms]
                    (swap! seen assoc
                           :operation
                           {:normalize-lifecycle normalize-lifecycle
                            :operation operation
                            :direction direction
                            :asset-key asset-key
                            :now-ms now-ms})
                    :operation-result)
                  lifecycle-ops/awaiting-lifecycle
                  (fn [normalize-lifecycle direction asset-key now-ms]
                    (swap! seen assoc
                           :awaiting
                           {:normalize-lifecycle normalize-lifecycle
                            :direction direction
                            :asset-key asset-key
                            :now-ms now-ms})
                    :awaiting-result)
                  lifecycle-ops/awaiting-deposit-lifecycle
                  (fn [normalize-lifecycle asset-key now-ms]
                    (swap! seen assoc
                           :awaiting-deposit
                           {:normalize-lifecycle normalize-lifecycle
                            :asset-key asset-key
                            :now-ms now-ms})
                    :awaiting-deposit-result)
                  lifecycle-ops/awaiting-withdraw-lifecycle
                  (fn [normalize-lifecycle asset-key now-ms]
                    (swap! seen assoc
                           :awaiting-withdraw
                           {:normalize-lifecycle normalize-lifecycle
                            :asset-key asset-key
                            :now-ms now-ms})
                    :awaiting-withdraw-result)
                  hyperunit-query/fetch-hyperunit-withdrawal-queue!
                  (fn [deps opts]
                    (swap! seen assoc
                           :withdrawal-queue
                           {:deps deps
                            :opts opts})
                    :withdrawal-queue-result)
                  lifecycle-polling/start-hyperunit-lifecycle-polling!
                  (fn [opts]
                    (swap! seen assoc :poll opts)
                    :poll-result)
                  lifecycle-polling/start-hyperunit-deposit-lifecycle-polling!
                  (fn [opts]
                    (swap! seen assoc :deposit-poll opts)
                    :deposit-poll-result)
                  lifecycle-polling/start-hyperunit-withdraw-lifecycle-polling!
                  (fn [opts]
                    (swap! seen assoc :withdraw-poll opts)
                    :withdraw-poll-result)]
      (is (= :selected-result
             (hyperunit-runtime/select-existing-hyperunit-deposit-address
              {:addresses []}
              "bitcoin"
              "btc"
              "0xowner")))
      (is (= {:addresses []}
             (get-in @seen [:select :operations-response])))
      (is (= "0xowner"
             (get-in @seen [:select :destination-address])))
      (is (= #{"arbitrum" "bitcoin" "ethereum" "hyperliquid" "monad" "plasma" "solana"}
             (get-in @seen [:select :deps :known-source-chain-tokens])))
      (is (fn? (get-in @seen [:select :deps :protocol-address-matches-source-chain?])))

      (is (= :requested-result
             (hyperunit-runtime/request-existing-hyperunit-deposit-address!
              "https://api.hyperunit.xyz"
              ["https://api.hyperunit.xyz"]
              "0xowner"
              "bitcoin"
              "btc")))
      (is (= ["https://api.hyperunit.xyz"]
             (get-in @seen [:request :base-urls])))
      (is (identical? hyperunit-runtime/request-hyperunit-operations!
                      (get-in @seen [:request :deps :request-hyperunit-operations!])))
      (is (fn? (get-in @seen [:request :deps :select-existing-hyperunit-deposit-address])))

      (is (= :prefetched-result
             (hyperunit-runtime/prefetch-selected-hyperunit-deposit-address! store)))
      (is (identical? store
                      (get-in @seen [:prefetch :store])))
      (is (identical? funding-actions/funding-modal-view-model
                      (get-in @seen [:prefetch :deps :funding-modal-view-model-fn])))
      (is (fn? (get-in @seen [:prefetch :deps :request-existing-hyperunit-deposit-address!])))

      (is (= :delay-result
             (hyperunit-runtime/lifecycle-next-delay-ms 1000 {:status "pending"})))
      (is (= {:default-delay-ms 3000
              :min-delay-ms 1000
              :max-delay-ms 60000}
             (get-in @seen [:delay :config])))

      (is (= :operation-result
             (hyperunit-runtime/operation->lifecycle {:status "processing"} :deposit :usdc 2500)))
      (is (identical? funding-lifecycle/normalize-hyperunit-lifecycle
                      (get-in @seen [:operation :normalize-lifecycle])))
      (is (= :deposit
             (get-in @seen [:operation :direction])))

      (is (= :awaiting-result
             (hyperunit-runtime/awaiting-lifecycle :withdraw :usdc 3500)))
      (is (= {:direction :withdraw
              :asset-key :usdc
              :now-ms 3500}
             (select-keys (get @seen :awaiting)
                          [:direction :asset-key :now-ms])))

      (is (= :awaiting-deposit-result
             (hyperunit-runtime/awaiting-deposit-lifecycle :btc 4500)))
      (is (= :btc
             (get-in @seen [:awaiting-deposit :asset-key])))

      (is (= :awaiting-withdraw-result
             (hyperunit-runtime/awaiting-withdraw-lifecycle :eth 5500)))
      (is (= :eth
             (get-in @seen [:awaiting-withdraw :asset-key])))

      (is (= :withdrawal-queue-result
             (hyperunit-runtime/fetch-hyperunit-withdrawal-queue!
              {:store store
               :base-url "https://api.hyperunit-testnet.xyz"})))
      (is (identical? funding-lifecycle/normalize-hyperunit-withdrawal-queue
                      (get-in @seen [:withdrawal-queue :deps :normalize-hyperunit-withdrawal-queue])))
      (is (identical? store
                      (get-in @seen [:withdrawal-queue :opts :store])))

      (is (= :poll-result
             (hyperunit-runtime/start-hyperunit-lifecycle-polling! {:store store
                                                                    :direction :deposit})))
      (is (= :deposit-poll-result
             (hyperunit-runtime/start-hyperunit-deposit-lifecycle-polling! {:store store})))
      (is (= :withdraw-poll-result
             (hyperunit-runtime/start-hyperunit-withdraw-lifecycle-polling! {:store store})))
      (doseq [poll-key [:poll :deposit-poll :withdraw-poll]]
        (is (= 3000
               (get-in @seen [poll-key :default-poll-delay-ms])))
        (is (fn? (get-in @seen [poll-key :lifecycle-poll-key-fn])))
        (is (fn? (get-in @seen [poll-key :fetch-hyperunit-withdrawal-queue!])))))))
