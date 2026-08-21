(ns hyperopen.views.pnl-share-modal-module
  (:require [hyperopen.views.pnl-share.modal :as pnl-share-modal]))

(defn ^:export pnl-share-modal-view
  [state]
  (pnl-share-modal/pnl-share-modal-view state))

(goog/exportSymbol "hyperopen.views.pnl_share_modal_module.pnl_share_modal_view" pnl-share-modal-view)
