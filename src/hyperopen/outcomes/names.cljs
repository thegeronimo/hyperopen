(ns hyperopen.outcomes.names
  "How an outcome side is named on every surface that is not the market list:
   event, answer and side, never a coin id and never a decimal price.

   Pure: callers hand in the market map (or a market-by-key catalog) and get
   plain strings back."
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.outcomes.domain.quotes :as quotes]))

(def outcome-coin-pattern #"^[+#](\d+)$")

(defn outcome-coin?
  "True for a side token such as a hash or plus token."
  [coin]
  (boolean (and (string? coin) (re-matches outcome-coin-pattern (str/trim coin)))))

(defn canonical-coin
  "A plus token and a hash token name the same side; markets use the hash form."
  [coin]
  (when (outcome-coin? coin)
    (str "#" (second (re-matches outcome-coin-pattern (str/trim coin))))))

(defn- non-blank
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn short-title
  "\"Detroit Pistons vs Washington Wizards\" -> \"Pistons vs Wizards\": a game's
   short form keeps the last word of each team. Other titles pass through."
  [title]
  (let [title* (non-blank title)]
    (if-let [[_ a b] (and title* (re-matches #"(.+?)\s+vs\.?\s+(.+)" title*))]
      (let [tail (fn [team] (last (str/split (str/trim team) #"\s+")))
            multi? (fn [team] (> (count (str/split (str/trim team) #"\s+")) 1))]
        (if (and (multi? a) (multi? b))
          (str (tail a) " vs " (tail b))
          title*))
      title*)))

(defn side-label
  "What the shares back, in words. `{:side-name :option-label :ladder? :head-to-head?}`
   -> \"DET\", \"Thunder Yes\", \"No on $80\"."
  [{:keys [side-name option-label ladder?]}]
  (let [side (or (non-blank side-name) "Yes")
        option (non-blank option-label)]
    (cond
      (and option ladder?) (str side " on " option)
      option (str option " " side)
      :else side)))

(defn- side-entries
  "Every side a market carries with its answer: [{:side :option-label}]."
  [market]
  (concat (map (fn [side] {:side side
                           :option-label (or (:outcome-option-label side)
                                             (:selected-outcome-option-label market))})
               (:outcome-sides market))
          (mapcat (fn [option]
                    (map (fn [side] {:side side
                                     :option-label (or (:outcome-option-label side)
                                                       (:label option))})
                         (:sides option)))
                  (:question-options market))))

(defn find-side
  "{:side :option-label} for `coin` in `market`, or nil. An entry that knows
   its answer wins over one that only knows the selected side."
  [market coin]
  (let [coin* (canonical-coin coin)]
    (when coin*
      (let [matches (filter (fn [entry]
                              (= coin* (canonical-coin (get-in entry [:side :coin]))))
                            (side-entries market))]
        (or (first (filter :option-label matches))
            (first matches))))))

(defn- ladder?
  [market]
  (or (= :ladder (:outcome-kind market))
      (= :ladder (:outcome-shape market))))

(defn describe
  "Names for `coin` inside `market`:
   {:event :event-short :side-name :option-label :side-label :label :short-label}.
   `:label` is \"Event · side\"; `:short-label` is \"Side · short event\"."
  [market coin]
  (when (and (map? market) (= :outcome (:market-type market)))
    (let [{:keys [side option-label]} (find-side market coin)
          event (or (non-blank (:title market)) (non-blank (:symbol market)))
          side-name (or (non-blank (:side-name side))
                        (case (some-> (canonical-coin coin) (subs 1) js/parseInt (mod 10))
                          0 "Yes"
                          1 "No"
                          nil))]
      (when (and event side-name)
        (let [label (side-label {:side-name side-name
                                 :option-label option-label
                                 :ladder? (ladder? market)})]
          {:event event
           :event-short (short-title event)
           :side-name side-name
           :option-label option-label
           :side-label label
           :label (str event " · " label)
           :short-label (str label " · " (short-title event))})))))

(defn resolve-coin
  "`describe` for a coin looked up in a market-by-key catalog; nil for coins
   that are not outcome sides or are unknown."
  [market-by-key coin]
  (when (outcome-coin? coin)
    (let [coin* (canonical-coin coin)
          market (or (markets/resolve-market-by-coin (or market-by-key {}) coin*)
                     (some (fn [market]
                             (when (and (= :outcome (:market-type market))
                                        (find-side market coin*))
                               market))
                           (vals market-by-key)))]
      (when market
        (describe market coin*)))))

(defn cents
  "81.9¢ for 0.819; em dash when missing."
  [price]
  (quotes/cents-text price))

(defn shares-text
  "\"122 shares\", \"1 share\"."
  [shares]
  (let [n (if (number? shares) shares (js/parseFloat shares))]
    (when (and (number? n) (not (js/isNaN n)))
      (let [text (if (== n (js/Math.floor n)) (str (js/Math.floor n)) (.toFixed n 2))]
        (str text (if (== n 1) " share" " shares"))))))
