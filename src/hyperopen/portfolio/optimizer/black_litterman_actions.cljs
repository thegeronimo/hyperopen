(ns hyperopen.portfolio.optimizer.black-litterman-actions
  (:require [hyperopen.portfolio.optimizer.black-litterman-actions.editor :as editor]
            [hyperopen.portfolio.optimizer.black-litterman-actions.views :as views]))

(def view-primary-instrument-id
  views/view-primary-instrument-id)

(def view-comparator-instrument-id
  views/view-comparator-instrument-id)

(def view-instrument-ids
  views/view-instrument-ids)

(def add-portfolio-optimizer-black-litterman-view
  views/add-portfolio-optimizer-black-litterman-view)

(def set-portfolio-optimizer-black-litterman-view-parameter
  views/set-portfolio-optimizer-black-litterman-view-parameter)

(def remove-portfolio-optimizer-black-litterman-view
  views/remove-portfolio-optimizer-black-litterman-view)

(def set-portfolio-optimizer-black-litterman-editor-type
  editor/set-portfolio-optimizer-black-litterman-editor-type)

(def set-portfolio-optimizer-black-litterman-editor-field
  editor/set-portfolio-optimizer-black-litterman-editor-field)

(def save-portfolio-optimizer-black-litterman-editor-view
  editor/save-portfolio-optimizer-black-litterman-editor-view)

(def edit-portfolio-optimizer-black-litterman-view
  editor/edit-portfolio-optimizer-black-litterman-view)

(def cancel-portfolio-optimizer-black-litterman-edit
  editor/cancel-portfolio-optimizer-black-litterman-edit)

(def request-clear-portfolio-optimizer-black-litterman-views
  editor/request-clear-portfolio-optimizer-black-litterman-views)

(def cancel-clear-portfolio-optimizer-black-litterman-views
  editor/cancel-clear-portfolio-optimizer-black-litterman-views)

(def confirm-clear-portfolio-optimizer-black-litterman-views
  editor/confirm-clear-portfolio-optimizer-black-litterman-views)
