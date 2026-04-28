(ns hyperopen.portfolio.optimizer.application.universe-candidates
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.query :as asset-query]))

(def ^:private default-candidate-limit
  6)

(defn- normalized-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- raw-asset-id?
  [value]
  (let [text (normalized-text value)]
    (boolean
     (and text
          (or (str/starts-with? text "@")
              (re-matches #"\d+" text))))))

(defn- hip3-instrument?
  [instrument]
  (boolean
   (or (:dex instrument)
       (:hip3? instrument)
       (:hip3-eligible? instrument))))

(defn- symbol-first?
  [instrument]
  (or (= :spot (:market-type instrument))
      (hip3-instrument? instrument)
      (raw-asset-id? (:coin instrument))))

(defn- base-from-symbol
  [symbol]
  (let [symbol* (normalized-text symbol)]
    (cond
      (and symbol* (str/includes? symbol* "/"))
      (normalized-text (first (str/split symbol* #"/" 2)))

      (and symbol* (str/includes? symbol* "-"))
      (normalized-text (first (str/split symbol* #"-" 2)))

      :else nil)))

(defn- base-label
  [instrument]
  (or (normalized-text (:base instrument))
      (base-from-symbol (:symbol instrument))
      (when-not (raw-asset-id? (:coin instrument))
        (normalized-text (:coin instrument)))))

(defn- friendly-name
  [value]
  (case (some-> value normalized-text str/upper-case)
    "BTC" "Bitcoin"
    "ETH" "Ether"
    "SOL" "Solana"
    "HYPE" "Hyperliquid"
    "ARB" "Arbitrum"
    "LINK" "Chainlink"
    "USDC" "USD Coin"
    "PURR/USDC" "Purr"
    (or (normalized-text value) "--")))

(defn selected-instrument-ids
  [universe]
  (into #{} (keep :instrument-id) universe))

(defn- market-label
  [market-or-instrument]
  (or (normalized-text (:symbol market-or-instrument))
      (normalized-text (:coin market-or-instrument))
      (normalized-text (:key market-or-instrument))
      (normalized-text (:instrument-id market-or-instrument))
      "Unknown Market"))

(defn- usable-market?
  [selected-ids market]
  (let [market-key (normalized-text (:key market))]
    (and market-key
         (normalized-text (:coin market))
         (:market-type market)
         (not (contains? selected-ids market-key)))))

(defn- exact-match-rank
  [query-upper market]
  (let [label-upper (some-> (market-label market) str/upper-case)
        coin-upper (some-> (:coin market) normalized-text str/upper-case)]
    (if (and (seq query-upper)
             (or (= query-upper label-upper)
                 (= query-upper coin-upper)))
      0
      1)))

(defn- market-type-rank
  [market]
  (if (= :spot (:market-type market)) 0 1))

(defn- rank-candidates
  [markets query ranking]
  (let [query-upper (some-> query normalized-text str/upper-case)]
    (case ranking
      :asset-query
      markets

      (->> markets
           (map-indexed (fn [idx market]
                          {:idx idx
                           :market market}))
           (sort-by (fn [{:keys [idx market]}]
                      [(exact-match-rank query-upper market)
                       (market-type-rank market)
                       idx]))
           (mapv :market)))))

(defn candidate-markets
  ([state universe query]
   (candidate-markets state universe query nil))
  ([state universe query opts]
   (let [selected-ids (selected-instrument-ids universe)
         query* (or (normalized-text query) "")
         ranking (or (:ranking opts) :exact-spot)
         markets (->> (asset-query/filter-and-sort-assets
                       (get-in state [:asset-selector :markets])
                       query*
                       :volume
                       :desc
                       #{}
                       false
                       false
                       :all)
                      (filter #(usable-market? selected-ids %))
                      vec)]
     (->> (rank-candidates markets query* ranking)
          (take default-candidate-limit)
          vec))))

(defn market-display
  [market-or-instrument]
  (let [base-label (base-label market-or-instrument)]
    {:label (market-label market-or-instrument)
     :name (or (normalized-text (:name market-or-instrument))
               (normalized-text (:full-name market-or-instrument))
               (when (symbol-first? market-or-instrument)
                 (friendly-name base-label))
               (friendly-name (:coin market-or-instrument)))
     :base-label base-label}))

(defn- finite-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value) value)

    (string? value)
    (let [parsed (js/Number value)]
      (when (js/isFinite parsed)
        parsed))

    :else nil))

(defn active-index
  [state markets]
  (if-not (seq markets)
    0
    (let [last-index (dec (count markets))
          idx (or (some-> (get-in state [:portfolio-ui :optimizer :universe-search-active-index])
                          finite-number
                          js/Math.floor)
                  0)]
      (-> idx
          (max 0)
          (min last-index)))))
