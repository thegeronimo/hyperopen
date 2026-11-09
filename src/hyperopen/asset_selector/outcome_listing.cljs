(ns hyperopen.asset-selector.outcome-listing
  "Model of the market selector's Outcome tab: one row per event, grouped into
   sections by when and what it is, each row carrying its clickable answers.
   Pure — the views render it and the subscription runtime reads the same order."
  (:require [clojure.string :as str]
            [hyperopen.outcomes.domain.clock :as clock]
            [hyperopen.outcomes.domain.quotes :as quotes]))

(def category-chips
  [{:tab :outcome :label "All"}
   {:tab :outcome-sports :label "Sports" :category :sports}
   {:tab :outcome-crypto :label "Crypto" :category :crypto}
   {:tab :outcome-stocks :label "Stocks & commodities" :category :stocks}
   {:tab :outcome-economics :label "Economics" :category :economics}
   {:tab :outcome-companies :label "Companies" :category :companies}])

(def ^:private known-categories
  #{:sports :crypto :stocks :economics :companies})

(def ^:private category-titles
  {:crypto "Crypto"
   :stocks "Stocks & commodities"
   :economics "Economics"
   :companies "Companies"
   :other "Other markets"})

(def ^:private category-order
  [:crypto :stocks :economics :companies :other])

(defn category
  "The chip an outcome market belongs to; anything unrecognized is :other."
  [market]
  (let [category* (:outcome-category market)]
    (if (contains? known-categories category*) category* :other)))

(defn event-volume
  "24h volume of the whole event: both sides of every contract. The two sides
   of a contract trade as one mirrored book, so their notionals add up to the
   shares traded × $1."
  [market]
  (let [contracts (if (seq (:question-options market))
                    (map :sides (:question-options market))
                    [(:outcome-sides market)])
        total (reduce + 0 (keep (fn [side]
                                  (let [v (:volume24h side)]
                                    (when (number? v) v)))
                                (apply concat contracts)))]
    (if (pos? total) total (:volume24h market))))

(defn- tradeable?
  [quote]
  (contains? #{:live :last} (:state quote)))

(defn- answer
  [label coin quote side-index]
  {:label label
   :coin coin
   :side-index side-index
   :quote quote
   :text (quotes/quote-text quote)})

(defn- option-quote
  [option]
  (quotes/side-quote (first (:sides option))))

(defn- chance-rank
  [quote]
  (if (tradeable? quote) (:chance quote) -1))

(defn- two-sided-answers
  "Both sides of one contract. Side 1 mirrors side 0 when side 0 has a price,
   so the two chances always add up to 100%."
  [market]
  (let [[side-a side-b] (:outcome-sides market)
        quote-a (quotes/side-quote side-a)
        quote-b (if (tradeable? quote-a)
                  (quotes/mirror-quote quote-a)
                  (quotes/side-quote side-b))]
    [(answer (or (:side-name side-a) "Yes") (:coin side-a) quote-a 0)
     (answer (or (:side-name side-b) "No") (:coin side-b) quote-b 1)]))

(def ^:private shown-answer-limit 3)

(defn- ladder-shown-options
  "The three rungs nearest an even chance, in strike order."
  [options]
  (let [indexed (map-indexed vector options)
        nearest (->> indexed
                     (sort-by (fn [[idx option]]
                                (let [quote (option-quote option)]
                                  [(if (tradeable? quote)
                                     (js/Math.abs (- 0.5 (:chance quote)))
                                     2)
                                   idx])))
                     (take shown-answer-limit)
                     (sort-by first))]
    (mapv second nearest)))

(defn- shown-options
  [market]
  (let [options (vec (:question-options market))]
    (cond
      (= :ladder (:outcome-shape market)) (ladder-shown-options options)
      (and (= :sports-contest-result (:outcome-template market))
           (<= (count options) shown-answer-limit)) options
      :else (->> options
                 (sort-by (fn [option] (- (chance-rank (option-quote option)))))
                 (take shown-answer-limit)
                 vec))))

(defn answers
  "{:answers [...] :more n} — the answers a row shows and how many it hides."
  [market]
  (if (seq (:question-options market))
    (let [options (shown-options market)]
      {:answers (mapv (fn [option]
                        (answer (:label option) (:yes-coin option) (option-quote option) 0))
                      options)
       :more (- (count (:question-options market)) (count options))})
    {:answers (two-sided-answers market)
     :more 0}))

(defn- day-key
  "Local calendar day as YYYY-MM-DD (en-CA formats ISO dates)."
  [ms {:keys [time-zone]}]
  (.format (js/Intl.DateTimeFormat. "en-CA" (clj->js (cond-> {:year "numeric" :month "2-digit" :day "2-digit"}
                                                       time-zone (assoc :timeZone time-zone))))
           (js/Date. ms)))

(defn- day-number
  [day]
  (let [[y m d] (map #(js/parseInt % 10) (str/split day #"-"))]
    (/ (js/Date.UTC y (dec m) d) 86400000)))

(defn day-label
  "Today, Tomorrow, Earlier, or a short weekday date (Thu, Nov 12)."
  [ms now-ms opts]
  (let [diff (- (day-number (day-key ms opts)) (day-number (day-key now-ms opts)))]
    (cond
      (neg? diff) "Earlier"
      (zero? diff) "Today"
      (= 1 diff) "Tomorrow"
      :else (.format (js/Intl.DateTimeFormat. "en-US"
                                              (clj->js (cond-> {:weekday "short" :month "short" :day "numeric"}
                                                         (:time-zone opts) (assoc :timeZone (:time-zone opts)))))
                     (js/Date. ms)))))

(defn- game?
  [market]
  (and (= :sports (category market))
       (= :starts (get-in market [:outcome-clock :kind]))))

(defn- section-of
  [market now-ms opts]
  (let [at-ms (get-in market [:outcome-clock :at-ms])
        competition (or (:outcome-competition market) "Sports")]
    (cond
      (game? market)
      (let [day (day-key at-ms opts)
            label (day-label at-ms now-ms opts)]
        ;; Games still to play come first; past games awaiting a result go last.
        {:id (str "games|" day "|" competition)
         :title (str label " · " competition)
         :rank [0 (if (= "Earlier" label) 1 0) day competition]})

      (= :sports (category market))
      {:id "futures" :title "Futures" :rank [1 0 "" ""]}

      :else
      (let [category* (category market)]
        {:id (name category*)
         :title (get category-titles category*)
         :rank [2 (.indexOf (clj->js category-order) (name category*)) "" ""]}))))

(defn- row-sort-key
  [market]
  [(or (get-in market [:outcome-clock :at-ms]) js/Number.MAX_SAFE_INTEGER)
   (- (or (:volume24h market) 0))
   (:key market)])

(defn- meta-label
  [market]
  (or (:outcome-competition market)
      (get category-titles (category market))
      "Sports"))

(defn row
  [market now-ms opts]
  (let [{:keys [answers more]} (answers market)]
    {:key (:key market)
     :title (or (:title market) (:symbol market))
     :shape (:outcome-shape market)
     :meta (meta-label market)
     :venue (or (:outcome-venue market)
                (when (seq (:outcome-venues market))
                  (str/join " · " (:outcome-venues market)))
                "Hyperliquid")
     :clock (clock/clock-view (:outcome-clock market) now-ms opts)
     :answers answers
     :more more
     :volume (event-volume market)}))

(defn sections
  "Rows grouped into sections: today's and upcoming games by day and
   competition, then futures, then each other category."
  ([markets now-ms] (sections markets now-ms nil))
  ([markets now-ms opts]
   (->> markets
        (group-by #(section-of % now-ms opts))
        (sort-by (comp :rank key))
        (mapv (fn [[{:keys [id title]} members]]
                {:id id
                 :title title
                 :rows (->> members
                            (sort-by (if (= "futures" id)
                                       (fn [market] [(- (or (:volume24h market) 0)) (:key market)])
                                       row-sort-key))
                            (mapv #(row % now-ms opts)))})))))

(defn ordered-keys
  [sections]
  (into [] (comp (mapcat :rows) (map :key)) sections))

(defn chip-counts
  "Event count per chip over the markets a search already narrowed."
  [markets]
  (let [by-category (frequencies (map category markets))]
    (into {:outcome (count markets)}
          (keep (fn [{:keys [tab category]}]
                  (when category [tab (get by-category category 0)])))
          category-chips)))

(def ^:private subscribed-event-limit 30)

(defn visible-coins
  "Side coins whose live contexts keep the first rows' answers current."
  [sections]
  (->> sections
       (mapcat :rows)
       (take subscribed-event-limit)
       (mapcat :answers)
       ;; Side 1 mirrors side 0, so side-0 contexts are enough.
       (filter #(zero? (:side-index %)))
       (keep :coin)
       set))
