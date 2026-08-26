(ns hyperopen.portfolio.optimizer.application.universe-candidates
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.query :as asset-query]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as history-api-v2]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.ids :as ids]))

;; The default cap is deliberately small and deliberately SHARED: it is the only
;; bound on the proxy typeahead and on the results-page "Add to universe"
;; popover, and that popover queries with a BLANK string (universe-panel-model
;; passes :include-blank-candidates? true), which matches the entire catalog —
;; ~838 markets plus ~9,500 non-child vault rows. Callers that can render a long
;; list safely pass their own :limit through opts; nobody deletes this.
(def ^:private default-candidate-limit
  6)

;; Market types the optimizer can actually take into a universe. Anything else
;; is dropped here rather than shown and rejected later: outcome (prediction)
;; markets are concatenated into [:asset-selector :markets] upstream and
;; market->universe-instrument refuses them, so an "+ add" on one is a silent
;; no-op. The 6-cap used to hide them; a full result list would not.
(def ^:private candidate-market-types
  #{:perp :spot :vault})

(def vault-instrument-prefix
  ids/vault-instrument-prefix)

(def ^:private normalized-text coercion/non-blank-text)

(defn- raw-asset-id?
  [value]
  (let [text (normalized-text value)]
    (boolean
     (and text
          (or (str/starts-with? text "@")
              (re-matches #"\d+" text))))))

(def normalize-vault-address ids/normalize-vault-address)

(def vault-instrument-id ids/vault-instrument-id)

(def vault-address-from-instrument-id ids/vault-address-from-instrument-id)

(defn- hip3-instrument?
  [instrument]
  (boolean
   (or (:dex instrument)
       (:hip3? instrument)
       (:hip3-eligible? instrument))))

(defn- symbol-first?
  [instrument]
  (or (= :spot (:market-type instrument))
      (hip3-instrument? instrument)
      (raw-asset-id? (:coin instrument))))

(defn- base-from-symbol
  [symbol]
  (let [symbol* (normalized-text symbol)]
    (cond
      (and symbol* (str/includes? symbol* "/"))
      (normalized-text (first (str/split symbol* #"/" 2)))

      (and symbol* (str/includes? symbol* "-"))
      (normalized-text (first (str/split symbol* #"-" 2)))

      :else nil)))

(defn- base-label
  [instrument]
  (or (when (= :vault (:market-type instrument))
        (or (normalize-vault-address (:vault-address instrument))
            (vault-address-from-instrument-id (:coin instrument))
            (vault-address-from-instrument-id (:instrument-id instrument))))
      (normalized-text (:base instrument))
      (base-from-symbol (:symbol instrument))
      (when-not (raw-asset-id? (:coin instrument))
        (normalized-text (:coin instrument)))))

(defn- friendly-name
  [value]
  (case (some-> value normalized-text str/upper-case)
    "BTC" "Bitcoin"
    "ETH" "Ether"
    "SOL" "Solana"
    "HYPE" "Hyperliquid"
    "ARB" "Arbitrum"
    "LINK" "Chainlink"
    "USDC" "USD Coin"
    "PURR/USDC" "Purr"
    (or (normalized-text value) "--")))

(defn selected-instrument-ids
  [universe]
  (into #{} (keep :instrument-id) universe))

(defn- market-label
  [market-or-instrument]
  (or (when (= :vault (:market-type market-or-instrument))
        (or (normalized-text (:name market-or-instrument))
            (normalized-text (:symbol market-or-instrument))))
      (normalized-text (:symbol market-or-instrument))
      (normalized-text (:coin market-or-instrument))
      (normalized-text (:key market-or-instrument))
      (normalized-text (:instrument-id market-or-instrument))
      "Unknown Market"))

(defn- usable-market?
  [selected-ids market]
  (let [market-key (normalized-text (:key market))]
    (and market-key
         (normalized-text (:coin market))
         (contains? candidate-market-types (:market-type market))
         (not (contains? selected-ids market-key)))))

(defn market-quote
  "The quote/collateral token for a candidate, or nil when the concept does not
  apply. Reads the real :quote field first — perps carry their dex's collateral
  token and spot carries the pair's second token — and only falls back to
  splitting the symbol, whose separator differs by market type (perps are
  BASE-QUOTE, spot is BASE/QUOTE).

  Vaults return nil BY DESIGN: a vault candidate is seven keys with no :quote and
  no :base, so any fallback would file every vault under a made-up USDC."
  [market]
  (when-not (= :vault (:market-type market))
    (or (normalized-text (:quote market))
        (let [symbol (normalized-text (:symbol market))
              separator (case (:market-type market)
                          :perp "-"
                          :spot "/"
                          nil)]
          (when (and symbol separator (str/includes? symbol separator))
            (normalized-text (second (str/split symbol
                                                (re-pattern separator)
                                                2))))))))

(defn- exact-match-rank
  [query-upper market]
  (let [label-upper (some-> (market-label market) str/upper-case)
        coin-upper (some-> (:coin market) normalized-text str/upper-case)]
    (if (and (not= :vault (:market-type market))
             (seq query-upper)
             (or (= query-upper label-upper)
                 (= query-upper coin-upper)))
      0
      1)))

(defn- market-type-rank
  [market]
  (if (= :spot (:market-type market)) 0 1))

(def ^:private finite-number coercion/parse-number)

(defn- vault-tvl
  [row]
  (or (finite-number (:tvl row)) 0))

(defn- vault-name
  [row]
  (normalized-text (:name row)))

(defn vault-row?
  [row]
  (and (map? row)
       (seq (normalize-vault-address (:vault-address row)))
       (not= :child (get-in row [:relationship :type]))))

(defn- vault-row-rank
  [row]
  [(- (vault-tvl row))
   (str/lower-case (or (vault-name row) ""))
   (str/lower-case (or (normalize-vault-address (:vault-address row)) ""))])

(defn- vault-row-signature
  [row]
  [(normalize-vault-address (:vault-address row))
   (vault-name row)
   (vault-tvl row)
   (get-in row [:relationship :type])])

(defn vault-row->candidate
  [row]
  (when (vault-row? row)
    (let [vault-address (normalize-vault-address (:vault-address row))
          key (vault-instrument-id vault-address)
          name (or (vault-name row) vault-address)]
      {:key key
       :market-type :vault
       :coin key
       :vault-address vault-address
       :name name
       :symbol name
       :tvl (vault-tvl row)})))

(defn- vault-search-text
  [candidate]
  (str/lower-case
   (str (or (:name candidate) "")
        " "
        (or (:symbol candidate) "")
        " "
        (or (:vault-address candidate) "")
        " "
        (or (:key candidate) "")
        " vault")))

(defn- vault-matches-query?
  [query candidate]
  (let [query* (some-> query normalized-text str/lower-case)]
    (or (not (seq query*))
        (str/includes? (vault-search-text candidate) query*))))

(defn- vault-rows-signature
  [rows]
  (let [rows* (or rows [])]
    [(count rows*)
     (mapv (fn [row]
             (when (map? row)
               (vault-row-signature row)))
           rows*)]))

(defn vault-candidate-pool
  [rows]
  (->> rows
       (filter vault-row?)
       (sort-by vault-row-rank)
       (keep vault-row->candidate)
       vec))

(defn cached-vault-candidate-pool
  [cache-atom rows]
  (let [cache @cache-atom]
    (cond
      (and (map? cache)
           (identical? rows (:rows cache)))
      (:candidates cache)

      :else
      (let [rows-signature (vault-rows-signature rows)]
        (if (and (map? cache)
                 (= rows-signature (:rows-signature cache)))
          (do
            (reset! cache-atom (assoc cache
                                      :rows rows
                                      :rows-signature rows-signature))
            (:candidates cache))
          (let [candidates (vault-candidate-pool rows)]
            (reset! cache-atom {:rows rows
                                :rows-signature rows-signature
                                :candidates candidates})
            candidates))))))

(defn- vault-candidates-for-options
  [rows opts]
  (if-let [cache-atom (:vault-candidate-cache opts)]
    (cached-vault-candidate-pool cache-atom rows)
    (vault-candidate-pool rows)))

(defn- candidate-vaults
  [state selected-ids query opts]
  (->> (vault-candidates-for-options
        (get-in state [:vaults :merged-index-rows])
        opts)
       (remove #(contains? selected-ids (:key %)))
       (filter #(vault-matches-query? query %))
       vec))

(defn- rank-candidates
  [markets query ranking]
  (let [query-upper (some-> query normalized-text str/upper-case)]
    (case ranking
      :asset-query
      markets

      (->> markets
           (map-indexed (fn [idx market]
                          {:idx idx
                           :market market}))
           (sort-by (fn [{:keys [idx market]}]
                      [(exact-match-rank query-upper market)
                       (market-type-rank market)
                       idx]))
           (mapv :market)))))

(defn- with-history-discovery
  [state candidates]
  (let [discovery (get-in state contracts/history-discovery-path)]
    (if (seq (:backend-id-by-local-id discovery))
      (mapv #(history-api-v2/with-discovery-metadata % discovery)
            candidates)
      candidates)))

(defn resolve-market
  "Resolve a candidate market-key back to its market map: the live asset-selector
  catalog first, then the vault index (vault candidates are synthesized, so they
  never appear in market-by-key). This is the single resolution path for every
  picker fed by candidate-markets (universe add, proxy picker)."
  [state market-key]
  (when-let [market-key* (normalized-text market-key)]
    (or (get-in state [:asset-selector :market-by-key market-key*])
        (when-let [vault-address (vault-address-from-instrument-id market-key*)]
          (some (fn [row]
                  (when (= vault-address
                           (normalize-vault-address (:vault-address row)))
                    (vault-row->candidate row)))
                (get-in state [:vaults :merged-index-rows]))))))

(defn candidate-markets
  ([state universe query]
   (candidate-markets state universe query nil))
  ([state universe query opts]
   (let [selected-ids (selected-instrument-ids universe)
         query* (or (normalized-text query) "")
         ranking (or (:ranking opts) :exact-spot)
         markets (->> (asset-query/filter-and-sort-assets
                       (get-in state [:asset-selector :markets])
                       query*
                       :volume
                       :desc
                       #{}
                       false
                       false
                       :all)
                      (filter #(usable-market? selected-ids %))
                      vec)
         vaults (candidate-vaults state selected-ids query* opts)
         candidates (into (vec markets) vaults)
         limit (get opts :limit default-candidate-limit)
         ranked (rank-candidates candidates query* ranking)]
     ;; Truncate BEFORE the history-discovery enrichment, not after. The
     ;; enrichment is a per-item mapv that used to run across every match on
     ;; every keystroke; with a wide limit that is the whole catalog on a route
     ;; already known to sit heavily main-thread-blocked. Order-independent, so
     ;; moving it behind the cap changes cost, not results.
     ;; The PRE-limit match count rides along as metadata. The panel promises an
     ;; honest "N of M matches shown"; without this the view can only ever count
     ;; what survived the cap and would report "50 of 50" for a 197-match query,
     ;; which is the silent truncation this work exists to remove. Metadata keeps
     ;; the return shape a plain vector for every existing caller.
     (with-meta
       (vec
        (with-history-discovery state
          (if (number? limit)
            (take limit ranked)
            ranked)))
       {:total-match-count (count ranked)}))))

(defn market-display
  [market-or-instrument]
  (let [base-label (base-label market-or-instrument)]
    {:label (market-label market-or-instrument)
     :name (or (normalized-text (:name market-or-instrument))
               (normalized-text (:full-name market-or-instrument))
               (when (symbol-first? market-or-instrument)
                 (friendly-name base-label))
               (friendly-name (:coin market-or-instrument)))
     :base-label base-label}))

(defn active-index
  [state markets]
  (if-not (seq markets)
    0
    (let [last-index (dec (count markets))
          idx (or (some-> (get-in state contracts/ui-universe-search-active-index-path)
                          finite-number
                          js/Math.floor)
                  0)]
      (-> idx
          (max 0)
          (min last-index)))))
