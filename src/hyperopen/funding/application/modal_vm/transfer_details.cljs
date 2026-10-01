(ns hyperopen.funding.application.modal-vm.transfer-details
  "Transfer view-model parts for routes that touch HyperEVM: the summary
   rows, the blocked card (with its one-click fix), and the HyperEVM run
   progress written by the submit effect at `[:funding-ui :modal
   :transfer-evm]`."
  (:require [hyperopen.funding.domain.evm-transfer-amounts :as evm-amounts]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-preview]
            [hyperopen.funding.domain.transfer-balances :as transfer-balances]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.fees :as fees]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.domain.units :as units]))

(def ^:private checking "Checking…")

(defn- row
  ([label value]
   (row label value :neutral))
  ([label value tone]
   {:label label :value value :tone tone}))

(defn- as-keyword
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else nil))

;; --- summary ----------------------------------------------------------------

(defn- core->evm-rows
  [state token]
  (let [fee (evm-preview/core-fee-hype state token)]
    [(row "Network fee"
          (cond
            (= :native (:kind token)) "None"
            (nil? fee) checking
            :else (str "≈ " fee " HYPE, paid from Spot")))
     (row "Arrives" "Next HyperEVM block, usually under 5 seconds")
     (row "Wallet network" "No switch needed")]))

(defn- evm->core-rows
  [{:keys [hyperevm-entry wallet-chain-id]} state owner token activation-fee?]
  (let [entry (hyperevm-entry state owner)
        cost (some-> (fees/evm-tx-cost-hype (:gas-price-wei entry)
                                            (evm-amounts/gas-kinds token))
                     evm-preview/approx-hype)
        have (some-> (:native-wei entry)
                     (units/format-units 18)
                     (units/floor-to-decimals 8)
                     transfer-balances/display-amount)]
    (cond-> [(row "Gas"
                  (if cost
                    (str "≈ " cost " HYPE on HyperEVM"
                         (when have (str " · " have " available")))
                    checking))
             (row "Arrives"
                  (if (= :usdc-cdw (:kind token))
                    "A few seconds after the deposit confirms"
                    "A few seconds after the transfer confirms"))
             (row "Wallet network"
                  (if (= (:chain-id chain/mainnet) (wallet-chain-id state))
                    "Already on HyperEVM"
                    "Switches to HyperEVM (chain 999)"))]
      activation-fee?
      (conj (row "Activation fee"
                 (str evm-amounts/usdc-activation-fee " USDC (first transfer only)")
                 :warn)))))

(defn summary-rows
  "Fee, arrival and wallet-network rows for a route that touches HyperEVM;
   empty for Perps <-> Spot and before an asset is chosen."
  [deps state route token owner activation-fee?]
  (if (and token (:valid? route))
    (case (transfer-route/route-kind route)
      :core->evm (core->evm-rows state token)
      :evm->core (evm->core-rows deps state owner token activation-fee?)
      [])
    []))

;; --- blocked card ---------------------------------------------------------------

(defn- hype-token
  [state]
  (tokens/token-by-name (get-in state [:spot :meta]) "HYPE"))

(defn- gas-fix
  [{:keys [hyperevm-moves-blocked-message]} state modal]
  (let [{:keys [status error]} (:transfer-gas-topup modal)
        status* (let [status (as-keyword status)]
                  (if (contains? #{:submitting :sent :failed} status) status :idle))
        spot-hype (some->> (hype-token state) (transfer-balances/spot-available-text state))
        enough? (and spot-hype
                     (not (neg? (transfer-balances/compare-text spot-hype
                                                                evm-preview/gas-topup-amount))))
        blocked-message (hyperevm-moves-blocked-message state)
        busy? (contains? #{:submitting :sent} status*)
        reason (cond
                 busy? nil
                 blocked-message blocked-message
                 (nil? spot-hype) "Checking your Spot balances…"
                 (not enough?) (str "You need at least " evm-preview/gas-topup-amount
                                    " HYPE on Spot.")
                 :else nil)]
    {:label (str "Send " evm-preview/gas-topup-amount " HYPE from Spot to HyperEVM")
     :action [:actions/submit-funding-transfer-gas-topup]
     :status status*
     :status-message (case status*
                       :submitting (str "Sending " evm-preview/gas-topup-amount " HYPE…")
                       :sent (str "Sent " evm-preview/gas-topup-amount
                                  " HYPE. Waiting for it to arrive…")
                       :failed (or error "The gas top-up didn't go through. Try again.")
                       (if spot-hype
                         (str "From your " (transfer-balances/display-amount spot-hype)
                              " HYPE on Spot · no network switch · a few seconds")
                         "From your HYPE on Spot · no network switch · a few seconds"))
     :disabled? (boolean (or busy? reason))
     :reason reason}))

(def capability-retry-label
  "The network-switch retry's label. It forgets the wallet's refusal and
   re-enables the form; it does not resubmit (one dispatch cannot both clear
   the block and submit), so it is not called \"Try again\", which resubmits
   everywhere else."
  "Try switching again")

(defn- retry-fix
  []
  {:label capability-retry-label
   :action [:actions/retry-funding-transfer-capability]
   :status :idle
   :status-message nil
   :disabled? false
   :reason nil})

(def ^:private checking-codes
  "Blocks that only wait on data being read (or read again): nothing for
   the user to do, so the form keeps its details and shows a quiet line."
  #{:hyperevm-unavailable :meta-missing})

(defn blocked-model
  "The blocked card for a preview's `:blocked`, or nil. `:checking?` marks a
   block that only waits on a read."
  [deps state modal blocked]
  (when blocked
    {:code (:code blocked)
     :checking? (contains? checking-codes (:code blocked))
     :title (:title blocked)
     :message (:message blocked)
     :explorer-url (:explorer-url blocked)
     :fix (when (:fix? blocked)
            (case (:code blocked)
              :no-evm-gas (gas-fix deps state modal)
              :no-chain-switch (retry-fix)
              nil))}))

;; --- HyperEVM run ---------------------------------------------------------------

(def ^:private phases
  #{:running :pending :failed :succeeded})

(def ^:private step-statuses
  #{:pending :active :done :skipped :failed})

(defn- step-model
  [step]
  {:id (or (as-keyword (:id step)) :step)
   :label (str (or (:label step) ""))
   :status (let [status (as-keyword (:status step))]
             (if (contains? step-statuses status) status :pending))
   :detail (some-> (:detail step) str)})

(defn- step-index
  "1-based position of the step waiting on the wallet: the active one, else
   the failed one, else the one after the last finished step."
  [steps]
  (let [indexed (map-indexed vector steps)
        at (fn [status] (some (fn [[i step]] (when (= status (:status step)) i)) indexed))]
    (cond
      (empty? steps) 0
      (at :active) (inc (at :active))
      (at :failed) (inc (at :failed))
      :else (min (count steps)
                 (inc (count (filter #(contains? #{:done :skipped} (:status %)) steps)))))))

(defn evm-model
  "The HyperEVM run for the view, or nil when there is none (the form)."
  [modal route]
  (let [run (:transfer-evm modal)
        phase (as-keyword (:phase run))]
    (when (and (map? run) (contains? phases phase))
      (let [steps (mapv step-model (when (sequential? (:steps run)) (:steps run)))
            arrival (as-keyword (:arrival run))]
        {:phase phase
         :flow-id (:flow-id run)
         :steps steps
         :step-index (step-index steps)
         :step-count (count steps)
         :tx-url (or (some-> (:tx-url run) str not-empty)
                     (some-> (:tx-hash run) str chain/explorer-tx-url))
         :error (some-> (:error run) str)
         :arrival (if (contains? #{:idle :arriving :arrived :slow} arrival) arrival :idle)
         :result (when (map? (:result run)) (:result run))
         :maybe-sent? (true? (:maybe-sent? run))
         :retry-action nil
         :add-to-wallet? (and (= :succeeded phase) (= :hyperevm (:to route)))}))))

(defn retry-action
  "What \"Try again\" does after a failed run, or nil when it must not be
   offered (\"Back to edit\" then shows why):

   - nil when the failure's outcome is unknown (`:maybe-sent?`): the funds
     may have moved, so the form and its refreshed balances come first;
   - forgetting the wallet's refused network switch when that is the block;
   - nil for any other block (an unresolved transaction, missing gas, …);
   - the same submit only when the draft would submit as it stands."
  [evm blocked preview-ok?]
  (cond
    (not= :failed (:phase evm)) nil
    (:maybe-sent? evm) nil
    (= :no-chain-switch (:code blocked)) [:actions/retry-funding-transfer-capability]
    (some? blocked) nil
    (true? preview-ok?) [:actions/submit-funding-transfer]
    :else nil))

(defn with-retry-action
  [evm blocked preview-ok?]
  (when evm
    (assoc evm :retry-action (retry-action evm blocked preview-ok?))))

(defn content-kind
  [evm]
  (case (:phase evm)
    :running :transfer/progress
    :pending :transfer/pending
    :failed :transfer/failed
    :succeeded :transfer/success
    :transfer/form))
