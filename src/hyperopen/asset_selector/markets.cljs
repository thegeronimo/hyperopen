(ns hyperopen.asset-selector.markets
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]))

(defn market-key
  "Return the unique key for a market map."
  [{:keys [market-type coin]}]
  (str (name market-type) ":" coin))

(defn coin->market-key
  "Best-effort mapping from a coin string to a market key.
   Spot coins contain '/', perps otherwise."
  [coin]
  (if (and coin (str/includes? coin "/"))
    (str "spot:" coin)
    (str "perp:" coin)))

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

(defn- pct-change [mark prev]
  (when (and prev (not= prev 0))
    (* 100 (/ (- mark prev) prev))))

(defn classify-market
  "Assign :category and :hip3? based on market type and dex." 
  [market]
  (cond
    (= :spot (:market-type market))
    (assoc market :category :spot :hip3? false)

    (nil? (:dex market))
    (assoc market :category :crypto :hip3? false)

    (= "hyna" (:dex market))
    (assoc market :category :crypto :hip3? true)

    :else
    (assoc market :category :tradfi :hip3? true)))

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
                     (let [{:keys [name maxLeverage]} info
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
                                   :idx idx
                                   :mark mark
                                   :markRaw mark-raw
                                   :volume24h volume
                                   :change24h change
                                   :change24hPct change-pct
                                   :prevDayRaw prev-raw
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
        token-by-index (reduce (fn [acc {:keys [index name tokenId]}]
                                 (cond-> acc
                                   (some? index) (assoc index name)
                                   (some? index) (assoc (str index) name)
                                   (some? tokenId) (assoc tokenId name)))
                               {}
                               tokens-list)
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
                                   :openInterest nil
                                   :fundingRate nil
                                   :maxLeverage nil}]
                       (classify-market market))))))
         vec)))
