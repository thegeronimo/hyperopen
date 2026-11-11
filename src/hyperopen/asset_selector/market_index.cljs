(ns hyperopen.asset-selector.market-index
  "One-pass indexes over the selector catalog (`market-by-key`), for callers
   that resolve many coins against one catalog value. Resolving a coin by
   scanning every market is fine once; done per balance row, or per render
   for the active outcome side, it made every catalog write cost a
   catalog-sized pass per consumer."
  (:require [clojure.string :as str]))

(defn base-coin-token
  "The upper-cased base token a bare coin (\"hype\" -> \"HYPE\") stands for,
   or nil for pair coins, @ids, numeric ids and namespaced coins."
  [coin*]
  (let [base-token (some-> coin* str/trim str/upper-case)]
    (when (and (seq base-token)
               (not (and (string? coin*) (re-matches #"\d+" coin*)))
               (not (str/includes? coin* "/"))
               (not (str/includes? coin* ":"))
               (not (str/starts-with? coin* "@")))
      base-token)))

(defn spot-base-candidate
  "{:base :rank :market} for a spot market (nil otherwise), ranked
   USDC-quoted pairs first, then by quote and key."
  [market]
  (when (= :spot (:market-type market))
    (let [market-coin* (some-> (:coin market) str str/trim)
          [coin-base coin-quote] (when (and (seq market-coin*)
                                            (str/includes? market-coin* "/"))
                                   (str/split market-coin* #"/" 2))
          market-base (some-> (or (:base market) coin-base) str str/trim str/upper-case)
          market-quote (some-> (or (:quote market) coin-quote) str str/trim str/upper-case)]
      {:base market-base
       :market market
       :rank [(if (= "USDC" market-quote) 0 1)
              (or market-quote "")
              (or (:key market) "")]})))

(defn spot-market-base-index
  "Base token -> the best-ranked spot candidate for it (the market a bare
   coin such as \"HYPE\" resolves to), built in one pass. Pass it to
   `markets/resolve-spot-market-by-coin` when resolving many coins."
  [market-by-key]
  (reduce (fn [index market]
            (if-let [{:keys [base rank] :as candidate} (spot-base-candidate market)]
              (let [current (get index base)]
                (if (or (nil? current) (neg? (compare rank (:rank current))))
                  (assoc index base candidate)
                  index))
              index))
          {}
          (vals (or market-by-key {}))))

(defn- outcome-market-tokens
  [market]
  (concat (map :coin (:outcome-sides market))
          (keys (:outcome-side-aliases market))
          (:outcome-subscription-coins market)))

(defn build-outcome-token-index
  "Side coin (as the catalog spells it) -> the outcome markets holding it, in
   catalog order. A coin spelled differently is not found; callers fall back
   to a scan."
  [market-by-key]
  (persistent!
   (reduce (fn [index market]
             (if (= :outcome (:market-type market))
               (reduce (fn [index* token]
                         (if (string? token)
                           (assoc! index* token (conj (get index* token []) market))
                           index*))
                       index
                       (distinct (outcome-market-tokens market)))
               index))
           (transient {})
           (vals (or market-by-key {})))))

(defonce ^:private outcome-token-index-cache
  ;; catalog value -> token index. The views resolve outcome sides against
  ;; both the live catalog and the quote-stable one handed to the account
  ;; panels and chart; a single slot thrashed between them and rebuilt the
  ;; index over the whole catalog on every switch.
  (js/WeakMap.))

(defn outcome-token-index
  "`build-outcome-token-index`, built once per catalog value."
  [market-by-key]
  (if (map? market-by-key)
    (or (.get outcome-token-index-cache market-by-key)
        (let [index (build-outcome-token-index market-by-key)]
          (.set outcome-token-index-cache market-by-key index)
          index))
    (build-outcome-token-index market-by-key)))
