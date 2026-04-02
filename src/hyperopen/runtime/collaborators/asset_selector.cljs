(ns hyperopen.runtime.collaborators.asset-selector
  (:require [hyperopen.asset-selector.actions :as asset-actions]))

(defn action-deps []
  {:toggle-asset-dropdown asset-actions/toggle-asset-dropdown
   :close-asset-dropdown asset-actions/close-asset-dropdown
   :select-asset asset-actions/select-asset
   :update-asset-search asset-actions/update-asset-search
   :update-asset-selector-sort asset-actions/update-asset-selector-sort
   :toggle-asset-selector-strict asset-actions/toggle-asset-selector-strict
   :toggle-asset-favorite asset-actions/toggle-asset-favorite
   :set-asset-selector-favorites-only asset-actions/set-asset-selector-favorites-only
   :set-asset-selector-tab asset-actions/set-asset-selector-tab
   :handle-asset-selector-shortcut asset-actions/handle-asset-selector-shortcut
   :set-asset-selector-live-market-subscriptions-paused
   asset-actions/set-asset-selector-live-market-subscriptions-paused
   :set-asset-selector-scroll-top asset-actions/set-asset-selector-scroll-top
   :increase-asset-selector-render-limit asset-actions/increase-asset-selector-render-limit
   :show-all-asset-selector-markets asset-actions/show-all-asset-selector-markets
   :maybe-increase-asset-selector-render-limit asset-actions/maybe-increase-asset-selector-render-limit
   :mark-loaded-asset-icon asset-actions/mark-loaded-asset-icon
   :mark-missing-asset-icon asset-actions/mark-missing-asset-icon
   :set-funding-tooltip-visible asset-actions/set-funding-tooltip-visible
   :set-funding-tooltip-pinned asset-actions/set-funding-tooltip-pinned
   :enter-funding-hypothetical-position asset-actions/enter-funding-hypothetical-position
   :reset-funding-hypothetical-position asset-actions/reset-funding-hypothetical-position
   :set-funding-hypothetical-size asset-actions/set-funding-hypothetical-size
   :set-funding-hypothetical-value asset-actions/set-funding-hypothetical-value})
