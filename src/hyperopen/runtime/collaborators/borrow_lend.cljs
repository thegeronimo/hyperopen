(ns hyperopen.runtime.collaborators.borrow-lend
  (:require [hyperopen.borrow-lend.actions :as borrow-lend-actions]))

(defn action-deps []
  {:load-borrow-lend-route borrow-lend-actions/load-borrow-lend-route
   :load-borrow-lend borrow-lend-actions/load-borrow-lend
   :set-borrow-lend-tab borrow-lend-actions/set-borrow-lend-tab
   :open-borrow-lend-modal borrow-lend-actions/open-borrow-lend-modal
   :close-borrow-lend-modal borrow-lend-actions/close-borrow-lend-modal
   :set-borrow-lend-modal-kind borrow-lend-actions/set-borrow-lend-modal-kind
   :set-borrow-lend-amount borrow-lend-actions/set-borrow-lend-amount
   :set-borrow-lend-amount-fraction borrow-lend-actions/set-borrow-lend-amount-fraction
   :submit-borrow-lend borrow-lend-actions/submit-borrow-lend})
