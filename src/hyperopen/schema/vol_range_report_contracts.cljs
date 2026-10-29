(ns hyperopen.schema.vol-range-report-contracts
  "Exact-key contracts for Vol Range reporting (HO-SPEC-014 v2.1 sections
  7.4, 12): the journal's campaign and closure records, the ledger store's
  records, and the Portfolio report view model
  (`hyperopen.vol-range.application.report-model/report-view-model`), which
  later stages extend.

  VIEW MODEL `view-model-keys`: {:enabled? :read-only? :loading?
  :account-address :window :markets :coverage :headline :campaigns
  :status-message :empty-message}, with `window-keys` {:selected :options}
  (+ `window-option-keys`), `markets-keys` {:selected :options} (+
  `market-option-keys`), `summary-coverage-keys` (or nil when no campaign
  is shown), `headline-keys` {:status :title :figures :benchmark-label
  :range-text :message :no-total :subset-offer} (figures, when shown, are
  {:net-pnl :hold-pnl :contribution} each `figure-keys` {:label :display
  :tone}; the offer is `subset-offer-keys`) and per campaign
  `campaign-keys` (+ `coverage-keys`, `interval-keys`, `activity-keys`,
  `restart-keys`); `:chart` (`chart-keys`, or nil) and `:matched`
  (`matched-keys`, or nil). Display values are strings, booleans, keywords or
  millisecond times; dollar figures appear only as display text in the
  headline."
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
  #{:enabled? :read-only? :loading? :account-address :window :markets :coverage :headline :chart :matched
    :campaigns :status-message :empty-message})
(def window-keys #{:selected :options})
(def window-option-keys #{:id :label :selected?})
(def window-ids #{:since-start :7d :30d})
(def markets-keys #{:selected :options})
(def market-option-keys #{:id :label :selected?})
(def summary-coverage-keys #{:state :label :icon})
(def headline-keys
  #{:status :title :figures :benchmark-label :range-text :message :no-total :subset-offer})
(def headline-statuses #{:ok :pending :suppressed :unavailable :no-total})
(def headline-figure-ids #{:net-pnl :hold-pnl :contribution})
(def figure-keys #{:label :display :tone})
(def subset-offer-keys #{:label :active?})
(def campaign-keys
  #{:campaign-id :campaign-key :market :open? :baseline-text :cost-basis-text :ended-text :coverage :activity
    :restart})
(def coverage-keys #{:state :label :icon :detail :intervals})
(def interval-keys #{:from-ms :to-ms :state :text})
(def activity-keys #{:market :fills :bought :buy-vwap :sold :sell-vwap :maker-taker :fees})
(def restart-keys #{:visible? :confirming? :busy?})

(def coverage-states #{:complete :partial :gap :attribution-break :unavailable})

(def chart-keys #{:title :caption :cadence :legend :y-ticks :x-labels :points :series :tooltips :end-text})
(def chart-legend-keys #{:id :label})
(def chart-tick-keys #{:value :y-ratio})
(def chart-point-keys #{:x-ratio :y-ratio})
(def chart-series-keys #{:id :kind :points})
(def chart-tooltip-keys #{:time-text :net :hold})
(def usdc-text-keys #{:display :tone})
(def matched-keys #{:open? :title :note :markets})
(def matched-market-keys #{:campaign-id :market :rows :fees-paid})
(def matched-row-keys #{:id :label :value})

(defn- coverage-valid? [c]
  (and (exact-keys? c coverage-keys)
       (contains? coverage-states (:state c))
       (every? string? [(:label c) (:icon c) (:detail c)])
       (every? #(and (exact-keys? % interval-keys) (string? (:text %)) (contains? coverage-states (:state %)))
               (:intervals c))))

(defn- window-valid? [w]
  (and (exact-keys? w window-keys)
       (contains? window-ids (:selected w))
       (= window-ids (set (map :id (:options w))))
       (every? #(and (exact-keys? % window-option-keys) (string? (:label %)) (boolean? (:selected? %)))
               (:options w))))

(defn- figures-valid? [f]
  (or (nil? f)
      (and (exact-keys? f headline-figure-ids)
           (every? #(and (exact-keys? % figure-keys) (string? (:label %)) (string? (:display %))
                         (contains? #{:positive :negative :neutral} (:tone %)))
                   (vals f)))))

(defn- headline-valid? [h]
  (and (exact-keys? h headline-keys)
       (contains? headline-statuses (:status h))
       (every? str-or-nil? [(:title h) (:benchmark-label h) (:range-text h) (:message h) (:no-total h)])
       (figures-valid? (:figures h))
       (= (= :ok (:status h)) (some? (:figures h)))
       (or (nil? (:subset-offer h))
           (and (exact-keys? (:subset-offer h) subset-offer-keys)
                (string? (get-in h [:subset-offer :label]))
                (boolean? (get-in h [:subset-offer :active?]))))))

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

(defn- ratio? [x] (and (number? x) (js/isFinite x) (<= 0 x 1)))
(defn- usdc-text-valid? [t]
  (and (exact-keys? t usdc-text-keys) (string? (:display t)) (contains? #{:positive :negative :neutral} (:tone t))))

(defn- chart-valid?
  "Nil, or the P&L chart: segment series of :net and :hold points with
  ratios in [0, 1], one tooltip per hover point."
  [c]
  (or (nil? c)
      (and (exact-keys? c chart-keys)
           (every? string? [(:title c) (:caption c)])
           (str-or-nil? (:end-text c))
           (contains? #{:hourly :daily} (:cadence c))
           (every? #(and (exact-keys? % chart-legend-keys) (string? (:label %))) (:legend c))
           (every? #(and (exact-keys? % chart-tick-keys) (string? (:value %)) (ratio? (:y-ratio %))) (:y-ticks c))
           (every? string? (vals (:x-labels c)))
           (every? #(and (exact-keys? % chart-point-keys) (ratio? (:x-ratio %)) (ratio? (:y-ratio %))) (:points c))
           (every? #(and (exact-keys? % chart-series-keys) (contains? #{:net :hold} (:kind %))
                         (seq (:points %))
                         (every? (fn [p] (and (ratio? (:x-ratio p)) (ratio? (:y-ratio p)))) (:points %)))
                   (:series c))
           (= (count (:points c)) (count (:tooltips c)))
           (every? #(and (exact-keys? % chart-tooltip-keys) (string? (:time-text %))
                         (usdc-text-valid? (:net %)) (usdc-text-valid? (:hold %)))
                   (:tooltips c)))))

(defn- matched-valid?
  "Nil, or the Gross spread on matched fills disclosure."
  [m]
  (or (nil? m)
      (and (exact-keys? m matched-keys)
           (boolean? (:open? m))
           (every? string? [(:title m) (:note m)])
           (seq (:markets m))
           (every? (fn [mk]
                     (and (exact-keys? mk matched-market-keys)
                          (every? string? [(:campaign-id mk) (:market mk) (:fees-paid mk)])
                          (every? #(and (exact-keys? % matched-row-keys) (keyword? (:id %)) (string? (:label %))
                                        (or (nil? (:value %)) (usdc-text-valid? (:value %))))
                                  (:rows mk))))
                   (:markets m)))))

(defn view-model-valid? [vm]
  (boolean
   (and (exact-keys? vm view-model-keys)
        (every? boolean? [(:enabled? vm) (:read-only? vm) (:loading? vm)])
        (str-or-nil? (:account-address vm))
        (window-valid? (:window vm))
        (exact-keys? (:markets vm) markets-keys)
        (every? #(exact-keys? % market-option-keys) (get-in vm [:markets :options]))
        (or (nil? (:coverage vm)) (exact-keys? (:coverage vm) summary-coverage-keys))
        (headline-valid? (:headline vm))
        (chart-valid? (:chart vm))
        (matched-valid? (:matched vm))
        (vector? (:campaigns vm))
        (every? campaign-valid? (:campaigns vm))
        (str-or-nil? (:status-message vm))
        (str-or-nil? (:empty-message vm)))))

(s/def ::view-model view-model-valid?)
(s/def ::campaign-record campaign-record-valid?)
(s/def ::closure-record closure-record-valid?)
(s/def ::ledger-record ledger-record-valid?)
