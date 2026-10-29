(ns hyperopen.vol-range.application.replace-flow
  "Pure steps of a ladder update (HO-SPEC-014 v2.1 sections 10.2, 13.4; VR-15,
  VR-16): the frozen membership, the ticket patches for the preview and the
  replacement, the phase after a confirmation, and the predecessor check that
  placement admission runs inside the account admission section.

  Execution code (it reads the lifecycle); the A0 preview reaches update
  state only through `replace-state`."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.ladder-settings :as ladder-settings]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]))

(def ^:private zero (precision/parse-decimal "0"))
(defn- d [x] (or (some-> x str precision/parse-decimal) zero))
(defn- ds [x] (precision/decimal->string x))

;; ---------------------------------------------------------------------------
;; Freeze (spec 10.2 step 2)
;; ---------------------------------------------------------------------------

(defn freeze
  "The membership an update cancels, taken from the journal-refreshed `grid`
  at the moment the user starts the cancel: {:frozen-cloids [cloid ...]
  :filled-at-freeze {cloid filled-base}}. Fills recorded here are not
  \"filled while canceling\"."
  [grid]
  (let [cloids (lifecycle/replace-membership grid)]
    {:frozen-cloids cloids
     :filled-at-freeze (into {} (map (fn [c] [c (:filled (lifecycle/leg-quantities grid (get-in grid [:legs c])))]))
                             cloids)}))

(defn phase-after-confirm
  "`:canceled` once the basis is ready, else `:blocked` (VR-15)."
  [basis]
  (if (:ready? basis) :canceled :blocked))

;; ---------------------------------------------------------------------------
;; Ticket patches
;; ---------------------------------------------------------------------------

(defn- side-patch
  "Base-unit quantity for one side; a side with nothing to place is disabled."
  [side quantity]
  (let [q (d quantity)]
    [[[side :amount-unit] :base]
     [[side :quantity] (ds q)]
     [[side :enabled?] (pos? (precision/cmp q zero))]]))

(defn- merge-patch
  "`patch` with each path in `overrides` replaced in place; override paths
  that `patch` lacks are appended. Every path appears once."
  [patch overrides]
  (let [by-path (into {} overrides)
        seen (set (map first patch))]
    (into (mapv (fn [[path value]] [path (get by-path path value)]) patch)
          (remove #(contains? seen (first %)) overrides))))

(defn- blank? [x] (or (nil? x) (and (string? x) (= "" (.trim x)))))

(defn- buy-cap
  "Spec 10.2 preview: keep the deployed maximum USDC debit when one was
  entered; when the buys were entered in USDC, that amount is the cap;
  otherwise blank, so the ticket's Use plan total remediation applies."
  [draft]
  (let [buy (:buy draft)]
    (cond (not (blank? (:max-usdc-debit buy))) (:max-usdc-debit buy)
          (= :quote (:amount-unit buy)) (or (:quote-amount buy) "")
          :else "")))

(defn preview-patch
  "Patch pairs that load the deployed ladder into the ticket for Preview
  update: the recorded draft (when `settings` has one) with each side's
  quantity set, in base units, to `remaining` ({:buy :sell}, the live
  remainder of the current members) and the buy cap rule applied. Without a
  recorded draft only the quantities are patched."
  [settings remaining]
  (let [base (ladder-settings/settings->draft-patch settings)
        sides (into (side-patch :buy (:buy remaining)) (side-patch :sell (:sell remaining)))]
    (merge-patch base (cond-> sides
                        (seq base) (conj [[:buy :max-usdc-debit] (buy-cap (:draft settings))])))))

(defn included-quantities
  "{:buy :sell} default replacement quantities: the frozen canceled remainder
  plus the full base quantity of each rejected member the user selected
  (`include-rejected`, logical ids). Rejected members are never included
  silently."
  [basis include-rejected]
  (let [picked (filter #(contains? (set include-rejected) (:logical-id %)) (:rejected basis))
        extra (fn [side] (reduce precision/add zero (map #(d (:original-base %)) (filter #(= side (:side %)) picked))))]
    {:buy (ds (precision/add (d (get-in basis [:remaining :buy])) (extra :buy)))
     :sell (ds (precision/add (d (get-in basis [:remaining :sell])) (extra :sell)))}))

(defn quantities-patch
  "Patch pairs for Review replacement: each side in base units at
  `included-quantities`; a side with nothing to place is disabled."
  [basis include-rejected]
  (let [{:keys [buy sell]} (included-quantities basis include-rejected)]
    (into (side-patch :buy buy) (side-patch :sell sell))))

;; ---------------------------------------------------------------------------
;; Predecessor admission (spec 10.2 step 6)
;; ---------------------------------------------------------------------------

(defn- successor?
  "A grid that names `grid-id` as its predecessor and may have placed
  something. A replacement that was never placed (every leg refused or not
  sent) does not use up the old ladder."
  [grid-id g]
  (and (= grid-id (get-in g [:predecessor :grid-id]))
       (not= :not-placed (lifecycle/status g))))

(defn predecessor-blockers
  "Why a replacement naming `predecessor` cannot be placed for `scope-key`,
  judged on the JOURNAL `records` inside the account admission section:
  [] or `:replace/predecessor-missing` (no such ladder, or the frozen
  membership is not its legs), `:replace/predecessor-scope` (another market
  or account), `:replace/predecessor-not-settled` (the frozen membership's
  replacement basis is not ready: unknown or open old legs, or fills not
  covered) and `:replace/already-replaced` (another ladder already replaced
  it)."
  [records predecessor scope-key]
  (let [grid-id (:grid-id predecessor)
        frozen (vec (:frozen-cloids predecessor))
        grids (jr/grids records nil)
        old (some #(when (= grid-id (:grid-id %)) %) grids)]
    (cond
      (or (nil? grid-id) (nil? old) (empty? frozen) (not-every? #(contains? (:legs old) %) frozen))
      [:replace/predecessor-missing]

      (not= scope-key (:scope-key old))
      [:replace/predecessor-scope]

      :else
      (cond-> []
        (not (:ready? (lifecycle/replacement-basis old {:frozen-cloids frozen})))
        (conj :replace/predecessor-not-settled)

        (some #(successor? grid-id %) grids)
        (conj :replace/already-replaced)))))
