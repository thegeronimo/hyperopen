(ns hyperopen.portfolio.optimizer.black-litterman-actions
  (:require [clojure.string :as str]))

(def ^:private view-kinds
  #{:absolute
    :relative})

(def ^:private max-active-views 10)

(def ^:private editor-path
  [:portfolio-ui :optimizer :black-litterman-editor])

(def ^:private numeric-parameter-keys
  #{:return
    :confidence})

(def ^:private instrument-parameter-keys
  #{:instrument-id
    :comparator-instrument-id
    :long-instrument-id
    :short-instrument-id})

(def ^:private confidence-weight-by-level
  {:low 0.25
   :medium 0.5
   :high 0.75})

(def ^:private horizons
  #{:1m :3m :6m :1y})

(def ^:private relative-directions
  #{:outperform :underperform})

(defn- normalize-keyword-like
  [value]
  (let [text (cond
               (keyword? value) (name value)
               (string? value) (str/trim value)
               :else nil)]
    (when (seq text)
      (-> text
          (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
          (str/replace #"[_\s]+" "-")
          str/lower-case
          keyword))))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- parse-number-value
  [value]
  (cond
    (finite-number? value)
    value

    (string? value)
    (let [text (str/trim value)]
      (when (seq text)
        (let [parsed (js/Number text)]
          (when (finite-number? parsed)
            parsed))))

    :else nil))

(defn- parse-percent-text
  [value]
  (cond
    (finite-number? value)
    (/ value 100)

    (string? value)
    (let [text (-> value
                   str/trim
                   (str/replace #"," "")
                   (str/replace #"%" "")
                   str/trim)]
      (when (seq text)
        (let [parsed (js/Number text)]
          (when (finite-number? parsed)
            (/ parsed 100)))))

    :else nil))

(defn- decimal->percent-text
  [value]
  (if (finite-number? value)
    (-> (.toFixed (* value 100) 4)
        (str/replace #"\.?0+$" ""))
    ""))

(defn- save-draft-path-values
  [path-values]
  [[:effects/save-many
    (conj (vec path-values)
          [[:portfolio :optimizer :draft :metadata :dirty?] true])]])

(defn- save-ui-path-values
  [path-values]
  [[:effects/save-many (vec path-values)]])

(defn- draft-universe
  [state]
  (vec (or (get-in state [:portfolio :optimizer :draft :universe])
           [])))

(defn- black-litterman-return-model?
  [state]
  (= :black-litterman
     (get-in state [:portfolio :optimizer :draft :return-model :kind])))

(defn- black-litterman-views
  [state]
  (vec (or (get-in state [:portfolio :optimizer :draft :return-model :views])
           [])))

(defn- universe-instrument-ids
  [state]
  (vec (keep :instrument-id (draft-universe state))))

(defn- instrument-present?
  [universe instrument-id]
  (boolean
   (some #(= instrument-id (:instrument-id %)) universe)))

(defn- valid-instrument-id?
  [state instrument-id]
  (and (non-blank-text instrument-id)
       (instrument-present? (draft-universe state) instrument-id)))

(defn- confidence-variance
  [confidence]
  (let [confidence* (-> (or confidence 0.5)
                        (max 0.0)
                        (min 1.0))]
    (max 0.000001 (- 1.0 confidence*))))

(defn- next-view-id
  [views]
  (let [existing (set (keep :id views))]
    (loop [idx (inc (count views))]
      (let [id (str "bl_view_" idx)]
        (if (contains? existing id)
          (recur (inc idx))
          id)))))

(defn- normalize-confidence-level
  [value]
  (let [level (normalize-keyword-like value)]
    (if (contains? confidence-weight-by-level level)
      level
      :medium)))

(defn- confidence-weight
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

(defn- confidence-level-from-view
  [view]
  (or (:confidence-level view)
      (let [confidence (or (:confidence view) 0.5)]
        (cond
          (<= confidence 0.25) :low
          (<= confidence 0.5) :medium
          :else :high))))

(defn- normalize-horizon
  [value]
  (let [horizon (normalize-keyword-like value)]
    (if (contains? horizons horizon)
      horizon
      :3m)))

(defn- normalize-direction
  [value]
  (let [direction (normalize-keyword-like value)]
    (if (contains? relative-directions direction)
      direction
      :outperform)))

(defn view-primary-instrument-id
  [view]
  (or (non-blank-text (:instrument-id view))
      (non-blank-text (:long-instrument-id view))))

(defn view-comparator-instrument-id
  [view]
  (or (non-blank-text (:comparator-instrument-id view))
      (non-blank-text (:short-instrument-id view))))

(defn- view-direction
  [view]
  (or (:direction view)
      (when (= :relative (:kind view))
        :outperform)))

(defn- relative-weights
  [instrument-id comparator-id direction]
  (case (normalize-direction direction)
    :underperform {instrument-id -1
                   comparator-id 1}
    {instrument-id 1
     comparator-id -1}))

(defn view-instrument-ids
  [view]
  (case (:kind view)
    :absolute (vec (keep non-blank-text [(:instrument-id view)]))
    :relative (vec (keep non-blank-text [(view-primary-instrument-id view)
                                         (view-comparator-instrument-id view)]))
    []))

(defn- default-view
  [state kind id]
  (let [ids (universe-instrument-ids state)
        kind* (normalize-keyword-like kind)
        confidence 0.5]
    (case kind*
      :absolute
      (when-let [instrument-id (first ids)]
        {:id id
         :kind :absolute
         :instrument-id instrument-id
         :return 0.0
         :confidence-level :medium
         :confidence confidence
         :confidence-variance (confidence-variance confidence)
         :horizon :3m
         :weights {instrument-id 1}})

      :relative
      (when (<= 2 (count ids))
        (let [[long-id short-id] ids]
          {:id id
           :kind :relative
           :instrument-id long-id
           :comparator-instrument-id short-id
           :direction :outperform
           :long-instrument-id long-id
           :short-instrument-id short-id
           :return 0.0
           :confidence-level :medium
           :confidence confidence
           :confidence-variance (confidence-variance confidence)
           :horizon :3m
           :weights {long-id 1
                     short-id -1}}))

      nil)))

(defn- rebuild-weights
  [view]
  (case (:kind view)
    :absolute
    (if-let [instrument-id (non-blank-text (:instrument-id view))]
      (assoc view :weights {instrument-id 1})
      view)

    :relative
    (let [instrument-id (view-primary-instrument-id view)
          comparator-id (view-comparator-instrument-id view)
          direction (normalize-direction (view-direction view))]
      (if (and instrument-id comparator-id (not= instrument-id comparator-id))
        (assoc view
               :instrument-id instrument-id
               :comparator-instrument-id comparator-id
               :direction direction
               :weights (relative-weights instrument-id comparator-id direction))
        view))

    view))

(defn- replace-view
  [views view-id f]
  (let [view-id* (non-blank-text view-id)
        replaced? (volatile! false)
        views* (mapv (fn [view]
                       (if (= view-id* (:id view))
                         (do
                           (vreset! replaced? true)
                           (f view))
                         view))
                     views)]
    (when @replaced?
      views*)))

(defn- view-by-id
  [views view-id]
  (some #(when (= view-id (:id %)) %) views))

(defn- valid-instrument-update?
  [view parameter-key instrument-id]
  (case parameter-key
    :long-instrument-id
    (not= instrument-id (:short-instrument-id view))

    :short-instrument-id
    (not= instrument-id (:long-instrument-id view))

    :comparator-instrument-id
    (not= instrument-id (view-primary-instrument-id view))

    true))

(defn- save-views
  [views]
  (save-draft-path-values
   [[[:portfolio :optimizer :draft :return-model :views] (vec views)]]))

(defn add-portfolio-optimizer-black-litterman-view
  [state view-kind]
  (let [view-kind* (normalize-keyword-like view-kind)
        views (black-litterman-views state)]
    (if (and (black-litterman-return-model? state)
             (contains? view-kinds view-kind*))
      (if-let [view (default-view state view-kind* (next-view-id views))]
        (save-views (conj views view))
        [])
      [])))

(defn set-portfolio-optimizer-black-litterman-view-parameter
  [state view-id parameter-key value]
  (let [parameter-key* (normalize-keyword-like parameter-key)
        views (black-litterman-views state)
        replace-one (fn [f]
                      (if-let [views* (replace-view views view-id f)]
                        (save-views views*)
                        []))]
    (cond
      (not (black-litterman-return-model? state))
      []

      (= :kind parameter-key*)
      (let [kind* (normalize-keyword-like value)]
        (if (contains? view-kinds kind*)
          (replace-one
           (fn [view]
             (let [new-view (or (default-view state kind* (:id view))
                                view)
                   confidence (or (:confidence view) 0.5)]
               (-> new-view
                   (assoc :return (or (:return view) 0.0)
                          :confidence-level (or (:confidence-level view) :medium)
                          :confidence confidence
                          :confidence-variance (confidence-variance confidence)
                          :horizon (or (:horizon view) :3m))
                   rebuild-weights))))
          []))

      (contains? numeric-parameter-keys parameter-key*)
      (let [value* (parse-number-value value)]
        (if (some? value*)
          (replace-one
           (fn [view]
             (cond-> (assoc view parameter-key* value*)
               (= :confidence parameter-key*)
               (assoc :confidence-variance (confidence-variance value*)))))
          []))

      (contains? instrument-parameter-keys parameter-key*)
      (let [instrument-id* (non-blank-text value)]
        (if (and (valid-instrument-id? state instrument-id*)
                 (valid-instrument-update?
                  (view-by-id views (non-blank-text view-id))
                  parameter-key*
                  instrument-id*))
          (replace-one
           (fn [view]
             (rebuild-weights
              (assoc view parameter-key* instrument-id*))))
          []))

      :else [])))

(defn- selected-kind
  [state]
  (let [kind (normalize-keyword-like (get-in state (conj editor-path :selected-kind)))]
    (if (contains? view-kinds kind)
      kind
      :absolute)))

(defn- draft-defaults
  [state kind]
  (let [ids (universe-instrument-ids state)
        [first-id second-id] ids]
    (case kind
      :relative {:instrument-id first-id
                 :comparator-instrument-id (or second-id first-id)
                 :direction :outperform
                 :return-text ""
                 :confidence :medium
                 :horizon :3m
                 :notes ""}
      {:instrument-id first-id
       :return-text ""
       :confidence :medium
       :horizon :3m
       :notes ""})))

(defn- editor-draft
  [state kind]
  (merge (draft-defaults state kind)
         (get-in state (conj editor-path :drafts kind))))

(defn- editor-draft-path
  [kind field]
  (conj editor-path :drafts kind field))

(defn- trim-notes
  [value]
  (let [text (str (or value ""))]
    (subs text 0 (min 280 (count text)))))

(defn- normalized-draft-field
  [field value]
  (case field
    :confidence (normalize-confidence-level value)
    :horizon (normalize-horizon value)
    :direction (normalize-direction value)
    :notes (trim-notes value)
    value))

(defn- draft->view
  [kind draft view-id]
  (let [return-value (parse-percent-text (:return-text draft))
        confidence-level (normalize-confidence-level (:confidence draft))
        confidence (confidence-weight confidence-level)
        horizon (normalize-horizon (:horizon draft))
        notes (non-blank-text (:notes draft))]
    (case kind
      :relative
      (let [instrument-id (non-blank-text (:instrument-id draft))
            comparator-id (non-blank-text (:comparator-instrument-id draft))
            direction (normalize-direction (:direction draft))]
        (cond-> {:id view-id
                 :kind :relative
                 :instrument-id instrument-id
                 :comparator-instrument-id comparator-id
                 :direction direction
                 :return return-value
                 :confidence-level confidence-level
                 :confidence confidence
                 :confidence-variance (confidence-variance confidence)
                 :horizon horizon
                 :weights (when (and instrument-id comparator-id)
                            (relative-weights instrument-id comparator-id direction))}
          notes (assoc :notes notes)))

      (let [instrument-id (non-blank-text (:instrument-id draft))]
        (cond-> {:id view-id
                 :kind :absolute
                 :instrument-id instrument-id
                 :return return-value
                 :confidence-level confidence-level
                 :confidence confidence
                 :confidence-variance (confidence-variance confidence)
                 :horizon horizon
                 :weights (when instrument-id {instrument-id 1})}
          notes (assoc :notes notes))))))

(defn- validate-draft
  [state kind draft editing?]
  (let [return-value (parse-percent-text (:return-text draft))
        instrument-id (non-blank-text (:instrument-id draft))
        comparator-id (non-blank-text (:comparator-instrument-id draft))
        views (black-litterman-views state)]
    (cond-> {}
      (not (black-litterman-return-model? state))
      (assoc :model "Use My Views must be selected.")

      (and (not editing?) (>= (count views) max-active-views))
      (assoc :max "Maximum of 10 active views reached.")

      (not (valid-instrument-id? state instrument-id))
      (assoc :instrument-id "Select an asset.")

      (nil? return-value)
      (assoc :return-text "Enter a valid percentage.")

      (and (= :relative kind)
           (not (valid-instrument-id? state comparator-id)))
      (assoc :comparator-instrument-id "Select a comparator asset.")

      (and (= :relative kind)
           instrument-id
           comparator-id
           (= instrument-id comparator-id))
      (assoc :comparator-instrument-id "Choose a different comparator asset.")

      (and (= :relative kind)
           (some? return-value)
           (neg? return-value))
      (assoc :return-text "Spread must be positive. Use direction to express underperformance."))))

(defn- reset-draft-after-save
  [draft]
  (assoc draft
         :return-text ""
         :notes ""))

(defn- view->draft
  [view]
  (let [kind (:kind view)]
    (case kind
      :relative {:instrument-id (view-primary-instrument-id view)
                 :comparator-instrument-id (view-comparator-instrument-id view)
                 :direction (normalize-direction (view-direction view))
                 :return-text (decimal->percent-text (:return view))
                 :confidence (normalize-confidence-level (confidence-level-from-view view))
                 :horizon (normalize-horizon (:horizon view))
                 :notes (or (:notes view) "")}
      {:instrument-id (:instrument-id view)
       :return-text (decimal->percent-text (:return view))
       :confidence (normalize-confidence-level (confidence-level-from-view view))
       :horizon (normalize-horizon (:horizon view))
       :notes (or (:notes view) "")})))

(defn set-portfolio-optimizer-black-litterman-editor-type
  [_state view-kind]
  (let [kind (normalize-keyword-like view-kind)]
    (if (contains? view-kinds kind)
      (save-ui-path-values [[(conj editor-path :selected-kind) kind]
                            [(conj editor-path :errors) {}]])
      [])))

(defn set-portfolio-optimizer-black-litterman-editor-field
  [state field value]
  (let [kind (selected-kind state)
        field* (normalize-keyword-like field)]
    (save-ui-path-values [[(editor-draft-path kind field*)
                           (normalized-draft-field field* value)]
                          [(conj editor-path :errors field*) nil]])))

(defn save-portfolio-optimizer-black-litterman-editor-view
  [state]
  (let [kind (selected-kind state)
        draft (editor-draft state kind)
        editing-view-id (non-blank-text (get-in state (conj editor-path :editing-view-id)))
        editing? (boolean editing-view-id)
        errors (validate-draft state kind draft editing?)
        views (black-litterman-views state)]
    (if (seq errors)
      (save-ui-path-values
       (into [[(conj editor-path :errors) errors]]
             (map (fn [[field message]]
                    [(conj editor-path :errors field) message])
                  errors)))
      (let [view-id (or editing-view-id (next-view-id views))
            view (draft->view kind draft view-id)
            views* (if editing?
                     (mapv (fn [existing]
                             (if (= view-id (:id existing))
                               view
                               existing))
                           views)
                     (conj views view))]
        (save-draft-path-values
         [[[:portfolio :optimizer :draft :return-model :views] views*]
          [(conj editor-path :drafts kind) (reset-draft-after-save draft)]
          [(conj editor-path :editing-view-id) nil]
          [(conj editor-path :errors) {}]
          [(conj editor-path :clear-confirmation-open?) false]])))))

(defn edit-portfolio-optimizer-black-litterman-view
  [state view-id]
  (let [view-id* (non-blank-text view-id)
        view (view-by-id (black-litterman-views state) view-id*)]
    (if view
      (let [kind (or (:kind view) :absolute)]
        (save-ui-path-values [[(conj editor-path :selected-kind) kind]
                              [(conj editor-path :drafts kind) (view->draft view)]
                              [(conj editor-path :editing-view-id) view-id*]
                              [(conj editor-path :errors) {}]]))
      [])))

(defn cancel-portfolio-optimizer-black-litterman-edit
  [state]
  (let [kind (selected-kind state)]
    (save-ui-path-values [[(conj editor-path :editing-view-id) nil]
                          [(conj editor-path :drafts kind)
                           (reset-draft-after-save (editor-draft state kind))]
                          [(conj editor-path :errors) {}]])))

(defn request-clear-portfolio-optimizer-black-litterman-views
  [_state]
  (save-ui-path-values [[(conj editor-path :clear-confirmation-open?) true]]))

(defn cancel-clear-portfolio-optimizer-black-litterman-views
  [_state]
  (save-ui-path-values [[(conj editor-path :clear-confirmation-open?) false]]))

(defn confirm-clear-portfolio-optimizer-black-litterman-views
  [state]
  (if (and (black-litterman-return-model? state)
           (seq (black-litterman-views state)))
    (save-draft-path-values
     [[[:portfolio :optimizer :draft :return-model :views] []]
      [(conj editor-path :clear-confirmation-open?) false]
      [(conj editor-path :editing-view-id) nil]
      [(conj editor-path :errors) {}]])
    (save-ui-path-values [[(conj editor-path :clear-confirmation-open?) false]
                          [(conj editor-path :editing-view-id) nil]])))

(defn remove-portfolio-optimizer-black-litterman-view
  [state view-id]
  (let [view-id* (non-blank-text view-id)
        views (black-litterman-views state)
        views* (vec (remove #(= view-id* (:id %)) views))]
    (if (and (black-litterman-return-model? state)
             view-id*
             (not= views views*))
      (save-draft-path-values
       [[[:portfolio :optimizer :draft :return-model :views] views*]
        [(conj editor-path :editing-view-id)
         (when (not= view-id*
                     (get-in state (conj editor-path :editing-view-id)))
           (get-in state (conj editor-path :editing-view-id)))]])
      [])))
