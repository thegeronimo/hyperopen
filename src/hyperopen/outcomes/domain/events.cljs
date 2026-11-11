(ns hyperopen.outcomes.domain.events
  "Group standalone outcome contracts into the events a person bets on.

   The feed lists every strike of a price ladder — Bitcoin reaches $65k, $70k,
   $85k, $90k by Nov 30 — as an unrelated contract. Contracts that share a
   template, an underlying (or company) and a time become one market with
   `:outcome-kind :ladder` whose rungs are `:question-options`, the same shape a
   question has, so selection, live price patching and trading reuse the
   question machinery. Singles stay as they are.

   A ladder is one venue's rule set: the key (see templates) carries venue,
   price source, window and deadline, so rungs that settle differently are
   never merged and never compared."
  (:require [hyperopen.outcomes.domain.display :as display]
            [hyperopen.outcomes.domain.quotes :as quotes]))

(defn- ladder-key
  [entry]
  (get-in entry [:outcome-ladder :key]))

(defn- rung-label
  "A rung's label. Rungs of one ladder share one venue (the ladder key
   includes it, `templates/ladder-key`), so the venue never needs saying."
  [entry]
  (get-in entry [:outcome-ladder :rung-label]))

;; ---------------------------------------------------------------------------
;; Direction and order

(defn rung-direction
  "Which way a rung asks the price to go. \"Above\" and \"at least\" ladders
   always rise. A touch (\"reaches\") ladder has no direction field: a target
   above the live price is a rise (:up), below it a fall (:down); without a
   live price the direction is unknown (nil)."
  [kind threshold spot]
  (case kind
    (:above :at-least) :up
    :touch (when (and (number? threshold) (number? spot) (pos? spot))
             (if (> threshold spot) :up :down))
    nil))

(defn direction-label
  "\"Rises to $80\" / \"Falls to $70\" for touch rungs; the bare level otherwise."
  [kind direction level]
  (if (= :touch kind)
    (case direction
      :up (str "Rises to " level)
      :down (str "Falls to " level)
      level)
    level))

(def order-tolerance
  "A harder rung may be priced above an easier one by this much (half a
   point) before it is flagged out of order."
  0.005)

(defn- easier?
  "True when rung `a` is easier to reach than rung `b` in `direction`: a lower
   target when rising, a higher one when falling."
  [direction a b]
  (case direction
    :up (< (:threshold a) (:threshold b))
    :down (> (:threshold a) (:threshold b))
    false))

(defn rung-order-flags
  "Out-of-order rungs. `rows` are [{:outcome-id :threshold :chance :label
   :venue :ladder-key}] (chance nil when there is no midpoint); `kind` the
   ladder kind; `spot` the live underlying price (touch ladders need it).
   Each rung is compared with EVERY easier rung of the same direction, venue
   and rule set — not only its neighbour — and flagged when it is priced above
   the least likely of them by more than half a point. The two directions of
   a touch ladder are never compared.

   Returns {outcome-id {:than-outcome-id :than-label :than-chance :text}}."
  [rows kind spot]
  (let [rows* (keep (fn [row]
                      (let [direction (rung-direction kind (:threshold row) spot)]
                        (when (and direction
                                   (number? (:threshold row))
                                   (number? (:chance row)))
                          (assoc row :direction direction))))
                    rows)]
    (into {}
          (mapcat (fn [[[direction] group]]
                    (keep (fn [row]
                            (when-let [floor (->> group
                                                  (filter #(easier? direction % row))
                                                  (sort-by :chance)
                                                  first)]
                              (when (> (:chance row) (+ (:chance floor) order-tolerance))
                                [(:outcome-id row)
                                 {:than-outcome-id (:outcome-id floor)
                                  :than-label (:label floor)
                                  :than-chance (:chance floor)
                                  :text "Out of order"}])))
                          group)))
          (group-by (juxt :direction :venue :ladder-key) rows*))))

(defn option-chance-row
  "The row `rung-order-flags` reads for one ladder option, priced by
   `quote-fn` (side-0 quote of the option; defaults to the context quote)."
  ([option] (option-chance-row option nil))
  ([option quote-fn]
   (let [quote ((or quote-fn #(quotes/side-quote (first (:sides %)))) option)]
     {:outcome-id (:outcome-id option)
      :threshold (:threshold option)
      :label (:label option)
      :venue (:venue option)
      :ladder-key (:ladder-key option)
      :chance (when (= :live (:state quote)) (:chance quote))})))

(defn ladder-order-flags
  "`rung-order-flags` for a ladder market's options. Options: `:spot` (live
   underlying price) and `:quote-fn` (option -> its side-0 quote)."
  [market {:keys [spot quote-fn]}]
  (if (= :ladder (:outcome-shape market))
    (rung-order-flags (map #(option-chance-row % quote-fn) (:question-options market))
                      (:outcome-ladder-kind market)
                      spot)
    {}))

(defn- rung-option
  [entry]
  (let [{:keys [threshold kind]} (:outcome-ladder entry)
        outcome-id (:outcome-id entry)
        label (rung-label entry)
        sides (mapv #(assoc %
                            :outcome-id outcome-id
                            :outcome-option-label label
                            :side-label (:side-name %))
                    (:outcome-sides entry))
        [yes no] sides]
    (cond-> {:label label
             :outcome-id outcome-id
             :threshold threshold
             :rung-kind kind
             :ladder-key (ladder-key entry)
             :yes-coin (:coin yes)
             :yes-asset-id (:asset-id yes)
             :no-coin (:coin no)
             :no-asset-id (:asset-id no)
             :mark (:mark yes)
             :volume24h (:volume24h yes)
             :openInterest (:openInterest entry)
             :sides sides}
      (:outcome-venue entry) (assoc :venue (:outcome-venue entry))
      (:outcome-clock entry) (assoc :clock (:outcome-clock entry))
      (:outcome-rules entry) (assoc :rules (:outcome-rules entry)))))

(defn- ladder-aliases
  [market-key options]
  (reduce (fn [aliases {:keys [outcome-id sides label]}]
            (let [sibling-coins (mapv :coin sides)
                  entry {:outcome-id outcome-id
                         :option-label label
                         :sibling-coins sibling-coins}]
              (-> (reduce (fn [aliases* side]
                            (assoc aliases* (:coin side) (assoc entry :side-index (:side-index side))))
                          aliases
                          sides)
                  (assoc (str "outcome:" outcome-id) (assoc entry :side-index 0))
                  (assoc market-key {:question? true}))))
          {}
          options))

(defn- number-or-zero
  [value]
  (if (number? value) value 0))

(defn- earliest-clock
  [entries]
  (->> entries
       (keep :outcome-clock)
       (sort-by :at-ms)
       first))

(def ladder-note
  "Each rung is its own Yes/No question, so the chances do not add up.")

(defn selected-option
  "The option (answer or rung) a market has selected, nil for single
   contracts."
  [market]
  (let [options (:question-options market)]
    (some (fn [id]
            (when (some? id)
              (some #(when (= id (:outcome-id %)) %) options)))
          [(:outcome-option-id market) (:selected-outcome-id market)])))

(defn selected-rung-facts
  "Clock, rules and venue of the selected rung of a ladder — the rung drives
   them, not the ladder's first member. Falls back to the market's own."
  [market]
  (let [option (selected-option market)
        ladder? (= :ladder (:outcome-shape market))
        rung-rules (when ladder? (:rules option))]
    {:clock (or (when ladder? (:clock option)) (:outcome-clock market))
     :rules (if rung-rules
              (str ladder-note " " rung-rules)
              (or (:outcome-rules market) (:outcome-details market)))
     :venue (or (when ladder? (:venue option))
                (:outcome-venue market))}))

(defn ladder-market
  "One market for two or more standalone contracts that share a ladder key."
  [members]
  (let [ordered (vec (sort-by (fn [entry]
                                [(- (number-or-zero (get-in entry [:outcome-ladder :threshold])))
                                 (:outcome-id entry)])
                              members))
        lead (first ordered)
        venues (vec (distinct (map #(or (:outcome-venue %) "Hyperliquid") ordered)))
        options (mapv rung-option ordered)
        selected (or (first (sort-by (fn [option]
                                       [(- (or (display/contract-shares (:sides option)) 0))
                                        (:outcome-id option)])
                                     options))
                     (first options))
        totals (display/event-totals {:question-options options})
        selected-entry (some #(when (= (:outcome-id selected) (:outcome-id %)) %) ordered)
        market-key (str "ladder:" (ladder-key lead))
        title (get-in lead [:outcome-ladder :title])
        rules (str ladder-note " " (:rules selected))]
    (cond-> {:key market-key
             :coin (:yes-coin selected)
             :symbol title
             :title title
             :base (:base lead)
             :quote (:quote lead)
             :market-type :outcome
             :category :outcome
             :hip3? false
             :hip3-eligible? false
             :outcome-kind :ladder
             :outcome-template (:outcome-template lead)
             :outcome-shape :ladder
             :outcome-category (:outcome-category lead)
             :outcome-ladder-kind (get-in lead [:outcome-ladder :kind])
             :outcome-venues venues
             :outcome-rules rules
             :question-options options
             :outcome-sides (:sides selected)
             :outcome-side-aliases (ladder-aliases market-key options)
             :outcome-subscription-coins (->> options (mapcat :sides) (keep :coin) distinct vec)
             :selected-outcome-id (:outcome-id selected)
             :selected-outcome-option-label (:label selected)
             :outcome-option-id (:outcome-id selected)
             :asset-id (:yes-asset-id selected)
             :szDecimals 0
             :mark (:mark selected)
             :markRaw (:markRaw selected-entry)
             :volume24h (or (:shares24h totals) 0)
             :change24h (:change24h selected-entry)
             :change24hPct (:change24hPct selected-entry)
             :prevDayRaw (:prevDayRaw selected-entry)
             :openInterest (:open-interest totals)
             :fundingRate nil
             :maxLeverage nil
             :outcome-summary (display/option-summary options)
             :outcome-details rules
             :search-keywords (vec (distinct (mapcat :search-keywords ordered)))}
      (:outcome-subcategory lead) (assoc :outcome-subcategory (:outcome-subcategory lead))
      (:outcome-subject lead) (assoc :outcome-subject (:outcome-subject lead))
      (= 1 (count venues)) (assoc :outcome-venue (:outcome-venue lead))
      (earliest-clock ordered) (assoc :outcome-clock (earliest-clock ordered)))))

(defn group-ladders
  "Replace every group of two or more entries sharing a ladder key with one
   ladder market, at the position of the group's first member. Entries without
   a key, and keys with a single member, pass through."
  [entries]
  (let [members-by-key (group-by ladder-key (filter ladder-key entries))]
    (:out (reduce (fn [{:keys [seen] :as acc} entry]
                    (let [k (ladder-key entry)
                          members (get members-by-key k)]
                      (cond
                        (or (nil? k) (< (count members) 2)) (update acc :out conj entry)
                        (contains? seen k) acc
                        :else (-> acc
                                  (update :seen conj k)
                                  (update :out conj (ladder-market members))))))
                  {:seen #{} :out []}
                  entries))))
