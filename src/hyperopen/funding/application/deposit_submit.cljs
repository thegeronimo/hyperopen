(ns hyperopen.funding.application.deposit-submit)

(defn- deposit-submit-error
  [provider from-address amount-units]
  (cond
    (nil? provider)
    "No wallet provider found. Connect your wallet first."

    (nil? from-address)
    "Connect your wallet before depositing."

    (nil? amount-units)
    "Enter a valid deposit amount."

    (<= amount-units (js/BigInt "0"))
    "Enter an amount greater than 0."

    :else nil))

(defn- error-response
  [message]
  (js/Promise.resolve {:status "err"
                       :error message}))

(defn submit-usdc-bridge2-deposit-tx!
  [{:keys [wallet-provider-fn
           normalize-address
           resolve-deposit-chain-config
           parse-usdc-units
           ensure-wallet-chain!
           provider-request!
           wait-for-transaction-receipt!
           encode-erc20-transfer-call-data
           wallet-error-message]}
   store
   owner-address
   action]
  (let [provider (wallet-provider-fn)
        from-address (normalize-address owner-address)
        chain-config (resolve-deposit-chain-config store action)
        amount-units (parse-usdc-units (:amount action))
        usdc-address (:usdc-address chain-config)
        bridge-address (:bridge-address chain-config)]
    (if-let [message (deposit-submit-error provider from-address amount-units)]
      (error-response message)
      (-> (ensure-wallet-chain! provider chain-config)
          (.then (fn [_]
                   (provider-request! provider
                                      "eth_sendTransaction"
                                      [{:from from-address
                                        :to usdc-address
                                        :data (encode-erc20-transfer-call-data bridge-address amount-units)}])))
          (.then (fn [tx-hash]
                   (-> (wait-for-transaction-receipt! provider tx-hash)
                       (.then (fn [_]
                                {:status "ok"
                                 :txHash tx-hash
                                 :network (:network-label chain-config)})))))
          (.catch (fn [err]
                    {:status "err"
                     :error (wallet-error-message err)}))))))

(defn- approve-lifi-swap-token-if-needed!
  [{:keys [read-erc20-allowance-units!
           provider-request!
           encode-erc20-approve-call-data
           wait-for-transaction-receipt!]}
   provider
   from-address
   {:keys [swap-token-address approval-address from-amount-units]}]
  (-> (read-erc20-allowance-units! provider
                                   swap-token-address
                                   from-address
                                   approval-address)
      (.then (fn [allowance-units]
               (if (< allowance-units from-amount-units)
                 (-> (provider-request! provider
                                        "eth_sendTransaction"
                                        [{:from from-address
                                          :to swap-token-address
                                          :data (encode-erc20-approve-call-data approval-address
                                                                                from-amount-units)
                                          :value "0x0"}])
                     (.then (fn [approve-tx-hash]
                              (wait-for-transaction-receipt! provider approve-tx-hash))))
                 (js/Promise.resolve nil))))))

(defn- submit-usdc-delta-bridge2-deposit!
  [{:keys [submit-usdc-bridge2-deposit!
           usdc-units->amount-text
           bridge-chain-id]}
   store
   owner-address
   delta-units]
  (if (<= delta-units (js/BigInt "0"))
    (js/Promise.reject
     (js/Error. "Swap completed but no USDC was received for deposit."))
    (submit-usdc-bridge2-deposit! store
                                  owner-address
                                  (cond-> {:amount (usdc-units->amount-text delta-units)}
                                    (some? bridge-chain-id)
                                    (assoc :chainId bridge-chain-id)))))

(defn- execute-lifi-swap-and-bridge2-deposit!
  [{:keys [read-erc20-balance-units!
           provider-request!
           wait-for-transaction-receipt!]
    :as deps}
   store
   owner-address
   provider
   from-address
   usdc-address
   {:keys [swap-to-address swap-data swap-value]}]
  (let [swap-transaction (cond-> {:from from-address
                                  :to swap-to-address
                                  :data swap-data}
                           (seq swap-value) (assoc :value swap-value))]
    (-> (read-erc20-balance-units! provider usdc-address from-address)
        (.then (fn [before-balance]
                 (-> (provider-request! provider
                                        "eth_sendTransaction"
                                        [swap-transaction])
                     (.then (fn [swap-tx-hash]
                              (wait-for-transaction-receipt! provider swap-tx-hash)))
                     (.then (fn [_]
                              (read-erc20-balance-units! provider usdc-address from-address)))
                     (.then (fn [after-balance]
                              (submit-usdc-delta-bridge2-deposit!
                               deps
                               store
                               owner-address
                               (- after-balance before-balance))))))))))

(defn- fetch-lifi-swap-config!
  [{:keys [fetch-lifi-quote!
           lifi-quote->swap-config]}
   from-address
   amount-units
   usdc-address]
  (-> (fetch-lifi-quote! from-address amount-units usdc-address)
      (.then (fn [quote]
               (let [swap-config (lifi-quote->swap-config quote)]
                 (if (nil? swap-config)
                   (js/Promise.reject
                    (js/Error. "LiFi quote response missing required transaction fields."))
                   swap-config))))))

(defn- approve-and-execute-lifi-swap!
  [{:keys [read-erc20-allowance-units!
           provider-request!
           encode-erc20-approve-call-data
           wait-for-transaction-receipt!
           read-erc20-balance-units!
           submit-usdc-bridge2-deposit!
           usdc-units->amount-text
           bridge-chain-id]}
   store
   owner-address
   provider
   from-address
   usdc-address
   swap-config]
  (-> (approve-lifi-swap-token-if-needed!
       {:read-erc20-allowance-units! read-erc20-allowance-units!
        :provider-request! provider-request!
        :encode-erc20-approve-call-data encode-erc20-approve-call-data
        :wait-for-transaction-receipt! wait-for-transaction-receipt!}
       provider
       from-address
       swap-config)
      (.then (fn [_]
               (execute-lifi-swap-and-bridge2-deposit!
                {:read-erc20-balance-units! read-erc20-balance-units!
                 :provider-request! provider-request!
                 :wait-for-transaction-receipt! wait-for-transaction-receipt!
                 :submit-usdc-bridge2-deposit! submit-usdc-bridge2-deposit!
                 :usdc-units->amount-text usdc-units->amount-text
                 :bridge-chain-id bridge-chain-id}
                store
                owner-address
                provider
                from-address
                usdc-address
                swap-config)))))

(defn submit-usdt-lifi-bridge2-deposit-tx!
  [{:keys [wallet-provider-fn
           normalize-address
           parse-usdc-units
           chain-config
           ensure-wallet-chain!
           fetch-lifi-quote!
           lifi-quote->swap-config
           read-erc20-allowance-units!
           encode-erc20-approve-call-data
           provider-request!
           wait-for-transaction-receipt!
           read-erc20-balance-units!
           submit-usdc-bridge2-deposit!
           usdc-units->amount-text
           bridge-chain-id
           wallet-error-message]}
   store
   owner-address
   action]
  (let [provider (wallet-provider-fn)
        from-address (normalize-address owner-address)
        amount-units (parse-usdc-units (:amount action))
        usdc-address (:usdc-address chain-config)]
    (if-let [message (deposit-submit-error provider from-address amount-units)]
      (error-response message)
      (-> (ensure-wallet-chain! provider chain-config)
          (.then (fn [_]
                   (fetch-lifi-swap-config! {:fetch-lifi-quote! fetch-lifi-quote!
                                             :lifi-quote->swap-config lifi-quote->swap-config}
                                            from-address
                                            amount-units
                                            usdc-address)))
          (.then (fn [swap-config]
                   (approve-and-execute-lifi-swap!
                    {:read-erc20-allowance-units! read-erc20-allowance-units!
                     :provider-request! provider-request!
                     :encode-erc20-approve-call-data encode-erc20-approve-call-data
                     :wait-for-transaction-receipt! wait-for-transaction-receipt!
                     :read-erc20-balance-units! read-erc20-balance-units!
                     :submit-usdc-bridge2-deposit! submit-usdc-bridge2-deposit!
                     :usdc-units->amount-text usdc-units->amount-text
                     :bridge-chain-id bridge-chain-id}
                    store
                    owner-address
                    provider
                    from-address
                    usdc-address
                    swap-config)))
          (.catch (fn [err]
                    {:status "err"
                     :error (wallet-error-message err)}))))))

(defn- send-across-approval-transactions!
  [send-and-confirm-evm-transaction!
   provider
   from-address
   approval-txs]
  (reduce (fn [promise approval-tx]
            (.then promise
                   (fn [_]
                     (send-and-confirm-evm-transaction! provider
                                                        from-address
                                                        approval-tx))))
          (js/Promise.resolve nil)
          approval-txs))

(defn submit-usdh-across-deposit-tx!
  [{:keys [wallet-provider-fn
           normalize-address
           parse-usdh-units
           usdh-route-max-units
           chain-config
           ensure-wallet-chain!
           fetch-across-approval!
           across-approval->swap-config
           send-and-confirm-evm-transaction!
           wallet-error-message]}
   _store
   owner-address
   action]
  (let [provider (wallet-provider-fn)
        from-address (normalize-address owner-address)
        amount-units (parse-usdh-units (:amount action))
        usdc-address (:usdc-address chain-config)]
    (cond
      (nil? provider)
      (js/Promise.resolve {:status "err"
                           :error "No wallet provider found. Connect your wallet first."})

      (nil? from-address)
      (js/Promise.resolve {:status "err"
                           :error "Connect your wallet before depositing."})

      (nil? amount-units)
      (js/Promise.resolve {:status "err"
                           :error "Enter a valid deposit amount."})

      (<= amount-units (js/BigInt "0"))
      (js/Promise.resolve {:status "err"
                           :error "Enter an amount greater than 0."})

      (> amount-units usdh-route-max-units)
      (js/Promise.resolve {:status "err"
                           :error "Maximum deposit is 1000000 USDH."})

      :else
      (-> (ensure-wallet-chain! provider chain-config)
          (.then (fn [_]
                   (fetch-across-approval! from-address amount-units usdc-address)))
          (.then (fn [approval]
                   (let [{:keys [swap-tx approval-txs]} (across-approval->swap-config approval)]
                     (if (nil? swap-tx)
                       (js/Promise.reject
                        (js/Error. "Across approval response missing swap transaction fields."))
                       (-> (send-across-approval-transactions! send-and-confirm-evm-transaction!
                                                               provider
                                                               from-address
                                                               approval-txs)
                           (.then (fn [_]
                                    (send-and-confirm-evm-transaction! provider
                                                                       from-address
                                                                       swap-tx))))))))
          (.then (fn [tx-hash]
                   {:status "ok"
                    :txHash tx-hash
                    :network (:network-label chain-config)}))
          (.catch (fn [err]
                    {:status "err"
                     :error (wallet-error-message err)}))))))
