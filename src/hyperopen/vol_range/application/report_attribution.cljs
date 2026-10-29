(ns hyperopen.vol-range.application.report-attribution
  "Attribution and same-base classification for Vol Range reporting
  (HO-SPEC-014 v2.1 sections 7.6, 12.2, 12.6).

  A fill is TRACKED for a campaign when its CLOID or oid belongs to a
  non-recovered ladder of the campaign's market key and its venue time lies
  in the campaign. Every other fill or ledger change that can alter the
  campaign's base balance is a break candidate:

  - a fill in the campaign's pair that no tracked ladder owns
    (`:break/untracked-same-base-trade`), a fill in another spot pair that
    contains the base (`:break/other-pair-trade`), a fill in a spot market
    the metadata cannot resolve (`:break/unknown-market-fill`, every
    campaign), a tracked fill whose fee is in a third token
    (`:break/unsupported-fee-token`);
  - a ledger transfer of the base (`:break/base-transfer`), or any other
    ledger entry naming it (`:break/unrecognized-ledger-entry`);
  - any of these, or a fill of an order admitted before the baseline, inside
    the skew-widened inception boundary (`:break/ambiguous-boundary`).

  Perp and HIP-3 coins are never relevant. Events before `T0 - skew` are in
  `q0`. A break suppresses dollar figures from `max(t, T0)` onward.
  Classification uses only persisted identity (never maker status). Pure."
  (:require [clojure.string :as str]
            [hyperopen.vol-range.application.campaign :as campaign]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.report-ledger :as ledger]))

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))
(defn- id-text [x] (some-> x str str/trim not-empty))

;; ---------------------------------------------------------------------------
;; Attribution index (derived from journal grids; not a copy of order state)
;; ---------------------------------------------------------------------------

(defn attribution-index
  "{:by-cloid {cloid entry} :by-oid {oid entry}} for every leg of `grids`.
  An entry is {:grid-id :operation-id :logical-id :side :rung-index
  :campaign-key :recovered? :sent-after-ms :predecessor-grid-id}, where
  `:sent-after-ms` is when the leg's operation was admitted (nil when
  unknown, e.g. recovered) and `:predecessor-grid-id` the canceled ladder a
  replacement was built from (nil for a first ladder)."
  [grids]
  (reduce
   (fn [idx grid]
     (reduce
      (fn [idx [cloid leg]]
        (let [entry {:grid-id (:grid-id grid)
                     :operation-id (:operation-id leg)
                     :logical-id (:logical-id leg)
                     :side (:side leg)
                     :rung-index (:rung-index leg)
                     :campaign-key (campaign/campaign-key (:scope grid))
                     :recovered? (boolean (:recovered? grid))
                     :sent-after-ms (get-in grid [:operations (:operation-id leg) :created-at-ms])
                     :predecessor-grid-id (get-in grid [:predecessor :grid-id])}
              cloid (lower (or (:cloid leg) cloid))
              oid (id-text (:oid leg))]
          (cond-> idx
            cloid (assoc-in [:by-cloid cloid] entry)
            oid (assoc-in [:by-oid oid] entry))))
      idx
      (:legs grid)))
   {:by-cloid {} :by-oid {}}
   grids))

(defn attribution-for
  "The index entry for a canonical fill (CLOID first, then oid), or nil."
  [index fill]
  (or (some->> (lower (:cloid fill)) (get (:by-cloid index)))
      (some->> (id-text (:oid fill)) (get (:by-oid index)))))

;; ---------------------------------------------------------------------------
;; Spot markets
;; ---------------------------------------------------------------------------

(defn spot-markets
  "Lookups from `[:spot :meta]` ({:universe [{:name :index :tokens [b q]}]
  :tokens [{:name :index}]}): {:by-name {coin market} :token-names {id name}
  :token-ids {name id} :ready? bool}. Token ids are text."
  [{:keys [universe tokens]}]
  (let [token-names (into {} (keep (fn [t] (when-let [i (id-text (:index t))] [i (:name t)]))) tokens)
        market (fn [u]
                 (let [[b q] (:tokens u)
                       b (id-text b) q (id-text q)]
                   (when (and b q)
                     {:coin (:name u) :pair-index (:index u) :base-token-id b :quote-token-id q
                      :base-symbol (get token-names b) :quote-symbol (get token-names q)})))]
    {:by-name (into {} (keep (fn [u] (when-let [m (market u)] [(:name u) m]))) universe)
     :token-names token-names
     :token-ids (into {} (map (fn [[i n]] [n i])) token-names)
     :ready? (boolean (and (seq universe) (seq tokens)))}))

(defn spot-coin?
  "Spot coins are \"@index\" or \"BASE/QUOTE\"; perps and HIP-3 \"dex:COIN\"
  are not."
  [coin]
  (and (string? coin) (or (str/starts-with? coin "@") (str/includes? coin "/"))))

(defn- resolve-market [markets coin]
  (or (get-in markets [:by-name coin])
      ;; A canonical \"BASE/QUOTE\" name missing from the universe resolves by
      ;; token names when both are known.
      (when (and (string? coin) (str/includes? coin "/"))
        (let [[bn qn] (str/split coin #"/" 2)
              b (get-in markets [:token-ids bn])
              q (get-in markets [:token-ids qn])]
          (when (and b q)
            {:coin coin :pair-index nil :base-token-id b :quote-token-id q :base-symbol bn :quote-symbol qn})))))

(defn classify-fill
  "{:status :ignored} for a non-spot coin, {:status :unknown-market} for a
  spot coin the metadata cannot resolve, else {:status :spot :market {...}
  :token-ids #{b q} :attribution entry-or-nil}."
  [markets index fill]
  (let [coin (:coin fill)]
    (cond
      (not (spot-coin? coin)) {:status :ignored}
      :else (if-let [m (resolve-market markets coin)]
              {:status :spot :market m
               :token-ids #{(:base-token-id m) (:quote-token-id m)}
               :attribution (attribution-for index fill)}
              {:status :unknown-market}))))

(defn relevant-fill?
  "True when a fill can alter any of `token-ids` (a set of tracked base
  token ids) or cannot be resolved at all. Only relevant rows are stored."
  [classification token-ids]
  (case (:status classification)
    :unknown-market (boolean (seq token-ids))
    :spot (boolean (some (set token-ids) (:token-ids classification)))
    false))

(defn unparsed-fill-token-ids
  "The tracked base tokens (`token-ids`) a raw fill row that failed
  normalization could have changed: none for a perp or HIP-3 coin, the
  pair's tokens for a known spot market, and every one for a missing coin or
  a spot market the metadata cannot resolve."
  [markets token-ids row]
  (let [tokens (set token-ids)
        coin (:coin row)]
    (if-not (and (string? coin) (seq (str/trim coin)))
      tokens
      (let [c (classify-fill markets nil {:coin coin})]
        (case (:status c)
          :unknown-market tokens
          :spot (into #{} (filter (:token-ids c)) tokens)
          #{})))))

;; ---------------------------------------------------------------------------
;; Non-funding ledger updates
;; ---------------------------------------------------------------------------

(def ignored-ledger-types
  "USDC-only delta types: never a base-token change."
  #{"deposit" "withdraw" "internalTransfer" "accountClassTransfer" "subAccountTransfer"
    "vaultCreate" "vaultDeposit" "vaultWithdraw" "vaultDistribution" "liquidation" "rewardsClaim"})

(def base-transfer-ledger-types
  "Delta types that move the named `:token`."
  #{"spotTransfer" "send" "spotGenesis" "cStakingTransfer" "borrowLend" "deployGasAuction"})

(defn- strings-in [x]
  (cond (string? x) [x]
        (map? x) (mapcat strings-in (vals x))
        (sequential? x) (mapcat strings-in x)
        :else []))

(defn- ledger-key [account time hash type symbol]
  (str "ledger:" account "|" time "|" hash "|" type "|" symbol))

(def native-token-name
  "The venue's native token, in which `nativeTokenFee` is paid."
  "HYPE")

(defn- nonzero-amount? [x]
  (boolean (and (some? x) (re-find #"[1-9]" (str x)))))

(defn- fee-token-labels
  "Tokens a ledger delta's fees are paid in, which the delta never names as
  its `:token`: `feeToken` for a non-zero `fee`, and the native token for a
  non-zero `nativeTokenFee`."
  [delta]
  (cond-> []
    (and (nonzero-amount? (:fee delta)) (some? (:feeToken delta))) (conj (:feeToken delta))
    (nonzero-amount? (:nativeTokenFee delta)) (conj native-token-name)))

(defn unparsed-ledger-row?
  "True when a ledger row cannot be classified (no numeric time, no delta
  map or no type) and is not a USDC-only type: it may hide a base change, so
  the read that returned it proves nothing from its time on."
  [row]
  (let [delta (:delta row)
        type (when (map? delta) (some-> (:type delta) str))]
    (and (not (and (number? (:time row)) (map? delta) type))
         (not (contains? ignored-ledger-types type)))))

(defn ledger-update-records
  "Ledger update records for one venue `userNonFundingLedgerUpdates` row
  ({:time :hash :delta {:type ...}}) of `account-address`, one per tracked
  base token it touches; [] when it touches none. `tracked` is the set of
  tracked base token ids."
  [{:keys [markets tracked account-address row now-ms]}]
  (let [account (lower account-address)
        delta (:delta row)
        type (some-> (:type delta) str)
        time (:time row)
        tracked (set tracked)
        id-of (fn [label] (get-in markets [:token-ids (ledger/token-name label)]))
        record (fn [token-id classification]
                 (let [symbol (get-in markets [:token-names token-id])]
                   {:schema-version contracts/ledger-schema-version
                    :record-id (ledger-key account time (:hash row) type symbol)
                    :kind :ledger-update
                    :account-address account
                    :time time
                    :hash (:hash row)
                    :delta-type type
                    :token symbol
                    :base-token-id token-id
                    :amount (some-> (:amount delta) str)
                    :classification classification
                    :raw-delta delta
                    :saved-at-ms now-ms}))]
    (cond
      (not (and account (number? time) (map? delta) type)) []
      (contains? ignored-ledger-types type) []
      :else
      (let [fee-ids (keep id-of (fee-token-labels delta))
            moved (if (contains? base-transfer-ledger-types type)
                    ;; The moved token and the fee tokens; a transfer that does
                    ;; not name its token is classified by the tokens it names.
                    (concat (map vector (keep id-of [(:token delta)]) (repeat :base-transfer))
                            (when-not (some? (:token delta))
                              (map vector (keep id-of (strings-in delta)) (repeat :unrecognized-ledger-entry))))
                    (map vector (keep id-of (strings-in delta)) (repeat :unrecognized-ledger-entry)))]
        (->> (concat moved (map vector fee-ids (repeat :base-transfer)))
             (filter (comp tracked first))
             (reduce (fn [[seen out] [id c]]
                       (if (contains? seen id) [seen out] [(conj seen id) (conj out (record id c))]))
                     [#{} []])
             second)))))

;; ---------------------------------------------------------------------------
;; Unreadable stored rows
;; ---------------------------------------------------------------------------

(defn- int-or-nil [x]
  (when (and (string? x) (re-matches #"-?\d+" x)) (js/parseInt x 10)))

(defn unreadable-row
  "What the record id of a stored ledger row that no longer decodes still
  says (ids are built by `ledger-store/fill-record`, `ledger-update-records`,
  `report-coverage/coverage-record-id` and the balance checks):
  {:record-id :kind (:fill, :ledger-update, :coverage, :balance-check or
  :unknown) :time (venue ms or nil) :token-ids (a set, or :any when it
  cannot be told) :campaign-id}."
  [markets record-id]
  (let [id (str record-id)
        [prefix body] (let [i (.indexOf id ":")] (if (neg? i) [nil id] [(subs id 0 i) (subs id (inc i))]))
        parts (str/split body #"\|" -1)
        base {:record-id record-id :kind :unknown :time nil :token-ids :any :campaign-id nil}]
    (case prefix
      "fill" (let [[_ _ coin time] parts
                   c (when (seq coin) (classify-fill markets nil {:coin coin}))]
               (assoc base :kind :fill :time (int-or-nil time)
                      :token-ids (case (:status c) :ignored #{} :spot (:token-ids c) :any)))
      "ledger" (let [[_ time _ _ symbol] parts
                     token (get-in markets [:token-ids (ledger/token-name symbol)])]
                 (assoc base :kind :ledger-update :time (int-or-nil time) :token-ids (if token #{token} :any)))
      "coverage" (let [[_ _ token] parts]
                   (assoc base :kind :coverage :token-ids (if (seq token) #{token} :any)))
      "balance-check" (assoc base :kind :balance-check :campaign-id (first parts))
      base)))

(defn unreadable-touches?
  "True when parsed unreadable row `u` could concern base token `token-id`."
  [u token-id]
  (let [ids (:token-ids u)]
    (or (= :any ids) (contains? ids token-id))))

;; ---------------------------------------------------------------------------
;; Per-campaign findings
;; ---------------------------------------------------------------------------

(def ^:private update-reasons
  {:base-transfer :break/base-transfer
   :unrecognized-ledger-entry :break/unrecognized-ledger-entry})

(defn campaign-findings
  "{:events [...] :breaks [...]} for `campaign` from stored canonical
  `fills` and ledger `updates`. `:events` are the campaign's tracked fill
  events (`report-ledger/fill-event`), sorted. Each break is {:at-ms
  (max t T0) :time t :reason :source (:fill or :ledger) :event-id}, sorted
  by `:at-ms`. `ended-at-ms` is the closure time or nil. `:unreadable` are
  the stored rows (record ids) that no longer decode and could concern the
  campaign, as [{:time :record-id}] (`:time` is T0 - skew when the row's own
  time cannot be told): what they held is unknown, so coverage cannot be
  complete from there on. A tracked fill inside the inception boundary is a
  break and stays in `:events` (activity), except before T0 of a campaign
  that superseded another one, whose interval already holds it."
  [{:keys [campaign ended-at-ms fills updates markets index unreadable]}]
  (let [scope (:scope campaign)
        base-id (some-> (:base-token-id scope) str)
        k (:campaign-key campaign)
        t0 (:t0-ms campaign)
        skew (or (get-in campaign [:baseline :boundary :skew-ms]) (:boundary-skew-ms contracts/report-policy))
        received (or (get-in campaign [:baseline :boundary :received-at-ms]) t0)
        start (- t0 skew)
        ambiguous-to (+ received skew)
        in-campaign? (fn [t] (and (number? t) (>= t start) (or (not (number? ended-at-ms)) (< t ended-at-ms))))
        ambiguous? (fn [t] (<= start t ambiguous-to))
        brk (fn [t reason source event-id]
              {:at-ms (max t t0) :time t :reason reason :source source :event-id event-id})
        names (fn [m] {:base-symbol (or (get-in markets [:token-names base-id]) (:symbol scope))
                       :quote-symbol (or (:quote-symbol m) (:quote-symbol scope) "USDC")})
        fill-finding
        (fn [fill]
          (let [t (:time fill)
                c (classify-fill markets index fill)]
            (when (in-campaign? t)
              (case (:status c)
                :unknown-market {:break (brk t :break/unknown-market-fill :fill (:fill-key fill))}
                :spot
                (when (contains? (:token-ids c) base-id)
                  (let [att (:attribution c)
                        m (:market c)
                        same-pair? (and (= base-id (:base-token-id m))
                                        (= (:spot-pair-index scope) (:pair-index m)))
                        tracked? (and att (not (:recovered? att)) (= k (:campaign-key att)) same-pair?)
                        early? (or (< t t0)
                                   (and (ambiguous? t)
                                        (not (and (number? (:sent-after-ms att)) (>= (:sent-after-ms att) t0)))))
                        e (when tracked?
                            (ledger/fill-event fill (merge (names m)
                                                           {:tracked? true :campaign-id (:campaign-id campaign)
                                                            :grid-id (:grid-id att)
                                                            :operation-id (:operation-id att)
                                                            :logical-id (:logical-id att)})))]
                    (cond
                      ;; The fill stays in the activity unless it is before T0
                      ;; of a campaign that superseded another one, whose
                      ;; interval (it ends at this T0) already shows it.
                      (and tracked? early?)
                      (cond-> {:break (brk t :break/ambiguous-boundary :fill (:fill-key fill))}
                        (and e (or (>= t t0) (nil? (:supersedes-campaign-id campaign)))) (assoc :event e))

                      tracked?
                      (cond-> {:event e}
                        (or (nil? e) (= :unsupported (:fee-kind e)))
                        (assoc :break (brk t (if e :break/unsupported-fee-token :break/untracked-same-base-trade)
                                           :fill (:fill-key fill))))

                      (ambiguous? t) {:break (brk t :break/ambiguous-boundary :fill (:fill-key fill))}
                      same-pair? {:break (brk t :break/untracked-same-base-trade :fill (:fill-key fill))}
                      :else {:break (brk t :break/other-pair-trade :fill (:fill-key fill))})))
                nil))))
        update-finding
        (fn [u]
          (let [t (:time u)]
            (when (and (= base-id (some-> (:base-token-id u) str)) (in-campaign? t))
              {:break (brk t (if (ambiguous? t)
                               :break/ambiguous-boundary
                               (get update-reasons (:classification u) :break/unrecognized-ledger-entry))
                           :ledger (:record-id u))})))
        unreadable-finding
        (fn [u]
          (let [t (:time u)
                found {:time (if (number? t) (max t start) start) :record-id (:record-id u)}]
            (case (:kind u)
              (:fill :ledger-update) (when (and (unreadable-touches? u base-id) (or (nil? t) (in-campaign? t))) found)
              :coverage (when (unreadable-touches? u base-id) found)
              :balance-check (when (= (:campaign-id u) (:campaign-id campaign)) found)
              found)))
        found (concat (keep fill-finding fills) (keep update-finding updates))]
    {:events (ledger/events (keep :event found))
     :breaks (vec (sort-by (juxt :at-ms :time #(str (:event-id %))) (keep :break found)))
     :unreadable (vec (sort-by (juxt :time #(str (:record-id %)))
                               (keep (comp unreadable-finding #(unreadable-row markets %)) unreadable)))}))
