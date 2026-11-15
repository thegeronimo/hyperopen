(ns hyperopen.portfolio.optimizer.black-litterman-actions.editor-model
  (:require [hyperopen.portfolio.optimizer.application.black-litterman-editor-model :as model]
            [hyperopen.portfolio.optimizer.application.return-inputs :as return-inputs]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.black-litterman-actions.common :as common]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(defn- automatic-return-inputs
  [state]
  (return-inputs/readiness-inputs-by-instrument
   (setup-readiness/build-readiness state)))

(defn reset-draft-after-save
  [draft]
  (model/reset-draft-after-save draft))

(defn view->draft
  [view]
  (model/view->draft view))

(defn editor-view-result
  [state]
  (model/editor-view-result
   {:black-litterman? (common/black-litterman-return-model? state)
    :universe (common/draft-universe state)
    :views (common/black-litterman-views state)
    :editor-state (get-in state common/editor-path)
    :return-inputs-by-instrument (automatic-return-inputs state)}))

(defn pending-editor-view-result
  [state]
  (model/pending-editor-view-result
   {:black-litterman? (common/black-litterman-return-model? state)
    :universe (common/draft-universe state)
    :views (common/black-litterman-views state)
    :editor-state (get-in state common/editor-path)
    :return-inputs-by-instrument (automatic-return-inputs state)}))

(defn materialized-view-path-values
  [{:keys [kind draft views]}]
  [[contracts/draft-return-model-views-path views]
   [(conj common/editor-path :drafts kind) (reset-draft-after-save draft)]
   [(conj common/editor-path :editing-view-id) nil]
   [(conj common/editor-path :errors) {}]
   [(conj common/editor-path :clear-confirmation-open?) false]])

(defn error-path-values
  [errors]
  (into [[(conj common/editor-path :errors) errors]]
        (map (fn [[field message]]
               [(conj common/editor-path :errors field) message])
             errors)))
