(ns hyperopen.vol-range.infrastructure.reads
  "Awaitable, scoped raw reads for Vol Range admission and reconciliation
  (HO-SPEC-014 v2.1 sections 2.2 items 5-6, 7.3, 8.6).

  Each read goes through the shared `post-info!` request policy with caching
  and request sharing disabled, so the result is a fresh observation for THIS
  barrier rather than a UI read that happened to be in flight. Nothing here
  writes to the app store; callers get raw rows plus provenance and decide."
  (:require [clojure.string :as str]
            [hyperopen.api.endpoints.orders :as orders]
            [hyperopen.domain.trading.precision :as precision]))

(defn- normalize-address [a] (some-> a str str/trim str/lower-case not-empty))

(defn- fresh-opts [kind address barrier-id]
  {:priority :high
   :cache-ttl-ms 0
   :dedupe-key [:vol-range-barrier kind address barrier-id]})

(defn- dex-of [scope] (or (some-> (:dex scope) str str/trim not-empty) ""))

(defn- perp-scopes [scopes]
  (->> scopes
       (filter #(and (= :perp (:market-type %)) (string? (:coin %))))
       (map (fn [s] {:coin (:coin s) :dex (dex-of s)}))
       distinct
       vec))

(defn- position-row [state coin]
  (some (fn [entry]
          (let [p (if (map? (:position entry)) (:position entry) entry)]
            (when (= coin (:coin p)) p)))
        (:assetPositions state)))

(defn- perp-facts
  "{:position :withdrawable :leverage :leverage-type :mark-px} for `coin`
  from its dex `clearinghouseState` and its `activeAssetData`, or nil when
  either has an unexpected shape. A coin without a position row is flat."
  [state asset coin]
  (let [lev (:leverage asset)]
    (when (and (map? state) (sequential? (:assetPositions state)) (string? (:withdrawable state))
               (map? asset) (map? lev) (number? (:value lev)) (pos? (:value lev)))
      {:position (or (some-> (position-row state coin) :szi str) "0")
       :withdrawable (:withdrawable state)
       :leverage (:value lev)
       :leverage-type (if (= "isolated" (:type lev)) :isolated :cross)
       :mark-px (when (string? (:markPx asset)) (:markPx asset))})))

(defn read-account-snapshot!
  "Spot balances and raw open orders for one account, read together, plus,
  for every perp market in `scopes` ({:market-type :perp :coin :dex}), its
  dex's clearinghouse state and the coin's active asset data.
  Resolves to {:account-address :barrier-id :requested-at-ms :received-at-ms
  :balances [...] :open-orders [...] :perps {coin {:position :withdrawable
  :leverage :leverage-type :mark-px}} :complete? bool :error text-or-nil}.
  Open orders are read for the first perp dex (which includes spot) and for
  every named dex in `scopes`: the venue returns one dex per request.
  `complete?` is true only when EVERY read succeeded with the expected
  shapes; unknown completeness is false (spec 9.1). Never rejects."
  [post-info! {:keys [account-address barrier-id now-ms! scopes]}]
  (let [address (normalize-address account-address)
        now-ms! (or now-ms! #(.now js/Date))
        requested-at (now-ms!)
        perps (perp-scopes scopes)
        dexes (vec (distinct (map :dex perps)))
        named (vec (remove #{""} dexes))]
    (if-not (and address barrier-id)
      (js/Promise.resolve {:account-address nil :barrier-id barrier-id :requested-at-ms requested-at
                           :received-at-ms nil :balances [] :open-orders [] :perps {} :complete? false
                           :error "Account address and barrier id are required"})
      (-> (js/Promise.all
           (clj->js
            (concat
             [(post-info! {"type" "spotClearinghouseState" "user" address}
                          (fresh-opts :spot-balances address barrier-id))
              (post-info! {"type" "frontendOpenOrders" "user" address}
                          (fresh-opts :open-orders address barrier-id))]
             (for [dex named]
               (post-info! {"type" "frontendOpenOrders" "user" address "dex" dex}
                           (fresh-opts [:open-orders dex] address barrier-id)))
             (for [dex dexes]
               (post-info! (cond-> {"type" "clearinghouseState" "user" address}
                             (seq dex) (assoc "dex" dex))
                           (fresh-opts [:clearinghouse dex] address barrier-id)))
             (for [{:keys [coin]} perps]
               (post-info! {"type" "activeAssetData" "user" address "coin" coin}
                           (fresh-opts [:active-asset coin] address barrier-id))))))
          (.then (fn [results]
                   (let [results (vec (array-seq results))
                         [spot open] results
                         dex-open (subvec results 2 (+ 2 (count named)))
                         states (zipmap dexes (subvec results (+ 2 (count named))
                                                      (+ 2 (count named) (count dexes))))
                         assets (subvec results (+ 2 (count named) (count dexes)))
                         facts (into {} (map (fn [{:keys [coin dex]} asset]
                                               [coin (perp-facts (get states dex) asset coin)])
                                             perps assets))
                         balances (:balances spot)
                         complete? (and (map? spot) (sequential? balances) (sequential? open)
                                        (every? sequential? dex-open)
                                        (every? some? (vals facts)))]
                     {:account-address address
                      :barrier-id barrier-id
                      :requested-at-ms requested-at
                      :received-at-ms (now-ms!)
                      :balances (if (sequential? balances) (vec balances) [])
                      :open-orders (into (if (sequential? open) (vec open) [])
                                         (mapcat #(when (sequential? %) %))
                                         dex-open)
                      :perps (into {} (filter (comp some? val)) facts)
                      :complete? (boolean complete?)
                      :error (when-not complete? "Unexpected account snapshot shape")})))
          (.catch (fn [e]
                    {:account-address address :barrier-id barrier-id :requested-at-ms requested-at
                     :received-at-ms nil :balances [] :open-orders [] :perps {} :complete? false
                     :error (if (instance? js/Error e) (.-message e) (str e))}))))))

(defn with-book-mid
  "`read` (an account snapshot read) with `:mid`, the midpoint of a complete
  `read-book!` result, so perp capacities are priced at the fresh book."
  [read {:keys [bid ask complete?]}]
  (let [b (when complete? (some-> bid precision/parse-decimal))
        a (when complete? (some-> ask precision/parse-decimal))]
    (cond-> read
      (and b a) (assoc :mid (precision/decimal->string
                             (precision/divide (precision/add b a) (precision/parse-decimal "2") 12 :half-up))))))

(defn read-order-statuses!
  "Order-status lookups for original identities (oid string or CLOID), one
  request each. Resolves to a vector of {:id :status :order :error} where
  `:status` is the venue's status string, \"unknownOid\", or nil when the
  lookup itself failed; a failed lookup is never treated as non-placement."
  [post-info! {:keys [account-address ids barrier-id]}]
  (let [address (normalize-address account-address)]
    (-> (js/Promise.all
         (clj->js
          (for [id ids]
            (-> (orders/request-order-status! post-info! address id
                                              (fresh-opts :order-status address [barrier-id id]))
                (.then (fn [resp]
                         (let [status (cond (map? resp) (:status resp) :else nil)]
                           {:id id
                            :status (case status
                                      "order" (get-in resp [:order :status])
                                      "unknownOid" "unknownOid"
                                      nil)
                            :order (when (map? resp) (:order resp))
                            :error (when-not (contains? #{"order" "unknownOid"} status)
                                     "Unrecognized order status response")})))
                (.catch (fn [e]
                          {:id id :status nil :order nil
                           :error (if (instance? js/Error e) (.-message e) (str e))}))))))
        (.then (fn [arr] (vec (array-seq arr)))))))

(def fills-page-limit
  "The venue returns at most this many fills per userFillsByTime request."
  2000)

(def ^:private max-fill-pages 10)

(defn read-fills-window!
  "Raw, un-aggregated fills for one account in [start-ms, end-ms] (spec 9.2).
  Pages forward from the newest row's time with overlap; rows are de-duplicated
  by the caller's durable fill key. Resolves to {:rows [...] :complete? bool
  :requested-at-ms :received-at-ms :error :reason}. `complete?` is false when
  a page failed, a full page made no time progress, or the page cap was
  reached: coverage is never assumed. `:reason` names why it is incomplete
  (:fills/read-error, :fills/no-progress or :fills/page-cap), nil when
  complete. `:priority` defaults to :high (reconciliation); reporting
  backfill passes :low. Never rejects."
  [post-info! {:keys [account-address start-ms end-ms barrier-id now-ms! priority]}]
  (let [address (normalize-address account-address)
        now-ms! (or now-ms! #(.now js/Date))
        requested-at (now-ms!)
        done (fn [rows complete? error reason]
               {:rows rows :complete? complete? :requested-at-ms requested-at
                :received-at-ms (now-ms!) :error error :reason reason})]
    (if-not (and address barrier-id (number? start-ms) (number? end-ms))
      (js/Promise.resolve (done [] false "Account, window and barrier id are required" :fills/read-error))
      (letfn [(page [from rows n]
                (-> (post-info! {"type" "userFillsByTime" "user" address "startTime" from
                                 "endTime" end-ms "aggregateByTime" false}
                                (assoc (fresh-opts :fills address [barrier-id from]) :priority (or priority :high)))
                    (.then (fn [resp]
                             (if-not (sequential? resp)
                               (done rows false "Unexpected fills response shape" :fills/read-error)
                               (let [rows (into rows resp)
                                     newest (reduce max from (keep :time resp))]
                                 (cond
                                   (< (count resp) fills-page-limit) (done rows true nil nil)
                                   (<= newest from) (done rows false "Fill pagination made no progress" :fills/no-progress)
                                   (>= (inc n) max-fill-pages) (done rows false "Fill page cap reached" :fills/page-cap)
                                   ;; Overlap at `newest`: rows sharing that ms are re-read
                                   ;; and de-duplicated by fill key.
                                   :else (page newest rows (inc n)))))))
                    (.catch (fn [e] (done rows false (if (instance? js/Error e) (.-message e) (str e))
                                          :fills/read-error)))))]
        (page start-ms [] 0)))))

(defn read-book!
  "Fresh best bid and ask for `coin` (spec 7.3: the book must be at most 2 s
  old at admission and before HTTP; the app store only keeps books that
  visibly changed, so its timestamp can be older in a quiet market).
  Resolves to {:bid :ask :observed-at-ms :complete? :error}. Never rejects."
  [post-info! {:keys [coin barrier-id now-ms!]}]
  (let [now-ms! (or now-ms! #(.now js/Date))
        level-px (fn [side] (let [px (:px (first side))] (when (string? px) px)))]
    (if-not (and (string? coin) barrier-id)
      (js/Promise.resolve {:bid nil :ask nil :observed-at-ms nil :complete? false
                           :error "Coin and barrier id are required"})
      (-> (post-info! {"type" "l2Book" "coin" coin}
                      {:priority :high :cache-ttl-ms 0 :dedupe-key [:vol-range-barrier :book coin barrier-id]})
          (.then (fn [resp]
                   (let [[bids asks] (when (map? resp) (:levels resp))
                         bid (level-px bids)
                         ask (level-px asks)]
                     {:bid bid :ask ask :observed-at-ms (now-ms!)
                      :complete? (boolean (and bid ask))
                      :error (when-not (and bid ask) "Unexpected order book shape")})))
          (.catch (fn [e] {:bid nil :ask nil :observed-at-ms nil :complete? false
                           :error (if (instance? js/Error e) (.-message e) (str e))}))))))
