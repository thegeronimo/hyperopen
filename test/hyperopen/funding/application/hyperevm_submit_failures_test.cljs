(ns hyperopen.funding.application.hyperevm-submit-failures-test
  "How a HyperEVM -> Core submit ends when a read fails, an estimate is
   refused, a receipt never comes or the wallet answers oddly. Each case
   pins the result, what reached `eth_sendTransaction` and the hashes,
   through the real wiring against a fake wallet and RPC."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.application.hyperevm-submit :as hyperevm-submit]
            [hyperopen.funding.application.hyperevm-transfer-effects :as transfer-effects]
            [hyperopen.funding.domain.transfer-run :as transfer-run]
            [hyperopen.funding.test-support.hyperevm-effects :as h]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.funding.test-support.hyperevm-wallet :as fake]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]
            [hyperopen.test-support.async :as async-support]))

(defn- sends
  [log]
  (fake/sent-transactions log))

(defn- ends
  [result]
  (select-keys result [:status :step :kind :hashes]))

(deftest reads-that-fail-before-the-wallet-is-asked-stop-the-flow-test
  (async done
    (-> (js/Promise.all
         #js [(fake/submit! support/hype-index "10" {} {:gas-price-error? true})
              (fake/submit! support/usdc-index "1000" {} {:allowance-error? true})])
        (.then (fn [results]
                 (let [[gas-price allowance] (vec results)]
                   (is (= {:status "err" :step nil :kind :read-failed :hashes []}
                          (ends (:result gas-price))))
                   (is (= (:gas-price transfer-run/messages) (:error (:result gas-price))))
                   (is (= {:status "err" :step nil :kind :read-failed :hashes []}
                          (ends (:result allowance))))
                   (is (= (:allowance transfer-run/messages) (:error (:result allowance))))
                   (doseq [{:keys [log]} [gas-price allowance]]
                     (is (= [] (fake/wallet-methods log)) "the wallet is never asked"))
                   (done))))
        (.catch (async-support/unexpected-error done)))))

(deftest a-failed-chain-read-before-the-deposit-sends-nothing-more-test
  (async done
    ;; Reads: before the switch, the verified switch, then the deposit's.
    (-> (fake/submit! support/usdc-index "1000" {:chain-reads ["0xa4b1" "0x3e7" :error]} {})
        (.then (fn [{:keys [result log]}]
                 (is (= {:status "err" :step :deposit :kind :wallet :hashes [(fake/tx-hash 1)]}
                        (ends result))
                     "the approve confirmed, so nothing is left unresolved")
                 (is (re-find #"^Couldn't read your wallet's network" (:error result)))
                 (is (= 1 (count (sends log))) "only the approve was sent")
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest a-deposit-estimate-that-reverts-after-the-approve-fails-not-pending-test
  (async done
    (-> (fake/submit! support/usdc-index "1000" {}
                      {:estimates ["0xb71b" {:error {:code 3 :message "execution reverted: paused"}}]})
        (.then (fn [{:keys [result log]}]
                 (is (= {:status "err" :step :deposit :kind :estimate-reverted :hashes [(fake/tx-hash 1)]}
                        (ends result)))
                 (is (= "HyperEVM would reject this transfer: execution reverted: paused."
                        (:error result)))
                 (is (= 1 (count (sends log))) "the approve is kept; the deposit never reached the wallet")
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest an-approve-whose-receipt-never-arrives-ends-pending-on-the-approve-test
  (async done
    (-> (fake/submit! support/usdc-index "1000" {}
                      {:receipts {(fake/tx-hash 1) [nil]}}
                      {:now-ms-fn (fake/stepping-clock 60000)})
        (.then (fn [{:keys [result log records]}]
                 (is (= {:status "pending" :step :approve :hashes [(fake/tx-hash 1)]}
                        (ends result)))
                 (is (= (fake/tx-hash 1) (:txHash result)))
                 (is (= 1 (count (sends log))) "no deposit follows an unconfirmed approve")
                 (is (= [{:hash (fake/tx-hash 1) :step :approve}]
                        (mapv #(select-keys % [:hash :step]) (filter :hash records))))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest bookkeeping-that-throws-after-a-confirmed-receipt-never-fails-the-move-test
  (async done
    ;; A validation build asserts app state on every store swap, so the
    ;; in-flight write after a receipt can throw. The transaction confirmed:
    ;; the move must still end ok, never as a retryable failure.
    (-> (js/Promise.all
         #js [(fake/submit! support/hype-index "10" {} {} {:throw-on-confirmed-record? true})
              (fake/submit! support/usdc-index "1000" {} {} {:throw-on-confirmed-record? true})])
        (.then (fn [results]
                 (let [[hype usdc] (vec results)]
                   (is (= {:status "ok" :hashes [(fake/tx-hash 1)]}
                          (select-keys (:result hype) [:status :hashes])))
                   (is (= {:status "ok" :hashes [(fake/tx-hash 1) (fake/tx-hash 2)]}
                          (select-keys (:result usdc) [:status :hashes]))
                       "the deposit still follows the confirmed approve")
                   (is (some #{[:send :done]} (:steps hype)) "the send step still reads done")
                   (done))))
        (.catch (async-support/unexpected-error done)))))

(deftest node-errors-on-the-estimate-fall-back-to-the-gas-table-test
  (async done
    (-> (js/Promise.all
         #js [(fake/submit! support/hype-index "10" {}
                            {:estimates [{:error {:code -32603 :message "internal error"}}]})
              (fake/submit! support/hype-index "10" {}
                            {:estimates [{:error {:code -32000 :message "header not found"}}]})
              (fake/submit! support/hype-index "10" {}
                            {:estimates [{:error {:code -32000
                                                  :message "insufficient funds for gas * price + value"}}]})])
        (.then (fn [results]
                 (let [[internal header funds] (vec results)]
                   (doseq [{:keys [result log]} [internal header]]
                     (is (= "ok" (:status result)))
                     (is (= "0x7530" (:gas (first (sends log))))
                         "a node hiccup falls back to the native table (30000)"))
                   (is (= {:status "err" :kind :estimate-reverted} (select-keys (:result funds) [:status :kind]))
                       "a sender that can't pay is refused before the wallet")
                   (is (= [] (sends (:log funds))))
                   (done))))
        (.catch (async-support/unexpected-error done)))))

;; --- through the effect -----------------------------------------------------------

(defn- effect-submit!
  "Run `submit-evm-to-core!` with the real submitter over a fake wallet
   (`wallet-config`) and RPC. Resolves with `{:store :log :toasts}`."
  [wallet-config]
  (let [log (atom [])
        store (h/store-for (assoc h/hype-to-spot :amount-input "10"))
        wallet (fake/fake-wallet log wallet-config)
        submit-fn (fn [store* owner action opts]
                    (hyperevm-submit/submit-hyperevm-to-core!
                     (fake/submit-deps store* {:provider (:provider wallet)
                                               :fetch-fn (fake/fake-rpc log {})})
                     owner action opts))
        {:keys [deps toasts]} (h/harness store (h/evm->core @store support/hype-index "10")
                                         {:submit-hyperevm-to-core! submit-fn})]
    (-> (transfer-effects/submit-evm-to-core! deps)
        (.then (fn [_] {:store store :log @log :toasts @toasts :deps deps})))))

(deftest a-send-answered-without-a-hash-stays-unresolved-test
  (async done
    (-> (effect-submit! {:send-answers {1 nil}})
        (.then (fn [{:keys [store log toasts deps]}]
                 (is (= 1 (count (sends log))) "the wallet was asked once")
                 (is (= :unconfirmed
                        (:status (transfer-state/in-flight-entry @store support/owner)))
                     "the entry stays: the transaction may have been broadcast")
                 (is (= [] (transfer-state/pending-in-flight @store)) "nothing to poll for")
                 (is (= {:phase :failed :error (:no-hash transfer-run/messages) :maybe-sent? true}
                        (select-keys (h/run-of store) [:phase :error :maybe-sent?])))
                 (is (not-any? #(= (:no-hash transfer-run/messages) (second %)) toasts)
                     "the open failed view says it; no toast over it")
                 ;; Back to edit, then another move: refused.
                 (swap! store assoc-in [:funding-ui :modal :transfer-evm] nil)
                 (let [seen (atom [])]
                   (transfer-effects/submit-evm-to-core!
                    (assoc deps :submit-hyperevm-to-core! (h/stub-submitter seen [] {:status "ok"})))
                   (is (= [] @seen))
                   (is (= transfer-state/unconfirmed-message
                          (get-in @store [:funding-ui :modal :error]))))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest a-wallet-that-switched-elsewhere-is-not-cached-as-unable-to-switch-test
  (async done
    (-> (js/Promise.all
         #js [(effect-submit! {:chain-reads ["0xa4b1" "0x1"]})
              (effect-submit! {:switch-noop? true})])
        (.then (fn [results]
                 (let [[elsewhere noop] (vec results)]
                   (is (= [] (sends (:log elsewhere))))
                   (is (= (:switch-elsewhere transfer-run/messages) (:error (h/run-of (:store elsewhere)))))
                   (is (not (transfer-state/chain-switch-unsupported? @(:store elsewhere))))
                   (is (= [:failed :pending] (mapv :status (:steps (h/run-of (:store elsewhere))))))
                   (is (transfer-state/chain-switch-unsupported? @(:store noop))
                       "a switch that did not move is still remembered")
                   (done))))
        (.catch (async-support/unexpected-error done)))))
