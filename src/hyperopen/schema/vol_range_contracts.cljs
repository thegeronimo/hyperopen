(ns hyperopen.schema.vol-range-contracts
  "Exact-key contracts for the Vol Range view models produced by
  `hyperopen.vol-range.application.projections` (HO-SPEC-014 v2.1 sections 4
  and 11.1). The ticket, review and chart views consume ONLY these shapes.

  TICKET MODEL (`ticket-model`), top-level keys `ticket-keys`:
    :visible? :eligible? :eligibility-reason :read-only? :status :identity
    :model :inventory :buy :sell :outcomes :errors :warnings :footer :ui
  When :visible? is false every key except :visible? and :ui is empty (nil, false
  or []). Nested key sets: `identity-keys`, `model-keys` ({:volatility
  :anchor :tilt} with `volatility-keys`, `anchor-keys`, `anchor-option-keys`,
  `tilt-keys`), `inventory-keys` (+ `track-keys`, `marker-keys`), `buy-keys`
  (+ `buy-totals-keys`, `row-keys`), `sell-keys` (+ `sell-totals-keys`,
  `percent-option-keys`), `outcomes-keys` (+ `vwap-gap-keys`), `error-keys`
  (+ `offer-keys`), `warning-keys`, `footer-keys`, `ui-keys`.

  REVIEW MODEL (`review-model`): `review-keys` {:open? :available? :title
  :identity-line :plan-as-of :base-symbol :tiles :legs :cash :inventory :details :note :replacement
  :footer} with `tile-keys`, `review-leg-keys`, `detail-row-keys` and `review-footer-keys`
  {:placement-available? :text :hint :back-label}. There is no commit field.
  `:replacement` is nil, or for a replacement review `review-replacement-keys`
  {:lineage-text :remaining-text :filled-text :rejected-text :edited-text}
  (strings; filled and edited texts may be nil), filled at the view boundary.

  CHART MODEL (`chart-model`): nil, or `chart-keys` {:stage :stage-label :anchor
  :shifted-anchor :bands :legs} with `price-line-keys` {:price :label},
  `band-keys` {:sigma :lower :upper} and `chart-leg-keys` {:leg-id :side :price
  :size :weight :label}.

  Display values are strings (already formatted), booleans, keywords or small
  layout numbers; `valid?` fns return booleans."
  (:require [cljs.spec.alpha :as s]))

(defn- exact-keys? [m ks] (and (map? m) (= (set (keys m)) ks)))

(defn- str-or-nil? [x] (or (nil? x) (string? x)))
(defn- all-str-or-nil? [m ks] (every? #(str-or-nil? (get m %)) ks))

;; ---------------------------------------------------------------------------
;; Key sets
;; ---------------------------------------------------------------------------

(def ticket-keys #{:visible? :eligible? :eligibility-reason :read-only? :status :identity :model
                   :inventory :buy :sell :outcomes :errors :warnings :footer :ui})
(def identity-keys #{:market-label :market-type-label :account-label :execution-label})
(def model-keys #{:volatility :anchor :tilt})
(def volatility-keys #{:mode :display :source-label :manual-input :caption :window-label :unavailable-message})
(def anchor-keys #{:mode :options :price :caption :custom-input :excursion-text})
(def anchor-option-keys #{:id :label :available? :price :selected?})
(def tilt-keys #{:enabled? :strength-input :caption})
(def inventory-keys #{:base-symbol :current :available-to-sell :limit-unit :limit-note
                      :summary :open? :collapsible? :tilt?
                      :core-input :target-input :maximum-input
                      :core-placeholder :target-placeholder :maximum-placeholder :track :set-maximum-offer
                      :starting-breach})
(def track-keys #{:domain-low :domain-high :markers})
(def marker-keys #{:id :label :value :position})
(def offer-keys #{:id :label})
(def row-keys #{:leg-id :price :size :notional :sigma})
(def buy-keys #{:enabled? :amount-unit :quote-amount-input :amount-note
                :quantity-input :count-input :start-sigma-input :end-sigma-input :skew-input
                :max-debit-input :rows :totals :use-plan-total-offer})
(def buy-totals-keys #{:quantity :gross :fee-reserve :fee-reserve-token :max-debit :cap :cap-headroom :vwap})
(def sell-keys #{:enabled? :amount-unit :quote-amount-input :amount-note :quantity-input :count-input :start-sigma-input :end-sigma-input :skew-input
                 :percent-options :sellable-excess :nothing-to-sell-hint :rows :totals :note})
(def sell-totals-keys #{:quantity :base-committed :fee-reserve :fee-reserve-token :gross :est-net-proceeds :vwap})
(def percent-option-keys #{:pct :label :quantity :enabled?})
(def outcomes-keys #{:both-sides? :base-symbol :all-buys-first :all-sells-first :everything-fills :vwap-gap})
(def vwap-gap-keys #{:per-unit :percent :explanation :collapsed?})
(def error-keys #{:code :message :path :remediations})
(def warning-keys #{:code :message})
(def footer-keys #{:note :review-label :review-enabled?})
(def ui-keys #{:show-bands? :advanced-open? :limits-open? :review-open?})

(def review-keys #{:open? :available? :title :identity-line :plan-as-of :base-symbol :tiles :legs :cash :inventory
                   :details :note :replacement :footer})
(def review-replacement-keys #{:lineage-text :remaining-text :filled-text :rejected-text :edited-text})
(def tile-keys #{:id :label :value :caption})
(def review-leg-keys #{:leg-id :side :side-label :price :size :notional :distance})
(def detail-row-keys #{:label :value})
(def review-footer-keys #{:placement-available? :text :hint :back-label})

(def chart-keys #{:stage :stage-label :anchor :shifted-anchor :bands :legs})
(def price-line-keys #{:price :label})
(def band-keys #{:sigma :lower :upper})
(def chart-leg-keys #{:leg-id :side :price :size :weight :label})

;; ---------------------------------------------------------------------------
;; Ticket specs
;; ---------------------------------------------------------------------------

(defn- each [pred xs] (and (sequential? xs) (every? pred xs)))
(defn- nil-or [pred x] (or (nil? x) (pred x)))

(s/def ::identity
  #(and (exact-keys? % identity-keys) (every? string? (vals %))))

(s/def ::volatility
  #(and (exact-keys? % volatility-keys)
        (contains? #{:auto :manual} (:mode %))
        (all-str-or-nil? % [:display :source-label :manual-input :caption :window-label :unavailable-message])))

(defn- anchor-option? [o]
  (and (exact-keys? o anchor-option-keys) (keyword? (:id o)) (string? (:label o))
       (boolean? (:available? o)) (boolean? (:selected? o)) (str-or-nil? (:price o))))

(s/def ::anchor
  #(and (exact-keys? % anchor-keys)
        (keyword? (:mode %))
        (each anchor-option? (:options %))
        (all-str-or-nil? % [:price :caption :custom-input :excursion-text])))

(s/def ::tilt
  #(and (exact-keys? % tilt-keys) (boolean? (:enabled? %)) (str-or-nil? (:strength-input %))
        (string? (:caption %))))

(s/def ::model
  #(and (exact-keys? % model-keys)
        (s/valid? ::volatility (:volatility %))
        (s/valid? ::anchor (:anchor %))
        (s/valid? ::tilt (:tilt %))))

(defn- offer? [o] (and (exact-keys? o offer-keys) (keyword? (:id o)) (string? (:label o))))
(defn- marker? [m] (and (exact-keys? m marker-keys) (keyword? (:id m)) (string? (:label m))
                        (str-or-nil? (:value m)) (number? (:position m))))
(defn- track? [t] (and (exact-keys? t track-keys) (str-or-nil? (:domain-low t))
                       (str-or-nil? (:domain-high t)) (each marker? (:markers t))))

(s/def ::inventory
  #(and (exact-keys? % inventory-keys)
        (contains? #{:base :quote} (:limit-unit %))
        (boolean? (:open? %)) (boolean? (:collapsible? %)) (boolean? (:tilt? %))
        (all-str-or-nil? % [:base-symbol :current :available-to-sell :limit-note :summary :core-input :target-input :maximum-input
                            :core-placeholder :target-placeholder :maximum-placeholder :starting-breach])
        (nil-or track? (:track %))
        (nil-or offer? (:set-maximum-offer %))))

(defn- row? [r] (and (exact-keys? r row-keys) (string? (:leg-id r))
                     (all-str-or-nil? r [:price :size :notional :sigma])))

(defn- side-inputs? [c]
  (and (boolean? (:enabled? c))
       (contains? #{:base :quote} (:amount-unit c))
       (all-str-or-nil? c [:quote-amount-input :amount-note :quantity-input :count-input :start-sigma-input :end-sigma-input :skew-input])
       (each row? (:rows c))))

(s/def ::buy
  #(and (exact-keys? % buy-keys) (side-inputs? %) (str-or-nil? (:max-debit-input %))
        (exact-keys? (:totals %) buy-totals-keys) (all-str-or-nil? (:totals %) buy-totals-keys)
        (nil-or offer? (:use-plan-total-offer %))))

(defn- percent-option? [o]
  (and (exact-keys? o percent-option-keys) (contains? #{25 50 100} (:pct o)) (string? (:label o))
       (str-or-nil? (:quantity o)) (boolean? (:enabled? o))))

(s/def ::sell
  #(and (exact-keys? % sell-keys) (side-inputs? %)
        (each percent-option? (:percent-options %))
        (str-or-nil? (:sellable-excess %)) (str-or-nil? (:nothing-to-sell-hint %))
        (exact-keys? (:totals %) sell-totals-keys) (all-str-or-nil? (:totals %) sell-totals-keys)
        (string? (:note %))))

(defn- gap? [g] (and (exact-keys? g vwap-gap-keys) (str-or-nil? (:per-unit g)) (str-or-nil? (:percent g))
                     (string? (:explanation g)) (boolean? (:collapsed? g))))

(s/def ::outcomes
  #(and (exact-keys? % outcomes-keys) (boolean? (:both-sides? %))
        (all-str-or-nil? % [:base-symbol :all-buys-first :all-sells-first :everything-fills])
        (nil-or gap? (:vwap-gap %))))

(defn- error-row? [e]
  (and (exact-keys? e error-keys) (keyword? (:code e)) (string? (:message e))
       (nil-or vector? (:path e)) (each offer? (:remediations e))))

(defn- warning-row? [w] (and (exact-keys? w warning-keys) (keyword? (:code w)) (string? (:message w))))

(s/def ::footer
  #(and (exact-keys? % footer-keys) (string? (:note %)) (string? (:review-label %))
        (boolean? (:review-enabled? %))))

(s/def ::ui #(and (exact-keys? % ui-keys) (every? boolean? (vals %))))

(s/def ::ticket-model
  (s/and #(exact-keys? % ticket-keys)
         #(boolean? (:visible? %))
         #(s/valid? ::ui (:ui %))
         #(if (:visible? %)
            (and (boolean? (:eligible? %)) (str-or-nil? (:eligibility-reason %))
                 (boolean? (:read-only? %))
                 (contains? #{:ready :invalid :unavailable} (:status %))
                 (s/valid? ::identity (:identity %))
                 (s/valid? ::model (:model %))
                 (s/valid? ::inventory (:inventory %))
                 (s/valid? ::buy (:buy %))
                 (s/valid? ::sell (:sell %))
                 (s/valid? ::outcomes (:outcomes %))
                 (each error-row? (:errors %))
                 (each warning-row? (:warnings %))
                 (s/valid? ::footer (:footer %)))
            true)))

;; ---------------------------------------------------------------------------
;; Review specs
;; ---------------------------------------------------------------------------

(defn- tile? [t] (and (exact-keys? t tile-keys) (keyword? (:id t)) (string? (:label t))
                      (str-or-nil? (:value t)) (str-or-nil? (:caption t))))
(defn- review-leg? [l] (and (exact-keys? l review-leg-keys) (string? (:leg-id l))
                            (contains? #{:buy :sell} (:side l)) (string? (:side-label l))
                            (all-str-or-nil? l [:price :size :notional :distance])))
(defn- detail-row? [r] (and (exact-keys? r detail-row-keys) (string? (:label r)) (str-or-nil? (:value r))))

(s/def ::review-footer
  #(and (exact-keys? % review-footer-keys)
        (false? (:placement-available? %))
        (string? (:text %)) (string? (:hint %)) (string? (:back-label %))))

(defn- review-replacement?
  "nil (an ordinary review) or the replacement's lineage section."
  [r]
  (or (nil? r)
      (and (exact-keys? r review-replacement-keys)
           (string? (:lineage-text r)) (string? (:remaining-text r)) (string? (:rejected-text r))
           (all-str-or-nil? r [:filled-text :edited-text]))))

(s/def ::review-model
  #(and (exact-keys? % review-keys)
        (review-replacement? (:replacement %))
        (boolean? (:open? %)) (boolean? (:available? %))
        (all-str-or-nil? % [:title :identity-line :plan-as-of :base-symbol :note])
        (each tile? (:tiles %))
        (each review-leg? (:legs %))
        (each detail-row? (:cash %))
        (each detail-row? (:inventory %))
        (each detail-row? (:details %))
        (s/valid? ::review-footer (:footer %))))

;; ---------------------------------------------------------------------------
;; Chart specs
;; ---------------------------------------------------------------------------

(defn- price-line? [l] (and (exact-keys? l price-line-keys) (string? (:price l)) (string? (:label l))))
(defn- band? [b] (and (exact-keys? b band-keys) (contains? #{1 2} (:sigma b))
                      (string? (:lower b)) (string? (:upper b))))
(defn- chart-leg? [l] (and (exact-keys? l chart-leg-keys) (string? (:leg-id l))
                           (contains? #{:buy :sell} (:side l)) (string? (:price l)) (string? (:size l))
                           (number? (:weight l)) (<= 0 (:weight l) 1) (string? (:label l))))

(s/def ::chart-model
  #(and (exact-keys? % chart-keys)
        (= :draft (:stage %)) (= "Draft" (:stage-label %))
        (price-line? (:anchor %))
        (nil-or price-line? (:shifted-anchor %))
        (each band? (:bands %))
        (each chart-leg? (:legs %))))

(defn ticket-model-valid? [x] (s/valid? ::ticket-model x))
(defn review-model-valid? [x] (s/valid? ::review-model x))
(defn chart-model-valid? [x] (or (nil? x) (s/valid? ::chart-model x)))
