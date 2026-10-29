(ns hyperopen.schema.runtime-registration.wallet)

(def effect-binding-rows
  [[:effects/connect-wallet :connect-wallet]
   [:effects/disconnect-wallet :disconnect-wallet]
   [:effects/enable-agent-trading :enable-agent-trading]
   [:effects/set-agent-storage-mode :set-agent-storage-mode]
   [:effects/set-agent-local-protection-mode :set-agent-local-protection-mode]
   [:effects/unlock-agent-trading :unlock-agent-trading]
   [:effects/copy-wallet-address :copy-wallet-address]
   [:effects/apply-ui-theme :apply-ui-theme]])

(def effect-order-policy-required-action-ids
  #{:actions/navigate-mobile-header-menu
    :actions/enable-agent-trading
    :actions/unlock-agent-trading})

(def action-binding-rows
  [[:actions/connect-wallet :connect-wallet-action]
   [:actions/disconnect-wallet :disconnect-wallet-action]
   [:actions/open-mobile-header-menu :open-mobile-header-menu]
   [:actions/close-mobile-header-menu :close-mobile-header-menu]
   [:actions/open-header-settings :open-header-settings]
   [:actions/close-header-settings :close-header-settings]
   [:actions/set-fill-alerts-enabled :set-fill-alerts-enabled]
   [:actions/set-sound-on-fill-enabled :set-sound-on-fill-enabled]
   [:actions/set-animate-orderbook-enabled :set-animate-orderbook-enabled]
   [:actions/set-chart-trades-enabled :set-chart-trades-enabled]
   [:actions/set-confirm-open-orders-enabled :set-confirm-open-orders-enabled]
   [:actions/set-confirm-close-position-enabled :set-confirm-close-position-enabled]
   [:actions/set-confirm-market-orders-enabled :set-confirm-market-orders-enabled]
   [:actions/set-open-order-safety-mode :set-open-order-safety-mode]
   [:actions/set-ui-theme :set-ui-theme]
   [:actions/reset-degen-life :reset-degen-life]
   [:actions/navigate-mobile-header-menu :navigate-mobile-header-menu]
   [:actions/request-agent-storage-mode-change :request-agent-storage-mode-change]
   [:actions/cancel-agent-storage-mode-change :cancel-agent-storage-mode-change]
   [:actions/confirm-agent-storage-mode-change :confirm-agent-storage-mode-change]
   [:actions/request-agent-local-protection-mode-change :request-agent-local-protection-mode-change]
   [:actions/cancel-agent-local-protection-mode-change :cancel-agent-local-protection-mode-change]
   [:actions/confirm-agent-local-protection-mode-change :confirm-agent-local-protection-mode-change]
   [:actions/enable-agent-trading :enable-agent-trading-action]
   [:actions/unlock-agent-trading :unlock-agent-trading-action]
   [:actions/close-agent-recovery-modal :close-agent-recovery-modal-action]
   [:actions/set-agent-storage-mode :set-agent-storage-mode-action]
   [:actions/set-agent-local-protection-mode :set-agent-local-protection-mode-action]
   [:actions/copy-wallet-address :copy-wallet-address-action]])
