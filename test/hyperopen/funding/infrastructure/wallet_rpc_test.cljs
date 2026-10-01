(ns hyperopen.funding.infrastructure.wallet-rpc-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.infrastructure.wallet-rpc :as wallet-rpc]
            [hyperopen.test-support.async :as async-support]))

(defn- stepping-now-ms
  [values]
  (let [remaining (atom (vec values))
        last-value (atom (or (last values) 0))]
    (fn []
      (if-let [next-value (first @remaining)]
        (do
          (swap! remaining subvec 1)
          (reset! last-value next-value)
          next-value)
        @last-value))))

(deftest provider-request-forwards-method-and-optional-params-test
  (async done
    (let [calls (atom [])
          provider #js {:request (fn [payload]
                                   (swap! calls conj (js->clj payload :keywordize-keys true))
                                   (js/Promise.resolve "ok"))}]
      (-> (js/Promise.all
           #js [(wallet-rpc/provider-request! provider "eth_chainId")
                (wallet-rpc/provider-request! provider
                                              "eth_sendTransaction"
                                              [{:to "0x1"}])])
          (.then (fn [_]
                   (is (= [{:method "eth_chainId"}
                           {:method "eth_sendTransaction"
                            :params [{:to "0x1"}]}]
                          @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest provider-request-rejects-when-provider-missing-test
  (async done
    (-> (wallet-rpc/provider-request! nil "eth_chainId")
        (.then (fn [_]
                 (is false "Expected missing provider to reject")))
        (.catch (fn [err]
                  (is (= "No wallet provider found. Connect your wallet first."
                         (.-message err)))
                  (done))))))

(deftest ensure-wallet-chain-short-circuits-when-wallet-already-on-target-chain-test
  (async done
    (let [calls (atom [])
          provider #js {:request (fn [payload]
                                   (let [request (js->clj payload :keywordize-keys true)]
                                     (swap! calls conj request)
                                     (js/Promise.resolve "10")))}]
      (-> (wallet-rpc/ensure-wallet-chain!
           provider
           {:chain-id "0xa"
            :chain-name "Optimism"
            :rpc-url "https://optimism.example"
            :explorer-url "https://explorer.optimism.example"})
          (.then (fn [result]
                   (is (= "0xa" result))
                   (is (= [{:method "eth_chainId"}]
                          @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest ensure-wallet-chain-adds-missing-chain-before-switching-test
  (async done
    (let [calls (atom [])
          switch-attempts (atom 0)
          provider #js {:request (fn [payload]
                                   (let [request (js->clj payload :keywordize-keys true)]
                                     (swap! calls conj request)
                                     (case (:method request)
                                       "eth_chainId" (js/Promise.resolve "0x1")
                                       "wallet_switchEthereumChain"
                                       (if (= 1 (swap! switch-attempts inc))
                                         (js/Promise.reject #js {:code 4902
                                                                 :message "Unknown chain"})
                                         (js/Promise.resolve nil))
                                       "wallet_addEthereumChain" (js/Promise.resolve nil))))}]
      (-> (wallet-rpc/ensure-wallet-chain!
           provider
           {:chain-id "0xa4b1"
            :chain-name "Arbitrum One"
            :rpc-url "https://rpc.arbitrum.example"
            :explorer-url "https://arbiscan.example"})
          (.then (fn [result]
                   (is (= "0xa4b1" result))
                   (is (= [{:method "eth_chainId"}
                           {:method "wallet_switchEthereumChain"
                            :params [{:chainId "0xa4b1"}]}
                           {:method "wallet_addEthereumChain"
                            :params [{:chainId "0xa4b1"
                                      :chainName "Arbitrum One"
                                      :nativeCurrency {:name "Ether"
                                                       :symbol "ETH"
                                                       :decimals 18}
                                      :rpcUrls ["https://rpc.arbitrum.example"]
                                      :blockExplorerUrls ["https://arbiscan.example"]}]}
                           {:method "wallet_switchEthereumChain"
                            :params [{:chainId "0xa4b1"}]}]
                          @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest ensure-wallet-chain-rejects-non-4902-switch-errors-test
  (async done
    (let [provider #js {:request (fn [payload]
                                   (let [request (js->clj payload :keywordize-keys true)]
                                     (case (:method request)
                                       "eth_chainId" (js/Promise.resolve "0x1")
                                       "wallet_switchEthereumChain" (js/Promise.reject #js {:code 4001
                                                                                             :message "User rejected"})
                                       (js/Promise.resolve nil))))}]
      (-> (wallet-rpc/ensure-wallet-chain!
           provider
           {:chain-id "0xa4b1"
            :chain-name "Arbitrum One"
            :rpc-url "https://rpc.arbitrum.example"
            :explorer-url "https://arbiscan.example"})
          (.then (fn [_]
                   (is false "Expected switch rejection to propagate")))
          (.catch (fn [err]
                    (is (= 4001
                           (or (.-code err)
                               (aget err "code"))))
                    (done)))))))

(deftest wait-for-transaction-receipt-polls-until-successful-confirmation-test
  (async done
    (let [original-set-timeout (.-setTimeout js/globalThis)
          calls (atom 0)
          provider #js {:request (fn [payload]
                                   (let [request (js->clj payload :keywordize-keys true)]
                                     (is (= {:method "eth_getTransactionReceipt"
                                             :params ["0xtx"]}
                                            request))
                                     (let [attempt (swap! calls inc)]
                                       (js/Promise.resolve
                                        (when (= attempt 2)
                                          #js {:status "0x1"
                                               :transactionHash "0xtx"})))))}
          restore! (fn []
                     (set! (.-setTimeout js/globalThis) original-set-timeout))
          fail! (fn [err]
                  (restore!)
                  ((async-support/unexpected-error done) err))]
      (set! (.-setTimeout js/globalThis) (fn [f _ms]
                                           (f)
                                           :timer-id))
      (-> (wallet-rpc/wait-for-transaction-receipt! provider "0xtx")
          (.then (fn [receipt]
                   (is (= 2 @calls))
                   (is (= "0xtx"
                          (aget receipt "transactionHash")))
                   (restore!)
                   (done)))
          (.catch fail!)))))

(deftest wait-for-transaction-receipt-times-out-when-receipt-never-arrives-test
  (async done
    (let [original-set-timeout (.-setTimeout js/globalThis)
          original-now (.-now js/Date)
          provider #js {:request (fn [payload]
                                   (let [request (js->clj payload :keywordize-keys true)]
                                     (is (= {:method "eth_getTransactionReceipt"
                                             :params ["0xtimeout"]}
                                            request))
                                     (js/Promise.resolve nil)))}
          restore! (fn []
                     (set! (.-setTimeout js/globalThis) original-set-timeout)
                     (set! (.-now js/Date) original-now))
          fail! (fn [err]
                  (restore!)
                  ((async-support/unexpected-error done) err))]
      (set! (.-setTimeout js/globalThis) (fn [f _ms]
                                           (f)
                                           :timer-id))
      (set! (.-now js/Date) (stepping-now-ms [0 121001]))
      (-> (wallet-rpc/wait-for-transaction-receipt! provider "0xtimeout")
          (.then (fn [_]
                   (restore!)
                   (is false "Expected receipt polling to time out")
                   (done)))
          (.catch (fn [err]
                    (restore!)
                    (is (= "Timed out waiting for deposit confirmation."
                           (.-message err)))
                    (done)))
          (.catch fail!)))))

(deftest send-and-confirm-evm-transaction-builds-send-payloads-and-returns-hash-test
  (async done
    (let [provider-calls (atom [])
          receipt-calls (atom [])
          provider #js {:request (fn [payload]
                                   (let [request (js->clj payload :keywordize-keys true)]
                                     (swap! provider-calls conj request)
                                     (case (:method request)
                                       "eth_sendTransaction"
                                       (js/Promise.resolve
                                        (str "0xtx-"
                                             (count (filter #(= "eth_sendTransaction"
                                                                (:method %))
                                                            @provider-calls))))
                                       "eth_getTransactionReceipt"
                                       (do
                                         (swap! receipt-calls conj (first (:params request)))
                                         (js/Promise.resolve #js {:status "0x1"})))))}]
      (-> (js/Promise.all
           #js [(wallet-rpc/send-and-confirm-evm-transaction!
                 provider
                 "0xfrom"
                 {:to "0xto"
                  :data "0xabc"
                  :value "0x5"})
                (wallet-rpc/send-and-confirm-evm-transaction!
                 provider
                 "0xfrom"
                 {:to "0xto2"
                  :data "0xdef"
                  :value nil})])
          (.then (fn [results]
                   (is (= ["0xtx-1" "0xtx-2"]
                          (js->clj results)))
                   (is (= [{:method "eth_sendTransaction"
                            :params [{:from "0xfrom"
                                      :to "0xto"
                                      :data "0xabc"
                                      :value "0x5"}]}
                           {:method "eth_sendTransaction"
                            :params [{:from "0xfrom"
                                      :to "0xto2"
                                      :data "0xdef"}]}]
                          (filter #(= "eth_sendTransaction" (:method %))
                                  @provider-calls)))
                   (is (= ["0xtx-1" "0xtx-2"]
                          @receipt-calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

;; --- HyperEVM generalization (the deposit defaults above are unchanged) -------

(def ^:private hyperevm-config
  {:chain-id "0x3e7"
   :chain-name "HyperEVM"
   :native-currency {:name "HYPE" :symbol "HYPE" :decimals 18}
   :rpc-url "https://rpc.hyperliquid.xyz/evm"
   :explorer-url "https://hyperevmscan.io"
   :verify-switch? true})

(defn- scripted-provider
  "A provider answering each method from `answers` (`method -> [value ..]`,
   the last one repeating; a JS object with a `code` rejects)."
  [calls answers]
  (let [remaining (atom answers)]
    #js {:request (fn [payload]
                    (let [request (js->clj payload :keywordize-keys true)
                          method (:method request)
                          [answer & more] (get @remaining method)]
                      (swap! calls conj request)
                      (when (seq more) (swap! remaining assoc method more))
                      (if (and (= "object" (goog/typeOf answer)) (some? (aget answer "code")))
                        (js/Promise.reject answer)
                        (js/Promise.resolve answer))))}))

(deftest ensure-wallet-chain-adds-hyperevm-with-hype-and-verifies-the-switch-test
  (async done
    (let [calls (atom [])
          provider (scripted-provider calls {"eth_chainId" ["0xa4b1" "0x3e7"]
                                             "wallet_switchEthereumChain"
                                             [#js {:code -32603 :message "Internal error"
                                                   :data #js {:originalError #js {:code 4902}}}
                                              nil]
                                             "wallet_addEthereumChain" [nil]})]
      (-> (wallet-rpc/ensure-wallet-chain! provider hyperevm-config)
          (.then (fn [result]
                   (is (= "0x3e7" result))
                   (is (= ["eth_chainId" "wallet_switchEthereumChain" "wallet_addEthereumChain"
                           "wallet_switchEthereumChain" "eth_chainId"]
                          (mapv :method @calls))
                       "MetaMask mobile's nested 4902 adds the chain; the switch is re-read")
                   (is (= {:name "HYPE" :symbol "HYPE" :decimals 18}
                          (-> @calls (nth 2) :params first :nativeCurrency)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest ensure-wallet-chain-rejects-a-switch-that-did-not-move-when-verifying-test
  (async done
    (let [calls (atom [])
          provider (scripted-provider calls {"eth_chainId" ["0xa4b1"]
                                             "wallet_switchEthereumChain" [nil]})]
      (-> (wallet-rpc/ensure-wallet-chain! provider hyperevm-config)
          (.then (fn [_] (is false "expected a rejection") (done)))
          (.catch (fn [err]
                    (is (= :chain-switch-unsupported (:kind (ex-data err))))
                    (is (= "Your wallet didn't switch to HyperEVM." (.-message err)))
                    (done)))))))

(deftest ensure-wallet-chain-tells-a-switch-elsewhere-from-one-that-did-not-move-test
  (async done
    (let [calls (atom [])
          provider (scripted-provider calls {"eth_chainId" ["0xa4b1" "0x1"]
                                             "wallet_switchEthereumChain" [nil]})]
      (-> (wallet-rpc/ensure-wallet-chain! provider hyperevm-config)
          (.then (fn [_] (is false "expected a rejection") (done)))
          (.catch (fn [err]
                    (is (= {:kind :chain-changed :chain-id "0x1"} (ex-data err))
                        "the wallet can switch, so it is not marked unable to")
                    (is (= "Your wallet moved to another network instead of HyperEVM." (.-message err)))
                    (done)))))))

(deftest ensure-wallet-chain-classifies-wallets-that-cannot-switch-test
  (async done
    (let [attempt (fn [error]
                    (-> (wallet-rpc/ensure-wallet-chain!
                         (scripted-provider (atom []) {"eth_chainId" ["0x1"]
                                                       "wallet_switchEthereumChain" [error]})
                         hyperevm-config)
                        (.then (fn [_] :resolved) (fn [err] err))))]
      (-> (js/Promise.all
           #js [(attempt #js {:code 4200 :message "Unsupported method"})
                (attempt #js {:code -32601 :message "Method not found"})
                (attempt #js {:code -1 :message "Switching chains is not supported"})
                (attempt #js {:code 4001 :message "User rejected the request."})])
          (.then (fn [results]
                   (let [[r4200 r32601 message rejected] (vec results)]
                     (doseq [err [r4200 r32601 message]]
                       (is (= :chain-switch-unsupported (:kind (ex-data err)))))
                     (is (= 4200 (.-code r4200)) "the wallet's code and message are kept")
                     (is (= "Unsupported method" (.-message r4200)))
                     (is (nil? (ex-data rejected)) "a user rejection passes through unchanged")
                     (is (= 4001 (.-code rejected)))
                     (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest request-chain-id-normalizes-the-wallet-answer-test
  (async done
    (-> (wallet-rpc/request-chain-id! (scripted-provider (atom []) {"eth_chainId" ["0x03E7"]}))
        (.then (fn [chain-id]
                 (is (= "0x3e7" chain-id))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest send-transaction-passes-chain-and-fees-and-omits-nil-data-test
  (async done
    (let [calls (atom [])
          provider (scripted-provider calls {"eth_sendTransaction" ["0xhash"]})]
      (-> (wallet-rpc/send-transaction! provider "0xfrom"
                                        {:to "0x2222222222222222222222222222222222222222"
                                         :data nil
                                         :value "0x8ac7230489e80000"
                                         :chainId "0x3e7"
                                         :gas "0x6aa4"
                                         :maxFeePerGas "0x17d78400"
                                         :maxPriorityFeePerGas "0x0"})
          (.then (fn [tx-hash]
                   (is (= "0xhash" tx-hash))
                   (is (= [{:method "eth_sendTransaction"
                            :params [{:from "0xfrom"
                                      :to "0x2222222222222222222222222222222222222222"
                                      :value "0x8ac7230489e80000"
                                      :chainId "0x3e7"
                                      :gas "0x6aa4"
                                      :maxFeePerGas "0x17d78400"
                                      :maxPriorityFeePerGas "0x0"}]}]
                          @calls)
                       "a native value transfer carries no data field")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest watch-asset-sends-an-object-param-test
  (async done
    (let [calls (atom [])
          provider (scripted-provider calls {"wallet_watchAsset" [true]})]
      (-> (wallet-rpc/watch-asset! provider {:address "0x9b49" :symbol "PURR" :decimals 18})
          (.then (fn [added]
                   (is (true? added))
                   (is (= [{:method "wallet_watchAsset"
                            :params {:type "ERC20"
                                     :options {:address "0x9b49" :symbol "PURR" :decimals 18}}}]
                          @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest wait-for-transaction-receipt-accepts-wording-and-timers-test
  (async done
    (let [provider (scripted-provider (atom []) {"eth_getTransactionReceipt" [nil #js {:status "0x0"}]})
          scheduled (atom [])]
      (-> (wallet-rpc/wait-for-transaction-receipt!
           provider "0xtx"
           {:poll-ms 5
            :reverted-message "The transfer reverted on HyperEVM."
            :set-timeout-fn (fn [f ms] (swap! scheduled conj ms) (f))
            :now-ms-fn (constantly 0)})
          (.then (fn [_] (is false "expected a revert") (done)))
          (.catch (fn [err]
                    (is (= "The transfer reverted on HyperEVM." (.-message err)))
                    (is (= [5] @scheduled))
                    (done)))))))

(defn- class-error
  "A wallet rejection that is a class instance not extending Error (some
   providers reject this way), so cljs `object?` and `instance? js/Error`
   both refuse it."
  [code message]
  (js* "new (class WalletRpcError { constructor(c, m) { this.code = c; this.message = m; } })(~{}, ~{})"
       code message))

(deftest ensure-wallet-chain-reads-codes-from-class-instance-rejections-test
  ;; HEAD read the code from any rejection value; the deposit flows (no
  ;; verified switch) must keep the 4902 add-chain fallback for wallets that
  ;; reject with a class instance, and the unsupported-switch classification.
  (async done
    (let [calls (atom [])
          switch-attempts (atom 0)
          provider #js {:request (fn [payload]
                                   (let [request (js->clj payload :keywordize-keys true)]
                                     (swap! calls conj (:method request))
                                     (case (:method request)
                                       "eth_chainId" (js/Promise.resolve "0x1")
                                       "wallet_switchEthereumChain"
                                       (if (= 1 (swap! switch-attempts inc))
                                         (js/Promise.reject (class-error 4902 "Unknown chain"))
                                         (js/Promise.resolve nil))
                                       "wallet_addEthereumChain" (js/Promise.resolve nil))))}
          deposit-config {:chain-id "0xa4b1"
                          :chain-name "Arbitrum One"
                          :rpc-url "https://rpc.arbitrum.example"
                          :explorer-url "https://arbiscan.example"}]
      (is (not (object? (class-error 1 "x"))) "the case this test exists for")
      (-> (wallet-rpc/ensure-wallet-chain! provider deposit-config)
          (.then (fn [result]
                   (is (= "0xa4b1" result))
                   (is (= ["eth_chainId" "wallet_switchEthereumChain" "wallet_addEthereumChain"
                           "wallet_switchEthereumChain"]
                          @calls))
                   (wallet-rpc/ensure-wallet-chain!
                    (scripted-provider (atom []) {"eth_chainId" ["0x1"]
                                                  "wallet_switchEthereumChain"
                                                  [(class-error 4200 "Unsupported method")]})
                    hyperevm-config)))
          (.then (fn [_] (is false "an unsupported switch must reject"))
                 (fn [err]
                   (is (= :chain-switch-unsupported (:kind (ex-data err))))
                   (is (= 4200 (.-code err)))
                   (is (= "Unsupported method" (.-message err)))))
          (.then (fn [_] (done)))
          (.catch (async-support/unexpected-error done))))))
