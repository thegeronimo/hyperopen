(ns hyperopen.vol-range.application.display
  "Display formatting for Vol Range view models. Every function works from exact
  decimal strings through `hyperopen.domain.trading.precision`; there is no
  float formatting (`.toFixed`, `toLocaleString`) anywhere in this namespace.
  Time enters as an injected millisecond value."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]))

(def minus-sign "−")

(defn parse [x] (cond (string? x) (precision/parse-decimal x)
                      (number? x) (precision/number->decimal x)
                      :else nil))

(defn group-thousands
  "Insert commas into the integer digits of a plain decimal string."
  [s]
  (let [negative? (str/starts-with? s "-")
        body (if negative? (subs s 1) s)
        dot (.indexOf body ".")
        int-part (if (neg? dot) body (subs body 0 dot))
        frac (if (neg? dot) "" (subs body dot))
        grouped (->> (reverse int-part)
                     (partition-all 3)
                     (map #(apply str (reverse %)))
                     reverse
                     (str/join ","))]
    (str (when negative? minus-sign) grouped frac)))

(defn exact
  "Exact decimal string with thousands grouping, or nil for a non-decimal."
  [x]
  (some-> (parse x) precision/decimal->string group-thousands))

(defn fixed
  "Decimal rounded half-up to exactly `places` decimals, grouped."
  [x places]
  (when-let [d (parse x)]
    (group-thousands (precision/to-fixed (precision/quantize d places :half-up) places))))

(defn amount
  "Base or quote amount at `places` decimals when given, else exact."
  [x places]
  (if (some? places) (fixed x places) (exact x)))

(defn percent
  "Fraction string as a percent with two decimals, e.g. \"0.06\" -> \"6.00%\"."
  [fraction]
  (when-let [d (parse fraction)]
    (str (fixed (precision/decimal->string (precision/mul d (precision/parse-decimal "100"))) 2) "%")))

(defn percent-places
  "Like `percent` with `places` decimals."
  [fraction places]
  (when-let [d (parse fraction)]
    (str (fixed (precision/decimal->string (precision/mul d (precision/parse-decimal "100"))) places) "%")))

(defn sigma
  "Signed sigma text such as \"−0.85σ\" or \"+0.50σ\"; trailing zeros trimmed."
  [x]
  (when-let [d (parse x)]
    (let [s (precision/decimal->string d)]
      (cond (precision/neg-dec? d) (str minus-sign (subs s 1) "σ")
            (precision/zero-dec? d) "0σ"
            :else (str "+" s "σ")))))

(defn sigma-magnitude
  "Unsigned sigma text such as \"0.75σ\"."
  [x]
  (when-let [d (parse x)]
    (str (precision/decimal->string (precision/abs-dec d)) "σ")))

(defn- pad2 [n] (if (< n 10) (str "0" n) (str n)))

(defn utc-clock
  "\"HH:MM:SS UTC\" for an epoch-millisecond value."
  [ms]
  (when (number? ms)
    (let [d (js/Date. ms)]
      (str (pad2 (.getUTCHours d)) ":" (pad2 (.getUTCMinutes d)) ":" (pad2 (.getUTCSeconds d)) " UTC"))))

(def ^:private month-names
  ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn utc-date
  "\"Oct 5\" for an epoch-millisecond value."
  [ms]
  (when (number? ms)
    (let [d (js/Date. ms)]
      (str (nth month-names (.getUTCMonth d)) " " (.getUTCDate d)))))

(defn short-address
  "\"0x1234…abcd\" for a long address; other text unchanged."
  [address]
  (cond
    (not (string? address)) nil
    (> (count address) 12) (str (subs address 0 6) "…" (subs address (- (count address) 4)))
    :else address))
