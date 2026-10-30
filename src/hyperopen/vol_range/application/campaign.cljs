(ns hyperopen.vol-range.application.campaign
  "Tracking campaigns (HO-SPEC-014 v2.1 sections 7.4, 7.6, 12.1).

  One campaign per network, account, base token and USDC spot pair. Its
  immutable baseline is captured at the first admitted placement in that
  market and committed in the SAME journal transaction as the placement
  intent, so it is durable before anything is sent: the complete spot base
  inventory `q0` (the barrier read's total, held units included), a fresh
  book-midpoint `P0` with its source and time, the reconciled read boundary,
  `C0 = 0` and the model versions. A campaign spans replacements and repairs.
  It ends only when a closure record names its successor (a user restart).

  Pure and total: the builders never throw and never touch the legs, the
  action or the reservations of a placement. A missing price records
  `:p0 nil` with `:p0-source :unavailable`. The baseline is a reporting
  baseline, never an acquisition or tax cost basis."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.contracts :as contracts]))

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

(defn campaign-key
  "\"network|account|spot|base-token-id|spot-pair-index\" for a spot scope,
  or nil when the scope cannot identify a market (no account, no pair index,
  not spot). The account is lower-cased; a missing base token id is an empty
  segment."
  [{:keys [network account-address market-type base-token-id spot-pair-index]}]
  (let [account (lower account-address)]
    (when (and account (= :spot market-type) (some? spot-pair-index))
      (str (name (or network (contracts/current-network))) "|" account "|spot|"
           (or (some-> base-token-id str) "") "|" spot-pair-index))))

;; ---------------------------------------------------------------------------
;; Baseline
;; ---------------------------------------------------------------------------

(defn- dec-string [x]
  (some-> x str precision/parse-decimal precision/decimal->string))

(defn base-total
  "The base token's total (held units included) in a raw barrier read: by
  token id when the rows carry `:token`, else by coin symbol. A read without
  the token is a zero balance. nil only for an unparseable total."
  [{:keys [balances]} {:keys [base-token-id symbol]}]
  (let [rows (if (sequential? balances) balances [])
        by-token? (boolean (some #(some? (:token %)) rows))
        row (if by-token?
              (first (filter #(and (some? base-token-id) (= (str base-token-id) (str (:token %)))) rows))
              (first (filter #(and symbol (= symbol (:coin %))) rows)))]
    (if row (dec-string (:total row)) "0")))

(defn- midpoint
  "(bid + ask) / 2 at 12 places, half-up, trimmed; nil for a missing,
  non-positive or crossed touch."
  [{:keys [bid ask]}]
  (let [b (some-> bid str precision/parse-decimal)
        a (some-> ask str precision/parse-decimal)]
    (when (and b a (precision/pos-dec? b) (precision/pos-dec? a) (not (pos? (precision/cmp b a))))
      (some-> (precision/divide (precision/add b a) (precision/parse-decimal "2") 12 :half-up)
              precision/decimal->string))))

(defn- campaign-scope [scope]
  {:network (or (:network scope) (contracts/current-network))
   :account-address (lower (:account-address scope))
   :coin (:coin scope)
   :spot-pair-index (:spot-pair-index scope)
   :base-token-id (some-> (:base-token-id scope) str)
   :quote-token-id (some-> (:quote-token-id scope) str)
   :symbol (:symbol scope)
   :quote-symbol (:quote-symbol scope)
   :sz-decimals (:sz-decimals scope)})

(defn baseline-record
  "The immutable campaign record. `read` is the admission barrier read
  ({:barrier-id :requested-at-ms :received-at-ms :balances}); `bbo` is the
  admission book ({:bid :ask :observed-at-ms}). `T0` is the read's request
  time."
  [{:keys [campaign-id scope read bbo started-by operation-id grid-id supersedes-campaign-id now-ms]}]
  (let [p0 (midpoint bbo)
        t0 (:requested-at-ms read)]
    {:schema-version jr/schema-version
     :record-id (str "campaign:" campaign-id)
     :kind :campaign
     :campaign-id campaign-id
     :campaign-key (campaign-key scope)
     :campaign-schema-version contracts/campaign-schema-version
     :accounting-model-version contracts/accounting-model-version
     :model-versions {:model contracts/model-version
                      :precision contracts/precision-version
                      :estimator contracts/estimator-version}
     :scope (campaign-scope scope)
     :started-by (or started-by :first-placement)
     :first-operation-id operation-id
     :first-grid-id grid-id
     :baseline {:q0 (base-total read scope)
                :p0 p0
                :p0-source (if p0 :book-midpoint :unavailable)
                :p0-bid (when p0 (:bid bbo))
                :p0-ask (when p0 (:ask bbo))
                :p0-observed-at-ms (when p0 (:observed-at-ms bbo))
                :c0 "0"
                :boundary {:barrier-id (:barrier-id read)
                           :requested-at-ms t0
                           :received-at-ms (:received-at-ms read)
                           :skew-ms (:boundary-skew-ms contracts/report-policy)}}
     :t0-ms t0
     :supersedes-campaign-id supersedes-campaign-id
     :saved-at-ms now-ms}))

(defn closure-record
  "Ends `campaign` where `successor` starts (its `:t0-ms`). Written only
  together with the successor, in one transaction."
  [{:keys [campaign successor reason now-ms]}]
  {:schema-version jr/schema-version
   :record-id (str "campaign-closed:" (:campaign-id campaign))
   :kind :campaign-closed
   :campaign-id (:campaign-id campaign)
   :campaign-key (:campaign-key campaign)
   :ended-at-ms (:t0-ms successor)
   :reason (or reason :user-restart)
   :successor-campaign-id (:campaign-id successor)
   :saved-at-ms now-ms})

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn- by-id [kind records]
  (into {} (comp (filter #(= kind (:kind %))) (map (juxt :campaign-id identity))) records))

(defn campaigns-from-records
  "{:campaigns {campaign-id record} :closures {campaign-id closure}}."
  [records]
  {:campaigns (by-id :campaign records)
   :closures (by-id :campaign-closed records)})

(defn campaigns-for-account
  "`campaigns-from-records` for one account (compared lower-cased); empty
  maps for a nil account."
  [records account]
  (let [account (lower account)
        mine? #(and account (= account (lower (get-in % [:scope :account-address]))))
        {:keys [campaigns closures]} (campaigns-from-records records)
        kept (into {} (filter (comp mine? val)) campaigns)]
    {:campaigns kept
     :closures (into {} (filter (comp (partial contains? kept) key)) closures)}))

(defn open-campaign-in
  "The campaign for `campaign-key` with no closure (the newest by `T0`
  should there ever be two), or nil."
  [campaigns closures campaign-key]
  (when campaign-key
    (->> (vals campaigns)
         (filter #(= campaign-key (:campaign-key %)))
         (remove #(contains? closures (:campaign-id %)))
         (sort-by (juxt :t0-ms :campaign-id))
         last)))

(defn open-campaign
  "The open campaign for `campaign-key` in journal `records`, or nil."
  [records campaign-key]
  (let [{:keys [campaigns closures]} (campaigns-from-records records)]
    (open-campaign-in campaigns closures campaign-key)))

(defn campaign-for-time
  "The campaign for `campaign-key` whose interval [T0, ended-at) contains
  `t` (open-ended without a closure), or nil."
  [campaigns closures campaign-key t]
  (when (and campaign-key (number? t))
    (->> (vals campaigns)
         (filter #(= campaign-key (:campaign-key %)))
         (filter (fn [c]
                   (let [end (get-in closures [(:campaign-id c) :ended-at-ms])]
                     (and (number? (:t0-ms c)) (<= (:t0-ms c) t)
                          (or (not (number? end)) (< t end))))))
         (sort-by (juxt :t0-ms :campaign-id))
         last)))

(defn grid-campaign
  "The campaign a ladder's activity belongs to, or nil (\"Not tracked\").
  Recovered orders are never tracked (spec 7.6). A ladder that is still
  working belongs to the market's open campaign once it was tracked at all,
  because its later fills belong to the open campaign by time; a closed
  ladder belongs to the campaign that contained its creation."
  [campaigns closures grid closed?]
  (when-not (:recovered? grid)
    (let [k (campaign-key (:scope grid))
          at-creation (campaign-for-time campaigns closures k (:created-at-ms grid))]
      (when at-creation
        (or (when-not closed? (open-campaign-in campaigns closures k))
            at-creation)))))

;; ---------------------------------------------------------------------------
;; Placement
;; ---------------------------------------------------------------------------

(defn campaign-needed?
  "True when a placement in `scope` would start a campaign: the scope
  identifies a spot market and the journal has no open campaign for it."
  [records scope]
  (let [k (campaign-key scope)]
    (boolean (and k (nil? (open-campaign records k))))))

(defn baseline-records-for-placement
  "[] or [baseline-record] for an admitted placement, decided on the journal
  `records` read inside the admission section. A replacement or any later
  placement in the same market reuses the open campaign. Without a
  `campaign-id` (the caller found none needed) nothing is added."
  [{:keys [records scope read bbo operation-id grid-id campaign-id now-ms]}]
  (if (and campaign-id (campaign-needed? records scope))
    [(baseline-record {:campaign-id campaign-id :scope scope :read read :bbo bbo
                       :started-by :first-placement :operation-id operation-id :grid-id grid-id
                       :now-ms now-ms})]
    []))
