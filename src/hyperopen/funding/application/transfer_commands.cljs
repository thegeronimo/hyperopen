(ns hyperopen.funding.application.transfer-commands
  "Transfer-mode commands: open (optionally preset to a route and asset),
   choose places and asset, amounts, submit, and the HyperEVM run controls.

   Every command is pure and returns effects. The route rules live in
   `hyperopen.funding.domain.transfer-route`; the previews and MAX come in
   through `deps` already bound to the clock.

   Once a HyperEVM run is `:running` or `:pending` a transaction may already
   be on chain, so nothing here returns the modal to an editable,
   submittable form until the run ends: a second submit would move the funds
   twice.

   A HyperEVM -> Core draft depends on two HyperCore reads: the token's
   bridge balance, trusted for a minute, and the owner's activation. They
   are read when the route or asset is chosen, again whenever an edit or
   submit finds them due (`capacity-refresh-due?`), and by the HyperEVM
   balance poller while the draft sits open (`capacity-refresh-index`), so
   the form never waits on a check nobody is running."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.application.modal-commands :as modal-commands]
            [hyperopen.funding.application.modal-state :as modal-state]
            [hyperopen.funding.domain.transfer-balances :as transfer-balances]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]))

(def ^:private legacy-amount-decimals
  "Perps <-> Spot amounts keep the legacy 6-decimal USDC input."
  6)

(defn- evm-phase
  [modal]
  (let [phase (get-in modal [:transfer-evm :phase])]
    (cond
      (keyword? phase) phase
      (string? phase) (keyword phase)
      :else nil)))

(defn- evm-run-locked?
  "Whether a HyperEVM run may have a transaction on chain."
  [modal]
  (contains? #{:running :pending} (evm-phase modal)))

(defn- retryable-failed-run?
  "Whether a failed run may be sent again as it stands (\"Try again\"). Not
   when its outcome is unknown (`:maybe-sent?`: the `sendAsset` POST threw,
   the wallet answered without a hash): the funds may have moved, so the
   user goes back to the form and its refreshed balances first."
  [modal]
  (and (= :failed (evm-phase modal))
       (not (true? (get-in modal [:transfer-evm :maybe-sent?])))))

(defn- transfer-mode?
  [{:keys [normalize-mode]} modal]
  (= :transfer (normalize-mode (:mode modal))))

(defn- legacy-route?
  [route]
  (and (:valid? route) (not (transfer-route/evm-route? route))))

(defn- resolve-context-asset
  [state value]
  (or (transfer-route/normalize-asset-index value)
      (when (and (string? value) (seq (str/trim value)))
        (:index (tokens/token-by-name (get-in state [:spot :meta]) value)))))

(defn- capacity-effects
  "What `:actions/refresh-hyperevm-bridge-capacity` emits, inlined (an action
   cannot emit actions), for a modal whose route moves an asset HyperEVM ->
   Core: the Core bridge balance and the owner's activation status."
  [{:keys [refresh-hyperevm-bridge-capacity]} state modal]
  (let [route (transfer-route/transfer-route modal)
        index (transfer-route/normalize-asset-index (:transfer-asset modal))]
    (if (and (fn? refresh-hyperevm-bridge-capacity)
             index
             (:valid? route)
             (= :evm->core (transfer-route/route-kind route)))
      (vec (refresh-hyperevm-bridge-capacity state index))
      [])))

(defn capacity-refresh-due?
  "Whether an open HyperEVM -> Core draft needs its HyperCore reads again at
   `now-ms`: the token's bridge balance was never read, failed, or is close
   to going stale (`bridge/core-capacity-refresh-due?`), or the owner's
   activation is still unknown. Nothing is due on other routes, while the
   identity gate blocks HyperEVM moves, or while a run may have a
   transaction on chain."
  [state modal now-ms]
  (let [route (transfer-route/transfer-route modal)
        token (transfer-route/route-token state modal)]
    (boolean
     (and (:valid? route)
          (= :evm->core (transfer-route/route-kind route))
          token
          (not (evm-run-locked? modal))
          (nil? (account-context/hyperevm-moves-blocked-message state))
          (or (bridge/core-capacity-refresh-due? state token now-ms)
              (nil? (account-context/core-account-activation-status state)))))))

(defn- refresh-effects-when-due
  "`capacity-effects` when the draft's HyperCore reads are due, else []."
  [{:keys [now-ms] :as deps} state modal]
  (if (and (fn? now-ms) (capacity-refresh-due? state modal (now-ms)))
    (capacity-effects deps state modal)
    []))

(defn capacity-refresh-index
  "The spot token index whose HyperCore reads the open Transfer modal needs
   again at `now-ms`, or nil. The HyperEVM balance poller dispatches
   `:actions/refresh-hyperevm-bridge-capacity` with it, so a draft left open
   keeps a current bridge balance without any edit."
  [{:keys [modal-state] :as deps} state now-ms]
  (let [modal (modal-state state)]
    (when (and (true? (:open? modal))
               (transfer-mode? deps modal)
               (capacity-refresh-due? state modal now-ms))
      (transfer-route/normalize-asset-index (:transfer-asset modal)))))

(defn- with-route
  "`modal` moved to `from` -> `to`: the amount, error and any HyperEVM run
   or gas top-up are cleared, `:to-perp?` follows a Perps/Spot pair, and the
   asset is kept only while the new source can move it (else the first
   movable asset is chosen)."
  [state modal from to]
  (let [modal* (cond-> (assoc modal
                              :transfer-from from
                              :transfer-to to
                              :amount-input ""
                              :error nil
                              :transfer-evm nil
                              :transfer-gas-topup nil)
                 (= #{:perps :spot} (set [from to])) (assoc :to-perp? (= :perps to)))
        route (transfer-route/transfer-route modal*)]
    (assoc modal* :transfer-asset
           (when (transfer-route/evm-route? route)
             (transfer-route/eligible-asset-index
              (transfer-route/asset-options state route)
              (:transfer-asset modal))))))

(defn- save-with-capacity
  [{:keys [funding-modal-path] :as deps} state modal]
  (into [[:effects/save funding-modal-path modal]]
        (capacity-effects deps state modal)))

(defn open-funding-transfer-modal
  "Open the Transfer modal. `transfer-context` may carry the legacy
   `{:dex :to-perp? :destination :from-subaccount}` and a preset `{:from :to
   :asset}` (places as keywords or strings, the asset as a spot token index
   or name). Without `:from`/`:to` the saved modal is exactly the legacy one."
  [{:keys [modal-state
           normalize-anchor
           default-funding-modal-state
           wallet-address
           funding-modal-path] :as deps}
   state
   anchor
   opener-data-role
   transfer-context]
  (let [base (modal-state state)
        anchor* (normalize-anchor anchor)
        context (when (map? transfer-context) transfer-context)
        from (transfer-route/normalize-location (:from context))
        to (transfer-route/normalize-location (:to context))
        preset? (or (some? from) (some? to))
        transfer-dex (or (some-> (:dex context) str str/trim) "")
        to-perp? (cond
                   (= #{:perps :spot} (set [from to])) (= :perps to)
                   (contains? context :to-perp?) (true? (:to-perp? context))
                   :else true)
        ;; A named-DEX balance shown for a selected owner-controlled subaccount belongs
        ;; to it: source from it (`fromSubAccount`) and land in its own spot; owner still
        ;; signs. `nil` vault = master active (wallet destination, empty fromSubAccount).
        ;; Explicit context addresses (normalized) win, for direct request-builder tests.
        vault (account-context/exchange-vault-address state)
        ctx-destination (some-> (:destination context) account-context/normalize-address)
        ctx-from-subaccount (some-> (:from-subaccount context) account-context/normalize-address)
        transfer-from-subaccount (or ctx-from-subaccount vault "")
        transfer-destination-address (or ctx-destination
                                         vault
                                         (wallet-address state)
                                         "")
        modal (-> (default-funding-modal-state)
                  (assoc :open? true
                         :mode :transfer
                         :anchor anchor*
                         :withdraw-step :asset-select
                         :withdraw-search-input ""
                         :to-perp? to-perp?
                         :transfer-dex transfer-dex
                         :transfer-destination-address transfer-destination-address
                         :transfer-from-subaccount transfer-from-subaccount
                         :destination-input (or (wallet-address state) ""))
                  (modal-state/with-open-focus-metadata base opener-data-role))
        modal* (if-not preset?
                 modal
                 (let [asset (resolve-context-asset state (:asset context))
                       routed (with-route state
                                (assoc modal :transfer-asset asset)
                                from
                                to)]
                   ;; An asset the opener named is kept even before its
                   ;; balance loads; the preview says why it cannot move yet.
                   (if (and asset (transfer-route/evm-route? (transfer-route/transfer-route routed)))
                     (assoc routed :transfer-asset asset)
                     routed)))]
    (into [[:effects/load-surface-module :funding-modal]]
          (save-with-capacity deps state modal*))))

(defn set-funding-transfer-location
  "Set the `side` (`:from`/`:to`) place to `location`. Choosing the place the
   other side has swaps them; HyperEVM -> Perps is turned into HyperEVM ->
   Spot. Choosing the place already set (the pressed option) changes
   nothing: the typed amount and a gas top-up in flight are kept."
  [deps state side location]
  (let [modal ((:modal-state deps) state)
        side* (transfer-route/normalize-side side)
        location* (transfer-route/normalize-location location)
        route (transfer-route/transfer-route modal)]
    (if (or (nil? side*) (nil? location*)
            (not (transfer-mode? deps modal))
            (evm-run-locked? modal))
      []
      (let [[from to] (transfer-route/with-location route side* location*)]
        (if (= [from to] [(:from route) (:to route)])
          []
          (save-with-capacity deps state (with-route state modal from to)))))))

(defn swap-funding-transfer-locations
  [deps state]
  (let [modal ((:modal-state deps) state)]
    (if (or (not (transfer-mode? deps modal)) (evm-run-locked? modal))
      []
      (let [[from to] (transfer-route/swapped (transfer-route/transfer-route modal))]
        (save-with-capacity deps state (with-route state modal from to))))))

(defn select-funding-transfer-asset
  [deps state index]
  (let [modal ((:modal-state deps) state)
        index* (transfer-route/normalize-asset-index index)]
    (if (or (nil? index*)
            (not (transfer-mode? deps modal))
            (evm-run-locked? modal))
      []
      (save-with-capacity deps state (assoc modal
                                            :transfer-asset index*
                                            :amount-input ""
                                            :error nil)))))

(defn set-funding-transfer-direction
  "The legacy Perps <-> Spot toggle. From a chosen route (`:transfer-from`/
   `:transfer-to` set) it returns to the derived legacy route and clears the
   places, asset, amount and any HyperEVM run or gas top-up. On the legacy
   route it only sets the direction, exactly as before this feature, so the
   typed amount survives (including a click on the selected direction)."
  [{:keys [modal-state funding-modal-path]} state to-perp?]
  (let [modal (modal-state state)
        chosen-route? (or (some? (:transfer-from modal)) (some? (:transfer-to modal)))]
    (cond
      (evm-run-locked? modal) []

      chosen-route?
      [[:effects/save funding-modal-path
        (assoc modal
               :to-perp? (true? to-perp?)
               :transfer-from nil
               :transfer-to nil
               :transfer-asset nil
               :transfer-evm nil
               :transfer-gas-topup nil
               :amount-input ""
               :error nil)]]

      :else
      [[:effects/save funding-modal-path
        (assoc modal :to-perp? (true? to-perp?) :error nil)]])))

(defn- max-amount-text
  "MAX as text for any route: the legacy number rendered at 6 decimals
   (floored), or the EVM route's floored string."
  [{:keys [transfer-max-amount]} state modal]
  (let [max-amount (transfer-max-amount state modal)]
    (if (string? max-amount)
      max-amount
      (some-> (transfer-balances/number->text max-amount)
              (transfer-balances/percent-of-text 100 legacy-amount-decimals)))))

(defn enter-funding-transfer-amount
  "Type an amount. A HyperEVM -> Core draft whose HyperCore reads are due
   also reads them again."
  [{:keys [modal-state] :as deps} state value]
  (into (vec (modal-commands/enter-funding-transfer-amount deps state value))
        (refresh-effects-when-due deps state (modal-state state))))

(defn set-funding-amount-to-max
  "Fill MAX. Routes touching HyperEVM fill their floored decimal string
   verbatim (never re-rounded); everything else keeps the legacy command.
   While an EVM route's MAX is unknown the typed amount is kept, and a
   HyperEVM -> Core draft reads its bridge balance again when due."
  [{:keys [modal-state funding-modal-path] :as deps} state]
  (let [modal (modal-state state)
        route (transfer-route/transfer-route modal)]
    (if (and (transfer-mode? deps modal) (not (legacy-route? route)))
      (if (evm-run-locked? modal)
        []
        (let [max-text (max-amount-text deps state modal)
              refresh (refresh-effects-when-due deps state modal)]
          (if max-text
            (into [[:effects/save funding-modal-path
                    (assoc modal :amount-input max-text :error nil)]]
                  refresh)
            refresh)))
      (modal-commands/set-funding-amount-to-max deps state))))

(defn set-funding-transfer-amount-percent
  "Fill `pct` percent of MAX, floored to the route precision (6 decimals on
   the Perps <-> Spot route)."
  [{:keys [modal-state funding-modal-path] :as deps} state pct]
  (let [modal (modal-state state)
        route (transfer-route/transfer-route modal)
        token (transfer-route/route-token state modal)
        precision (if (legacy-route? route)
                    legacy-amount-decimals
                    (:core-precision token))
        amount (when (and (number? precision) (number? pct))
                 (some-> (max-amount-text deps state modal)
                         (transfer-balances/percent-of-text pct precision)))]
    (cond
      (or (not (transfer-mode? deps modal)) (evm-run-locked? modal)) []
      (nil? amount) (refresh-effects-when-due deps state modal)
      :else (into [[:effects/save funding-modal-path
                    (assoc modal :amount-input amount :error nil)]]
                  (refresh-effects-when-due deps state modal)))))

(defn- guard-effects
  [funding-modal-path message]
  [[:effects/save-many [[(conj funding-modal-path :submitting?) false]
                        [(conj funding-modal-path :error) message]]]])

(defn- retry-effects
  "A submit from a failed run (\"Try again\") first clears that run, so the
   submit effect can start a new one on the same draft."
  [funding-modal-path modal effects]
  (if (and (= :failed (evm-phase modal))
           (some #(= :effects/api-submit-funding-transfer (first %)) effects))
    (into [[:effects/save (conj funding-modal-path :transfer-evm) nil]] effects)
    effects))

(defn submit-funding-transfer
  "Submit the Transfer. Refused while a submit is already running or a
   HyperEVM run exists that has not failed (its own views own the next
   step). A failed run's \"Try again\" submits the same draft again only
   when that run is known to have moved nothing: a failure whose outcome is
   unknown (`:maybe-sent?`) is refused, so the user returns to the form
   (\"Back to edit\") and its refreshed balances first. A failed HyperEVM ->
   Core send that may have gone out also keeps its in-flight entry, which
   blocks below. Routes touching HyperEVM also need the master account, and
   a HyperEVM -> Core move waits for any earlier one still confirming. A
   HyperEVM -> Core submit whose HyperCore reads are due also reads them
   again (non-heavy effects after the covered submit's own)."
  [{:keys [modal-state funding-modal-path] :as deps} state]
  (let [modal (modal-state state)
        route (transfer-route/transfer-route modal)
        evm? (transfer-route/evm-route? route)
        owner (account-context/owner-address state)]
    (cond
      (true? (:submitting? modal)) []
      (and (some? (evm-phase modal)) (not (retryable-failed-run? modal))) []

      (and evm? (account-context/hyperevm-moves-blocked-message state))
      (guard-effects funding-modal-path (account-context/hyperevm-moves-blocked-message state))

      (and (= :evm->core (transfer-route/route-kind route))
           (transfer-state/in-flight-entry state owner))
      (guard-effects funding-modal-path
                     (transfer-state/in-flight-blocked-message
                      (transfer-state/in-flight-entry state owner)))

      :else
      (into (retry-effects funding-modal-path modal
                           (vec (modal-commands/submit-funding-transfer deps state)))
            (refresh-effects-when-due deps state modal)))))

(defn submit-funding-transfer-gas-topup
  "Send 0.05 HYPE from Spot to HyperEVM for gas, leaving the draft as it is.
   Ignored while a top-up is submitting or already sent."
  [{:keys [modal-state funding-modal-path gas-topup-request] :as deps} state]
  (let [modal (modal-state state)
        status (get-in modal [:transfer-gas-topup :status])
        path (conj funding-modal-path :transfer-gas-topup)]
    (cond
      (not (transfer-mode? deps modal)) []
      (contains? #{:submitting :sent} status) []
      :else
      (let [result (gas-topup-request state)]
        (if (:ok? result)
          [[:effects/save path {:status :submitting}]
           [:effects/api-submit-funding-transfer (:request result)]]
          [[:effects/save path {:status :failed :error (:display-message result)}]])))))

(defn reset-funding-transfer-evm
  "\"Back to edit\" after a failed (or finished) HyperEVM run: keeps the
   route, asset and amount. Refused while the run may still have a
   transaction confirming."
  [{:keys [modal-state funding-modal-path]} state]
  (let [modal (modal-state state)]
    (if (evm-run-locked? modal)
      []
      [[:effects/save funding-modal-path
        (assoc modal :transfer-evm nil :submitting? false :error nil)]])))

(defn retry-funding-transfer-capability
  "Forget that this wallet refused to switch to HyperEVM, and return a
   failed run to the form, so the user can try again. The whole capability
   map is saved: its provider keys are strings, and `:effects/save` paths
   must be all keywords."
  [{:keys [modal-state funding-modal-path]} state]
  (let [modal (modal-state state)]
    (cond-> [[:effects/save transfer-state/wallet-capabilities-path
              (transfer-state/capabilities-without-chain-switch state)]]
      (= :failed (evm-phase modal))
      (conj [:effects/save funding-modal-path
             (assoc modal :transfer-evm nil :submitting? false :error nil)]))))

(defn add-funding-transfer-token-to-wallet
  "Ask the wallet to show the moved token on HyperEVM. Only for a route that
   ends on HyperEVM. HYPE is native there, so it only needs the network (the
   effect switches to or adds chain 999 first)."
  [{:keys [modal-state]} state]
  (let [modal (modal-state state)
        route (transfer-route/transfer-route modal)
        token (transfer-route/route-token state modal)]
    (if (and token (= :hyperevm (:to route)))
      [[:effects/wallet-watch-asset {:chain-id (:chain-id chain/mainnet)
                                     :address (:erc20-address token)
                                     :symbol (:name token)
                                     :decimals (:evm-decimals token)}]]
      [])))
