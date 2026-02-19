(ns hyperopen.asset-selector.markets
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]))

(defn market-key
  "Return the unique key for a market map."
  [{:keys [market-type coin]}]
  (str (name market-type) ":" coin))

(defn- numeric-coin-string? [coin]
  (and (string? coin)
       (re-matches #"\d+" coin)))

(defn- spot-market-key [coin]
  (str "spot:" coin))

(defn- perp-market-key [coin]
  (str "perp:" coin))

(defn coin->market-key
  "Best-effort mapping from a coin string to a market key.
   Spot coins contain '/' or provider spot ids prefixed with '@'."
  [coin]
  (let [coin* (when (some? coin) (str coin))]
    (if (and (seq coin*)
             (or (str/includes? coin* "/")
                 (str/starts-with? coin* "@")))
      (spot-market-key coin*)
      (perp-market-key coin*))))

(defn candidate-market-keys
  "Deterministically ordered candidate keys for resolving a coin into market-by-key.
   For numeric legacy spot ids, tries `spot:@<id>` first."
  [coin]
  (let [coin* (when (some? coin) (str coin))]
    (cond
      (not (seq coin*))
      []

      (numeric-coin-string? coin*)
      (vec (distinct [(spot-market-key (str "@" coin*))
                      (spot-market-key coin*)
                      (perp-market-key coin*)]))

      :else
      (let [primary (coin->market-key coin*)
            fallback (if (str/starts-with? primary "spot:")
                       (perp-market-key coin*)
                       (spot-market-key coin*))]
        (vec (distinct [primary fallback]))))))

(defn resolve-market-by-coin
  "Resolve a market from market-by-key using deterministic fallback keys."
  [market-by-key coin]
  (when (and (map? market-by-key) (some? coin))
    (some #(get market-by-key %)
          (candidate-market-keys coin))))

(defn- parse-perp-name [name]
  (if (and name (str/includes? name ":"))
    (let [[dex base] (str/split name #":" 2)]
      {:dex dex :base base :coin name})
    {:dex nil :base name :coin name}))

(defn- parse-spot-name [name]
  (if (and name (str/includes? name "/"))
    (let [[base quote] (str/split name #"/" 2)]
      {:base base :quote quote})
    {:base name :quote nil}))

(defn- safe-num [v]
  (let [n (fmt/safe-number v)]
    (if (js/isNaN n) 0 n)))

(def ^:private hip3-min-open-interest-usd
  1000000)

(defn- hip3-eligible-market?
  [market]
  (let [open-interest-usd (safe-num (:openInterest market))]
    (and (= :perp (:market-type market))
         (some? (:dex market))
         (not (true? (:delisted? market)))
         (>= open-interest-usd hip3-min-open-interest-usd))))

(defn- pct-change [mark prev]
  (when (and prev (not= prev 0))
    (* 100 (/ (- mark prev) prev))))

(defn classify-market
  "Assign :category and :hip3? based on market type and dex." 
  [market]
  (cond
    (= :spot (:market-type market))
    (assoc market :category :spot :hip3? false :hip3-eligible? false)

    (nil? (:dex market))
    (assoc market :category :crypto :hip3? false :hip3-eligible? false)

    (= "hyna" (:dex market))
    (assoc market :category :crypto :hip3? true :hip3-eligible? (hip3-eligible-market? market))

    :else
    (assoc market :category :tradfi :hip3? true :hip3-eligible? (hip3-eligible-market? market))))

(defn build-perp-markets
  "Build normalized perp market entries from metaAndAssetCtxs data.
   Accepts the meta map, asset ctxs vector, a token index->symbol map,
   and optional dex name (string)."
  [meta asset-ctxs collateral-token->symbol & {:keys [dex]}]
  (let [universe (:universe meta)
        collateral-token (:collateralToken meta)
        quote (or (get collateral-token->symbol collateral-token) "USDC")
        ctxs (vec (or asset-ctxs []))]
    (->> (map-indexed vector (or universe []))
         (keep (fn [[idx info]]
                 (let [ctx (nth ctxs idx nil)]
                   (when ctx
                     (let [{:keys [name maxLeverage szDecimals]} info
                           parsed (parse-perp-name name)
                           base (:base parsed)
                           coin (:coin parsed)
                           dex-name (or dex (:dex parsed))
                           mark-raw (:markPx ctx)
                           prev-raw (:prevDayPx ctx)
                           mark (safe-num mark-raw)
                           prev (safe-num prev-raw)
                           change (- mark prev)
                           change-pct (pct-change mark prev)
                           volume (safe-num (:dayNtlVlm ctx))
                           open-interest (safe-num (:openInterest ctx))
                           open-interest-usd (fmt/calculate-open-interest-usd open-interest mark)
                           funding (safe-num (:funding ctx))
                           market {:key (str "perp:" coin)
                                   :coin coin
                                   :symbol (str base "-" quote)
                                   :base base
                                   :quote quote
                                   :market-type :perp
                                   :dex dex-name
                                   :delisted? (boolean (:isDelisted info))
                                   :idx idx
                                   :mark mark
                                   :markRaw mark-raw
                                   :volume24h volume
                                   :change24h change
                                   :change24hPct change-pct
                                   :prevDayRaw prev-raw
                                   :szDecimals szDecimals
                                   :openInterest open-interest-usd
                                   :fundingRate funding
                                   :maxLeverage maxLeverage}]
                       (classify-market market))))))
         vec)))

(defn build-spot-markets
  "Build normalized spot market entries from spotMeta and spotAssetCtxs." 
  [spot-meta spot-asset-ctxs]
  (let [universe (:universe spot-meta)
        tokens-list (vec (:tokens spot-meta))
        token-info-by-index (reduce (fn [acc {:keys [index tokenId] :as token}]
                                 (cond-> acc
                                   (some? index) (assoc index token)
                                   (some? index) (assoc (str index) token)
                                   (some? tokenId) (assoc tokenId token)))
                               {}
                               tokens-list)
        token-by-index (into {}
                             (map (fn [[k {:keys [name]}]] [k name]))
                             token-info-by-index)
        token-name (fn [idx]
                     (or (get token-by-index idx)
                         (when (and (number? idx)
                                    (<= 0 idx)
                                    (< idx (count tokens-list)))
                           (:name (nth tokens-list idx)))
                         (when (and (string? idx) (re-matches #"\d+" idx))
                           (let [n (js/parseInt idx)]
                             (when (and (<= 0 n) (< n (count tokens-list)))
                               (:name (nth tokens-list n)))))))
        token-sz-decimals (fn [idx]
                            (or (some-> (get token-info-by-index idx) :szDecimals)
                                (when (and (number? idx)
                                           (<= 0 idx)
                                           (< idx (count tokens-list)))
                                  (:szDecimals (nth tokens-list idx)))
                                (when (and (string? idx) (re-matches #"\d+" idx))
                                  (let [n (js/parseInt idx)]
                                    (when (and (<= 0 n) (< n (count tokens-list)))
                                      (:szDecimals (nth tokens-list n)))))))
        ctxs (vec (or spot-asset-ctxs []))]
    (->> (or universe [])
         (keep (fn [entry]
                 (let [coin (:name entry)
                       idx (:index entry)
                       tokens (:tokens entry)
                       base-idx (when (sequential? tokens) (first tokens))
                       quote-idx (when (sequential? tokens) (second tokens))
                       ctx (nth ctxs idx nil)]
                   (when (and coin ctx)
                     (let [parsed (parse-spot-name coin)
                           base (or (token-name base-idx) (:base parsed))
                           quote (or (token-name quote-idx) (:quote parsed))
                           symbol (if (and base quote) (str base "/" quote) coin)
                           mark-raw (:markPx ctx)
                           prev-raw (:prevDayPx ctx)
                           mark (safe-num mark-raw)
                           prev (safe-num prev-raw)
                           change (- mark prev)
                           change-pct (pct-change mark prev)
                           volume (safe-num (:dayNtlVlm ctx))
                           market {:key (str "spot:" coin)
                                   :coin coin
                                   :symbol symbol
                                   :base base
                                   :quote quote
                                   :market-type :spot
                                   :dex nil
                                   :idx idx
                                   :mark mark
                                   :markRaw mark-raw
                                   :volume24h volume
                                   :change24h change
                                   :change24hPct change-pct
                                   :prevDayRaw prev-raw
                                   :szDecimals (token-sz-decimals base-idx)
                                   :openInterest nil
                                   :fundingRate nil
                                   :maxLeverage nil}]
                       (classify-market market))))))
         vec)))
