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
  "These settings live on this device only.")

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
        disabled? (boolean (#{:approving :unlocking} status))]
    (when (not= :ready status)
      {:label (case status
                :approving "Awaiting signature..."
                :locked "Unlock Trading"
                :unlocking "Awaiting passkey..."
                "Enable Trading")
       :disabled? disabled?
       :action [[(case status
                   :locked :actions/unlock-agent-trading
                   :unlocking :actions/unlock-agent-trading
                   :actions/enable-agent-trading)]]})))

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
    :local))

(defn- remember-trading-session?
  [state]
  (= :local (trading-settings-storage-mode state)))

(defn- trading-settings-local-protection-mode
  [state]
  (agent-session/normalize-local-protection-mode
   (get-in state [:wallet :agent :local-protection-mode])))

(defn- passkey-lock-enabled?
  [state]
  (= :passkey (trading-settings-local-protection-mode state)))

(defn- passkey-toggle-available?
  [state]
  (true? (get-in state [:wallet :agent :passkey-supported?])))

(defn- passkey-toggle-helper-copy
  [remember-session? passkey-capable? passkey-enabled? agent-status]
  (cond
    (not remember-session?)
    "Available when Remember session is on."

    (not passkey-capable?)
    "This browser does not support passkey-locked trading."

    (and passkey-enabled? (= :locked agent-status))
    "Unlock trading before turning off passkey protection."

    (and passkey-enabled? (= :unlocking agent-status))
    "Finish unlocking before turning off passkey protection."

    :else
    nil))

(defn- passkey-toggle-tooltip-copy
  [remember-session? passkey-capable? passkey-enabled? agent-status]
  (or (passkey-toggle-helper-copy remember-session?
                                  passkey-capable?
                                  passkey-enabled?
                                  agent-status)
      (if passkey-enabled?
        "Trading stays remembered on this device, but you will need one passkey unlock after a browser restart before orders can be signed again."
        "Protect the remembered trading session with a passkey so the key is not resumed automatically after a browser restart.")))

(defn- passkey-toggle-disabled?
  [remember-session? passkey-capable? passkey-enabled? agent-status]
  (boolean
   (or (not remember-session?)
       (not passkey-capable?)
       (and passkey-enabled?
            (#{:locked :unlocking} agent-status)))))

(defn- settings-confirmation-copy
  [confirmation]
  (case (:kind confirmation)
    :agent-storage-mode
    (case (some-> (:next-mode confirmation)
                  agent-session/normalize-storage-mode)
      :local
      {:title "Remember session on this device?"
       :body "Changes trading persistence on this device and will require Enable Trading again."
       :confirm-label "Change"
       :cancel-action [[:actions/cancel-agent-storage-mode-change]]
       :confirm-action [[:actions/confirm-agent-storage-mode-change]]}

      :session
      {:title "Use session-only trading?"
       :body "Changes trading persistence for this browser session and will require Enable Trading again."
       :confirm-label "Change"
       :cancel-action [[:actions/cancel-agent-storage-mode-change]]
       :confirm-action [[:actions/confirm-agent-storage-mode-change]]}

      nil)

    nil))

(defn- settings-row
  [id title hint checked? icon-kind on-change & {:keys [confirmation disabled? tooltip]}]
  {:id id
   :data-role (str "trading-settings-" (name id) "-row")
   :title title
   :hint hint
   :helper-copy nil
   :tooltip tooltip
   :checked? checked?
   :disabled? disabled?
   :icon-kind icon-kind
   :aria-label title
   :on-change on-change
   :confirmation confirmation})

(defn- settings-section
  [id title hint rows]
  {:id id
   :title title
   :hint hint
   :data-role (str "trading-settings-" (name id) "-section")
   :rows rows})

(defn- settings-vm
  [state]
  (let [confirmation (settings-confirmation-copy
                      (get-in state [:header-ui :settings-confirmation]))
        remember-session? (remember-trading-session? state)
        passkey-capable? (passkey-toggle-available? state)
        passkey-enabled? (passkey-lock-enabled? state)
        agent-status (get-in state [:wallet :agent :status])
        passkey-helper-copy (passkey-toggle-helper-copy remember-session?
                                                        passkey-capable?
                                                        passkey-enabled?
                                                        agent-status)
        passkey-disabled? (passkey-toggle-disabled? remember-session?
                                                    passkey-capable?
                                                    passkey-enabled?
                                                    agent-status)]
    {:open? (true? (get-in state [:header-ui :settings-open?]))
     :return-focus? (true? (get-in state [:header-ui :settings-return-focus?]))
     :trigger-key (str "header-settings-button:"
                       (true? (get-in state [:header-ui :settings-open?]))
                       ":"
                       (true? (get-in state [:header-ui :settings-return-focus?])))
     :trigger-action (if (true? (get-in state [:header-ui :settings-open?]))
                       [[:actions/close-header-settings]]
                       [[:actions/open-header-settings]])
     :title "Trading Settings"
     :close-actions trading-settings-close-actions
     :keydown-action trading-settings-keydown-action
     :footer-note trading-settings-footer-copy
     :sections [(settings-section
                 :session
                 "Session"
                 "Sign-in behavior"
                 [(settings-row :storage-mode
                                "Remember session"
                                "Stay signed in across browser restarts."
                                remember-session?
                                :session
                                [[:actions/request-agent-storage-mode-change
                                  (not remember-session?)]]
                                :tooltip "Keep trading enabled across browser restarts on this device."
                                :confirmation (when (= :agent-storage-mode
                                                       (get-in state [:header-ui :settings-confirmation :kind]))
                                                confirmation))
                  (settings-row :local-protection-mode
                                "Lock trading with passkey"
                                "Require passkey for sensitive actions."
                                passkey-enabled?
                                :key
                                [[:actions/request-agent-local-protection-mode-change
                                  (if passkey-enabled? :plain :passkey)]]
                                :tooltip (passkey-toggle-tooltip-copy remember-session?
                                                                      passkey-capable?
                                                                      passkey-enabled?
                                                                      agent-status)
                                :disabled? passkey-disabled?)])
                (settings-section
                 :confirmations
                 "Confirmations"
                 "Ask before you trade"
                 [(settings-row :confirm-open-orders
                                "Confirm open orders"
                                "Show a preview before placing."
                                (trading-settings/confirm-open-orders? state)
                                :confirm
                                [[:actions/set-confirm-open-orders-enabled
                                  (not (trading-settings/confirm-open-orders? state))]]
                                :tooltip "Ask before sending a new order from the trade form.")
                  (settings-row :confirm-close-position
                                "Confirm close position"
                                "Show a preview before closing."
                                (trading-settings/confirm-close-position? state)
                                :confirm
                                [[:actions/set-confirm-close-position-enabled
                                  (not (trading-settings/confirm-close-position? state))]]
                                :tooltip "Ask before submitting from the close-position popover.")
                  (settings-row :confirm-market-orders
                                "Confirm market orders"
                                "Also ask when placing market orders."
                                (trading-settings/confirm-market-orders? state)
                                :confirm
                                [[:actions/set-confirm-market-orders-enabled
                                  (not (trading-settings/confirm-market-orders? state))]])])
                (settings-section
                 :alerts
                 "Alerts"
                 "Feedback when fills land"
                 [(settings-row :fill-alerts
                                "Fill alerts"
                                "Toast when any order fills."
                                (trading-settings/fill-alerts-enabled? state)
                                :alerts
                                [[:actions/set-fill-alerts-enabled
                                  (not (trading-settings/fill-alerts-enabled? state))]]
                                :tooltip "Show fill alerts while Hyperopen is open.")
                  (settings-row :sound-on-fill
                                "Sound on fill"
                                "Plays a short chime on fill."
                                (trading-settings/sound-on-fill? state)
                                :sound
                                [[:actions/set-sound-on-fill-enabled
                                  (not (trading-settings/sound-on-fill? state))]])])
                (settings-section
                 :display
                 "Display"
                 "Visual chrome"
                 [(settings-row :animate-orderbook
                                "Animate order book"
                                "Animate row changes in the book."
                                (trading-settings/animate-orderbook? state)
                                :book
                                [[:actions/set-animate-orderbook-enabled
                                  (not (trading-settings/animate-orderbook? state))]]
                                :tooltip "Smooth bid and ask depth changes as the book updates.")
                  (settings-row :fill-markers
                                "Fill markers"
                                "Show your fills on the price chart."
                                (trading-settings/show-fill-markers? state)
                                :marker
                                [[:actions/set-fill-markers-enabled
                                  (not (trading-settings/show-fill-markers? state))]]
                                :tooltip "Show buy and sell markers for the active asset on the chart.")])]}))

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
