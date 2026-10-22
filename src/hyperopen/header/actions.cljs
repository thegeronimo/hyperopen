(ns hyperopen.header.actions
  (:require [hyperopen.account.spectate-mode-actions :as spectate-mode-actions]
            [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.ui.theme :as ui-theme]
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

(defn set-sound-on-fill-enabled
  [state enabled?]
  (persist-trading-settings state {:sound-on-fill? (boolean enabled?)}))

(defn set-animate-orderbook-enabled
  [state enabled?]
  (persist-trading-settings state {:animate-orderbook? (boolean enabled?)}))

(defn set-chart-trades-enabled
  [state enabled?]
  (persist-trading-settings state {:show-chart-trades? (boolean enabled?)}))

(defn set-confirm-open-orders-enabled
  [state enabled?]
  (persist-trading-settings state {:confirm-open-orders? (boolean enabled?)}))

(defn set-confirm-close-position-enabled
  [state enabled?]
  (persist-trading-settings state {:confirm-close-position? (boolean enabled?)}))

(defn set-confirm-market-orders-enabled
  [state enabled?]
  (persist-trading-settings state {:confirm-market-orders? (boolean enabled?)}))

(defn set-open-order-safety-mode
  [state mode]
  (persist-trading-settings
   state
   {:open-order-safety-mode
    (trading-settings/normalize-open-order-safety-mode mode)}))

(defn set-ui-theme
  [state theme-id]
  (let [normalized (ui-theme/normalize-theme-id theme-id)]
    (if (= normalized (ui-theme/active-theme-id state))
      []
      [[:effects/save [:ui :theme] normalized]
       [:effects/local-storage-set ui-theme/local-storage-key normalized]
       [:effects/apply-ui-theme normalized]])))

(defn reset-degen-life
  "Joke control on the degen Feeling Gauge widget. Bumps a session-local
   counter; the gauge reports lives used and the degen tip re-rolls."
  [state]
  [[:effects/save [:degen :life-resets]
    (inc (get-in state [:degen :life-resets] 0))]])

(defn navigate-mobile-header-menu
  [state path]
  (into [[:effects/save [:header-ui :mobile-menu-open?] false]]
        (action-adapters/navigate state path)))

(defn open-spectate-mode-mobile-header-menu
  [state & [trigger-bounds]]
  (into [[:effects/save [:header-ui :mobile-menu-open?] false]]
        (spectate-mode-actions/open-spectate-mode-modal state trigger-bounds)))
