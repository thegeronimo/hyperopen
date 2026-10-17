(ns hyperopen.portfolio.optimizer.application.vault-funding.plan
  "Pure planner for funding an optimizer vault allocation: given the target
  weights, the user's current vault holdings and an amount of new cash, work
  out which vaults to withdraw from and which to deposit into.

  Rules (owner-approved design, 2026-10-16):

  - By default existing deposits count toward the allocation: the total is
    holdings + new cash and each vault's target is its weight of that total.
  - A vault above its target gets no new cash. When `withdraw-over?` is on and
    the position is unlocked, the excess is withdrawn first and recycled into
    the vaults below target; a locked excess is reported, not withdrawn.
  - Cash is shared out in proportion to each vault's shortfall, so when there
    is not enough to fill every gap all of them close by the same fraction.
  - With `include-existing?` off only the new cash is split, by target
    weight; holdings are left alone and nothing is withdrawn (drift is still
    measured on the real holdings).
  - With no new cash, an unlocked excess is still withdrawn and redeposited:
    a pure rebalance.
  - A vault closed to deposits gets nothing. Its share is either spread over
    the open vaults (`:closed-mode :spread`, default: the open vaults' targets
    grow, so none of them is withdrawn from to make room) or kept as cash.
  - No vault is ever both withdrawn from and deposited into.

  Amounts are USD floats here; the runner converts each step to integer USDC
  micros, the unit `vaultTransfer` takes."
  )

(def min-step-usd
  "Transfers below this are dust: not worth a signed request."
  1)

(def closed-modes
  #{:spread :cash})

(defn floor-cents
  "Down to whole cents (never send more than the plan's cash), with a nudge
  so float noise such as 299.99999 still reads as 300.00."
  [x]
  (/ (js/Math.floor (+ (* 100 (max 0 x)) 1e-6)) 100))

(defn- normalize-targets
  "Vault legs, weights rescaled to sum 1 (a mixed run's perp legs are traded
  from the Execution tab, so the vault sleeve is funded on its own). A leg at
  0% stays in: anything held there is above target and can be withdrawn."
  [targets]
  (let [legs (filterv #(and (string? (:address %)) (number? (:weight %)) (not (neg? (:weight %)))) targets)
        total (reduce + 0 (map :weight legs))]
    (if (pos? total)
      (mapv #(assoc % :weight (/ (:weight %) total)) legs)
      [])))

(defn- locked?
  [{:keys [locked-until-ms]} now-ms]
  (and (number? locked-until-ms) (number? now-ms) (> locked-until-ms now-ms)))

(defn- share-out
  "Split `pool` over `needs` ({k need}) in proportion to need, never more than
  each need. Returns {k amount}."
  [pool needs]
  (let [total (reduce + 0 (vals needs))]
    (if (or (not (pos? total)) (not (pos? pool)))
      (zipmap (keys needs) (repeat 0))
      (let [scale (min 1 (/ pool total))]
        (into {} (map (fn [[k need]] [k (* need scale)])) needs)))))

(defn- drift
  "Half the summed absolute gap between holdings shares and target weights:
  the fraction of the sleeve that sits in the wrong vault (0 = on target)."
  [rows amount-key]
  (let [total (reduce + 0 (map amount-key rows))]
    (if (pos? total)
      (/ (reduce + 0 (map #(js/Math.abs (- (/ (amount-key %) total) (:weight %))) rows)) 2)
      0)))

(defn- row-status
  [{:keys [withdraw deposit over need deposits-closed? locked?]}]
  (cond
    (>= withdraw min-step-usd) :withdraw
    (>= over min-step-usd) (if locked? :over-locked :over-kept)
    (>= deposit min-step-usd) :deposit
    deposits-closed? :closed
    (>= need 0.01) :too-small
    :else :on-target))

(defn- targets-usd
  "Each row's dollar target. Under :spread a vault closed to deposits keeps at
  most what it already holds and the open vaults share the rest by weight, so
  one target per vault drives both sides: a vault is never withdrawn from and
  topped up in the same plan, and no over-target vault receives cash. Under
  :cash every vault keeps its own weight; a closed vault's gap stays cash."
  [rows total closed-mode]
  (if (= closed-mode :spread)
    (let [closed-target (fn [{:keys [weight hold]}] (min (* weight total) hold))
          closed-total (reduce + 0 (map closed-target (filter :deposits-closed? rows)))
          open-weight (reduce + 0 (map :weight (remove :deposits-closed? rows)))
          open-total (max 0 (- total closed-total))]
      (mapv (fn [{:keys [weight] :as row}]
              (if (:deposits-closed? row)
                (closed-target row)
                (if (pos? open-weight) (* open-total (/ weight open-weight)) 0)))
            rows))
    (mapv #(* (:weight %) total) rows)))

(defn build-plan
  "Inputs:

    :targets            [{:address :id :label :weight}] (vault legs of the run)
    :holdings           {address {:equity :locked-until-ms}}
    :amount             new cash to add, USD
    :available          USDC the account can spend on deposits, USD (or nil)
    :include-existing?  count holdings toward the target (default true)
    :withdraw-over?     withdraw unlocked excess first (default true)
    :closed             #{address} vaults closed to deposits
    :closed-mode        :spread | :cash
    :now-ms             clock, for lock-ups

  Returns {:rows [...] :steps [...] :totals {...} :drift-before :drift-after
           :errors [...]} where :steps lists withdrawals first, then deposits,
  each {:kind :withdraw|:deposit :address :label :usd}."
  [{:keys [targets holdings amount available include-existing? withdraw-over? closed closed-mode now-ms]
    :or {include-existing? true withdraw-over? true closed #{} closed-mode :spread}}]
  (let [legs (normalize-targets targets)
        closed-mode* (if (contains? closed-modes closed-mode) closed-mode :spread)
        cash (max 0 (or amount 0))
        rows0 (mapv (fn [{:keys [address] :as leg}]
                      (let [holding (get holdings address)
                            held (max 0 (or (:equity holding) 0))]
                        (assoc leg
                               :held held
                               :hold (if include-existing? held 0)
                               :locked-until-ms (:locked-until-ms holding)
                               :locked? (locked? holding now-ms)
                               :deposits-closed? (contains? closed address))))
                    legs)
        total (+ cash (reduce + 0 (map :hold rows0)))
        rows1 (mapv (fn [{:keys [hold locked?] :as row} target]
                      (let [over (if include-existing? (max 0 (- hold target)) 0)
                            withdraw (if (and include-existing? withdraw-over? (not locked?) (>= over min-step-usd))
                                       (floor-cents over)
                                       0)]
                        (assoc row
                               :target-usd target
                               :over over
                               :withdraw withdraw
                               :need (if (:deposits-closed? row) 0 (max 0 (- target hold))))))
                    rows0
                    (targets-usd rows0 total closed-mode*))
        pool (+ cash (reduce + 0 (map :withdraw rows1)))
        base (share-out pool (into {} (map (juxt :address :need)) rows1))
        rows (mapv (fn [{:keys [address held withdraw] :as row}]
                     (let [deposit (floor-cents (get base address 0))
                           deposit* (if (>= deposit min-step-usd) deposit 0)
                           after (+ (- held withdraw) deposit*)
                           row* (assoc row :deposit deposit* :after after)]
                       (assoc row* :status (row-status row*))))
                   rows1)
        total-deposit (reduce + 0 (map :deposit rows))
        total-withdraw (reduce + 0 (map :withdraw rows))
        errors (cond-> []
                 (empty? legs) (conj :no-vaults)
                 (and (number? available) (> cash (+ available 1e-9))) (conj :over-available)
                 (and (seq legs) (zero? total-deposit) (zero? total-withdraw)) (conj :nothing-to-do))]
    {:rows rows
     :steps (into (mapv (fn [{:keys [address label withdraw]}]
                          {:kind :withdraw :address address :label label :usd withdraw})
                        (filter #(pos? (:withdraw %)) rows))
                  (map (fn [{:keys [address label deposit]}]
                         {:kind :deposit :address address :label label :usd deposit}))
                  (filter #(pos? (:deposit %)) rows))
     :totals {:cash cash
              :total total
              :withdraw total-withdraw
              :deposit total-deposit
              :leftover (max 0 (- pool total-deposit))}
     :include-existing? include-existing?
     :drift-before (drift rows :held)
     :drift-after (drift rows :after)
     :errors errors}))

(defn usd->micros
  "Integer USDC micros, rounded at nano precision first so 1350.69 does not
  floor to 1350.689999."
  [usd]
  (js/Math.floor (/ (js/Math.round (* (max 0 usd) 1000000000)) 1000)))
