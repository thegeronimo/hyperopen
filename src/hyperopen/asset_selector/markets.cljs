(ns hyperopen.asset-selector.markets
  (:require [clojure.string :as str]
            [hyperopen.domain.market.instrument :as instrument]
            [hyperopen.utils.formatting :as fmt]))

(defn market-key
  "Return the unique key for a market map."
  [{:keys [market-type coin]}]
  (str (name market-type) ":" coin))

(defn- normalized-token
  [value]
  (some-> value str str/trim str/upper-case))

(defn- base-token
  [value]
  (some-> value
          instrument/base-symbol-from-value
          normalized-token))

(defn- namespace-token
  [value]
  (let [text (some-> value str str/trim)]
    (when (and (seq text)
               (str/includes? text ":"))
      (some-> (first (str/split text #":" 2))
              normalized-token))))

(defn- base-match-compatible?
  [coin market]
  (let [coin-namespace (namespace-token coin)
        market-namespace (or (namespace-token (:coin market))
                             (normalized-token (:dex market)))]
    (or (nil? coin-namespace)
        (= coin-namespace market-namespace))))

(defn market-matches-coin?
  "Return true when a market and coin refer to the same instrument even if one side
   is namespaced (for example `xyz:GOLD`) and the other is the display/base symbol (`GOLD`)."
  [market coin]
  (let [market* (or market {})
        coin-token (normalized-token coin)
        market-coin-token (normalized-token (:coin market*))
        market-symbol-token (normalized-token (:symbol market*))
        coin-base-token (base-token coin)
        market-base-token (or (normalized-token (:base market*))
                              (base-token (:coin market*))
                              (base-token (:symbol market*)))]
    (boolean
     (or (and coin-token
              (= coin-token market-coin-token))
         (and coin-token
              (= coin-token market-symbol-token))
         (and coin-base-token
              market-base-token
              (= coin-base-token market-base-token)
              (base-match-compatible? coin market*))))))

(defn coin-aliases
  "Return deterministic raw coin aliases for the same instrument across active-asset and market state."
  [active-asset market]
  (->> [active-asset
        (:coin market)
        (:base market)
        (some-> active-asset instrument/base-symbol-from-value)
        (some-> (:coin market) instrument/base-symbol-from-value)
        (some-> (:symbol market) instrument/base-symbol-from-value)]
       (keep (fn [value]
               (some-> value str str/trim not-empty)))
       distinct
       vec))

(defn related-coin-candidates
  "Return a deterministic set of coin identifiers that may refer to the same active
   instrument in UI state, including base-symbol and namespaced market forms."
  [active-asset market]
  (coin-aliases active-asset market))

(defn- numeric-coin-string? [coin]
  (and (string? coin)
       (re-matches #"\d+" coin)))

(defn- spot-market-key [coin]
  (str "spot:" coin))

(defn- perp-market-key [coin]
  (str "perp:" coin))

(defn- scalar-coin-id?
  [coin]
  (or (string? coin)
      (keyword? coin)
      (number? coin)))

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

(defn- namespaced-perp-coin?
  [coin]
  (and (string? coin)
       (seq coin)
       (str/includes? coin ":")
       (not (str/includes? coin "/"))
       (not (str/starts-with? coin "@"))))

(defn resolve-market-by-coin
  "Resolve a market from market-by-key using deterministic fallback keys."
  [market-by-key coin]
  (let [coin* (when (scalar-coin-id? coin) (str coin))]
    (when (and (map? market-by-key) (seq coin*))
      (or (some #(get market-by-key %)
                (candidate-market-keys coin*))
          (let [base-token (some-> coin* str/trim str/upper-case)
                base-coin? (and (seq base-token)
                                (not (numeric-coin-string? coin*))
                                (not (str/includes? coin* "/"))
                                (not (str/includes? coin* ":"))
                                (not (str/starts-with? coin* "@")))]
            (when base-coin?
              (some->> (vals market-by-key)
                       (keep (fn [market]
                               (let [market-coin* (some-> (:coin market) str str/trim)
                                     [coin-base coin-quote] (when (and (seq market-coin*)
                                                                       (str/includes? market-coin* "/"))
                                                              (str/split market-coin* #"/" 2))
                                     market-base (some-> (or (:base market) coin-base) str str/trim str/upper-case)
                                     market-quote (some-> (or (:quote market) coin-quote) str str/trim str/upper-case)]
                                 (when (and (= :spot (:market-type market))
                                            (= base-token market-base))
                                   {:market market
                                    :rank [(if (= "USDC" market-quote) 0 1)
                                           (or market-quote "")
                                           (or (:key market) "")]}))))
                       (sort-by :rank)
                       first
                       :market)))))))

(declare inferred-perp-market)

(defn resolve-or-infer-market-by-coin
  "Resolve a market from market-by-key. When lookup misses for a namespaced perp coin,
   infer the minimal market metadata needed for active-market consumers."
  [market-by-key coin]
  (let [coin* (when (scalar-coin-id? coin) (str coin))]
    (or (resolve-market-by-coin market-by-key coin*)
        (when (namespaced-perp-coin? coin*)
          (inferred-perp-market coin*)))))

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

(def ^:private stable-coin-symbols
  #{"DAI"
    "FDUSD"
    "GHO"
    "LUSD"
    "MIM"
    "PYUSD"
    "TUSD"
    "USDBC"
    "USDC"
    "USDE"
    "USDH"
    "USDL"
    "USDS"
    "USDT"})

(defn- normalized-symbol
  [value]
  (some-> value str str/trim str/upper-case))

(defn- stable-coin-symbol?
  [symbol]
  (contains? stable-coin-symbols (normalized-symbol symbol)))

(defn- stable-pair?
  [base quote]
  (and (stable-coin-symbol? base)
       (stable-coin-symbol? quote)))

(defn- growth-mode-enabled?
  [value]
  (or (= true value)
      (= :enabled value)
      (= "enabled" (some-> value str str/trim str/lower-case))))

(defn- parse-optional-boolean
  [value]
  (cond
    (boolean? value) value
    (string? value) (= "true" (some-> value str/trim str/lower-case))
    :else nil))

(defn- normalize-margin-mode
  [value]
  (let [token (cond
                (keyword? value) (name value)
                (string? value) value
                :else nil)
        normalized (some-> token
                          str/trim
                          str/lower-case
                          (str/replace #"[_-]" ""))]
    (case normalized
      "normal" :normal
      "nocross" :no-cross
      "strictisolated" :strict-isolated
      nil)))

(def ^:private builder-deployed-perp-asset-id-base
  100000)

(def ^:private builder-deployed-perp-asset-id-stride
  10000)

(defn- parse-int-value [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- canonical-perp-dex-index
  [meta dex perp-dex-index]
  (some parse-int-value
        [perp-dex-index
         (:perp-dex-index meta)
         (:perpDexIndex meta)
         (when (nil? dex) 0)]))

(defn- perp-asset-id
  [perp-dex-index idx]
  (let [dex-idx (parse-int-value perp-dex-index)
        idx* (parse-int-value idx)]
    (cond
      (or (nil? dex-idx) (nil? idx*)) nil
      (zero? dex-idx) idx*
      :else (+ builder-deployed-perp-asset-id-base
               (* dex-idx builder-deployed-perp-asset-id-stride)
               idx*))))

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

(defn- inferred-perp-market
  [coin]
  (let [coin* (some-> coin str str/trim)
        {:keys [dex]} (parse-perp-name coin*)
        identity (instrument/market-identity coin*
                                             {:coin coin*
                                              :market-type :perp
                                              :dex dex})
        base-symbol (some-> (:base-symbol identity) str str/trim not-empty)
        quote-symbol (some-> (:quote-symbol identity) str str/trim not-empty)]
    (when (and (namespaced-perp-coin? coin*)
               (seq dex)
               (seq base-symbol))
      (classify-market
       {:key (perp-market-key coin*)
        :coin coin*
        :symbol (str base-symbol "-" (or quote-symbol "USDC"))
        :base base-symbol
        :quote (or quote-symbol "USDC")
        :market-type :perp
        :dex dex}))))

(defn build-perp-markets
  "Build normalized perp market entries from metaAndAssetCtxs data.
   Accepts the meta map, asset ctxs vector, a token index->symbol map,
   and optional dex name (string)."
  [meta asset-ctxs collateral-token->symbol & {:keys [dex perp-dex-index]}]
  (let [universe (:universe meta)
        collateral-token (:collateralToken meta)
        quote (or (get collateral-token->symbol collateral-token) "USDC")
        ctxs (vec (or asset-ctxs []))
        resolved-perp-dex-index (canonical-perp-dex-index meta dex perp-dex-index)]
    (->> (map-indexed vector (or universe []))
         (keep (fn [[idx info]]
                 (let [ctx (nth ctxs idx nil)]
                   (when ctx
                     (let [{:keys [name maxLeverage szDecimals]} info
                           only-isolated? (parse-optional-boolean
                                           (or (:only-isolated? info)
                                               (:onlyIsolated info)))
                           margin-mode (normalize-margin-mode
                                        (or (:margin-mode info)
                                            (:marginMode info)))
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
                                   :growth-mode? (growth-mode-enabled? (:growthMode info))
                                   :delisted? (boolean (:isDelisted info))
                                   :idx idx
                                   :perp-dex-index resolved-perp-dex-index
                                   :asset-id (perp-asset-id resolved-perp-dex-index idx)
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
                       (classify-market
                        (cond-> market
                          (some? only-isolated?) (assoc :only-isolated? only-isolated?)
                          margin-mode (assoc :margin-mode margin-mode))))))))
         vec)))

(defn- build-spot-token-info-by-key
  [tokens-list]
  (reduce (fn [acc {:keys [index tokenId] :as token}]
            (cond-> acc
              (some? index) (assoc index token)
              (some? index) (assoc (str index) token)
              (some? tokenId) (assoc tokenId token)))
          {}
          tokens-list))

(defn- token-list-entry
  [tokens-list idx]
  (cond
    (and (number? idx)
         (<= 0 idx)
         (< idx (count tokens-list)))
    (nth tokens-list idx)

    (and (string? idx)
         (re-matches #"\d+" idx))
    (let [n (js/parseInt idx 10)]
      (when (and (<= 0 n)
                 (< n (count tokens-list)))
        (nth tokens-list n)))

    :else nil))

(defn- token-field-value
  [token-info-by-key tokens-list idx field]
  (or (some-> (get token-info-by-key idx) field)
      (some-> (token-list-entry tokens-list idx) field)))

(defn- spot-market-stats
  [ctx]
  (let [mark-raw (:markPx ctx)
        prev-raw (:prevDayPx ctx)
        mark (safe-num mark-raw)
        prev (safe-num prev-raw)]
    {:mark mark
     :mark-raw mark-raw
     :prev-raw prev-raw
     :change (- mark prev)
     :change-pct (pct-change mark prev)
     :volume (safe-num (:dayNtlVlm ctx))}))

(defn- build-spot-market-entry
  [ctxs tokens-list token-info-by-key entry]
  (let [coin (:name entry)
        idx (:index entry)
        tokens (:tokens entry)
        ctx (nth ctxs idx nil)]
    (when (and coin ctx)
      (let [[base-idx quote-idx] (if (sequential? tokens)
                                   [(first tokens) (second tokens)]
                                   [nil nil])
            parsed (parse-spot-name coin)
            base (or (token-field-value token-info-by-key tokens-list base-idx :name)
                     (:base parsed))
            quote (or (token-field-value token-info-by-key tokens-list quote-idx :name)
                      (:quote parsed))
            symbol (if (and base quote) (str base "/" quote) coin)
            {:keys [mark mark-raw prev-raw change change-pct volume]}
            (spot-market-stats ctx)]
        (classify-market
         {:key (str "spot:" coin)
          :coin coin
          :symbol symbol
          :base base
          :quote quote
          :market-type :spot
          :dex nil
          :stable-pair? (stable-pair? base quote)
          :idx idx
          :asset-id idx
          :mark mark
          :markRaw mark-raw
          :volume24h volume
          :change24h change
          :change24hPct change-pct
          :prevDayRaw prev-raw
          :szDecimals (token-field-value token-info-by-key tokens-list base-idx :szDecimals)
          :openInterest nil
          :fundingRate nil
          :maxLeverage nil})))))

(defn build-spot-markets
  "Build normalized spot market entries from spotMeta and spotAssetCtxs." 
  [spot-meta spot-asset-ctxs]
  (let [tokens-list (vec (:tokens spot-meta))
        token-info-by-key (build-spot-token-info-by-key tokens-list)
        ctxs (vec (or spot-asset-ctxs []))]
    (->> (or (:universe spot-meta) [])
         (keep #(build-spot-market-entry ctxs tokens-list token-info-by-key %))
         vec)))
