(ns hyperopen.portfolio.optimizer.application.black-litterman-editor-model
  (:require [hyperopen.portfolio.optimizer.application.black-litterman-editor-model.rules :as rules]
            [hyperopen.portfolio.optimizer.application.black-litterman-editor-model.views :as views]
            [hyperopen.portfolio.optimizer.application.return-inputs :as return-inputs]))

(def view-kinds rules/view-kinds)
(def numeric-parameter-keys rules/numeric-parameter-keys)
(def instrument-parameter-keys rules/instrument-parameter-keys)
(def confidence-weight-by-level rules/confidence-weight-by-level)
(def confidence-options rules/confidence-options)
(def horizon-options rules/horizon-options)
(def parse-number-value rules/parse-number-value)
(def parse-percent-text rules/parse-percent-text)
(def decimal->percent-text rules/decimal->percent-text)
(def confidence-variance rules/confidence-variance)
(def next-view-id rules/next-view-id)
(def normalize-confidence-level rules/normalize-confidence-level)
(def confidence-weight rules/confidence-weight)
(def confidence-level-from-view rules/confidence-level-from-view)
(def selected-kind rules/selected-kind)
(def default-editor-state rules/default-editor-state)
(def editor-draft rules/editor-draft)
(def normalized-draft-field rules/normalized-draft-field)
(def selected-draft rules/selected-draft)
(def pending-draft? rules/pending-draft?)
(def validate-draft rules/validate-draft)
(def draft-valid? rules/draft-valid?)
(def preview-text rules/preview-text)
(def view-primary-id rules/view-primary-id)
(def view-comparator-id rules/view-comparator-id)
(def view-by-id rules/view-by-id)
(def draft->view views/draft->view)
(def reset-draft-after-save views/reset-draft-after-save)
(def view->draft views/view->draft)
(def default-view views/default-view)
(def rebuild-weights views/rebuild-weights)
(def editing-view-id views/editing-view-id)
(def editor-view-result views/editor-view-result)
(def pending-editor-view-result views/pending-editor-view-result)

(defn editor-view-model
  [draft readiness editor-state]
  (when (= :black-litterman (get-in draft [:return-model :kind]))
    (let [universe (vec (:universe draft))
          active-views (vec (get-in draft [:return-model :views]))
          kind (selected-kind editor-state)
          errors (or (:errors editor-state) {})
          editing-view-id* (editing-view-id editor-state)
          editing? (boolean editing-view-id*)
          return-inputs-by-instrument (return-inputs/readiness-inputs-by-instrument readiness)
          draft* (selected-draft universe
                                 editor-state
                                 kind
                                 return-inputs-by-instrument
                                 editing?)
          validation-errors (validate-draft true universe active-views kind draft* editing?)
          valid? (empty? validation-errors)
          pending? (pending-draft? draft* editing?)]
      {:universe universe
       :views active-views
       :kind kind
       :errors errors
       :validation-errors validation-errors
       :editing? editing?
       :draft draft*
       :valid? valid?
       :pending? pending?
       :clear-open? (true? (:clear-confirmation-open? editor-state))})))
