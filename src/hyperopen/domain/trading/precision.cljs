(ns hyperopen.domain.trading.precision
  "Exact decimal arithmetic and legal-price quantization for Vol Range.

  A decimal (`dec`) is `{:units <js/BigInt> :scale <int >= 0>}` meaning
  `units / 10^scale`. Everything here is pure and uses BigInt scaled integers;
  nothing uses floating point parsing. Floats enter only through
  `number->decimal`, at explicit numeric boundaries (fee percent, estimator
  output).

  This namespace is the explicit, side-aware helper for Vol Range
  (HO-SPEC-014 sections 6.1-6.2). The existing helpers in
  `hyperopen.domain.trading.core` and `hyperopen.domain.trading.market` are
  unchanged and keep their float behavior. `szDecimals` is never defaulted: a
  missing or out-of-range value yields nil/unavailable."
  (:refer-clojure :exclude [divide]))

;; ---------------------------------------------------------------------------
;; BigInt primitives (js* keeps operators BigInt-safe through the compiler)
;; ---------------------------------------------------------------------------

(defn- big [x] (js/BigInt x))
(defn- b+ [a b] (js* "(~{} + ~{})" a b))
(defn- b- [a b] (js* "(~{} - ~{})" a b))
(defn- b* [a b] (js* "(~{} * ~{})" a b))
(defn- bquot [a b] (js* "(~{} / ~{})" a b))
(defn- brem [a b] (js* "(~{} % ~{})" a b))
(defn- blt? [a b] (js* "(~{} < ~{})" a b))
(defn- bgt? [a b] (js* "(~{} > ~{})" a b))

(def ^:private b0 (big 0))
(def ^:private b1 (big 1))
(def ^:private b2 (big 2))

(defn- bcmp [a b] (cond (blt? a b) -1 (bgt? a b) 1 :else 0))
(defn- bneg? [a] (blt? a b0))
(defn- bpos? [a] (bgt? a b0))
(defn- bzero? [a] (zero? (bcmp a b0)))
(defn- babs [a] (if (bneg? a) (b- b0 a) a))

(defn- pow10
  "10^n as BigInt, built from a string so no `**` operator is emitted."
  [n]
  (big (str "1" (apply str (repeat n "0")))))

(defn- mk [units scale] {:units units :scale scale})

;; ---------------------------------------------------------------------------
;; Parsing and rendering
;; ---------------------------------------------------------------------------

(def ^:private decimal-pattern #"[+-]?(\d+(\.\d*)?|\.\d+)")

(defn parse-decimal
  "Parse a plain decimal string (`^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)$`). Returns a
  dec or nil. Rejects nil, blank, exponents, grouping and malformed text."
  [s]
  (when (and (string? s) (re-matches decimal-pattern s))
    (let [negative? (= "-" (subs s 0 1))
          body (if (contains? #{"+" "-"} (subs s 0 1)) (subs s 1) s)
          dot (.indexOf body ".")
          int-part (if (neg? dot) body (subs body 0 dot))
          frac-part (if (neg? dot) "" (subs body (inc dot)))
          units (big (str int-part frac-part))]
      (mk (if negative? (b- b0 units) units) (count frac-part)))))

(def ^:private number-string-pattern #"(-?)(\d+)(?:\.(\d+))?(?:e([+-]?\d+))?")

(defn number->decimal
  "Convert a finite JS number to a dec using its shortest round-trip string,
  expanding exponent notation exactly. Nil for NaN, Infinity and non-numbers.
  Use only at numeric boundaries."
  [x]
  (when (and (number? x) (js/isFinite x))
    (when-let [[_ sign int-part frac-part exp-part] (re-matches number-string-pattern (.toString x))]
      (let [frac (or frac-part "")
            exp10 (- (if exp-part (js/parseInt exp-part 10) 0) (count frac))
            digits (big (str int-part frac))
            units (if (>= exp10 0) (b* digits (pow10 exp10)) digits)]
        (mk (if (= "-" sign) (b- b0 units) units)
            (if (>= exp10 0) 0 (- exp10)))))))

(defn- strip-zeros
  "Remove trailing fractional zeros without changing the value."
  [{:keys [units scale]}]
  (loop [u units s scale]
    (if (and (pos? s) (bzero? (brem u (big 10))))
      (recur (bquot u (big 10)) (dec s))
      (mk u s))))

(defn- render [units scale]
  (let [digits (.toString (babs units))
        padded (if (<= (count digits) scale)
                 (str (apply str (repeat (- (inc scale) (count digits)) "0")) digits)
                 digits)
        cut (- (count padded) scale)
        body (if (zero? scale) padded (str (subs padded 0 cut) "." (subs padded cut)))]
    (if (and (bneg? units) (not (bzero? units))) (str "-" body) body)))

(defn decimal->string
  "Clean wire string: no exponent, no trailing zeros or '.', \"0\" for zero,
  never \"-0\"."
  [d]
  (let [{:keys [units scale]} (strip-zeros d)]
    (render units scale)))

;; ---------------------------------------------------------------------------
;; Arithmetic
;; ---------------------------------------------------------------------------

(defn int->decimal [n]
  (when (and (number? n) (js/Number.isInteger n))
    (mk (big n) 0)))

(defn- align [a b]
  (let [s (max (:scale a) (:scale b))]
    [(b* (:units a) (pow10 (- s (:scale a))))
     (b* (:units b) (pow10 (- s (:scale b))))
     s]))

(defn add [a b] (let [[x y s] (align a b)] (mk (b+ x y) s)))
(defn sub [a b] (let [[x y s] (align a b)] (mk (b- x y) s)))
(defn mul [a b] (mk (b* (:units a) (:units b)) (+ (:scale a) (:scale b))))
(defn neg [a] (mk (b- b0 (:units a)) (:scale a)))
(defn abs-dec [a] (mk (babs (:units a)) (:scale a)))
(defn cmp [a b] (let [[x y _] (align a b)] (bcmp x y)))
(defn zero-dec? [a] (bzero? (:units a)))
(defn pos-dec? [a] (bpos? (:units a)))
(defn neg-dec? [a] (bneg? (:units a)))
(defn dec-min [a b] (if (<= (cmp a b) 0) a b))
(defn dec-max [a b] (if (>= (cmp a b) 0) a b))

;; ---------------------------------------------------------------------------
;; Rounding
;; ---------------------------------------------------------------------------

(defn- round-quot
  "Integer num/den (den > 0) rounded by mode. JS BigInt division truncates
  toward zero, so floor/ceil adjust by the remainder's sign."
  [num den mode]
  (let [q (bquot num den)
        r (brem num den)]
    (case mode
      :down q
      :floor (if (bneg? r) (b- q b1) q)
      :ceil (if (bpos? r) (b+ q b1) q)
      :half-up (if (not (blt? (b* (babs r) b2) den))
                 (if (bneg? num) (b- q b1) (b+ q b1))
                 q))))

(defn quantize
  "Round `d` to `scale` decimals. Modes: :floor (toward -inf), :ceil (toward
  +inf), :down (toward zero), :half-up (ties away from zero). Padding to a
  larger scale is exact."
  [d scale mode]
  (let [{:keys [units] s :scale} d]
    (if (>= scale s)
      (mk (b* units (pow10 (- scale s))) scale)
      (mk (round-quot units (pow10 (- s scale)) mode) scale))))

(defn divide
  "Exact rational a/b rounded at `scale` with `mode`; nil when b is zero."
  [a b scale mode]
  (when-not (zero-dec? b)
    (let [num (b* (:units a) (pow10 (+ (:scale b) scale)))
          den (b* (:units b) (pow10 (:scale a)))
          [num den] (if (bneg? den) [(b- b0 num) (b- b0 den)] [num den])]
      (mk (round-quot num den mode) scale))))

(defn to-fixed
  "Half-up quantize then render exactly `places` decimals (display only)."
  [d places]
  (let [{:keys [units scale]} (quantize d places :half-up)]
    (render units scale)))

;; ---------------------------------------------------------------------------
;; Lots
;; ---------------------------------------------------------------------------

(defn- valid-sz? [sz]
  (and (number? sz) (js/Number.isInteger sz) (<= 0 sz 8)))

(defn decimal->lots
  "Convert a non-negative size to integer lots at an explicit `sz-decimals`
  (0..8, never defaulted). Returns {:lots BigInt}, or {:error :not-lot-multiple
  :suggested-down :suggested-up} when not an exact multiple, or nil."
  [d sz-decimals]
  (when (and (valid-sz? sz-decimals) (map? d) (not (neg-dec? d)))
    (let [down (quantize d sz-decimals :floor)]
      (if (zero? (cmp down d))
        {:lots (:units down)}
        {:error :not-lot-multiple
         :suggested-down (decimal->string down)
         :suggested-up (decimal->string (quantize d sz-decimals :ceil))}))))

(defn lots->decimal [lots sz-decimals]
  (mk (big lots) sz-decimals))

;; ---------------------------------------------------------------------------
;; Legal prices
;; ---------------------------------------------------------------------------

(defn max-price-decimals
  "Maximum decimal places for a price: (spot 8 / otherwise 6) - szDecimals,
  floored at 0. Nil when sz-decimals is not an explicit int 0..8."
  [market-type sz-decimals]
  (when (valid-sz? sz-decimals)
    (max 0 (- (if (= market-type :spot) 8 6) sz-decimals))))

(defn significant-figures
  "Digits from the first nonzero digit to the last digit of the clean string."
  [d]
  (let [digits (-> (decimal->string d)
                   (.replace "-" "")
                   (.replace "." "")
                   (.replace (js/RegExp. "^0+") ""))]
    (count digits)))

(defn legal-price?
  "Positive AND (integer OR (clean scale <= max-price-decimals AND at most 5
  significant figures))."
  [d {:keys [market-type sz-decimals]}]
  (boolean
   (when-let [maxdec (max-price-decimals market-type sz-decimals)]
     (and (pos-dec? d)
          (let [clean (strip-zeros d)]
            (or (zero? (:scale clean))
                (and (<= (:scale clean) maxdec)
                     (<= (significant-figures d) 5))))))))

(defn- allowed-scale [d maxdec]
  (let [s (decimal->string d)
        dot (.indexOf s ".")
        int-part (if (neg? dot) s (subs s 0 dot))]
    (if (>= (cmp d (mk b1 0)) 0)
      (min maxdec (max 0 (- 5 (count int-part))))
      (let [frac (subs s (inc dot))
            zeros (- (count frac) (count (.replace frac (js/RegExp. "^0+") "")))]
        (min maxdec (+ zeros 5))))))

(defn quantize-price
  "Explicit side-aware legal price quantization for Vol Range (existing
  helpers are unchanged). `raw` must be a positive dec. :buy returns the
  greatest legal positive price <= raw, :sell the least legal price >= raw.
  `opts` is {:market-type :sz-decimals}; nil when inputs are invalid or the
  result would be non-positive."
  [raw side {:keys [market-type sz-decimals] :as opts}]
  (when-let [maxdec (max-price-decimals market-type sz-decimals)]
    (when (and (map? raw) (pos-dec? raw) (contains? #{:buy :sell} side))
      (let [mode (if (= side :buy) :floor :ceil)
            step (fn [x] (quantize x (allowed-scale x maxdec) mode))]
        (loop [c (step raw) n 1]
          (cond
            (not (pos-dec? c)) nil
            (legal-price? c opts) (strip-zeros c)
            (>= n 3) nil
            :else (recur (step c) (inc n))))))))
