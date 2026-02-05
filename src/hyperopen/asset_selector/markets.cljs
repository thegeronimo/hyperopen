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
                           mark (safe-num (:markPx ctx))
                           prev (safe-num (:prevDayPx ctx))
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
                                   :volume24h volume
                                   :change24h change
                                   :change24hPct change-pct
                                   :openInterest open-interest-usd
                                   :fundingRate funding
                                   :maxLeverage maxLeverage}]
                       (classify-market market))))))
         vec)))

(defn build-spot-markets
  "Build normalized spot market entries from spotMeta and spotAssetCtxs." 
  [spot-meta spot-asset-ctxs]
  (let [universe (:universe spot-meta)
        ctxs (vec (or spot-asset-ctxs []))]
    (->> (or universe [])
         (keep (fn [entry]
                 (let [coin (:name entry)
                       idx (:index entry)
                       ctx (nth ctxs idx nil)]
                   (when (and coin ctx)
                     (let [{:keys [base quote]} (parse-spot-name coin)
                           mark (safe-num (:markPx ctx))
                           prev (safe-num (:prevDayPx ctx))
                           change (- mark prev)
                           change-pct (pct-change mark prev)
                           volume (safe-num (:dayNtlVlm ctx))
                           market {:key (str "spot:" coin)
                                   :coin coin
                                   :symbol coin
                                   :base base
                                   :quote quote
                                   :market-type :spot
                                   :dex nil
                                   :idx idx
                                   :mark mark
                                   :volume24h volume
                                   :change24h change
                                   :change24hPct change-pct
                                   :openInterest nil
                                   :fundingRate nil
                                   :maxLeverage nil}]
                       (classify-market market))))))
         vec)))
