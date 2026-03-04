(ns hyperopen.views.funding-comparison.vm
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.funding-comparison.actions :as funding-actions]))

(def ^:private neutral-threshold-eight-hour
  1e-4)

(def ^:private binance-4h-coins
  #{"ANIME" "BERA" "LAYER" "IP" "KAITO" "TST" "VINE" "VVV" "AI16Z" "ME" "SPX"
    "NOT" "TURBO" "IO" "ZK" "MEW" "LISTA" "ZRO" "RENDER" "BANANA" "BRETT" "POPCAT"
    "KDOGS" "NEIROETH" "POL" "NEIRO" "CATI" "HMSTR" "EIGEN" "SCR" "GOAT" "MOODENG"
    "GRASS" "PNUT" "MORPHO" "CHILLGUY" "MOVE" "VIRTUAL" "PENGU" "USUAL" "AIXBT"
    "FARTCOIN" "GRIFFAIN" "ZEREBRO" "BIO" "S" "TRUMP" "MELANIA" "TRB" "BLZ" "IMX"
    "UMA" "YGG" "CYBER" "LOOM" "BIGTIME" "ORBS" "BSV" "POLYX" "GAS" "TIA" "CAKE"
    "MEME" "ORDI" "BADGER" "ILV" "NTRN" "KAS" "KBONK" "PYTH" "SUPER" "USTC" "JTO"
    "ACE" "AI" "XAI" "WIF" "MANTA" "ONDO" "ALT" "JUP" "ZETA" "DYM" "PIXEL"
    "STRK" "MAVIA" "TON" "MYRO" "BOME" "ETHFI" "ENA" "W" "TNSR" "SAGA" "TAO"
    "OMNI" "REZ"})

(def ^:private bybit-1h-coins
  #{"KAITO"})

(def ^:private bybit-2h-coins
  #{"IP" "LAYER" "USUAL" "BERA" "ANIME"})

(def ^:private bybit-4h-coins
  #{"VINE" "VVV" "JELLY" "TST" "ACE" "MANTA" "ME" "SCR" "MELANIA" "TRUMP" "JUP"
    "FARTCOIN" "ONDO" "WIF" "KBONK" "AI16Z" "AIXBT" "VIRTUAL" "ENA" "POPCAT" "PNUT"
    "JTO" "TIA" "PENGU" "GOAT" "SPX" "TAO" "BRETT" "KAS" "ORDI" "TON" "RENDER"
    "PYTH" "MOODENG" "MEW" "NOT" "CHILLGUY" "MORPHO" "POL" "BOME" "GRASS" "EIGEN"
    "MOVE" "STRK" "DOGS" "ZETA" "ETHFI" "ZRO" "TRB" "IO" "MEME" "BIGTIME" "ZK"
    "W" "NEIROETH" "SAGA" "SUPER" "HMSTR" "MYRO" "IMX" "XAI" "AI" "CAKE" "TNSR"
    "GAS" "BANANA" "CATI" "OMNI" "DYM" "ALT" "MINA" "BSV" "CYBER" "UMA" "YGG"
    "POLYX" "ILV" "PIXEL" "ORBS" "BADGER" "LISTA" "USTC" "REZ" "NTRN" "LOOM"})

(def timeframe-options
  [{:value :hour :label "Hourly"}
   {:value :8hour :label "8 Hours"}
   {:value :day :label "Day"}
   {:value :week :label "Week"}
   {:value :year :label "Year"}])

(def ^:private timeframe-multipliers
  {:hour 1
   :8hour 8
   :day 24
   :week (* 24 7)
   :year (* 24 365)})

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

    :else
    nil))

(defn- normalize-coin-token
  [coin]
  (some-> coin str str/trim str/upper-case))

(defn- parse-predicted-row
  [row]
  (when (and (sequential? row)
             (>= (count row) 2))
    (let [coin (some-> (first row) str str/trim)
          venues (second row)]
      (when (seq coin)
        {:coin coin
         :coin-token (normalize-coin-token coin)
         :venues (if (sequential? venues)
                   (reduce (fn [acc venue-row]
                             (if (and (sequential? venue-row)
                                      (>= (count venue-row) 2))
                               (assoc acc
                                      (first venue-row)
                                      (second venue-row))
                               acc))
                           {}
                           venues)
                   {})}))))

(defn- has-cex-funding-rate?
  [{:keys [venues]}]
  (or (number? (optional-number (:fundingRate (get venues "BinPerp"))))
      (number? (optional-number (:fundingRate (get venues "BybitPerp"))))))

(defn- venue-fallback-interval-hours
  [venue coin-token]
  (case venue
    "BinPerp"
    (if (contains? binance-4h-coins coin-token) 4 8)

    "BybitPerp"
    (cond
      (contains? bybit-1h-coins coin-token) 1
      (contains? bybit-2h-coins coin-token) 2
      (contains? bybit-4h-coins coin-token) 4
      :else 8)

    1))

(defn- venue-hourly-rate
  [venue timeframe payload coin-token]
  (let [funding-rate (optional-number (:fundingRate payload))]
    (when (number? funding-rate)
      (let [interval-hours
            (if (= venue "HlPerp")
              1
              (let [declared-interval (optional-number (:fundingIntervalHours payload))]
                (if (and (number? declared-interval)
                         (pos? declared-interval))
                  declared-interval
                  (venue-fallback-interval-hours venue coin-token))))
            hourly-rate (/ funding-rate interval-hours)
            multiplier (get timeframe-multipliers timeframe 8)]
        (* hourly-rate multiplier)))))

(defn- funding-tone
  [venue value]
  (if-not (number? value)
    :neutral
    (let [threshold (if (= venue "HlPerp")
                      (/ neutral-threshold-eight-hour 8)
                      neutral-threshold-eight-hour)]
      (cond
        (< value 0) :negative
        (<= value threshold) :neutral
        :else :positive))))

(defn- coin-open-interest
  [market-by-key coin]
  (when-let [market (markets/resolve-market-by-coin market-by-key coin)]
    (optional-number (:openInterest market))))

(defn- coin-favorite-state
  [favorites coin]
  (let [candidate-keys (markets/candidate-market-keys coin)
        favorite-key (first candidate-keys)
        favorite? (boolean (some favorites candidate-keys))]
    {:favorite? favorite?
     :favorite-market-key favorite-key}))

(defn- arb-details
  [venue-label venue-rate hl-rate]
  (if (and (number? venue-rate)
           (number? hl-rate))
    (let [diff (- venue-rate hl-rate)
          direction (cond
                      (zero? diff)
                      (str "No funding arb between Hyperliquid and " venue-label)

                      (pos? diff)
                      (str "Long on Hyperliquid and short on " venue-label)

                      :else
                      (str "Short on Hyperliquid and long on " venue-label))]
      {:value (js/Math.abs diff)
       :direction direction
       :raw-diff diff})
    {:value nil
     :direction nil
     :raw-diff nil}))

(defn- build-row
  [{:keys [coin coin-token venues]} market-by-key favorites timeframe]
  (let [hl-rate (venue-hourly-rate "HlPerp" timeframe (get venues "HlPerp") coin-token)
        binance-rate (venue-hourly-rate "BinPerp" timeframe (get venues "BinPerp") coin-token)
        bybit-rate (venue-hourly-rate "BybitPerp" timeframe (get venues "BybitPerp") coin-token)
        open-interest (coin-open-interest market-by-key coin)
        {:keys [favorite? favorite-market-key]} (coin-favorite-state favorites coin)
        binance-hl-arb (arb-details "Binance" binance-rate hl-rate)
        bybit-hl-arb (arb-details "Bybit" bybit-rate hl-rate)]
    {:coin coin
     :favorite? favorite?
     :favorite-market-key favorite-market-key
     :open-interest open-interest
     :hyperliquid {:rate hl-rate
                   :tone (funding-tone "HlPerp" hl-rate)}
     :binance {:rate binance-rate
               :tone (funding-tone "BinPerp" binance-rate)}
     :binance-hl-arb binance-hl-arb
     :bybit {:rate bybit-rate
             :tone (funding-tone "BybitPerp" bybit-rate)}
     :bybit-hl-arb bybit-hl-arb}))

(defn- matches-query?
  [coin query]
  (let [query* (some-> query str str/trim str/lower-case)]
    (or (str/blank? query*)
        (str/includes? (str/lower-case (or coin "")) query*))))

(defn- row-sort-value
  [row column]
  (case column
    :coin (str/lower-case (or (:coin row) ""))
    :open-interest (optional-number (:open-interest row))
    :hyperliquid (optional-number (get-in row [:hyperliquid :rate]))
    :binance (optional-number (get-in row [:binance :rate]))
    :binance-hl-arb (optional-number (get-in row [:binance-hl-arb :raw-diff]))
    :bybit (optional-number (get-in row [:bybit :rate]))
    :bybit-hl-arb (optional-number (get-in row [:bybit-hl-arb :raw-diff]))
    nil))

(defn- compare-rows
  [left right {:keys [column direction]}]
  (let [column* (funding-actions/normalize-funding-comparison-sort-column column)
        direction* (funding-actions/normalize-funding-comparison-sort-direction direction)
        primary (if (= column* :coin)
                  (compare (row-sort-value left column*)
                           (row-sort-value right column*))
                  (compare (or (row-sort-value left column*) js/Number.NEGATIVE_INFINITY)
                           (or (row-sort-value right column*) js/Number.NEGATIVE_INFINITY)))
        primary* (if (= :desc direction*) (- primary) primary)]
    (if (zero? primary*)
      (let [coin-cmp (compare (str/lower-case (or (:coin left) ""))
                              (str/lower-case (or (:coin right) "")))]
        (if (zero? coin-cmp)
          (compare (if (:favorite? left) 0 1)
                   (if (:favorite? right) 0 1))
          coin-cmp))
      primary*)))

(defn- sort-rows
  [rows sort-state]
  (vec (sort #(compare-rows %1 %2 sort-state)
             rows)))

(defn funding-comparison-vm
  [state]
  (let [query (get-in state [:funding-comparison-ui :query] "")
        timeframe (funding-actions/normalize-funding-comparison-timeframe
                   (get-in state [:funding-comparison-ui :timeframe]))
        sort-state (let [raw-sort (or (get-in state [:funding-comparison-ui :sort])
                                      {:column funding-actions/default-sort-column
                                       :direction funding-actions/default-sort-direction})]
                     {:column (funding-actions/normalize-funding-comparison-sort-column (:column raw-sort))
                      :direction (funding-actions/normalize-funding-comparison-sort-direction (:direction raw-sort))})
        favorites (or (get-in state [:asset-selector :favorites]) #{})
        market-by-key (or (get-in state [:asset-selector :market-by-key]) {})
        parsed-rows (->> (or (get-in state [:funding-comparison :predicted-fundings]) [])
                         (keep parse-predicted-row)
                         (filter has-cex-funding-rate?)
                         (map #(build-row % market-by-key favorites timeframe))
                         (filter #(matches-query? (:coin %) query))
                         vec)
        sorted-rows (sort-rows parsed-rows sort-state)]
    {:query query
     :timeframe timeframe
     :timeframe-options timeframe-options
     :sort sort-state
     :loading? (true? (get-in state [:funding-comparison-ui :loading?]))
     :error (get-in state [:funding-comparison :error])
     :rows sorted-rows
     :row-count (count sorted-rows)
     :loaded-at-ms (get-in state [:funding-comparison :loaded-at-ms])}))
