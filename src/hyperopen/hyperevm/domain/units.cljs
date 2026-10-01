(ns hyperopen.hyperevm.domain.units
  "Exact base-unit math for HyperEVM amounts, over `js/BigInt`.

   An amount of `a` tokens is `a × 10^decimals` base units on HyperEVM, and an
   18-decimal balance overflows a JS number's 53-bit mantissa, so the math here
   runs on BigInt. BigInt values must never leave application or
   infrastructure code: telemetry `JSON.stringify`s app-db snapshots and
   action/effect traces (which throws on BigInt), and wallets reject BigInt
   params. Everywhere else amounts travel as decimal strings, which is what
   `units-text` and `format-units` produce.

   Parsing FLOORS extra fractional digits and never rounds up, because an
   amount rounded up is an amount the user does not hold. Invalid input yields
   nil rather than throwing.

   BigInt arithmetic caveats for ClojureScript: `+ - * < >` compile to the JS
   operators and are safe on BigInt operands, but `zero?`, `mod`, `quot` and
   `rem` compare against or mix with JS numbers and silently misbehave. Use
   `(= value zero)` and `js-mod`/`/` on BigInt operands only."
  (:require [clojure.string :as str]))

(def zero
  (js/BigInt 0))

(def ^:private ten
  (js/BigInt 10))

(def ^:private max-decimals
  "Upper bound on a decimals argument. ERC-20 decimals are a uint8, and the
   linked-token catalog already excludes anything above 36."
  77)

(defn bigint?
  [value]
  (= "bigint" (js* "typeof ~{}" value)))

(defn- valid-decimals?
  [decimals]
  (and (number? decimals)
       (js/Number.isInteger decimals)
       (<= 0 decimals max-decimals)))

(defn pow10
  "10^n as a BigInt, or nil for a negative or non-integer `n`."
  [n]
  (when (valid-decimals? n)
    (loop [acc (js/BigInt 1)
           i 0]
      (if (< i n)
        (recur (* acc ten) (inc i))
        acc))))

(defn to-bigint
  "Coerce a base-unit quantity to BigInt, or nil when it is not a
   non-negative integer.

   Accepts a BigInt, a decimal integer string (`\"1000000\"`), a `0x` hex
   quantity (`\"0xf4240\"`, as JSON-RPC returns), or a JS safe integer."
  [value]
  (cond
    (bigint? value)
    (when-not (neg? value) value)

    (string? value)
    (let [text (str/trim value)]
      (cond
        (re-matches #"^\d+$" text) (js/BigInt text)
        (re-matches #"^0[xX][0-9a-fA-F]+$" text) (js/BigInt (str/lower-case text))
        :else nil))

    (and (number? value)
         (js/Number.isSafeInteger value)
         (not (neg? value)))
    (js/BigInt value)

    :else nil))

(defn units-text
  "Decimal string for a base-unit BigInt (`1000000n` -> \"1000000\"), or nil
   when `value` is not a BigInt."
  [value]
  (when (bigint? value)
    (.toString value)))

(defn parse-units
  "Parse a non-negative decimal amount into base units at `decimals`.

   Extra fractional digits are FLOORED (`\"1.239\"` at 2 decimals is 123).
   Accepts `\"12\"`, `\"12.5\"`, `\"12.\"` and `\".5\"` with surrounding
   whitespace; rejects signs, exponents, separators and blank text. Returns a
   BigInt, or nil for invalid input or an invalid `decimals`."
  [amount-text decimals]
  (let [text (when (string? amount-text) (str/trim amount-text))]
    (when (and (valid-decimals? decimals)
               (seq text)
               (re-matches #"^(?:\d+\.?\d*|\.\d+)$" text))
      (let [[whole fraction] (str/split text #"\." 2)
            whole* (if (seq whole) whole "0")
            fraction* (subs (str (or fraction "")
                                 (apply str (repeat decimals "0")))
                            0
                            decimals)]
        (+ (* (js/BigInt whole*) (pow10 decimals))
           (if (seq fraction*) (js/BigInt fraction*) zero))))))

(defn format-units
  "Decimal string for `units` base units at `decimals`, with trailing
   fractional zeros trimmed (`1500000n` at 6 decimals -> \"1.5\").

   `units` may be anything `to-bigint` accepts, or a negative BigInt. Returns
   nil for invalid input."
  [units decimals]
  (let [value (if (bigint? units) units (to-bigint units))]
    (when (and (valid-decimals? decimals) value)
      (let [negative? (neg? value)
            magnitude (if negative? (- zero value) value)
            digits (.toString magnitude)
            padded (if (<= (count digits) decimals)
                     (str (apply str (repeat (- (inc decimals) (count digits)) "0"))
                          digits)
                     digits)
            split-at* (- (count padded) decimals)
            whole (subs padded 0 split-at*)
            fraction (str/replace (subs padded split-at*) #"0+$" "")]
        (str (when (and negative? (not= magnitude zero)) "-")
             whole
             (when (seq fraction) (str "." fraction)))))))

(defn floor-to-decimals
  "Floor a decimal amount string to at most `decimals` fractional digits and
   trim trailing zeros (`\"1.23456789\"` at 4 -> \"1.2345\"). Nil when invalid."
  [amount-text decimals]
  (some-> (parse-units amount-text decimals)
          (format-units decimals)))

(defn compare-amounts
  "Compare two decimal amount strings exactly at `decimals` precision.
   Returns -1, 0 or 1, or nil when either side is invalid."
  [left-text right-text decimals]
  (let [left (parse-units left-text decimals)
        right (parse-units right-text decimals)]
    (when (and left right)
      (cond
        (< left right) -1
        (> left right) 1
        :else 0))))
