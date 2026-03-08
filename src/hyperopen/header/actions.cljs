(ns hyperopen.header.actions
  (:require [hyperopen.account.spectate-mode-actions :as spectate-mode-actions]
            [hyperopen.runtime.action-adapters :as action-adapters]))

(defn open-mobile-header-menu
  [_state]
  [[:effects/save [:header-ui :mobile-menu-open?] true]])

(defn close-mobile-header-menu
  [_state]
  [[:effects/save [:header-ui :mobile-menu-open?] false]])

(defn navigate-mobile-header-menu
  [state path]
  (into [[:effects/save [:header-ui :mobile-menu-open?] false]]
        (action-adapters/navigate state path)))

(defn open-spectate-mode-mobile-header-menu
  [state & [trigger-bounds]]
  (into [[:effects/save [:header-ui :mobile-menu-open?] false]]
        (spectate-mode-actions/open-spectate-mode-modal state trigger-bounds)))
