(ns hyperopen.views.account-info.projections
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]))

(defn parse-num [value]
  (let [num-val (js/parseFloat (or value 0))]
    (if (js/isNaN num-val) 0 num-val)))

(defn parse-optional-num [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseFloat value)
              :else js/NaN)]
    (when (and (number? num) (not (js/isNaN num)))
      num)))

(defn parse-optional-int [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num) (not (js/isNaN num)))
      (js/Math.floor num))))

(defn parse-time-ms [value]
  (when-let [num (parse-optional-num value)]
    (js/Math.floor num)))

(defn boolean-value [value]
  (cond
    (true? value) true
    (false? value) false

    (string? value)
    (let [text (-> value str str/trim str/lower-case)]
      (case text
        "true" true
        "false" false
        nil))

    :else nil))

(defn title-case-label [value]
  (let [text (some-> value str str/trim)]
    (if (seq text)
      (->> (str/split (str/lower-case text) #"[_\s-]+")
           (remove str/blank?)
           (map str/capitalize)
           (str/join " "))
      "-")))

(defn non-blank-text [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn parse-coin-namespace [coin]
  (let [coin* (non-blank-text coin)]
    (when coin*
      (if (str/includes? coin* ":")
        (let [[prefix suffix] (str/split coin* #":" 2)]
          {:prefix (non-blank-text prefix)
           :base (non-blank-text suffix)})
        {:prefix nil
         :base coin*}))))

(defn symbol-base-label [symbol]
  (let [symbol* (non-blank-text symbol)]
    (when symbol*
      (let [parts (cond
                    (str/includes? symbol* "/") (str/split symbol* #"/" 2)
                    (str/includes? symbol* "-") (str/split symbol* #"-" 2)
                    :else [symbol*])]
        (non-blank-text (first parts))))))

(defn resolve-coin-display [coin market-by-key]
  (let [coin* (non-blank-text coin)
        parsed (parse-coin-namespace coin*)
        market (markets/resolve-market-by-coin (or market-by-key {}) coin*)
        market-base (or (non-blank-text (:base market))
                        (symbol-base-label (:symbol market))
                        (some-> (:coin market) parse-coin-namespace :base))
        base-label (or market-base (:base parsed) coin* "-")
        prefix-label (:prefix parsed)]
    {:base-label base-label
     :prefix-label prefix-label}))

(def ^:private default-order-history-status-labels
  {:all "All"
   :open "Open"
   :filled "Filled"
   :canceled "Canceled"
   :rejected "Rejected"
   :triggered "Triggered"})

(defn order-history-status-key [status]
  (let [text (some-> status str str/trim str/lower-case)]
    (case text
      "open" :open
      "filled" :filled
      "canceled" :canceled
      "cancelled" :canceled
      "rejected" :rejected
      "triggered" :triggered
      nil)))

(defn order-history-status-label
  ([status]
   (order-history-status-label status default-order-history-status-labels))
  ([status labels]
   (let [status-key (order-history-status-key status)]
     (or (get labels status-key)
         (title-case-label status)))))

(defn normalize-open-order [order]
  (let [root (or (:order order) order)
        coin (or (:coin root) (:coin order))
        oid (or (:oid root) (:oid order))
        side (or (:side root) (:side order))
        sz (or (:sz root) (:origSz root) (:sz order) (:origSz order))
        orig-sz (or (:origSz root) (:origSz order))
        limit-px (or (:limitPx root) (:limitPx order))
        fallback-px (or (:px root) (:px order))
        trigger-px (or (:triggerPx root) (:triggerPx order))
        is-trigger? (or (:isTrigger root) (:isTrigger order))
        trigger-condition (or (:triggerCondition root) (:triggerCondition order)
                              (:triggerCond root) (:triggerCond order))
        px (let [candidate (or limit-px fallback-px)]
             (if (and is-trigger? (zero? (parse-num candidate)))
               trigger-px
               candidate))
        time (or (:timestamp root) (:timestamp order) (:time root) (:time order))
        order-type (or (:orderType root) (:orderType order) (:type root) (:type order) (:tif root) (:tif order))
        reduce-only (or (:reduceOnly root) (:reduceOnly order))
        is-position-tpsl (or (:isPositionTpsl root) (:isPositionTpsl order))]
    (when (or coin oid)
      {:coin coin
       :oid oid
       :side side
       :sz sz
       :orig-sz orig-sz
       :px px
       :type order-type
       :time time
       :reduce-only reduce-only
       :is-trigger is-trigger?
       :trigger-condition trigger-condition
       :trigger-px trigger-px
       :is-position-tpsl is-position-tpsl})))

(defn open-orders-seq [orders]
  (cond
    (nil? orders) []
    (sequential? orders) orders
    (map? orders) (let [nested (or (:orders orders) (:openOrders orders) (:data orders))]
                    (cond
                      (sequential? nested) nested
                      (:order orders) [orders]
                      :else []))
    :else []))

(defn open-orders-by-dex [orders-by-dex]
  (->> (vals (or orders-by-dex {}))
       (mapcat open-orders-seq)))

(defn open-orders-source [orders snapshot snapshot-by-dex]
  (let [live (open-orders-seq orders)
        fallback (open-orders-seq snapshot)
        dex-orders (open-orders-by-dex snapshot-by-dex)]
    (concat (if (seq live) live fallback) dex-orders)))

(defn normalized-open-orders [orders snapshot snapshot-by-dex]
  (->> (open-orders-source orders snapshot snapshot-by-dex)
       (map normalize-open-order)
       (remove nil?)
       (filter (fn [o] (and (:coin o) (:oid o))))
       vec))

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
   :metadata :meta :details
   :address])

(def ^:private non-contract-address-key-exact
  #{"user" "wallet" "owner" "account" "maker" "taker" "from" "to"})

(defn- map-entry-contract-key? [k]
  (let [key-name (some-> k name str/lower-case)]
    (and (seq key-name)
         (or (str/includes? key-name "contract")
             (str/includes? key-name "token")
             (str/includes? key-name "address"))
         (not (contains? non-contract-address-key-exact key-name)))))

(defn- extract-balance-contract-id [value]
  (letfn [(walk [node depth]
            (when (and (some? node) (<= depth 4))
              (cond
                (map? node)
                (or (some (fn [k]
                            (walk (get node k) (inc depth)))
                          balance-contract-id-candidate-keys)
                    (some (fn [[k v]]
                            (when (map-entry-contract-key? k)
                              (walk v (inc depth))))
                          node))

                (sequential? node)
                (some #(walk % (inc depth)) node)

                :else
                (normalize-balance-contract-id node))))]
    (walk value 0)))

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

(defn- non-usdc-balance? [balance]
  (not (usdc-coin? (:coin balance))))

(defn- maybe-warn-balance-spot-meta-invariant!
  [spot-meta spot-state]
  (let [spot-balances (or (get spot-state :balances) [])
        non-usdc-count (count (filter non-usdc-balance? spot-balances))
        token-count (count (or (:tokens spot-meta) []))]
    (when (and ^boolean goog.DEBUG
               (pos? non-usdc-count)
               (zero? token-count))
      (js/console.warn
       "Balance contract invariant warning: spot balances include non-USDC assets but spot meta tokens are empty."
       (clj->js {:non-usdc-balance-count non-usdc-count
                 :spot-meta-token-count token-count})))))

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
      (normalize-coin-code (symbol-base-label (:symbol market)))
      (normalize-coin-code (some-> (parse-coin-namespace (:coin market))
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
        base-key (normalize-coin-code (some-> (parse-coin-namespace coin)
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
        usdc-value (if (number? price) (* total-num price) 0)]
    (maybe-warn-usdc-valuation-invariant! coin total-num usdc-value)
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
        _ (maybe-warn-balance-spot-meta-invariant! spot-meta spot-state)
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

(defn normalize-order-history-row
  ([row]
   (normalize-order-history-row row default-order-history-status-labels))
  ([row order-history-status-labels]
   (let [root (or (:order row) row)
         root-map (if (map? root) root {})
         row-map (if (map? row) row {})
         coin (or (:coin root-map) (:coin row-map))
         oid (or (:oid root-map) (:oid row-map) (:orderId root-map) (:orderId row-map))
         side (or (:side root-map) (:side row-map))
         size (or (:origSz root-map) (:origSz row-map) (:sz root-map) (:sz row-map))
         remaining-size (or (:remainingSz root-map) (:remainingSz row-map))
         limit-px (or (:limitPx root-map) (:limitPx row-map))
         fallback-px (or (:px root-map) (:px row-map))
         trigger-px (or (:triggerPx root-map) (:triggerPx row-map))
         is-trigger (true? (boolean-value (or (:isTrigger root-map) (:isTrigger row-map))))
         trigger-condition (or (:triggerCondition root-map)
                               (:triggerCondition row-map)
                               (:triggerCond root-map)
                               (:triggerCond row-map))
         reduce-only-value (if (contains? root-map :reduceOnly)
                             (:reduceOnly root-map)
                             (:reduceOnly row-map))
         reduce-only (boolean-value reduce-only-value)
         is-position-tpsl-value (if (contains? root-map :isPositionTpsl)
                                  (:isPositionTpsl root-map)
                                  (:isPositionTpsl row-map))
         is-position-tpsl (true? (boolean-value is-position-tpsl-value))
         order-type (or (:orderType root-map)
                        (:orderType row-map)
                        (:type root-map)
                        (:type row-map)
                        (:tif root-map)
                        (:tif row-map))
         status (or (:status row-map)
                    (:status root-map)
                    (:orderStatus row-map)
                    (:orderStatus root-map))
         status-timestamp (or (:statusTimestamp row-map)
                              (:statusTimestamp root-map)
                              (:statusTime row-map)
                              (:statusTime root-map)
                              (:timestamp root-map)
                              (:timestamp row-map)
                              (:time root-map)
                              (:time row-map))
         size-num (parse-optional-num size)
         remaining-size-num (parse-optional-num remaining-size)
         market? (or (= "market" (some-> order-type str str/trim str/lower-case))
                     (true? (boolean-value (or (:isMarket root-map) (:isMarket row-map))))
                     (zero? (or (parse-optional-num (or limit-px fallback-px)) 0)))
         px (when-not market?
              (or limit-px fallback-px))
         filled-size (when (and (number? size-num)
                                (number? remaining-size-num))
                       (max 0 (- size-num remaining-size-num)))
         order-value (let [price-num (parse-optional-num px)]
                       (when (and (not market?)
                                  (number? size-num)
                                  (number? price-num)
                                  (pos? size-num)
                                  (pos? price-num))
                         (* size-num price-num)))
         status-key (order-history-status-key status)]
     (when (or (some? oid) (some? coin) (some? status-timestamp))
       {:coin coin
        :oid oid
        :side side
        :size size
        :size-num size-num
        :filled-size filled-size
        :order-value order-value
        :px px
        :market? market?
        :type order-type
        :time-ms (parse-time-ms status-timestamp)
        :reduce-only reduce-only
        :is-trigger is-trigger
        :trigger-condition trigger-condition
        :trigger-px trigger-px
        :is-position-tpsl is-position-tpsl
        :status status
        :status-key status-key
        :status-label (order-history-status-label status order-history-status-labels)}))))

(defn normalized-order-history
  ([rows]
   (normalized-order-history rows default-order-history-status-labels))
  ([rows order-history-status-labels]
   (->> (or rows [])
        (map #(normalize-order-history-row % order-history-status-labels))
        (remove nil?)
        vec)))

(def ^:private trade-history-trade-value-keys
  [:tradeValue :trade-value :tradeValueUsd :tradeValueUSDC :notional :notionalValue :value :quoteValue])

(def ^:private trade-history-fee-keys
  [:fee :feePaid :feeUsd :feeUSDC])

(def ^:private trade-history-closed-pnl-keys
  [:closedPnl :closed-pnl :closed_pnl :closedPnlUsd :closedPnlUSDC :realizedPnl])

(defn trade-history-coin [row]
  (or (:coin row) (:symbol row) (:asset row)))

(defn trade-history-time-ms [row]
  (when-let [raw-time (parse-optional-num (or (:time row) (:timestamp row) (:ts row) (:t row)))]
    (let [rounded (js/Math.floor raw-time)]
      (if (< rounded 1000000000000)
        (* rounded 1000)
        rounded))))

(defn trade-history-first-parseable-row-value [row keys]
  (some (fn [k]
          (let [value (get row k)]
            (when (number? (parse-optional-num value))
              value)))
        keys))

(defn trade-history-value-number [row]
  (let [explicit-value (trade-history-first-parseable-row-value row trade-history-trade-value-keys)]
    (or (parse-optional-num explicit-value)
        (let [size (parse-optional-num (or (:sz row) (:size row) (:s row)))
              price (parse-optional-num (or (:px row) (:price row) (:p row)))]
          (when (and (number? size)
                     (number? price))
            (* size price))))))

(defn trade-history-fee-number [row]
  (parse-optional-num (trade-history-first-parseable-row-value row trade-history-fee-keys)))

(defn trade-history-closed-pnl-number [row]
  (parse-optional-num (trade-history-first-parseable-row-value row trade-history-closed-pnl-keys)))

(defn trade-history-row-id [row]
  (str (or (:tid row) (:id row) "")
       "|"
       (or (trade-history-time-ms row) 0)
       "|"
       (or (trade-history-coin row) "")
       "|"
       (or (:px row) (:price row) (:p row) "")
       "|"
       (or (:sz row) (:size row) (:s row) "")))
