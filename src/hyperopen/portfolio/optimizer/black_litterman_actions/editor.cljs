(ns hyperopen.portfolio.optimizer.black-litterman-actions.editor
  (:require [hyperopen.portfolio.optimizer.application.engine.context :as engine-context]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.black-litterman-actions.common :as common]
            [hyperopen.portfolio.optimizer.black-litterman-actions.views :as views]))

(defn- draft->view
  [kind draft view-id]
  (let [return-value (common/parse-percent-text (:return-text draft))
        confidence-level (common/normalize-confidence-level (:confidence draft))
        confidence (common/confidence-weight confidence-level)
        horizon (common/normalize-horizon (:horizon draft))
        notes (common/non-blank-text (:notes draft))]
    (case kind
      :relative
      (let [instrument-id (common/non-blank-text (:instrument-id draft))
            comparator-id (common/non-blank-text (:comparator-instrument-id draft))
            direction (common/normalize-direction (:direction draft))]
        (cond-> {:id view-id
                 :kind :relative
                 :instrument-id instrument-id
                 :comparator-instrument-id comparator-id
                 :direction direction
                 :return return-value
                 :confidence-level confidence-level
                 :confidence confidence
                 :confidence-variance (common/confidence-variance confidence)
                 :horizon horizon
                 :weights (when (and instrument-id comparator-id)
                            (common/relative-weights instrument-id comparator-id direction))}
          notes (assoc :notes notes)))

      (let [instrument-id (common/non-blank-text (:instrument-id draft))]
        (cond-> {:id view-id
                 :kind :absolute
                 :instrument-id instrument-id
                 :return return-value
                 :confidence-level confidence-level
                 :confidence confidence
                 :confidence-variance (common/confidence-variance confidence)
                 :horizon horizon
                 :weights (when instrument-id {instrument-id 1})}
          notes (assoc :notes notes))))))

(defn- validate-draft
  [state kind draft editing?]
  (let [return-value (common/parse-percent-text (:return-text draft))
        instrument-id (common/non-blank-text (:instrument-id draft))
        comparator-id (common/non-blank-text (:comparator-instrument-id draft))
        views (common/black-litterman-views state)]
    (cond-> {}
      (not (common/black-litterman-return-model? state))
      (assoc :model "Use My Views must be selected.")

      (and (not editing?) (>= (count views) common/max-active-views))
      (assoc :max "Maximum of 10 active views reached.")

      (not (common/valid-instrument-id? state instrument-id))
      (assoc :instrument-id "Select an asset.")

      (nil? return-value)
      (assoc :return-text "Enter a valid percentage.")

      (and (= :relative kind)
           (not (common/valid-instrument-id? state comparator-id)))
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

(defn- automatic-return-inputs
  [state]
  (let [readiness (setup-readiness/build-readiness state)
        request (:request readiness)]
    (if (and (= :ready (:status readiness))
             (= :black-litterman (get-in request [:return-model :kind])))
      (engine-context/expected-return-inputs-by-instrument request)
      {})))

(defn- with-automatic-absolute-return-text
  [state kind draft editing?]
  (let [instrument-id (common/non-blank-text (:instrument-id draft))]
    (if (and (= :absolute kind)
             (not editing?)
             instrument-id
             (not (:return-text-touched? draft))
             (nil? (common/parse-percent-text (:return-text draft))))
      (if-let [return-input (get (automatic-return-inputs state) instrument-id)]
        (assoc draft :return-text (common/decimal->percent-text return-input))
        draft)
      draft)))

(defn- reset-draft-after-save
  [draft]
  (assoc draft
         :return-text ""
         :return-text-touched? false
         :notes ""))

(defn- view->draft
  [view]
  (let [kind (:kind view)]
    (case kind
      :relative {:instrument-id (views/view-primary-instrument-id view)
                 :comparator-instrument-id (views/view-comparator-instrument-id view)
                 :direction (common/normalize-direction (:direction view))
                 :return-text (common/decimal->percent-text (:return view))
                 :return-text-touched? true
                 :confidence (common/normalize-confidence-level
                              (common/confidence-level-from-view view))
                 :horizon (common/normalize-horizon (:horizon view))
                 :notes (or (:notes view) "")}
      {:instrument-id (:instrument-id view)
       :return-text (common/decimal->percent-text (:return view))
       :return-text-touched? true
       :confidence (common/normalize-confidence-level
                    (common/confidence-level-from-view view))
       :horizon (common/normalize-horizon (:horizon view))
       :notes (or (:notes view) "")})))

(defn set-portfolio-optimizer-black-litterman-editor-type
  [_state view-kind]
  (let [kind (common/normalize-keyword-like view-kind)]
    (if (contains? common/view-kinds kind)
      (common/save-ui-path-values [[(conj common/editor-path :selected-kind) kind]
                                   [(conj common/editor-path :errors) {}]])
      [])))

(defn set-portfolio-optimizer-black-litterman-editor-field
  [state field value]
  (let [kind (common/selected-kind state)
        field* (common/normalize-keyword-like field)]
    (common/save-ui-path-values
     (cond-> [[(common/editor-draft-path kind field*)
               (common/normalized-draft-field field* value)]
              [(conj common/editor-path :errors field*) nil]]
       (= :return-text field*)
       (conj [(common/editor-draft-path kind :return-text-touched?) true])))))

(defn save-portfolio-optimizer-black-litterman-editor-view
  [state]
  (let [kind (common/selected-kind state)
        editing-view-id (common/non-blank-text
                         (get-in state (conj common/editor-path :editing-view-id)))
        editing? (boolean editing-view-id)
        draft (with-automatic-absolute-return-text
                state
                kind
                (common/editor-draft state kind)
                editing?)
        errors (validate-draft state kind draft editing?)
        views* (common/black-litterman-views state)]
    (if (seq errors)
      (common/save-ui-path-values
       (into [[(conj common/editor-path :errors) errors]]
             (map (fn [[field message]]
                    [(conj common/editor-path :errors field) message])
                  errors)))
      (let [view-id (or editing-view-id (common/next-view-id views*))
            view (draft->view kind draft view-id)
            saved-views (if editing?
                          (mapv (fn [existing]
                                  (if (= view-id (:id existing))
                                    view
                                    existing))
                                views*)
                          (conj views* view))]
        (common/save-draft-path-values
         [[[:portfolio :optimizer :draft :return-model :views] saved-views]
          [(conj common/editor-path :drafts kind) (reset-draft-after-save draft)]
          [(conj common/editor-path :editing-view-id) nil]
          [(conj common/editor-path :errors) {}]
          [(conj common/editor-path :clear-confirmation-open?) false]])))))

(defn edit-portfolio-optimizer-black-litterman-view
  [state view-id]
  (let [view-id* (common/non-blank-text view-id)
        view (common/view-by-id (common/black-litterman-views state) view-id*)]
    (if view
      (let [kind (or (:kind view) :absolute)]
        (common/save-ui-path-values [[(conj common/editor-path :selected-kind) kind]
                                     [(conj common/editor-path :drafts kind) (view->draft view)]
                                     [(conj common/editor-path :editing-view-id) view-id*]
                                     [(conj common/editor-path :errors) {}]]))
      [])))

(defn cancel-portfolio-optimizer-black-litterman-edit
  [state]
  (let [kind (common/selected-kind state)]
    (common/save-ui-path-values [[(conj common/editor-path :editing-view-id) nil]
                                 [(conj common/editor-path :drafts kind)
                                  (reset-draft-after-save (common/editor-draft state kind))]
                                 [(conj common/editor-path :errors) {}]])))

(defn request-clear-portfolio-optimizer-black-litterman-views
  [_state]
  (common/save-ui-path-values [[(conj common/editor-path :clear-confirmation-open?) true]]))

(defn cancel-clear-portfolio-optimizer-black-litterman-views
  [_state]
  (common/save-ui-path-values [[(conj common/editor-path :clear-confirmation-open?) false]]))

(defn confirm-clear-portfolio-optimizer-black-litterman-views
  [state]
  (if (and (common/black-litterman-return-model? state)
           (seq (common/black-litterman-views state)))
    (common/save-draft-path-values
     [[[:portfolio :optimizer :draft :return-model :views] []]
      [(conj common/editor-path :clear-confirmation-open?) false]
      [(conj common/editor-path :editing-view-id) nil]
      [(conj common/editor-path :errors) {}]])
    (common/save-ui-path-values [[(conj common/editor-path :clear-confirmation-open?) false]
                                 [(conj common/editor-path :editing-view-id) nil]])))
