(ns hyperopen.funding.application.hyperevm-submit
  "HyperEVM -> HyperCore moves: the wallet transactions behind the
   client-only `hyperEvmToCore` action.

   The flow, in order:

   1. check the provider, the owner and the pre-signing invariant;
   2. read the gas price (and, for USDC, the allowance) from the public RPC;
   3. estimate the first transaction's gas;
   4. switch the wallet to HyperEVM, verified by re-reading `eth_chainId`;
   5. send each transaction (USDC: approve when needed, then deposit),
      re-reading `eth_chainId` right before every `eth_sendTransaction`, and
      wait for its receipt through the RPC before the next one. The
      deposit's gas is estimated only after the approve confirmed.

   Two rules keep funds safe:

   - Every transaction is pinned to chain 0x3e7 and is sent only while the
     wallet is on it; a wallet that changed network stops the flow before
     anything more is sent.
   - Once a hash exists, the flow never reports a plain failure for it: a
     receipt that does not arrive (or any unexpected error while one is
     outstanding) ends as `pending`, never as something the user could
     simply submit again. Hashes are recorded through `record-in-flight!` as
     soon as the wallet returns them.

   Everything that talks to the wallet or the network is injected (see
   `hyperopen.funding.effects.hyperevm-runtime`). The result is a promise
   that never rejects:

       {:status \"ok\" :txHash :explorer-url :hashes}
       {:status \"pending\" :txHash :explorer-url :step :hashes}
       {:status \"err\" :error :step :kind :tx-hash :hashes}"
  (:require [clojure.string :as str]
            [hyperopen.funding.domain.transfer-invariants :as transfer-invariants]
            [hyperopen.funding.domain.transfer-run :as transfer-run]
            [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.txs :as txs]))

(def ^:private messages transfer-run/messages)

;; --- errors -------------------------------------------------------------------

(defn- failure
  "A flow failure: nothing unresolved is left on chain for `step`."
  ([step message]
   (failure step message nil))
  ([step message extra]
   (ex-info message (merge {::failure true :step step} extra))))

(defn- error-data
  [err]
  (or (ex-data err) {}))

(defn- error-field
  [err field]
  (when (some? err)
    (or (get (error-data err) (keyword field))
        (when (or (instance? js/Error err) (object? err))
          (aget err field)))))

(defn- error-message
  [err]
  (str/trim (str (or (error-field err "message") (when (string? err) err) ""))))

(defn- user-rejected?
  [err]
  (or (= 4001 (error-field err "code"))
      (let [message (str/lower-case (error-message err))]
        (or (str/includes? message "user rejected")
            (str/includes? message "user denied")))))

(defn- with-reason
  [prefix err]
  (let [reason (error-message err)]
    (if (seq reason) (str prefix ": " reason) (str prefix "."))))

(defn- switch-failure
  [err]
  (cond
    (= :chain-switch-unsupported (:kind (error-data err)))
    (failure :switch-network (:switch-unsupported messages) {:kind :chain-switch-unsupported})

    ;; The wallet switched, but not to HyperEVM (or moved on right away):
    ;; it can switch, so nothing is cached against it.
    (= :chain-changed (:kind (error-data err)))
    (failure :switch-network (:switch-elsewhere messages) {:kind :chain-changed})

    (user-rejected? err)
    (failure :switch-network (:switch-rejected messages) {:kind :rejected})

    :else
    (failure :switch-network (with-reason "Couldn't switch your wallet to HyperEVM" err)
             {:kind :wallet})))

(defn- send-failure
  [step err]
  (let [approve? (= :approve step)]
    (if (user-rejected? err)
      (failure step
               (if approve? (:approve-rejected messages) (:transfer-rejected messages))
               {:kind :rejected})
      (failure step
               (with-reason (if approve? "The approval failed" "The transfer failed") err)
               {:kind :wallet}))))

(defn- revert-reason
  [err]
  (-> (error-message err)
      (str/replace #"^HyperEVM RPC eth_estimateGas failed:?\s*" "")
      (str/replace #"\.$" "")))

(def ^:private execution-revert-code
  "The JSON-RPC code nodes use for `execution reverted` with revert data."
  3)

(defn- estimate-rejected?
  "Whether a failed `eth_estimateGas` says the transaction itself would
   fail on chain (it reverts, or the sender can't pay for it), as opposed to
   a node hiccup (-32603 internal error, -32000 header not found or timed
   out, a rate limit, a transport failure), after which the fee table is
   used instead."
  [err]
  (let [data (error-data err)
        message (str/lower-case (revert-reason err))]
    (and (= :rpc (:kind data))
         (or (= execution-revert-code (:code data))
             (str/includes? message "revert")
             (str/includes? message "insufficient funds")))))

;; --- steps --------------------------------------------------------------------

(defn- valid-hash?
  [value]
  (and (string? value) (re-matches #"^0x[0-9a-fA-F]{64}$" value)))

(defn- report!
  "Report a step event, never letting a reporting failure break the flow."
  [{:keys [on-step!]} step-id event]
  (when (fn? on-step!)
    (try
      (on-step! step-id event)
      (catch :default _ nil))))

(defn- priced!
  "Estimate `tx`'s gas through the RPC and price it. Transport and node
   problems fall back to the fee table; an execution revert stops the flow
   with its reason, because the transaction would fail on chain."
  [{:keys [deps gas-price]} {:keys [step gas-kind tx]}]
  (-> ((:estimate-gas! deps) (txs/estimate-request tx))
      (.catch (fn [err]
                (if (estimate-rejected? err)
                  (throw (failure step
                                  (let [reason (revert-reason err)]
                                    (str "HyperEVM would reject this transfer"
                                         (when (seq reason) (str ": " reason))
                                         "."))
                                  {:kind :estimate-reverted}))
                  nil)))
      (.then (fn [estimate]
               (or (txs/priced-tx tx gas-kind estimate gas-price)
                   (throw (failure step (:build-failed messages))))))))

(defn- confirm!
  "Wait for `hash`'s receipt. A timeout (or anything unexpected) leaves the
   hash unresolved and ends the flow as pending. Once the receipt confirmed,
   the bookkeeping that follows (the in-flight entry, the step report) can
   never fail the flow: a confirmed transaction reported as a retryable
   failure would invite the same move twice."
  [{:keys [deps unresolved owner flow-id] :as ctx} step hash]
  (-> ((:wait-for-receipt! deps) hash)
      (.then (fn [_]
               (try
                 ((:record-in-flight! deps) owner flow-id {:waiting-receipt? false})
                 (catch :default _ nil))
               (swap! unresolved disj hash)
               (report! ctx step :done)
               hash))
      (.catch (fn [err]
                (if (= :reverted (:kind (error-data err)))
                  (do (swap! unresolved disj hash)
                      (throw (failure step
                                      (if (= :approve step)
                                        (:approve-reverted messages)
                                        (:reverted messages))
                                      {:kind :reverted :tx-hash hash})))
                  (throw err))))))

(defn- send-pinned!
  "Send `tx` for `step`, but only while the wallet is on HyperEVM
   (`chain-id` was read right before). The hash is recorded before the
   receipt wait starts."
  [{:keys [deps provider from owner flow-id unresolved hashes] :as ctx} {:keys [step tx]} chain-id]
  (if (not= txs/chain-id chain-id)
    (js/Promise.reject (failure step (:chain-left messages) {:kind :chain-changed}))
    (do
      (report! ctx step :active)
      (-> ((:send-transaction! deps) provider from tx)
          (.catch (fn [err] (throw (send-failure step err))))
          (.then (fn [hash]
                   (when-not (valid-hash? hash)
                     (throw (failure step (:no-hash messages) {:kind :no-hash})))
                   (swap! unresolved conj hash)
                   (swap! hashes conj hash)
                   ((:record-in-flight! deps) owner flow-id
                    {:hash hash :step step :submitted-at-ms ((:now-ms-fn deps))})
                   (report! ctx step :confirming)
                   (confirm! ctx step hash)))))))

(defn- send-rest!
  "Price, chain-check and send each remaining transaction in turn."
  [{:keys [deps provider] :as ctx} remaining last-hash]
  (if-let [item (first remaining)]
    (-> (priced! ctx item)
        (.then (fn [tx]
                 (-> ((:request-chain-id! deps) provider)
                     (.catch (fn [err]
                               (throw (failure (:step item)
                                               (with-reason "Couldn't read your wallet's network" err)
                                               {:kind :wallet}))))
                     (.then (fn [chain-id]
                              (send-pinned! ctx (assoc item :tx tx) chain-id))))))
        (.then (fn [hash] (send-rest! ctx (rest remaining) hash))))
    (js/Promise.resolve last-hash)))

(defn- allowance-plan!
  "The plan without the USDC approve when the allowance already covers the
   amount (the step then reads \"Already approved\")."
  [{:keys [deps from action] :as ctx} plan]
  (if-not (some :optional? plan)
    (js/Promise.resolve plan)
    (-> ((:read-allowance! deps) (:tokenAddress action) from (:spender action))
        (.catch (fn [_] (throw (failure nil (:allowance messages) {:kind :read-failed}))))
        (.then (fn [allowance]
                 (if (txs/allowance-covers? allowance (:units action))
                   (do (report! ctx :approve :skipped)
                       (vec (remove :optional? plan)))
                   plan))))))

(defn- run-flow!
  [{:keys [deps provider] :as ctx} plan]
  (-> ((:gas-price! deps))
      (.catch (fn [_] (throw (failure nil (:gas-price messages) {:kind :read-failed}))))
      (.then (fn [gas-price]
               (let [ctx* (assoc ctx :gas-price gas-price)]
                 (-> (allowance-plan! ctx* plan)
                     (.then (fn [plan*]
                              (let [[first-item & remaining] plan*]
                                (-> (priced! ctx* first-item)
                                    (.then (fn [tx]
                                             (-> ((:ensure-wallet-chain! deps) provider (:chain-config deps))
                                                 (.catch (fn [err] (throw (switch-failure err))))
                                                 (.then (fn [chain-id]
                                                          (report! ctx* :switch-network :done)
                                                          ;; The verified switch is the read
                                                          ;; right before the first send.
                                                          (send-pinned! ctx*
                                                                        (assoc first-item :tx tx)
                                                                        chain-id))))))
                                    (.then (fn [hash] (send-rest! ctx* remaining hash)))))))))))))

(defn- outcome
  "The flow's result for how it ended."
  [{:keys [unresolved hashes]} final-hash err]
  (let [data (when err (error-data err))
        outstanding (last (filter @unresolved @hashes))]
    (cond
      (and (nil? err) (string? final-hash))
      {:status "ok"
       :txHash final-hash
       :explorer-url (chain/explorer-tx-url final-hash)
       :hashes @hashes}

      ;; A transaction is still unresolved: whatever went wrong, it is
      ;; pending, never a failure the user could submit again.
      (some? outstanding)
      {:status "pending"
       :txHash outstanding
       :explorer-url (chain/explorer-tx-url outstanding)
       :step (:step data)
       :hashes @hashes}

      (::failure data)
      {:status "err"
       :error (.-message err)
       :step (:step data)
       :kind (:kind data)
       :tx-hash (:tx-hash data)
       :hashes @hashes}

      :else
      {:status "err"
       :error (with-reason "The transfer failed" err)
       :step nil
       :kind :unexpected
       :hashes @hashes})))

(defn- resolve-err
  [step message kind]
  (js/Promise.resolve {:status "err" :error message :step step :kind kind :hashes []}))

(defn- steps-with-pending-step
  "The outcome's `:step` for a pending result: the step whose hash is
   outstanding (the deposit/send, or the approve)."
  [result step-by-hash]
  (if (= "pending" (:status result))
    (assoc result :step (get step-by-hash (:txHash result) (:step result)))
    result))

(defn submit-hyperevm-to-core!
  "Send the HyperEVM transactions for `action` (a `hyperEvmToCore`) from
   `owner`. `opts` carries `:flow-id` (recorded with each hash) and
   `:on-step!`, called as `(on-step! step-id event)` for progress.

   `deps`: `:wallet-provider-fn`, `:state-fn` (app state, for the
   invariant), `:chain-config` (HyperEVM, with `:verify-switch?`),
   `:ensure-wallet-chain!`, `:request-chain-id!`, `:send-transaction!`,
   `:gas-price!`, `:estimate-gas!`, `:read-allowance!`,
   `:wait-for-receipt!`, `:record-in-flight!` `(owner flow-id changes)`,
   `:now-ms-fn`."
  ([deps owner action]
   (submit-hyperevm-to-core! deps owner action {}))
  ([{:keys [wallet-provider-fn state-fn] :as deps} owner action {:keys [flow-id on-step!]}]
   (let [provider (when (fn? wallet-provider-fn) (wallet-provider-fn))
         from (abi/normalize-address owner)
         invariant-error (transfer-invariants/check-request
                          (if (fn? state-fn) (state-fn) {})
                          {:action action})
         plan (try (txs/transfer-plan from action) (catch :default _ nil))
         step-by-hash (atom {})
         deps* (update deps :record-in-flight!
                       (fn [record!]
                         (fn [owner* flow-id* changes]
                           (when-let [hash (:hash changes)]
                             (swap! step-by-hash assoc hash (:step changes)))
                           (when (fn? record!) (record! owner* flow-id* changes)))))
         ctx {:deps deps*
              :provider provider
              :from from
              :owner owner
              :action action
              :flow-id flow-id
              :on-step! on-step!
              :unresolved (atom #{})
              :hashes (atom [])}]
     (cond
       (nil? provider) (resolve-err nil (:no-provider messages) :no-provider)
       (nil? from) (resolve-err nil (:no-owner messages) :no-owner)
       (seq invariant-error) (resolve-err nil invariant-error :invariant)
       (or (empty? plan) (some (comp nil? :tx) plan)) (resolve-err nil (:build-failed messages) :build)
       :else
       (-> (js/Promise.resolve nil)
           (.then (fn [_] (run-flow! ctx plan)))
           (.then (fn [final-hash] (outcome ctx final-hash nil))
                  (fn [err] (outcome ctx nil err)))
           (.then (fn [result] (steps-with-pending-step result @step-by-hash))))))))
