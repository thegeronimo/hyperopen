(ns hyperopen.portfolio.optimizer.application.view-model.setup-history-assumption-queue
  "Queue projection over the history-assumption cards (designer restructure
  2026-08-14, option 1c): a fixed asset rail that never scrolls, ONE active
  question, the recommended model leading, and every diagnostic folded behind a
  single detail block.

  Pure projection. It re-reads the card model plus the queue's active-asset UI
  state and adds only what the queue asks for that a card does not already
  carry: settled counts, the one-line model summary, the folded detail rows,
  and the prev/next neighbours. Views render these rows and dispatch the
  carried action ids - no history math here and none there."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model.setup-history-assumption-cards :as assumption-cards]
            [hyperopen.portfolio.optimizer.application.view-model.setup-history-assumption-exposure :as exposure]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]))

(def queue-action-ids
  {:set-active :actions/set-portfolio-optimizer-history-assumption-active})

(def detail-footnote
  "Confidence decides how far the regression can move the prior. Specific risk and the cap keep the optimizer honest.")

(defn- settled?
  "The queue owes this asset nothing more: it is engine-complete AND the user
  (or an agent file, or a one-click recommendation apply) has accepted it.
  Engine-complete alone is not settled — an entry the user edited since drops
  its acknowledgment and still owes an Accept, which is exactly the turn the
  queue exists to hand back."
  [card]
  (boolean (:configured? card)))

;; --- Labels --------------------------------------------------------------------

(defn- native-days-label
  "Terse rail-pill history count (\"34d\"). An UNKNOWN count (history not
  fetched, or still in flight) is not zero — printing \"0d\" for it states a
  fact nobody has established, so the pill withholds instead."
  [card]
  (let [observations (:observations card)]
    (if (number? observations)
      (str observations "d")
      "--")))

(defn- native-history-line
  [card]
  (let [observations (:observations card)]
    (cond
      (and (number? observations) (pos? observations))
      (str observations " days of native history - too short to model on its own.")

      (:history-loading? card)
      "Native history is still loading - this line updates itself."

      :else
      "No usable native history - it cannot be modeled on its own.")))

(defn- question
  [card]
  (if (settled? card)
    (str (:label card) " is modeled - change it?")
    (str "What does " (:label card) " behave like?")))

(defn- similarity-clause
  [relationship-label]
  (when relationship-label
    (str (str/lower-case relationship-label) " similarity")))

(defn- join-clauses
  [clauses]
  (str/join " · " (keep identity clauses)))

;; --- Model summary line --------------------------------------------------------

(defn- seed-guardrails-summary
  "The guardrails a one-click apply would author - the SAME behavior seeds
  `history-assumptions/default-assumption` writes, so the recommended panel can
  never promise numbers the apply would not produce."
  [approach percent-label*]
  (str (percent-label* history-assumptions/default-conservative-volatility) " vol · "
       (percent-label* (history-assumptions/default-max-weight approach)) " max"))

(defn- recommended-split-text
  "\"UNI 50% / PUMP-USDC 50%\" over the members an apply would actually use.
  Weights normalize across the USABLE members only, mirroring the apply path's
  own all-or-nothing prior rule; a basket missing any weight falls back to bare
  labels rather than inventing a split."
  [members]
  (let [usable (filterv :available? members)
        weights (mapv :weight usable)
        total (reduce + 0 (filter number? weights))]
    (cond
      (empty? usable) nil

      (and (every? #(and (number? %) (not (neg? %))) weights) (pos? total))
      (str/join " / " (map (fn [{:keys [label weight]}]
                             (str label " " (js/Math.round (* 100 (/ weight total))) "%"))
                           usable))

      :else (str/join " / " (map :label usable)))))

(defn- recommended-model
  "The lead panel while the asset carries no authored entry: what the backend
  suggests, why, and the single click that authors it. Nil once the user (or an
  agent file) has authored an entry - an authored assumption always wins."
  [card percent-label*]
  (when-let [rec (:recommendation card)]
    (let [proxy? (= :proxy (:approach rec))
          split (recommended-split-text (:members rec))]
      {:kind :recommended
       :eyebrow "Recommended model"
       :line (join-clauses [(if proxy? split (:approach-label rec))
                            (when proxy? (similarity-clause (:relationship-label rec)))
                            (seed-guardrails-summary (:approach rec) percent-label*)])
       :why (:rationale rec)
       :held-count (or (:held-count rec) 0)
       :cta "Use this"
       :cta-action (when (:applicable? rec)
                     [(get-in rec [:actions :apply-one]) (:instrument-id card)])
       :cta-role (str "portfolio-optimizer-history-assumption-recommendation-apply-"
                      (:instrument-id card))
       :cta-disabled? (not (:applicable? rec))
       :unavailable-note
       (when-not (:applicable? rec)
         "The recommended members are still waiting on backend history - adjust by hand, or check back later.")})))

(defn- current-model
  "The lead panel once an entry exists: the basket the engine would actually
  use (never the prior dressed up as the model), its similarity, and its
  guardrails. Its CTA is the existing acknowledge - held while proxy history is
  still fetching, exactly like the card's Apply."
  [card]
  (let [proxy? (= :proxy (:mode card))
        final-rows (get-in card [:final-basket :rows])
        id (:instrument-id card)]
    {:kind :current
     :eyebrow (if (settled? card) "Current model" "Model so far")
     :line (join-clauses
            [(cond
               (seq final-rows) (exposure/basket-summary-text final-rows)
               proxy? "No basket picked yet"
               :else "Worst case · no diversification credit")
             (when proxy? (similarity-clause (:relationship-label card)))
             (get-in card [:risk-guardrails :summary])])
     :held-count 0
     ;; The agent/backend rationale already has its own attributed box above the
     ;; panel; repeating it verbatim here reads as two sources agreeing.
     :why nil
     :cta (if (settled? card) "Keep this" "Accept assumption")
     :cta-action [(get-in card [:actions :apply]) id]
     :cta-role (str "portfolio-optimizer-history-assumption-apply-" id)
     :cta-disabled? (boolean (or (not (:engine-applied? card))
                                 (:history-loading? card)))
     :unavailable-note nil}))

;; --- The one folded detail block ------------------------------------------------

(defn- diagnostics-value
  [card cell-key]
  (some #(when (= cell-key (:key %)) (:value %)) (:diagnostics card)))

(defn- window-value
  [card]
  (let [covariance (:covariance-observations card)
        native (:observations card)]
    (cond
      (and (:history-loading? card)
           (not (and covariance (pos? covariance))))
      "Loading history..."

      (and covariance (pos? covariance))
      (str covariance " days via proxies · "
           (if (and native (pos? native))
             (str native "d native overlap")
             "no native overlap"))

      :else (exposure/observations-label native))))

(defn- proxy-detail-rows
  [card]
  (let [regression (:regression-estimate card)
        final (:final-basket card)]
    [(when (seq (:prior-basket card))
       ["Prior basket" (str (or (:prior-source-label card) "Prior") " - "
                            (exposure/basket-summary-text (:prior-basket card) " · "))])
     (when regression
       ["Regression" (if (= :estimated (:status regression))
                       (str (exposure/basket-summary-text (:rows regression) " · ")
                            " over " (:summary regression))
                       (:message regression))])
     (when (seq (:rows final))
       ["Final basket" (str (exposure/basket-summary-text (:rows final) " · ")
                            (when (:confidence-label final)
                              (str " after confidence shrinkage (q "
                                   (:confidence-label final) ")")))])
     ["Confidence" (str (or (diagnostics-value card :regression-confidence) "Low")
                        " - R² sets confidence, not weights")]
     ["Specific risk" (str (or (diagnostics-value card :specific-risk) "--")
                           " - risk the basket cannot explain")]
     ["Window" (window-value card)]]))

(defn- conservative-detail-rows
  [card]
  [["Approach" "No diversification credit - treated as its own risk"]
   ["Volatility" (str (or (get-in card [:volatility :percent-label]) "--")
                      " annual, pre-filled and editable")]
   ["Cap" (str (or (get-in card [:max-weight :percent-label]) "--")
               " of the portfolio at most")]
   ["Confidence" "Not applicable - no basket fitted"]
   ["Specific risk" "All of it, by construction"]
   ["Window" (str (exposure/observations-label (:observations card))
                  ", unused for covariance")]])

(defn- recommended-detail-rows
  "Detail for a card that has no entry yet: what the recommendation would
  author, what it holds back, and the history it starts from. There is no
  exposure preview to report - the pipeline only runs once an entry exists."
  [card percent-label*]
  (let [rec (:recommendation card)
        held (:held-count rec 0)]
    [["Proposed basket" (or (recommended-split-text (:members rec))
                            (:approach-label rec))]
     ["Similarity" (or (:relationship-label rec) "--")]
     ["Guardrails" (seed-guardrails-summary (:approach rec) percent-label*)]
     (when (pos? held)
       ["Held back" (str held (if (= 1 held) " member" " members")
                        " the backend cannot serve history for yet")])
     ["Confidence" "Estimated once the basket has an overlap to fit"]
     ["Window" (window-value card)]]))

(defn- detail-rows
  [card percent-label*]
  (->> (cond
         (= :proxy (:mode card)) (proxy-detail-rows card)
         (= :conservative (:mode card)) (conservative-detail-rows card)
         (:recommendation card) (recommended-detail-rows card percent-label*)
         :else [["Window" (window-value card)]])
       (keep identity)
       (mapv (fn [[label value]] {:key label :label label :value value}))))

;; --- Queue assembly ---------------------------------------------------------------

(defn- tone
  "Rail-dot tone. Three states, in the order the queue cares about them: this
  asset is done, a one-click model is waiting for it, or it is still unanswered."
  [card]
  (cond
    (settled? card) "settled"
    (get-in card [:recommendation :applicable?]) "suggested"
    :else "unset"))

(defn- queue-status-label
  "The chip beside the question, in the queue's own vocabulary. The card's own
  label answers \"is the engine backed?\" — beside a question that asks the user
  to decide something, \"Configured\" reads as a contradiction, so the queue says
  where the DECISION stands instead. `:status` rides on untouched for the pinned
  data-status contract."
  [card]
  (cond
    (settled? card) "Accepted"
    ;; Engine-backed but the user never said yes (or edited since): the only
    ;; thing left is the acceptance.
    (:engine-applied? card) "Ready to accept"
    (get-in card [:recommendation :applicable?]) "Suggested"
    :else (:status-label card)))

(defn- pill-row
  [active-id card]
  {:instrument-id (:instrument-id card)
   :label (:label card)
   :native-label (native-days-label card)
   :status (:status card)
   :status-label (queue-status-label card)
   :tone (tone card)
   :settled? (settled? card)
   :loading? (boolean (:history-loading? card))
   :active? (= (:instrument-id card) active-id)
   :action [(:set-active queue-action-ids) (:instrument-id card)]})

(defn- neighbour
  [card]
  (when card
    {:instrument-id (:instrument-id card)
     :label (:label card)
     :action [(:set-active queue-action-ids) (:instrument-id card)]}))

(defn- advance-model
  "The footer's primary: settle the active asset and move on. It carries the
  lead panel's own CTA action so the two can never disagree about what
  accepting means, plus the hop to the next asset when there is one."
  [model next-card total]
  (let [action (:cta-action model)]
    {:label (if (> total 1) "Accept & next" "Accept")
     :disabled? (boolean (or (nil? action) (:cta-disabled? model)))
     :actions (cond-> []
                action (conj action)
                (and action next-card)
                (conj [(:set-active queue-action-ids) (:instrument-id next-card)]))}))

(defn- universe-ordered
  "Cards back in universe order. The card list arrives attention-sorted
  (unsettled floated above settled) because a STACK of cards had to put the
  work on top - but the queue moves a pointer instead of the reader, and a rail
  that reshuffles itself every time you accept something destroys the only
  thing it is for: a stable map of what is left. Same universe resolution the
  card projection itself uses."
  [draft readiness cards]
  (let [universe (or (get-in readiness [:request :requested-universe])
                     (:universe draft)
                     [])
        position (into {}
                       (map-indexed (fn [index instrument]
                                      [(:instrument-id instrument) index]))
                       universe)]
    (vec (sort-by #(get position (:instrument-id %) (count position)) cards))))

(defn history-assumption-queue-model
  "Queue view of the history-assumption workflow. `:pills` is the fixed rail
  (every asset, always, in universe order), `:active` is the one card the queue
  is asking about (the requested asset, else the first unsettled one), and
  `:prev` / `:next` / `:advance` drive the footer. Aggregates the section header
  needs (`:settled-label`, `:left-label`) come from the same card list the rail
  renders, so the counter can never disagree with the pills."
  ([state draft readiness history-load-state]
   (history-assumption-queue-model state draft readiness history-load-state nil))
  ([state draft readiness history-load-state {:keys [percent-label] :as opts}]
   (let [{:keys [addable-assets applicable? history-loading-count
                 recommended-count recommended-actions]
          raw-cards :cards}
         (assumption-cards/history-assumption-cards state draft readiness
                                                    history-load-state opts)
         cards (universe-ordered draft readiness raw-cards)
         percent-label* #(assumption-cards/apply-percent-label percent-label %)
         total (count cards)
         settled-count (count (filter settled? cards))
         left-count (- total settled-count)
         requested (get-in state contracts/ui-history-assumption-active-path)
         by-id (into {} (map (juxt :instrument-id identity)) cards)
         ;; A stale requested id (asset dropped from the universe, or its
         ;; history arrived and retired the card) silently falls back rather
         ;; than stranding the queue on nothing.
         active (or (get by-id requested)
                    (first (remove settled? cards))
                    (first cards))
         index (first (keep-indexed (fn [i card]
                                      (when (= (:instrument-id card)
                                               (:instrument-id active))
                                        i))
                                    cards))
         prev-card (when (and index (> total 1)) (nth cards (mod (dec index) total)))
         next-card (when (and index (> total 1)) (nth cards (mod (inc index) total)))
         model (when active
                 (or (recommended-model active percent-label*)
                     (current-model active)))]
     {:applicable? (boolean applicable?)
      :addable-assets addable-assets
      :card-count total
      :settled-count settled-count
      :left-count left-count
      :settled-label (str settled-count " of " total " settled")
      :left-label (if (zero? left-count) "nothing left" (str left-count " left"))
      :history-loading-count (or history-loading-count 0)
      :recommended-count (or recommended-count 0)
      :recommended-actions recommended-actions
      :pills (mapv #(pill-row (:instrument-id active) %) cards)
      :active (when active
                (assoc active
                       :question (question active)
                       :native-line (native-history-line active)
                       :settled? (settled? active)
                       :tone (tone active)
                       :queue-status-label (queue-status-label active)
                       :model model
                       :detail-rows (detail-rows active percent-label*)
                       :detail-footnote detail-footnote))
      :prev (neighbour prev-card)
      :next (neighbour next-card)
      :advance (when model (advance-model model next-card total))})))
