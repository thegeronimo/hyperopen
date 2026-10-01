(ns hyperopen.funding.application.hyperevm-transfer-effects
  "Submit effects for Transfer routes that touch HyperEVM, plus the follow-up
   effects their runs need.

   - HyperCore -> HyperEVM (`sendAsset` with `:route :core->evm`): one
     signature. The modal stays open on success and follows the arrival.
   - HyperEVM -> HyperCore (`hyperEvmToCore`): wallet transactions sent by
     `hyperopen.funding.application.hyperevm-submit`. The owner's entry at
     `[:hyperevm :in-flight]` is written before the wallet is asked for
     anything and blocks every other HyperEVM -> Core move until no
     transaction is left unresolved.
   - `resolve-in-flight-receipt!` settles a move that ended `pending`, in the
     background (dispatched by the HyperEVM balance poller).
   - `wallet-watch-asset!` adds the moved token to the wallet.

   Refusals before a run starts (read-only, subaccount, no wallet, an
   earlier move still confirming, a failed pre-signing invariant) are shown
   on the form like any Transfer error. Once a run starts, every modal write
   is guarded by its flow id (`hyperevm-run-state/update-run!`)."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.application.hyperevm-run-state :as run-state]
            [hyperopen.funding.domain.transfer-balances :as transfer-balances]
            [hyperopen.funding.domain.transfer-invariants :as transfer-invariants]
            [hyperopen.funding.domain.transfer-run :as transfer-run]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]
            [hyperopen.hyperevm.domain.txs :as txs]))

(def in-flight-message
  transfer-state/in-flight-message)

(defn- toast!
  [{:keys [store show-toast!]} kind message]
  (when (and (fn? show-toast!) (seq message))
    (show-toast! store kind message)))

(defn- outcome-toast!
  "Toast a run's outcome only when the modal no longer shows that run
   (closed, Done, a new draft): an open modal already says it, and on a
   phone the toast would cover the run view's own actions."
  [{:keys [store] :as deps} flow-id kind message]
  (when-not (run-state/run-current? @store flow-id)
    (toast! deps kind message)))

(defn- error-text
  [f value fallback]
  (let [text (str/trim (str (when (fn? f) (f value))))]
    (if (seq text) text fallback)))

(defn- amount-symbol-text
  "`1,000 USDC`: grouped like the modal's own headings."
  [amount symbol]
  (str (transfer-balances/grouped-amount amount) " " symbol))

(defn- move-text
  [request]
  (amount-symbol-text (get-in request [:evm :amount]) (get-in request [:evm :symbol])))

(defn- preflight-refusal
  "Why a HyperEVM move must not start, or nil."
  [state request]
  (let [owner (account-context/owner-address state)
        request-owner (account-context/normalize-address (get-in request [:evm :owner]))]
    (or (account-context/hyperevm-moves-blocked-message state)
        (when (and request-owner (not= request-owner owner))
          (:owner-changed transfer-run/messages))
        (transfer-invariants/check-request state request))))

(defn- refuse!
  [{:keys [store show-toast! set-funding-submit-error!]} message]
  (set-funding-submit-error! store show-toast! message))

;; --- HyperCore -> HyperEVM --------------------------------------------------------

(defn- fail-run!
  "Fail the run of `flow-id`. `opts` carries `:maybe-sent?` for a failure
   whose outcome is unknown (see `transfer-run/failed`)."
  ([deps flow-id step message]
   (fail-run! deps flow-id step message nil))
  ([{:keys [store] :as deps} flow-id step message opts]
   (run-state/update-run! store flow-id
                          #(transfer-run/failed % step message nil opts)
                          run-state/finished-modal-changes)
   (outcome-toast! deps flow-id :error message)))

(defn- refresh-user-data!
  [{:keys [store dispatch! refresh-after-funding-submit!]} owner]
  (when (fn? refresh-after-funding-submit!)
    (refresh-after-funding-submit! store dispatch! owner)))

(defn- mark-sent!
  "Record a Core -> EVM send of the request's token now, so its HyperEVM
   bridge capacity reads as unknown until a read requested after the send
   lands (`bridge/core->evm-capacity`): a second move, from a reopened
   modal while this POST is in flight or right after it, is then checked
   against a balance that shows this one's draw."
  [{:keys [store] :as deps} request]
  (swap! store
         (fn [state]
           (bridge/mark-core->evm-sent state
                                       (tokens/token-by-index (get-in state [:spot :meta])
                                                              (get-in request [:evm :token-index]))
                                       (run-state/now deps)))))

(defn- core-to-evm-sent!
  "The `sendAsset` was accepted: show the arriving run, then refresh and
   watch the destination. The follow-ups run one by one through
   `run-state/follow-up!`, so none of them can fail the run."
  [{:keys [store] :as deps} request flow-id owner arrival]
  (run-state/update-run! store flow-id
                         #(transfer-run/succeeded % nil (run-state/now deps))
                         run-state/finished-modal-changes)
  (run-state/follow-up! deps "toast"
                        #(outcome-toast! deps flow-id :success
                                         (str "Sent " (move-text request) " to HyperEVM.")))
  (run-state/follow-up! deps "user data" #(refresh-user-data! deps owner))
  (run-state/follow-up! deps "HyperEVM balances" #(run-state/refresh-hyperevm-after-transfer! deps))
  (run-state/follow-up! deps "Spot" #(run-state/refresh-spot! deps owner))
  (run-state/follow-up! deps "arrival" #(run-state/track-arrival! deps flow-id arrival)))

(defn- core-to-evm-errored!
  "Signing or posting the `sendAsset` threw. The exchange may still have
   applied it (a timed-out POST), so the run is marked `:maybe-sent?` (no
   one-click \"Try again\": \"Back to edit\" shows the balances as they are
   now) and HyperEVM and the account are read again at once, so a retry
   from the form is checked against the bridge capacity as it is now."
  [{:keys [runtime-error-message] :as deps} flow-id owner err]
  (fail-run! deps flow-id :sign
             (str "Transfer failed: "
                  (error-text runtime-error-message err "Unknown runtime error")
                  ". Check your HyperEVM balance before trying again.")
             {:maybe-sent? true})
  (run-state/follow-up! deps "user data" #(refresh-user-data! deps owner))
  (run-state/follow-up! deps "HyperEVM balances" #(run-state/refresh-hyperevm-after-transfer! deps)))

(defn submit-core-to-evm!
  "Sign and post the `sendAsset` of a HyperCore -> HyperEVM move. Success
   keeps the modal open on the arriving run; the destination is watched
   until the amount shows up on HyperEVM. Only a failed submit reaches the
   failure path: what follows a success can never mark the run failed."
  [{:keys [store request submit-send-asset! exchange-response-error] :as deps}]
  (let [state @store
        action (:action request)
        refusal (or (preflight-refusal state request)
                    (when-not (= "sendAsset" (:type action))
                      "A move to HyperEVM must be a sendAsset."))]
    (if refusal
      (refuse! deps refusal)
      (let [owner (account-context/owner-address state)
            flow-id (run-state/next-flow-id deps)
            arrival (transfer-run/arrival-plan state request)]
        (run-state/start-run! store (transfer-run/start-run flow-id request (run-state/now deps)))
        (run-state/follow-up! deps "bridge stamp" #(mark-sent! deps request))
        (-> (try
              (js/Promise.resolve (submit-send-asset! store owner action))
              (catch :default err
                (js/Promise.reject err)))
            (.then (fn [resp]
                     ;; Again once HyperCore answered: the settle time
                     ;; counts from the debit, not from the click.
                     (run-state/follow-up! deps "bridge stamp" #(mark-sent! deps request))
                     (if (= "ok" (:status resp))
                       (core-to-evm-sent! deps request flow-id owner arrival)
                       (fail-run! deps flow-id :sign
                                  (str "Transfer failed: "
                                       (error-text exchange-response-error resp
                                                   "Unknown exchange error"))))
                     resp)
                   (fn [err]
                     (run-state/follow-up! deps "bridge stamp" #(mark-sent! deps request))
                     (core-to-evm-errored! deps flow-id owner err)
                     nil))
            (.catch (fn [err]
                      ;; Only a store watcher throwing inside a run write
                      ;; gets here; the run's phase is already written.
                      (run-state/log-error! deps "settling the run" err))))))))

;; --- HyperEVM -> HyperCore --------------------------------------------------------

(defn- on-step!
  [{:keys [store]} flow-id step event]
  (when (and (= :switch-network step) (= :done event))
    (run-state/set-wallet-chain! store txs/chain-id))
  (run-state/update-run! store flow-id #(transfer-run/apply-step-event % step event)))

(defn- settled-outcome
  "A result for a submitter that rejected instead of resolving (it never
   should): pending while the in-flight entry holds a hash, since that
   transaction may land; otherwise a failure."
  [store owner err]
  (let [entry (transfer-state/in-flight-entry @store owner)
        hash (transfer-state/in-flight-tx-hash entry)]
    (if hash
      {:status "pending" :txHash hash :step (:step entry) :hashes (:hashes entry)}
      {:status "err"
       :error (str "The transfer failed: " (or (some-> err .-message) (str err)))
       :step nil
       :hashes []})))

(defn- finish-evm-to-core!
  [{:keys [store] :as deps} request flow-id owner arrival result]
  (case (:status result)
    "ok"
    (do
      (swap! store transfer-state/clear-in-flight owner flow-id)
      (run-state/update-run! store flow-id
                             #(transfer-run/succeeded % (:txHash result) (run-state/now deps))
                             run-state/finished-modal-changes)
      (run-state/follow-up! deps "toast"
                            #(outcome-toast! deps flow-id :success
                                             (str "Sent " (move-text request) " to Spot.")))
      (run-state/follow-up! deps "HyperEVM balances"
                            #(run-state/refresh-hyperevm-after-transfer! deps))
      (run-state/follow-up! deps "activation"
                            #(run-state/refresh-core-activation! deps (get-in request [:action :tokenIndex])))
      (run-state/follow-up! deps "arrival" #(run-state/track-arrival! deps flow-id arrival)))

    "pending"
    (do
      ;; The entry stays, so no second move can start; balance polling
      ;; resumes and the poller resolves the hash in the background.
      (swap! store transfer-state/merge-in-flight owner flow-id
             {:status :pending :waiting-receipt? false})
      (run-state/update-run! store flow-id
                             #(transfer-run/pending % (:step result) (:txHash result))
                             run-state/finished-modal-changes)
      (outcome-toast! deps flow-id :info (:pending transfer-run/messages))
      (run-state/refresh-hyperevm-after-transfer! deps))

    (let [unconfirmed? (= :no-hash (:kind result))]
      ;; A send the wallet answered without a usable hash may still have
      ;; been broadcast: its entry stays (`:unconfirmed`, nothing to poll),
      ;; so no second move can start until a reload.
      (if unconfirmed?
        (swap! store transfer-state/merge-in-flight owner flow-id
               {:status :unconfirmed :waiting-receipt? false})
        (swap! store transfer-state/clear-in-flight owner flow-id))
      (when (= :chain-switch-unsupported (:kind result))
        (run-state/cache-chain-switch-unsupported! store))
      (run-state/update-run! store flow-id
                             #(transfer-run/failed % (:step result) (:error result) (:tx-hash result)
                                                   {:maybe-sent? unconfirmed?})
                             run-state/finished-modal-changes)
      (outcome-toast! deps flow-id :error (:error result))
      (when (or unconfirmed? (seq (:hashes result)))
        (run-state/refresh-hyperevm-after-transfer! deps))))
  result)

(defn submit-evm-to-core!
  "Send the HyperEVM transactions of a HyperEVM -> HyperCore move. The
   owner's in-flight entry is written first, so a move started while the
   wallet prompt is still open (from a reopened modal) is refused."
  [{:keys [store request submit-hyperevm-to-core!] :as deps}]
  (let [state @store
        owner (account-context/owner-address state)
        refusal (or (preflight-refusal state request)
                    (transfer-state/in-flight-blocked-message
                     (transfer-state/in-flight-entry state owner))
                    (when-not (fn? submit-hyperevm-to-core!)
                      "Transfers from HyperEVM aren't available right now."))]
    (if refusal
      (refuse! deps refusal)
      (let [flow-id (run-state/next-flow-id deps)
            started (run-state/now deps)
            arrival (transfer-run/arrival-plan state request)
            {:keys [symbol token-index amount]} (:evm request)]
        (swap! store transfer-state/start-in-flight owner
               {:flow-id flow-id
                :started-at-ms started
                :kind (get-in request [:action :kind])
                :asset symbol
                :token-index token-index
                :amount amount
                :arrival arrival})
        (run-state/start-run! store (transfer-run/start-run flow-id request started))
        (-> (try
              (js/Promise.resolve
               (submit-hyperevm-to-core! store owner (:action request)
                                         {:flow-id flow-id
                                          :on-step! (fn [step event]
                                                      (on-step! deps flow-id step event))}))
              (catch :default err
                (js/Promise.reject err)))
            (.catch (fn [err] (settled-outcome store owner err)))
            (.then (fn [result]
                     (finish-evm-to-core! deps request flow-id owner arrival result))))))))

;; --- background resolution ------------------------------------------------------

(defn- reverted-message
  [approve?]
  (if approve?
    (:approve-reverted transfer-run/messages)
    (:reverted transfer-run/messages)))

(defn- resolve-run
  [run ok? step tx-hash now-ms]
  (if-not (= :pending (:phase run))
    run
    (cond
      (and ok? (= :approve step)) (transfer-run/approval-only run)
      ok? (transfer-run/succeeded run tx-hash now-ms)
      :else (transfer-run/failed run step (reverted-message (= :approve step)) tx-hash))))

(defn resolve-in-flight-receipt!
  "Settle `owner`'s move left `:pending` once `tx-hash` has a receipt: clear
   the in-flight entry, finish the modal's run if it still shows it, and
   tell the user. A missing receipt, or a failed read, leaves everything for
   the next check."
  [{:keys [store owner tx-hash get-transaction-receipt!] :as deps}]
  (-> (js/Promise.resolve nil)
      (.then (fn [_] (get-transaction-receipt! tx-hash)))
      (.then
       (fn [receipt]
         (let [entry (transfer-state/in-flight-entry @store owner)]
           (when (and (map? receipt)
                      entry
                      (= :pending (:status entry))
                      (= tx-hash (transfer-state/in-flight-tx-hash entry)))
             (let [ok? (= "0x1" (:status receipt))
                   step (:step entry)
                   approve? (= :approve step)
                   flow-id (:flow-id entry)
                   text (amount-symbol-text (:amount entry) (:asset entry))
                   ;; Toasts only when the modal no longer shows this run;
                   ;; its view says the same thing otherwise.
                   shown? (run-state/run-current? @store flow-id)
                   toast* (fn [kind message] (when-not shown? (toast! deps kind message)))]
               (swap! store transfer-state/clear-in-flight owner flow-id)
               (run-state/update-run! store flow-id
                                      #(resolve-run % ok? step tx-hash (run-state/now deps))
                                      run-state/finished-modal-changes)
               (cond
                 (and ok? approve?) (toast* :info (:approved-only transfer-run/messages))
                 ok? (do (run-state/follow-up!
                          deps "toast"
                          #(toast* :success (str "Your HyperEVM transfer of " text " confirmed.")))
                         (run-state/follow-up!
                          deps "activation"
                          #(run-state/refresh-core-activation! deps (:token-index entry)))
                         (run-state/follow-up!
                          deps "arrival"
                          #(run-state/track-arrival! deps flow-id (:arrival entry))))
                 approve? (toast* :error (str "Your USDC approval for " text
                                              " reverted on HyperEVM, so nothing was sent."))
                 :else (toast* :error (str "Your HyperEVM transfer of " text " reverted.")))
               (run-state/follow-up! deps "HyperEVM balances"
                                     #(run-state/refresh-hyperevm-after-transfer! deps))
               {:resolved? true :ok? ok?})))))
      (.catch (fn [_] nil))))

;; --- add to wallet --------------------------------------------------------------

(defn- wallet-error-message
  [err symbol]
  (let [code (or (some-> err .-code) (:code (ex-data err)))
        message (str/trim (str (some-> err .-message)))]
    (cond
      (= :chain-switch-unsupported (:kind (ex-data err))) (:switch-unsupported transfer-run/messages)
      (= 4001 code) (str "Adding " symbol " to your wallet was cancelled.")
      (seq message) (str "Couldn't add " symbol " to your wallet: " message)
      :else (str "Couldn't add " symbol " to your wallet."))))

(defn wallet-watch-asset!
  "Show a token in the wallet on HyperEVM: switch to (or add) chain 999
   first, then `wallet_watchAsset`. HYPE is native there, so it only needs
   the network. Resolves true when done."
  [{:keys [store request wallet-provider-fn ensure-wallet-chain! watch-asset! chain-config]
    :as deps}]
  (let [{:keys [chain-id address symbol decimals]} request
        provider (when (fn? wallet-provider-fn) (wallet-provider-fn))]
    (cond
      (not= txs/chain-id chain-id)
      (do (toast! deps :error "Only HyperEVM tokens can be added to your wallet here.")
          (js/Promise.resolve false))

      (nil? provider)
      (do (toast! deps :error "Connect your wallet to add tokens to it.")
          (js/Promise.resolve false))

      :else
      (-> (js/Promise.resolve nil)
          (.then (fn [_] (ensure-wallet-chain! provider chain-config)))
          (.then (fn [chain]
                   (run-state/set-wallet-chain! store chain)
                   (if (string? address)
                     (watch-asset! provider {:address address :symbol symbol :decimals decimals})
                     ::network-only)))
          (.then (fn [added]
                   (cond
                     (= ::network-only added)
                     (toast! deps :success "Your wallet is on HyperEVM, where HYPE is the native coin.")

                     (true? added)
                     (toast! deps :success (str symbol " added to your wallet.")))
                   (boolean added)))
          (.catch (fn [err]
                    (when (= :chain-switch-unsupported (:kind (ex-data err)))
                      (run-state/cache-chain-switch-unsupported! store))
                    (toast! deps :error (wallet-error-message err symbol))
                    false))))))
