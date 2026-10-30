(ns hyperopen.vol-range.application.snapshots
  "Pure Vol Range snapshot builders (HO-SPEC-014 v2.1 sections 3.2, 6.7, 7.3).

  Every function takes the app state and an injected `now-ms`; nothing here
  reads the clock, calls an endpoint, builds an exchange action, claims a
  signing counter or writes a record (VR-24). Snapshots satisfy the exact-key specs in
  `hyperopen.vol-range.contracts`."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.domain.trading.fees :as trading-fees]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.state.trading.fee-context :as fee-context]
            [hyperopen.views.account-info.projections :as account-projections]
            [hyperopen.vol-range.application.freshness :as freshness]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.fees :as vr-fees]
            [hyperopen.vol-range.domain.volatility :as volatility]))

(defn- d [x] (precision/parse-decimal x))
(defn- ds [x] (precision/decimal->string x))
(def ^:private zero (d "0"))

(defn- ->dec
  "Exact decimal from a canonical string or a finite number; nil otherwise."
  [x]
  (cond
    (string? x) (precision/parse-decimal (str/trim x))
    (number? x) (precision/number->decimal x)
    :else nil))

(defn- ->dec-string [x] (some-> (->dec x) ds))

;; ---------------------------------------------------------------------------
;; Scope (spec 3.2)
;; ---------------------------------------------------------------------------

(defn- spot-universe-entry [state spot-pair-index]
  (when (some? spot-pair-index)
    (->> (get-in state [:spot :meta :universe])
         (filter #(= (str (:index %)) (str spot-pair-index)))
         first)))

(defn- size-decimals [market]
  (let [v (:szDecimals market)
        n (cond (number? v) v
                (string? v) (let [t (str/trim v)] (when (re-matches #"\d+" t) (js/Number t)))
                :else nil)]
    (when (and (number? n) (js/Number.isInteger n) (<= 0 n)) n)))

(defn current-scope
  "Resolved identity of the active market and account (spec 3.2)."
  [state]
  (let [market (or (:active-market state) {})
        entry (spot-universe-entry state (:idx market))
        [base-token quote-token] (when (sequential? (:tokens entry)) (:tokens entry))]
    {:network (contracts/current-network)
     :owner-address (account-context/owner-address state)
     :account-address (account-context/active-trading-account-address state)
     :vault-address (account-context/exchange-vault-address state)
     :market-type (:market-type market)
     :dex (some-> (:dex market) str)
     :spot-pair-index (:idx market)
     :asset-id (:asset-id market)
     :coin (:coin market)
     :base-token-id (some-> base-token str)
     :quote-token-id (some-> quote-token str)
     :symbol (:base market)
     :quote-symbol (:quote market)
     :sz-decimals (size-decimals market)
     :metadata-version nil}))

;; ---------------------------------------------------------------------------
;; Market snapshot
;; ---------------------------------------------------------------------------

(defn- level-px [level] (->dec (if (map? level) (:px level) nil)))

(defn- extreme-price [levels better?]
  (reduce (fn [best level]
            (let [px (level-px level)]
              (cond (or (nil? px) (not (precision/pos-dec? px))) best
                    (or (nil? best) (better? (precision/cmp px best))) px
                    :else best)))
          nil
          levels))

(defn- epoch-ms
  "A source timestamp as epoch ms, or nil. Never substitutes the render clock:
  an unknown observation time stays unknown (spec 7.3)."
  [x]
  (when (and (number? x) (js/isFinite x) (pos? x)) x))

(defn- book-bbo
  "Best bid/ask with the venue time of the book that produced them. The store
  only receives visually changed books, so this is the time of the last
  CHANGE; Release A admission must confirm freshness with an awaited read."
  [state coin]
  (let [book (get-in state [:orderbooks coin])
        bid (extreme-price (:bids book) pos?)
        ask (extreme-price (:asks book) neg?)]
    (when (and bid ask)
      {:bid (ds bid) :ask (ds ask) :observed-at-ms (epoch-ms (:timestamp book))})))

(defn- vwap-anchor [state coin]
  (let [ctx (get-in state [:active-assets :contexts coin])
        notional (->dec (:dayNtlVlmRaw ctx))
        base (->dec (:dayBaseVlmRaw ctx))]
    (when (and notional base (precision/pos-dec? notional) (precision/pos-dec? base))
      {:price (ds (precision/divide notional base 12 :half-up))
       :observed-at-ms (epoch-ms (get-in state [:market-observations :active-asset-ctx coin]))})))

(defn- bbo-mid-anchor [bbo]
  (let [bid (some-> (:bid bbo) d) ask (some-> (:ask bbo) d)]
    (when (and bid ask)
      {:price (ds (precision/mul (precision/add bid ask) (d "0.5")))
       :observed-at-ms (:observed-at-ms bbo)})))

(defn provenance
  "Live source observation times, captured when a review is frozen (spec 7.3).
  Kept beside the plan input rather than inside its memo key, so a new
  receipt time alone never re-plans."
  [state]
  (let [coin (get-in state [:active-market :coin])]
    {:bbo-observed-at-ms (epoch-ms (get-in state [:orderbooks coin :timestamp]))
     :vwap-observed-at-ms (epoch-ms (get-in state [:market-observations :active-asset-ctx coin]))
     :fee-loaded-at-ms (epoch-ms (get-in state [:portfolio :user-fees-loaded-at-ms]))}))

(def ^:private auto-volatility-cache (atom nil))

(defn- auto-volatility
  "Auto volatility memoized on [rows identity, coin, UTC day] so it recomputes
  on a new sample or day, never per tick."
  [rows coin now-ms]
  (let [day (volatility/utc-day-start-ms now-ms)
        cached @auto-volatility-cache]
    (if (and cached
             (identical? rows (:rows cached))
             (= coin (:coin cached))
             (= day (:day cached)))
      (:result cached)
      (let [result (volatility/resolve-volatility {:mode :auto :rows rows :now-ms now-ms :coin coin})]
        (reset! auto-volatility-cache {:rows rows :coin coin :day day :result result})
        result))))

(defn- auto-volatility-for-state
  "Auto volatility, but only from a series fetched successfully during the
  current UTC day and within the TTL (spec 5.1). A partial in-progress bar from
  an earlier fetch must never reach the Parkinson window after rollover."
  [state rows coin now-ms]
  (if-let [reason (freshness/stale-reason (freshness/completed-at-ms state coin) now-ms)]
    {:status :unavailable
     :reason :stale
     :stale-reason reason
     :message "Daily candles are stale; refreshing"}
    (auto-volatility rows coin now-ms)))

(defn market-snapshot
  "::market-snapshot for the active market. Unsupported markets and missing
  metadata make the snapshot :unavailable."
  [state now-ms]
  (let [market (or (:active-market state) {})
        coin (:coin market)
        quote-symbol (:quote market)
        sz (size-decimals market)
        errors (cond
                 (empty? market) [(contracts/error :data/market-metadata-unavailable {})]
                 (or (not= :spot (:market-type market))
                     (not= "USDC" (some-> quote-symbol str str/trim str/upper-case)))
                 [(contracts/error :capability/market-unsupported {})]
                 (nil? sz) [(contracts/error :data/market-metadata-unavailable {})]
                 :else [])
        base {:status (if (seq errors) :unavailable :ready)
              :errors errors
              :coin coin
              :market-type (:market-type market)
              :base-symbol (:base market)
              :quote-symbol quote-symbol
              :sz-decimals sz
              :bbo nil
              :anchors {:vwap-24h nil :utc-open nil :market-price nil}
              :auto-volatility nil
              :observed-at-ms now-ms}]
    (if (seq errors)
      base
      (let [rows (get-in state [:candles coin :1d])
            bbo (book-bbo state coin)]
        (assoc base
               :bbo bbo
               :anchors {:vwap-24h (vwap-anchor state coin)
                         :utc-open (volatility/utc-open-anchor rows now-ms coin)
                         :market-price (bbo-mid-anchor bbo)}
               :auto-volatility (auto-volatility-for-state state rows coin now-ms))))))

;; ---------------------------------------------------------------------------
;; Account snapshot
;; ---------------------------------------------------------------------------

(defn- selected-subaccount-spot-state
  "Mirror of the trading context rule: a selected owned subaccount's own spot
  state replaces the global spot state."
  [state]
  (when-let [row (account-context/selected-subaccount-row state)]
    (or (get row :spot-state) (get row :spotState) (get row "spotState"))))

(defn- spot-balances [state]
  (if-let [spot-state (selected-subaccount-spot-state state)]
    (:balances spot-state)
    (get-in state [:spot :clearinghouse-state :balances])))

(defn- balance-view [row]
  (let [total (->dec (:total row))
        hold (or (->dec (:hold row)) zero)]
    (when total
      {:total (ds total)
       :hold (ds hold)
       :available (ds (precision/dec-max zero (precision/sub total hold)))})))

(def ^:private zero-balance {:total "0" :hold "0" :available "0"})

(defn- find-balance
  "Balance for a token: by token id when the rows carry :token, else by coin
  symbol. A loaded list without the token means a zero balance."
  [balances token-id symbol]
  (when (sequential? balances)
    (let [by-token? (boolean (some #(some? (:token %)) balances))
          row (if by-token?
                (first (filter #(and token-id (= (str token-id) (str (:token %)))) balances))
                (first (filter #(and symbol (= symbol (:coin %))) balances)))]
      (if row (balance-view row) zero-balance))))

(defn- order-side [side]
  (cond (contains? #{"B" "b" "buy" "BUY" :buy :B} side) :buy
        (contains? #{"A" "a" "sell" "SELL" :sell :A} side) :sell))

(defn- preferred-orders-value [state k]
  (if (contains? (or (:orders state) {}) k)
    (get-in state [:orders k])
    (get-in state [:webdata2 k])))

(defn- open-orders
  "Open orders for the active coin. Optimistic cancels still count: pending
  cancel oids are deliberately not filtered (spec 2.2 item 5)."
  [state coin]
  (->> (account-projections/normalized-open-orders-for-active-asset
        (preferred-orders-value state :open-orders)
        (preferred-orders-value state :open-orders-snapshot)
        (preferred-orders-value state :open-orders-snapshot-by-dex)
        coin
        nil)
       (keep (fn [o]
               (let [side (order-side (:side o))
                     price (->dec-string (:px o))
                     remaining (->dec-string (:sz o))]
                 (when (and side price remaining)
                   {:oid (str (:oid o)) :side side :price price :remaining remaining}))))
       vec))

(defn account-snapshot
  "::account-snapshot for the active trading account. Without any account the
  snapshot is :unavailable."
  [state now-ms]
  (let [scope (current-scope state)
        address (or (:account-address scope) (account-context/effective-account-address state))
        errors (if address [] [(contracts/error :capability/account-required {})])
        balances (when address (spot-balances state))]
    {:status (if (seq errors) :unavailable :ready)
     :errors errors
     :account-address address
     :account-mode (get-in state [:account :mode])
     :raw-account-mode (let [raw (get-in state [:account :abstraction-raw])]
                         (when (string? raw) (str/trim raw)))
     :read-only? (boolean (account-context/mutations-blocked-message state))
     :base (when address (find-balance balances (:base-token-id scope) (:symbol scope)))
     :quote (when address (find-balance balances (:quote-token-id scope) (:quote-symbol scope)))
     :open-orders (if (and address (:coin scope)) (open-orders state (:coin scope)) [])
     :observed-at-ms now-ms}))

(defn account-snapshot-from-read
  "::account-snapshot from an awaited raw barrier read
  (`reads/read-account-snapshot!`) instead of app state, for the final
  pre-send guard (spec 8.1 step 8). Raw open orders are used before any
  optimistic cancel filtering; an incomplete read is :unavailable."
  [scope {:keys [account-address balances open-orders complete? received-at-ms]}
   {:keys [raw-account-mode read-only?]}]
  (let [ok? (and complete? (some? account-address))]
    {:status (if ok? :ready :unavailable)
     :errors (if ok? [] [(contracts/error :data/balances-unavailable {})])
     :account-address account-address
     :account-mode nil
     :raw-account-mode raw-account-mode
     :read-only? (boolean read-only?)
     :base (when ok? (find-balance balances (:base-token-id scope) (:symbol scope)))
     :quote (when ok? (find-balance balances (:quote-token-id scope) (:quote-symbol scope)))
     :open-orders (if ok?
                    (->> open-orders
                         (filter #(= (:coin scope) (:coin %)))
                         (keep (fn [o]
                                 (let [side (order-side (:side o))
                                       price (->dec-string (:limitPx o))
                                       remaining (->dec-string (:sz o))]
                                   (when (and side price remaining)
                                     {:oid (str (:oid o)) :side side :price price :remaining remaining}))))
                         vec)
                    [])
     :observed-at-ms received-at-ms}))

;; ---------------------------------------------------------------------------
;; Fee snapshot
;; ---------------------------------------------------------------------------

(defn fee-snapshot
  "::fee-capability from the user's fee quote. The generic fallback quote is
  never used for cash admission, so it yields an :unavailable capability."
  [state _now-ms]
  (let [ctx (fee-context/select-fee-context state)
        quote (trading-fees/quote-fees (:user-fees ctx)
                                       {:market-type (:market-type ctx)
                                        :stable-pair? (boolean (:stable-pair? ctx))
                                        :deployer-fee-scale (:deployer-fee-scale ctx)
                                        :growth-mode? (boolean (:growth-mode? ctx))
                                        :extra-adjustment? (boolean (:special-quote-fee-adjustment? ctx))})]
    (vr-fees/fee-capability {:quote (or quote (trading-fees/default-fee-quote))
                             :fallback? (nil? quote)
                             :loaded-at-ms (epoch-ms (get-in state [:portfolio :user-fees-loaded-at-ms]))
                             :settlement (vr-fees/live-spot-settlement)
                             :settlement-verified? false
                             :source :user-fees})))
