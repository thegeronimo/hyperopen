(ns hyperopen.portfolio.optimizer.black-litterman-actions.common
  (:require [hyperopen.portfolio.optimizer.application.black-litterman-editor-model :as editor-model]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def view-kinds editor-model/view-kinds)

(def editor-path
  contracts/ui-black-litterman-editor-path)

(def numeric-parameter-keys editor-model/numeric-parameter-keys)

(def instrument-parameter-keys editor-model/instrument-parameter-keys)

(def normalize-keyword-like coercion/normalize-keyword-like)

(def non-blank-text coercion/non-blank-text)

(def parse-number-value editor-model/parse-number-value)

(defn save-draft-path-values
  [path-values]
  [[:effects/save-many
    (conj (vec path-values)
          [contracts/draft-dirty-path true])]])

(defn save-ui-path-values
  [path-values]
  [[:effects/save-many (vec path-values)]])

(defn draft-universe
  [state]
  (vec (or (get-in state contracts/draft-universe-path)
           [])))

(defn black-litterman-return-model?
  [state]
  (= :black-litterman
     (get-in state (conj contracts/draft-return-model-path :kind))))

(defn black-litterman-views
  [state]
  (vec (or (get-in state contracts/draft-return-model-views-path)
           [])))

(defn instrument-present?
  [universe instrument-id]
  (boolean
   (some #(= instrument-id (:instrument-id %)) universe)))

(defn valid-instrument-id?
  [state instrument-id]
  (and (non-blank-text instrument-id)
       (instrument-present? (draft-universe state) instrument-id)))

(defn confidence-variance
  [confidence]
  (editor-model/confidence-variance confidence))

(defn next-view-id
  [views]
  (editor-model/next-view-id views))

(defn selected-kind
  [state]
  (editor-model/selected-kind (get-in state editor-path)))

(defn editor-draft
  [state kind]
  (editor-model/editor-draft
   (draft-universe state)
   (get-in state editor-path)
   kind))

(defn editor-draft-path
  [kind field]
  (conj editor-path :drafts kind field))

(defn normalized-draft-field
  [field value]
  (editor-model/normalized-draft-field field value))

(defn view-by-id
  [views view-id]
  (editor-model/view-by-id views view-id))
