(ns hyperopen.funding.effects-wrappers-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.application.hyperunit-query :as hyperunit-query]
            [hyperopen.funding.application.lifecycle-guards :as lifecycle-guards]
            [hyperopen.funding.application.lifecycle-polling :as lifecycle-polling]
            [hyperopen.funding.domain.lifecycle :as funding-lifecycle]
            [hyperopen.funding.domain.lifecycle-operations :as lifecycle-ops]
            [hyperopen.funding.effects :as effects]
            [hyperopen.ui.dialog-focus-runtime :as dialog-focus-runtime]))

(deftest funding-effect-state-and-token-helpers-cover_modal_reset_refresh_and_poll_state_test
  (let [update-funding-submit-error @#'hyperopen.funding.effects/update-funding-submit-error
        set-funding-submit-error! @#'hyperopen.funding.effects/set-funding-submit-error!
        close-funding-modal! @#'hyperopen.funding.effects/close-funding-modal!
        refresh-after-funding-submit! @#'hyperopen.funding.effects/refresh-after-funding-submit!
        resolve-hyperunit-base-url @#'hyperopen.funding.effects/resolve-hyperunit-base-url
        hyperunit-source-chain-candidates @#'hyperopen.funding.effects/hyperunit-source-chain-candidates
        install-lifecycle-poll-token! @#'hyperopen.funding.effects/install-lifecycle-poll-token!
        clear-lifecycle-poll-token! @#'hyperopen.funding.effects/clear-lifecycle-poll-token!
        lifecycle-poll-token-active? @#'hyperopen.funding.effects/lifecycle-poll-token-active?
        modal-active-for-withdraw-queue? @#'hyperopen.funding.effects/modal-active-for-withdraw-queue?
        initial-state {:funding-ui {:modal {:submitting? true
                                            :error nil
                                            :open? true
                                            :opener-data-role "funding-action-deposit"
                                            :focus-return-token 4}}}
        poll-key [:effects-test (str (js/Date.now))]
        poll-token #js {:id "token"}
        toast-calls (atom [])
        dispatch-calls (atom [])
        restore-focus-calls (atom 0)
        modal-store (atom initial-state)
        wrapper-calls (atom {})]
    (is (= {:funding-ui {:modal {:submitting? false
                                 :error "failed"
                                 :open? true
                                 :opener-data-role "funding-action-deposit"
                                 :focus-return-token 4}}}
           (update-funding-submit-error initial-state "failed")))
    (set-funding-submit-error! modal-store
                               (fn [_store kind message]
                                 (swap! toast-calls conj [kind message]))
                               "toast failure")
    (is (= false (get-in @modal-store [:funding-ui :modal :submitting?])))
    (is (= "toast failure" (get-in @modal-store [:funding-ui :modal :error])))
    (is (= [[:error "toast failure"]] @toast-calls))
    (with-redefs [dialog-focus-runtime/restore-remembered-focus! (fn
                                                                   ([] (swap! restore-focus-calls inc))
                                                                   ([_dialog-node] (swap! restore-focus-calls inc)))]
      (close-funding-modal! modal-store
                            (fn []
                              {:open? false
                               :mode nil})))
    (is (= {:open? false
            :mode nil
            :focus-return-data-role "funding-action-deposit"
            :focus-return-token 5}
           (get-in @modal-store [:funding-ui :modal])))
    (is (= 1 @restore-focus-calls))
    (refresh-after-funding-submit! modal-store
                                   (fn [_store _ctx event]
                                     (swap! dispatch-calls conj event))
                                   "0xabc")
    (refresh-after-funding-submit! modal-store
                                   (fn [_store _ctx event]
                                     (swap! dispatch-calls conj event))
                                   nil)
    (refresh-after-funding-submit! modal-store nil "0xabc")
    (is (= [[[:actions/load-user-data "0xabc"]]]
           @dispatch-calls))
    (is (= "https://api.hyperunit.xyz"
           (resolve-hyperunit-base-url
            (atom {:wallet {:chain-id "not-a-real-chain"}}))))
    (is (= ["bitcoin" "btc"]
           (hyperunit-source-chain-candidates " BTC ")))
    (is (= ["hyperliquid"]
           (hyperunit-source-chain-candidates "hyperliquid")))
    (is (= []
           (hyperunit-source-chain-candidates nil)))
    (install-lifecycle-poll-token! poll-key poll-token)
    (is (true? (lifecycle-poll-token-active? poll-key poll-token)))
    (clear-lifecycle-poll-token! poll-key poll-token)
    (is (false? (lifecycle-poll-token-active? poll-key poll-token)))
    (with-redefs [lifecycle-guards/modal-active-for-withdraw-queue?
                  (fn
                    ([store]
                     (swap! wrapper-calls assoc :one-arg store)
                     :single-arity)
                    ([store expected-asset-key]
                     (swap! wrapper-calls assoc :two-arg [store expected-asset-key])
                     :two-arity))]
      (is (= :single-arity
             (modal-active-for-withdraw-queue? modal-store)))
      (is (= :two-arity
             (modal-active-for-withdraw-queue? modal-store :usdc))))
    (is (= modal-store (:one-arg @wrapper-calls)))
    (is (= [modal-store :usdc] (:two-arg @wrapper-calls)))))

(deftest funding-effect-query-and-polling-wrappers-inject_expected_dependencies_test
  (let [select-existing-hyperunit-deposit-address
        @#'hyperopen.funding.effects/select-existing-hyperunit-deposit-address
        request-existing-hyperunit-deposit-address!
        @#'hyperopen.funding.effects/request-existing-hyperunit-deposit-address!
        prefetch-selected-hyperunit-deposit-address!
        @#'hyperopen.funding.effects/prefetch-selected-hyperunit-deposit-address!
        lifecycle-next-delay-ms @#'hyperopen.funding.effects/lifecycle-next-delay-ms
        operation->lifecycle @#'hyperopen.funding.effects/operation->lifecycle
        awaiting-lifecycle @#'hyperopen.funding.effects/awaiting-lifecycle
        awaiting-deposit-lifecycle @#'hyperopen.funding.effects/awaiting-deposit-lifecycle
        awaiting-withdraw-lifecycle @#'hyperopen.funding.effects/awaiting-withdraw-lifecycle
        fetch-hyperunit-withdrawal-queue! @#'hyperopen.funding.effects/fetch-hyperunit-withdrawal-queue!
        start-hyperunit-lifecycle-polling! @#'hyperopen.funding.effects/start-hyperunit-lifecycle-polling!
        start-hyperunit-deposit-lifecycle-polling!
        @#'hyperopen.funding.effects/start-hyperunit-deposit-lifecycle-polling!
        start-hyperunit-withdraw-lifecycle-polling!
        @#'hyperopen.funding.effects/start-hyperunit-withdraw-lifecycle-polling!
        store (atom {:wallet {:chain-id "0x66eee"}})
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
             (select-existing-hyperunit-deposit-address {:addresses []}
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
             (request-existing-hyperunit-deposit-address! "https://api.hyperunit.xyz"
                                                          ["https://api.hyperunit.xyz"]
                                                          "0xowner"
                                                          "bitcoin"
                                                          "btc")))
      (is (= ["https://api.hyperunit.xyz"]
             (get-in @seen [:request :base-urls])))
      (is (identical? effects/request-hyperunit-operations!
                      (get-in @seen [:request :deps :request-hyperunit-operations!])))
      (is (fn? (get-in @seen [:request :deps :select-existing-hyperunit-deposit-address])))

      (is (= :prefetched-result
             (prefetch-selected-hyperunit-deposit-address! store)))
      (is (identical? store
                      (get-in @seen [:prefetch :store])))
      (is (identical? funding-actions/funding-modal-view-model
                      (get-in @seen [:prefetch :deps :funding-modal-view-model-fn])))
      (is (fn? (get-in @seen [:prefetch :deps :request-existing-hyperunit-deposit-address!])))

      (is (= :delay-result
             (lifecycle-next-delay-ms 1000 {:status "pending"})))
      (is (= {:default-delay-ms 3000
              :min-delay-ms 1000
              :max-delay-ms 60000}
             (get-in @seen [:delay :config])))

      (is (= :operation-result
             (operation->lifecycle {:status "processing"} :deposit :usdc 2500)))
      (is (identical? funding-lifecycle/normalize-hyperunit-lifecycle
                      (get-in @seen [:operation :normalize-lifecycle])))
      (is (= :deposit
             (get-in @seen [:operation :direction])))

      (is (= :awaiting-result
             (awaiting-lifecycle :withdraw :usdc 3500)))
      (is (= {:direction :withdraw
              :asset-key :usdc
              :now-ms 3500}
             (select-keys (get @seen :awaiting)
                          [:direction :asset-key :now-ms])))

      (is (= :awaiting-deposit-result
             (awaiting-deposit-lifecycle :btc 4500)))
      (is (= :btc
             (get-in @seen [:awaiting-deposit :asset-key])))

      (is (= :awaiting-withdraw-result
             (awaiting-withdraw-lifecycle :eth 5500)))
      (is (= :eth
             (get-in @seen [:awaiting-withdraw :asset-key])))

      (is (= :withdrawal-queue-result
             (fetch-hyperunit-withdrawal-queue! {:store store
                                                 :base-url "https://api.hyperunit-testnet.xyz"})))
      (is (identical? funding-lifecycle/normalize-hyperunit-withdrawal-queue
                      (get-in @seen [:withdrawal-queue :deps :normalize-hyperunit-withdrawal-queue])))
      (is (identical? store
                      (get-in @seen [:withdrawal-queue :opts :store])))

      (is (= :poll-result
             (start-hyperunit-lifecycle-polling! {:store store
                                                  :direction :deposit})))
      (is (= :deposit-poll-result
             (start-hyperunit-deposit-lifecycle-polling! {:store store})))
      (is (= :withdraw-poll-result
             (start-hyperunit-withdraw-lifecycle-polling! {:store store})))
      (doseq [poll-key [:poll :deposit-poll :withdraw-poll]]
        (is (= 3000
               (get-in @seen [poll-key :default-poll-delay-ms])))
        (is (fn? (get-in @seen [poll-key :lifecycle-poll-key-fn])))
        (is (fn? (get-in @seen [poll-key :fetch-hyperunit-withdrawal-queue!])))))))
