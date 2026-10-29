(ns hyperopen.schema.vol-range-report-contracts
  "Exact-key contracts for Vol Range reporting (HO-SPEC-014 v2.1 sections
  7.4, 12): the journal's campaign and closure records, the ledger store's
  records, and the Portfolio report view model
  (`hyperopen.vol-range.application.report-model/report-view-model`), which
  later stages extend.

  VIEW MODEL `view-model-keys`: {:enabled? :read-only? :loading?
  :account-address :markets :coverage :headline :campaigns :status-message
  :empty-message}, with `markets-keys` {:selected :options} (+
  `market-option-keys`), `summary-coverage-keys` (or nil when no campaign
  is shown), `headline-keys` {:status :message} and per campaign
  `campaign-keys` (+ `coverage-keys`, `interval-keys`, `activity-keys`,
  `restart-keys`). Display values are strings, booleans, keywords or
  millisecond times. The view model carries no dollar P&L figure."
  (:require [cljs.spec.alpha :as s]))

(defn- exact-keys? [m ks] (and (map? m) (= (set (keys m)) ks)))
(defn- str-or-nil? [x] (or (nil? x) (string? x)))

;; ---------------------------------------------------------------------------
;; Records
;; ---------------------------------------------------------------------------

(def campaign-record-keys
  #{:schema-version :record-id :kind :campaign-id :campaign-key :campaign-schema-version
    :accounting-model-version :model-versions :scope :started-by :first-operation-id :first-grid-id
    :baseline :t0-ms :supersedes-campaign-id :saved-at-ms})
(def campaign-scope-keys
  #{:network :account-address :coin :spot-pair-index :base-token-id :quote-token-id :symbol :quote-symbol
    :sz-decimals})
(def baseline-keys #{:q0 :p0 :p0-source :p0-bid :p0-ask :p0-observed-at-ms :c0 :boundary})
(def boundary-keys #{:barrier-id :requested-at-ms :received-at-ms :skew-ms})
(def closure-record-keys
  #{:schema-version :record-id :kind :campaign-id :campaign-key :ended-at-ms :reason :successor-campaign-id
    :saved-at-ms})
(def ledger-fill-record-keys #{:schema-version :record-id :kind :account-address :fill :saved-at-ms})
(def ledger-update-record-keys
  #{:schema-version :record-id :kind :account-address :time :hash :delta-type :token :base-token-id :amount
    :classification :raw-delta :saved-at-ms})
(def coverage-record-keys
  #{:schema-version :record-id :kind :account-address :stream :base-token-id :complete :failures :saved-at-ms})

(defn campaign-record-valid? [r]
  (and (exact-keys? r campaign-record-keys)
       (= :campaign (:kind r))
       (contains? #{:first-placement :user-restart} (:started-by r))
       (exact-keys? (:scope r) campaign-scope-keys)
       (exact-keys? (:baseline r) baseline-keys)
       (exact-keys? (get-in r [:baseline :boundary]) boundary-keys)
       (contains? #{:book-midpoint :unavailable} (get-in r [:baseline :p0-source]))
       (number? (:t0-ms r))))

(defn closure-record-valid? [r]
  (and (exact-keys? r closure-record-keys)
       (= :campaign-closed (:kind r))
       (number? (:ended-at-ms r))
       (string? (:successor-campaign-id r))))

(defn ledger-record-valid?
  "Exact keys for each ledger record kind (balance checks are checked by
  the ledger store)."
  [r]
  (case (:kind r)
    :ledger-fill (exact-keys? r ledger-fill-record-keys)
    :ledger-update (exact-keys? r ledger-update-record-keys)
    :coverage (exact-keys? r coverage-record-keys)
    :balance-check (map? r)
    false))

;; ---------------------------------------------------------------------------
;; View model
;; ---------------------------------------------------------------------------

(def view-model-keys
  #{:enabled? :read-only? :loading? :account-address :markets :coverage :headline :campaigns
    :status-message :empty-message})
(def markets-keys #{:selected :options})
(def market-option-keys #{:id :label :selected?})
(def summary-coverage-keys #{:state :label :icon})
(def headline-keys #{:status :message})
(def campaign-keys
  #{:campaign-id :campaign-key :market :open? :baseline-text :cost-basis-text :ended-text :coverage :activity
    :restart})
(def coverage-keys #{:state :label :icon :detail :intervals})
(def interval-keys #{:from-ms :to-ms :state :text})
(def activity-keys #{:market :fills :bought :buy-vwap :sold :sell-vwap :maker-taker :fees})
(def restart-keys #{:visible? :confirming? :busy?})

(def coverage-states #{:complete :partial :gap :attribution-break :unavailable})

(defn- coverage-valid? [c]
  (and (exact-keys? c coverage-keys)
       (contains? coverage-states (:state c))
       (every? string? [(:label c) (:icon c) (:detail c)])
       (every? #(and (exact-keys? % interval-keys) (string? (:text %)) (contains? coverage-states (:state %)))
               (:intervals c))))

(defn- campaign-valid? [c]
  (and (exact-keys? c campaign-keys)
       (every? string? [(:campaign-id c) (:campaign-key c) (:market c) (:baseline-text c) (:cost-basis-text c)])
       (str-or-nil? (:ended-text c))
       (boolean? (:open? c))
       (coverage-valid? (:coverage c))
       (exact-keys? (:activity c) activity-keys)
       (every? string? (vals (:activity c)))
       (exact-keys? (:restart c) restart-keys)
       (every? boolean? (vals (:restart c)))))

(defn view-model-valid? [vm]
  (boolean
   (and (exact-keys? vm view-model-keys)
        (every? boolean? [(:enabled? vm) (:read-only? vm) (:loading? vm)])
        (str-or-nil? (:account-address vm))
        (exact-keys? (:markets vm) markets-keys)
        (every? #(exact-keys? % market-option-keys) (get-in vm [:markets :options]))
        (or (nil? (:coverage vm)) (exact-keys? (:coverage vm) summary-coverage-keys))
        (exact-keys? (:headline vm) headline-keys)
        (contains? #{:pending :unavailable} (get-in vm [:headline :status]))
        (str-or-nil? (get-in vm [:headline :message]))
        (vector? (:campaigns vm))
        (every? campaign-valid? (:campaigns vm))
        (str-or-nil? (:status-message vm))
        (str-or-nil? (:empty-message vm)))))

(s/def ::view-model view-model-valid?)
(s/def ::campaign-record campaign-record-valid?)
(s/def ::closure-record closure-record-valid?)
(s/def ::ledger-record ledger-record-valid?)
