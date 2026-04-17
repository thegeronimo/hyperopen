(ns hyperopen.app.document-title
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.domain.market.instrument :as instrument]
            [hyperopen.utils.formatting :as fmt]))

(def brand-title "HyperOpen")

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn- first-present
  [values]
  (some (fn [value]
          (if (string? value)
            (non-blank-text value)
            (when (some? value)
              value)))
        values))

(defn- namespace-token
  [value]
  (let [text (non-blank-text value)]
    (when (and text (str/includes? text ":"))
      (non-blank-text (first (str/split text #":" 2))))))

(defn- resolve-active-market
  [state active-asset]
  (let [projected-market (:active-market state)
        market-by-key (get-in state [:asset-selector :market-by-key] {})]
    (cond
      (and (map? projected-market)
           (markets/market-matches-coin? projected-market active-asset))
      projected-market

      (seq active-asset)
      (markets/resolve-or-infer-market-by-coin market-by-key active-asset)

      :else
      nil)))

(defn- context-for-active-asset
  [state active-asset active-market]
  (let [contexts (get-in state [:active-assets :contexts] {})
        aliases (markets/coin-aliases active-asset active-market)]
    (or (get contexts active-asset)
        (some #(get contexts %) aliases))))

(defn- mark-price
  [context active-market]
  (first-present [(:mark context)
                  (:markRaw context)
                  (:markPx context)
                  (:markPrice context)
                  (:mark active-market)
                  (:markRaw active-market)
                  (:markPx active-market)
                  (:markPrice active-market)]))

(defn- mark-price-raw
  [context active-market]
  (first-present [(:markRaw context)
                  (:markRaw active-market)
                  (:mark context)
                  (:mark active-market)
                  (:markPx context)
                  (:markPx active-market)
                  (:markPrice context)
                  (:markPrice active-market)]))

(defn- format-mark-price
  [context active-market locale]
  (fmt/format-trade-price-plain
   (mark-price context active-market)
   (mark-price-raw context active-market)
   locale))

(defn- spot-label
  [active-asset active-market]
  (or (non-blank-text (:symbol active-market))
      (non-blank-text (:coin active-market))
      (let [base (instrument/resolve-base-symbol active-asset active-market nil)
            quote (instrument/resolve-quote-symbol active-asset active-market nil)]
        (when (and (seq base) (seq quote))
          (str base "/" quote)))
      (non-blank-text active-asset)))

(defn- perp-label
  [active-asset active-market]
  (let [base (instrument/resolve-base-symbol active-asset active-market nil)
        dex (or (non-blank-text (:dex active-market))
                (namespace-token (:coin active-market))
                (namespace-token active-asset))]
    (cond
      (and (seq base) (seq dex))
      (str base " (" dex ")")

      (seq base)
      base

      :else
      (non-blank-text active-asset))))

(defn- active-asset-label
  [active-asset active-market]
  (if (instrument/spot-instrument? active-asset active-market)
    (spot-label active-asset active-market)
    (perp-label active-asset active-market)))

(defn title-for-state
  [state]
  (let [active-asset (non-blank-text (:active-asset state))
        active-market (resolve-active-market state active-asset)
        context (context-for-active-asset state active-asset active-market)
        locale (get-in state [:ui :locale])
        mark-text (format-mark-price context active-market locale)
        asset-text (when (seq active-asset)
                     (active-asset-label active-asset active-market))
        parts (cond-> []
                (seq mark-text) (conj mark-text)
                (seq asset-text) (conj asset-text)
                true (conj brand-title))]
    (str/join " | " parts)))

(defn sync!
  [document state]
  (when (some? document)
    (let [title (title-for-state state)]
      (when (not= title (.-title document))
        (set! (.-title document) title)))))
