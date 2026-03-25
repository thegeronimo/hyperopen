(ns hyperopen.views.asset-selector-view
  (:require [hyperopen.views.asset-selector.controls :as controls]
            [hyperopen.views.asset-selector.icons :as icons]
            [hyperopen.views.asset-selector.layout :as layout]
            [hyperopen.views.asset-selector.processing :as processing]
            [hyperopen.views.asset-selector.rows :as rows]
            [hyperopen.views.asset-selector.runtime :as runtime]))

(def tooltip controls/tooltip)
(def search-controls controls/search-controls)
(def favorite-button icons/favorite-button)
(def asset-list-item rows/asset-list-item)
(def asset-list runtime/asset-list)
(def matches-search? processing/matches-search?)
(def tab-match? processing/tab-match?)
(def filter-and-sort-assets processing/filter-and-sort-assets)
(def reset-processed-assets-cache! processing/reset-processed-assets-cache!)
(def processed-assets processing/processed-assets)
(def asset-list-scroll-active? runtime/asset-list-scroll-active?)
(def asset-list-freeze-active? runtime/asset-list-freeze-active?)

(def ^:private mobile-favorite-button icons/mobile-favorite-button)
(def ^:private asset-list-body runtime/asset-list-body)
(def ^:private asset-list-window-state runtime/asset-list-window-state)
(def ^:private asset-list-viewport-covered? runtime/asset-list-viewport-covered?)
(def ^:private asset-list-window-covered? runtime/asset-list-window-covered?)
(def ^:private schedule-asset-list-render-limit-sync! runtime/schedule-asset-list-render-limit-sync!)
(def ^:private asset-list-now-ms runtime/asset-list-now-ms)
(def ^:private asset-list-set-timeout! runtime/asset-list-set-timeout!)
(def ^:private asset-list-clear-timeout! runtime/asset-list-clear-timeout!)

(defn asset-selector-dropdown
  [props]
  (layout/asset-selector-dropdown props))

(defn asset-selector-wrapper
  [props]
  (layout/asset-selector-wrapper props))
