(ns hyperopen.funding.application.deposit-submit-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.application.deposit-submit :as deposit-submit]
            [hyperopen.funding.application.submit-effects :as effects]
            [hyperopen.funding.test-support.effects :as effects-support]
            [hyperopen.test-support.async :as async-support]))

(defn- submit-deps
  [overrides]
  (merge (effects-support/base-submit-effect-deps)
         overrides))

(deftest api-submit-funding-deposit-blocks-mutations-while-spectate-mode-active-test
  (let [store (atom {:wallet {:address "0xabc"}
                     :account-context {:spectate-mode {:active? true
                                                    :address "0x1234567890abcdef1234567890abcdef12345678"}}
                     :funding-ui {:modal (effects-support/seed-modal :deposit)}})
        toasts (atom [])]
    (effects/api-submit-funding-deposit!
     (submit-deps
      {:store store
      :request {:action {:type "bridge2Deposit"
                         :asset "usdc"
                         :amount "7"
                         :chainId "0xa4b1"}}
      :show-toast! (effects-support/capture-toast! toasts)}))
    (is (= false (get-in @store [:funding-ui :modal :submitting?])))
    (is (= account-context/spectate-mode-read-only-message
           (get-in @store [:funding-ui :modal :error])))
    (is (= [[:error account-context/spectate-mode-read-only-message]]
           @toasts))))

(deftest api-submit-funding-deposit-no-wallet-sets-error-test
  (let [store (atom {:wallet {}
                     :funding-ui {:modal (effects-support/seed-modal :deposit)}})
        submit-calls (atom 0)
        toasts (atom [])]
    (is (nil?
         (effects/api-submit-funding-deposit!
          (submit-deps
           {:store store
           :request {:action {:type "bridge2Deposit"
                              :asset "usdc"
                              :amount "5"
                              :chainId "0xa4b1"}}
           :submit-usdc-bridge2-deposit! (fn [_store _address _action]
                                           (swap! submit-calls inc)
                                           (js/Promise.resolve {:status "ok"}))
           :show-toast! (effects-support/capture-toast! toasts)}))))
    (is (= 0 @submit-calls))
    (is (= false (get-in @store [:funding-ui :modal :submitting?])))
    (is (= "Connect your wallet before depositing."
           (get-in @store [:funding-ui :modal :error])))
    (is (= [[:error "Connect your wallet before depositing."]]
           @toasts))))

(deftest api-submit-funding-deposit-success-closes-modal-and-refreshes-test
  (async done
    (let [default-modal (effects-support/default-funding-modal-state)
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (effects-support/seed-modal :deposit)}})
          toasts (atom [])
          dispatches (atom [])]
      (-> (effects/api-submit-funding-deposit!
           (submit-deps
            {:store store
            :request {:action {:type "bridge2Deposit"
                               :asset "usdc"
                               :amount "5"
                               :chainId "0xa4b1"}}
            :submit-usdc-bridge2-deposit! (fn [_store _address _action]
                                            (js/Promise.resolve {:status "ok"
                                                                 :network "Arbitrum"}))
            :show-toast! (effects-support/capture-toast! toasts)
            :dispatch! (effects-support/capture-dispatch! dispatches)
            :default-funding-modal-state (fn [] default-modal)}))
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= default-modal (get-in @store [:funding-ui :modal])))
                   (is (= [[:success "Deposit submitted on Arbitrum."]]
                          @toasts))
                   (is (= [[[[:actions/load-user-data "0xabc"]]]]
                          (mapv (fn [[_store event]] [event]) @dispatches)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected deposit success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-usdt-route-delegates-to-lifi-submitter-test
  (async done
    (let [default-modal (effects-support/default-funding-modal-state)
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (effects-support/seed-modal :deposit)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           (submit-deps
            {:store store
            :request {:action {:type "lifiUsdtToUsdcBridge2Deposit"
                               :asset "usdt"
                               :amount "10"
                               :chainId "0xa4b1"}}
            :submit-usdc-bridge2-deposit! (fn [_store _address _action]
                                            (js/Promise.resolve {:status "err"
                                                                 :error "wrong submitter"}))
            :submit-usdt-lifi-deposit! (fn [_store address action]
                                         (swap! submit-calls conj [address action])
                                         (js/Promise.resolve {:status "ok"
                                                              :network "Arbitrum"}))
            :show-toast! (effects-support/capture-toast! toasts)
            :default-funding-modal-state (fn [] default-modal)}))
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "lifiUsdtToUsdcBridge2Deposit"
                                     :asset "usdt"
                                     :amount "10"
                                     :chainId "0xa4b1"}]]
                          @submit-calls))
                   (is (= default-modal (get-in @store [:funding-ui :modal])))
                   (is (= [[:success "Deposit submitted on Arbitrum."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected USDT route deposit success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-usdh-route-delegates-to-across-submitter-test
  (async done
    (let [default-modal (effects-support/default-funding-modal-state)
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (effects-support/seed-modal :deposit)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           (submit-deps
            {:store store
            :request {:action {:type "acrossUsdcToUsdhDeposit"
                               :asset "usdh"
                               :amount "10"
                               :chainId "0xa4b1"}}
            :submit-usdc-bridge2-deposit! (fn [_store _address _action]
                                            (js/Promise.resolve {:status "err"
                                                                 :error "wrong submitter"}))
            :submit-usdt-lifi-deposit! (fn [_store _address _action]
                                         (js/Promise.resolve {:status "err"
                                                              :error "wrong submitter"}))
            :submit-usdh-across-deposit! (fn [_store address action]
                                           (swap! submit-calls conj [address action])
                                           (js/Promise.resolve {:status "ok"
                                                                :network "Arbitrum"}))
            :show-toast! (effects-support/capture-toast! toasts)
            :default-funding-modal-state (fn [] default-modal)}))
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "acrossUsdcToUsdhDeposit"
                                     :asset "usdh"
                                     :amount "10"
                                     :chainId "0xa4b1"}]]
                          @submit-calls))
                   (is (= default-modal (get-in @store [:funding-ui :modal])))
                   (is (= [[:success "Deposit submitted on Arbitrum."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected USDH route deposit success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-runtime-error-sets-error-state-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (effects-support/seed-modal :deposit)}})
          toasts (atom [])]
      (-> (effects/api-submit-funding-deposit!
           (submit-deps
            {:store store
            :request {:action {:type "bridge2Deposit"
                               :asset "usdc"
                               :amount "5"
                               :chainId "0xa4b1"}}
            :submit-usdc-bridge2-deposit! (fn [_store _address _action]
                                            (js/Promise.reject (js/Error. "wallet unavailable")))
            :show-toast! (effects-support/capture-toast! toasts)}))
          (.then (fn [result]
                   (is (nil? result))
                   (is (= false (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= "Deposit failed: wallet unavailable"
                          (get-in @store [:funding-ui :modal :error])))
                   (is (= [[:error "Deposit failed: wallet unavailable"]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                   (is false (str "Unexpected deposit runtime failure-path rejection: " err))
                    (done)))))))

(deftest submit-usdt-lifi-bridge2-deposit-tx-validates-prerequisites-test
  (async done
    (let [base-deps {:normalize-address identity
                     :parse-usdc-units (fn [value]
                                         (when (= value "5")
                                           (js/BigInt "5000000")))
                     :chain-config {:usdc-address "0xusdc"}
                     :ensure-wallet-chain! (fn [_provider _chain-config]
                                             (js/Promise.resolve nil))
                     :fetch-lifi-quote! (fn [_from-address _amount-units _usdc-address]
                                          (js/Promise.resolve {}))
                     :lifi-quote->swap-config (fn [_quote] nil)
                     :read-erc20-allowance-units! (fn [& _args]
                                                    (js/Promise.resolve (js/BigInt "0")))
                     :encode-erc20-approve-call-data (fn [_spender _amount] "0xapprove")
                     :provider-request! (fn [& _args]
                                          (js/Promise.resolve "0xtx"))
                     :wait-for-transaction-receipt! (fn [_provider _tx-hash]
                                                     (js/Promise.resolve {:status "ok"}))
                     :read-erc20-balance-units! (fn [& _args]
                                                  (js/Promise.resolve (js/BigInt "0")))
                     :submit-usdc-bridge2-deposit! (fn [_store _owner-address _action]
                                                     (js/Promise.resolve {:status "ok"}))
                     :usdc-units->amount-text (fn [_units] "0")
                     :bridge-chain-id "0xa4b1"
                     :wallet-error-message (fn [err] (or (some-> err .-message) "unknown"))}]
      (-> (js/Promise.all
           #js[(deposit-submit/submit-usdt-lifi-bridge2-deposit-tx!
                (assoc base-deps :wallet-provider-fn (fn [] nil))
                (atom {})
                "0xowner"
                {:amount "5"})
               (deposit-submit/submit-usdt-lifi-bridge2-deposit-tx!
                (assoc base-deps :wallet-provider-fn (fn [] :provider)
                                :normalize-address (fn [_] nil))
                (atom {})
                "0xowner"
                {:amount "5"})
               (deposit-submit/submit-usdt-lifi-bridge2-deposit-tx!
                (assoc base-deps :wallet-provider-fn (fn [] :provider)
                                :parse-usdc-units (fn [_] nil))
                (atom {})
                "0xowner"
                {:amount "invalid"})
               (deposit-submit/submit-usdt-lifi-bridge2-deposit-tx!
                (assoc base-deps :wallet-provider-fn (fn [] :provider)
                                :parse-usdc-units (fn [_] (js/BigInt "0")))
                (atom {})
                "0xowner"
                {:amount "0"})])
          (.then (fn [results]
                   (is (= [{:status "err"
                            :error "No wallet provider found. Connect your wallet first."}
                           {:status "err"
                            :error "Connect your wallet before depositing."}
                           {:status "err"
                            :error "Enter a valid deposit amount."}
                           {:status "err"
                            :error "Enter an amount greater than 0."}]
                          (js->clj results :keywordize-keys true)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest submit-usdt-lifi-bridge2-deposit-tx-skips-approval-when-allowance-suffices-test
  (async done
    (let [provider-calls (atom [])
          balance-calls (atom [])
          submit-calls (atom [])]
      (-> (deposit-submit/submit-usdt-lifi-bridge2-deposit-tx!
           {:wallet-provider-fn (fn [] :provider)
            :normalize-address identity
            :parse-usdc-units (fn [_] (js/BigInt "5000000"))
            :chain-config {:usdc-address "0xusdc"}
            :ensure-wallet-chain! (fn [provider chain-config]
                                    (is (= :provider provider))
                                    (is (= {:usdc-address "0xusdc"} chain-config))
                                    (js/Promise.resolve nil))
            :fetch-lifi-quote! (fn [from-address amount-units usdc-address]
                                 (is (= "0xowner" from-address))
                                 (is (= "5000000" (.toString amount-units)))
                                 (is (= "0xusdc" usdc-address))
                                 (js/Promise.resolve {:id "quote"}))
            :lifi-quote->swap-config (fn [_quote]
                                       {:swap-token-address "0xusdt"
                                        :approval-address "0xapproval"
                                        :from-amount-units (js/BigInt "5000000")
                                        :swap-to-address "0xrouter"
                                        :swap-data "0xswap"
                                        :swap-value "0x5"})
            :read-erc20-allowance-units! (fn [_provider token-address from-address spender-address]
                                           (is (= "0xusdt" token-address))
                                           (is (= "0xowner" from-address))
                                           (is (= "0xapproval" spender-address))
                                           (js/Promise.resolve (js/BigInt "5000000")))
            :encode-erc20-approve-call-data (fn [_spender _amount]
                                              (is false "Approval data should not be encoded when allowance is sufficient.")
                                              "0xapprove")
            :provider-request! (fn [_provider method params]
                                 (swap! provider-calls conj [method (js->clj (first params) :keywordize-keys true)])
                                 (js/Promise.resolve "0xswap-tx"))
            :wait-for-transaction-receipt! (fn [_provider tx-hash]
                                            (is (= "0xswap-tx" tx-hash))
                                            (js/Promise.resolve {:status "ok"}))
            :read-erc20-balance-units! (fn [_provider token-address owner-address]
                                         (swap! balance-calls conj [token-address owner-address])
                                         (js/Promise.resolve
                                          (if (= 1 (count @balance-calls))
                                            (js/BigInt "100")
                                            (js/BigInt "160"))))
            :submit-usdc-bridge2-deposit! (fn [store owner-address action]
                                            (swap! submit-calls conj [store owner-address action])
                                            (js/Promise.resolve {:status "ok"
                                                                 :network "Arbitrum"}))
            :usdc-units->amount-text (fn [units]
                                       (str "delta-" (.toString units)))
            :bridge-chain-id "0xa4b1"
            :wallet-error-message (fn [err] (or (some-> err .-message) "unknown"))}
           (atom {:funding-ui {:modal {}}})
           "0xowner"
           {:amount "5"})
          (.then (fn [resp]
                   (is (= {:status "ok"
                           :network "Arbitrum"}
                          (js->clj resp :keywordize-keys true)))
                   (is (= [["eth_sendTransaction"
                            {:from "0xowner"
                             :to "0xrouter"
                             :data "0xswap"
                             :value "0x5"}]]
                          @provider-calls))
                   (is (= [["0xusdc" "0xowner"]
                           ["0xusdc" "0xowner"]]
                          @balance-calls))
                   (is (= [["0xowner"
                            {:amount "delta-60"
                             :chainId "0xa4b1"}]]
                          (mapv (fn [[_store owner-address action]]
                                  [owner-address action])
                                @submit-calls)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest submit-usdt-lifi-bridge2-deposit-tx-sends-approval-before-swap-when-needed-test
  (async done
    (let [provider-calls (atom [])
          receipt-calls (atom [])
          balance-call-count (atom 0)]
      (-> (deposit-submit/submit-usdt-lifi-bridge2-deposit-tx!
           {:wallet-provider-fn (fn [] :provider)
            :normalize-address identity
            :parse-usdc-units (fn [_] (js/BigInt "5000000"))
            :chain-config {:usdc-address "0xusdc"}
            :ensure-wallet-chain! (fn [_provider _chain-config]
                                    (js/Promise.resolve nil))
            :fetch-lifi-quote! (fn [_from-address _amount-units _usdc-address]
                                 (js/Promise.resolve {:id "quote"}))
            :lifi-quote->swap-config (fn [_quote]
                                       {:swap-token-address "0xusdt"
                                        :approval-address "0xapproval"
                                        :from-amount-units (js/BigInt "5000000")
                                        :swap-to-address "0xrouter"
                                        :swap-data "0xswap"})
            :read-erc20-allowance-units! (fn [& _args]
                                           (js/Promise.resolve (js/BigInt "1")))
            :encode-erc20-approve-call-data (fn [spender amount]
                                              (is (= "0xapproval" spender))
                                              (is (= "5000000" (.toString amount)))
                                              "0xapprove")
            :provider-request! (fn [_provider method params]
                                 (swap! provider-calls conj [method (js->clj (first params) :keywordize-keys true)])
                                 (js/Promise.resolve
                                  (if (= 1 (count @provider-calls))
                                    "0xapprove-tx"
                                    "0xswap-tx")))
            :wait-for-transaction-receipt! (fn [_provider tx-hash]
                                            (swap! receipt-calls conj tx-hash)
                                            (js/Promise.resolve {:status "ok"}))
            :read-erc20-balance-units! (fn [& _args]
                                         (swap! balance-call-count inc)
                                         (js/Promise.resolve
                                          (if (= 1 @balance-call-count)
                                            (js/BigInt "100")
                                            (js/BigInt "175"))))
            :submit-usdc-bridge2-deposit! (fn [_store _owner-address _action]
                                            (js/Promise.resolve {:status "ok"
                                                                 :network "Arbitrum"}))
            :usdc-units->amount-text (fn [units]
                                       (str (.toString units)))
            :bridge-chain-id "0xa4b1"
            :wallet-error-message (fn [err] (or (some-> err .-message) "unknown"))}
           (atom {})
           "0xowner"
           {:amount "5"})
          (.then (fn [resp]
                   (is (= {:status "ok"
                           :network "Arbitrum"}
                          (js->clj resp :keywordize-keys true)))
                   (is (= [["eth_sendTransaction"
                            {:from "0xowner"
                             :to "0xusdt"
                             :data "0xapprove"
                             :value "0x0"}]
                           ["eth_sendTransaction"
                            {:from "0xowner"
                             :to "0xrouter"
                             :data "0xswap"}]]
                          @provider-calls))
                   (is (= ["0xapprove-tx" "0xswap-tx"]
                          @receipt-calls))
                   (is (= 2 @balance-call-count))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest submit-usdt-lifi-bridge2-deposit-tx-converts-quote-and-delta-errors-via-wallet-error-message-test
  (async done
    (let [base-deps {:wallet-provider-fn (fn [] :provider)
                     :normalize-address identity
                     :parse-usdc-units (fn [_] (js/BigInt "5000000"))
                     :chain-config {:usdc-address "0xusdc"}
                     :ensure-wallet-chain! (fn [_provider _chain-config]
                                             (js/Promise.resolve nil))
                     :read-erc20-allowance-units! (fn [& _args]
                                                    (js/Promise.resolve (js/BigInt "5000000")))
                     :encode-erc20-approve-call-data (fn [_spender _amount] "0xapprove")
                     :provider-request! (fn [_provider _method _params]
                                          (js/Promise.resolve "0xswap-tx"))
                     :wait-for-transaction-receipt! (fn [_provider _tx-hash]
                                                     (js/Promise.resolve {:status "ok"}))
                     :submit-usdc-bridge2-deposit! (fn [_store _owner-address _action]
                                                     (js/Promise.resolve {:status "ok"}))
                     :usdc-units->amount-text (fn [units] (.toString units))
                     :bridge-chain-id "0xa4b1"
                     :wallet-error-message (fn [err]
                                             (str "wallet: " (or (some-> err .-message) "unknown")))}]
      (-> (js/Promise.all
           #js[(deposit-submit/submit-usdt-lifi-bridge2-deposit-tx!
                (assoc base-deps
                       :fetch-lifi-quote! (fn [_from-address _amount-units _usdc-address]
                                            (js/Promise.resolve {:id "quote"}))
                       :lifi-quote->swap-config (fn [_quote] nil)
                       :read-erc20-balance-units! (fn [& _args]
                                                    (js/Promise.resolve (js/BigInt "0"))))
                (atom {})
                "0xowner"
                {:amount "5"})
               (deposit-submit/submit-usdt-lifi-bridge2-deposit-tx!
                (assoc base-deps
                       :fetch-lifi-quote! (fn [_from-address _amount-units _usdc-address]
                                            (js/Promise.resolve {:id "quote"}))
                       :lifi-quote->swap-config (fn [_quote]
                                                  {:swap-token-address "0xusdt"
                                                   :approval-address "0xapproval"
                                                   :from-amount-units (js/BigInt "5000000")
                                                   :swap-to-address "0xrouter"
                                                   :swap-data "0xswap"})
                       :read-erc20-balance-units! (fn [& _args]
                                                    (js/Promise.resolve (js/BigInt "100"))))
                (atom {})
                "0xowner"
                {:amount "5"})])
          (.then (fn [results]
                   (is (= [{:status "err"
                            :error "wallet: LiFi quote response missing required transaction fields."}
                           {:status "err"
                            :error "wallet: Swap completed but no USDC was received for deposit."}]
                          (js->clj results :keywordize-keys true)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
