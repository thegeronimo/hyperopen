(ns hyperopen.outcomes.application.exit-actions
  "Exits for the account Outcomes tab. Each is ONE action per click: a handler
   may return many effects, which all run against one snapshot, where several
   actions in one click would overwrite each other.

   Cash out and merge are composites: select the side's market, then change
   the ticket. The ticket change is computed against the state AFTER the
   market switch (the switch resets the form) and folded into the same
   `:effects/save-many`, so the click lands as one state transition."
  (:require [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.order.actions :as order-actions]))

(defn- project-effects
  "`state` as it will be once the `:effects/save` / `:effects/save-many`
   effects in `effects` have run, in order."
  [state effects]
  (reduce (fn [state* [effect-id & args]]
            (case effect-id
              :effects/save (let [[path value] args] (assoc-in state* path value))
              :effects/save-many (reduce (fn [state** [path value]] (assoc-in state** path value))
                                         state*
                                         (first args))
              state*))
          state
          effects))

(defn- save-many-pairs
  [effects]
  (into [] (mapcat (fn [[effect-id pairs]] (when (= :effects/save-many effect-id) pairs))) effects))

(defn- with-ticket-entry
  "`effects` (a market switch) followed, in the same first `save-many`, by the
   writes of `entry` applied to the switched state."
  [state effects entry]
  (let [switched (project-effects state effects)
        writes (save-many-pairs (order-actions/set-outcome-ticket-entry switched entry))
        [head & tail] effects]
    (if (and (= :effects/save-many (first head)) (seq writes))
      (into [[:effects/save-many (into (vec (second head)) writes)]] tail)
      (into effects (when (seq writes) [[:effects/save-many writes]])))))

(defn- whole-shares-text
  [shares]
  (str (js/Math.floor (or shares 0))))

(defn open-outcome-market
  "Take the user to the market for `side-coin`. Never the perp reduce popover."
  [state _market-key side-coin]
  (asset-actions/select-asset state side-coin))

(defn open-outcome-cash-out
  "Open `side-coin`'s market with the ticket in Cash out mode and `shares`
   (whole) typed in as shares: side sell, size unit shares, size filled, in
   one state transition. Nothing happens for a coin the catalog cannot select."
  [state _market-key side-coin shares]
  (let [effects (asset-actions/select-asset state side-coin)]
    (if (empty? effects)
      effects
      (with-ticket-entry state effects {:side :sell
                                        :size-input-mode :base
                                        :size-display (whole-shares-text shares)}))))

(defn merge-outcome-pairs
  "Open the contract's market with the Merge pairs ticket showing `pairs`
   (whole) pairs of `yes-coin` + `no-coin`, in one state transition. The
   exchange call is the ticket's own submit (`:actions/submit-outcome-merge`)."
  [state _market-key yes-coin no-coin pairs]
  (let [effects (asset-actions/select-asset state yes-coin)]
    (if (empty? effects)
      effects
      (with-ticket-entry state effects {:merge {:coins [yes-coin no-coin]
                                                :pairs (whole-shares-text pairs)}}))))
