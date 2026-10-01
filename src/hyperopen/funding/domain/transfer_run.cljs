(ns hyperopen.funding.domain.transfer-run
  "The HyperEVM run the Transfer modal shows once a move that touches
   HyperEVM starts, stored at `[:funding-ui :modal :transfer-evm]`:

       {:phase :running|:pending|:failed|:succeeded
        :flow-id \"…\" :route :core->evm|:evm->core
        :steps [{:id :label :status :detail}]
        :tx-hash :tx-url :error :maybe-sent? bool
        :arrival :idle|:arriving|:arrived|:slow
        :started-at-ms n :result {…}}

   Every write carries the run's `:flow-id`; the submit effect drops writes
   whose flow is no longer the modal's (closed, or reopened on a new draft).

   Also here: what a finished move waits for before it reads \"Arrived\"
   (`arrival-plan`, `arrived?`). Pure; the submit effects own the IO."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.funding.domain.evm-transfer-amounts :as evm-amounts]
            [hyperopen.funding.domain.transfer-balances :as transfer-balances]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.tokens :as tokens]))

(def arrival-slow-ms
  "A finished move that has not shown up on the other side after this long
   reads \"Taking longer than usual\"."
  60000)

(def arrival-watch-ms
  "How long a finished move is watched for its arrival at all."
  300000)

(def messages
  {:no-provider "Reconnect your wallet to send HyperEVM transactions."
   :no-owner "Connect your wallet to move funds."
   :owner-changed "Your wallet changed since this transfer was prepared. Review it and try again."
   :build-failed "Couldn't build the HyperEVM transaction. Review the transfer and try again."
   :gas-price "Couldn't read the HyperEVM gas price. Try again in a moment."
   :allowance "Couldn't read your USDC approval on HyperEVM. Try again in a moment."
   :switch-rejected "Network switch rejected in wallet."
   :switch-unsupported (str "Your wallet couldn't switch to HyperEVM (chain 999), so it can't send "
                            "this transfer. Switch networks in your wallet, or try again.")
   :switch-elsewhere (str "Your wallet moved to another network instead of HyperEVM (chain 999), so "
                          "nothing was sent. Switch to HyperEVM in your wallet and try again.")
   :chain-left (str "Your wallet left HyperEVM (chain 999) before the next transaction, so "
                    "nothing more was sent. Switch back to HyperEVM and try again.")
   :approve-rejected "Approval rejected in wallet."
   :transfer-rejected "Transfer rejected in wallet."
   :no-hash (str "The wallet didn't return a transaction hash, so the transfer may still have "
                 "been sent. Check your wallet activity; reload the page before starting another.")
   :reverted "Transaction reverted on HyperEVM."
   :approve-reverted "The USDC approval reverted on HyperEVM."
   :pending (str "Your HyperEVM transfer was sent but hasn't confirmed yet. Check it in your "
                 "wallet or on the explorer.")
   :approved-only (str "The USDC approval confirmed, but the deposit wasn't sent. Try again to "
                       "finish the move; the approval is kept.")})

;; --- steps --------------------------------------------------------------------

(def ^:private chain-detail
  "Chain 999 · trading still works on any network")

(def confirming-detail
  "The detail of a step whose transaction the wallet sent and HyperEVM has
   yet to confirm. The submit label reads it to say the chain, not the
   wallet, is what the move waits on."
  "Confirming on HyperEVM…")

(defn- step
  [id label status detail]
  {:id id :label label :status status :detail detail})

(defn- amount-label
  "`Approve 1,000 USDC`: the amount grouped like the run's heading."
  [prefix {:keys [amount symbol]} suffix]
  (str prefix " " (transfer-balances/grouped-amount amount) " " symbol suffix))

(defn initial-steps
  "The steps a move shows, in order. Core -> HyperEVM is one signature;
   HyperEVM -> Core switches the wallet's network, then sends (or, for USDC,
   approves then deposits)."
  [{:keys [route action evm]}]
  (case route
    :core->evm
    [(step :sign (amount-label "Move" evm " to HyperEVM") :active
           "Confirm in your wallet · no network switch")]

    :evm->core
    (into [(step :switch-network "Switch wallet to HyperEVM" :active chain-detail)]
          (if (= "usdcCoreDeposit" (:kind action))
            [(step :approve (amount-label "Approve" evm "") :pending "Confirm in your wallet")
             (step :deposit "Deposit to HyperCore Spot" :pending "Second wallet confirmation")]
            [(step :send (amount-label "Send" evm " to Spot") :pending "Confirm in your wallet")]))

    []))

(def ^:private failed-detail
  "A failed step's detail when nothing more specific is known."
  "Didn't finish")

(defn failure-detail
  "What a failed step says under its label, from the run's `error`: why it
   stopped, never the \"Confirm in your wallet\" it showed while active."
  [error maybe-sent?]
  (cond
    maybe-sent? "May have been sent — check your wallet"
    (contains? #{(:switch-rejected messages) (:approve-rejected messages)
                 (:transfer-rejected messages)}
               error)
    "Rejected in wallet"
    (contains? #{(:reverted messages) (:approve-reverted messages)} error)
    "Reverted on HyperEVM"
    :else failed-detail))

(defn- event-changes
  "Status, detail and (sometimes) label for a step event reported by the
   submit flow."
  [step-id event]
  (case event
    :active {:status :active :detail "Confirm in your wallet"}
    :confirming {:status :active :detail confirming-detail}
    :skipped {:status :skipped :detail "Already approved"}
    :failed {:status :failed :detail failed-detail}
    :done (if (= :switch-network step-id)
            {:status :done :label "Wallet on HyperEVM" :detail chain-detail}
            {:status :done :detail "Confirmed"})
    {}))

(defn apply-step-event
  "`run` with step `step-id` moved by `event` (`:active`, `:confirming`,
   `:done`, `:skipped`, `:failed`). Unknown steps and events change nothing."
  [run step-id event]
  (let [changes (event-changes step-id event)]
    (if (empty? changes)
      run
      (update run :steps
              (fn [steps]
                (mapv (fn [s] (if (= step-id (:id s)) (merge s changes) s))
                      steps))))))

(defn- fail-steps
  "Mark `step-id` failed, with `detail` under its label. Every other step
   still `:active` goes back to `:pending`: the run stopped before it. With
   no `step-id` (a read that failed before the wallet was asked for
   anything) no step is marked failed."
  [steps step-id detail]
  (mapv (fn [s]
          (cond
            (and (some? step-id) (= step-id (:id s))) (assoc s :status :failed :detail detail)
            (= :active (:status s)) (assoc s :status :pending)
            :else s))
        steps))

;; --- run ----------------------------------------------------------------------

(defn result-summary
  "What the success view reports about a move: amount, asset and places.
   `:sent-at-ms` is stamped when the move goes through (`succeeded`), so
   \"Arrived in N seconds\" counts the bridge, not the time spent signing."
  [{:keys [route evm]} now-ms]
  {:amount (:amount evm)
   :symbol (:symbol evm)
   :from (if (= :evm->core route) :hyperevm (or (:from evm) :spot))
   :to (if (= :evm->core route) :spot :hyperevm)
   :owner (:owner evm)
   :started-at-ms now-ms
   :sent-at-ms nil
   :arrived-at-ms nil})

(defn start-run
  "A `:running` run for `request`."
  [flow-id request now-ms]
  {:phase :running
   :flow-id flow-id
   :route (:route request)
   :steps (initial-steps request)
   :tx-hash nil
   :tx-url nil
   :error nil
   :maybe-sent? false
   :arrival :idle
   :started-at-ms now-ms
   :result (result-summary request now-ms)})

(defn- with-tx
  [run tx-hash]
  (if (string? tx-hash)
    (assoc run :tx-hash tx-hash :tx-url (chain/explorer-tx-url tx-hash))
    run))

(defn succeeded
  "The move went through; the other side has yet to show it. With `now-ms`
   the result records when (`:sent-at-ms`), which the arrival time counts
   from."
  ([run tx-hash]
   (succeeded run tx-hash nil))
  ([run tx-hash now-ms]
   (cond-> (-> run
               (assoc :phase :succeeded :error nil :arrival :arriving)
               (update :steps (fn [steps]
                                (mapv #(if (contains? #{:active :pending} (:status %))
                                         (assoc % :status :done :detail "Confirmed")
                                         %)
                                      steps)))
               (with-tx tx-hash))
     (and (number? now-ms) (map? (:result run)))
     (assoc-in [:result :sent-at-ms] now-ms))))

(defn failed
  "The move stopped at `step-id` with `error` (see `fail-steps`). A run that
   already succeeded stays succeeded: its funds moved, so an error in the
   work that follows (refreshes, tracking) must never offer to send them
   again.

   `:maybe-sent? true` marks a failure whose outcome is unknown (the
   `sendAsset` POST threw, the wallet answered without a hash): the funds may
   have moved, so the failed view offers no one-click \"Try again\", only
   \"Back to edit\", which shows the balances as they are now."
  ([run step-id error tx-hash]
   (failed run step-id error tx-hash nil))
  ([run step-id error tx-hash {:keys [maybe-sent?]}]
   (if (= :succeeded (:phase run))
     run
     (-> run
         (assoc :phase :failed :error error :maybe-sent? (true? maybe-sent?))
         (update :steps fail-steps step-id (failure-detail error (true? maybe-sent?)))
         (with-tx tx-hash)))))

(defn pending
  "A transaction was sent but its receipt did not arrive in time. The run
   never returns to a submittable form from here."
  [run step-id tx-hash]
  (-> run
      (assoc :phase :pending :error nil)
      (apply-step-event step-id :confirming)
      (update :steps (fn [steps]
                       (mapv #(if (= step-id (:id %))
                                (assoc % :detail "Still confirming on HyperEVM")
                                %)
                             steps)))
      (with-tx tx-hash)))

(defn approval-only
  "A pending USDC approve confirmed in the background, but the deposit was
   never sent: the run ends so the user can try again (the approval is
   kept, so the next attempt goes straight to the deposit)."
  [run]
  (-> run
      (apply-step-event :approve :done)
      (assoc :phase :failed :error (:approved-only messages))))

(defn arrived
  [run now-ms]
  (-> run
      (assoc :arrival :arrived)
      (assoc-in [:result :arrived-at-ms] now-ms)))

(defn slow
  "Still arriving after `arrival-slow-ms`."
  [run]
  (if (= :arriving (:arrival run))
    (assoc run :arrival :slow)
    run))

;; --- arrival ------------------------------------------------------------------

(defn- request-token
  [state request]
  (tokens/token-by-index (get-in state [:spot :meta]) (get-in request [:evm :token-index])))

(defn spot-shows-owner?
  "Whether `[:spot :clearinghouse-state]` holds `owner`'s balances: Spot
   state follows the effective account, which is another address while a
   subaccount is selected or an address is spectated."
  [state owner]
  (let [owner* (account-context/normalize-address owner)]
    (and (some? owner*)
         (= owner* (account-context/normalize-address
                    (account-context/effective-account-address state))))))

(defn- arrival-amount-text
  "What `location` holds of `token` for `owner`, as arrival judges it: Spot
   by its whole balance (holds included, so an order placed meanwhile
   changes nothing), and only while Spot shows `owner`; HyperEVM from the
   owner's own entry. nil when unknown."
  [state location token owner]
  (case location
    :spot (when (spot-shows-owner? state owner)
            (transfer-balances/spot-total-text state token))
    :hyperevm (transfer-balances/evm-amount-text state owner token)
    nil))

(defn arrival-plan
  "What a move of `request` waits for, from `state` before it starts:
   `{:location :token-index :owner :before :expected}`. `:before` is what the
   destination held (nil when unknown, in which case the move never reads
   \"Arrived\" on its own), `:expected` the amount it should grow by. A
   first USDC move into a HyperCore account that isn't activated yet
   arrives 1 USDC short (the activation fee)."
  [state request]
  (let [token (request-token state request)
        route (:route request)
        owner (get-in request [:evm :owner])
        amount (get-in request [:evm :amount])
        location (case route :core->evm :hyperevm :evm->core :spot nil)
        activation-fee? (and (= :evm->core route)
                             (= :usdc-cdw (:kind token))
                             (= :missing (account-context/core-account-activation-status state)))
        expected (if activation-fee?
                   (transfer-balances/sub-text amount evm-amounts/usdc-activation-fee)
                   amount)]
    (when (and token owner location (transfer-balances/positive-text? expected))
      {:location location
       :token-index (:index token)
       :owner owner
       :before (arrival-amount-text state location token owner)
       :expected expected})))

(defn arrived?
  "Whether the destination of `arrival` (an `arrival-plan`) grew by the
   expected amount. Spot is judged only while it shows the owner's
   balances."
  [state {:keys [location token-index owner before expected]}]
  (let [token (tokens/token-by-index (get-in state [:spot :meta]) token-index)
        current (when token
                  (arrival-amount-text state location token owner))
        target (transfer-balances/add-text before expected)]
    (boolean (and current target
                  (not (neg? (transfer-balances/compare-text current target)))))))

(defn location-label
  [location]
  (get transfer-route/location-labels location ""))
