(ns hyperopen.outcomes.domain.events
  "Group standalone outcome contracts into the events a person bets on.

   The feed lists every strike of a price ladder — Bitcoin reaches $65k, $70k,
   $85k, $90k by Nov 30 — as an unrelated contract. Contracts that share a
   template, an underlying (or company) and a time become one market with
   `:outcome-kind :ladder` whose rungs are `:question-options`, the same shape a
   question has, so selection, live price patching and trading reuse the
   question machinery. Singles stay as they are."
  (:require [hyperopen.outcomes.domain.display :as display]))

(defn- ladder-key
  [entry]
  (get-in entry [:outcome-ladder :key]))

(defn- rung-label
  [entry multi-venue?]
  (let [label (get-in entry [:outcome-ladder :rung-label])
        venue (or (:outcome-venue entry) "Hyperliquid")]
    (if multi-venue?
      (str label " · " venue)
      label)))

(defn- rung-option
  [multi-venue? entry]
  (let [{:keys [threshold kind]} (:outcome-ladder entry)
        outcome-id (:outcome-id entry)
        label (rung-label entry multi-venue?)
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

(def ^:private ladder-note
  "Each rung is its own Yes/No question, so the chances do not add up.")

(defn ladder-market
  "One market for two or more standalone contracts that share a ladder key."
  [members]
  (let [ordered (vec (sort-by (fn [entry]
                                [(- (number-or-zero (get-in entry [:outcome-ladder :threshold])))
                                 (:outcome-id entry)])
                              members))
        lead (first ordered)
        venues (vec (distinct (map #(or (:outcome-venue %) "Hyperliquid") ordered)))
        multi-venue? (> (count venues) 1)
        options (mapv (partial rung-option multi-venue?) ordered)
        selected (or (first (sort-by (fn [option]
                                       [(- (number-or-zero (:volume24h option)))
                                        (:outcome-id option)])
                                     options))
                     (first options))
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
             :volume24h (reduce + 0 (keep :volume24h options))
             :change24h (:change24h selected-entry)
             :change24hPct (:change24hPct selected-entry)
             :prevDayRaw (:prevDayRaw selected-entry)
             :openInterest (reduce + 0 (filter number? (map :openInterest options)))
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
