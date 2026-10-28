(ns hyperopen.schema.vol-range-repair-replace-contracts
  "Exact-key contracts for the Vol Range repair and replace view models
  (HO-SPEC-014 v2.1 sections 8.6, 10.2, 11.2). The dialogs consume ONLY these
  shapes.

  REPAIR MODEL (`repair-model/repair-model`): nil when no review is open, or
  `repair-keys` {:grid-id :status :title :identity-line :intro :loading-text
  :base-symbol :legs :unrepairable :bounds :limits-warning :errors :as-of
  :refresh :submit} with `repair-leg-keys`, `unrepairable-keys`,
  `bound-row-keys`, `refresh-keys` {:enabled? :busy?} and `submit-keys`
  {:label :enabled? :busy?}. Display values are strings (already
  formatted), booleans or keywords."
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
         (exact-keys? (:submit m) submit-keys)
         (string? (get-in m [:submit :label]))
         (boolean? (get-in m [:submit :enabled?]))
         (boolean? (get-in m [:submit :busy?])))))

(defn repair-model-valid?
  "nil (no review open) or an exact repair dialog model."
  [x]
  (or (nil? x) (s/valid? ::repair-model x)))
