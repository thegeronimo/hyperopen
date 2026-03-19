(ns hyperopen.header.actions
  (:require [hyperopen.account.spectate-mode-actions :as spectate-mode-actions]
            [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.wallet.agent-session :as agent-session]))

(defn open-mobile-header-menu
  [_state]
  [[:effects/save [:header-ui :mobile-menu-open?] true]])

(defn close-mobile-header-menu
  [_state]
  [[:effects/save [:header-ui :mobile-menu-open?] false]])

(defn open-header-settings
  [_state]
  [[:effects/save [:header-ui :settings-return-focus?] false]
   [:effects/save [:header-ui :settings-open?] true]])

(defn close-header-settings
  [_state]
  [[:effects/save [:header-ui :settings-confirmation] nil]
   [:effects/save [:header-ui :settings-open?] false]
   [:effects/save [:header-ui :settings-return-focus?] true]])

(defn handle-header-settings-keydown
  [_state key]
  (if (= "Escape" key)
    (close-header-settings nil)
    []))

(defn- normalize-storage-mode-request
  [storage-mode]
  (cond
    (boolean? storage-mode) (if storage-mode :local :session)
    :else (agent-session/normalize-storage-mode storage-mode)))

(defn request-agent-storage-mode-change
  [state storage-mode]
  (let [next-mode (normalize-storage-mode-request storage-mode)
        current-mode (agent-session/normalize-storage-mode
                      (get-in state [:wallet :agent :storage-mode]))]
    (if (= next-mode current-mode)
      []
      [[:effects/save [:header-ui :settings-confirmation]
        {:kind :agent-storage-mode
         :next-mode next-mode}]])))

(defn cancel-agent-storage-mode-change
  [_state]
  [[:effects/save [:header-ui :settings-confirmation] nil]])

(defn confirm-agent-storage-mode-change
  [state]
  (let [next-mode (some-> (get-in state [:header-ui :settings-confirmation :next-mode])
                          agent-session/normalize-storage-mode)
        current-mode (agent-session/normalize-storage-mode
                      (get-in state [:wallet :agent :storage-mode]))]
    (if (or (nil? next-mode)
            (= next-mode current-mode))
      [[:effects/save [:header-ui :settings-confirmation] nil]]
      [[:effects/save [:header-ui :settings-confirmation] nil]
       [:effects/save [:header-ui :settings-open?] false]
       [:effects/save [:header-ui :settings-return-focus?] true]
       [:effects/set-agent-storage-mode next-mode]])))

(defn- persist-trading-settings
  [state updates]
  (let [next-state (trading-settings/normalize-state
                    (merge (or (:trading-settings state)
                               trading-settings/default-state)
                           updates))]
    [[:effects/save [:trading-settings] next-state]
     [:effects/local-storage-set-json trading-settings/storage-key next-state]]))

(defn set-fill-alerts-enabled
  [state enabled?]
  (persist-trading-settings state {:fill-alerts-enabled? (boolean enabled?)}))

(defn set-animate-orderbook-enabled
  [state enabled?]
  (persist-trading-settings state {:animate-orderbook? (boolean enabled?)}))

(defn set-fill-markers-enabled
  [state enabled?]
  (persist-trading-settings state {:show-fill-markers? (boolean enabled?)}))

(defn navigate-mobile-header-menu
  [state path]
  (into [[:effects/save [:header-ui :mobile-menu-open?] false]]
        (action-adapters/navigate state path)))

(defn open-spectate-mode-mobile-header-menu
  [state & [trigger-bounds]]
  (into [[:effects/save [:header-ui :mobile-menu-open?] false]]
        (spectate-mode-actions/open-spectate-mode-modal state trigger-bounds)))
