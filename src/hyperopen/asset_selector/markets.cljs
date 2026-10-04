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

(defn- outcome-side-coin?
  [coin]
  (and (string? coin)
       (str/starts-with? coin "#")))

(defn- outcome-market-key?
  [coin]
  (and (string? coin)
       (or (str/starts-with? coin "outcome:")
           (str/starts-with? coin "question:"))))

(defn- outcome-side-matches-coin?
  [market coin-token]
  (and (= :outcome (:market-type market))
       (or (some (fn [{:keys [coin]}]
                   (= coin-token (normalized-token coin)))
                 (:outcome-sides market))
           (some (fn [coin]
                   (= coin-token (normalized-token coin)))
                 (keys (:outcome-side-aliases market)))
           (some (fn [coin]
                   (= coin-token (normalized-token coin)))
                 (:outcome-subscription-coins market)))))

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
              (outcome-side-matches-coin? market* coin-token))
         (and coin-token
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
  (->> (concat [active-asset
                (:coin market)
                (:base market)
                (some-> active-asset instrument/base-symbol-from-value)
               (some-> (:coin market) instrument/base-symbol-from-value)
               (some-> (:symbol market) instrument/base-symbol-from-value)]
               (map :coin (:outcome-sides market))
               (:outcome-subscription-coins market)
               (keys (:outcome-side-aliases market)))
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
    (cond
      (outcome-market-key? coin*) coin*
      (outcome-side-coin? coin*) coin*
      (and (seq coin*)
           (or (str/includes? coin* "/")
               (str/starts-with? coin* "@"))) (spot-market-key coin*)
      :else (perp-market-key coin*))))

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

      (or (outcome-market-key? coin*)
          (outcome-side-coin? coin*))
      [coin*]

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

(declare market-with-selected-outcome-coin)

(defn- spot-market-by-base-token
  "Best spot market whose base token matches a bare coin (\"HYPE\" ->
   HYPE/USDC), USDC-quoted pairs first."
  [market-by-key coin*]
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
               :market))))

(defn resolve-market-by-coin
  "Resolve a market from market-by-key using deterministic fallback keys."
  [market-by-key coin]
  (let [coin* (when (scalar-coin-id? coin) (str coin))]
    (when (and (map? market-by-key) (seq coin*))
      (or (when (or (outcome-side-coin? coin*)
                    (outcome-market-key? coin*))
            (some (fn [market]
                    (when (market-matches-coin? market coin*)
                      (market-with-selected-outcome-coin market coin*)))
                  (vals market-by-key)))
          (some #(get market-by-key %)
                (candidate-market-keys coin*))
          (spot-market-by-base-token market-by-key coin*)))))

(defn resolve-spot-market-by-coin
  "Resolve a SPOT market for a coin/token, never a perp. Spot balances carry bare
   base tokens (\"HYPE\") whose primary candidate key is the PERP market key when
   the token also trades as a perp, so `resolve-market-by-coin` hands spot
   consumers the perp market (wrong instrument id, symbol, and mark source).
   This variant filters the candidate-key hits to spot markets and falls through
   to the base-token spot scan."
  [market-by-key coin]
  (let [coin* (when (scalar-coin-id? coin) (str coin))]
    (when (and (map? market-by-key) (seq coin*))
      (or (some (fn [market-key]
                  (let [market (get market-by-key market-key)]
                    (when (= :spot (:market-type market))
                      market)))
                (candidate-market-keys coin*))
          (spot-market-by-base-token market-by-key coin*)))))

(defn bare-spot-token-market
  "The spot market a bare spot-token name stands for, when the name is not a
   market coin itself. Spot balances name tokens (\"KHYPE\") while the venue only
   knows spot pairs by their pair coin (\"@336\"); subscribing or fetching with the
   token name is rejected, and Hyperliquid closes the websocket on an unknown
   l2Book coin. Returns nil for any coin that is already a market key candidate
   (perps, pair coins, @ids, outcomes)."
  [market-by-key coin]
  (let [coin* (when (scalar-coin-id? coin) (str coin))]
    (when (and (map? market-by-key)
               (seq coin*)
               (not (some #(contains? market-by-key %)
                          (candidate-market-keys coin*))))
      (spot-market-by-base-token market-by-key coin*))))

(defn unknown-market-coin?
  "True when a FULL catalog (every perp dex, spot, outcomes) cannot resolve
   `coin` to any market, so the venue will reject it. A namespaced perp coin
   counts as unknown only when its dex is in the catalog, so a dex missing
   from a partial load never condemns its markets."
  [market-by-key coin]
  (let [coin* (when (scalar-coin-id? coin) (str coin))]
    (boolean
     (and (seq market-by-key)
          (seq coin*)
          (nil? (resolve-market-by-coin market-by-key coin*))
          (or (not (namespaced-perp-coin? coin*))
              (let [dex (normalized-token (first (str/split coin* #":" 2)))]
                (some #(= dex (normalized-token (:dex %)))
                      (vals market-by-key))))))))

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

(defn expired-outcome-market? [market now-ms]
  (let [expiry-ms (parse-int-value (:expiry-ms market))
        now-ms* (parse-int-value now-ms)]
    (boolean (and (= :outcome (:market-type market)) (number? expiry-ms) (number? now-ms*)
                  (<= expiry-ms now-ms*)))))

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

(def ^:private spot-asset-id-offset
  ;; Hyperliquid encodes a spot order's wire asset as 10000 + the spotMeta pair
  ;; index (perp orders use the raw perp universe index). Verified against
  ;; testnet: PURR/USDC idx 0 -> 10000, @1 idx 1 -> 10001, HYPE/USDC @1035 ->
  ;; 11035. The raw pair index collides 1:1 with low-index perps (raw idx 0 is
  ;; the SOL perp), so the offset MUST be applied before the index is used as a
  ;; wire asset id.
  10000)

(defn- spot-asset-id
  [idx]
  (let [idx* (parse-int-value idx)]
    (when (some? idx*)
      (+ spot-asset-id-offset idx*))))

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

(def ^:private outcome-asset-id-base 100000000)

(defn outcome-encoding
  [outcome side]
  (+ (* 10 (or (parse-int-value outcome) 0))
     (or (parse-int-value side) 0)))

(defn outcome-coin
  [outcome side]
  (str "#" (outcome-encoding outcome side)))

(defn outcome-asset-id
  [outcome side]
  (+ outcome-asset-id-base (outcome-encoding outcome side)))

(defn- split-description-field
  [field]
  (let [[k v] (str/split (str field) #":" 2)]
    (when (seq k)
      [(str/trim k) (some-> v str/trim)])))

(defn- parse-outcome-expiry-ms
  [expiry]
  (when-let [[_ yyyy mm dd hh minute] (and (string? expiry)
                                           (re-matches #"(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})" expiry))]
    (js/Date.UTC (js/parseInt yyyy 10)
                 (dec (js/parseInt mm 10))
                 (js/parseInt dd 10)
                 (js/parseInt hh 10)
                 (js/parseInt minute 10))))

(defn parse-outcome-description
  [description]
  (let [fields (->> (str/split (str (or description "")) #"\|")
                    (keep split-description-field))
        known (into {} fields)
        extra-fields (into {}
                           (remove (fn [[k _]]
                                     (contains? #{"class" "underlying" "expiry" "targetPrice" "priceThresholds" "period"} k)))
                           fields)
        expiry (get known "expiry")
        price-thresholds (->> (str/split (str (or (get known "priceThresholds") "")) #",")
                              (map str/trim)
                              (filter seq)
                              vec)]
    (cond-> {:raw-description description
             :outcome-class (get known "class")
             :underlying (get known "underlying")
             :expiry expiry
             :expiry-ms (parse-outcome-expiry-ms expiry)
             :target-price (get known "targetPrice")
             :period (get known "period")
             :extra-fields extra-fields}
      (seq price-thresholds) (assoc :price-thresholds price-thresholds)
      (nil? description) (assoc :raw-description ""))))

(defn parse-outcome-metadata
  [description]
  (let [text (str (or description ""))
        marker "metadata="
        marker-idx (.lastIndexOf text marker)
        has-metadata? (>= marker-idx 0)
        display-description (if has-metadata?
                              (str/trim (subs text 0 marker-idx))
                              text)
        metadata-text (when has-metadata?
                        (subs text (+ marker-idx (count marker))))
        metadata-pairs (->> (str/split (str (or metadata-text "")) #"\|")
                            (keep split-description-field))
        metadata-map (->> metadata-pairs
                          (take 16)
                          (map (fn [[k v]]
                                 [(-> k str/trim str/lower-case keyword)
                                  (some-> v str/trim)]))
                          (into {}))
        category (some-> (:category metadata-map) str/lower-case keyword)
        subcategory (some-> (:subcategory metadata-map) str/lower-case keyword)]
    (cond-> {:display-description display-description
             :extra-metadata metadata-map}
      category (assoc :outcome-category category)
      subcategory (assoc :outcome-subcategory subcategory))))

(def ^:private local-month-names
  ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn- twelve-hour-label
  [hour]
  (let [hour* (mod hour 24)
        hour12 (mod hour* 12)]
    (str (if (zero? hour12) 12 hour12)
         ":00 "
         (if (< hour* 12) "AM" "PM"))))

(defn- outcome-title
  [{:keys [underlying target-price expiry-ms]}]
  (let [date (js/Date. expiry-ms)]
    (str underlying
         " above "
         target-price
         " on "
         (get local-month-names (.getMonth date))
         " "
         (.getDate date)
         " at "
         (twelve-hour-label (.getHours date))
         "?")))

(def ^:private utc-month-names
  ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn- pad2
  [value]
  (let [text (str value)]
    (if (= 1 (count text)) (str "0" text) text)))

(defn- utc-expiry-label
  [expiry-ms]
  (let [date (js/Date. expiry-ms)]
    (str (get utc-month-names (.getUTCMonth date))
         " "
         (pad2 (.getUTCDate date))
         ", "
         (.getUTCFullYear date)
         " "
         (pad2 (.getUTCHours date))
         ":"
         (pad2 (.getUTCMinutes date))
         " UTC")))

(defn- outcome-details-copy
  [{:keys [underlying target-price expiry-ms]}]
  (str "If the "
       underlying
       " mark price at time of settlement is above "
       target-price
       " at "
       (utc-expiry-label expiry-ms)
       ", YES tokens pay out $1 each. Otherwise, NO tokens pay out $1 each."))

(defn- outcome-price-bucket-title
  [{:keys [underlying expiry-ms]}]
  (let [date (js/Date. expiry-ms)]
    (str underlying
         " price range on "
         (get local-month-names (.getMonth date))
         " "
         (.getDate date)
         " at "
         (twelve-hour-label (.getHours date))
         "?")))

(defn- outcome-price-bucket-details-copy
  [{:keys [underlying price-thresholds expiry-ms]}]
  (str "If the "
       underlying
       " mark price at time of settlement is in the selected range at "
       (utc-expiry-label expiry-ms)
       ", that range's YES tokens pay out $1 each. Thresholds: "
       (str/join ", " price-thresholds)
       "."))

(defn- outcome-ctx-by-coin
  [spot-asset-ctxs]
  (into {}
        (keep (fn [{:keys [coin] :as ctx}]
                (when (outcome-side-coin? coin)
                  [coin ctx])))
        (or spot-asset-ctxs [])))

(defn- outcome-side-name
  [side-index side-spec]
  (or (:name side-spec)
      (:sideName side-spec)
      (case side-index
        0 "Yes"
        1 "No"
        (str "Side " side-index))))

(defn- outcome-side-stats
  [ctx]
  (let [mark-raw (:markPx ctx)
        mid-raw (:midPx ctx)
        prev-raw (:prevDayPx ctx)
        mark (safe-num mark-raw)
        prev (safe-num prev-raw)]
    {:mark mark
     :markRaw mark-raw
     :mid (safe-num mid-raw)
     :midRaw mid-raw
     :prevDayRaw prev-raw
     :change24h (- mark prev)
     :change24hPct (pct-change mark prev)
     :volume24h (safe-num (:dayNtlVlm ctx))
     :baseVolume24h (safe-num (:dayBaseVlm ctx))
     :circulatingSupply (safe-num (:circulatingSupply ctx))}))

(defn- build-outcome-side
  [outcome-id side-index side-spec ctx]
  (merge {:side-index side-index
          :side-name (outcome-side-name side-index side-spec)
          :coin (outcome-coin outcome-id side-index)
          :asset-id (outcome-asset-id outcome-id side-index)
          :szDecimals 0}
         (outcome-side-stats ctx)))

(defn- outcome-entry-description
  [entry]
  (or (:description entry)
      (:encodedDescription entry)
      (:encoded-description entry)))

(defn- outcome-entry-id
  [idx entry]
  (or (parse-int-value (:outcome entry))
      (parse-int-value (:outcomeId entry))
      idx))

(defn- outcome-entry-name
  [entry]
  (or (:name entry)
      (:title entry)
      (:symbol entry)))

(defn- quote-token
  [entry]
  (or (:quoteToken entry)
      (:quote-token entry)
      "USDH"))

(defn- outcome-category
  [name parsed metadata]
  (let [metadata-category (:outcome-category metadata)
        name-token (some-> name str/lower-case)]
    (cond
      metadata-category metadata-category
      (= "priceBinary" (:outcome-class parsed)) :crypto
      (= "priceBucket" (:outcome-class parsed)) :crypto
      (and name-token (re-find #"\b(cpi|fed)\b" name-token)) :economics
      :else :uncategorized)))

(defn- first-positive-number
  [values]
  (some (fn [value]
          (when (and (number? value)
                     (pos? value))
            value))
        values))

(defn- sum-numbers
  [values]
  (reduce + 0 (filter number? values)))

(defn- build-outcome-sides
  [ctx-by-coin outcome-id side-specs]
  (->> side-specs
       (map-indexed (fn [side-index side-spec]
                      (let [coin (outcome-coin outcome-id side-index)]
                        (build-outcome-side outcome-id
                                            side-index
                                            side-spec
                                            (get ctx-by-coin coin)))))
       vec))

(defn- outcome-side-aliases-for-binary
  [outcome-id sides]
  (let [outcome-key (str "outcome:" outcome-id)]
    (reduce (fn [aliases side]
              (cond-> aliases
                (seq (:coin side))
                (assoc (:coin side)
                       {:outcome-id outcome-id
                        :side-index (:side-index side)
                        :sibling-coins (mapv :coin sides)})

                (zero? (or (:side-index side) 0))
                (assoc outcome-key
                       {:outcome-id outcome-id
                        :side-index 0
                        :sibling-coins (mapv :coin sides)})))
            {}
            sides)))

(def ^:private generic-binary-side-labels
  #{"yes" "no"})

(defn- generic-binary-side-label?
  [label]
  (contains? generic-binary-side-labels
             (some-> label str str/trim str/lower-case)))

(defn- side-summary-label
  [side]
  (or (:side-label side)
      (:side-name side)))

(defn- outcome-side-summary
  [outcome-category sides]
  (let [sides* (vec (or sides []))
        labels (mapv side-summary-label sides*)]
    (when (and (= :sports outcome-category)
               (> (count sides*) 1)
               (every? seq labels)
               (some (complement generic-binary-side-label?) labels))
      (->> sides*
           (map (fn [side]
                  (let [label (side-summary-label side)
                        mark (:mark side)]
                    (str label
                         " "
                         (if (number? mark)
                           (str (js/Math.round (* mark 100)) "%")
                           "--")))))
           (str/join "  *  ")))))

(defn- build-outcome-spec
  [ctx-by-coin idx entry]
  (let [outcome-id (outcome-entry-id idx entry)
        parsed (parse-outcome-description (outcome-entry-description entry))
        metadata (parse-outcome-metadata (outcome-entry-description entry))
        side-specs (vec (or (:sideSpecs entry)
                            (:side-specs entry)
                            [{:name "Yes"} {:name "No"}]))
        sides (build-outcome-sides ctx-by-coin outcome-id side-specs)
        name (outcome-entry-name entry)]
    (merge parsed
           metadata
           {:entry entry
            :outcome-id outcome-id
            :name name
            :quote (quote-token entry)
            :display-description (:display-description metadata)
            :sides sides})))

(defn- build-binary-outcome-market-entry
  [spec]
  (let [outcome-id (:outcome-id spec)
        parsed spec
        sides (:sides spec)
        yes-side (or (first sides) {})
        price-binary? (= "priceBinary" (:outcome-class parsed))
        title (if price-binary?
                (outcome-title parsed)
                (or (:name spec) (:display-description spec) (str "Outcome " outcome-id)))
        open-interest (some->> sides
                               (map :circulatingSupply)
                               (filter pos?)
                               first)
        details (if price-binary?
                  (outcome-details-copy parsed)
                  (or (:display-description spec) ""))
        outcome-category* (outcome-category title parsed spec)
        side-summary (outcome-side-summary outcome-category* sides)]
    (merge parsed
           (cond-> {:key (str "outcome:" outcome-id)
                    :coin (:coin yes-side)
                    :symbol title
                    :title title
                    :base (:underlying parsed)
                    :quote (:quote spec)
                    :market-type :outcome
                    :category :outcome
                    :hip3? false
                    :hip3-eligible? false
                    :outcome-kind :binary
                    :outcome-category outcome-category*
                    :outcome-subcategory (:outcome-subcategory spec)
                    :outcome-id outcome-id
                    :outcome-sides sides
                    :outcome-side-aliases (outcome-side-aliases-for-binary outcome-id sides)
                    :outcome-subscription-coins (mapv :coin sides)
                    :asset-id (:asset-id yes-side)
                    :szDecimals 0
                    :mark (:mark yes-side)
                    :markRaw (:markRaw yes-side)
                    :volume24h (:volume24h yes-side)
                    :change24h (:change24h yes-side)
                    :change24hPct (:change24hPct yes-side)
                    :prevDayRaw (:prevDayRaw yes-side)
                    :openInterest open-interest
                    :fundingRate nil
                    :maxLeverage nil
                    :outcome-details details}
             (seq side-summary) (assoc :outcome-summary side-summary)))))

(defn- question-entry-id
  [idx entry]
  (or (parse-int-value (:question entry))
      (parse-int-value (:questionId entry))
      idx))

(defn- question-fallback-outcome-id
  [entry]
  (or (parse-int-value (:fallbackOutcome entry))
      (parse-int-value (:fallback-outcome entry))))

(defn- question-named-outcome-ids
  [entry]
  (->> (or (:namedOutcomes entry)
           (:named-outcomes entry)
           [])
       (keep parse-int-value)
       vec))

(defn- price-bucket-option-labels
  [thresholds]
  (let [thresholds* (vec thresholds)
        threshold-count (count thresholds*)]
    (cond
      (zero? threshold-count) []
      (= 1 threshold-count) [(str "Below " (first thresholds*))
                             (str "Above " (first thresholds*))]
      :else (vec (concat [(str "Below " (first thresholds*))]
                         (map (fn [[lo hi]]
                                (str lo " to " hi))
                              (partition 2 1 thresholds*))
                         [(str "Above " (last thresholds*))])))))

(defn- question-option-labels
  [question-spec outcome-specs]
  (let [parsed question-spec
        named-ids (:named-outcome-ids question-spec)
        bucket-labels (when (= "priceBucket" (:outcome-class parsed))
                        (price-bucket-option-labels (:price-thresholds parsed)))]
    (mapv (fn [idx outcome-id]
            (or (nth bucket-labels idx nil)
                (:name (get outcome-specs outcome-id))
                (str "Outcome " outcome-id)))
          (range)
          named-ids)))

(defn- option-side
  [option-label outcome-id side]
  (assoc side
         :outcome-id outcome-id
         :outcome-option-label option-label
         :side-label (:side-name side)))

(defn- build-question-option
  [outcome-spec option-label]
  (let [outcome-id (:outcome-id outcome-spec)
        sides (mapv #(option-side option-label outcome-id %) (:sides outcome-spec))
        yes-side (first sides)
        no-side (second sides)]
    {:label option-label
     :outcome-id outcome-id
     :yes-coin (:coin yes-side)
     :yes-asset-id (:asset-id yes-side)
     :no-coin (:coin no-side)
     :no-asset-id (:asset-id no-side)
     :mark (:mark yes-side)
     :volume24h (:volume24h yes-side)
     :openInterest (:circulatingSupply yes-side)
     :sides sides}))

(defn- best-question-option
  [options]
  (first (sort-by (fn [option]
                    (- (safe-num (:mark option))))
                  options)))

(def ^:private outcome-summary-option-limit
  3)

(defn- option-summary-options
  [options]
  (let [options* (vec (or options []))]
    (if (> (count options*) outcome-summary-option-limit)
      (->> options*
           (sort-by (fn [{:keys [label mark volume24h outcome-id]}]
                      [(- (safe-num mark))
                       (- (safe-num volume24h))
                       (some-> label str str/lower-case)
                       (or (parse-int-value outcome-id) js/Number.MAX_SAFE_INTEGER)]))
           (take outcome-summary-option-limit)
           vec)
      options*)))

(defn outcome-option-summary
  [options]
  (->> (option-summary-options options)
       (map (fn [{:keys [label mark]}]
              (str label
                   " "
                   (if (number? mark)
                     (str (js/Math.round (* mark 100)) "%")
                     "--"))))
       (str/join "  *  ")))

(defn- question-side-aliases
  [question-key options]
  (reduce (fn [aliases {:keys [outcome-id sides] :as option}]
            (let [sibling-coins (mapv :coin sides)]
              (-> (reduce (fn [aliases* side]
                            (assoc aliases*
                                   (:coin side)
                                   {:outcome-id outcome-id
                                    :side-index (:side-index side)
                                    :option-label (:label option)
                                    :sibling-coins sibling-coins}))
                          aliases
                          sides)
                  (assoc (str "outcome:" outcome-id)
                         {:outcome-id outcome-id
                          :side-index 0
                          :option-label (:label option)
                          :sibling-coins sibling-coins})
                  (assoc question-key
                         {:question? true}))))
          {}
          options))

(defn- selected-option-sides
  [selected-option]
  (vec (or (:sides selected-option) [])))

(defn- build-question-market-entry
  [question-spec outcome-specs]
  (let [question-id (:question-id question-spec)
        question-key (str "question:" question-id)
        title (if (= "priceBucket" (:outcome-class question-spec))
                (outcome-price-bucket-title question-spec)
                (:name question-spec))
        option-labels (question-option-labels question-spec outcome-specs)
        options (->> (:named-outcome-ids question-spec)
                     (map vector option-labels)
                     (keep (fn [[option-label outcome-id]]
                             (when-let [outcome-spec (get outcome-specs outcome-id)]
                               (build-question-option outcome-spec option-label))))
                     vec)
        selected-option (or (best-question-option options)
                            (first options))
        selected-sides (selected-option-sides selected-option)
        yes-side (first selected-sides)
        subscription-coins (->> options
                                (mapcat (comp (partial map :coin) :sides))
                                (filter string?)
                                distinct
                                vec)
        details (if (= "priceBucket" (:outcome-class question-spec))
                  (outcome-price-bucket-details-copy question-spec)
                  (or (:display-description question-spec) ""))
        volume24h (sum-numbers (map :volume24h options))
        open-interest (or (first-positive-number (map :openInterest options))
                          (sum-numbers (map :openInterest options)))]
    (merge question-spec
           {:key question-key
            :coin (:coin yes-side)
            :symbol title
            :title title
            :base (:underlying question-spec)
            :quote (:quote question-spec)
            :market-type :outcome
            :category :outcome
            :hip3? false
            :hip3-eligible? false
            :outcome-kind :question
            :outcome-category (outcome-category title question-spec question-spec)
            :outcome-subcategory (:outcome-subcategory question-spec)
            :question-id question-id
            :fallback-outcome-id (:fallback-outcome-id question-spec)
            :named-outcome-ids (:named-outcome-ids question-spec)
            :question-options options
            :outcome-sides selected-sides
            :outcome-side-aliases (question-side-aliases question-key options)
            :outcome-subscription-coins subscription-coins
            :selected-outcome-id (:outcome-id selected-option)
            :selected-outcome-option-label (:label selected-option)
            :outcome-option-id (:outcome-id selected-option)
            :asset-id (:asset-id yes-side)
            :szDecimals 0
            :mark (:mark yes-side)
            :markRaw (:markRaw yes-side)
            :volume24h volume24h
            :change24h (:change24h yes-side)
            :change24hPct (:change24hPct yes-side)
            :prevDayRaw (:prevDayRaw yes-side)
            :openInterest open-interest
            :fundingRate nil
            :maxLeverage nil
            :outcome-summary (outcome-option-summary options)
            :outcome-details details})))

(defn outcome-subscription-coins
  [market]
  (vec (or (seq (:outcome-subscription-coins market))
           (seq (keep :coin (:outcome-sides market)))
           (when (string? (:coin market))
             [(:coin market)]))))

(defn market-with-selected-outcome-coin
  [market coin]
  (let [coin* (some-> coin str str/trim)]
    (if-not (and (= :outcome (:market-type market))
                 (seq coin*))
      market
      (let [alias (get (:outcome-side-aliases market) coin*)
            binary-side (some (fn [side]
                                (when (= coin* (:coin side))
                                  side))
                              (:outcome-sides market))
            selected-option (when-let [outcome-id (:outcome-id alias)]
                              (some (fn [option]
                                      (when (= outcome-id (:outcome-id option))
                                        option))
                                    (:question-options market)))
            selected-sides (or (selected-option-sides selected-option)
                               (when-let [coins (seq (:sibling-coins alias))]
                                 (mapv (fn [coin idx]
                                         {:coin coin
                                          :side-index idx
                                          :outcome-id (:outcome-id alias)
                                          :side-label (case idx
                                                        0 "Yes"
                                                        1 "No"
                                                        (str "Side " idx))})
                                       coins
                                       (range)))
                               (:outcome-sides market))
            subscription-coin? (some #(= coin* %) (outcome-subscription-coins market))
            side-index (or (:side-index alias)
                           (:side-index binary-side)
                           0)
            question-selection? (or selected-option
                                    (= :question (:outcome-kind market)))
            selected-side (or (some (fn [side]
                                      (when (= side-index (:side-index side))
                                        side))
                                    selected-sides)
                              binary-side
                              (when (:coin alias)
                                {:coin (:coin alias)
                                 :side-index side-index
                                 :outcome-id (:outcome-id alias)
                                 :side-label (case side-index
                                               0 "Yes"
                                               1 "No"
                                               (str "Side " side-index))})
                              (when subscription-coin?
                                {:coin coin*
                                 :side-index side-index})
                              (first selected-sides))]
        (cond-> market
          (seq selected-sides) (assoc :outcome-sides (vec selected-sides))
          selected-option (assoc :selected-outcome-id (:outcome-id selected-option)
                                 :selected-outcome-option-label (:label selected-option)
                                 :outcome-option-id (:outcome-id selected-option))
          (:coin selected-side) (assoc :coin (:coin selected-side))
          (some? (:asset-id selected-side)) (assoc :asset-id (:asset-id selected-side))
          (some? (:mark selected-side)) (assoc :mark (:mark selected-side))
          (some? (:markRaw selected-side)) (assoc :markRaw (:markRaw selected-side))
          (some? (:volume24h selected-side)) (assoc :volume24h (:volume24h selected-side))
          (some? (:change24h selected-side)) (assoc :change24h (:change24h selected-side))
          (some? (:change24hPct selected-side)) (assoc :change24hPct (:change24hPct selected-side))
          (some? (:prevDayRaw selected-side)) (assoc :prevDayRaw (:prevDayRaw selected-side))
          (and question-selection? (some? (:outcome-id selected-side)))
          (assoc :outcome-id (:outcome-id selected-side))
          (some? (:side-index selected-side))
          (assoc :outcome-side-index (:side-index selected-side))
          (and question-selection? (:side-label selected-side))
          (assoc :outcome-side-label (:side-label selected-side)))))))

(defn- build-question-spec
  [idx entry]
  (let [question-id (question-entry-id idx entry)
        parsed (parse-outcome-description (outcome-entry-description entry))
        metadata (parse-outcome-metadata (outcome-entry-description entry))]
    (merge parsed
           metadata
           {:entry entry
            :question-id question-id
            :name (outcome-entry-name entry)
            :quote (quote-token entry)
            :display-description (:display-description metadata)
            :fallback-outcome-id (question-fallback-outcome-id entry)
            :named-outcome-ids (question-named-outcome-ids entry)})))

(defn- outcome-meta-graph
  [outcome-meta ctx-by-coin]
  (let [outcome-specs (->> (:outcomes outcome-meta)
                           (map-indexed #(build-outcome-spec ctx-by-coin %1 %2))
                           (map (juxt :outcome-id identity))
                           (into {}))
        question-specs (->> (:questions outcome-meta)
                            (map-indexed build-question-spec)
                            (map (juxt :question-id identity))
                            (into {}))
        outcome-id->question-id (reduce-kv
                                 (fn [lookup question-id question-spec]
                                   (reduce (fn [lookup* outcome-id]
                                             (assoc lookup* outcome-id question-id))
                                           lookup
                                           (conj (:named-outcome-ids question-spec)
                                                 (:fallback-outcome-id question-spec))))
                                 {}
                                 question-specs)
        fallback-outcome-ids (->> (vals question-specs)
                                  (keep :fallback-outcome-id)
                                  set)]
    {:outcome-spec-by-id outcome-specs
     :question-spec-by-id question-specs
     :outcome-id->question-id outcome-id->question-id
     :fallback-outcome-ids fallback-outcome-ids}))

(defn build-outcome-markets
  "Build normalized outcome question markets from outcomeMeta and webData2 spot asset contexts."
  [outcome-meta spot-asset-ctxs]
  (let [ctx-by-coin (outcome-ctx-by-coin spot-asset-ctxs)
        {:keys [outcome-spec-by-id question-spec-by-id outcome-id->question-id fallback-outcome-ids]
         :as graph} (outcome-meta-graph outcome-meta ctx-by-coin)
        question-markets (->> (vals question-spec-by-id)
                              (keep #(build-question-market-entry % outcome-spec-by-id)))
        binary-markets (->> (vals outcome-spec-by-id)
                            (remove (fn [{:keys [outcome-id]}]
                                      (or (contains? fallback-outcome-ids outcome-id)
                                          (contains? outcome-id->question-id outcome-id))))
                            (map build-binary-outcome-market-entry))]
    (->> (concat question-markets binary-markets)
         (map #(assoc %
                      :outcome-spec-by-id outcome-spec-by-id
                      :question-spec-by-id question-spec-by-id
                      :outcome-id->question-id outcome-id->question-id
                      :fallback-outcome-ids fallback-outcome-ids
                      :outcome-meta-signature [(sort (keys outcome-spec-by-id))
                                               (sort (keys question-spec-by-id))
                                               (sort fallback-outcome-ids)]))
         vec)))

(defn classify-market
  "Assign :category and :hip3? based on market type and dex." 
  [market]
  (cond
    (= :outcome (:market-type market))
    (assoc market :category :outcome :hip3? false :hip3-eligible? false)

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

(defn- perp-market-stats
  [ctx]
  (let [mark-raw (:markPx ctx)
        prev-raw (:prevDayPx ctx)
        mark (safe-num mark-raw)
        prev (safe-num prev-raw)
        open-interest (safe-num (:openInterest ctx))]
    {:mark mark
     :markRaw mark-raw
     :volume24h (safe-num (:dayNtlVlm ctx))
     :change24h (- mark prev)
     :change24hPct (pct-change mark prev)
     :prevDayRaw prev-raw
     :openInterest (fmt/calculate-open-interest-usd open-interest mark)
     :fundingRate (safe-num (:funding ctx))}))

(defn- perp-market-flags
  [info]
  (let [only-isolated? (parse-optional-boolean
                        (or (:only-isolated? info)
                            (:onlyIsolated info)))
        margin-mode (normalize-margin-mode
                     (or (:margin-mode info)
                         (:marginMode info)))]
    (merge {:growth-mode? (growth-mode-enabled? (:growthMode info))
            :delisted? (boolean (:isDelisted info))}
           (when (some? only-isolated?)
             {:only-isolated? only-isolated?})
           (when margin-mode
             {:margin-mode margin-mode}))))

(defn- build-perp-market-entry
  [quote default-dex perp-dex-index idx info ctx]
  (when ctx
    (let [{:keys [name maxLeverage szDecimals]} info
          {:keys [base coin dex]} (parse-perp-name name)
          dex-name (or default-dex dex)]
      (classify-market
       (merge {:key (perp-market-key coin)
               :coin coin
               :symbol (str base "-" quote)
               :base base
               :quote quote
               :market-type :perp
               :dex dex-name
               :idx idx
               :perp-dex-index perp-dex-index
               :asset-id (perp-asset-id perp-dex-index idx)
               :szDecimals szDecimals
               :maxLeverage maxLeverage}
              (perp-market-stats ctx)
              (perp-market-flags info))))))

(defn build-perp-markets
  "Build normalized perp market entries from metaAndAssetCtxs data.
   Accepts the meta map, asset ctxs vector, a token index->symbol map,
   and optional dex name (string)."
  [meta asset-ctxs collateral-token->symbol & {:keys [dex perp-dex-index]}]
  (let [quote (or (get collateral-token->symbol (:collateralToken meta)) "USDC")
        ctxs (vec (or asset-ctxs []))
        resolved-perp-dex-index (canonical-perp-dex-index meta dex perp-dex-index)]
    (->> (or (:universe meta) [])
         (keep-indexed (fn [idx info]
                         (build-perp-market-entry quote dex resolved-perp-dex-index
                                                  idx info (nth ctxs idx nil))))
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
          ;; :idx stays the raw spotMeta pair index (token-list / asset-context
          ;; lookup key); :asset-id carries the offset wire id used for orders.
          :idx idx
          :asset-id (spot-asset-id idx)
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
