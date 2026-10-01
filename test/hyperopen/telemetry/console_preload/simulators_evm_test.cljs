(ns hyperopen.telemetry.console-preload.simulators-evm-test
  "The wallet simulator's EVM methods, which the HyperEVM Transfer Playwright
   spec drives: chain switches that reflect in `eth_chainId`, the 4902 add-
   then-switch path and the 4200 refusal, deterministic transaction hashes
   (which the mocked HyperEVM RPC answers receipts for), per-send rejections,
   `wallet_watchAsset`, and the ordered request log."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.telemetry.console-preload :as console-preload]
            [hyperopen.telemetry.console-preload.simulators :as simulators]
            [hyperopen.telemetry.console-preload.wallet-evm :as wallet-evm]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.wallet.core :as wallet-core]))

(def ^:private owner "0x1234567890abcdef1234567890abcdef12345678")

(defn- capture-browser-state
  []
  (let [window* (aget js/globalThis "window")]
    {:window window*
     :window-provider (some-> window* (aget "ethereum"))
     :global-provider (aget js/globalThis "ethereum")}))

(defn- restore-browser-state!
  [{:keys [window window-provider global-provider]}]
  (if (some? global-provider)
    (aset js/globalThis "ethereum" global-provider)
    (js-delete js/globalThis "ethereum"))
  (if (some? window)
    (do
      (aset window "ethereum" window-provider)
      (aset js/globalThis "window" window))
    (js-delete js/globalThis "window")))

(defn- cleanup!
  [browser-state]
  (simulators/clear-wallet-simulator!)
  (wallet-core/clear-provider-override!)
  (wallet-core/reset-provider-listener-state!)
  (restore-browser-state! browser-state))

(defn- install-provider!
  [config]
  (with-redefs [wallet-core/attach-listeners! (fn [_] nil)]
    (simulators/install-wallet-simulator! (clj->js config))
    (aget js/globalThis "ethereum")))

(defn- outcome!
  "Resolve with `{:ok value}` or `{:error {:code :message}}`, so a test can
   chain requests whether they succeed or reject."
  [provider method params]
  (-> (.request provider (clj->js (cond-> {:method method}
                                    (some? params) (assoc :params params))))
      (.then (fn [value] {:ok (js->clj value :keywordize-keys true)})
             (fn [err] {:error {:code (aget err "code") :message (.-message err)}}))))

(defn- run-steps!
  "Run `[method params]` requests in order; resolve with their outcomes."
  [provider steps]
  (reduce (fn [acc [method params]]
            (.then acc (fn [results]
                         (.then (outcome! provider method params)
                                (fn [result] (conj results result))))))
          (js/Promise.resolve [])
          steps))

(defn- snapshot
  []
  (js->clj (simulators/wallet-simulator-snapshot) :keywordize-keys true))

(defn- run-test!
  [done config steps check]
  (let [browser-state (capture-browser-state)]
    (try
      (let [provider (install-provider! config)]
        (-> (run-steps! provider steps)
            (.then (fn [results]
                     (try
                       (check results (snapshot))
                       (finally
                         (cleanup! browser-state)
                         (done)))))
            (.catch (fn [err]
                      (cleanup! browser-state)
                      ((async-support/unexpected-error done) err)))))
      (catch :default err
        (cleanup! browser-state)
        ((async-support/unexpected-error done) err)))))

(deftest simulated-tx-hashes-are-deterministic-test
  ;; `tools/playwright/support/hyperevm_fixtures.mjs` `simulatedTxHash`
  ;; builds the same strings; its node test pins the same two values.
  (is (= (str "0x" (apply str (repeat 56 "e")) "00000001") (wallet-evm/simulated-tx-hash 1)))
  (is (= (str "0x" (apply str (repeat 56 "e")) "0000001a") (wallet-evm/simulated-tx-hash 26)))
  (is (= 66 (count (wallet-evm/simulated-tx-hash 3)))))

(deftest normalize-config-reads-camel-case-keys-and-error-specs-test
  (let [config (wallet-evm/normalize-config
                {:switchChainErrorCode 4902
                 :sendTransactionErrors [nil 4001 "boom" {:code -32603 :message "Internal error"}]
                 :txHashes ["0xabc"]})]
    (is (= 4902 (:switch-chain-error-code config)))
    (is (= [nil
            {:code 4001 :message "User rejected the request."}
            {:code nil :message "boom"}
            {:code -32603 :message "Internal error"}]
           (:send-transaction-errors config)))
    (is (= ["0xabc"] (:tx-hashes config)))
    (is (true? (:watch-asset-result config))))
  (is (= {:switch-chain-error-code nil
          :send-transaction-errors []
          :tx-hashes []
          :watch-asset-result true
          :chain-after-sends {}}
         (wallet-evm/normalize-config {})))
  (testing "chainAfterSends keys arrive keywordized from a JS object"
    (is (= {1 "0xa4b1" 3 "0x1"}
           (:chain-after-sends (wallet-evm/normalize-config
                                {:chainAfterSends {(keyword "1") "0xa4b1" "3" "0x1" :x "0x2"}}))))))

(deftest unknown-chain-is-added-then-switched-and-chain-id-follows-test
  (async done
    (run-test!
     done
     {:accounts [owner] :chainId "0xa4b1" :switchChainErrorCode 4902}
     [["eth_chainId" nil]
      ["wallet_switchEthereumChain" [{:chainId "0x3e7"}]]
      ["wallet_addEthereumChain" [{:chainId "0x3e7"
                                   :chainName "HyperEVM"
                                   :nativeCurrency {:name "HYPE" :symbol "HYPE" :decimals 18}
                                   :rpcUrls ["https://rpc.hyperliquid.xyz/evm"]
                                   :blockExplorerUrls ["https://hyperevmscan.io"]}]]
      ["wallet_switchEthereumChain" [{:chainId "0x3e7"}]]
      ["eth_chainId" nil]]
     (fn [results snap]
       (is (= [{:ok "0xa4b1"}
               {:error {:code 4902
                        :message "Unrecognized chain ID. Try adding the chain using wallet_addEthereumChain first."}}
               {:ok nil}
               {:ok nil}
               {:ok "0x3e7"}]
              results))
       (is (= ["eth_chainId" "wallet_switchEthereumChain" "wallet_addEthereumChain"
               "wallet_switchEthereumChain" "eth_chainId"]
              (mapv :method (:requests snap))))
       (is (= {:name "HYPE" :symbol "HYPE" :decimals 18}
              (:nativeCurrency (first (:addedChains snap)))))
       (is (= "0x3e7" (get-in snap [:config :chain-id])))))))

(deftest unsupported-switch-rejects-every-time-and-keeps-the-chain-test
  (async done
    (run-test!
     done
     {:accounts [owner] :chainId "0xa4b1" :switchChainErrorCode 4200}
     [["wallet_switchEthereumChain" [{:chainId "0x3e7"}]]
      ["wallet_addEthereumChain" [{:chainId "0x3e7"}]]
      ["wallet_switchEthereumChain" [{:chainId "0x3e7"}]]
      ["eth_chainId" nil]]
     (fn [results _snap]
       (is (= {:code 4200 :message "The Provider does not support the requested method."}
              (:error (first results))))
       (is (= 4200 (get-in results [2 :error :code])))
       (is (= {:ok "0xa4b1"} (last results)))))))

(deftest a-plain-switch-moves-the-chain-without-an-error-code-test
  (async done
    (run-test!
     done
     {:accounts [owner] :chainId "0xa4b1"}
     [["wallet_switchEthereumChain" [{:chainId "0x3e7"}]]
      ["eth_chainId" nil]]
     (fn [results _snap]
       (is (= [{:ok nil} {:ok "0x3e7"}] results))))))

(deftest send-transaction-records-each-send-and-rejects-the-configured-one-test
  (async done
    (let [approve {:from owner
                   :to "0xb88339cb7199b77e23db6e890353e22632ba630f"
                   :data "0x095ea7b3"
                   :chainId "0x3e7"
                   :gas "0x1"
                   :maxFeePerGas "0x2"
                   :maxPriorityFeePerGas "0x0"}
          deposit (assoc approve :to "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24" :data "0x2b2dfd2c")]
      (run-test!
       done
       {:accounts [owner]
        :chainId "0x3e7"
        :sendTransactionErrors [nil {:code 4001 :message "User rejected the request."}]}
       [["eth_sendTransaction" [approve]]
        ["eth_sendTransaction" [deposit]]
        ["eth_sendTransaction" [deposit]]
        ["wallet_watchAsset" {:type "ERC20"
                              :options {:address "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
                                        :symbol "PURR"
                                        :decimals 18}}]]
       (fn [results snap]
         (testing "hashes follow the successful sends; the rejected one carries 4001"
           (is (= [{:ok (wallet-evm/simulated-tx-hash 1)}
                   {:error {:code 4001 :message "User rejected the request."}}
                   {:ok (wallet-evm/simulated-tx-hash 2)}
                   {:ok true}]
                  results)))
         (testing "each sent transaction is recorded with its params and the wallet's chain"
           (is (= [(assoc approve :hash (wallet-evm/simulated-tx-hash 1) :walletChainId "0x3e7")
                   (assoc deposit :hash (wallet-evm/simulated-tx-hash 2) :walletChainId "0x3e7")]
                  (:transactions snap))))
         (testing "the log keeps every request, the rejected send included"
           (is (= ["eth_sendTransaction" "eth_sendTransaction" "eth_sendTransaction" "wallet_watchAsset"]
                  (mapv :method (:requests snap))))
           (is (= [{:type "ERC20"
                    :options {:address "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
                              :symbol "PURR"
                              :decimals 18}}]
                  (:watchedAssets snap)))))))))

(deftest configured-hashes-come-before-the-deterministic-sequence-test
  (async done
    (run-test!
     done
     {:accounts [owner] :chainId "0x3e7" :txHashes ["0xfeed"]}
     [["eth_sendTransaction" [{:to "0x2222222222222222222222222222222222222222" :value "0x1"}]]
      ["eth_sendTransaction" [{:to "0x2222222222222222222222222222222222222222" :value "0x1"}]]]
     (fn [results _snap]
       (is (= [{:ok "0xfeed"} {:ok (wallet-evm/simulated-tx-hash 2)}] results))))))

(deftest snapshot-is-reachable-from-the-debug-api-and-cleared-with-the-simulator-test
  (let [browser-state (capture-browser-state)]
    (try
      (install-provider! {:accounts [owner]})
      (let [api (@#'console-preload/debug-api)
            read-snapshot (aget api "walletSimulatorSnapshot")]
        (is (fn? read-snapshot))
        (is (true? (aget (read-snapshot) "installed")))
        (is (= [] (js->clj (aget (read-snapshot) "requests"))))
        (simulators/clear-wallet-simulator!)
        (is (false? (aget (read-snapshot) "installed")))
        (is (nil? (aget (read-snapshot) "requests"))))
      (finally
        (cleanup! browser-state)))))

(deftest wallet-chain-id-is-the-wallets-chain-not-the-transactions-test
  ;; Playwright case (d) relies on `walletChainId` to prove a transaction was
  ;; sent while the wallet was on HyperEVM, so it must record the wallet's
  ;; chain even when the transaction names another one.
  (async done
    (let [tx {:from owner
              :to "0x2222222222222222222222222222222222222222"
              :value "0x1"
              :chainId "0x3e7"}]
      (run-test!
       done
       {:accounts [owner] :chainId "0xa4b1"}
       [["eth_sendTransaction" [tx]]]
       (fn [_results snap]
         (is (= [(assoc tx :hash (wallet-evm/simulated-tx-hash 1) :walletChainId "0xa4b1")]
                (:transactions snap))))))))

(deftest chain-after-sends-moves-the-wallet-right-after-that-send-test
  ;; Acceptance 7 needs a wallet that leaves HyperEVM between the approve and
  ;; the deposit: `chainAfterSends {1 "0xa4b1"}` moves it after send 1, so
  ;; `eth_chainId` answers the new chain and listeners hear `chainChanged`.
  (async done
    (let [browser-state (capture-browser-state)
          heard (atom [])]
      (try
        (let [provider (install-provider! {:accounts [owner]
                                           :chainId "0x3e7"
                                           :chainAfterSends {"1" "0xa4b1"}})]
          (.on ^js provider "chainChanged" #(swap! heard conj %))
          (-> (run-steps! provider [["eth_chainId" nil]
                                    ["eth_sendTransaction" [{:to owner :value "0x1" :chainId "0x3e7"}]]
                                    ["eth_chainId" nil]
                                    ["eth_sendTransaction" [{:to owner :value "0x1" :chainId "0x3e7"}]]])
              (.then (fn [results]
                       (is (= [{:ok "0x3e7"}
                               {:ok (wallet-evm/simulated-tx-hash 1)}
                               {:ok "0xa4b1"}
                               {:ok (wallet-evm/simulated-tx-hash 2)}]
                              results))
                       (is (= ["0xa4b1"] @heard))
                       (is (= ["0x3e7" "0xa4b1"] (mapv :walletChainId (:transactions (snapshot)))))))
              (.catch (fn [err] (is false (str "Unexpected error: " err))))
              (.finally (fn [] (cleanup! browser-state) (done)))))
        (catch :default err
          (cleanup! browser-state)
          ((async-support/unexpected-error done) err))))))

(deftest unknown-methods-reject-with-4200-and-are-logged-test
  ;; A read routed through the wallet must fail loudly instead of reading
  ;; nil: receipts, gas and balances come from the mocked RPC only.
  (async done
    (run-test!
     done
     {:accounts [owner] :chainId "0x3e7"}
     [["eth_getTransactionReceipt" ["0xabc"]]
      ["eth_chainId" nil]]
     (fn [results snap]
       (is (= [{:error {:code 4200 :message "The Provider does not support the requested method."}}
               {:ok "0x3e7"}]
              results))
       (is (= [{:method "eth_getTransactionReceipt" :params ["0xabc"]}]
              (:unknownRequests snap)))
       (is (= ["eth_getTransactionReceipt" "eth_chainId"] (mapv :method (:requests snap))))))))

(deftest an-emitted-chain-change-moves-the-wallet-test
  (async done
    (let [browser-state (capture-browser-state)]
      (try
        (let [provider (install-provider! {:accounts [owner] :chainId "0x3e7"})]
          (simulators/emit-wallet-simulator! "chainChanged" "0xa4b1")
          (-> (outcome! provider "eth_chainId" nil)
              (.then (fn [result]
                       (is (= {:ok "0xa4b1"} result))))
              (.catch (fn [err] (is false (str "Unexpected error: " err))))
              (.finally (fn [] (cleanup! browser-state) (done)))))
        (catch :default err
          (cleanup! browser-state)
          ((async-support/unexpected-error done) err))))))
