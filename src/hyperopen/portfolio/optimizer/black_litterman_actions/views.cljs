(ns hyperopen.portfolio.optimizer.black-litterman-actions.views
  (:require [hyperopen.portfolio.optimizer.black-litterman-actions.common :as common]))

(defn view-primary-instrument-id
  [view]
  (or (common/non-blank-text (:instrument-id view))
      (common/non-blank-text (:long-instrument-id view))))

(defn view-comparator-instrument-id
  [view]
  (or (common/non-blank-text (:comparator-instrument-id view))
      (common/non-blank-text (:short-instrument-id view))))

(defn- view-direction
  [view]
  (or (:direction view)
      (when (= :relative (:kind view))
        :outperform)))

(defn view-instrument-ids
  [view]
  (case (:kind view)
    :absolute (vec (keep common/non-blank-text [(:instrument-id view)]))
    :relative (vec (keep common/non-blank-text [(view-primary-instrument-id view)
                                                (view-comparator-instrument-id view)]))
    []))

(defn- default-view
  [state kind id]
  (let [ids (common/universe-instrument-ids state)
        kind* (common/normalize-keyword-like kind)
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
         :confidence-variance (common/confidence-variance confidence)
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
           :confidence-variance (common/confidence-variance confidence)
           :horizon :3m
           :weights {long-id 1
                     short-id -1}}))

      nil)))

(defn- rebuild-weights
  [view]
  (case (:kind view)
    :absolute
    (if-let [instrument-id (common/non-blank-text (:instrument-id view))]
      (assoc view :weights {instrument-id 1})
      view)

    :relative
    (let [instrument-id (view-primary-instrument-id view)
          comparator-id (view-comparator-instrument-id view)
          direction (common/normalize-direction (view-direction view))]
      (if (and instrument-id comparator-id (not= instrument-id comparator-id))
        (assoc view
               :instrument-id instrument-id
               :comparator-instrument-id comparator-id
               :direction direction
               :weights (common/relative-weights instrument-id comparator-id direction))
        view))

    view))

(defn- replace-view
  [views view-id f]
  (let [view-id* (common/non-blank-text view-id)
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
  (common/save-draft-path-values
   [[[:portfolio :optimizer :draft :return-model :views] (vec views)]]))

(defn add-portfolio-optimizer-black-litterman-view
  [state view-kind]
  (let [view-kind* (common/normalize-keyword-like view-kind)
        views (common/black-litterman-views state)]
    (if (and (common/black-litterman-return-model? state)
             (contains? common/view-kinds view-kind*))
      (if-let [view (default-view state view-kind* (common/next-view-id views))]
        (save-views (conj views view))
        [])
      [])))

(defn set-portfolio-optimizer-black-litterman-view-parameter
  [state view-id parameter-key value]
  (let [parameter-key* (common/normalize-keyword-like parameter-key)
        views (common/black-litterman-views state)
        replace-one (fn [f]
                      (if-let [views* (replace-view views view-id f)]
                        (save-views views*)
                        []))]
    (cond
      (not (common/black-litterman-return-model? state))
      []

      (= :kind parameter-key*)
      (let [kind* (common/normalize-keyword-like value)]
        (if (contains? common/view-kinds kind*)
          (replace-one
           (fn [view]
             (let [new-view (or (default-view state kind* (:id view))
                                view)
                   confidence (or (:confidence view) 0.5)]
               (-> new-view
                   (assoc :return (or (:return view) 0.0)
                          :confidence-level (or (:confidence-level view) :medium)
                          :confidence confidence
                          :confidence-variance (common/confidence-variance confidence)
                          :horizon (or (:horizon view) :3m))
                   rebuild-weights))))
          []))

      (contains? common/numeric-parameter-keys parameter-key*)
      (let [value* (common/parse-number-value value)]
        (if (some? value*)
          (replace-one
           (fn [view]
             (cond-> (assoc view parameter-key* value*)
               (= :confidence parameter-key*)
               (assoc :confidence-variance (common/confidence-variance value*)))))
          []))

      (contains? common/instrument-parameter-keys parameter-key*)
      (let [instrument-id* (common/non-blank-text value)]
        (if (and (common/valid-instrument-id? state instrument-id*)
                 (valid-instrument-update?
                  (common/view-by-id views (common/non-blank-text view-id))
                  parameter-key*
                  instrument-id*))
          (replace-one
           (fn [view]
             (rebuild-weights
              (assoc view parameter-key* instrument-id*))))
          []))

      :else [])))

(defn remove-portfolio-optimizer-black-litterman-view
  [state view-id]
  (let [view-id* (common/non-blank-text view-id)
        views (common/black-litterman-views state)
        views* (vec (remove #(= view-id* (:id %)) views))]
    (if (and (common/black-litterman-return-model? state)
             view-id*
             (not= views views*))
      (common/save-draft-path-values
       [[[:portfolio :optimizer :draft :return-model :views] views*]
        [(conj common/editor-path :editing-view-id)
         (when (not= view-id*
                     (get-in state (conj common/editor-path :editing-view-id)))
           (get-in state (conj common/editor-path :editing-view-id)))]])
      [])))
