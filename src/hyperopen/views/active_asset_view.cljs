(ns hyperopen.views.active-asset-view
  (:require [hyperopen.views.active-asset.row :as active-asset-row]
            [hyperopen.views.active-asset.vm :as active-asset-vm]
            [hyperopen.views.asset-selector-view :as asset-selector]))

;; Public trade-page entrypoint for the active asset strip.
;; Rendering consumes the normalized panel VM from `hyperopen.views.active-asset.vm`;
;; funding policy and icon probe side effects live in dedicated modules.

(defn active-asset-panel [state]
  (let [{:keys [active-asset row-vm dropdown-state asset-selector-props]}
        (active-asset-vm/active-asset-panel-vm state)]
    [:div {:class ["relative" "bg-base-200" "border-b" "border-base-300" "rounded-none" "spectate-none"]
           :data-parity-id "market-strip"}
     [:div
      (if active-asset
        (active-asset-row/active-asset-row-from-vm row-vm)
        (active-asset-row/select-asset-row dropdown-state))]
     (when asset-selector-props
       (asset-selector/asset-selector-wrapper asset-selector-props))]))

(defn active-asset-view [state]
  (active-asset-panel state))
