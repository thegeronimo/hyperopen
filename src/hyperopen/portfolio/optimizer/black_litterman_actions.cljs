(ns hyperopen.portfolio.optimizer.black-litterman-actions
  (:require [clojure.string :as str]))

(def ^:private view-kinds
  #{:absolute
    :relative})

(def ^:private numeric-parameter-keys
  #{:return
    :confidence})

(def ^:private instrument-parameter-keys
  #{:instrument-id
    :long-instrument-id
    :short-instrument-id})

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

(defn- parse-number-value
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [text (str/trim value)]
      (when (seq text)
        (let [parsed (js/Number text)]
          (when (and (number? parsed)
                     (js/isFinite parsed))
            parsed))))

    :else nil))

(defn- save-draft-path-values
  [path-values]
  [[:effects/save-many
    (conj (vec path-values)
          [[:portfolio :optimizer :draft :metadata :dirty?] true])]])

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
  (let [confidence* (-> confidence
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
         :confidence confidence
         :confidence-variance (confidence-variance confidence)
         :weights {instrument-id 1}})

      :relative
      (when (<= 2 (count ids))
        (let [[long-id short-id] ids]
          {:id id
           :kind :relative
           :long-instrument-id long-id
           :short-instrument-id short-id
           :return 0.0
           :confidence confidence
           :confidence-variance (confidence-variance confidence)
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
    (let [long-id (non-blank-text (:long-instrument-id view))
          short-id (non-blank-text (:short-instrument-id view))]
      (if (and long-id short-id (not= long-id short-id))
        (assoc view :weights {long-id 1
                              short-id -1})
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
                                view)]
               (-> new-view
                   (assoc :return (or (:return view) 0.0)
                          :confidence (or (:confidence view) 0.5)
                          :confidence-variance
                          (confidence-variance (or (:confidence view) 0.5)))
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

(defn remove-portfolio-optimizer-black-litterman-view
  [state view-id]
  (let [view-id* (non-blank-text view-id)
        views (black-litterman-views state)
        views* (vec (remove #(= view-id* (:id %)) views))]
    (if (and (black-litterman-return-model? state)
             view-id*
             (not= views views*))
      (save-views views*)
      [])))
