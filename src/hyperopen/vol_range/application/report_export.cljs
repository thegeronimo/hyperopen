(ns hyperopen.vol-range.application.report-export
  "Vol Range report export (HO-SPEC-014 v2.1 section 12.8): a reproducible
  JSON bundle and a human-readable fills CSV of the report the Portfolio
  shows, built only from this browser's loaded report state
  (`[:vol-range :report]`) and spot metadata.

  The JSON bundle (`json-document`) carries the export format and every
  model version; the valuation method and policy; each tracking campaign
  with its immutable baseline, closure, coverage intervals, breaks and the
  Since tracking began, 7D and 30D windows (boundaries, marks with their
  source and candle times, boundary inventory and cash, figures or why they
  are unavailable); the order mappings (CLOID and oid to ladder, operation,
  logical leg and rung, and the ladder a replacement was built from); every
  stored fill with its attribution, signed fee by its actual token and
  signed deltas; ledger updates; coverage records; balance checks; and the
  stored hourly closes. Keys are whitelisted at construction, and
  `sanitize` removes any key that names signing material (`forbidden-key?`)
  at every depth, including inside raw venue rows.

  The CSV (`fills-csv`) lists the same fills with plain column names (it
  says Ladder, never the internal name). Cells are RFC 4180 quoted; a text
  cell that a spreadsheet would read as a formula is prefixed with `'`.
  Pure."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.application.report-coverage :as coverage]
            [hyperopen.vol-range.application.report-windows :as windows]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.report-ledger :as ledger]))

(def format-id "hyperopen.vol-range.report")

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

;; ---------------------------------------------------------------------------
;; Forbidden keys
;; ---------------------------------------------------------------------------

(def ^:private forbidden-key-names
  "Normalized (lower-case, no separators) key names that could hold signing
  material. Exports never carry them."
  #{"privatekey" "signature" "signatures" "session" "agentkey" "secret" "unlockedsession" "nonce"
    "signedaction" "mnemonic" "seedphrase" "apikey" "apisecret"})

(defn forbidden-key?
  "True for a map key (keyword or string) that names signing material."
  [k]
  (let [n (cond (keyword? k) (name k) (string? k) k :else nil)]
    (boolean (and n (contains? forbidden-key-names (str/replace (str/lower-case n) #"[-_\s]" ""))))))

(defn sanitize
  "`x` with every forbidden key removed at every depth."
  [x]
  (cond
    (map? x) (into {} (keep (fn [[k v]] (when-not (forbidden-key? k) [k (sanitize v)]))) x)
    (vector? x) (mapv sanitize x)
    (sequential? x) (mapv sanitize x)
    (set? x) (mapv sanitize (sort-by str x))
    :else x))

;; ---------------------------------------------------------------------------
;; Report pieces
;; ---------------------------------------------------------------------------

(defn- market-label
  ([c] (let [{:keys [symbol quote-symbol coin]} (:scope c)]
         (str (or symbol coin) (when quote-symbol (str "/" quote-symbol)))))
  ([markets coin]
   (if-let [m (get-in markets [:by-name coin])]
     (str (or (:base-symbol m) (:base-token-id m)) "/" (or (:quote-symbol m) (:quote-token-id m)))
     coin)))

(def ^:private campaign-keys
  [:campaign-id :campaign-key :campaign-schema-version :accounting-model-version :model-versions :started-by
   :first-operation-id :first-grid-id :t0-ms :supersedes-campaign-id :saved-at-ms])
(def ^:private scope-keys
  [:network :account-address :coin :spot-pair-index :base-token-id :quote-token-id :symbol :quote-symbol
   :sz-decimals])
(def ^:private baseline-keys [:q0 :p0 :p0-source :p0-bid :p0-ask :p0-observed-at-ms :c0])
(def ^:private boundary-keys [:barrier-id :requested-at-ms :received-at-ms :skew-ms])
(def ^:private closure-keys [:ended-at-ms :reason :successor-campaign-id :saved-at-ms])
(def ^:private interval-keys [:from-ms :to-ms :state :reason])
(def ^:private break-keys [:at-ms :time :reason :source :event-id])
(def ^:private mark-keys [:status :price :source :candle-open-ms :observed-at-ms])
(def ^:private order-keys
  [:grid-id :operation-id :logical-id :side :rung-index :campaign-key :recovered? :sent-after-ms
   :predecessor-grid-id])
(def ^:private fill-keys
  [:fill-key :network :time :coin :side :px :sz :fee :fee-token :builder-fee :crossed :tid :oid :cloid :hash
   :dir :start-position :source :received-at-ms])
(def ^:private delta-keys [:base-delta :cash-delta :net-base-delta :net-cash-delta :fee-kind])
(def ^:private ledger-update-keys
  [:record-id :time :hash :delta-type :token :base-token-id :amount :classification :raw-delta :saved-at-ms])
(def ^:private coverage-record-keys [:record-id :stream :base-token-id :complete :saved-at-ms])
(def ^:private failure-keys [:from-ms :to-ms :reason :at-ms :permanent?])
(def ^:private balance-check-keys
  [:record-id :campaign-id :requested-at-ms :received-at-ms :observed-total :expected :state
   :last-match-at-ms :evaluated-at-ms :saved-at-ms])

(defn- pick [m ks] (when (map? m) (select-keys m ks)))

(defn- coverage-view [result]
  (-> (select-keys result [:state :reason :from-ms :to-ms :headline-allowed?])
      (assoc :intervals (mapv #(pick % interval-keys) (:intervals result))
             :breaks (mapv #(pick % break-keys) (:breaks result)))))

(defn- window-view [{:keys [range result]}]
  (merge (select-keys range [:window :a-ms :b-ms :a-source])
         {:status (:status result)
          :reason (:reason result)
          :a-mark (pick (:a-mark result) mark-keys)
          :b-mark (pick (:b-mark result) mark-keys)
          :q-a (:q-a result) :c-a (:c-a result) :q-b (:q-b result) :c-b (:c-b result)
          :figures (:figures result)
          :coverage (some-> (:coverage result) (select-keys [:state :reason :from-ms :to-ms :headline-allowed?]))
          :activity (:activity result)}))

(defn- campaign-entry
  "One campaign's export entry, with what the fills list needs:
  {:entry :events :breaks}."
  [{:keys [report markets now-ms]} c]
  (let [ended (get-in report [:closures (:campaign-id c) :ended-at-ms])
        led (:ledger report)
        token (some-> (get-in c [:scope :base-token-id]) str)
        found (attribution/campaign-findings {:campaign c :ended-at-ms ended
                                              :fills (vals (:fills led)) :updates (vals (:updates led))
                                              :markets markets :index (:attribution report)
                                              :unreadable (:unreadable report)})
        cov-of (fn [until]
                 (coverage/campaign-coverage {:campaign c
                                              :coverage {:fills (get-in led [:coverage :fills token])
                                                         :ledger (get-in led [:coverage :ledger token])}
                                              :breaks (:breaks found) :balance-checks (:balance-checks led)
                                              :until-ms until :unreadable (:unreadable found)
                                              :storage-ok? (not (false? (:storage-ok? report)))}))
        coin-marks (get-in report [:marks (get-in c [:scope :coin])])
        window-of (fn [w]
                    (let [rng (windows/resolve-window {:window w :campaign c :now-ms now-ms})
                          cov (when (= :ok (:status rng)) (cov-of (:b-ms rng)))]
                      {:range rng
                       :result (windows/campaign-window-figures {:campaign c :range rng :events (:events found)
                                                                 :coverage cov :coin-marks coin-marks})}))]
    {:events (:events found)
     :breaks (:breaks found)
     :entry (merge (select-keys c campaign-keys)
                   {:market (market-label c)
                    :open? (nil? ended)
                    :scope (pick (:scope c) scope-keys)
                    :baseline (assoc (pick (:baseline c) baseline-keys)
                                     :boundary (pick (get-in c [:baseline :boundary]) boundary-keys))
                    :closure (some-> (get-in report [:closures (:campaign-id c)]) (pick closure-keys))
                    :coverage (coverage-view (cov-of (if (number? ended) (min ended now-ms) now-ms)))
                    :unreadable (mapv #(pick % [:time :record-id]) (:unreadable found))
                    :fill-keys (mapv :event-id (:events found))
                    :activity (ledger/activity (:events found))
                    ;; Headline windows exist only for open campaigns.
                    :windows (if (nil? ended) (mapv (comp window-view window-of) windows/window-ids) [])})}))

(defn- orders
  "One row per CLOID or oid of the attribution index: the ladder, operation,
  logical leg and rung it belongs to."
  [{:keys [by-cloid by-oid]}]
  (let [oids-by-entry (group-by val by-oid)
        with-cloid (for [[cloid entry] (sort-by key by-cloid)]
                     (merge {:cloid cloid :oid (some-> (get oids-by-entry entry) first key)} (pick entry order-keys)))
        paired (set (keep :oid with-cloid))
        oid-only (for [[oid entry] (sort-by key by-oid) :when (not (contains? paired oid))]
                   (merge {:cloid nil :oid oid} (pick entry order-keys)))]
    (vec (concat with-cloid oid-only))))

(defn- tid-key [tid] (let [t (str tid)] [(count t) t]))

(defn- fill-entries [{:keys [report markets]} campaign-parts]
  (let [tracked (into {} (for [{:keys [events]} campaign-parts e events] [(:event-id e) e]))
        breaks (group-by :event-id (for [{:keys [entry breaks]} campaign-parts
                                         b breaks :when (= :fill (:source b))]
                                     {:event-id (:event-id b) :campaign-id (:campaign-id entry)
                                      :reason (:reason b) :at-ms (:at-ms b)}))
        index (:attribution report)]
    (->> (vals (get-in report [:ledger :fills]))
         (sort-by (fn [f] [(:time f) (tid-key (:tid f)) (str (:fill-key f))]))
         (mapv (fn [f]
                 (let [e (get tracked (:fill-key f))]
                   (merge (pick f fill-keys)
                          {:market (market-label markets (:coin f))
                           :tracked? (some? e)
                           :campaign-id (:campaign-id e)
                           :order (pick (attribution/attribution-for index f) order-keys)
                           :deltas (pick e delta-keys)
                           :breaks (mapv #(dissoc % :event-id) (get breaks (:fill-key f)))})))))))

(defn- marks-entries [marks]
  (vec (for [[coin {:keys [closes covered fetched-at-ms error]}] (sort-by key marks)]
         {:coin coin
          :source "candleSnapshot 1h close"
          :closes (mapv (fn [[open close]] {:candle-open-ms open :close close}) (sort-by key closes))
          :covered (vec covered)
          :fetched-at-ms fetched-at-ms
          :error error})))

(def valuation
  "How the windows are valued (spec 12.3, 12.4)."
  {:quote-token "USDC"
   :interval "half-open [a,b): a window counts events with a <= time < b; the state at x counts events before x"
   :window-end "b = floor((exported-at - mark-settle-ms) / 1h) * 1h"
   :since-start "a = T0, valued at the inception book midpoint P0 with q0 and C0 = 0"
   :fixed-windows "7D and 30D: a = b - 7d or b - 30d, unavailable (never clipped) while a < T0"
   :mark "the close of the completed 1h candle opening one hour before the boundary; a missing close makes the window unavailable"
   :equations {:net-pnl "(C_b - C_a) + q_b * P_b - q_a * P_a"
               :hold-pnl "q_a * (P_b - P_a)"
               :contribution "(C_b - C_a) + (q_b - q_a) * P_b"}
   :fees "signed, in their actual token; a base fee changes base, a USDC fee changes cash"
   :cost-basis "unavailable"})

(defn versions []
  {:export-format contracts/export-format-version
   :accounting-model contracts/accounting-model-version
   :campaign-schema contracts/campaign-schema-version
   :ledger-schema contracts/ledger-schema-version
   :model contracts/model-version
   :precision contracts/precision-version
   :estimator contracts/estimator-version})

(defn json-document
  "The JSON bundle (as data) of the loaded report for `account-address` at
  `now-ms`. `report` is `[:vol-range :report]`, `spot-meta` `[:spot :meta]`."
  [{:keys [report spot-meta account-address now-ms]}]
  (let [markets (attribution/spot-markets spot-meta)
        ctx {:report report :markets markets :now-ms now-ms}
        parts (mapv #(campaign-entry ctx %)
                    (sort-by (juxt :campaign-key :t0-ms :campaign-id) (vals (:campaigns report))))
        led (:ledger report)]
    (sanitize
     {:format format-id
      :format-version contracts/export-format-version
      :exported-at-ms now-ms
      :network (or (some #(get-in % [:scope :network]) (vals (:campaigns report))) (contracts/current-network))
      :account-address (lower account-address)
      :versions (versions)
      :valuation valuation
      :policy contracts/report-policy
      :selection (select-keys (:ui report) [:window :selected-campaign-key :covered-subset?])
      :storage {:journal-ok? (not (false? (:journal-ok? report))) :ledger-ok? (not (false? (:storage-ok? report)))
                :loaded-at-ms (:loaded-at-ms report) :unreadable-record-ids (vec (:unreadable report))}
      :campaigns (mapv :entry parts)
      :orders (orders (:attribution report))
      :fills (fill-entries ctx parts)
      :ledger-updates (->> (vals (:updates led)) (sort-by (juxt :time :record-id)) (mapv #(pick % ledger-update-keys)))
      :coverage-records (vec (for [stream [:fills :ledger]
                                   [_ r] (sort-by key (get-in led [:coverage stream]))]
                               (assoc (pick r coverage-record-keys)
                                      :failures (mapv #(pick % failure-keys) (:failures r)))))
      :balance-checks (->> (:balance-checks led) (sort-by (juxt :campaign-id :requested-at-ms))
                           (mapv #(pick % balance-check-keys)))
      :marks (marks-entries (:marks report))})))

;; ---------------------------------------------------------------------------
;; JSON text
;; ---------------------------------------------------------------------------

(defn- key-text [k]
  (cond (keyword? k) (subs (str k) 1) (string? k) k :else (str k)))

(defn ->js
  "`x` as plain JS data: keywords (keys and values) keep their namespace
  (\"break/base-transfer\"), map keys become strings."
  [x]
  (cond
    (map? x) (let [o (js-obj)]
               (doseq [[k v] x] (aset o (key-text k) (->js v)))
               o)
    (or (sequential? x) (set? x)) (into-array (map ->js x))
    (keyword? x) (subs (str x) 1)
    :else x))

(defn json-text
  "The bundle as pretty-printed JSON text."
  [doc]
  (js/JSON.stringify (->js doc) nil 2))

;; ---------------------------------------------------------------------------
;; CSV
;; ---------------------------------------------------------------------------

(def csv-columns
  ["Time (UTC)" "Market" "Coin" "Side" "Price" "Size" "Fee" "Fee token" "Builder fee" "Liquidity" "Tracking"
   "Tracking ID" "Ladder" "Operation" "Order leg" "Rung" "Client order ID" "Order ID" "Trade ID"
   "Transaction hash" "Source" "Outside activity"])

(defn- iso-time [t] (when (number? t) (.toISOString (js/Date. t))))

(defn- decimal-text? [s] (and (re-matches #"-?\d+(\.\d+)?" s) (some? (precision/parse-decimal s))))

(defn csv-cell
  "One RFC 4180 cell. Text a spreadsheet would read as a formula (starting
  with an equals, plus, minus or at sign, a tab or a CR) gets a leading
  `'`; plain decimals are left as they are."
  [x]
  (let [s (cond (nil? x) "" (keyword? x) (subs (str x) 1) :else (str x))
        s (if (and (re-find #"^[=+\-@\t\r]" s) (not (decimal-text? s))) (str "'" s) s)]
    (if (re-find #"[\",\r\n]|^\s|\s$" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn- side-text [side] (case side :buy "Buy" :sell "Sell" nil))
(defn- liquidity-text [crossed] (case crossed true "Taker" false "Maker" nil))

(defn- fill-row [f]
  (let [o (:order f)]
    [(iso-time (:time f)) (:market f) (:coin f) (side-text (:side f)) (:px f) (:sz f) (:fee f)
     (some-> (:fee-token f) ledger/token-name) (:builder-fee f) (liquidity-text (:crossed f))
     (if (:tracked? f) "Tracked" "Not tracked") (:campaign-id f) (:grid-id o) (:operation-id o)
     (:logical-id o) (:rung-index o) (:cloid f) (:oid f) (:tid f) (:hash f) (some-> (:source f) name)
     (str/join "; " (distinct (map #(subs (str (:reason %)) 1) (:breaks f))))]))

(defn fills-csv
  "The bundle's fills as CSV text (header first, CRLF line ends)."
  [doc]
  (str (str/join "\r\n" (map #(str/join "," (map csv-cell %))
                             (cons csv-columns (map fill-row (:fills doc)))))
       "\r\n"))

;; ---------------------------------------------------------------------------
;; File names
;; ---------------------------------------------------------------------------

(defn- pad2 [n] (if (< n 10) (str "0" n) (str n)))

(defn filename
  "\"hyperopen-vol-range-report-0x123456-20261029T0712Z.json\" (or
  \"…-fills-….csv\") for `kind` :json or :csv."
  [{:keys [kind account-address now-ms]}]
  (let [d (js/Date. now-ms)
        stamp (str (.getUTCFullYear d) (pad2 (inc (.getUTCMonth d))) (pad2 (.getUTCDate d)) "T"
                   (pad2 (.getUTCHours d)) (pad2 (.getUTCMinutes d)) "Z")
        acct (let [a (str/replace (or (lower account-address) "account") #"[^a-z0-9]" "")]
               (subs a 0 (min 8 (count a))))]
    (case kind
      :csv (str "hyperopen-vol-range-fills-" acct "-" stamp ".csv")
      (str "hyperopen-vol-range-report-" acct "-" stamp ".json"))))
