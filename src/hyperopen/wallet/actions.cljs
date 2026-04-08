(ns hyperopen.wallet.actions)

(defn connect-wallet-action
  [_]
  [[:effects/connect-wallet]])

(defn disconnect-wallet-action
  [_]
  [[:effects/disconnect-wallet]])

(defn close-agent-recovery-modal-action
  [_state]
  [[:effects/save [:wallet :agent :recovery-modal-open?] false]])

(defn enable-agent-trading-action
  [state normalize-storage-mode normalize-local-protection-mode]
  (let [wallet-address (get-in state [:wallet :address])
        connected? (boolean (get-in state [:wallet :connected?]))
        storage-mode (normalize-storage-mode
                      (get-in state [:wallet :agent :storage-mode]))
        local-protection-mode (normalize-local-protection-mode
                               (get-in state [:wallet :agent :local-protection-mode]))]
    (if (and connected? (seq wallet-address))
      [[:effects/save-many [[[:wallet :agent :status] :approving]
                            [[:wallet :agent :error] nil]]]
       [:effects/enable-agent-trading {:storage-mode storage-mode
                                       :local-protection-mode local-protection-mode}]]
      [[:effects/save-many [[[:wallet :agent :status] :error]
                            [[:wallet :agent :error] "Connect your wallet before enabling trading."]]]])))

(defn set-agent-storage-mode-action
  [state storage-mode normalize-storage-mode]
  (let [next-mode (normalize-storage-mode storage-mode)
        current-mode (normalize-storage-mode
                      (get-in state [:wallet :agent :storage-mode]))]
    (if (= next-mode current-mode)
      []
      [[:effects/set-agent-storage-mode next-mode]])))

(defn unlock-agent-trading-action
  [state]
  (let [wallet-address (get-in state [:wallet :address])
        connected? (boolean (get-in state [:wallet :connected?]))]
    (if (and connected? (seq wallet-address))
      [[:effects/save-many [[[:wallet :agent :status] :unlocking]
                            [[:wallet :agent :error] nil]]]
       [:effects/unlock-agent-trading]]
      [[:effects/save-many [[[:wallet :agent :status] :locked]
                            [[:wallet :agent :error] "Connect your wallet before unlocking trading."]]]])))

(defn set-agent-local-protection-mode-action
  [state local-protection-mode normalize-local-protection-mode]
  (let [next-mode (normalize-local-protection-mode local-protection-mode)
        current-mode (normalize-local-protection-mode
                      (get-in state [:wallet :agent :local-protection-mode]))]
    (if (= next-mode current-mode)
      []
      [[:effects/set-agent-local-protection-mode next-mode]])))

(defn copy-wallet-address-action
  [state]
  [[:effects/copy-wallet-address (get-in state [:wallet :address])]])
