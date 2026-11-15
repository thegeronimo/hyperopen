(ns hyperopen.portfolio.optimizer.application.black-litterman-editor-model.rules
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def view-kinds
  #{:absolute
    :relative})

(def max-active-views 10)

(def numeric-parameter-keys
  #{:return
    :confidence})

(def instrument-parameter-keys
  #{:instrument-id
    :comparator-instrument-id
    :long-instrument-id
    :short-instrument-id})

(def confidence-weight-by-level
  {:low 0.25
   :medium 0.5
   :high 0.75})

(def confidence-options
  [[:low "LOW"]
   [:medium "MEDIUM"]
   [:high "HIGH"]])

(def horizons
  #{:1m :3m :6m :1y})

(def horizon-options
  [[:1m "1M"]
   [:3m "3M"]
   [:6m "6M"]
   [:1y "1Y"]])

(def relative-directions
  #{:outperform :underperform})

(def normalize-keyword-like coercion/normalize-keyword-like)

(def non-blank-text coercion/non-blank-text)

(def finite-number? coercion/finite-number?)

(def parse-number-value coercion/parse-number)

(def parse-percent-text coercion/parse-percent-text)

(def decimal->percent-text coercion/decimal->percent-text)

(defn pct-label
  ([value]
   (pct-label value false))
  ([value signed?]
   (if (finite-number? value)
     (let [pct (* value 100)
           abs-pct (js/Math.abs pct)
           fixed (.toFixed abs-pct 2)
           trimmed (str/replace fixed #"\.?0+$" "")
           sign (cond
                  (not signed?) ""
                  (pos? pct) "+"
                  (neg? pct) "-"
                  :else "")]
       (str sign trimmed "%"))
     "--")))

(defn confidence-variance
  [confidence]
  (let [confidence* (-> (or confidence 0.5)
                        (max 0.0)
                        (min 1.0))]
    (max 0.000001 (- 1.0 confidence*))))

(defn next-view-id
  [views]
  (let [existing (set (keep :id views))]
    (loop [idx (inc (count views))]
      (let [id (str "bl_view_" idx)]
        (if (contains? existing id)
          (recur (inc idx))
          id)))))

(defn normalize-confidence-level
  [value]
  (let [level (normalize-keyword-like value)]
    (if (contains? confidence-weight-by-level level)
      level
      :medium)))

(defn confidence-weight
  [value]
  (cond
    (contains? confidence-weight-by-level value)
    (get confidence-weight-by-level value)

    (keyword? value)
    (get confidence-weight-by-level (normalize-confidence-level value))

    (string? value)
    (if-let [parsed (parse-number-value value)]
      parsed
      (get confidence-weight-by-level (normalize-confidence-level value)))

    (finite-number? value)
    value

    :else
    (get confidence-weight-by-level :medium)))

(defn confidence-level-from-view
  [view]
  (or (:confidence-level view)
      (let [confidence (or (:confidence view) 0.5)]
        (cond
          (<= confidence 0.25) :low
          (<= confidence 0.5) :medium
          :else :high))))

(defn normalize-horizon
  [value]
  (let [horizon (normalize-keyword-like value)]
    (if (contains? horizons horizon)
      horizon
      :3m)))

(defn normalize-direction
  [value]
  (let [direction (normalize-keyword-like value)]
    (if (contains? relative-directions direction)
      direction
      :outperform)))

(defn normalize-view-kind
  [value]
  (let [kind (normalize-keyword-like value)]
    (if (contains? view-kinds kind)
      kind
      :absolute)))

(defn selected-kind
  [editor-state]
  (normalize-view-kind (:selected-kind editor-state)))

(defn relative-weights
  [instrument-id comparator-id direction]
  (case (normalize-direction direction)
    :underperform {instrument-id -1
                   comparator-id 1}
    {instrument-id 1
     comparator-id -1}))

(def empty-absolute-draft
  {:instrument-id nil
   :return-text ""
   :return-text-touched? false
   :confidence :medium
   :horizon :3m
   :notes ""})

(def empty-relative-draft
  {:instrument-id nil
   :comparator-instrument-id nil
   :direction :outperform
   :return-text ""
   :return-text-touched? false
   :confidence :medium
   :horizon :3m
   :notes ""})

(defn default-editor-state
  []
  {:selected-kind :absolute
   :drafts {:absolute empty-absolute-draft
            :relative empty-relative-draft}
   :editing-view-id nil
   :errors {}
   :clear-confirmation-open? false})

(defn draft-defaults
  [universe kind]
  (let [ids (mapv :instrument-id universe)
        [first-id second-id] ids]
    (case (normalize-view-kind kind)
      :relative (assoc empty-relative-draft
                       :instrument-id first-id
                       :comparator-instrument-id (or second-id first-id))
      (assoc empty-absolute-draft
             :instrument-id first-id))))

(defn drop-nil-values
  [m]
  (into {}
        (remove (fn [[_ value]]
                  (nil? value)))
        m))

(defn editor-draft
  [universe editor-state kind]
  (merge (draft-defaults universe kind)
         (drop-nil-values (get-in editor-state [:drafts (normalize-view-kind kind)]))))

(defn trim-notes
  [value]
  (let [text (str (or value ""))]
    (subs text 0 (min 280 (count text)))))

(defn normalized-draft-field
  [field value]
  (case field
    :confidence (normalize-confidence-level value)
    :horizon (normalize-horizon value)
    :direction (normalize-direction value)
    :notes (trim-notes value)
    value))

(defn with-automatic-absolute-return-text
  [draft kind return-inputs-by-instrument editing?]
  (let [instrument-id (non-blank-text (:instrument-id draft))]
    (if (and (= :absolute kind)
             (not editing?)
             instrument-id
             (not (:return-text-touched? draft))
             (nil? (parse-percent-text (:return-text draft))))
      (if-let [return-input (get return-inputs-by-instrument instrument-id)]
        (assoc draft :return-text (decimal->percent-text return-input))
        draft)
      draft)))

(defn selected-draft
  [universe editor-state kind return-inputs-by-instrument editing?]
  (-> (editor-draft universe editor-state kind)
      (with-automatic-absolute-return-text
        (normalize-view-kind kind)
        return-inputs-by-instrument
        editing?)))

(defn pending-draft?
  [draft editing?]
  (boolean
   (or editing?
       (:return-text-touched? draft)
       (some-> (:return-text draft) str str/trim seq)
       (some-> (:notes draft) str str/trim seq))))

(defn instrument-present?
  [universe instrument-id]
  (boolean
   (some #(= instrument-id (:instrument-id %)) universe)))

(defn valid-instrument-id?
  [universe instrument-id]
  (and (non-blank-text instrument-id)
       (instrument-present? universe instrument-id)))

(defn validate-draft
  [black-litterman? universe views kind draft editing?]
  (let [kind* (normalize-view-kind kind)
        return-value (parse-percent-text (:return-text draft))
        instrument-id (non-blank-text (:instrument-id draft))
        comparator-id (non-blank-text (:comparator-instrument-id draft))]
    (cond-> {}
      (not black-litterman?)
      (assoc :model "Use My Views must be selected.")

      (and (not editing?) (>= (count views) max-active-views))
      (assoc :max "Maximum of 10 active views reached.")

      (not (valid-instrument-id? universe instrument-id))
      (assoc :instrument-id "Select an asset.")

      (nil? return-value)
      (assoc :return-text "Enter a valid percentage.")

      (and (= :relative kind*)
           (not (valid-instrument-id? universe comparator-id)))
      (assoc :comparator-instrument-id "Select a comparator asset.")

      (and (= :relative kind*)
           instrument-id
           comparator-id
           (= instrument-id comparator-id))
      (assoc :comparator-instrument-id "Choose a different comparator asset.")

      (and (= :relative kind*)
           (some? return-value)
           (neg? return-value))
      (assoc :return-text "Spread must be positive. Use direction to express underperformance."))))

(defn draft-valid?
  [universe kind draft active-count editing?]
  (empty?
   (validate-draft true
                   universe
                   (repeat active-count {})
                   kind
                   draft
                   editing?)))

(defn label-for
  [label-fn instrument-id]
  (or (when (and label-fn instrument-id)
        (label-fn instrument-id))
      instrument-id
      "Select"))

(defn preview-text
  [label-fn kind draft]
  (let [kind* (normalize-view-kind kind)
        value (parse-percent-text (:return-text draft))
        instrument-id (:instrument-id draft)
        asset (label-for label-fn instrument-id)]
    (if (and instrument-id value)
      (case kind*
        :relative
        (let [comparator-id (:comparator-instrument-id draft)]
          (if (and comparator-id (not= comparator-id instrument-id))
            (str asset " "
                 (if (= :underperform (normalize-direction (:direction draft))) "<" ">")
                 " " (label-for label-fn comparator-id)
                 " by " (pct-label value) " annualized")
            "Select a comparator asset to preview this view."))
        (str asset " expected return " (pct-label value true) " annualized"))
      "Select an asset and enter a value to preview this view.")))

(defn view-primary-id
  [view]
  (or (non-blank-text (:instrument-id view))
      (non-blank-text (:long-instrument-id view))))

(defn view-comparator-id
  [view]
  (or (non-blank-text (:comparator-instrument-id view))
      (non-blank-text (:short-instrument-id view))))

(defn view-direction
  [view]
  (or (:direction view)
      (when (= :relative (:kind view))
        :outperform)))

(defn view-by-id
  [views view-id]
  (some #(when (= view-id (:id %)) %) views))
