(ns hyperopen.runtime.collaborators.spectate-mode
  (:require [hyperopen.account.spectate-mode-actions :as spectate-mode-actions]
            [hyperopen.header.actions :as header-actions]))

(defn action-deps []
  {:open-mobile-header-menu header-actions/open-mobile-header-menu
   :close-mobile-header-menu header-actions/close-mobile-header-menu
   :open-header-settings header-actions/open-header-settings
   :close-header-settings header-actions/close-header-settings
   :request-agent-storage-mode-change header-actions/request-agent-storage-mode-change
   :cancel-agent-storage-mode-change header-actions/cancel-agent-storage-mode-change
   :confirm-agent-storage-mode-change header-actions/confirm-agent-storage-mode-change
   :request-agent-local-protection-mode-change
   header-actions/request-agent-local-protection-mode-change
   :cancel-agent-local-protection-mode-change
   header-actions/cancel-agent-local-protection-mode-change
   :confirm-agent-local-protection-mode-change
   header-actions/confirm-agent-local-protection-mode-change
   :set-fill-alerts-enabled header-actions/set-fill-alerts-enabled
   :set-sound-on-fill-enabled header-actions/set-sound-on-fill-enabled
   :set-animate-orderbook-enabled header-actions/set-animate-orderbook-enabled
   :set-fill-markers-enabled header-actions/set-fill-markers-enabled
   :set-confirm-open-orders-enabled header-actions/set-confirm-open-orders-enabled
   :set-confirm-close-position-enabled header-actions/set-confirm-close-position-enabled
   :set-confirm-market-orders-enabled header-actions/set-confirm-market-orders-enabled
   :navigate-mobile-header-menu header-actions/navigate-mobile-header-menu
   :open-spectate-mode-mobile-header-menu header-actions/open-spectate-mode-mobile-header-menu
   :open-spectate-mode-modal spectate-mode-actions/open-spectate-mode-modal
   :close-spectate-mode-modal spectate-mode-actions/close-spectate-mode-modal
   :set-spectate-mode-search spectate-mode-actions/set-spectate-mode-search
   :set-spectate-mode-label spectate-mode-actions/set-spectate-mode-label
   :start-spectate-mode spectate-mode-actions/start-spectate-mode
   :stop-spectate-mode spectate-mode-actions/stop-spectate-mode
   :add-spectate-mode-watchlist-address spectate-mode-actions/add-spectate-mode-watchlist-address
   :remove-spectate-mode-watchlist-address
   spectate-mode-actions/remove-spectate-mode-watchlist-address
   :edit-spectate-mode-watchlist-address spectate-mode-actions/edit-spectate-mode-watchlist-address
   :clear-spectate-mode-watchlist-edit spectate-mode-actions/clear-spectate-mode-watchlist-edit
   :copy-spectate-mode-watchlist-address spectate-mode-actions/copy-spectate-mode-watchlist-address
   :copy-spectate-mode-watchlist-link spectate-mode-actions/copy-spectate-mode-watchlist-link
   :start-spectate-mode-watchlist-address
   spectate-mode-actions/start-spectate-mode-watchlist-address})
