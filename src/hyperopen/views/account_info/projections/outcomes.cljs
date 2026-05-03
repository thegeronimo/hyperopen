(ns hyperopen.views.account-info.projections.outcomes
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.projections.parse :as parse]))

(def parse-num parse/parse-num)
(def parse-optional-num parse/parse-optional-num)

(def ^:private outcome-asset-id-base 100000000)

(def ^:private outcome-token-pattern
  #"^[+#](\d+)$")

(def ^:private outcome-entry-notional-keys
  [:entryNtl :entryNotional :entryNtlUsd :entryNtlUSDC :entryValue :entry])

(defn- parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt (str/trim value) 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- token-encoding
  [value]
  (when-let [[_ encoding] (and (string? value)
                               (re-matches outcome-token-pattern
                                           (str/trim value)))]
    (parse-int-value encoding)))

(defn- asset-id-encoding
  [value]
  (when-let [token-id (parse-int-value value)]
    (let [encoding (- token-id outcome-asset-id-base)]
      (when (not (neg? encoding))
        encoding))))

(defn outcome-token?
  [value]
  (boolean (token-encoding value)))

(defn outcome-balance?
  [{:keys [coin token]}]
  (or (outcome-token? coin)
      (some? (asset-id-encoding token))))

(defn- side-coin-from-encoding
  [encoding]
  (when (number? encoding)
    (str "#" encoding)))

(defn- token-name-from-encoding
  [encoding]
  (when (number? encoding)
    (str "+" encoding)))

(defn- side-index-from-encoding
  [encoding]
  (when (number? encoding)
    (mod encoding 10)))

(defn- balance-encoding
  [{:keys [coin token]}]
  (or (token-encoding coin)
      (asset-id-encoding token)))

(defn- balance-side-coin
  [balance]
  (side-coin-from-encoding (balance-encoding balance)))

(defn- outcome-side-entry
  [market side]
  (let [side-coin (:coin side)
        encoding (token-encoding side-coin)]
    (when (and (= :outcome (:market-type market))
               (number? encoding))
      (merge side
             {:market-key (:key market)
              :market-title (or (:title market)
                                (:symbol market)
                                side-coin)
              :market-symbol (:symbol market)
              :market-coin (:coin market)
              :market-mark (:mark market)
              :quote (or (:quote market) "USDH")
              :token-name (token-name-from-encoding encoding)}))))

(defn- outcome-market-primary-side-entry
  [market]
  (let [coin (:coin market)
        encoding (token-encoding coin)]
    (outcome-side-entry
     market
     (cond-> {:coin coin
              :mark (:mark market)
              :markRaw (:markRaw market)}
       (number? encoding)
       (assoc :side-index (side-index-from-encoding encoding))))))

(defn- assoc-side-lookup
  [lookup side-entry]
  (cond-> lookup
    (seq (:coin side-entry))
    (assoc (:coin side-entry) side-entry)

    (seq (:token-name side-entry))
    (assoc (:token-name side-entry) side-entry)))

(defn- outcome-market-candidates
  [market-by-key {:keys [active-market selector-active-market]}]
  (->> (concat (vals (or market-by-key {}))
               [active-market selector-active-market])
       (filter #(= :outcome (:market-type %)))
       (reduce (fn [{:keys [seen markets]} market]
                 (let [identity [(or (:key market) "")
                                 (or (:coin market) "")
                                 (:outcome-id market)]]
                   (if (contains? seen identity)
                     {:seen seen :markets markets}
                     {:seen (conj seen identity)
                      :markets (conj markets market)})))
               {:seen #{} :markets []})
       :markets))

(defn- outcome-side-lookup
  [market-by-key options]
  (reduce (fn [lookup market]
            (if (= :outcome (:market-type market))
              (let [lookup* (if-let [side-entry (outcome-market-primary-side-entry market)]
                              (assoc-side-lookup lookup side-entry)
                              lookup)]
                (reduce (fn [lookup** side]
                          (if-let [side-entry (outcome-side-entry market side)]
                            (assoc-side-lookup lookup** side-entry)
                            lookup**))
                        lookup*
                        (or (:outcome-sides market) [])))
              lookup))
          {}
          (outcome-market-candidates market-by-key options)))

(defn- balance-entry-notional
  [balance]
  (some (fn [k]
          (let [parsed (parse-optional-num (get balance k))]
            (when (number? parsed)
              parsed)))
        outcome-entry-notional-keys))

(defn- fallback-side-name
  [side-coin]
  (let [encoding (token-encoding side-coin)
        side-index (side-index-from-encoding encoding)]
    (case side-index
      0 "Yes"
      1 "No"
      (str "Side " (or side-index encoding side-coin)))))

(defn- non-zero-outcome-balance?
  [balance]
  (and (outcome-balance? balance)
       (not (zero? (parse-num (:total balance))))))

(defn- active-context-mark-price
  [context-by-coin side-coin token-name]
  (when-let [ctx (or (get context-by-coin side-coin)
                    (get context-by-coin token-name))]
    (some parse-optional-num
          [(:mark ctx)
           (:markPx ctx)
           (get-in ctx [:funding :mark])
           (get-in ctx [:funding :markPx])])))

(defn- present-text
  [value]
  (some-> value str str/trim not-empty))

(defn- assoc-context-entry
  [lookup key ctx]
  (if-let [key* (present-text key)]
    (assoc lookup key* ctx)
    lookup))

(defn- context-entry-keys
  [entry-key ctx]
  (let [funding (:funding ctx)
        info (:info ctx)]
    (->> [entry-key
          (:coin ctx)
          (:name ctx)
          (:coin funding)
          (:name funding)
          (:name info)
          (:coin info)]
         (keep present-text)
         distinct
         vec)))

(defn- add-context-entry
  [lookup entry-key ctx]
  (reduce #(assoc-context-entry %1 %2 ctx)
          lookup
          (context-entry-keys entry-key ctx)))

(defn- context-map->lookup
  [contexts]
  (if (map? contexts)
    (reduce-kv (fn [lookup entry-key ctx]
                 (add-context-entry lookup entry-key ctx))
               {}
               contexts)
    {}))

(defn- context-seq->lookup
  [contexts]
  (if (sequential? contexts)
    (reduce (fn [lookup ctx]
              (add-context-entry lookup (:coin ctx) ctx))
            {}
            contexts)
    {}))

(defn- build-context-lookup
  [{:keys [active-contexts asset-contexts spot-asset-ctxs]}]
  (merge (context-seq->lookup spot-asset-ctxs)
         (context-map->lookup asset-contexts)
         (context-map->lookup active-contexts)))

(defn- side-mark-price
  [context-by-coin side-entry side-coin]
  (or (active-context-mark-price context-by-coin
                                 side-coin
                                 (:token-name side-entry))
      (parse-optional-num (:mark side-entry))
      (when (= side-coin (:market-coin side-entry))
        (parse-optional-num (:market-mark side-entry)))
      0))

(defn- outcome-row
  [side-by-coin context-by-coin balance]
  (let [side-coin (balance-side-coin balance)
        side-entry (or (get side-by-coin side-coin)
                       (get side-by-coin (:coin balance)))
        size (parse-num (:total balance))
        entry-notional (or (balance-entry-notional balance) 0)
        mark-price (side-mark-price context-by-coin side-entry side-coin)
        position-value (* size mark-price)
        entry-price (if (zero? size)
                      0
                      (/ entry-notional size))
        pnl-value (- position-value entry-notional)
        roe-pct (when (pos? entry-notional)
                  (* 100 (/ pnl-value entry-notional)))]
    {:key (str "outcome-" side-coin)
     :title (or (:market-title side-entry) side-coin)
     :market-key (:market-key side-entry)
     :raw-coin (:coin balance)
     :side-coin side-coin
     :side-name (or (:side-name side-entry)
                    (:sideName side-entry)
                    (fallback-side-name side-coin))
     :side-index (:side-index side-entry)
     :type-label "Outcome"
     :size size
     :position-value position-value
     :quote (or (:quote side-entry) "USDH")
     :entry-price entry-price
     :mark-price mark-price
     :pnl-value pnl-value
     :roe-pct roe-pct
     :entry-notional entry-notional}))

(defn build-outcome-rows
  ([spot-data market-by-key]
   (build-outcome-rows spot-data market-by-key {}))
  ([spot-data market-by-key options]
   (let [side-by-coin (outcome-side-lookup market-by-key options)
         context-by-coin (build-context-lookup options)]
     (->> (get-in spot-data [:clearinghouse-state :balances])
          (filter non-zero-outcome-balance?)
          (map #(outcome-row side-by-coin context-by-coin %))
          vec))))
