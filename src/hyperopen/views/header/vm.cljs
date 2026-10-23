(ns hyperopen.views.header.vm
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.account.spectate-mode-links :as spectate-mode-links]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.ui.theme :as ui-theme]
            [hyperopen.ui.voice :as voice]
            [hyperopen.views.header.account-selector :as account-selector]
            [hyperopen.views.header.nav :as nav]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.core :as wallet]))

(def ^:private spectate-mode-trigger-tooltip-id
  "spectate-mode-open-tooltip")

(def ^:private trading-settings-footer-copy
  "These settings live on this device only.")

(def ^:private trading-settings-close-actions
  [[:actions/close-header-settings]])

(defn- nav-href
  [state route]
  (spectate-mode-links/internal-route-href state route))

(defn- with-voice-label
  [state item]
  (assoc item :label (voice/nav-label state (:id item) (:label item))))

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
  (mapv (comp (partial with-desktop-action state)
              (partial with-voice-label state))
        (nav/items-for-placement route :desktop)))

(defn- mobile-nav-vm
  [state route]
  (let [present (comp with-mobile-action
                      (partial with-voice-label state))]
    {:primary-items (mapv present
                          (nav/items-for-placement route :mobile-primary))
     :secondary-items (mapv present
                            (nav/items-for-placement route :mobile-secondary))}))

(defn- more-nav-vm
  [state route]
  (let [items (mapv (comp (partial with-more-action state)
                          (partial with-voice-label state))
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
     :providers (:providers wallet-state)
     :copy-feedback (:copy-feedback wallet-state)
     :copy-action [[:actions/copy-wallet-address]]
     :disconnect-action [[:actions/disconnect-wallet]]
     :agent-error (some-> wallet-state :agent :error str not-empty)
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

(defn- theme-choice-row
  [state]
  (let [active-id (ui-theme/active-theme-id state)]
    {:id :ui-theme
     :kind :choice
     :data-role "trading-settings-ui-theme-row"
     :title "Theme"
     :hint "Color palette on this device."
     :icon-kind :appearance
     :aria-label "Theme"
     :options (mapv (fn [{:keys [id label]}]
                      {:value id
                       :label label
                       :active? (= id active-id)
                       :action [[:actions/set-ui-theme id]]})
                    ui-theme/themes)}))

(def ^:private open-order-safety-options
  [{:value "strict"
    :label "Strict"
    :mode :strict
    :tooltip "Cancels open orders if Hyperopen stops refreshing for about 1 minute."}
   {:value "extended"
    :label "4h"
    :mode :extended
    :tooltip "Keeps the dead-man switch, but gives this account or vault about 4 hours offline before canceling."}
   {:value "off"
    :label "Off"
    :mode :off
    :tooltip "Clears Hyperliquid scheduled cancel. GTC orders stay live until filled, manually canceled, or rejected."}])

(defn- open-order-safety-choice-row
  [state]
  (let [active-mode (trading-settings/open-order-safety-mode state)]
    {:id :open-order-safety-mode
     :kind :choice
     :data-role "trading-settings-open-order-safety-mode-row"
     :title "Open order safety"
     :hint "Account/vault-wide offline cancel behavior."
     :icon-kind :book
     :aria-label "Open order safety"
     :options (mapv (fn [{:keys [label mode tooltip value]}]
                      {:value value
                       :label label
                       :tooltip tooltip
                       :active? (= mode active-mode)
                       :action [[:actions/set-open-order-safety-mode value]]})
                    open-order-safety-options)}))

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
                 :open-orders
                 "Open orders"
                 "Exchange safety"
                 [(open-order-safety-choice-row state)])
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
                  (settings-row :chart-trades
                                "Trades on chart"
                                "Show your buys, sells and position history on the price chart."
                                (trading-settings/show-chart-trades? state)
                                :marker
                                [[:actions/set-chart-trades-enabled
                                  (not (trading-settings/show-chart-trades? state))]]
                                :tooltip "Markers at your fill prices, a details card on hover, and a long/short ribbon.")])
                (settings-section
                 :appearance
                 "Appearance"
                 "Look and feel"
                 [(theme-choice-row state)])]}))

(defn- brand-vm
  [state]
  {:wordmark (voice/label state :brand/wordmark)
   :mark (voice/label state :brand/mark)
   :tagline (when (voice/degen? state) "formerly responsible")})

(defn header-vm
  [state]
  (let [route (get-in state [:router :path] "/trade")
        spectate-active? (account-context/spectate-mode-active? state)]
    {:route route
     :brand (brand-vm state)
     :mobile-menu-open? (true? (get-in state [:header-ui :mobile-menu-open?]))
     :desktop-nav-items (desktop-nav-vm state route)
     :mobile-nav (mobile-nav-vm state route)
     :more-nav (more-nav-vm state route)
     :spectate (spectate-vm spectate-active?)
     :account-selector (account-selector/vm state)
     :wallet (wallet-vm state)
     :settings (settings-vm state)}))
