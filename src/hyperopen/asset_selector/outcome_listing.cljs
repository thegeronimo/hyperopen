(ns hyperopen.asset-selector.outcome-listing
  "Model of the market selector's Outcome tab: one row per event, ordered by
   the chosen sort, each row carrying its clickable answers, flags and volume.
   Pure — the views render it and the subscription runtime reads the same
   order.

   Every answer is priced by the one chance rule (`book/contract-quotes`), so a
   row says exactly what the header and the ticket say for the same inputs.
   `opts` (all optional): `:time-zone`, `:books` (coin -> order book, for rows
   whose live book is known), `:market-by-key` (the selector catalog, to read
   a ladder underlying's live price), `:sort` (see `sort-options`) and
   `:visible-keys` (event keys the view reports on screen)."
  (:require [clojure.string :as str]
            [hyperopen.outcomes.domain.book :as book]
            [hyperopen.outcomes.domain.clock :as clock]
            [hyperopen.outcomes.domain.display :as display]
            [hyperopen.outcomes.domain.events :as events]
            [hyperopen.outcomes.domain.labels :as labels]
            [hyperopen.outcomes.domain.quotes :as quotes]))

(def category-chips
  [{:tab :outcome :label "All"}
   {:tab :outcome-sports :label "Sports" :category :sports}
   {:tab :outcome-crypto :label "Crypto" :category :crypto}
   {:tab :outcome-stocks :label "Stocks & commodities" :category :stocks}
   {:tab :outcome-economics :label "Economics" :category :economics}
   {:tab :outcome-companies :label "Companies" :category :companies}])

(def sort-options
  "How the list can be ordered; the first is the default."
  [{:id :most-traded :label "Most traded"}
   {:id :ending-soon :label "Ending soon"}
   {:id :start-time :label "By start time"}])

(def default-sort :most-traded)

(def ^:private sort-ids (set (map :id sort-options)))

(defn normalize-sort
  [sort-id]
  (let [sort* (cond
                (keyword? sort-id) sort-id
                (and (string? sort-id) (seq sort-id)) (keyword (str/replace sort-id #"^:" ""))
                :else nil)]
    (if (contains? sort-ids sort*) sort* default-sort)))

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
  "Shares traded today across the whole event, each contract counted once
   (volume is mirrored across a contract's two sides). Nil when unknown."
  [market]
  (:shares24h (display/event-totals market)))

(defn event-open-interest
  "Dollars locked across the whole event, each contract counted once."
  [market]
  (:open-interest (display/event-totals market)))

;; ---------------------------------------------------------------------------
;; Answers

(defn option-quote
  "Side-0 quote of one answer (option or rung) under the one chance rule."
  ([option] (option-quote option nil))
  ([option books]
   (first (book/contract-quotes (:sides option) 0 books))))

(defn- answer
  [label coin quote side-index]
  {:label label
   :coin coin
   :side-index side-index
   :quote quote
   :state (:state quote)
   :muted? (boolean (:muted? quote))
   :text (quotes/quote-text quote)
   :detail (when (contains? #{:not-traded :no-market} (:state quote))
             (quotes/standing-quotes-text quote))})

(defn- two-sided-answers
  "Both sides of one contract; the two quotes are mirrors, so they agree."
  [market books]
  (let [[side-a side-b] (:outcome-sides market)
        [quote-a quote-b] (book/contract-quotes (:outcome-sides market) 0 books)]
    [(answer (or (:side-name side-a) "Yes") (:coin side-a) quote-a 0)
     (answer (or (:side-name side-b) "No") (:coin side-b) quote-b 1)]))

(def ^:private shown-answer-limit 3)

(def ^:private state-tier
  ;; Traded, tightly quoted answers lead; placeholders (never traded) trail.
  {:live 0 :range 1 :no-market 2 :no-quotes 3 :not-traded 4})

(defn- answer-rank
  "Sort key that prefers quoted, traded answers, then the most traded."
  [option quote]
  [(get state-tier (:state quote) 5)
   (- (or (display/contract-shares (:sides option)) 0))])

(defn underlying-price
  "Live price of a ladder's underlying (`perp:<subject>` mark) from the
   catalog in `opts`, nil when unknown."
  [market opts]
  (when-let [subject (:outcome-subject market)]
    (let [mark (get-in opts [:market-by-key (str "perp:" subject) :mark])]
      (when (and (number? mark) (pos? mark)) mark))))

(defn- touch-shown-options
  "A touch ladder leads with the nearest target on each side of the price."
  [indexed spot]
  (let [ups (->> indexed
                 (filter (fn [[_ option]] (and (number? (:threshold option)) (> (:threshold option) spot))))
                 (sort-by (fn [[_ option]] (:threshold option)) <))
        downs (->> indexed
                   (filter (fn [[_ option]] (and (number? (:threshold option)) (<= (:threshold option) spot))))
                   (sort-by (fn [[_ option]] (:threshold option)) >))
        leads (keep first [ups downs])
        rest* (->> (concat (rest ups) (rest downs))
                   (sort-by (fn [[_ option]] (js/Math.abs (- (:threshold option) spot)))))]
    (vec (take 2 (concat leads rest*)))))

(defn- ranked-shown-options
  "The most informative rungs: quoted and traded first, nearest an even
   chance among those, then shown in strike order."
  [indexed books]
  (->> indexed
       (sort-by (fn [[idx option]]
                  (let [quote (option-quote option books)
                        center (case (:state quote)
                                 :live (:chance quote)
                                 :range (/ (+ (:low quote) (:high quote)) 2)
                                 nil)]
                    (into (answer-rank option quote)
                          [(if (number? center) (js/Math.abs (- 0.5 center)) 2) idx]))))
       (take shown-answer-limit)
       (sort-by first)
       vec))

(defn- shown-options
  "[[idx option] …] the row shows."
  [market books spot]
  (let [indexed (vec (map-indexed vector (:question-options market)))]
    (cond
      (= :ladder (:outcome-shape market))
      (if (and (= :touch (:outcome-ladder-kind market)) (number? spot))
        (touch-shown-options indexed spot)
        (ranked-shown-options indexed books))

      (and (= :sports-contest-result (:outcome-template market))
           (<= (count indexed) shown-answer-limit))
      indexed

      :else
      (->> indexed
           (sort-by (fn [[idx option]]
                      (let [quote (option-quote option books)]
                        [(- (if (:rankable? quote) (:chance quote) -1))
                         (answer-rank option quote)
                         idx])))
           (take shown-answer-limit)
           vec))))

(defn- option-answer
  [market spot order-flags option]
  (let [kind (:outcome-ladder-kind market)
        direction (when (= :ladder (:outcome-shape market))
                    (events/rung-direction kind (:threshold option) spot))
        quote (:quote option)]
    (cond-> (answer (events/direction-label kind direction (:label option))
                    (:yes-coin option)
                    quote
                    0)
      (= :touch kind) (assoc :direction direction)
      (contains? order-flags (:outcome-id option)) (assoc :out-of-order? true))))

(defn- sum-flag
  "\"Adds up to 109%\" for answers that exclude each other."
  [market books]
  (when (contains? #{:pick-one :field} (:outcome-shape market))
    (let [options (cond-> (vec (:question-options market))
                    (:fallback-option market) (conj (:fallback-option market)))
          {:keys [over? text]} (quotes/exclusive-sum (map #(option-quote % books) options))]
      (when over? {:kind :sum :text text}))))

(defn answers
  "{:answers [...] :more n :flags [...]} — the answers a row shows, how many
   it hides, and the row's warnings (exclusive sum, ladder order)."
  ([market] (answers market nil))
  ([market opts]
   (let [books (:books opts)]
     (if (seq (:question-options market))
       (let [spot (underlying-price market opts)
             order-flags (events/ladder-order-flags market {:spot spot
                                                            :quote-fn #(option-quote % books)})
             shown (shown-options market books spot)
             flags (cond-> []
                     (sum-flag market books) (conj (sum-flag market books))
                     (seq order-flags) (conj {:kind :order :text "Out of order"}))]
         {:answers (mapv (fn [[_ option]]
                           (option-answer market spot order-flags
                                          (assoc option :quote (option-quote option books))))
                         shown)
          :more (- (count (:question-options market)) (count shown))
          :flags flags})
       {:answers (two-sided-answers market books)
        :more 0
        :flags []}))))

;; ---------------------------------------------------------------------------
;; Sections and order

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
      (let [phase (:phase (clock/clock-view (:outcome-clock market) now-ms opts))
            day (day-key at-ms opts)
            label (day-label at-ms now-ms opts)]
        (cond
          ;; A game in progress leads, even when it started before midnight.
          (= :live phase)
          {:id (str "games|live|" competition)
           :title (str "Live · " competition)
           :rank [0 -1 "" competition]}

          ;; Games past their live window wait for a result at the end.
          (= :awaiting phase)
          {:id (str "games|awaiting|" competition)
           :title (str "Awaiting result · " competition)
           :rank [0 1 "" competition]}

          :else
          {:id (str "games|" day "|" competition)
           :title (str label " · " competition)
           :rank [0 0 day competition]}))

      (= :sports (category market))
      {:id "futures" :title "Futures" :rank [1 0 "" ""]}

      :else
      (let [category* (category market)]
        {:id (name category*)
         :title (get category-titles category*)
         :rank [2 (.indexOf (clj->js category-order) (name category*)) "" ""]}))))

(defn- volume-key
  [market]
  (- (or (event-volume market) 0)))

(defn- row-sort-key
  [market]
  [(or (get-in market [:outcome-clock :at-ms]) js/Number.MAX_SAFE_INTEGER)
   (volume-key market)
   (:key market)])

(defn- ending-soon-key
  "Upcoming events by time left; past or undated events last."
  [now-ms market]
  (let [at-ms (get-in market [:outcome-clock :at-ms])
        upcoming? (and (number? at-ms) (> at-ms now-ms))]
    [(if upcoming? 0 1)
     (if upcoming? at-ms (or at-ms js/Number.MAX_SAFE_INTEGER))
     (volume-key market)
     (:key market)]))

(defn- meta-label
  [market]
  (or (:outcome-competition market)
      (get category-titles (category market))
      "Sports"))

(defn row
  ([market now-ms] (row market now-ms nil))
  ([market now-ms opts]
   (let [{:keys [answers more flags]} (answers market opts)
         {:keys [clock venue]} (events/selected-rung-facts market)
         volume (event-volume market)]
     {:key (:key market)
      :title (or (:title market) (:symbol market))
      :shape (:outcome-shape market)
      :meta (meta-label market)
      :venue (or (labels/venue-label venue)
                 (when (seq (:outcome-venues market))
                   (str/join " · " (keep labels/venue-label (:outcome-venues market))))
                 "Hyperliquid")
      :clock (clock/clock-view clock now-ms opts)
      :answers answers
      :more more
      :flags flags
      :volume volume
      :volume-text (or (quotes/compact-shares-text volume) "—")})))

(defn sections
  "Rows grouped into sections. `:most-traded` (default) and `:ending-soon` are
   one flat section each; `:start-time` groups today's and upcoming games by
   day and competition, then futures, then each other category."
  ([markets now-ms] (sections markets now-ms nil))
  ([markets now-ms opts]
   (case (normalize-sort (:sort opts))
     :most-traded
     [{:id "most-traded"
       :title "Most traded"
       :rows (->> markets
                  (sort-by (fn [market] [(volume-key market) (:key market)]))
                  (mapv #(row % now-ms opts)))}]

     :ending-soon
     [{:id "ending-soon"
       :title "Ending soon"
       :rows (->> markets
                  (sort-by (partial ending-soon-key now-ms))
                  (mapv #(row % now-ms opts)))}]

     :start-time
     (->> markets
          (group-by #(section-of % now-ms opts))
          (sort-by (comp :rank key))
          (mapv (fn [[{:keys [id title]} members]]
                  {:id id
                   :title title
                   :rows (->> members
                              (sort-by (if (= "futures" id)
                                         (fn [market] [(volume-key market) (:key market)])
                                         row-sort-key))
                              (mapv #(row % now-ms opts)))}))))))

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

;; ---------------------------------------------------------------------------
;; Live price subscriptions

(def ^:private subscribed-event-limit
  "Fallback until the view reports which rows are on screen."
  30)

(defn visible-coins
  "Side coins whose live contexts keep the rows on screen current. With
   `visible-keys` (event keys the view reports) exactly those rows; without
   them, the first 30 rows."
  ([sections] (visible-coins sections nil))
  ([sections visible-keys]
   (let [rows (mapcat :rows sections)
         key-set (when (seq visible-keys) (set visible-keys))
         rows* (if key-set
                 (filter #(contains? key-set (:key %)) rows)
                 (take subscribed-event-limit rows))]
     (->> rows*
          (mapcat :answers)
          ;; Side 1 mirrors side 0, so side-0 contexts are enough.
          (filter #(zero? (:side-index %)))
          (keep :coin)
          set))))

;; ---------------------------------------------------------------------------
;; Load state

(def stale-after-ms
  "A list older than this should reload when the panel opens."
  (* 5 60 1000))

(defn- age-text
  [age-ms]
  (let [minutes (js/Math.floor (/ age-ms 60000))]
    (cond
      (< minutes 1) "Updated just now"
      (< minutes 60) (str "Updated " minutes " min ago")
      :else (str "Updated " (js/Math.floor (/ minutes 60)) " h ago"))))

(defn load-status
  "What the list can say about its data, from `[:asset-selector]` state:
   {:status :loading|:error|:ready :error :loaded-at-ms :age-ms :stale?
    :updated-text :refresh-action}. A failed load is :error (with the message)
   and offers the same refresh action as a manual refresh — never \"No events
   found\"."
  [{:keys [loading? phase error loaded-at-ms]} now-ms]
  (let [age-ms (when (and (number? loaded-at-ms) (number? now-ms))
                 (max 0 (- now-ms loaded-at-ms)))
        ready? (= :full phase)]
    {:status (cond
               loading? :loading
               (some? error) :error
               ready? :ready
               :else :loading)
     :error error
     :loaded-at-ms loaded-at-ms
     :age-ms age-ms
     :stale? (boolean (and ready? (number? age-ms) (> age-ms stale-after-ms)))
     :updated-text (when (and ready? (number? age-ms)) (age-text age-ms))
     :refresh-action [:actions/refresh-asset-markets]}))

;; ---------------------------------------------------------------------------
;; Actions (pure: state -> effects)

(def ^:private sync-subscriptions-effect
  [:effects/sync-asset-selector-active-ctx-subscriptions])

(defn set-outcome-list-sort
  "Order the outcome list by `sort-id` (:most-traded :ending-soon
   :start-time). Stored at [:asset-selector :outcome-sort]."
  [state sort-id]
  (let [sort* (normalize-sort sort-id)]
    (if (= sort* (get-in state [:asset-selector :outcome-sort]))
      []
      [[:effects/save-many [[[:asset-selector :outcome-sort] sort*]
                            [[:asset-selector :highlighted-market-key] nil]]]
       sync-subscriptions-effect])))

(defn set-outcome-visible-event-keys
  "The view reports which event rows are on screen; live price subscriptions
   follow them. Stored at [:asset-selector :outcome-visible-keys]."
  [state market-keys]
  (let [keys* (vec (distinct (filter string? (if (sequential? market-keys) market-keys []))))]
    (if (= keys* (get-in state [:asset-selector :outcome-visible-keys]))
      []
      [[:effects/save [:asset-selector :outcome-visible-keys] keys*]
       sync-subscriptions-effect])))

(defn listing-opts
  "The listing options a state carries: sort, visible keys, catalog and the
   live books the app holds."
  [state]
  {:sort (get-in state [:asset-selector :outcome-sort])
   :visible-keys (get-in state [:asset-selector :outcome-visible-keys])
   :market-by-key (get-in state [:asset-selector :market-by-key])
   :books (:orderbooks state)})
