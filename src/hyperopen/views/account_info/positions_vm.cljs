(ns hyperopen.views.account-info.positions-vm
  (:require [clojure.string :as str]
            [hyperopen.ui.table.sort-kernel :as sort-kernel]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]))

(def ^:private cross-margin-mode-tokens
  #{"cross" "crossmargin"})

(def ^:private isolated-margin-mode-tokens
  #{"isolated" "isolatedmargin" "nocross" "strictisolated"})

(defn- normalize-position-row
  [row]
  (cond
    (and (map? row) (map? (:position row))) row
    (map? row) {:position row}
    :else {:position {}}))

(defn display-coin
  [position-data]
  (let [coin (:coin position-data)
        parsed (shared/parse-coin-namespace coin)]
    (or (:base parsed)
        (shared/non-blank-text coin)
        "-")))

(defn dex-chip-label
  [position-row]
  (let [position (or (:position position-row) {})
        explicit-dex (shared/non-blank-text (:dex position-row))
        parsed-prefix (some-> (:coin position) shared/parse-coin-namespace :prefix)]
    (or explicit-dex parsed-prefix)))

(defn position-side
  [position-data]
  (let [size-num (shared/parse-optional-num (:szi position-data))]
    (cond
      (and (number? size-num) (neg? size-num)) :short
      (and (number? size-num) (pos? size-num)) :long
      :else :flat)))

(defn calculate-mark-price
  [position-row-or-position]
  (let [position-row (normalize-position-row position-row-or-position)
        position (or (:position position-row) {})]
    (or (shared/parse-optional-num (:markPx position))
        (shared/parse-optional-num (:markPrice position))
        (shared/parse-optional-num (:markPx position-row))
        (shared/parse-optional-num (:markPrice position-row)))))

(defn- parse-optional-boolean
  [value]
  (cond
    (true? value) true
    (false? value) false

    (string? value)
    (let [text (-> value str str/trim str/lower-case)]
      (case text
        "true" true
        "false" false
        nil))

    :else nil))

(defn- normalize-margin-mode
  [value]
  (let [token (some-> value
                      str
                      str/trim
                      str/lower-case
                      (str/replace #"[\s_\-]" ""))]
    (cond
      (contains? cross-margin-mode-tokens token) :cross
      (contains? isolated-margin-mode-tokens token) :isolated
      :else nil)))

(defn- first-present
  [values]
  (reduce (fn [_ value]
            (if (some? value)
              (reduced value)
              nil))
          nil
          values))

(defn position-margin-mode
  [position-row-or-position]
  (let [position-row (normalize-position-row position-row-or-position)
        position (or (:position position-row) {})
        leverage-type-mode (normalize-margin-mode
                            (or (get-in position [:leverage :type])
                                (get-in position [:leverage :mode])
                                (:leverageType position)
                                (:leverage-type position)))
        is-cross? (first-present [(parse-optional-boolean (:isCross position))
                                  (parse-optional-boolean (:is-cross position))
                                  (parse-optional-boolean (:isCross position-row))
                                  (parse-optional-boolean (:is-cross position-row))])
        boolean-mode (when (some? is-cross?)
                       (if is-cross? :cross :isolated))
        margin-mode (normalize-margin-mode
                     (or (:marginMode position)
                         (:margin-mode position)
                         (:marginMode position-row)
                         (:margin-mode position-row)))]
    (or leverage-type-mode boolean-mode margin-mode)))

(defn margin-mode-display-label
  [margin-mode]
  (case margin-mode
    :cross "Cross"
    :isolated "Isolated"
    nil))

(defn- absolute-size-text
  [size]
  (let [size-text (shared/non-blank-text size)]
    (cond
      (and size-text (str/starts-with? size-text "-")) (subs size-text 1)
      (and size-text (str/starts-with? size-text "+")) (subs size-text 1)
      size-text size-text
      :else (if-let [size-num (shared/parse-optional-num size)]
              (str (js/Math.abs size-num))
              "0"))))

(defn format-position-size
  [position-row-or-position]
  (let [position-row (normalize-position-row position-row-or-position)
        position (or (:position position-row) {})
        size (absolute-size-text (:szi position))
        coin (display-coin position)]
    (str size " " coin)))

(defn funding-display-value
  [funding-num]
  (when (number? funding-num)
    (let [display-value (- funding-num)]
      (if (zero? display-value)
        0
        display-value))))

(defn format-funding-currency
  [display-funding]
  (when (number? display-funding)
    (let [display-funding* (if (zero? display-funding)
                             0
                             display-funding)
          sign (when (neg? display-funding*) "-")]
      (str sign "$" (shared/format-currency (js/Math.abs display-funding*))))))

(defn- format-funding-tooltip
  [all-time-funding since-change-funding]
  (let [all-time-text (or (format-funding-currency all-time-funding)
                          "--")
        since-change-text (or (format-funding-currency since-change-funding)
                              "--")]
    (str "All-time: " all-time-text " Since change: " since-change-text)))

(defn- position-all-time-funding-num
  [position]
  (or (shared/parse-optional-num (get-in position [:cumFunding :allTime]))
      (shared/parse-optional-num (get-in position [:cumFunding :all-time]))))

(defn- position-since-open-funding-num
  [position]
  (or (shared/parse-optional-num (get-in position [:cumFunding :sinceOpen]))
      (shared/parse-optional-num (get-in position [:cumFunding :since-open]))))

(defn- valid-trigger-price
  [value]
  (let [num (shared/parse-optional-num value)]
    (when (and (number? num)
               (pos? num))
      value)))

(defn- resolve-position-trigger-price
  [position-row side]
  (let [row (normalize-position-row position-row)
        position (or (:position row) {})
        candidates (case side
                     :tp [(:position-tp-trigger-px row)
                          (:tp-trigger-px row)
                          (:tpTriggerPx row)
                          (:takeProfitPx row)
                          (:takeProfitTriggerPx row)
                          (:tpPx row)
                          (:tpTriggerPx position)
                          (:takeProfitPx position)
                          (:takeProfitTriggerPx position)
                          (:tpPx position)]
                     :sl [(:position-sl-trigger-px row)
                          (:sl-trigger-px row)
                          (:slTriggerPx row)
                          (:stopLossPx row)
                          (:stopLossTriggerPx row)
                          (:slPx row)
                          (:slTriggerPx position)
                          (:stopLossPx position)
                          (:stopLossTriggerPx position)
                          (:slPx position)]
                     [])]
    (some valid-trigger-price candidates)))

(defn- tpsl-copy
  [position-row]
  (let [tp-trigger (resolve-position-trigger-price position-row :tp)
        sl-trigger (resolve-position-trigger-price position-row :sl)
        tp-text (if tp-trigger
                  (shared/format-trade-price tp-trigger)
                  "--")
        sl-text (if sl-trigger
                  (shared/format-trade-price sl-trigger)
                  "--")]
    (str tp-text " / " sl-text)))

(defn- pnl-color-class
  [pnl-num]
  (cond
    (and (number? pnl-num) (pos? pnl-num)) "text-success"
    (and (number? pnl-num) (neg? pnl-num)) "text-error"
    :else "text-trading-text"))

(defn- funding-tone-class
  [display-funding]
  (cond
    (and (number? display-funding) (neg? display-funding)) "text-error"
    (and (number? display-funding) (pos? display-funding)) "text-success"
    :else "text-trading-text"))

(defn position-row-vm
  [position-row]
  (let [row-data (normalize-position-row position-row)
        position (or (:position row-data) {})
        side (position-side position)
        mark-price (calculate-mark-price row-data)
        margin-mode (position-margin-mode row-data)
        margin-mode-label (margin-mode-display-label margin-mode)
        all-time-funding-num (position-all-time-funding-num position)
        since-open-funding-num (position-since-open-funding-num position)
        since-change-funding-num (or (shared/parse-optional-num (get-in position [:cumFunding :sinceChange]))
                                     (shared/parse-optional-num (get-in position [:cumFunding :since-change]))
                                     since-open-funding-num)
        display-all-time-funding (funding-display-value all-time-funding-num)
        display-funding (funding-display-value (or since-open-funding-num
                                                   all-time-funding-num))
        display-since-change-funding (funding-display-value since-change-funding-num)
        coin-label (display-coin position)
        dex-label (dex-chip-label row-data)
        coin-search-candidates (shared/normalized-coin-search-candidates
                                [(:coin position)
                                 (some-> (:coin position) shared/parse-coin-namespace :base)
                                 dex-label])
        pnl-num (shared/parse-optional-num (:unrealizedPnl position))
        pnl-percent (some-> (:returnOnEquity position) shared/parse-optional-num (* 100))
        size-num (shared/parse-optional-num (:szi position))
        row-key (projections/position-unique-key row-data)]
    {:row-data row-data
     :position position
     :row-key row-key
     :side side
     :coin-label coin-label
     :coin-sort-label (some-> coin-label str/lower-case)
     :dex-label dex-label
     :size-num size-num
     :size-abs-num (when (number? size-num) (js/Math.abs size-num))
     :size-display (format-position-size row-data)
     :position-value-num (shared/parse-optional-num (:positionValue position))
     :entry-price (:entryPx position)
     :mark-price mark-price
     :mark-price-display (if (number? mark-price)
                           (shared/format-trade-price mark-price)
                           "--")
     :pnl-num pnl-num
     :pnl-percent pnl-percent
     :pnl-color-class (pnl-color-class pnl-num)
     :liq-price (:liquidationPx position)
     :liq-explanation (or (shared/non-blank-text (:liquidationExplanation position))
                          (shared/non-blank-text (:liquidation-explanation position))
                          (shared/non-blank-text (:liquidation-explanation row-data)))
     :margin (:marginUsed position)
     :margin-num (shared/parse-optional-num (:marginUsed position))
     :margin-mode margin-mode
     :margin-mode-label margin-mode-label
     :margin-editable? (not= :cross margin-mode)
     :funding-display display-funding
     :funding-display-text (format-funding-currency display-funding)
     :funding-tooltip (when (or (number? display-all-time-funding)
                                (number? display-since-change-funding))
                        (format-funding-tooltip display-all-time-funding display-since-change-funding))
     :funding-tone-class (funding-tone-class display-funding)
     :tpsl-copy (tpsl-copy row-data)
     :normalized-coin-search-candidates coin-search-candidates}))

(defn position-row-vms
  [positions]
  (->> (or positions [])
       (mapv position-row-vm)))

(defn filter-row-vms
  [row-vms direction-filter coin-search]
  (let [direction* (or direction-filter :all)
        direction-filtered (case direction*
                             :long (filterv #(= :long (:side %)) row-vms)
                             :short (filterv #(= :short (:side %)) row-vms)
                             (vec row-vms))
        query (shared/compile-coin-search-query coin-search)]
    (if (shared/coin-search-query-blank? query)
      direction-filtered
      (filterv #(shared/normalized-coin-candidates-match? (:normalized-coin-search-candidates %) query)
               direction-filtered))))

(defn- liq-sort-value
  [row-vm]
  (if-let [liq (:liq-price row-vm)]
    (or (shared/parse-optional-num liq) js/Number.MAX_VALUE)
    js/Number.MAX_VALUE))

(defn sort-row-vms-by-column
  [row-vms column direction]
  (let [pnl-column? (= column "PNL (ROE %)")
        sort-options {:column column
                      :direction direction
                      :accessor-by-column
                      {"Coin" (fn [row-vm] (or (:coin-sort-label row-vm) ""))
                       "Size" (fn [row-vm] (or (:size-abs-num row-vm) 0))
                       "Position Value" (fn [row-vm] (or (:position-value-num row-vm) 0))
                       "Entry Price" (fn [row-vm] (or (shared/parse-optional-num (:entry-price row-vm)) 0))
                       "Mark Price" (fn [row-vm] (or (:mark-price row-vm) 0))
                       "PNL (ROE %)" (fn [row-vm] (or (:pnl-num row-vm) 0))
                       "Liq. Price" liq-sort-value
                       "Margin" (fn [row-vm] (or (:margin-num row-vm) 0))
                       "Funding" (fn [row-vm] (or (:funding-display row-vm) 0))}}
        sort-options (if pnl-column?
                       (assoc sort-options :tie-breaker (fn [row-vm]
                                                          (or (:pnl-percent row-vm) 0)))
                       sort-options)]
    (sort-kernel/sort-rows-by-column row-vms sort-options)))

(defn sort-position-rows-by-column
  [positions column direction]
  (mapv :row-data
        (sort-row-vms-by-column (position-row-vms positions)
                                column
                                direction)))
