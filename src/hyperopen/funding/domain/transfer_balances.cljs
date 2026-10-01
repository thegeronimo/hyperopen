(ns hyperopen.funding.domain.transfer-balances
  "What each Transfer location holds, as exact decimal strings.

   The three places are Perps (USDC collateral), Spot (HyperCore spot
   balances) and HyperEVM (the owner's ERC-20s and native HYPE). Amounts stay
   decimal strings end to end, because a JS number rounds 18-decimal balances
   and `toFixed` rounds half-up, which could make MAX exceed the balance.

   nil always means unknown (not loaded, or unreadable), never zero. \"0\" is a
   balance that was read and is empty."
  (:require [clojure.string :as str]
            [hyperopen.funding.domain.availability :as availability]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.hyperevm.domain.units :as units]))

(def ^:private exact-decimals
  "Precision used to add and subtract amounts exactly. HyperCore reports up
   to 10 fractional digits for some balances and HyperEVM uses 18."
  18)

(defn- parse-exact
  [text]
  (when (string? text)
    (units/parse-units text exact-decimals)))

(defn- format-exact
  [value]
  (units/format-units value exact-decimals))

(defn number->text
  "A non-negative finite JS number as a decimal string, or nil. Rendered with
   10 fractional digits first so float noise (50.123455000000004) does not
   survive, then trimmed."
  [value]
  (when (and (number? value)
             (js/isFinite value)
             (not (neg? value)))
    (some-> (.toFixed value 10) (units/floor-to-decimals 10))))

(defn add-text
  "a + b, exactly. nil when either side is unknown."
  [a b]
  (let [a* (parse-exact a)
        b* (parse-exact b)]
    (when (and a* b*)
      (format-exact (+ a* b*)))))

(defn sub-text
  "a - b, exactly. nil when either side is unknown or the result would be
   negative."
  [a b]
  (let [a* (parse-exact a)
        b* (parse-exact b)]
    (when (and a* b* (>= a* b*))
      (format-exact (- a* b*)))))

(defn min-text
  "The smaller of two amounts. An unknown side is ignored; nil when both are."
  [a b]
  (let [a* (parse-exact a)
        b* (parse-exact b)]
    (cond
      (and a* b*) (if (< b* a*) b a)
      a* a
      b* b
      :else nil)))

(defn positive-text?
  [text]
  (let [value (parse-exact text)]
    (boolean (and value (> value units/zero)))))

(defn compare-text
  "-1, 0 or 1 comparing two amounts exactly, or nil when either is unknown."
  [a b]
  (units/compare-amounts a b exact-decimals))

(defn percent-of-text
  "`pct` percent of `text`, floored to `precision` decimals, or nil."
  [text pct precision]
  (let [value (when (string? text) (units/parse-units text precision))]
    (when (and value (number? pct) (js/isFinite pct) (pos? pct) (<= pct 100))
      (units/format-units (/ (* value (js/BigInt (js/Math.floor pct)))
                             (js/BigInt 100))
                          precision))))

(defn- group-thousands
  [whole]
  (let [digits (str/replace whole #"^0+(?=\d)" "")]
    (str/replace digits #"\B(?=(\d{3})+(?!\d))" ",")))

(defn grouped-amount
  "`text` with its whole part grouped by thousands and its fraction kept as
   typed (\"1000\" -> \"1,000\", \"1234.5\" -> \"1,234.5\"). Anything that is
   not a plain decimal comes back unchanged."
  [text]
  (if (and (string? text) (re-matches #"^\d+(\.\d+)?$" text))
    (let [[whole fraction] (str/split text #"\." 2)]
      (str (group-thousands whole) (when fraction (str "." fraction))))
    text))

(defn display-amount
  "`text` for display: thousands grouped, at least two fractional digits and
   every significant digit kept (\"1240\" -> \"1,240.00\", \"0.12345678\" ->
   \"0.12345678\"). nil for unknown."
  [text]
  (when (and (string? text) (re-matches #"^\d+(\.\d+)?$" text))
    (let [[whole fraction] (str/split text #"\." 2)
          fraction* (str/replace (or fraction "") #"0+$" "")
          fraction** (if (< (count fraction*) 2)
                       (subs (str fraction* "00") 0 2)
                       fraction*)]
      (str (group-thousands whole) "." fraction**))))

;; --- per-location balances -------------------------------------------------

(defn- spot-balance-rows
  [state]
  (let [rows (get-in state [:spot :clearinghouse-state :balances])]
    (when (sequential? rows) rows)))

(defn- row-token-index
  [row]
  (let [token (:token row)]
    (cond
      (number? token) token
      (and (string? token) (re-matches #"^\d+$" (str/trim token))) (js/parseInt (str/trim token) 10)
      :else nil)))

(defn- spot-row
  [rows {:keys [index name]}]
  (or (some #(when (= index (row-token-index %)) %) rows)
      (some #(when (and (nil? (row-token-index %))
                        (= (some-> name str/upper-case)
                           (some-> (:coin %) str str/trim str/upper-case)))
               %)
            rows)))

(defn- row-available-text
  "total - hold of a spot balance row, never negative."
  [{:keys [total hold]}]
  (let [total* (parse-exact (some-> total str str/trim))
        hold* (or (parse-exact (some-> (or hold "0") str str/trim)) units/zero)]
    (when total*
      (format-exact (if (> total* hold*) (- total* hold*) units/zero)))))

(defn spot-available-text
  "Spot `token` (a linked-token map with `:index` and `:name`) available to
   move: total - hold. \"0\" when spot balances are loaded without a row for
   it; nil while spot balances are not loaded."
  [state token]
  (when-let [rows (spot-balance-rows state)]
    (if-let [row (spot-row rows token)]
      (row-available-text row)
      "0")))

(defn spot-total-text
  "Spot `token`'s whole balance (`:total`, holds included), for judging
   whether a credit arrived: an order placed meanwhile moves the hold, not
   the total. \"0\" without a row; nil while spot balances are not loaded."
  [state token]
  (when-let [rows (spot-balance-rows state)]
    (if-let [row (spot-row rows token)]
      (some-> (:total row) str str/trim parse-exact format-exact)
      "0")))

(defn perps-available-text
  "Perps USDC that can leave the default perps dex (withdrawable)."
  [state]
  (number->text (availability/perps-withdrawable state)))

(defn evm-amount-text
  "The owner's HyperEVM balance of `token`, floored to its Core precision,
   or nil when unknown."
  [state owner token]
  (hyperevm-balances/token-amount-text state owner token))

(defn location-known?
  "Whether `location`'s balances for `owner` have been read at all, so an
   empty result there means empty rather than not loaded yet. Perps counts
   once a clearinghouse state is present (its withdrawable reads 0 before).
   HyperEVM counts once read, and not while the read is partial (a token
   whose whole chunk was never answered is unknown, not absent)."
  [state location owner]
  (case location
    :perps (boolean (seq (availability/clearinghouse-state-candidates state)))
    :spot (some? (spot-balance-rows state))
    :hyperevm (and (string? (:native-wei (hyperevm-balances/entry state owner)))
                   (not (hyperevm-balances/partial-read? state owner)))
    false))

(defn location-available-text
  "What `location` (`:perps`, `:spot` or `:hyperevm`) holds of `token` for
   `owner`, as a decimal string, or nil when unknown. Perps holds USDC
   only."
  [state location token owner]
  (case location
    :perps (when (= :usdc-cdw (:kind token)) (perps-available-text state))
    :spot (spot-available-text state token)
    :hyperevm (evm-amount-text state owner token)
    nil))
