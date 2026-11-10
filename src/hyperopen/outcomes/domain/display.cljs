(ns hyperopen.outcomes.domain.display
  "The `:outcome-*` display fields a built outcome market carries, how they
   survive the browser market caches, and the one-line answer summary used by
   search and compact rows."
  (:require [clojure.string :as str]
            [hyperopen.outcomes.domain.labels :as labels]
            [hyperopen.outcomes.domain.quotes :as quotes]))

(defn market-fields
  "Fields a market built from template facts (see `templates/describe-*`)
   carries for every outcome view."
  [facts]
  (cond-> {:outcome-template (:template facts)
           :outcome-shape (:shape facts)}
    (:competition facts) (assoc :outcome-competition (:competition facts))
    (:clock facts) (assoc :outcome-clock (:clock facts))
    (:venue facts) (assoc :outcome-venue (:venue facts))
    (:rules facts) (assoc :outcome-rules (:rules facts))
    (:subject facts) (assoc :outcome-subject (:subject facts))
    (:ladder facts) (assoc :outcome-ladder (:ladder facts))
    (some some? (:side-long-labels facts)) (assoc :outcome-side-long-labels (vec (:side-long-labels facts)))
    (seq (:search-terms facts)) (assoc :search-keywords (vec (distinct (:search-terms facts))))))

(defn- keyword-value
  [value]
  (cond
    (keyword? value) value
    (and (string? value) (seq (str/trim value))) (keyword (str/replace (str/trim value) #"^:" ""))
    :else nil))

(defn- integer-value
  [value]
  (let [number (cond
                 (number? value) value
                 (string? value) (js/parseInt value 10)
                 :else js/NaN)]
    (when (and (number? number) (not (js/isNaN number)))
      (js/Math.floor number))))

(defn- cached-clock
  [clock]
  (when (map? clock)
    (let [kind (keyword-value (:kind clock))
          at-ms (integer-value (:at-ms clock))
          deadline-ms (integer-value (:deadline-ms clock))]
      (when (and kind (some? at-ms))
        (cond-> {:kind kind :at-ms at-ms}
          (some? deadline-ms) (assoc :deadline-ms deadline-ms))))))

(defn cache-fields
  "The display fields worth persisting with a cached market, normalized so a
   JSON round trip (keywords become strings) restores them intact."
  [market]
  (let [shape (keyword-value (:outcome-shape market))
        template (keyword-value (:outcome-template market))
        clock (cached-clock (:outcome-clock market))]
    (cond-> {}
      shape (assoc :outcome-shape shape)
      template (assoc :outcome-template template)
      clock (assoc :outcome-clock clock)
      (labels/clean (:outcome-venue market)) (assoc :outcome-venue (labels/clean (:outcome-venue market)))
      (labels/clean (:outcome-competition market)) (assoc :outcome-competition (labels/clean (:outcome-competition market)))
      (labels/clean (:outcome-rules market)) (assoc :outcome-rules (labels/clean (:outcome-rules market)))
      (labels/clean (:outcome-subject market)) (assoc :outcome-subject (labels/clean (:outcome-subject market)))
      (keyword-value (:outcome-ladder-kind market)) (assoc :outcome-ladder-kind (keyword-value (:outcome-ladder-kind market)))
      (seq (keep labels/clean (:outcome-venues market))) (assoc :outcome-venues (vec (keep labels/clean (:outcome-venues market)))))))

(defn option-cache-fields
  "The ladder fields a cached question option (rung) keeps: threshold, rung
   kind, rule-set key, venue, clock and rules. Without them a restored ladder
   loses its order check, direction and per-rung rules until the next load."
  [option]
  (let [threshold (labels/parse-number (:threshold option))
        rung-kind (keyword-value (:rung-kind option))
        clock (cached-clock (:clock option))]
    (cond-> {}
      (number? threshold) (assoc :threshold threshold)
      rung-kind (assoc :rung-kind rung-kind)
      clock (assoc :clock clock)
      (labels/clean (:ladder-key option)) (assoc :ladder-key (labels/clean (:ladder-key option)))
      (labels/clean (:venue option)) (assoc :venue (labels/clean (:venue option)))
      (labels/clean (:rules option)) (assoc :rules (labels/clean (:rules option))))))

(defn- safe-number
  [value]
  (let [number (labels/parse-number value)]
    (if (number? number) number 0)))

(defn- positive-number
  [value]
  (let [number (labels/parse-number value)]
    (when (and (number? number) (pos? number)) number)))

(defn market-contracts
  "The sides of every contract an outcome market holds: one entry per option
   (question, ladder), or the market's own sides (a single contract)."
  [market]
  (if (seq (:question-options market))
    (map :sides (:question-options market))
    [(:outcome-sides market)]))

(defn contract-shares
  "Shares traded today in one contract. Volume is mirrored across the two
   sides, so it counts once: side 0's base volume, else side 1's, else the two
   sides' notionals added (a share bought at p on one side is sold at 1 − p
   on the other, so they sum to shares × $1). Nil when nothing traded or no
   context has arrived."
  [sides]
  (let [[a b] sides]
    (or (positive-number (:baseVolume24h a))
        (positive-number (:baseVolume24h b))
        (positive-number (+ (safe-number (:volume24h a)) (safe-number (:volume24h b)))))))

(defn contract-open-interest
  "Dollars locked in one contract: its larger side's shares outstanding (each
   pair of shares is backed by $1)."
  [sides]
  (some->> sides (keep #(positive-number (:circulatingSupply %))) seq (apply max)))

(defn event-totals
  "{:shares24h :open-interest} of a whole event, each contract counted once and
   recomputed from per-contract values, never taken from one side's tick."
  [market]
  (let [contracts (market-contracts market)
        shares (keep contract-shares contracts)
        interest (keep contract-open-interest contracts)]
    {:shares24h (when (seq shares) (reduce + 0 shares))
     :open-interest (when (seq interest) (reduce + 0 interest))}))

(def ^:private summary-option-limit 3)

(defn- option-quote
  [option]
  (quotes/side-quote (first (:sides option))))

(defn- summary-options
  [options]
  (let [options* (vec (or options []))]
    (if (> (count options*) summary-option-limit)
      (->> options*
           (sort-by (fn [{:keys [label volume24h outcome-id] :as option}]
                      (let [quote (option-quote option)]
                        [(- (if (:rankable? quote) (:chance quote) -1))
                         (- (safe-number volume24h))
                         (some-> label str str/lower-case)
                         (or (integer-value outcome-id) js/Number.MAX_SAFE_INTEGER)])))
           (take summary-option-limit)
           vec)
      options*)))

(defn option-summary
  "\"France 55%  *  Draw 25%  *  Italy 21%\" — the three likeliest answers,
   each read through the one chance rule (never the raw mark)."
  [options]
  (->> (summary-options options)
       (map (fn [{:keys [label] :as option}]
              (str label " " (quotes/quote-text (option-quote option)))))
       (str/join "  *  ")))
