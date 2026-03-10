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
  [state normalize-storage-mode]
  (let [wallet-address (get-in state [:wallet :address])
        connected? (boolean (get-in state [:wallet :connected?]))
        storage-mode (normalize-storage-mode
                      (get-in state [:wallet :agent :storage-mode]))]
    (if (and connected? (seq wallet-address))
      [[:effects/save-many [[[:wallet :agent :status] :approving]
                            [[:wallet :agent :error] nil]]]
       [:effects/enable-agent-trading {:storage-mode storage-mode}]]
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

(defn copy-wallet-address-action
  [state]
  [[:effects/copy-wallet-address (get-in state [:wallet :address])]])
