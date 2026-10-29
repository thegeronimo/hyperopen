(ns hyperopen.views.trading-chart.utils.fill-fields
  "Tolerant readers for Hyperliquid fill rows. REST `userFills` rows and
  websocket/runtime rows spell the same fields differently, so every chart
  consumer reads fills through these helpers instead of raw keys."
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.projections :as projections]))

(defn finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn parse-num
  [value]
  (projections/parse-optional-num value))

(defn parse-time-ms
  [value]
  (projections/parse-time-ms value))

(defn non-blank-text
  [value]
  (projections/non-blank-text value))

(defn- normalize-token
  [value]
  (some-> value non-blank-text str/upper-case))

(defn spot-like-coin?
  [coin]
  (let [coin* (some-> coin non-blank-text)]
    (boolean
     (and coin*
          (or (str/includes? coin* "/")
              (str/starts-with? coin* "@"))))))

(defn fill-coin
  [fill]
  (or (:coin fill)
      (:symbol fill)
      (:asset fill)))

(defn fill-direction-text
  [fill]
  (some-> (or (:dir fill) (:direction fill))
          str
          str/trim
          str/lower-case))

(defn- resolve-base-token
  [coin market-by-key]
  (let [{:keys [base-label]} (projections/resolve-coin-display coin (or market-by-key {}))]
    (normalize-token base-label)))

(defn asset-fill-match?
  "True when `fill` belongs to the chart's `active-asset`: same wire coin, or
  the same base token within the same market kind (spot vs perp)."
  [active-asset fill market-by-key]
  (let [active-asset* (non-blank-text active-asset)
        fill-coin* (non-blank-text (fill-coin fill))
        active-base (resolve-base-token active-asset* market-by-key)
        fill-base (resolve-base-token fill-coin* market-by-key)
        raw-match? (= active-asset* fill-coin*)
        active-spot? (spot-like-coin? active-asset*)
        fill-spot? (spot-like-coin? fill-coin*)
        same-market-kind? (= active-spot? fill-spot?)]
    (boolean
     (and (seq active-base)
          (seq fill-base)
          (or raw-match?
              (and same-market-kind?
                   (= active-base fill-base)))))))

(defn fill-side-sign
  "1 for a buy, -1 for a sell, nil when the side cannot be determined."
  [fill]
  (let [side (some-> (:side fill) str str/trim str/upper-case)
        direction (fill-direction-text fill)]
    (cond
      (contains? #{"B" "BUY" "BID" "LONG"} side) 1
      (contains? #{"A" "S" "SELL" "ASK" "SHORT"} side) -1
      (and (seq direction)
           (or (str/includes? direction "sell")
               (str/includes? direction "open short")
               (str/includes? direction "close long")))
      -1
      (and (seq direction)
           (or (str/includes? direction "buy")
               (str/includes? direction "open long")
               (str/includes? direction "close short")))
      1
      :else nil)))

(defn fill-time
  [fill]
  (or (parse-time-ms (:time fill))
      (parse-time-ms (:timestamp fill))
      (parse-time-ms (:ts fill))
      (parse-time-ms (:t fill))))

(defn fill-price
  [fill]
  (or (parse-num (:price fill))
      (parse-num (:px fill))
      (parse-num (:p fill))
      (parse-num (:fillPx fill))
      (parse-num (:avgPx fill))))

(defn fill-size
  [fill]
  (or (parse-num (:sz fill))
      (parse-num (:size fill))
      (parse-num (:s fill))
      (parse-num (:filledSz fill))
      (parse-num (:filled fill))))

(defn fill-identity
  "Stable dedupe key: trade id when present, else a tuple of the fill's fields.
  The order id is deliberately not a key on its own: one order can fill in
  several trades, and each must count."
  [fill]
  (or (non-blank-text (:tid fill))
      (non-blank-text (:fill-id fill))
      (non-blank-text (:id fill))
      (non-blank-text (:fillId fill))
      [(non-blank-text (:oid fill))
       (fill-coin fill)
       (fill-time fill)
       (fill-side-sign fill)
       (fill-price fill)
       (fill-size fill)
       (parse-num (:startPosition fill))
       (non-blank-text (:dir fill))
       (non-blank-text (:direction fill))]))
