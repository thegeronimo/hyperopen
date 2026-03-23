(ns hyperopen.views.active-asset-view
  (:require [hyperopen.views.active-asset.row :as active-asset-row]
            [hyperopen.views.active-asset.vm :as active-asset-vm]
            [hyperopen.views.asset-selector-view :as asset-selector]))

;; Public trade-page entrypoint for the active asset strip.
;; Rendering consumes the normalized panel VM from `hyperopen.views.active-asset.vm`;
;; funding policy and icon probe side effects live in dedicated modules.

(defonce ^:private frozen-asset-selector-props* (atom nil))

(defn reset-asset-selector-scroll-snapshot! []
  (reset! frozen-asset-selector-props* nil))

(defn- stable-asset-selector-props
  [props]
  (cond
    (nil? props)
    (do
      (reset-asset-selector-scroll-snapshot!)
      nil)

    (asset-selector/asset-list-scroll-active?)
    (let [snapshot @frozen-asset-selector-props*]
      (if (some? snapshot)
        snapshot
        (do
          (reset! frozen-asset-selector-props* props)
          props)))

    :else
    (do
      (reset! frozen-asset-selector-props* props)
      props)))

(defn active-asset-panel [state]
  (let [{:keys [active-asset row-vm dropdown-state asset-selector-props]}
        (active-asset-vm/active-asset-panel-vm state)
        funding-tooltip-open? (true? (:funding-tooltip-open? row-vm))
        asset-selector-props* (stable-asset-selector-props asset-selector-props)]
    [:div {:class (into ["relative" "bg-base-200" "border-b" "border-base-300" "rounded-none" "spectate-none"]
                        (when funding-tooltip-open?
                          ["z-[160]"]))
           :data-parity-id "market-strip"}
     [:div
      (if active-asset
        (active-asset-row/active-asset-row-from-vm row-vm)
        (active-asset-row/select-asset-row dropdown-state))]
     (when asset-selector-props*
       (asset-selector/asset-selector-wrapper asset-selector-props*))]))

(defn active-asset-view [state]
  (active-asset-panel state))
