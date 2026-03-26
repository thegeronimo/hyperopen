(ns hyperopen.views.header.vm
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.account.spectate-mode-links :as spectate-mode-links]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.views.header.nav :as nav]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.core :as wallet]))

(def ^:private spectate-mode-trigger-tooltip-id
  "spectate-mode-open-tooltip")

(def ^:private trading-settings-footer-copy
  "Applies only to this browser on this device.")

(def ^:private trading-settings-close-actions
  [[:actions/close-header-settings]])

(def ^:private trading-settings-keydown-action
  [[:actions/handle-header-settings-keydown [:event/key]]])

(defn- nav-href
  [state route]
  (spectate-mode-links/internal-route-href state route))

(defn- with-desktop-action
  [state item]
  (assoc item
         :href (nav-href state (:route item))
         :action [[:actions/navigate (:route item)]]))

(defn- with-mobile-action
  [item]
  (assoc item :action [[:actions/navigate-mobile-header-menu (:route item)]]))

(defn- with-more-action
  [state item]
  (assoc item
         :href (nav-href state (:route item))
         :action [[:actions/navigate (:route item)]]))

(defn- desktop-nav-vm
  [state route]
  (mapv (partial with-desktop-action state)
        (nav/items-for-placement route :desktop)))

(defn- mobile-nav-vm
  [route]
  {:primary-items (mapv with-mobile-action
                        (nav/items-for-placement route :mobile-primary))
   :secondary-items (mapv with-mobile-action
                          (nav/items-for-placement route :mobile-secondary))})

(defn- more-nav-vm
  [state route]
  (let [items (mapv (partial with-more-action state)
                    (nav/items-for-placement route :more))]
    {:menu-key (str "header-more-menu:" route)
     :active? (boolean (some :active? items))
     :items items}))

(defn- spectate-mode-trigger-tooltip-copy
  [active?]
  (if active?
    "Spectate Mode is active. Click to manage the address you are viewing or stop spectating."
    "Inspect another wallet in read-only mode. Click to open Spectate Mode and choose an address."))

(defn- spectate-vm
  [spectate-active?]
  (let [button-label (if spectate-active?
                       "Manage Spectate Mode"
                       "Open Spectate Mode")]
    {:active? spectate-active?
     :button-label button-label
     :tooltip-id spectate-mode-trigger-tooltip-id
     :tooltip-copy (spectate-mode-trigger-tooltip-copy spectate-active?)
     :trigger-action [[:actions/open-spectate-mode-modal :event.currentTarget/bounds]]
     :mobile-action [[:actions/open-spectate-mode-mobile-header-menu
                      :event.currentTarget/bounds]]
     :mobile-label button-label}))

(defn- enable-trading-vm
  [agent-state]
  (let [status (:status agent-state)
        disabled? (= :approving status)]
    (when (not= :ready status)
      {:label (if disabled? "Awaiting signature..." "Enable Trading")
       :disabled? disabled?
       :action [[:actions/enable-agent-trading]]})))

(defn- wallet-vm
  [state]
  (let [wallet-state (get-in state [:wallet] {})
        connected? (boolean (:connected? wallet-state))
        short-address (wallet/short-addr (:address wallet-state))]
    {:connected? connected?
     :connecting? (boolean (:connecting? wallet-state))
     :trigger-label (or short-address "Connected")
     :menu-address-label (or short-address "Unavailable")
     :copy-feedback (:copy-feedback wallet-state)
     :copy-action [[:actions/copy-wallet-address]]
     :disconnect-action [[:actions/disconnect-wallet]]
     :enable-trading (enable-trading-vm (:agent wallet-state))
     :connect-action [[:actions/connect-wallet]]}))

(defn- trading-settings-storage-mode
  [state]
  (if-some [storage-mode (get-in state [:wallet :agent :storage-mode])]
    (agent-session/normalize-storage-mode storage-mode)
    :session))

(defn- remember-trading-session?
  [state]
  (= :local (trading-settings-storage-mode state)))

(defn- agent-storage-confirmation-copy
  [confirmation]
  (case (some-> (:next-mode confirmation)
                agent-session/normalize-storage-mode)
    :local
    {:title "Remember session on this device?"
     :body "Changes trading persistence on this device and will require Enable Trading again."
     :confirm-label "Change"}

    :session
    {:title "Use session-only trading?"
     :body "Changes trading persistence for this browser session and will require Enable Trading again."
     :confirm-label "Change"}

    nil))

(defn- settings-row
  [id title helper-copy checked? icon-kind on-change]
  {:id id
   :data-role (str "trading-settings-" (name id) "-row")
   :title title
   :helper-copy helper-copy
   :checked? checked?
   :icon-kind icon-kind
   :aria-label title
   :on-change on-change})

(defn- settings-section
  [id title rows]
  {:id id
   :title title
   :data-role (str "trading-settings-" (name id) "-section")
   :rows rows})

(defn- settings-vm
  [state]
  (let [confirmation (agent-storage-confirmation-copy
                      (get-in state [:header-ui :settings-confirmation]))]
    {:open? (true? (get-in state [:header-ui :settings-open?]))
     :return-focus? (true? (get-in state [:header-ui :settings-return-focus?]))
     :trigger-key (str "header-settings-button:"
                       (true? (get-in state [:header-ui :settings-open?]))
                       ":"
                       (true? (get-in state [:header-ui :settings-return-focus?])))
     :trigger-action [[:actions/open-header-settings]]
     :title "Trading settings"
     :close-actions trading-settings-close-actions
     :keydown-action trading-settings-keydown-action
     :footer-note trading-settings-footer-copy
     :sections [(settings-section
                 :session
                 "Session"
                 [(assoc (settings-row :storage-mode
                                       "Remember session"
                                       "Keep trading enabled across browser restarts on this device."
                                       (remember-trading-session? state)
                                       :session
                                       [[:actions/request-agent-storage-mode-change
                                         (not (remember-trading-session? state))]])
                         :confirmation confirmation)])
                (settings-section
                 :confirmations
                 "Confirmations"
                 [(settings-row :confirm-open-orders
                                "Confirm open orders"
                                "Ask before sending a new order from the trade form."
                                (trading-settings/confirm-open-orders? state)
                                :confirm-open-orders
                                [[:actions/set-confirm-open-orders-enabled
                                  (not (trading-settings/confirm-open-orders? state))]])
                  (settings-row :confirm-close-position
                                "Confirm close position"
                                "Ask before submitting from the close-position popover."
                                (trading-settings/confirm-close-position? state)
                                :confirm-close-position
                                [[:actions/set-confirm-close-position-enabled
                                  (not (trading-settings/confirm-close-position? state))]])])
                (settings-section
                 :alerts
                 "Alerts"
                 [(settings-row :fill-alerts
                                "Fill alerts"
                                "Show fill alerts while Hyperopen is open."
                                (trading-settings/fill-alerts-enabled? state)
                                :alerts
                                [[:actions/set-fill-alerts-enabled
                                  (not (trading-settings/fill-alerts-enabled? state))]])])
                (settings-section
                 :display
                 "Display"
                 [(settings-row :animate-orderbook
                                "Animate order book"
                                "Smooth bid and ask depth changes as the book updates."
                                (trading-settings/animate-orderbook? state)
                                :animate-orderbook
                                [[:actions/set-animate-orderbook-enabled
                                  (not (trading-settings/animate-orderbook? state))]])
                  (settings-row :fill-markers
                                "Fill markers"
                                "Show buy and sell markers for the active asset on the chart."
                                (trading-settings/show-fill-markers? state)
                                :fill-markers
                                [[:actions/set-fill-markers-enabled
                                  (not (trading-settings/show-fill-markers? state))]])])]}))

(defn header-vm
  [state]
  (let [route (get-in state [:router :path] "/trade")
        spectate-active? (account-context/spectate-mode-active? state)]
    {:route route
     :mobile-menu-open? (true? (get-in state [:header-ui :mobile-menu-open?]))
     :desktop-nav-items (desktop-nav-vm state route)
     :mobile-nav (mobile-nav-vm route)
     :more-nav (more-nav-vm state route)
     :spectate (spectate-vm spectate-active?)
     :wallet (wallet-vm state)
     :settings (settings-vm state)}))
