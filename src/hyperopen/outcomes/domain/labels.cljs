(ns hyperopen.outcomes.domain.labels
  "Plain-language pieces shared by outcome titles, options and rules: asset
   names, price levels, market caps and UTC timestamps. Pure and locale-free so
   titles built from the feed are identical in every test environment."
  (:require [clojure.string :as str]))

(def ^:private asset-aliases
  ;; Display names for the underlyings the live feed prices outcomes against.
  ;; Anything missing here shows its base symbol (xyz:NVDA -> NVDA).
  {"BTC" "Bitcoin"
   "ETH" "Ethereum"
   "SOL" "Solana"
   "ZEC" "Zcash"
   "xyz:SP500" "S&P 500"
   "xyz:GOLD" "Gold"
   "xyz:SILVER" "Silver"
   "xyz:CL" "Crude oil"})

(def ^:private index-underlyings
  ;; Index levels are points, not dollars.
  #{"xyz:SP500" "xyz:XYZ100"})

(def ^:private institution-aliases
  {"Federal Open Market Committee" "Fed"})

(defn clean
  "Trimmed text, or nil when blank."
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn competition-label
  "How a person names a competition: the abbreviation the feed puts in
   parentheses (\"USA National Football League (NFL)\" -> \"NFL\"), else the name
   without a trailing \"Championship\" (\"MLB Championship\" -> \"MLB\")."
  [value]
  (when-let [text (clean value)]
    (or (some-> (re-find #"\(([A-Za-z0-9&.]{2,8})\)" text) second)
        (let [stripped (str/trim (str/replace text #"(?i)\s+championships?$" ""))]
          (when (seq stripped) stripped))
        text)))

(defn season-label
  "A season (\"2026/2027\"); the feed's literal placeholder \"season\" is none."
  [value]
  (let [text (clean value)]
    (when (and text (not= "season" (str/lower-case text))) text)))

(def ^:private venue-names
  ;; Outcome deployers publish a short code. Only names that are certain are
  ;; mapped; the display names of `out`, `skew` and `fox` are not documented,
  ;; so those codes are shown as published rather than guessed. Add a name
  ;; here once confirmed and every surface picks it up.
  ;; `txyz` is trade.xyz, the deployer of the `xyz:` HIP-3 perps.
  {"txyz" "trade.xyz"})

(defn venue-label
  "Display name for an outcome venue (deployer) code: `txyz` -> trade.xyz.
   Codes without a confirmed name read as published; nil stays nil."
  [venue]
  (when-let [code (clean venue)]
    (or (get venue-names (str/lower-case code))
        code)))

(defn base-symbol
  [subject]
  (let [text (clean subject)]
    (when text
      (last (str/split text #":")))))

(defn asset-label
  "Readable name for an underlying such as `BTC` or `xyz:SP500`."
  [subject]
  (let [subject* (clean subject)]
    (or (get asset-aliases subject*)
        (base-symbol subject*))))

(defn institution-label
  [institution]
  (let [institution* (clean institution)]
    (or (get institution-aliases institution*)
        institution*)))

(defn stocks-subject?
  "Underlyings listed by the `xyz` HIP-3 deployer are equities, indices and
   commodities; everything else priced by outcome templates is crypto."
  [subject]
  (boolean (some-> (clean subject) (str/starts-with? "xyz:"))))

(defn- group-thousands
  [digits]
  (->> (reverse digits)
       (partition-all 3)
       (map (comp str/join reverse))
       reverse
       (str/join ",")))

(defn number-text
  "Format a decimal string with thousands separators while keeping the
   decimals it was published with: \"75040\" -> \"75,040\", \"102.5\" -> \"102.5\"."
  [value]
  (let [text (clean value)]
    (if (or (nil? text)
            (js/isNaN (js/parseFloat text)))
      text
      (let [[int-part frac-part] (str/split text #"\." 2)
            negative? (str/starts-with? int-part "-")
            digits (if negative? (subs int-part 1) int-part)]
        (str (when negative? "-")
             (group-thousands digits)
             (when (seq frac-part)
               (str "." frac-part)))))))

(defn level-text
  "A price level for `subject`: dollars for assets, points for indices."
  [subject value]
  (when-let [number (number-text value)]
    (if (contains? index-underlyings (clean subject))
      number
      (str "$" number))))

(defn parse-number
  [value]
  (let [number (cond
                 (number? value) value
                 (string? value) (js/parseFloat (str/trim value))
                 :else js/NaN)]
    (when-not (js/isNaN number)
      number)))

(defn- trim-decimals
  [text]
  (-> text
      (str/replace #"0+$" "")
      (str/replace #"\.$" "")))

(defn market-cap-text
  "Market caps are published in billions: 1500 -> $1.5T, 800 -> $800B."
  [billions]
  (when-let [value (parse-number billions)]
    (if (>= value 1000)
      (str "$" (trim-decimals (.toFixed (/ value 1000) 2)) "T")
      (str "$" (trim-decimals (.toFixed value 2)) "B"))))

(def ^:private month-names
  ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn- pad2
  [value]
  (let [text (str value)]
    (if (= 1 (count text)) (str "0" text) text)))

(defn utc-time-text
  "Rules quote times in UTC so they read the same for everyone:
   \"Nov 10, 2026 06:00 UTC\"."
  [ms]
  (when (number? ms)
    (let [date (js/Date. ms)]
      (str (get month-names (.getUTCMonth date))
           " "
           (.getUTCDate date)
           ", "
           (.getUTCFullYear date)
           " "
           (pad2 (.getUTCHours date))
           ":"
           (pad2 (.getUTCMinutes date))
           " UTC"))))

(defn humanize-key
  "Turn an unknown template key into words: `companyIpoFoo` -> \"Company ipo foo\"."
  [value]
  (let [text (some-> (clean value)
                     (str/replace #"^template:" "")
                     (str/replace #"([a-z0-9])([A-Z])" "$1 $2")
                     str/lower-case)]
    (when (seq text)
      (str (str/upper-case (subs text 0 1)) (subs text 1)))))

(defn sport-key
  "Normalize the free-text `sport` parameter into a filter keyword."
  [sport]
  (let [token (some-> (clean sport) str/lower-case)]
    (cond
      (nil? token) nil
      (str/includes? token "soccer") :soccer
      (str/includes? token "basketball") :basketball
      (str/includes? token "football") :football
      (str/includes? token "baseball") :baseball
      (str/includes? token "esports") :esports
      :else (keyword (str/replace token #"[^a-z0-9]+" "-")))))
