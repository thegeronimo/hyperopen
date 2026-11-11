(ns hyperopen.views.account-info.projections.outcomes
  (:require [clojure.string :as str]
            [hyperopen.outcomes.domain.book :as book]
            [hyperopen.outcomes.domain.fees :as fees]
            [hyperopen.outcomes.names :as outcome-names]
            [hyperopen.views.account-info.projections.outcome-contexts :as outcome-contexts]
            [hyperopen.views.account-info.projections.parse :as parse]))

(def parse-num parse/parse-num)
(def parse-optional-num parse/parse-optional-num)

(def payout-currency
  "Outcome contracts are quoted and paid in USDC."
  "USDC")

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
              :market-shape (:outcome-shape market)
              :market-clock (:outcome-clock market)
              :market-category (:outcome-category market)
              :market-competition (:outcome-competition market)
              :market-venue (or (:outcome-venue market)
                                (when (seq (:outcome-venues market))
                                  (str/join " · " (:outcome-venues market)))
                                "Hyperliquid")
              :outcome-option-label (:outcome-option-label side)
              :quote payout-currency
              :token-name (token-name-from-encoding encoding)}))))

(def ^:private category-titles
  {:sports "Sports"
   :crypto "Crypto"
   :stocks "Stocks & commodities"
   :economics "Economics"
   :companies "Companies"})

(defn your-side-label
  "What the shares back, in words: \"DET to win\", \"No on Hike\", \"Yes\"."
  [{:keys [side-name outcome-option-label market-shape]}]
  (cond
    (seq outcome-option-label) (str side-name " on " outcome-option-label)
    (= :head-to-head market-shape) (str side-name " to win")
    :else side-name))

(defn position-label
  "The Position column: \"DET\", \"Thunder Yes\", \"No on $80\"."
  [{:keys [side-name outcome-option-label market-shape]}]
  (outcome-names/side-label {:side-name side-name
                             :option-label outcome-option-label
                             :ladder? (= :ladder market-shape)}))

(defn- market-meta
  [{:keys [market-category market-competition market-venue]}]
  (str/join " · " (keep identity [(get category-titles market-category)
                                  market-competition
                                  market-venue])))

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

(defn- holds-coin?
  "True when `market` has a side (or is primary on) a coin in `coins`."
  [coins market]
  (or (contains? coins (:coin market))
      (some #(contains? coins (:coin %)) (:outcome-sides market))
      (some (fn [option] (some #(contains? coins (:coin %)) (:sides option)))
            (:question-options market))))

(defn- market-identity
  [market]
  [(or (:key market) "") (or (:coin market) "") (:outcome-id market)])

(defn- extra-candidates
  "The active and selector-active markets, unless the catalog holds the same
   market (same key, coin and contract) already."
  [market-by-key {:keys [active-market selector-active-market]}]
  (->> [active-market selector-active-market]
       (filter #(= :outcome (:market-type %)))
       (remove #(= (market-identity %) (some-> (get market-by-key (:key %)) market-identity)))
       (reduce (fn [acc market]
                 (if (some #(= (market-identity %) (market-identity market)) acc)
                   acc
                   (conj acc market)))
               [])))

(defn- question-option-sides
  [market]
  (->> (:question-options market)
       (mapcat (fn [option]
                 (map #(cond-> %
                         (and (:label option)
                              (nil? (:outcome-option-label %)))
                         (assoc :outcome-option-label (:label option)))
                      (:sides option))))
       vec))

(defn- side-lookup-over
  "Side entries of `markets`, only for sides `wanted?` accepts."
  [lookup markets wanted?]
  (reduce (fn [lookup market]
            (let [primary {:coin (:coin market)}
                  lookup* (if-let [side-entry (when (wanted? primary)
                                                (outcome-market-primary-side-entry market))]
                            (assoc-side-lookup lookup side-entry)
                            lookup)]
              (reduce (fn [lookup** side]
                        (if-let [side-entry (when (wanted? side)
                                              (outcome-side-entry market side))]
                          (assoc-side-lookup lookup** side-entry)
                          lookup**))
                      lookup*
                      (concat (or (:outcome-sides market) [])
                              (question-option-sides market)))))
          lookup
          markets))

(defonce ^:private catalog-lookup-cache
  ;; One slot, keyed on the catalog value and the held coins: Account Equity
  ;; and the account tables ask for the same sides many times a second.
  (atom nil))

(defn- catalog-side-lookup
  [market-by-key coins wanted?]
  (let [cached @catalog-lookup-cache]
    (if (and cached
             (identical? market-by-key (:market-by-key cached))
             (= coins (:coins cached)))
      (:lookup cached)
      (let [markets (cond->> (filter #(= :outcome (:market-type %)) (vals (or market-by-key {})))
                      (set? coins) (filter #(holds-coin? coins %)))
            lookup (side-lookup-over {} markets wanted?)]
        (reset! catalog-lookup-cache {:market-by-key market-by-key :coins coins :lookup lookup})
        lookup))))

(defn outcome-side-lookup
  "Side coin (and token name) -> side entry for the catalog's outcome sides
   (and the active markets the catalog does not hold as such). With `:coins`
   in `options` (a set of side coins), only those sides get an entry: the
   account tables only need the sides they hold, and building an entry for
   every side of every event on each render is what made a held balance cost
   a whole-catalog pass per book tick. The catalog part is cached per
   catalog value."
  [market-by-key options]
  (let [coins (:coins options)
        wanted? (if (set? coins) #(contains? coins (:coin %)) (constantly true))
        extra (cond->> (extra-candidates market-by-key options)
                (set? coins) (filter #(holds-coin? coins %)))]
    (side-lookup-over (catalog-side-lookup market-by-key coins wanted?) extra wanted?)))

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

(defn- side-mark-price
  [context-by-coin side-entry side-coin]
  (or (active-context-mark-price context-by-coin
                                 side-coin
                                 (:token-name side-entry))
      (parse-optional-num (:mark side-entry))
      (when (= side-coin (:market-coin side-entry))
        (parse-optional-num (:market-mark side-entry)))
      0))

(defn- best-bid-price
  "Highest bid in the side's live book, or nil without one."
  [orderbooks side-coin]
  (when (map? orderbooks)
    (some-> (get orderbooks side-coin) book/best-bid)))

(defn- close-figures
  "What closing `size` shares at `price` returns, and what winning pays, both
   after fees measured on the contract's venue. Unconfirmed venues carry
   `:fee-known? false` and no fee figure."
  [{:keys [size price venue rates entry-notional]}]
  (let [close (fees/sell {:shares size :price price :venue venue :rates rates :cost entry-notional})
        win (fees/settlement {:shares size :venue venue :rates rates :cost entry-notional})]
    {:fee-known? (:fee-known? close)
     :close-fee (:fee close)
     :value-after-fees (if (:fee-known? close) (:net close) (* size price))
     :close-result (:result close)
     :if-right-gross (:gross win)
     :settlement-fee (:fee win)
     :if-right (if (:fee-known? win) (:net win) size)
     :if-right-profit (:profit win)}))

(defn- outcome-row
  [side-by-coin context-by-coin rates orderbooks balance]
  (let [side-coin (balance-side-coin balance)
        side-entry (or (get side-by-coin side-coin)
                       (get side-by-coin (:coin balance)))
        size (parse-num (:total balance))
        entry-notional (or (balance-entry-notional balance) 0)
        mark-price (side-mark-price context-by-coin side-entry side-coin)
        bid-price (best-bid-price orderbooks side-coin)
        value-price (or bid-price mark-price)
        position-value (* size value-price)
        entry-price (if (zero? size)
                      0
                      (/ entry-notional size))
        pnl-value (- position-value entry-notional)
        roe-pct (when (pos? entry-notional)
                  (* 100 (/ pnl-value entry-notional)))
        encoding (token-encoding side-coin)
        side-name (or (:side-name side-entry)
                      (:sideName side-entry)
                      (fallback-side-name side-coin))
        figures (close-figures {:size size
                                :price value-price
                                :venue (:market-venue side-entry)
                                :rates rates
                                :entry-notional entry-notional})]
    (merge
     {:key (str "outcome-" side-coin)
      :title (or (:market-title side-entry) side-coin)
      :market-key (:market-key side-entry)
      :raw-coin (:coin balance)
      :side-coin side-coin
      :side-name side-name
      :outcome-option-label (:outcome-option-label side-entry)
      :your-side (your-side-label {:side-name side-name
                                   :outcome-option-label (:outcome-option-label side-entry)
                                   :market-shape (:market-shape side-entry)})
      :position-label (position-label {:side-name side-name
                                       :outcome-option-label (:outcome-option-label side-entry)
                                       :market-shape (:market-shape side-entry)})
      :market-meta (market-meta side-entry)
      :venue (:market-venue side-entry)
      :clock (:market-clock side-entry)
      :pays size
      :side-index (or (:side-index side-entry) (side-index-from-encoding encoding))
      :outcome-id (when (number? encoding) (quot encoding 10))
      :type-label "Outcome"
      :size size
      :position-value position-value
      :quote payout-currency
      :entry-price entry-price
      :mark-price mark-price
      :bid-price bid-price
      :value-basis (if bid-price :bid :mark)
      :pnl-value pnl-value
      :roe-pct roe-pct
      :entry-notional entry-notional
      :cost entry-notional}
     figures)))

(defonce ^:private held-cache
  ;; {:raw <balances vector> :held <its outcome part>}
  (atom nil))

(defn- held-outcome-balances
  "{:balances :coins :wanted}: the non-zero outcome balances, their side
   coins, and every alias (side coin and token name) their contexts may sit
   under. Kept per balances value."
  [spot-data]
  (let [raw (get-in spot-data [:clearinghouse-state :balances])
        cached @held-cache]
    (if (and cached (identical? raw (:raw cached)))
      (:held cached)
      (let [balances (filterv non-zero-outcome-balance? raw)
            coins (into #{} (keep balance-side-coin) balances)
            held {:balances balances
                  :coins coins
                  :wanted (into coins (keep token-name-from-encoding (keep balance-encoding balances)))}]
        (reset! held-cache {:raw raw :held held})
        held))))

(defn build-outcome-rows
  ([spot-data market-by-key]
   (build-outcome-rows spot-data market-by-key {}))
  ([spot-data market-by-key options]
   ;; Most accounts hold no outcome shares: nothing is looked up then. With
   ;; shares held, only the held sides are resolved from the catalog.
   (let [{:keys [balances coins wanted]} (held-outcome-balances spot-data)]
     (if (empty? balances)
       []
       (let [side-by-coin (outcome-side-lookup market-by-key (assoc options :coins coins))
             context-by-coin (outcome-contexts/context-lookup options wanted)
             rates (fees/spot-rates (:user-fees options))
             orderbooks (:orderbooks options)]
         (mapv #(outcome-row side-by-coin context-by-coin rates orderbooks %) balances))))))

(defn outcome-rows-inputs
  "Everything `build-outcome-rows` reads for these arguments, narrowed to the
   held sides (their balances, catalog, extra active markets, contexts and
   books); nil when no outcome share is held. Two equal by
   `same-outcome-rows-inputs?` build equal rows."
  [spot-data market-by-key options]
  (let [{:keys [balances coins wanted]} (held-outcome-balances spot-data)
        orderbooks (:orderbooks options)]
    (when (seq balances)
      {:balances balances
       :market-by-key market-by-key
       :extra (filterv #(holds-coin? coins %) (extra-candidates market-by-key options))
       :contexts (outcome-contexts/context-lookup options wanted)
       :books (mapv #(when (map? orderbooks) (get orderbooks %)) coins)
       :user-fees (:user-fees options)})))

(defn- all-identical?
  [xs ys]
  (and (= (count xs) (count ys))
       (every? true? (map identical? xs ys))))

(defn same-outcome-rows-inputs?
  "Pointer compares (a value compare only for the few held contexts)."
  [a b]
  (and (map? a) (map? b)
       (identical? (:balances a) (:balances b))
       (identical? (:market-by-key a) (:market-by-key b))
       (all-identical? (:extra a) (:extra b))
       (all-identical? (:books a) (:books b))
       (= (:contexts a) (:contexts b))
       (= (:user-fees a) (:user-fees b))))

(defn shares-value
  "Dollars the account's outcome shares are worth at the best bid, before the
   closing fee (what Account value counts). Nil when no share is held."
  [rows]
  (when (seq rows)
    (reduce + 0 (map :position-value rows))))

(defn- clock-at
  [{:keys [clock]}]
  (or (:at-ms clock) js/Number.MAX_SAFE_INTEGER))

(defn- pair-of
  "The Yes+No pair a group holds on one contract: {:pairs :worth :merge}."
  [rows rates]
  (let [by-contract (group-by :outcome-id (filter #(some? (:outcome-id %)) rows))]
    (some (fn [[_ sides]]
            (let [yes (first (filter #(= 0 (:side-index %)) sides))
                  no (first (filter #(= 1 (:side-index %)) sides))]
              (when (and yes no)
                (let [pairs (js/Math.floor (min (:size yes) (:size no)))]
                  (when (pos? pairs)
                    {:pairs pairs
                     :worth pairs
                     :side-coins [(:side-coin yes) (:side-coin no)]
                     :market-key (:market-key yes)
                     :merge (fees/merge-pairs {:pairs pairs
                                               :venue (:venue yes)
                                               :rates rates})})))))
          by-contract)))

(defn group-by-event
  "Rows grouped by event, soonest first, each with its pair (when both sides of
   one contract are held) and subtotals. Value now and If right are after fees.
   -> [{:key :title :market-meta :clock :rows :pair :cost :value-now}]"
  ([rows] (group-by-event rows nil))
  ([rows user-fees]
   (let [rates (fees/spot-rates user-fees)]
     (->> rows
          (group-by #(or (:market-key %) (:title %)))
          (map (fn [[group-key group-rows]]
                 (let [rows* (vec (sort-by (juxt :outcome-id :side-index) group-rows))
                       lead (first rows*)]
                   {:key group-key
                    :title (:title lead)
                    :market-meta (:market-meta lead)
                    :clock (:clock lead)
                    :rows rows*
                    :pair (pair-of rows* rates)
                    :cost (reduce + 0 (map :cost rows*))
                    :value-now (reduce + 0 (map :value-after-fees rows*))})))
          (sort-by (juxt clock-at :title))
          vec))))

(defn totals
  "Cost and Value now across groups. There is no If right total: two sides of
   one event cannot both win."
  [groups]
  {:cost (reduce + 0 (map :cost groups))
   :value-now (reduce + 0 (map :value-now groups))})
