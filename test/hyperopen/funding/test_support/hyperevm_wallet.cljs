(ns hyperopen.funding.test-support.hyperevm-wallet
  "A fake EIP-1193 wallet and a fake HyperEVM JSON-RPC endpoint for the
   HyperEVM -> Core submit tests. Both append to one ordered event log, so a
   test can assert what the wallet was asked relative to what the RPC was
   asked (for example that the deposit's gas estimate follows the approve's
   receipt). The submit runs through the real wiring
   (`hyperevm-runtime/submit-deps`: real `wallet-rpc`, real RPC client)."
  (:require [hyperopen.funding.application.hyperevm-submit :as hyperevm-submit]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-preview]
            [hyperopen.funding.effects.hyperevm-runtime :as hyperevm-runtime]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.domain.tokens :as tokens]))

(defn tx-hash
  "Deterministic transaction hash for the wallet's `n`th send (1-based)."
  [n]
  (let [hex (.toString n 16)]
    (str "0x" (apply str (repeat (- 64 (count hex)) "a")) hex)))

(defn- wallet-error
  [code message]
  #js {:code code :message message})

(defn- next-chain-read
  "The chain the wallet reports: the next scripted read (`:error` rejects),
   else the chain it is on."
  [state]
  (let [[scripted & more] (:chain-reads @state)]
    (if (some? scripted)
      (do (swap! state assoc :chain-reads (vec more))
          (if (= :error scripted)
            (js/Promise.reject (wallet-error -32603 "Internal error"))
            (js/Promise.resolve scripted)))
      (js/Promise.resolve (:chain @state)))))

(defn fake-wallet
  "A provider stub. `config`:

   - `:chain` the starting chain (default \"0xa4b1\");
   - `:chain-reads` scripted `eth_chainId` answers, used first, in order
     (`:error` rejects that read);
   - `:switch-error` `[code message]` rejects every switch;
   - `:switch-noop?` resolves the switch without changing chain;
   - `:unknown-chain?` rejects the first switch with 4902 (nested when
     `:nested-4902?`);
   - `:send-errors` `{n [code message]}` rejects the `n`th send;
   - `:send-answers` `{n value}` answers the `n`th send with `value`
     instead of a hash (nil: a wallet that returns nothing);
   - `:defer-sends?` makes every send wait for `release-send!`.

   Returns `{:provider :state}`; `(:log @state)` is the request log."
  [log config]
  (let [state (atom (merge {:chain "0xa4b1" :sends 0 :deferred [] :known? (not (:unknown-chain? config))}
                           config))
        record! (fn [method params]
                  (swap! log conj [:wallet method (js->clj params :keywordize-keys true)]))
        handle (fn [method params]
                 (case method
                   "eth_chainId" (next-chain-read state)

                   "wallet_switchEthereumChain"
                   (let [target (:chainId (first (js->clj params :keywordize-keys true)))]
                     (cond
                       (:switch-error @state)
                       (js/Promise.reject (apply wallet-error (:switch-error @state)))

                       (not (:known? @state))
                       (js/Promise.reject
                        (if (:nested-4902? @state)
                          #js {:code -32603 :message "Internal error"
                               :data #js {:originalError #js {:code 4902}}}
                          (wallet-error 4902 "Unrecognized chain ID")))

                       :else
                       (do (when-not (:switch-noop? @state)
                             (swap! state assoc :chain target))
                           (js/Promise.resolve nil))))

                   "wallet_addEthereumChain"
                   (do (swap! state assoc :known? true)
                       (js/Promise.resolve nil))

                   "eth_sendTransaction"
                   (let [n (:sends (swap! state update :sends inc))]
                     (cond
                       (contains? (:send-errors @state) n)
                       (let [[code message] (get (:send-errors @state) n)]
                         (js/Promise.reject (wallet-error code message)))

                       (contains? (:send-answers @state) n)
                       (js/Promise.resolve (get (:send-answers @state) n))

                       :else
                       (if (:defer-sends? @state)
                         (js/Promise. (fn [resolve _]
                                        (swap! state update :deferred conj
                                               #(resolve (tx-hash n)))))
                         (js/Promise.resolve (tx-hash n)))))

                   "wallet_watchAsset" (js/Promise.resolve true)

                   (js/Promise.resolve nil)))]
    {:state state
     :provider #js {:request (fn [payload]
                               (let [method (aget payload "method")
                                     params (aget payload "params")]
                                 (record! method params)
                                 (handle method params)))}}))

(defn release-send!
  "Let the oldest deferred send return its hash."
  [{:keys [state]}]
  (let [[release & more] (:deferred @state)]
    (swap! state assoc :deferred (vec more))
    (when release (release))))

(defn wallet-requests
  "The wallet part of `log` as `[method params]`."
  [log]
  (into [] (keep (fn [[kind method params]] (when (= :wallet kind) [method params]))) log))

(defn wallet-methods
  [log]
  (mapv first (wallet-requests log)))

(defn sent-transactions
  "The params of every `eth_sendTransaction`, in order."
  [log]
  (into [] (keep (fn [[method params]]
                   (when (= "eth_sendTransaction" method) (first params))))
        (wallet-requests log)))

(defn- json-response
  [payload]
  #js {:ok true
       :status 200
       :json (fn [] (js/Promise.resolve (clj->js payload)))})

(def gas-price-hex
  "0.1 gwei."
  "0x5f5e100")

(defn fake-rpc
  "A fetch-fn answering single JSON-RPC requests. `config`:

   - `:estimates` a queue of `eth_estimateGas` answers (hex, or
     `{:error {..}}`), default \"0x5208\";
   - `:allowance` the `allowance` eth_call answer in units (default 0);
   - `:receipts` `{hash [answer ..]}` scripted receipt answers per hash:
     a receipt map, nil (pending) or `:rate-limited`; the last one repeats.
     A hash without a script gets a success receipt at once;
   - `:gas-price-error?` fails `eth_gasPrice`;
   - `:allowance-error?` fails the `allowance` eth_call."
  [log config]
  (let [state (atom (merge {:estimates [] :allowance 0 :receipts {}} config))]
    (fn [_url init]
      (let [body (js->clj (js/JSON.parse (.-body init)) :keywordize-keys true)
            method (:method body)
            [param] (:params body)
            reply (fn [result] (js/Promise.resolve
                                (json-response {:jsonrpc "2.0" :id (:id body) :result result})))
            reply-error (fn [error] (js/Promise.resolve
                                     (json-response {:jsonrpc "2.0" :id (:id body) :error error})))]
        (swap! log conj [:rpc method param])
        (case method
          "eth_gasPrice" (if (:gas-price-error? @state)
                           (reply-error {:code -32000 :message "unavailable"})
                           (reply gas-price-hex))

          "eth_estimateGas"
          (let [[answer & more] (:estimates @state)]
            (swap! state assoc :estimates (vec more))
            (if (map? answer) (reply-error (:error answer)) (reply (or answer "0x5208"))))

          "eth_call" (if (:allowance-error? @state)
                       (reply-error {:code -32000 :message "header not found"})
                       (reply (str "0x" (let [hex (.toString (js/BigInt (:allowance @state)) 16)]
                                          (str (apply str (repeat (- 64 (count hex)) "0")) hex)))))

          "eth_getTransactionReceipt"
          (let [scripted (get-in @state [:receipts param])
                [answer & more] scripted]
            (when (seq more) (swap! state assoc-in [:receipts param] (vec more)))
            (cond
              (nil? scripted) (reply {:status "0x1" :transactionHash param})
              (= :rate-limited answer) (reply-error {:code -32005 :message "rate limited"})
              :else (reply answer)))

          (reply nil))))))

(defn rpc-methods
  [log]
  (into [] (keep (fn [[kind method]] (when (= :rpc kind) method))) log))

(defn microtask-schedule
  "A receipt-poll scheduler that re-polls on the next microtask."
  [f _ms]
  (.then (js/Promise.resolve nil) (fn [_] (f))))

(defn stepping-clock
  "A clock that advances `step-ms` on every read."
  [step-ms]
  (let [now (atom 0)]
    (fn [] (swap! now + step-ms))))

(defn submit-deps
  "The real `hyperevm-runtime/submit-deps` over a fake wallet and RPC."
  [store {:keys [provider fetch-fn now-ms-fn]}]
  (let [clock (or now-ms-fn (stepping-clock 1))]
    (hyperevm-runtime/submit-deps
     store
     {:wallet-provider-fn (constantly provider)
      :rpc-deps {:fetch-fn fetch-fn
                 :set-timeout-fn (fn [_ _] :no-deadline)
                 :clear-timeout-fn (fn [_] nil)
                 :make-abort-controller (fn [] nil)}
      :receipt-opts {:poll-ms 1
                     :schedule-poll-fn microtask-schedule
                     :now-ms-fn clock}
      :now-ms-fn clock})))

(defn call-data
  "Expected calldata helpers for assertions."
  [kind & args]
  (case kind
    :approve (apply abi/encode-approve args)
    :deposit (apply abi/encode-core-deposit args)
    :transfer (apply abi/encode-transfer args)))

(defn request
  "The real preview's `hyperEvmToCore` request for `amount` of token
   `index` from the owner in `state`."
  [state index amount]
  (evm-preview/evm->core-request state
                                 (tokens/token-by-index (get-in state [:spot :meta]) index)
                                 amount))

(defn submit!
  "Submit `index`/`amount` from the owner with a fake wallet
   (`wallet-config`) and RPC (`rpc-config`) through the real wiring.
   Resolves with `{:result :log :steps :records :wallet}`; `:records` are the
   in-flight changes the submitter recorded, also logged as `[:record ..]`."
  ([index amount wallet-config rpc-config]
   (submit! index amount wallet-config rpc-config {}))
  ([index amount wallet-config rpc-config {:keys [now-ms-fn throw-on-confirmed-record?]}]
   (let [log (atom [])
         steps (atom [])
         records (atom [])
         store (atom (support/state))
         wallet (fake-wallet log wallet-config)
         deps (-> (submit-deps store {:provider (:provider wallet)
                                      :fetch-fn (fake-rpc log rpc-config)
                                      :now-ms-fn now-ms-fn})
                  (update :record-in-flight!
                          (fn [record!]
                            (fn [owner flow-id changes]
                              (swap! log conj [:record changes])
                              (swap! records conj changes)
                              ;; A store watcher that throws on the write
                              ;; after a confirmed receipt.
                              (when (and throw-on-confirmed-record?
                                         (false? (:waiting-receipt? changes)))
                                (throw (js/Error. "store watcher threw")))
                              (record! owner flow-id changes)))))]
     (-> (hyperevm-submit/submit-hyperevm-to-core!
          deps support/owner (:action (request @store index amount))
          {:flow-id "flow-1"
           :on-step! (fn [step event] (swap! steps conj [step event]))})
         (.then (fn [result]
                  {:result result :log @log :steps @steps :records @records :wallet wallet}))))))
