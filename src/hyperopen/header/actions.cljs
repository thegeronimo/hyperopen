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
  [state key]
  (if (= "Escape" key)
    (if (get-in state [:header-ui :settings-confirmation])
      [[:effects/save [:header-ui :settings-confirmation] nil]]
      (close-header-settings state))
    []))

(defn- normalize-storage-mode-request
  [storage-mode]
  (cond
    (boolean? storage-mode) (if storage-mode :local :session)
    :else (agent-session/normalize-storage-mode storage-mode)))

(defn- normalize-local-protection-mode-request
  [local-protection-mode]
  (cond
    (boolean? local-protection-mode) (if local-protection-mode :passkey :plain)
    :else (agent-session/normalize-local-protection-mode local-protection-mode)))

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

(defn request-agent-local-protection-mode-change
  [state local-protection-mode]
  (let [next-mode (normalize-local-protection-mode-request local-protection-mode)
        current-mode (agent-session/normalize-local-protection-mode
                      (get-in state [:wallet :agent :local-protection-mode]))
        storage-mode (agent-session/normalize-storage-mode
                      (get-in state [:wallet :agent :storage-mode]))
        agent-status (get-in state [:wallet :agent :status])]
    (if (or (= :session storage-mode)
            (= next-mode current-mode)
            (and (= :passkey current-mode)
                 (= :plain next-mode)
                 (#{:locked :unlocking} agent-status)))
      []
      [[:effects/set-agent-local-protection-mode next-mode]])))

(defn cancel-agent-local-protection-mode-change
  [_state]
  [[:effects/save [:header-ui :settings-confirmation] nil]])

(defn confirm-agent-local-protection-mode-change
  [state]
  (let [next-mode (some-> (get-in state [:header-ui :settings-confirmation :next-mode])
                          agent-session/normalize-local-protection-mode)
        current-mode (agent-session/normalize-local-protection-mode
                      (get-in state [:wallet :agent :local-protection-mode]))]
    (if (or (nil? next-mode)
            (= next-mode current-mode))
      [[:effects/save [:header-ui :settings-confirmation] nil]]
      [[:effects/save [:header-ui :settings-confirmation] nil]
       [:effects/save [:header-ui :settings-open?] false]
       [:effects/save [:header-ui :settings-return-focus?] true]
       [:effects/set-agent-local-protection-mode next-mode]])))

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

(defn set-confirm-open-orders-enabled
  [state enabled?]
  (persist-trading-settings state {:confirm-open-orders? (boolean enabled?)}))

(defn set-confirm-close-position-enabled
  [state enabled?]
  (persist-trading-settings state {:confirm-close-position? (boolean enabled?)}))

(defn navigate-mobile-header-menu
  [state path]
  (into [[:effects/save [:header-ui :mobile-menu-open?] false]]
        (action-adapters/navigate state path)))

(defn open-spectate-mode-mobile-header-menu
  [state & [trigger-bounds]]
  (into [[:effects/save [:header-ui :mobile-menu-open?] false]]
        (spectate-mode-actions/open-spectate-mode-modal state trigger-bounds)))
