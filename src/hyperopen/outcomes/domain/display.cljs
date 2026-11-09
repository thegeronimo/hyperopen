(ns hyperopen.outcomes.domain.display
  "The `:outcome-*` display fields a built outcome market carries, how they
   survive the browser market caches, and the one-line answer summary used by
   search and compact rows."
  (:require [clojure.string :as str]
            [hyperopen.outcomes.domain.labels :as labels]))

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
      (labels/clean (:outcome-subject market)) (assoc :outcome-subject (labels/clean (:outcome-subject market))))))

(defn- safe-number
  [value]
  (let [number (labels/parse-number value)]
    (if (number? number) number 0)))

(def ^:private summary-option-limit 3)

(defn- summary-options
  [options]
  (let [options* (vec (or options []))]
    (if (> (count options*) summary-option-limit)
      (->> options*
           (sort-by (fn [{:keys [label mark volume24h outcome-id]}]
                      [(- (safe-number mark))
                       (- (safe-number volume24h))
                       (some-> label str str/lower-case)
                       (or (integer-value outcome-id) js/Number.MAX_SAFE_INTEGER)]))
           (take summary-option-limit)
           vec)
      options*)))

(defn option-summary
  "\"France 55%  *  Draw 25%  *  Italy 21%\" — the three likeliest answers."
  [options]
  (->> (summary-options options)
       (map (fn [{:keys [label mark]}]
              (str label
                   " "
                   (if (number? mark)
                     (str (js/Math.round (* mark 100)) "%")
                     "--"))))
       (str/join "  *  ")))
