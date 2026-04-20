(ns hyperopen.vaults.adapters.fills
  (:require [clojure.string :as str]
            [hyperopen.vaults.adapters.fill-direction :as fill-direction]))

(defn- optional-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [trimmed (str/trim value)]
      (when (seq trimmed)
        (let [parsed (js/Number trimmed)]
          (when (js/isFinite parsed)
            parsed))))

    :else nil))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- normalize-side
  [side]
  (let [token (some-> side str str/trim str/upper-case)]
    (case token
      "B" "Long"
      "A" "Short"
      "S" "Short"
      "BUY" "Long"
      "SELL" "Short"
      token)))

(defn- normalize-side-key
  [side]
  (case (some-> side str str/trim str/lower-case)
    ("long" "buy" "b") :long
    ("short" "sell" "a" "s") :short
    nil))

(defn- side-source
  [row]
  (or (:side row) (:dir row) (:direction row)))

(defn- fill-time-ms
  [row]
  (or (optional-number (:time row))
      (optional-number (:timestamp row))
      (optional-number (:timeMs row))))

(defn- fill-coin
  [row]
  (non-blank-text (or (:coin row) (:symbol row) (:asset row))))

(defn- fill-side
  [row]
  (or (fill-direction/direction-label row)
      (normalize-side (side-source row))))

(defn- fill-side-key
  [row]
  (or (fill-direction/action-side-key (fill-direction/direction-label row))
      (normalize-side-key (side-source row))))

(defn- fill-direction-key
  [row]
  (or (fill-direction/position-direction-key (fill-direction/direction-label row))
      (normalize-side-key (side-source row))))

(defn- fill-size
  [row]
  (optional-number (or (:sz row)
                       (:size row)
                       (:closedSize row))))

(defn- fill-price
  [row]
  (optional-number (or (:px row)
                       (:price row))))

(defn- fill-closed-pnl
  [row]
  (optional-number (or (:closedPnl row)
                       (:closed-pnl row)
                       (:pnl row))))

(defn- fill-row
  [row]
  (when (map? row)
    (let [size (fill-size row)
          price (fill-price row)]
      {:time-ms (fill-time-ms row)
       :coin (fill-coin row)
       :side (fill-side row)
       :side-key (fill-side-key row)
       :direction-key (fill-direction-key row)
       :size size
       :price price
       :trade-value (when (and (number? size)
                               (number? price))
                      (* (js/Math.abs size) price))
       :fee (optional-number (:fee row))
       :closed-pnl (fill-closed-pnl row)})))

(defn fills
  [rows]
  (->> (if (sequential? rows) rows [])
       (keep fill-row)
       (sort-by (fn [{:keys [time-ms]}]
                  (or time-ms 0))
                >)
       vec))
