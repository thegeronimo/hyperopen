(ns hyperopen.funding.application.hyperevm-submit-test
  "HyperEVM -> Core submits through the real wiring (`wallet-rpc`, the RPC
   client, `hyperevm-runtime/submit-deps`) against a fake wallet and a fake
   RPC. Each scenario pins what reaches the wallet, in order, because that
   is what moves funds."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.application.hyperevm-submit :as hyperevm-submit]
            [hyperopen.funding.application.hyperevm-transfer-effects :as transfer-effects]
            [hyperopen.funding.application.modal-state :as modal-state]
            [hyperopen.funding.test-support.effects :as effects-support]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.funding.test-support.hyperevm-wallet :as fake]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]
            [hyperopen.test-support.async :as async-support]))

(def ^:private hype-system "0x2222222222222222222222222222222222222222")
(def ^:private usdc-token "0xb88339cb7199b77e23db6e890353e22632ba630f")
(def ^:private deposit-wallet "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24")
(def ^:private purr-system "0x2000000000000000000000000000000000000001")

(def ^:private request fake/request)

(def ^:private submit! fake/submit!)

(defn- sends
  [log]
  (fake/sent-transactions log))

(deftest native-hype-switches-verifies-and-sends-one-pinned-transaction-test
  (async done
    (-> (submit! support/hype-index "10" {} {})
        (.then (fn [{:keys [result log steps records]}]
                 (is (= "ok" (:status result)))
                 (is (= (fake/tx-hash 1) (:txHash result)))
                 (is (= ["eth_chainId" "wallet_switchEthereumChain" "eth_chainId" "eth_sendTransaction"]
                        (fake/wallet-methods log))
                     "the verified switch's re-read is the chain check right before the send")
                 (is (= {:from support/owner
                         :to hype-system
                         :value "0x8ac7230489e80000"
                         :chainId "0x3e7"
                         :gas "0x6aa4"
                         :maxFeePerGas "0x17d78400"
                         :maxPriorityFeePerGas "0x0"}
                        (first (sends log)))
                     "10 HYPE, chain-pinned, estimate (0x5208) x 1.3 gas, 2 x 0.2 gwei max fee, no data")
                 (is (= [[:switch-network :done] [:send :active] [:send :confirming] [:send :done]]
                        steps))
                 (is (= [{:hash (fake/tx-hash 1) :step :send} {:waiting-receipt? false}]
                        (mapv #(select-keys % [:hash :step :waiting-receipt?]) records))
                     "the hash is recorded before its receipt is awaited")
                 (let [record-at (.indexOf (mapv first log) :record)
                       receipt-at (.indexOf (mapv second log) "eth_getTransactionReceipt")]
                   (is (< record-at receipt-at)))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest usdc-approves-then-deposits-and-estimates-the-deposit-after-the-approve-test
  (async done
    (-> (submit! support/usdc-index "1000" {} {})
        (.then (fn [{:keys [result log steps]}]
                 (is (= "ok" (:status result)))
                 (is (= [{:to usdc-token
                          :data (fake/call-data :approve deposit-wallet (js/BigInt 1000000000))}
                         {:to deposit-wallet
                          :data (fake/call-data :deposit (js/BigInt 1000000000) 4294967295)}]
                        (mapv #(select-keys % [:to :data]) (sends log))))
                 (is (every? #(= "0x3e7" (:chainId %)) (sends log)))
                 (is (= ["eth_chainId" "wallet_switchEthereumChain" "eth_chainId" "eth_sendTransaction"
                         "eth_chainId" "eth_sendTransaction"]
                        (fake/wallet-methods log))
                     "the chain is re-read right before the deposit")
                 (let [events (mapv (fn [[kind method]] [kind method]) log)
                       approve-receipt (.indexOf events [:rpc "eth_getTransactionReceipt"])
                       estimates (keep-indexed (fn [i e] (when (= e [:rpc "eth_estimateGas"]) i)) events)]
                   (is (= 2 (count estimates)))
                   (is (< (first estimates) approve-receipt) "the approve is estimated before it is sent")
                   (is (> (second estimates) approve-receipt)
                       "the deposit is estimated only after the approve confirmed"))
                 (is (= [[:switch-network :done] [:approve :active] [:approve :confirming] [:approve :done]
                         [:deposit :active] [:deposit :confirming] [:deposit :done]]
                        steps))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest usdc-skips-the-approve-when-the-allowance-covers-the-amount-test
  (async done
    (-> (submit! support/usdc-index "1000" {} {:allowance 1000000000})
        (.then (fn [{:keys [result log steps]}]
                 (is (= "ok" (:status result)))
                 (is (= [deposit-wallet] (mapv :to (sends log))) "only the deposit is sent")
                 (is (= [:approve :skipped] (first steps)))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest erc20-transfers-to-its-own-system-address-test
  (async done
    (-> (submit! support/purr-index "12.5" {:chain "0x3e7"} {})
        (.then (fn [{:keys [result log]}]
                 (is (= "ok" (:status result)))
                 (is (= ["eth_chainId" "eth_sendTransaction"] (fake/wallet-methods log))
                     "a wallet already on HyperEVM is not asked to switch")
                 (is (= (fake/call-data :transfer purr-system (js/BigInt "12500000000000000000"))
                        (:data (first (sends log)))))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest a-rejection-at-each-step-stops-the-flow-with-a-plain-message-test
  (async done
    (-> (js/Promise.all
         #js [(submit! support/hype-index "10" {:switch-error [4001 "User rejected the request."]} {})
              (submit! support/hype-index "10" {:send-errors {1 [4001 "User denied transaction"]}} {})
              (submit! support/usdc-index "1000" {:send-errors {1 [4001 "User rejected"]}} {})
              (submit! support/usdc-index "1000" {:send-errors {2 [4001 "User rejected"]}} {})])
        (.then (fn [results]
                 (let [[switch native approve deposit] (vec results)]
                   (is (= {:status "err" :error "Network switch rejected in wallet." :step :switch-network}
                          (select-keys (:result switch) [:status :error :step])))
                   (is (= [] (sends (:log switch))))
                   (is (= {:status "err" :error "Transfer rejected in wallet." :step :send}
                          (select-keys (:result native) [:status :error :step])))
                   (is (= {:status "err" :error "Approval rejected in wallet." :step :approve}
                          (select-keys (:result approve) [:status :error :step])))
                   (is (= 1 (count (sends (:log approve)))) "no deposit follows a rejected approve")
                   (is (= {:status "err" :error "Transfer rejected in wallet." :step :deposit}
                          (select-keys (:result deposit) [:status :error :step]))
                       "the approve confirmed, so a rejected deposit leaves nothing unresolved")
                   (is (= [(fake/tx-hash 1)] (:hashes (:result deposit))))
                   (done))))
        (.catch (async-support/unexpected-error done)))))

(deftest a-wallet-that-cannot-switch-is-reported-as-unsupported-test
  (async done
    (-> (js/Promise.all
         #js [(submit! support/hype-index "10" {:switch-error [4200 "Unsupported method"]} {})
              (submit! support/hype-index "10" {:switch-error [-32601 "Method not found"]} {})
              (submit! support/hype-index "10" {:switch-noop? true} {})])
        (.then (fn [results]
                 (doseq [{:keys [result log]} (vec results)]
                   (is (= "err" (:status result)))
                   (is (= :chain-switch-unsupported (:kind result)))
                   (is (= :switch-network (:step result)))
                   (is (= [] (sends log)) "nothing is sent from a wallet that did not switch"))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest a-wallet-missing-hyperevm-adds-it-with-hype-as-native-currency-test
  (async done
    (-> (js/Promise.all
         #js [(submit! support/hype-index "10" {:unknown-chain? true} {})
              (submit! support/hype-index "10" {:unknown-chain? true :nested-4902? true} {})])
        (.then (fn [results]
                 (doseq [{:keys [result log]} (vec results)]
                   (is (= "ok" (:status result)))
                   (is (= ["eth_chainId" "wallet_switchEthereumChain" "wallet_addEthereumChain"
                           "wallet_switchEthereumChain" "eth_chainId" "eth_sendTransaction"]
                          (fake/wallet-methods log)))
                   (is (= {:name "HYPE" :symbol "HYPE" :decimals 18}
                          (-> (fake/wallet-requests log) (nth 2) second first :nativeCurrency))))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest a-chain-change-before-a-send-stops-every-further-send-test
  (async done
    (-> (js/Promise.all
         #js [;; Between approve and deposit: the re-read before the deposit.
              (submit! support/usdc-index "1000" {:chain-reads ["0xa4b1" "0x3e7" "0xa4b1"]} {})
              ;; Between the switch and the native send: the verified re-read.
              (submit! support/hype-index "10" {:chain-reads ["0xa4b1" "0x1"]} {})])
        (.then (fn [results]
                 (let [[usdc native] (vec results)]
                   (is (= 1 (count (sends (:log usdc)))) "the approve went out, the deposit never did")
                   (is (= {:status "err" :step :deposit :kind :chain-changed}
                          (select-keys (:result usdc) [:status :step :kind])))
                   (is (re-find #"left HyperEVM" (:error (:result usdc))))
                   (is (= [] (sends (:log native))) "no send after the chain moved away")
                   (is (= {:status "err" :step :switch-network :kind :chain-changed}
                          (select-keys (:result native) [:status :step :kind]))
                       "a wallet that switched elsewhere can switch: it is not reported as unsupported")
                   (done))))
        (.catch (async-support/unexpected-error done)))))

(deftest estimates-fall-back-on-transport-errors-and-block-on-reverts-test
  (async done
    (-> (js/Promise.all
         #js [(submit! support/hype-index "10" {} {:estimates [{:error {:code -32005 :message "rate limited"}}]})
              (submit! support/purr-index "12.5" {} {:estimates [{:error {:code 3 :message "execution reverted: paused"}}]})])
        (.then (fn [results]
                 (let [[fallback reverted] (vec results)]
                   (is (= "ok" (:status (:result fallback))))
                   (is (= "0x7530" (:gas (first (sends (:log fallback)))))
                       "a rate-limited estimate falls back to the native table (30000)")
                   (is (= "err" (:status (:result reverted))))
                   (is (= "HyperEVM would reject this transfer: execution reverted: paused."
                          (:error (:result reverted))))
                   (is (= [] (fake/wallet-methods (:log reverted)))
                       "a reverting transfer never reaches the wallet")
                   (done))))
        (.catch (async-support/unexpected-error done)))))

(deftest an-rpc-error-during-the-receipt-wait-keeps-polling-until-success-test
  (async done
    (-> (submit! support/hype-index "10" {}
              {:receipts {(fake/tx-hash 1) [:rate-limited nil {:status "0x1"}]}})
        (.then (fn [{:keys [result log]}]
                 (is (= "ok" (:status result)))
                 (is (= 3 (count (filter #{"eth_getTransactionReceipt"} (fake/rpc-methods log)))))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest a-receipt-that-never-arrives-ends-pending-not-failed-test
  (async done
    (-> (submit! support/hype-index "10" {} {:receipts {(fake/tx-hash 1) [:rate-limited nil]}}
              {:now-ms-fn (fake/stepping-clock 60000)})
        (.then (fn [{:keys [result records]}]
                 (is (= {:status "pending" :txHash (fake/tx-hash 1) :step :send}
                        (select-keys result [:status :txHash :step])))
                 (is (= (str "https://hyperevmscan.io/tx/" (fake/tx-hash 1)) (:explorer-url result)))
                 (is (= [(fake/tx-hash 1)] (keep :hash records)))
                 (is (not-any? #(false? (:waiting-receipt? %)) records)
                     "an unresolved hash is never marked settled by the submitter")
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest a-reverted-receipt-fails-with-the-hash-test
  (async done
    (-> (submit! support/hype-index "10" {} {:receipts {(fake/tx-hash 1) [{:status "0x0"}]}})
        (.then (fn [{:keys [result]}]
                 (is (= {:status "err" :error "Transaction reverted on HyperEVM." :step :send
                         :tx-hash (fake/tx-hash 1)}
                        (select-keys result [:status :error :step :tx-hash])))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest refusals-before-the-wallet-is-asked-test
  (async done
    (let [log (atom [])
          wallet (fake/fake-wallet log {})
          store (atom (support/state))
          deps (fake/submit-deps store {:provider (:provider wallet)
                                        :fetch-fn (fake/fake-rpc log {})})
          action (:action (request @store support/hype-index "10"))]
      (-> (js/Promise.all
           #js [(hyperevm-submit/submit-hyperevm-to-core!
                 (assoc deps :wallet-provider-fn (constantly nil)) support/owner action)
                (hyperevm-submit/submit-hyperevm-to-core!
                 deps support/owner (assoc action :recipient purr-system))
                (hyperevm-submit/submit-hyperevm-to-core!
                 deps support/owner (assoc action :units "1"))])
          (.then (fn [results]
                   (let [[no-provider wrong-recipient wrong-units] (vec results)]
                     (is (= "Reconnect your wallet to send HyperEVM transactions." (:error no-provider)))
                     (is (re-find #"^Refusing to sign" (:error wrong-recipient)))
                     (is (re-find #"^Refusing to sign" (:error wrong-units)))
                     (is (= [] @log) "nothing reached the wallet or the RPC")
                     (done))))
          (.catch (async-support/unexpected-error done))))))

;; --- flow ids ---------------------------------------------------------------------

(defn- flush!
  "Resolve after pending microtasks and a macrotask have run."
  []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 0))))

(deftest stale-flow-writes-are-ignored-after-close-and-reopen-test
  (async done
    (let [log (atom [])
          toasts (atom [])
          store (atom (support/state {:transfer-from :hyperevm :transfer-to :spot
                                      :transfer-asset support/hype-index :amount-input "10"
                                      :submitting? true}))
          wallet (fake/fake-wallet log {:defer-sends? true})
          submit-fn (fn [store* owner action opts]
                    (hyperevm-submit/submit-hyperevm-to-core!
                     (fake/submit-deps store* {:provider (:provider wallet)
                                               :fetch-fn (fake/fake-rpc log {})})
                     owner action opts))
          deps (merge (effects-support/base-submit-effect-deps)
                      {:store store
                       :request (request @store support/hype-index "10")
                       :submit-hyperevm-to-core! submit-fn
                       :next-flow-id! (constantly "flow-old")
                       :now-ms-fn (constantly support/now-ms)
                       :show-toast! (effects-support/capture-toast! toasts)})
          reopened (support/modal {:transfer-from :hyperevm :transfer-to :spot
                                   :transfer-asset support/purr-index :amount-input "3"})
          result (transfer-effects/submit-evm-to-core! deps)]
      (-> (flush!)
          (.then (fn [_]
                   (is (= "flow-old" (get-in @store [:funding-ui :modal :transfer-evm :flow-id])))
                   (is (= :running (get-in @store [:hyperevm :in-flight support/owner :status]))
                       "the move is in flight before any hash exists")
                   ;; The user closes the modal while the wallet prompt is open,
                   ;; then opens a new draft.
                   (swap! store update-in [:funding-ui :modal]
                          #(modal-state/closed-funding-modal-state
                            modal-state/default-funding-modal-state %))
                   (swap! store assoc-in [:funding-ui :modal] reopened)
                   ;; A second move from the new draft is refused while the
                   ;; first is unresolved.
                   (transfer-effects/submit-evm-to-core!
                    (assoc deps :request (request @store support/purr-index "3")
                           :next-flow-id! (constantly "flow-new")))
                   (is (= transfer-state/wallet-unanswered-message
                          (get-in @store [:funding-ui :modal :error]))
                       "no hash yet: the copy says the wallet is still asking")
                   (is (= 1 (count (sends @log)))
                       "only the first move's send was requested; the second never reached the wallet")
                   (swap! store assoc-in [:funding-ui :modal] reopened)
                   (fake/release-send! wallet)
                   result))
          (.then (fn [_]
                   (is (= reopened (get-in @store [:funding-ui :modal]))
                       "the old flow wrote nothing into the new draft")
                   (is (nil? (get-in @store [:hyperevm :in-flight support/owner]))
                       "the in-flight entry clears once the receipt confirms")
                   (is (= [:success "Sent 10 HYPE to Spot."] (last @toasts))
                       "the result still reaches the user")
                   (is (= 1 (count (sends @log))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
