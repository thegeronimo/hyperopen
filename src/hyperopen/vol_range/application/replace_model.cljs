(ns hyperopen.vol-range.application.replace-model
  "Pure view models for updating a ladder (HO-SPEC-014 v2.1 sections 10.2,
  11.2, 11.4, 13.4).

  `replace-dialog-model` is the \"Update ladder\" dialog: old versus proposed
  settings (the old ladder from `[:vol-range :execution :grids]`, the
  proposal from the live plan the caller passes), the cancel step, why a
  replacement is blocked, and the unfilled remainder once the old ladder is
  canceled. `banner-model` is the ticket's update banner, `review-replacement`
  the review's lineage section (from the frozen review context), and
  `row-control`, `lineage` and `status-message` serve the Vol ladders dock.
  Every display value is an already-formatted string; copy says \"ladder\",
  never \"grid\".

  Execution code (it reads the lifecycle and `replace-flow`); the A0 review
  model never requires it: the order-form view adds `:replacement` at the
  view boundary."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.display :as display]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.replace-flow :as replace-flow]
            [hyperopen.vol-range.application.replace-state :as replace-state]
            [hyperopen.vol-range.application.repair-model :as repair-model]))

(def intermediate-message
  "Spec 10.2, exactly."
  "Old ladder canceled. Review the updated quantities before placing the replacement.")

(def explainer
  (str "Updating takes two separate steps. First this ladder's open orders are canceled. "
       "Then you review and place a new ladder. If you stop after canceling, no new orders are placed."))

(def quantity-note "Proposed quantities are the unfilled remainder of this ladder. Edit the ticket to change them.")

(def settings-note
  "This ladder's original settings weren't recorded; the proposal uses the ticket's current settings.")

(def ^:private zero (precision/parse-decimal "0"))
(defn- d [x] (or (some-> x str precision/parse-decimal) zero))
(defn- busy? [state k] (true? (get-in state [:vol-range :execution :busy k])))
(defn- plural [n word] (str n " " word (when (not= 1 n) "s")))
(defn- side-label [side] (if (= :buy side) "Buy" "Sell"))
(defn- grid [state grid-id] (when grid-id (get-in state [:vol-range :execution :grids grid-id])))
(defn- with-base [x base] (when x (str x (when base (str " " base)))))

(defn created-text
  "\"2026-10-28 14:02 UTC\" for an epoch-millisecond value."
  [ms]
  (when (number? ms)
    (let [t (js/Date. ms)
          p #(if (< % 10) (str "0" %) (str %))]
      (str (.getUTCFullYear t) "-" (p (inc (.getUTCMonth t))) "-" (p (.getUTCDate t)) " "
           (p (.getUTCHours t)) ":" (p (.getUTCMinutes t)) " UTC"))))

(defn- update-busy? [state grid-id]
  (or (busy? state [:replace-cancel grid-id]) (busy? state [:replace-confirm grid-id])))

(defn- step-number [phase]
  (case phase :preview 1 (:canceling :blocked) 2 3))

;; ---------------------------------------------------------------------------
;; Quantities and lineage texts
;; ---------------------------------------------------------------------------

(defn remaining-text
  "\"Unfilled remainder: buys 40 VRT, sells 27 VRT.\""
  [{:keys [buy sell]} base]
  (str "Unfilled remainder: buys " (with-base (or (display/exact buy) "0") base)
       ", sells " (with-base (or (display/exact sell) "0") base) "."))

(defn filled-text
  "\"Filled while canceling: sells 3 VRT.\", or nil when nothing filled."
  [filled base]
  (let [parts (keep (fn [side]
                      (let [q (get filled side)]
                        (when (pos? (precision/cmp (d q) zero))
                          (str (if (= :buy side) "buys " "sells ") (with-base (display/exact q) base)))))
                    [:buy :sell])]
    (when (seq parts) (str "Filled while canceling: " (str/join ", " parts) "."))))

(defn- replaces-text [old]
  (if-let [t (created-text (:created-at-ms old))]
    (str "Replaces the ladder created " t)
    "Replaces an earlier ladder"))

;; ---------------------------------------------------------------------------
;; Old versus proposed (spec 10.2 step 1)
;; ---------------------------------------------------------------------------

(def ^:private anchor-labels
  {:vwap-24h "24h VWAP" :utc-open "UTC open" :market-price "Market price" :custom "Custom anchor"})

(defn- anchor-text [model]
  (when-let [price (display/exact (get-in model [:anchor :price]))]
    (str price (when-let [l (get anchor-labels (get-in model [:anchor :mode]))] (str " · " l)))))

(defn- tilt-text [model]
  (cond (nil? model) nil
        (get-in model [:tilt :enabled?]) (str "On · effective anchor " (or (display/exact (:shifted-anchor model)) "—"))
        :else "Off"))

(defn- side-text
  "\"4 orders · 76.4 to 72.6 · 40 VRT\" (nearest to farthest price), or
  \"None\"."
  [legs side size-key base]
  (let [legs (sort-by #(d (:price %)) (if (= :buy side) #(precision/cmp %2 %1) precision/cmp)
                      (filter #(= side (:side %)) legs))
        total (reduce precision/add zero (map #(d (size-key %)) legs))]
    (if (empty? legs)
      "None"
      (str (plural (count legs) "order") " · " (display/exact (:price (first legs)))
           (when (> (count legs) 1) (str " to " (display/exact (:price (last legs)))))
           " · " (with-base (display/exact (precision/decimal->string total)) base)))))

(defn- reserve-text [reserve {:keys [symbol quote-symbol]}]
  (when (and reserve (:token reserve))
    (str (display/exact (:amount reserve)) " " (if (= :quote (:token reserve)) quote-symbol symbol))))

(defn- live-legs
  "The old ladder's current live members, with their unfilled remainder."
  [g]
  (keep (fn [leg]
          (when (lifecycle/live-leg? leg)
            (assoc leg :remaining (:remaining (lifecycle/leg-quantities g leg)))))
        (lifecycle/current-legs g)))

(defn- compare-rows
  [g plan]
  (let [scope (:scope g)
        base (:symbol scope)
        quote (:quote-symbol scope)
        settings (:settings g)
        recorded (fn [v] (if settings v "Not recorded"))
        totals (:totals settings)
        outcomes (:inventory-outcomes settings)
        pmodel (:model plan)
        row (fn [field label current proposed] {:field field :label label :current current :proposed proposed})]
    [(row :anchor "Anchor" (anchor-text (:model g)) (anchor-text pmodel))
     (row :volatility "Daily volatility" (display/percent (get-in g [:model :volatility :daily-fraction]))
          (display/percent (get-in pmodel [:volatility :daily-fraction])))
     (row :tilt "Inventory tilt" (tilt-text (:model g)) (tilt-text pmodel))
     (row :buys "Buys" (side-text (live-legs g) :buy :remaining base) (side-text (:legs plan) :buy :size base))
     (row :sells "Sells" (side-text (live-legs g) :sell :remaining base) (side-text (:legs plan) :sell :size base))
     (row :buy-fee-reserve "Buy fee reserve" (recorded (reserve-text (get-in totals [:buy :fee-reserve]) scope))
          (reserve-text (get-in plan [:totals :buy :fee-reserve]) scope))
     (row :max-buy-debit "Maximum buy debit" (recorded (with-base (display/exact (get-in totals [:buy :max-debit])) quote))
          (with-base (display/exact (get-in plan [:totals :buy :max-debit])) quote))
     (row :all-buys-first "If all buys fill first" (recorded (with-base (display/exact (:all-buys-first outcomes)) base))
          (with-base (display/exact (get-in plan [:inventory-outcomes :all-buys-first])) base))
     (row :all-sells-first "If all sells fill first"
          (recorded (with-base (display/exact (:all-sells-first outcomes)) base))
          (with-base (display/exact (get-in plan [:inventory-outcomes :all-sells-first])) base))]))

;; ---------------------------------------------------------------------------
;; Dialog
;; ---------------------------------------------------------------------------

(defn- control
  ([label enabled?] (control label enabled? false nil))
  ([label enabled? busy? aria-label]
   {:label label :enabled? (boolean enabled?) :busy? (boolean busy?) :aria-label aria-label}))

(defn- still-open-count [g frozen]
  (let [legs (keep #(get-in g [:legs %]) frozen)]
    (count (filter lifecycle/live-leg? (if (seq legs) legs (lifecycle/current-legs g))))))

(defn- blocking-text [code g frozen]
  (case code
    :replace/unresolved-outcomes
    "Some old orders have an unknown status. The replacement can't be reviewed until they are settled."
    :replace/legs-still-open
    (let [n (still-open-count g frozen)]
      (str (plural n "old order") " " (if (= 1 n) "is" "are") " still open."))
    :replace/fills-incomplete "Fills during the cancel aren't confirmed yet."
    :replace/check-failed "The cancellation couldn't be confirmed. Check again."
    (str "The replacement can't be reviewed yet (" (some-> code name) ").")))

(defn- rejected-rows [basis include-rejected selectable?]
  (let [selected (set include-rejected)]
    (mapv (fn [{:keys [logical-id side original-base price]}]
            {:logical-id logical-id
             :label (str "Include " (side-label side) " " (display/exact original-base) " at " (display/exact price))
             :selected? (contains? selected logical-id)
             :enabled? (boolean selectable?)})
          (:rejected basis))))

(defn- included-text [basis include-rejected base]
  (when (seq include-rejected)
    (let [{:keys [buy sell]} (replace-flow/included-quantities basis include-rejected)]
      (str "With the selected orders: buys " (with-base (display/exact buy) base)
           ", sells " (with-base (display/exact sell) base) "."))))

(def ^:private no-actions
  {:cancel-old nil :edit nil :cancel-remaining nil :check nil :use-quantities nil
   :abandon (control "Stop updating" true)})

(defn replace-dialog-model
  "The \"Update ladder\" dialog model, or nil unless an update's dialog is
  open and its ladder is in the store. `plan` is the ticket's live plan (the
  proposal)."
  [state plan]
  (let [{:keys [grid-id phase dialog-open? basis frozen-cloids include-rejected settings-recorded?]}
        (replace-state/update-state state)
        g (grid state grid-id)]
    (when (and dialog-open? g)
      (let [scope (:scope g)
            base (:symbol scope)
            market (repair-model/market-label scope)
            current? (replace-state/current? state)
            working? (update-busy? state grid-id)
            blocking (set (:blocking basis))
            live (:live (lifecycle/counts g))
            step (step-number phase)
            canceled? (contains? #{:canceled :ready} phase)]
        {:grid-id grid-id
         :phase phase
         :stale-text (when-not current?
                       (str "This update is for the " market " ladder. Open " market
                            " to continue, or stop updating."))
         :title "Update ladder"
         :identity-line (str/join " · " (remove str/blank? [market (display/short-address
                                                                    (some-> (:account-address scope) str))
                                                            "Post-only"]))
         :steps (mapv (fn [[n label]] {:label (str n " " label) :current? (= n step)})
                      [[1 "Preview"] [2 "Cancel old ladder"] [3 "Review replacement"]])
         :explainer (when (and current? (= :preview phase)) explainer)
         :compare (if (and current? (= :preview phase)) (compare-rows g plan) [])
         :settings-note (when (and current? (= :preview phase) (not settings-recorded?)) settings-note)
         :quantity-note (when (and current? (= :preview phase)) quantity-note)
         :phase-text (when (and current? (= :canceling phase)) "Canceling the old ladder…")
         :blocking (if (and current? (= :blocked phase))
                     (mapv #(blocking-text % g frozen-cloids) (or (seq (:blocking basis)) [:replace/check-failed]))
                     [])
         :message (when (and current? canceled?) intermediate-message)
         :remaining-text (when (and current? canceled?) (remaining-text (:remaining basis) base))
         :filled-text (when (and current? canceled?) (filled-text (:filled-during-update basis) base))
         :included-text (when (and current? canceled?) (included-text basis include-rejected base))
         :rejected (if (and current? canceled?)
                     (rejected-rows basis include-rejected (and (= :canceled phase) (not working?)))
                     [])
         :actions
         (if-not current?
           no-actions
           (assoc no-actions
                  :cancel-old (when (= :preview phase)
                                (control "Cancel current ladder to update" (not working?) false
                                         (str "Cancel the " (plural live "open order") " of the " market " ladder")))
                  :edit (when (= :preview phase) (control "Edit in ticket" true))
                  :cancel-remaining (when (and (= :blocked phase) (contains? blocking :replace/legs-still-open))
                                      (control "Cancel remaining orders" (not working?) working? nil))
                  :check (when (= :blocked phase) (control "Check again" (not working?) working? nil))
                  :use-quantities (when (= :canceled phase)
                                    (control "Review replacement" (and (:ready? basis) (not working?))))))}))))

;; ---------------------------------------------------------------------------
;; Ticket banner
;; ---------------------------------------------------------------------------

(defn banner-model
  "The ticket's update banner, or nil without an update. While the update is
  before its replacement review the banner's controls replace the ticket's
  Review button (`:replaces-footer?`); in `:ready` the normal footer renders
  under it."
  [state]
  (let [{:keys [grid-id phase] :as u} (replace-state/update-state state)
        g (grid state grid-id)]
    (when (and u g)
      (let [market (repair-model/market-label (:scope g))
            current? (replace-state/current? state)
            working? (update-busy? state grid-id)
            canceled? (contains? #{:canceled :ready} phase)]
        (if-not current?
          {:phase phase
           :title (str "Updating the " market " ladder")
           :message (str "Open " market " to continue updating this ladder.")
           :lineage nil
           :replaces-footer? false
           :open-update? false
           :refresh-balances nil
           :busy? false}
          {:phase phase
           :title (str "Updating the " market " ladder · step " (step-number phase) " of 3")
           :message (case phase
                      :canceling "Canceling the old ladder…"
                      :blocked "The old ladder isn't fully canceled yet. Open the update to check again."
                      (:canceled :ready) intermediate-message
                      nil)
           :lineage (when canceled?
                      (when-let [t (created-text (:created-at-ms g))] (str "Replacing the ladder created " t)))
           :replaces-footer? (contains? replace-state/review-blocked-phases phase)
           :open-update? (not= :ready phase)
           :refresh-balances (when (= :ready phase)
                               {:enabled? (not working?) :busy? (boolean working?)})
           :busy? (boolean (or working? (= :canceling phase)))})))))

;; ---------------------------------------------------------------------------
;; Review lineage (spec 10.2 steps 5-6)
;; ---------------------------------------------------------------------------

(defn- member-leg [g logical-id]
  (get-in g [:legs (lifecycle/current-cloid (get-in g [:members logical-id]))]))

(defn- rejected-included-text [g ids]
  (if (empty? ids)
    "Rejected orders from the old ladder are not included."
    (str "Rejected orders included: "
         (str/join ", " (map (fn [id]
                               (if-let [{:keys [side original-base price]} (member-leg g id)]
                                 (str (side-label side) " " (display/exact original-base)
                                      " (old price " (display/exact price) ")")
                                 id))
                             ids)))))

(defn- side-quantity [draft side]
  (when (map? draft)
    (let [s (get draft side)]
      (cond (false? (:enabled? s)) zero
            (= :base (:amount-unit s)) (d (:quantity s))
            :else nil))))

(defn- edited?
  "True when the frozen review's side quantities are not the remainder plus
  the included rejected orders (or were entered in USDC)."
  [draft predecessor g]
  (let [extra (fn [side] (reduce precision/add zero
                                 (keep #(let [leg (member-leg g %)]
                                          (when (= side (:side leg)) (d (:original-base leg))))
                                       (:included-rejected predecessor))))]
    (boolean
     (and (map? draft)
          (some (fn [side]
                  (let [q (side-quantity draft side)
                        expected (precision/add (d (get-in predecessor [:remaining side])) (extra side))]
                    (or (nil? q) (not (zero? (precision/cmp q expected))))))
                [:buy :sell])))))

(defn review-replacement
  "The review's `:replacement` section for a review frozen with an update's
  lineage, or nil for an ordinary review."
  [state]
  (when-let [predecessor (get-in state [:vol-range :frozen-context :replace :predecessor])]
    (let [g (grid state (:grid-id predecessor))
          base (or (get-in g [:scope :symbol]) (get-in state [:vol-range :frozen-review :scope :symbol]))]
      {:lineage-text (str (replaces-text g) ".")
       :remaining-text (remaining-text (:remaining predecessor) base)
       :filled-text (filled-text (:filled-during-update predecessor) base)
       :rejected-text (rejected-included-text g (:included-rejected predecessor))
       :edited-text (when (edited? (get-in state [:vol-range :frozen-context :draft]) predecessor g)
                      "You changed the quantities from the remainder.")})))

;; ---------------------------------------------------------------------------
;; Dock
;; ---------------------------------------------------------------------------

(defn row-control
  "The dock row's Preview update button: shown for a live or partially
  deployed ladder placed in this browser (not recovered, not being canceled)
  while placement is enabled and no update of it is past its preview;
  enabled only on the ladder's market and account and while nothing else
  is working on it."
  [state g]
  (let [id (:grid-id g)
        market (repair-model/market-label (:scope g))
        u (get-in state [:vol-range :replace])
        current-key (get-in state [:vol-range-dock :current-scope-key])
        visible? (boolean (and (get-in state [:vol-range :execution :enabled?])
                               (not (:recovered? g))
                               (not (:cancel-requested-at-ms g))
                               (contains? #{:live :partially-deployed} (lifecycle/status g))
                               (or (not= id (:grid-id u)) (= :preview (:phase u)))))
        in-scope? (= (:scope-key g) current-key)
        other-update? (and u (= (:scope-key u) current-key) (not= :preview (:phase u)))
        working? (or (busy? state [:repair-preview id]) (busy? state [:repair id])
                     (= id (get-in state [:vol-range :repair :grid-id]))
                     (and u (update-busy? state (:grid-id u)))
                     ;; A stopped update's operation of this ladder still runs.
                     (update-busy? state id))]
    {:visible? visible?
     :enabled? (boolean (and visible? in-scope? (not other-update?) (not working?)))
     :label "Preview update"
     :aria-label (str "Preview an update of the " market " ladder")
     :hint (when (and visible? (not in-scope?)) (repair-model/scope-hint market))}))

(defn- successor [state grid-id]
  (->> (vals (get-in state [:vol-range :execution :grids]))
       (filter #(and (= grid-id (get-in % [:predecessor :grid-id])) (not= :not-placed (lifecycle/status %))))
       (sort-by :created-at-ms)
       first))

(defn lineage
  "\"Replaces the ladder created …\" on a replacement, \"Replaced by the
  ladder created …\" on the ladder it replaced; nil otherwise."
  [state g]
  (let [parts (cond-> []
                (get-in g [:predecessor :grid-id])
                (conj (replaces-text (grid state (get-in g [:predecessor :grid-id]))))
                :always
                (into (when-let [s (successor state (:grid-id g))]
                        [(if-let [t (created-text (:created-at-ms s))]
                           (str "Replaced by the ladder created " t)
                           "Replaced by a newer ladder")])))]
    (when (seq parts) (str/join " · " parts))))

(def ^:private cancel-refusal-text
  {:busy "An order placement for this ladder is still in flight. Cancel again in a moment."
   :refused "This ladder belongs to another account or your trading session isn't ready."})

(def replacement-admission-text
  (str "The market or your balances changed since review. "
       "Refresh balances on the update banner, then review the ladder again."))

(def stopped-unsettled-text
  "Update stopped. No new orders were placed. Some of the old ladder's orders may still be open.")

(defn- stop-text [old-ladder]
  (case old-ladder
    :unchanged "Update stopped. Your ladder was not changed."
    :canceled "Update stopped. The old ladder stays canceled; no new orders were placed."
    stopped-unsettled-text))

(defn- for-current-update?
  "True when `last-result` answers the update in progress (same ladder, same
  start); false for an operation of an update that was stopped or
  restarted."
  [state {:keys [grid-id update-started-at-ms]}]
  (let [u (get-in state [:vol-range :replace])]
    (boolean (and u (= grid-id (:grid-id u)) (= update-started-at-ms (:started-at-ms u))))))

(defn- stopped-result-text
  "An update operation that finished after Stop updating: what it did to the
  old ladder, never a step of the update."
  [g {:keys [status errors]}]
  (case status
    :ready "Update stopped. The old ladder was canceled; no new orders were placed."
    :blocked (str/join " " (concat ["Update stopped. No new orders were placed."
                                    "The old ladder isn't fully canceled yet."]
                                   (map #(blocking-text % g nil) (filter keyword? errors))))
    (:busy :refused) "Update stopped. Nothing was canceled; your ladder was not changed."
    (str stopped-unsettled-text " The cancellation couldn't be confirmed.")))

(defn status-message
  "Live-region text for an update result (`:replace-cancel`,
  `:replace-confirm`, `:replace-stop`) in `[:vol-range :execution :last]`.
  Results carry the `:update-started-at-ms` of the update they answer; one
  for an update that is no longer in progress is announced as a stopped
  update."
  [state {:keys [op status errors error old-ladder] :as last-result}]
  (let [g (grid state (:grid-id last-result))
        frozen (get-in state [:vol-range :replace :frozen-cloids])]
    (case op
      :replace-stop
      (stop-text old-ladder)

      (:replace-cancel :replace-confirm)
      (if-not (for-current-update? state last-result)
        (stopped-result-text g last-result)
        (case status
          :ready (if (and (= :replace-confirm op) (= :ready (get-in state [:vol-range :replace :phase])))
                   "Balances refreshed. Review the replacement before placing it."
                   intermediate-message)
          :blocked (str/join " " (cons "The old ladder isn't fully canceled yet."
                                       (map #(blocking-text % g frozen) (filter keyword? errors))))
          (:busy :refused) (str "Nothing was canceled. " (get cancel-refusal-text status))
          (str "The cancellation couldn't be confirmed. Check again." (when (string? error) (str " " error)))))

      nil)))
