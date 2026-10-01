(ns hyperopen.views.account-info.projections.balances-moves
  "Where each Balances row can move its funds, as data for the row's move
   actions.

   `with-move-targets` gives every row a `:move-targets` vector (possibly
   empty). A target is

       {:row-key :from :to :label :aria-label :legacy? :context
        :disabled? :reason}

   - A legacy target is today's Perps <-> Spot USDC transfer. The view opens
     it with the row's existing `balance-row-transfer-action`, byte for byte,
     and it exists exactly where that action was enabled.
   - Every other target opens the Transfer modal preset to `:context`
     `{:from :to :asset}` (a spot token index).

   HyperEVM targets exist only for linked tokens whose bridge health is known
   good or still unknown. A token whose health is bad (a `decimals()` mismatch
   or a reverting `balanceOf`) gets none. A target is disabled, with the
   reason, when HyperEVM moves are blocked for this account (read-only,
   subaccount, no wallet), while the token's health is still unknown (being
   checked, or unavailable while the first HyperEVM read fails), or when the
   token's HyperEVM bridge side is empty.

   A legacy target's aria-label names a named-DEX row's DEX (\"Move xyz
   USDC from Perps to Spot\"), so no two actions share an accessible name.

   Apply this after the HyperEVM rows are appended, so they get their
   targets too."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-transfer-preview]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.views.account-info.projections.balances-hyperevm :as balances-hyperevm]))

(def ^:private place-labels
  transfer-route/location-labels)

(defn- target
  [row from to symbol overrides]
  (merge {:row-key (str (:key row))
          :from from
          :to to
          :label (str "To " (get place-labels to))
          :aria-label (str "Move " symbol " from " (get place-labels from)
                           " to " (get place-labels to))
          :legacy? false
          :context nil
          :disabled? false
          :reason nil}
         overrides))

(defn- usdc-row?
  [row]
  (str/starts-with? (str (:coin row)) "USDC"))

(defn- legacy-symbol
  "The USDC a legacy move carries, named with its perp DEX on a named-DEX
   row (\"xyz USDC\"), so each row's action has its own accessible name."
  [row]
  (let [dex (str/trim (str (:transfer-dex row)))]
    (if (seq dex)
      (str dex " USDC")
      "USDC")))

(defn- legacy-target
  "Today's Perps <-> Spot transfer, where the row offered it: a USDC row
   that is not a unified account's pooled row."
  [row]
  (when (and (usdc-row? row) (not (:transfer-disabled? row)))
    (let [to-perp? (if (some? (:transfer-to-perp? row))
                     (boolean (:transfer-to-perp? row))
                     (not (str/includes? (str (:coin row)) "Perps")))
          symbol (legacy-symbol row)]
      (if to-perp?
        (target row :spot :perps symbol {:legacy? true})
        (target row :perps :spot symbol {:legacy? true})))))

(defn- evm-reason
  [state ctx token to health]
  (cond
    (:blocked ctx) (:blocked ctx)
    ;; Health rides the balance read, so while the first read is failing
    ;; nothing is being checked: say the balances are unavailable.
    (= :unknown health) (if (= :unavailable (:hyperevm-status ctx))
                          (:balances-unavailable evm-transfer-preview/messages)
                          transfer-route/checking-token-reason)
    (and (= :hyperevm to)
         (= "0" (bridge/core->evm-capacity state token)))
    (:bridge-empty-evm evm-transfer-preview/messages)
    :else nil))

(defn- evm-target
  [state ctx row token from to]
  (when token
    (let [health (bridge/token-health-status state token)]
      (when-not (= :bad health)
        (let [reason (evm-reason state ctx token to health)]
          (target row from to (:name token)
                  {:context {:from from :to to :asset (:index token)}
                   :disabled? (some? reason)
                   :reason reason}))))))

(defn- spot-row-token
  [spot-meta row]
  (if-let [index (transfer-route/normalize-asset-index (:token row))]
    (tokens/token-by-index spot-meta index)
    (tokens/token-by-name spot-meta (or (:selection-coin row) (:coin row)))))

(defn- positive-total?
  [row]
  (let [total (:total-balance row)]
    (and (number? total) (pos? total))))

(defn- row-targets
  [state ctx row]
  (let [spot-meta (:spot-meta ctx)]
    (->> (case (balances-hyperevm/row-location row)
           :hyperevm
           [(evm-target state ctx row
                        (tokens/token-by-index spot-meta (:token row))
                        :hyperevm :spot)]

           ;; Perps -> HyperEVM is not a supported route
           ;; (`transfer-route/supported-pair?`): USDC goes Perps -> Spot ->
           ;; HyperEVM.
           :perps
           [(legacy-target row)]

           [(legacy-target row)
            (when (positive-total? row)
              (evm-target state ctx row (spot-row-token spot-meta row)
                          :spot :hyperevm))])
         (filterv some?))))

(defn hyperevm-target?
  "Whether `row` carries a HyperEVM move (any target that is not the legacy
   Perps <-> Spot transfer)."
  [row]
  (boolean (some (complement :legacy?) (:move-targets row))))

(defn with-move-targets
  "`rows` with `:move-targets` on each."
  [rows state]
  (let [ctx {:spot-meta (get-in state [:spot :meta])
             :blocked (account-context/hyperevm-moves-blocked-message state)
             :hyperevm-status (balances-hyperevm/hyperevm-status state)}]
    (mapv #(assoc % :move-targets (row-targets state ctx %)) rows)))
