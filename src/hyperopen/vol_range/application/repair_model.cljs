(ns hyperopen.vol-range.application.repair-model
  "Pure view models for repairing a ladder's definitively rejected orders
  (HO-SPEC-014 v2.1 sections 8.6, 11.2, 11.4, 13.4).

  `repair-model` is the \"Review rejected orders\" dialog, built only from the
  frozen proposal at `[:vol-range :repair]`, the ladder in
  `[:vol-range :execution :grids]`, busy keys and the last result. Nothing is
  re-priced here: prices and sizes are the frozen proposal's. `row-control`
  is the dock row's \"Repair N rejected orders\" button and
  `status-message` the live-region text for the repair operations. Every
  display value is an already-formatted string; the dialog says \"ladder\",
  never \"grid\"."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.display :as display]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.repair-plan :as repair-plan]
            [hyperopen.vol-range.contracts :as contracts]))

(defn- busy? [state k] (true? (get-in state [:vol-range :execution :busy k])))
(defn- plural [n word] (str n " " word (when (not= 1 n) "s")))
(defn- side-label [side] (if (= :buy side) "Buy" "Sell"))
(defn- dn [x] (some-> x str precision/parse-decimal))

(defn market-label
  "\"VRT/USDC\" for a ladder scope."
  [{:keys [symbol quote-symbol coin]}]
  (str (or symbol coin) (when quote-symbol (str "/" quote-symbol))))

(defn scope-hint
  "Shown when the ticket is on another market or account."
  [market]
  (str "Open " market " to repair or update this ladder."))

;; ---------------------------------------------------------------------------
;; Error texts
;; ---------------------------------------------------------------------------

(def ^:private moved-text "The market moved since these prices were set. Refresh prices and review again.")
(def ^:private stale-text "Prices or balances are out of date. Refresh prices and try again.")

(defn- code-text [code {:keys [market base]}]
  (case code
    (:repair/crosses-book :repair/self-cross :repair/duplicate-price :repair/too-far-from-mid) moved-text
    :repair/proposal-mismatch "This review is out of date. Refresh prices and review again."
    :repair/max-inventory-breach "These orders could take inventory above the maximum set for this ladder."
    :repair/protected-core-breach "These orders could take inventory below this ladder's protected core."
    :repair/insufficient-usdc "Not enough available USDC for these orders."
    :repair/insufficient-base (str "Not enough available " (or base "base") " for these orders.")
    :repair/funds-pending "Funds held for an earlier placement are still being confirmed. Refresh prices in a few seconds."
    :grid/cancel-requested "This ladder is being canceled, so it can't be repaired."
    :grid/closed "This ladder has no active orders left, so it can't be repaired."
    :journal/unavailable "Repairs need browser storage, which is unavailable."
    (:grid/unresolved :grid/unresolved-attempt)
    "Some orders in this ladder are still being checked. Refresh status first."
    (:guard/book-stale :guard/account-stale :guard/fees-stale) stale-text
    :guard/account-unavailable "Your balances couldn't be read. Refresh prices and try again."
    :guard/fees-unavailable "Your fee rate couldn't be read. Refresh prices and try again."
    :guard/scope-changed (scope-hint market)
    :guard/over-ladder-cap "These orders are over this browser's ladder cap."
    :capability/execution-disabled "Placement is turned off in this browser."
    nil))

(def ^:private status-text
  {:busy "Another placement for this account is in progress. Try again in a moment."
   :journal-unavailable "Repairs need browser storage, which is unavailable."
   :coordination-unavailable "Another tab could be trading this account; repairs are paused."
   :reconciliation-required "Checking an earlier placement before sending again."
   :blocked "This ladder can't be repaired right now."
   :refused "Repairs are not allowed right now."
   :admission-failed "The market or your balances changed. Refresh prices and review again."})

(defn- result-codes
  "Error codes of a result; a final-guard refusal carries them in :error."
  [{:keys [errors error]}]
  (vec (concat errors (when (sequential? error) (filter keyword? error)))))

(defn error-texts
  "Distinct texts for `codes`; unknown codes fall back to the generic text
  with their names."
  [codes ctx]
  (let [known (keep #(code-text % ctx) codes)
        unknown (remove #(code-text % ctx) codes)]
    (vec (distinct (concat known
                           (when (seq unknown)
                             [(str "Something went wrong. (" (str/join ", " (map name unknown)) ")")]))))))

(defn- result-texts
  "Why a result sent nothing, as texts."
  [{:keys [status error] :as result} ctx]
  (let [codes (result-codes result)]
    (cond
      (seq codes) (error-texts codes ctx)
      (string? error) [(str "Something went wrong. " error)]
      (contains? status-text status) [(get status-text status)]
      :else ["Something went wrong."])))

;; ---------------------------------------------------------------------------
;; Live region
;; ---------------------------------------------------------------------------

(defn- partial-text [{:keys [rejected live]}]
  (str (plural rejected "order") " " (if (= 1 rejected) "was" "were") " not placed. "
       live " other order" (when (not= 1 live) "s") " remain" (when (= 1 live) "s") " active."))

(defn status-message
  "Live-region text for a `:repair-preview` or `:repair` result in
  `[:vol-range :execution :last]`. A successful preview announces nothing:
  the dialog shows the proposal."
  ([last-result] (status-message last-result {}))
  ([{:keys [op status delivery counts] :as last-result} ctx]
   (case op
     :repair-preview
     (when (not= :ok status)
       (str/join " " (cons "Couldn't prepare the repair review." (result-texts last-result ctx))))

     :repair
     (cond
       (= :unknown delivery) "Checking the repair's outcome. Nothing will be resent."
       (= :response delivery)
       (let [{:keys [live rejected members]} counts]
         (if (and (pos? (or rejected 0)) (pos? (or live 0)))
           (partial-text counts)
           (str "Repair sent. " (or live 0) " of " (plural (or members 0) "order") " active.")))
       ;; An error after admission may follow a send: never claim nothing went out.
       (and (= :error status) (not= :not-sent delivery))
       (str "Something went wrong with the repair. Refresh status to check this ladder."
            (when (string? (:error last-result)) (str " " (:error last-result))))
       :else (str/join " " (cons "Nothing was sent." (result-texts last-result ctx))))

     nil)))

(defn scope-context
  "{:market :base} of a ladder, for error texts."
  [grid]
  {:market (market-label (:scope grid)) :base (get-in grid [:scope :symbol])})

;; ---------------------------------------------------------------------------
;; Dock row control
;; ---------------------------------------------------------------------------

(defn row-control
  "The dock row's repair button: shown only with definitively rejected
  current members (none for a closed or never-placed ladder, see
  `lifecycle/repair-candidates`), placement enabled, a ladder that is neither
  recovered nor being canceled, and no update in progress for it; enabled only when the
  ticket is on the ladder's market and account (`current-scope-key`);
  `:busy?` while its review is read or its repair is sent."
  [state grid]
  (let [id (:grid-id grid)
        n (count (lifecycle/repair-candidates grid))
        market (market-label (:scope grid))
        visible? (boolean (and (get-in state [:vol-range :execution :enabled?])
                               (pos? n)
                               (not (:recovered? grid))
                               (not (:cancel-requested-at-ms grid))
                               (not= id (get-in state [:vol-range :replace :grid-id]))))
        in-scope? (= (:scope-key grid) (get-in state [:vol-range-dock :current-scope-key]))
        working? (or (busy? state [:repair-preview id]) (busy? state [:repair id]))]
    ;; Not disabled while the review is read or sent: the button keeps focus,
    ;; so focus can return to it when the dialog closes. The action ignores a
    ;; click while either operation is busy.
    {:visible? visible?
     :enabled? (and visible? in-scope?)
     :busy? (and visible? working?)
     :count n
     :label (str "Repair " (plural n "rejected order"))
     :aria-label (str "Repair " (plural n "rejected order") " in the " market " ladder")
     :hint (when (and visible? (not in-scope?)) (scope-hint market))}))

;; ---------------------------------------------------------------------------
;; Dialog
;; ---------------------------------------------------------------------------

(defn- with-base [x base] (when x (str x (when base (str " " base)))))

(defn- leg-note [{:keys [side rejected-price repriced?]} bbo]
  (let [rejected (dn rejected-price)
        bid (dn (:bid bbo))
        ask (dn (:ask bbo))]
    (cond
      (not repriced?) "The original price no longer crosses the book."
      (and (= :buy side) rejected ask (>= (precision/cmp rejected ask) 0))
      (str "Moved away from the book: " (display/exact rejected-price) " would cross the best ask "
           (display/exact (:ask bbo)) ".")
      (and (= :sell side) rejected bid (<= (precision/cmp rejected bid) 0))
      (str "Moved away from the book: " (display/exact rejected-price) " would cross the best bid "
           (display/exact (:bid bbo)) ".")
      :else (str "Moved away from the book: " (display/exact rejected-price) " is no longer a safe post-only price."))))

(defn- leg-rows [{:keys [legs selected bbo]} base]
  (let [selected (set selected)]
    (mapv (fn [{:keys [logical-id side size] :as leg}]
            {:logical-id logical-id
             :side side
             :side-label (side-label side)
             :selected? (contains? selected logical-id)
             :select-label (str "Include " (side-label side) " " (display/exact size)
                                (when base (str " " base)))
             :rejected-price (display/exact (:rejected-price leg))
             :price (display/exact (:price leg))
             :size (display/exact size)
             :notional (display/fixed (:notional leg) 2)
             :distance (display/sigma-magnitude (:actual-sigma leg))
             :note (leg-note leg bbo)
             :reason (some-> (:reason leg) str)})
          legs)))

(defn- unrepairable-text [{:keys [side size rejected-price code]}]
  (str "Can't repair " (side-label side) " " (display/exact size) " at " (display/exact rejected-price) ": "
       (case code
         :repair/no-safe-price "no post-only price within 50% of the mid."
         :repair/below-min-notional (str "below the " (display/fixed (:min-leg-notional-usdc contracts/product-policy) 2)
                                         " USDC minimum at a safe price.")
         :repair/model-unavailable "the ladder's model at placement is unavailable."
         :repair/book-unavailable "the current book is unavailable."
         (str "no safe price (" (some-> code name) ")."))))

(defn- bound-rows [proposal base]
  (let [o (repair-plan/inventory-outcomes proposal)
        limits (:limits proposal)
        amt (fn [x] (with-base (display/exact x) base))]
    (if (nil? o)
      []
      [{:label "If all buys fill first" :value (amt (:all-buys-first o))}
       {:label "If all sells fill first" :value (amt (:all-sells-first o))}
       {:label "Hold at most" :value (cond (nil? limits) "Not recorded"
                                           (:maximum limits) (amt (:maximum limits))
                                           :else "No limit")}
       {:label "Keep at least" :value (cond (nil? limits) "Not recorded"
                                            (:core limits) (amt (:core limits))
                                            :else "None")}])))

(defn- last-result-for
  "The last `:repair` result for THIS proposal, unless a send is in flight."
  [state {:keys [grid-id proposal-id]}]
  (let [last-result (get-in state [:vol-range :execution :last])]
    (when (and (= :repair (:op last-result))
               proposal-id
               (= proposal-id (:proposal-id last-result))
               (not (busy? state [:repair grid-id])))
      last-result)))

(defn repair-model
  "The \"Review rejected orders\" dialog model, or nil when no review is open
  (or its ladder is gone)."
  [state]
  (let [{:keys [grid-id status] :as proposal} (get-in state [:vol-range :repair])
        grid (when grid-id (get-in state [:vol-range :execution :grids grid-id]))]
    (when grid
      (let [scope (:scope grid)
            base (:symbol scope)
            market (market-label scope)
            ctx (scope-context grid)
            ready? (= :ready status)
            in-scope? (= (:scope-key grid) (get-in state [:vol-range-dock :current-scope-key]))
            selection (when ready? (repair-plan/proposal-status proposal))
            n (count (when ready? (repair-plan/selected-legs proposal)))
            sending? (busy? state [:repair grid-id])
            loading? (or (= :loading status) (busy? state [:repair-preview grid-id]))
            live (:live (lifecycle/counts grid))
            last-result (last-result-for state proposal)
            errors (vec (distinct
                         (concat
                          (when (= :error status)
                            (if (or (seq (:errors proposal)) (not (string? (:error proposal))))
                              (result-texts {:errors (:errors proposal)} ctx)
                              [(str "Something went wrong. " (:error proposal))]))
                          (when (and ready? (not in-scope?)) [(scope-hint market)])
                          (when ready? (error-texts (:errors selection) ctx))
                          (when last-result (result-texts last-result ctx)))))]
        {:grid-id grid-id
         :status (if (contains? #{:loading :ready :error} status) status :loading)
         :title "Review rejected orders"
         :identity-line (str/join " · " (remove str/blank? [market (display/short-address
                                                                    (some-> (:account-address scope) str))
                                                            "Post-only"]))
         :intro (str "Only the selected orders are sent. The " (plural live "active order") " in this ladder "
                     (if (= 1 live) "is" "are") " not touched.")
         :loading-text (when (= :loading status) "Reading the current book…")
         :base-symbol base
         :legs (if ready? (leg-rows proposal base) [])
         :unrepairable (if ready?
                         (mapv (fn [u] {:logical-id (:logical-id u) :text (unrepairable-text u)})
                               (:unrepairable proposal))
                         [])
         :bounds (if ready? (bound-rows proposal base) [])
         :limits-warning (when (and ready? (some #{:repair/limits-unrecorded} (:warnings selection)))
                           "This ladder's inventory limits weren't recorded when it was placed. Only balance checks apply.")
         :errors errors
         :as-of (when ready? (some->> (display/utc-clock (:proposed-at-ms proposal)) (str "Prices as of ")))
         :refresh {:enabled? (boolean (and (not loading?) (not sending?))) :busy? (boolean loading?)}
         :submit {:label (if sending? "Placing…" (str "Place " (plural n "repair order")))
                  :enabled? (boolean (and ready? in-scope? (pos? n) (not sending?)
                                          (get-in state [:vol-range :execution :enabled?])
                                          (= :ready (:status selection))))
                  :busy? (boolean sending?)}}))))
