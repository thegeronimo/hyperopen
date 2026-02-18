(ns hyperopen.views.account-info.projections.balances
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.views.account-info.projections.coins :as coins]
            [hyperopen.views.account-info.projections.parse :as parse]))

(def parse-num parse/parse-num)
(def parse-optional-num parse/parse-optional-num)
(def parse-optional-int parse/parse-optional-int)

(def ^:private balance-contract-id-pattern
  #"^(?:0[xX][0-9a-fA-F]{8,}|[0-9A-Za-z]{10,})$")

(def ^:private embedded-hex-contract-id-pattern
  #"0[xX][0-9a-fA-F]{8,}")

(defn normalize-balance-contract-id [contract-id]
  (let [contract-id* (some-> contract-id str str/trim)]
    (cond
      (not (seq contract-id*)) nil
      (re-matches balance-contract-id-pattern contract-id*) contract-id*
      :else (some->> (re-find embedded-hex-contract-id-pattern contract-id*)
                     str))))

(def ^:private balance-contract-id-candidate-keys
  [:tokenId :token-id :token_id
   :tokenAddress :token-address :token_address
   :contractId :contract-id :contract_id
   :contractAddress :contract-address :contract_address
   :erc20Address :erc20-address :erc20_address
   :evmAddress :evm-address :evm_address
   :evmContract :evm-contract :evm_contract
   :tokenInfo :token-info :token_info
   :tokenMetadata :token-metadata :token_metadata
   :metadata :meta :details])

(def ^:private non-contract-address-key-exact
  #{"user" "wallet" "owner" "account" "maker" "taker" "from" "to"})

(def ^:private contract-context-key-fragments
  ["contract" "token" "erc20" "evm"])

(def ^:private contract-wrapper-key-exact
  #{"metadata" "meta" "details"})

(defn- contract-context-key? [k]
  (let [key-name (some-> k name str/lower-case)]
    (and (seq key-name)
         (not (contains? non-contract-address-key-exact key-name))
         (boolean (some #(str/includes? key-name %)
                        contract-context-key-fragments)))))

(defn- contract-wrapper-key? [k]
  (contains? contract-wrapper-key-exact
             (some-> k name str/lower-case)))

(defn- map-entry-contract-key? [k]
  (or (contract-context-key? k)
      (contract-wrapper-key? k)))

(defn- extract-balance-contract-id [value]
  (letfn [(address-field-value [node]
            (some (fn [[k v]]
                    (when (= "address" (some-> k name str/lower-case))
                      v))
                  node))
          (walk [node depth contract-context?]
            (when (and (some? node) (<= depth 4))
              (cond
                (map? node)
                (or (when contract-context?
                      (walk (address-field-value node) (inc depth) true))
                    (some (fn [k]
                            (walk (get node k)
                                  (inc depth)
                                  (or contract-context?
                                      (contract-context-key? k))))
                          balance-contract-id-candidate-keys)
                    (some (fn [[k v]]
                            (when (map-entry-contract-key? k)
                              (walk v
                                    (inc depth)
                                    (or contract-context?
                                        (contract-context-key? k)))))
                          node))

                (sequential? node)
                (some #(walk % (inc depth) contract-context?) node)

                :else
                (normalize-balance-contract-id node))))]
    (walk value 0 false)))

(defn- parse-spot-token-index [token]
  (cond
    (number? token) (js/Math.floor token)
    (string? token) (let [token* (str/trim token)
                          token* (if (str/starts-with? token* "@")
                                   (subs token* 1)
                                   token*)
                          parsed (js/parseInt token* 10)]
                      (when (and (seq token*) (not (js/isNaN parsed)))
                        parsed))
    :else nil))

(defn- token-ref-candidates [token coin]
  (let [token* (some-> token str str/trim)
        coin* (some-> coin str str/trim)]
    (->> [token
          token*
          coin
          coin*
          (some-> coin* str/upper-case)]
         (remove nil?))))

(defn- build-token-by-ref [tokens]
  (reduce (fn [acc {:keys [index name] :as token}]
            (let [name* (some-> name str str/trim)
                  contract-id (extract-balance-contract-id token)]
              (cond-> acc
                (some? index) (assoc index token)
                (some? index) (assoc (str index) token)
                (seq name*) (assoc name* token)
                (seq name*) (assoc (str/upper-case name*) token)
                (seq contract-id) (assoc contract-id token))))
          {}
          (or tokens [])))

(defn- resolve-token-meta [token-by-index token-by-ref token coin]
  (let [token-idx (parse-spot-token-index token)]
    (or (get token-by-index token-idx)
        (some #(get token-by-ref %)
              (token-ref-candidates token coin)))))

(defn- unified-account-mode? [account]
  (= :unified (:mode account)))

(defn- usdc-coin? [coin]
  (let [coin* (some-> coin str str/trim str/upper-case)]
    (and (seq coin*)
         (str/starts-with? coin* "USDC"))))

(defn- normalize-coin-code [coin]
  (some-> coin str str/trim str/upper-case))

(def ^:private usdc-valuation-invariant-epsilon 0.000001)

(defn- usdc-token-idx [spot-meta]
  (some->> (:tokens spot-meta)
           (some (fn [{:keys [name index]}]
                   (when (= "USDC" (some-> name str str/trim str/upper-case))
                     index)))))

(defn- spot-market-mark-px [market-by-key spot-coin]
  (let [market (or (get market-by-key (str "spot:" spot-coin))
                   (markets/resolve-market-by-coin market-by-key spot-coin))]
    (or (let [mark (parse-optional-num (:mark market))]
          (when (and (number? mark) (pos? mark))
            mark))
        (let [mark-raw (parse-optional-num (:markRaw market))]
          (when (and (number? mark-raw) (pos? mark-raw))
            mark-raw)))))

(defn- market-positive-mark [market]
  (or (let [mark (parse-optional-num (:mark market))]
        (when (and (number? mark) (pos? mark))
          mark))
      (let [mark-raw (parse-optional-num (:markRaw market))]
        (when (and (number? mark-raw) (pos? mark-raw))
          mark-raw))))

(defn- spot-market-base-coin [market]
  (or (normalize-coin-code (:base market))
      (normalize-coin-code (coins/symbol-base-label (:symbol market)))
      (normalize-coin-code (some-> (coins/parse-coin-namespace (:coin market))
                                   :base))))

(defn- spot-market-quote-coin [market]
  (or (normalize-coin-code (:quote market))
      (let [symbol* (some-> (:symbol market) str str/trim)]
        (when (and (seq symbol*) (str/includes? symbol* "/"))
          (normalize-coin-code (second (str/split symbol* #"/" 2)))))))

(defn- build-coin-usdc-price-map-from-market-state [market-by-key]
  (if (map? market-by-key)
    (reduce (fn [m market]
              (if (= :spot (:market-type market))
                (let [base-coin (spot-market-base-coin market)
                      quote-coin (spot-market-quote-coin market)
                      mark-px (market-positive-mark market)]
                  (cond
                    (and (seq base-coin)
                         (usdc-coin? quote-coin)
                         (number? mark-px)
                         (pos? mark-px))
                    (assoc m base-coin mark-px)

                    (and (seq quote-coin)
                         (usdc-coin? base-coin)
                         (number? mark-px)
                         (pos? mark-px))
                    (assoc m quote-coin (/ 1 mark-px))

                    :else m))
                m))
            {}
            (vals market-by-key))
    {}))

(defn- build-coin-decimals-map-from-market-state [market-by-key]
  (if (map? market-by-key)
    (reduce (fn [m market]
              (if (= :spot (:market-type market))
                (let [base-coin (spot-market-base-coin market)
                      decimals (parse-optional-int (:szDecimals market))]
                  (if (and (seq base-coin) (number? decimals))
                    (assoc m base-coin decimals)
                    m))
                m))
            {}
            (vals market-by-key))
    {}))

(defn- build-token-usdc-price-map-from-spot-ctxs [spot-meta spot-asset-ctxs]
  (let [usdc-token-idx (usdc-token-idx spot-meta)
        base-prices (if (some? usdc-token-idx)
                      {usdc-token-idx 1}
                      {})]
    (if (and (some? usdc-token-idx)
             (seq (:universe spot-meta))
             (seq spot-asset-ctxs))
      (let [ctxs (vec spot-asset-ctxs)]
        (reduce (fn [m {:keys [tokens index]}]
                  (let [[base quote] tokens
                        ctx (nth ctxs index nil)
                        mark-px (parse-optional-num (:markPx ctx))]
                    (cond
                      (and (= quote usdc-token-idx) (number? mark-px) (pos? mark-px))
                      (assoc m base mark-px)

                      (and (= base usdc-token-idx) (number? mark-px) (pos? mark-px))
                      (assoc m quote (/ 1 mark-px))

                      :else m)))
                base-prices
                (:universe spot-meta)))
      base-prices)))

(defn- build-token-usdc-price-map-from-market-state [spot-meta market-by-key]
  (let [usdc-token-idx (usdc-token-idx spot-meta)
        base-prices (if (some? usdc-token-idx)
                      {usdc-token-idx 1}
                      {})]
    (if (and (some? usdc-token-idx)
             (seq (:universe spot-meta))
             (map? market-by-key))
      (reduce (fn [m {:keys [name tokens]}]
                (let [[base quote] tokens
                      mark-px (spot-market-mark-px market-by-key name)]
                  (cond
                    (and (= quote usdc-token-idx) (number? mark-px) (pos? mark-px))
                    (assoc m base mark-px)

                    (and (= base usdc-token-idx) (number? mark-px) (pos? mark-px))
                    (assoc m quote (/ 1 mark-px))

                    :else m)))
              base-prices
              (:universe spot-meta))
      base-prices)))

(defn- build-token-usdc-price-map [spot-meta spot-asset-ctxs market-by-key]
  (let [market-state-prices (build-token-usdc-price-map-from-market-state spot-meta market-by-key)
        ctx-prices (build-token-usdc-price-map-from-spot-ctxs spot-meta spot-asset-ctxs)]
    ;; Prefer direct spotAssetCtxs when available; fall back to market projection prices.
    (merge market-state-prices ctx-prices)))

(defn- spot-token-usdc-price [price-by-token token-idx coin]
  (or (get price-by-token token-idx)
      (when (usdc-coin? coin) 1)))

(defn- spot-coin-usdc-price [price-by-coin coin]
  (let [coin-key (normalize-coin-code coin)
        base-key (normalize-coin-code (some-> (coins/parse-coin-namespace coin)
                                              :base))]
    (or (get price-by-coin coin-key)
        (get price-by-coin base-key)
        (when (usdc-coin? coin) 1))))

(defn- maybe-warn-usdc-valuation-invariant! [coin total-num usdc-value]
  (when (and ^boolean goog.DEBUG
             (usdc-coin? coin)
             (pos? total-num)
             (> (js/Math.abs (- usdc-value total-num))
                usdc-valuation-invariant-epsilon))
    (js/console.warn "USDC valuation invariant breach in balance row."
                     (clj->js {:coin coin
                               :total-balance total-num
                               :usdc-value usdc-value}))))

(defn- normalize-spot-token-index [token]
  (parse-spot-token-index token))

(def ^:private spot-entry-notional-keys
  [:entryNtl :entryNotional :entryNtlUsd :entryNtlUSDC :entryValue :entry])

(defn- spot-entry-notional [balance]
  (some (fn [k]
          (let [value (get balance k)
                parsed (parse-optional-num value)]
            (when (number? parsed)
              parsed)))
        spot-entry-notional-keys))

(defn- spot-balance-valuation [price-by-token price-by-coin coin token total]
  (let [token-idx (normalize-spot-token-index token)
        total-num (parse-num total)
        price (or (spot-token-usdc-price price-by-token token-idx coin)
                  (spot-coin-usdc-price price-by-coin coin))
        usdc-value (when (number? price)
                     (* total-num price))]
    (when (number? usdc-value)
      (maybe-warn-usdc-valuation-invariant! coin total-num usdc-value))
    {:token-idx token-idx
     :total-num total-num
     :price price
     :usdc-value usdc-value}))

(defn portfolio-usdc-value [balance-rows]
  (when (seq balance-rows)
    (reduce + (map #(parse-num (:usdc-value %)) balance-rows))))

(defn- non-zero-balance-row? [row]
  (let [total-balance (parse-num (:total-balance row))
        available-balance (parse-num (:available-balance row))
        usdc-value (parse-num (:usdc-value row))
        pnl-value (parse-num (:pnl-value row))]
    (or (not (zero? total-balance))
        (not (zero? available-balance))
        (not (zero? usdc-value))
        (not (zero? pnl-value)))))

(defn- merge-unified-usdc-row [spot-rows perps-row]
  (let [usdc-spot-row (some #(when (= "USDC" (:coin %)) %) (or spot-rows []))
        other-spot-rows (->> (or spot-rows [])
                             (remove #(= "USDC" (:coin %)))
                             (filter non-zero-balance-row?))
        unified-usdc-row (cond
                           usdc-spot-row
                           (assoc usdc-spot-row
                                  :coin "USDC"
                                  :contract-id nil
                                  :transfer-disabled? true)

                           perps-row
                           (assoc perps-row
                                  :key "unified-usdc-fallback"
                                  :coin "USDC"
                                  :contract-id nil
                                  :transfer-disabled? true)

                           :else
                           nil)]
    (->> (concat (when unified-usdc-row [unified-usdc-row])
                 other-spot-rows)
         (filter non-zero-balance-row?)
         (remove nil?)
         vec)))

(defn build-balance-rows
  ([webdata2 spot-data]
   (build-balance-rows webdata2 spot-data nil))
  ([webdata2 spot-data account]
   (build-balance-rows webdata2 spot-data account nil))
  ([webdata2 spot-data account market-by-key]
   (let [clearinghouse-state (:clearinghouseState webdata2)
         unified? (unified-account-mode? account)
         spot-meta (:meta spot-data)
         spot-state (:clearinghouse-state spot-data)
         spot-asset-ctxs (:spotAssetCtxs webdata2)
         spot-tokens (or (:tokens spot-meta) [])
         price-by-token (build-token-usdc-price-map spot-meta spot-asset-ctxs market-by-key)
         price-by-coin (build-coin-usdc-price-map-from-market-state market-by-key)
         token-by-index (into {}
                              (keep (fn [{:keys [index] :as token}]
                                      (when (some? index)
                                        [index token])))
                              spot-tokens)
         token-by-ref (build-token-by-ref spot-tokens)
         token-decimals (into {}
                              (map (fn [{:keys [index weiDecimals szDecimals]}]
                                     [index (or weiDecimals szDecimals 2)]))
                              spot-tokens)
         coin-decimals (build-coin-decimals-map-from-market-state market-by-key)
         perps-row (when clearinghouse-state
                     (let [account-value (parse-num (get-in clearinghouse-state [:marginSummary :accountValue]))
                           total-margin-used (parse-num (get-in clearinghouse-state [:marginSummary :totalMarginUsed]))
                           available (- account-value total-margin-used)]
                       {:key "perps-usdc"
                        :coin (if unified? "USDC" "USDC (Perps)")
                        :total-balance account-value
                        :available-balance available
                        :usdc-value account-value
                        :pnl-value nil
                        :pnl-pct nil
                        :amount-decimals nil}))
         spot-rows (when (seq (get spot-state :balances))
                     (->> (get spot-state :balances)
                          (map (fn [{:keys [coin token hold total] :as balance}]
                                 (let [{:keys [token-idx total-num price usdc-value]}
                                       (spot-balance-valuation price-by-token price-by-coin coin token total)
                                       token-meta (resolve-token-meta token-by-index token-by-ref token coin)
                                       decimals (or (get token-decimals token-idx)
                                                    (get coin-decimals (normalize-coin-code coin)))
                                       hold-num (parse-num hold)
                                       available-num (- total-num hold-num)
                                       entry-num (spot-entry-notional balance)
                                       pnl-value (when (and (number? price)
                                                            (number? entry-num)
                                                            (pos? entry-num))
                                                   (- usdc-value entry-num))
                                       pnl-pct (when (and pnl-value (pos? entry-num))
                                                 (* 100 (/ pnl-value entry-num)))
                                       coin-label (if (= coin "USDC")
                                                    (if unified? "USDC" "USDC (Spot)")
                                                    coin)
                                       contract-id (when-not (= coin "USDC")
                                                     (or (extract-balance-contract-id balance)
                                                         (extract-balance-contract-id token-meta)))]
                                   {:key (str "spot-" (or token-idx coin))
                                    :coin coin-label
                                    :total-balance total-num
                                    :available-balance available-num
                                    :usdc-value usdc-value
                                    :pnl-value pnl-value
                                    :pnl-pct pnl-pct
                                    :amount-decimals decimals
                                    :contract-id contract-id})))
                          vec))
         rows (if unified?
                (merge-unified-usdc-row spot-rows perps-row)
                (->> (concat (when perps-row [perps-row]) spot-rows)
                     (remove nil?)
                     (filter non-zero-balance-row?)
                     vec))]
     rows)))

(defn position-unique-key [position-data]
  (str (get-in position-data [:position :coin]) "|" (or (:dex position-data) "default")))

(defn collect-positions [webdata2 perp-dex-states]
  (let [base-positions (->> (get-in webdata2 [:clearinghouseState :assetPositions])
                            (map #(assoc % :dex nil)))
        extra-positions (->> perp-dex-states
                             (mapcat (fn [[dex state]]
                                       (->> (:assetPositions state)
                                            (map #(assoc % :dex dex))))))
        combined (->> (concat base-positions extra-positions)
                      (remove nil?))]
    (second
     (reduce (fn [[seen acc] pos]
               (let [k (position-unique-key pos)]
                 (if (contains? seen k)
                   [seen acc]
                   [(conj seen k) (conj acc pos)])))
             [#{} []]
             combined))))
