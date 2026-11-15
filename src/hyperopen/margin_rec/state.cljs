(ns hyperopen.margin-rec.state
  "App-db bucket and pure planning helpers for isolated-margin
  recommendations.

  Everything here is main-bundle-safe: no engine namespaces are required. The
  heavy math lives behind the lazy :margin_rec shadow module and is reached
  only from the compute effect adapter.

  State shape under [:margin-rec]:
    {:fills {:status :idle|:loading|:ready|:error :address nil :rows [] :fetched-at nil}
     :recs {position-key {:status .. :result .. :input-sig .. :structural-sig ..
                          :computed-at .. :error ..}}
     :computing nil | {:key position-key :started-at ms}
     :panel nil | position-key
     :candle-requests {coin requested-at-ms}
     :intents {position-key intent}}"
  (:require [clojure.string :as str]
            [hyperopen.account.history.position-identity :as position-identity]
            [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.trading-settings :as trading-settings]))

(def root-path [:margin-rec])
(def recs-path (conj root-path :recs))
(def panel-path (conj root-path :panel))
(def panel-anchor-path (conj root-path :panel-anchor))
(def intents-path (conj root-path :intents))
(def batch-path (conj root-path :batch))
(def fills-path (conj root-path :fills))
(def computing-path (conj root-path :computing))
(def candle-requests-path (conj root-path :candle-requests))

(def candle-interval :1h)
(def candle-fetch-bars 1104)
(def min-candle-rows 61)
(def ^:private candle-stale-ms (* 2 3600000))
(def ^:private candle-request-cooldown-ms (* 5 60000))
(def ^:private recompute-min-interval-ms 120000)
(def ^:private mark-bucket-width 0.0025)
(def intent-ttl-ms 180000)
(def intent-size-tolerance 0.02)
(def intent-min-topup-usd 1.0)

(defn default-state
  []
  {:fills {:status :idle :address nil :rows [] :fetched-at nil}
   :recs {}
   :computing nil
   :panel nil
   :panel-anchor nil
   :candle-requests {}
   :intents {}
   :batch {:open? false :anchor nil :deselected #{}}})

(defn- parse-num
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (js/parseFloat value)
            :else js/NaN)]
    (when (and (number? n) (js/isFinite n))
      n)))

(def ^:private isolated-margin-tokens
  #{"isolated" "isolatedmargin" "nocross" "strictisolated"})

(defn- normalize-margin-token
  [value]
  (let [text (cond
               (keyword? value) (name value)
               (string? value) value
               :else nil)]
    (some-> text str/trim str/lower-case (str/replace #"[\s_-]" ""))))

(defn isolated-position?
  [position]
  (let [leverage-token (normalize-margin-token
                        (or (get-in position [:leverage :type])
                            (get-in position [:leverage :mode])))
        margin-mode-token (normalize-margin-token (:marginMode position))
        is-cross (:isCross position)]
    (boolean
     (or (contains? isolated-margin-tokens leverage-token)
         (contains? isolated-margin-tokens margin-mode-token)
         (false? is-cross)))))

(defn market-for
  [market-by-key coin dex]
  (let [dex* (or dex "")]
    (some (fn [market]
            (when (and (= :perp (:market-type market))
                       (= coin (:coin market))
                       (= dex* (or (:dex market) "")))
              market))
          (vals (or market-by-key {})))))

(defn isolated-only-market?
  [market]
  (boolean (or (:only-isolated? market)
               (:onlyIsolated market))))

(defn- position-entry
  [state dex position]
  (let [szi (parse-num (:szi position))
        abs-size (when szi (js/Math.abs szi))
        position-value (parse-num (:positionValue position))
        mark (when (and position-value abs-size (pos? abs-size))
               (/ position-value abs-size))
        coin (:coin position)]
    (when (and (string? coin) (seq coin)
               szi (not (zero? szi))
               mark (pos? mark))
      (let [market (market-for (get-in state [:asset-selector :market-by-key])
                               coin
                               dex)
            position-data {:position position :dex dex}]
        {:position-key (position-identity/position-unique-key position-data)
         :coin coin
         :dex dex
         :position position
         :position-data position-data
         :szi szi
         :mark mark
         :equity (max 0 (or (parse-num (:marginUsed position)) 0))
         :entry-px (parse-num (:entryPx position))
         :liquidation-px (parse-num (:liquidationPx position))
         :max-leverage (or (parse-num (:maxLeverage position))
                           (parse-num (:maxLeverage market))
                           (parse-num (:max-leverage market)))
         :margin-table (get-in state [:asset-contexts (keyword coin) :margin])
         :funding-rate-hourly (or (parse-num (:fundingRate market))
                                  (parse-num
                                   (get-in state [:asset-contexts (keyword coin)
                                                  :funding :funding])))}))))

(defn isolated-positions
  "Open isolated positions across the default dex and every named dex,
  enriched with the market/meta inputs the engine needs."
  [state]
  (let [base (->> (get-in state [:webdata2 :clearinghouseState :assetPositions])
                  (keep :position)
                  (filter isolated-position?)
                  (keep #(position-entry state nil %)))
        named (mapcat (fn [[dex clearinghouse]]
                        (->> (:assetPositions clearinghouse)
                             (keep :position)
                             (filter isolated-position?)
                             (keep #(position-entry state dex %))))
                      (get state :perp-dex-clearinghouse))]
    (->> (concat base named)
         (reduce (fn [acc entry]
                   (if (contains? acc (:position-key entry))
                     acc
                     (assoc acc (:position-key entry) entry)))
                 {})
         vals
         vec)))

(defn mark-bucket
  [mark]
  (js/Math.round (/ (js/Math.log mark) mark-bucket-width)))

(defn structural-sig
  [{:keys [szi equity]}]
  [(js/Math.round (* 1e8 szi))
   (js/Math.round (* 100 equity))])

(defn input-sig
  [{:keys [coin dex szi equity mark] :as entry} candle-last-t]
  [coin
   (or dex "")
   (structural-sig entry)
   (mark-bucket mark)
   candle-last-t])

(defn risk-mode
  [state]
  (trading-settings/margin-rec-risk-mode state))

(defn candle-rows
  [state coin]
  (get-in state [:candles coin candle-interval]))

(defn- candle-last-t
  [rows]
  (when (seq rows)
    (:t (if (vector? rows) (peek rows) (last rows)))))

(defn- candles-ready?
  [rows now-ms]
  (and (>= (count rows) min-candle-rows)
       (let [last-t (candle-last-t rows)]
         (and (number? last-t)
              (< (- now-ms last-t) candle-stale-ms)))))

(defn- candles-fetch-needed?
  [state coin rows now-ms]
  (and (not (candles-ready? rows now-ms))
       (let [requested-at (get-in state (conj candle-requests-path coin))]
         (or (nil? requested-at)
             (> (- now-ms requested-at) candle-request-cooldown-ms)))))

(defn rec-for
  [state position-key]
  (get-in state (conj recs-path position-key)))

(defn- compute-needed?
  [rec sig structural now-ms]
  (cond
    (nil? rec) true
    (= sig (:input-sig rec)) false
    (not= structural (:structural-sig rec)) true
    :else (> (- now-ms (or (:computed-at rec) 0)) recompute-min-interval-ms)))

(defn- compute-priority
  [state rec entry]
  [(if (= (get-in state panel-path) (:position-key entry)) 0 1)
   (if (not= (structural-sig entry) (:structural-sig rec)) 0 1)
   (or (:computed-at rec) 0)])

(defn fills-rows
  [state]
  (get-in state (conj fills-path :rows)))

(defn fills-fetch-needed?
  [state address]
  (let [{:keys [status] fills-address :address} (get-in state fills-path)]
    (and (seq address)
         (or (= :idle status)
             (and (not= :loading status)
                  (not= fills-address address))))))

(defn compute-job
  "The single highest-priority compute job that is ready to run, or nil."
  [state entries now-ms]
  (when (nil? (get-in state computing-path))
    (let [mode (risk-mode state)
          candidates
          (->> entries
               (keep (fn [entry]
                       (let [rows (candle-rows state (:coin entry))]
                         (when (candles-ready? rows now-ms)
                           (let [sig (input-sig entry (candle-last-t rows))
                                 rec (rec-for state (:position-key entry))]
                             (when (compute-needed? rec sig (structural-sig entry) now-ms)
                               {:entry entry
                                :sig sig
                                :priority (compute-priority state rec entry)}))))))
               (sort-by :priority))]
      (when-let [{:keys [entry sig]} (first candidates)]
        {:key (:position-key entry)
         :input-sig sig
         :structural-sig (structural-sig entry)
         :inputs {:coin (:coin entry)
                  :dex (:dex entry)
                  :szi (:szi entry)
                  :mark (:mark entry)
                  :margin-used (:equity entry)
                  :liquidation-px (:liquidation-px entry)
                  :max-leverage (:max-leverage entry)
                  :margin-table (:margin-table entry)
                  :funding-rate-hourly (:funding-rate-hourly entry)
                  :candle-rows (candle-rows state (:coin entry))
                  :fills (fills-rows state)
                  :risk-mode mode}}))))

(defn plan-work
  "Pure diff of desired background work against current state.
  Returns {:candle-coins [..] :fills-address addr|nil :job compute-job|nil
           :prune-keys [..]}."
  [state address now-ms]
  (let [entries (isolated-positions state)
        live-keys (set (map :position-key entries))
        prune-keys (->> (keys (get-in state recs-path))
                        (remove live-keys)
                        vec)
        candle-coins (->> entries
                          (filter #(candles-fetch-needed? state
                                                          (:coin %)
                                                          (candle-rows state (:coin %))
                                                          now-ms))
                          (map :coin)
                          distinct
                          vec)
        fills-address (when (and (seq entries)
                                 (fills-fetch-needed? state address))
                        address)]
    {:entries entries
     :prune-keys prune-keys
     :candle-coins candle-coins
     :fills-address fills-address
     :job (when (seq entries)
            (compute-job state entries now-ms))}))

(defn watch-fingerprint
  "Cheap decision-input fingerprint for the store watcher: when this changes,
  a sync (and intent-processing) pass is worth dispatching."
  [state]
  (let [entries (isolated-positions state)]
    [(mapv (fn [{:keys [position-key] :as entry}]
             [position-key
              (structural-sig entry)
              (mark-bucket (:mark entry))])
           entries)
     (mapv (fn [{:keys [coin]}]
             (let [rows (candle-rows state coin)]
               [coin (count rows) (candle-last-t rows)]))
           entries)
     (risk-mode state)
     (trading-settings/margin-rec-auto-topup? state)
     (get-in state (conj fills-path :status))
     (get-in state computing-path)
     (mapv (fn [[k rec]] [k (:computed-at rec) (:status rec)])
           (sort-by key (get-in state recs-path)))
     (mapv (fn [[k intent]] [k (:status intent)])
           (sort-by key (get-in state intents-path)))
     (get-in state panel-path)]))

(defn apply-fills
  [state address rows fetched-at]
  (assoc-in state fills-path
            {:status :ready
             :address address
             :rows (->> rows
                        (keep (fn [row]
                                (when (map? row)
                                  (select-keys row [:coin :time :side :sz :startPosition]))))
                        vec)
             :fetched-at fetched-at}))

(defn apply-fills-error
  [state address]
  (update-in state fills-path assoc :status :error :address address))

(defn apply-computing
  [state job now-ms]
  (-> state
      (assoc-in computing-path {:key (:key job) :started-at now-ms})
      (update-in (conj recs-path (:key job))
                 (fn [rec]
                   (assoc (or rec {}) :status (or (:status rec) :computing))))))

(defn apply-result
  [state {:keys [key input-sig structural-sig]} result now-ms]
  (-> state
      (assoc-in computing-path nil)
      (assoc-in (conj recs-path key)
                {:status (:status result)
                 :result result
                 :input-sig input-sig
                 :structural-sig structural-sig
                 :computed-at now-ms
                 :error nil})))

(defn apply-compute-error
  [state {:keys [key input-sig structural-sig]} message now-ms]
  (-> state
      (assoc-in computing-path nil)
      (assoc-in (conj recs-path key)
                {:status :error
                 :result nil
                 :input-sig input-sig
                 :structural-sig structural-sig
                 :computed-at now-ms
                 :error message})))

(defn dex-for-coin
  [coin]
  (when (and (string? coin) (str/includes? coin ":"))
    (first (str/split coin #":"))))

(defn position-key-for-coin
  "Position key for a coin string, matching
  hyperopen.account.history.position-identity/position-unique-key for rows
  assembled from clearinghouse state (named-dex rows carry their dex)."
  [coin]
  (str coin "|" (or (dex-for-coin coin) "default")))

(defn make-intent-draft
  "Auto top-up intent created by a pure action (no clock access). The first
  intent-processing pass stamps :created-at/:expires-at."
  [{:keys [position-key coin dex expected-size target-equity max-add source]}]
  {:position-key position-key
   :coin coin
   :dex dex
   :expected-size expected-size
   :target-equity target-equity
   :max-add max-add
   :source source
   :status :pending
   :created-at nil
   :expires-at nil})

(defn make-intent
  [draft-fields now-ms]
  (assoc (make-intent-draft draft-fields)
         :created-at now-ms
         :expires-at (+ now-ms intent-ttl-ms)))

(defn stamp-intent
  "Ensure a pending intent has its expiry clock started."
  [intent now-ms]
  (if (some? (:expires-at intent))
    intent
    (assoc intent
           :created-at now-ms
           :expires-at (+ now-ms intent-ttl-ms))))

(defn isolated-execution-legs
  "Optimizer execution-plan rows that will open or grow an isolated-margin
  perp position: ready perp rows on isolated-only markets. Each returned leg
  carries the fields an auto top-up intent needs."
  [state rows]
  (let [market-by-key (get-in state [:asset-selector :market-by-key])
        current-by-key (into {}
                             (map (juxt :position-key :szi))
                             (isolated-positions state))]
    (->> rows
         (filter (fn [{:keys [status instrument-type coin side quantity]}]
                   (and (= :ready status)
                        (= :perp instrument-type)
                        (string? coin)
                        (seq coin)
                        (contains? #{:buy :sell} side)
                        (number? quantity)
                        (pos? quantity))))
         (keep (fn [{:keys [coin side quantity delta-notional-usd]}]
                 (let [dex (dex-for-coin coin)
                       market (market-for market-by-key coin dex)]
                   (when (isolated-only-market? market)
                     (let [position-key (position-key-for-coin coin)
                           current (or (get current-by-key position-key) 0)
                           expected (+ current (if (= :buy side) quantity (- quantity)))
                           expected-size (js/Math.abs expected)]
                       (when (> expected-size (js/Math.abs current))
                         {:position-key position-key
                          :coin coin
                          :dex dex
                          :expected-size expected-size
                          :max-add (when (number? delta-notional-usd)
                                     (js/Math.abs delta-notional-usd))}))))))
         vec)))

(defn intent-matches-position?
  [{:keys [expected-size]} {:keys [szi]}]
  (and (number? expected-size)
       (pos? expected-size)
       (<= (/ (js/Math.abs (- (js/Math.abs szi) expected-size))
              expected-size)
           intent-size-tolerance)))

(defn select-risk-mode
  "Project a recommendation result onto one risk mode: the alpha-dependent
  fields (`:recommended`, `:p-after`, `:status`, `:breakdown`,
  `:reduce-suggestion`, `:alpha`, `:risk-mode`) come from the precomputed
  `:by-risk-mode` table, the alpha-independent fields stay as they are. Falls
  back to the stored active mode when the table or the requested mode is absent
  (older cached results, or a not-yet-recomputed entry)."
  [result mode]
  (if-let [per-mode (get-in result [:by-risk-mode mode])]
    (merge result per-mode)
    result))

(defn ready-recommended-equity
  "Recommended equity from a completed recommendation for the active risk mode,
  when available."
  [state position-key]
  (let [{:keys [status result]} (rec-for state position-key)]
    (when (contains? #{:ok :within-target} status)
      (get-in (select-risk-mode result (risk-mode state))
              [:recommended :equity]))))

(def batch-risk-levels
  "Risk levels that count as \"facing liquidation\" for the batch top-up."
  #{:high :elevated})

(defn batch-candidates
  "Isolated positions currently facing high/elevated modeled liquidation risk
  with an actionable recommended top-up under `mode`, sorted most-at-risk
  (highest p-now) first."
  [state mode]
  (->> (isolated-positions state)
       (keep (fn [{:keys [position-key] :as entry}]
               (let [{:keys [status result]} (rec-for state position-key)
                     selected (select-risk-mode result mode)
                     additional (get-in selected [:recommended :additional])]
                 (when (and (= :ok status)
                            (contains? batch-risk-levels (:risk-level result))
                            (number? additional)
                            (>= additional 0.01))
                   {:position-key position-key
                    :coin (:coin entry)
                    :dex (:dex entry)
                    :position-data (:position-data entry)
                    :equity (:equity entry)
                    :additional additional
                    :target-equity (get-in selected [:recommended :equity])
                    :new-liquidation-px (get-in selected [:recommended :new-liquidation-px])
                    :p-now (:p-now selected)
                    :p-after (:p-after selected)
                    :risk-level (:risk-level result)}))))
       (sort-by (fn [{:keys [p-now]}] (- (or p-now 0))))
       vec))

(defn batch-computing-count
  "Isolated positions whose recommendation has not resolved yet — shown in the
  batch panel so a partial list is never mistaken for the full picture."
  [state]
  (->> (isolated-positions state)
       (filter (fn [{:keys [position-key]}]
                 (let [{:keys [status]} (rec-for state position-key)]
                   (or (nil? status) (= :computing status)))))
       count))

(defn batch-ui
  [state]
  (merge {:open? false :anchor nil :deselected #{}}
         (get-in state batch-path)))

(defn batch-available-pools
  "Available collateral per dex pool for the given candidates. Named-dex
  clearinghouses are separate pools; the default/unified pool is shared."
  [state candidates]
  (reduce (fn [pools {:keys [dex position-data]}]
            (let [pool-key (or dex "")]
              (if (contains? pools pool-key)
                pools
                (assoc pools pool-key
                       (or (:available-to-add
                            (position-margin/from-position-row state position-data))
                           0)))))
          {}
          candidates))

(defn batch-coverage
  "How much of the selected candidates' total top-up the per-dex pools cover
  when drained in candidate order (most-severe-first). Returns
  {:total :covered :fundable-count :skipped-count}."
  [candidates pools]
  (reduce (fn [acc {:keys [dex additional]}]
            (let [pool-key (or dex "")
                  available (get-in acc [:pools pool-key] 0)
                  amount (min additional available)
                  fundable? (>= amount intent-min-topup-usd)]
              (-> acc
                  (update :total + additional)
                  (cond->
                   fundable? (-> (update :covered + amount)
                                 (update :fundable-count inc)
                                 (assoc-in [:pools pool-key] (- available amount)))
                   (not fundable?) (update :skipped-count inc)))))
          {:total 0 :covered 0 :fundable-count 0 :skipped-count 0 :pools pools}
          candidates))

(defn ui-slice
  "The margin-rec slice the positions view model consumes."
  [state]
  (let [candidates (batch-candidates state (risk-mode state))]
    {:recs (get-in state recs-path {})
     :panel (get-in state panel-path)
     :panel-anchor (get-in state panel-anchor-path)
     :computing (get-in state computing-path)
     :risk-mode (risk-mode state)
     :auto-topup? (trading-settings/margin-rec-auto-topup? state)
     :batch (batch-ui state)
     :batch-candidates candidates
     :batch-computing-count (batch-computing-count state)
     :batch-available-pools (batch-available-pools state candidates)}))

(defn modal-hint
  "Prefill hint for the Adjust Margin modal: the recommended top-up for the
  position under the active risk mode, when one is actionable."
  [recs position-key mode]
  (let [{:keys [status result]} (get recs position-key)
        result (select-risk-mode result mode)]
    (when (= :ok status)
      (let [additional (get-in result [:recommended :additional])]
        (when (and (number? additional)
                   (>= additional 0.01))
          {:additional additional
           :target (get-in result [:recommended :equity])
           :p-now (:p-now result)
           :p-after (:p-after result)})))))
