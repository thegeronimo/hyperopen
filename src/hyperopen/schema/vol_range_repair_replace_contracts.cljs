(ns hyperopen.schema.vol-range-repair-replace-contracts
  "Exact-key contracts for the Vol Range repair and replace view models
  (HO-SPEC-014 v2.1 sections 8.6, 10.2, 11.2). The dialogs consume ONLY these
  shapes.

  REPAIR MODEL (`repair-model/repair-model`): nil when no review is open, or
  `repair-keys` {:grid-id :status :title :identity-line :intro :loading-text
  :base-symbol :legs :unrepairable :bounds :limits-warning :errors :as-of
  :refresh :submit} with `repair-leg-keys`, `unrepairable-keys`,
  `bound-row-keys`, `refresh-keys` {:enabled? :busy?} and `submit-keys`
  {:label :enabled? :busy?} plus an optional string `:note` (why the submit
  waits for live monitoring). Display values are strings (already
  formatted), booleans or keywords.

  REPLACE MODEL and BANNER MODEL: see `replace-model-valid?` and
  `replace-banner-valid?` below."
  (:require [cljs.spec.alpha :as s]))

(defn- exact-keys? [m ks] (and (map? m) (= (set (keys m)) ks)))
(defn- str-or-nil? [x] (or (nil? x) (string? x)))
(defn- each [pred xs] (and (vector? xs) (every? pred xs)))

(def repair-keys #{:grid-id :status :title :identity-line :intro :loading-text :base-symbol :legs
                   :unrepairable :bounds :limits-warning :errors :as-of :refresh :submit})
(def repair-leg-keys #{:logical-id :side :side-label :selected? :select-label :rejected-price :price :size
                       :notional :distance :note :reason})
(def unrepairable-keys #{:logical-id :text})
(def bound-row-keys #{:label :value})
(def refresh-keys #{:enabled? :busy?})
(def submit-keys #{:label :enabled? :busy?})

(defn- repair-leg? [l]
  (and (exact-keys? l repair-leg-keys)
       (string? (:logical-id l))
       (contains? #{:buy :sell} (:side l))
       (boolean? (:selected? l))
       (every? #(string? (get l %)) [:side-label :select-label :price :size :note])
       (every? #(str-or-nil? (get l %)) [:rejected-price :notional :distance :reason])))

(defn- unrepairable? [u]
  (and (exact-keys? u unrepairable-keys) (string? (:logical-id u)) (string? (:text u))))

(defn- bound-row? [r] (and (exact-keys? r bound-row-keys) (string? (:label r)) (str-or-nil? (:value r))))

(s/def ::repair-model
  (fn [m]
    (and (exact-keys? m repair-keys)
         (string? (:grid-id m))
         (contains? #{:loading :ready :error} (:status m))
         (every? #(string? (get m %)) [:title :identity-line :intro])
         (every? #(str-or-nil? (get m %)) [:loading-text :base-symbol :limits-warning :as-of])
         (each repair-leg? (:legs m))
         (each unrepairable? (:unrepairable m))
         (each bound-row? (:bounds m))
         (each string? (:errors m))
         (exact-keys? (:refresh m) refresh-keys)
         (every? boolean? (vals (:refresh m)))
         (or (exact-keys? (:submit m) submit-keys)
             (and (exact-keys? (:submit m) (conj submit-keys :note)) (string? (get-in m [:submit :note]))))
         (string? (get-in m [:submit :label]))
         (boolean? (get-in m [:submit :enabled?]))
         (boolean? (get-in m [:submit :busy?])))))

(defn repair-model-valid?
  "nil (no review open) or an exact repair dialog model."
  [x]
  (or (nil? x) (s/valid? ::repair-model x)))

;; ---------------------------------------------------------------------------
;; Replace (spec 10.2)
;; ---------------------------------------------------------------------------
;;
;; REPLACE MODEL (`replace-model/replace-dialog-model`): nil when the update
;; dialog is closed, or `replace-keys` with `step-keys` {:label :current?},
;; `compare-row-keys` {:field :label :current :proposed}, `rejected-row-keys`
;; {:logical-id :label :selected? :enabled?} and `:actions` (`action-keys`,
;; each nil or a `control-keys` map {:label :enabled? :busy? :aria-label};
;; `:abandon` is always present).
;;
;; BANNER MODEL (`replace-model/banner-model`): nil without an update, or
;; `banner-keys` {:phase :title :message :lineage :replaces-footer?
;; :open-update? :refresh-balances :busy?}; `:refresh-balances` is nil or
;; {:enabled? :busy?}.

(def replace-phases #{:preview :canceling :blocked :canceled :ready})
(def replace-keys #{:grid-id :phase :stale-text :title :identity-line :steps :explainer :compare :settings-note
                    :quantity-note :phase-text :blocking :message :remaining-text :filled-text :included-text
                    :rejected :actions})
(def step-keys #{:label :current?})
(def compare-row-keys #{:field :label :current :proposed})
(def rejected-row-keys #{:logical-id :label :selected? :enabled?})
(def action-keys #{:cancel-old :edit :cancel-remaining :check :use-quantities :abandon})
(def control-keys #{:label :enabled? :busy? :aria-label})
(def banner-keys #{:phase :title :message :lineage :replaces-footer? :open-update? :refresh-balances :busy?})

(defn- step? [x] (and (exact-keys? x step-keys) (string? (:label x)) (boolean? (:current? x))))

(defn- compare-row? [r]
  (and (exact-keys? r compare-row-keys) (keyword? (:field r)) (string? (:label r))
       (str-or-nil? (:current r)) (str-or-nil? (:proposed r))))

(defn- rejected-row? [r]
  (and (exact-keys? r rejected-row-keys) (string? (:logical-id r)) (string? (:label r))
       (boolean? (:selected? r)) (boolean? (:enabled? r))))

(defn- control? [c]
  (and (exact-keys? c control-keys) (string? (:label c)) (boolean? (:enabled? c)) (boolean? (:busy? c))
       (str-or-nil? (:aria-label c))))

(s/def ::replace-model
  (fn [m]
    (and (exact-keys? m replace-keys)
         (string? (:grid-id m))
         (contains? replace-phases (:phase m))
         (every? #(string? (get m %)) [:title :identity-line])
         (every? #(str-or-nil? (get m %)) [:stale-text :explainer :settings-note :quantity-note :phase-text
                                            :message :remaining-text :filled-text :included-text])
         (each step? (:steps m))
         (= 3 (count (:steps m)))
         (= 1 (count (filter :current? (:steps m))))
         (each compare-row? (:compare m))
         (each string? (:blocking m))
         (each rejected-row? (:rejected m))
         (exact-keys? (:actions m) action-keys)
         (every? #(or (nil? %) (control? %)) (vals (:actions m)))
         (control? (get-in m [:actions :abandon])))))

(s/def ::replace-banner
  (fn [b]
    (and (exact-keys? b banner-keys)
         (contains? replace-phases (:phase b))
         (string? (:title b))
         (str-or-nil? (:message b))
         (str-or-nil? (:lineage b))
         (every? #(boolean? (get b %)) [:replaces-footer? :open-update? :busy?])
         (let [r (:refresh-balances b)]
           (or (nil? r) (and (exact-keys? r refresh-keys) (every? boolean? (vals r))))))))

(defn replace-model-valid?
  "nil (no update dialog open) or an exact update dialog model."
  [x]
  (or (nil? x) (s/valid? ::replace-model x)))

(defn replace-banner-valid?
  "nil (no update) or an exact ticket banner model."
  [x]
  (or (nil? x) (s/valid? ::replace-banner x)))
