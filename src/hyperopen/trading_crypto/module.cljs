(ns hyperopen.trading-crypto.module
  (:require [hyperopen.utils.hl-signing :as signing]
            [hyperopen.wallet.agent-session-crypto :as agent-session-crypto]))

(defn ^:export createAgentCredentials
  []
  (agent-session-crypto/create-agent-credentials!))

(defn ^:export privateKeyToAgentAddress
  [private-key]
  (agent-session-crypto/private-key->agent-address private-key))

(defn ^:export signL1ActionWithPrivateKey
  [private-key action nonce options]
  (let [{:keys [vault-address expires-after is-mainnet]} (or options {})]
    (signing/sign-l1-action-with-private-key!
     private-key
     action
     nonce
     :vault-address vault-address
     :expires-after expires-after
     :is-mainnet is-mainnet)))

(defn ^:export signApproveAgentAction
  [address action]
  (signing/sign-approve-agent-action! address action))

(defn ^:export signUsdClassTransferAction
  [address action]
  (signing/sign-usd-class-transfer-action! address action))

(defn ^:export signSendAssetAction
  [address action]
  (signing/sign-send-asset-action! address action))

(defn ^:export signCDepositAction
  [address action]
  (signing/sign-c-deposit-action! address action))

(defn ^:export signCWithdrawAction
  [address action]
  (signing/sign-c-withdraw-action! address action))

(defn ^:export signTokenDelegateAction
  [address action]
  (signing/sign-token-delegate-action! address action))

(defn ^:export signWithdraw3Action
  [address action]
  (signing/sign-withdraw3-action! address action))

(goog/exportSymbol "hyperopen.trading_crypto.module.createAgentCredentials" createAgentCredentials)
(goog/exportSymbol "hyperopen.trading_crypto.module.privateKeyToAgentAddress" privateKeyToAgentAddress)
(goog/exportSymbol "hyperopen.trading_crypto.module.signL1ActionWithPrivateKey" signL1ActionWithPrivateKey)
(goog/exportSymbol "hyperopen.trading_crypto.module.signApproveAgentAction" signApproveAgentAction)
(goog/exportSymbol "hyperopen.trading_crypto.module.signUsdClassTransferAction" signUsdClassTransferAction)
(goog/exportSymbol "hyperopen.trading_crypto.module.signSendAssetAction" signSendAssetAction)
(goog/exportSymbol "hyperopen.trading_crypto.module.signCDepositAction" signCDepositAction)
(goog/exportSymbol "hyperopen.trading_crypto.module.signCWithdrawAction" signCWithdrawAction)
(goog/exportSymbol "hyperopen.trading_crypto.module.signTokenDelegateAction" signTokenDelegateAction)
(goog/exportSymbol "hyperopen.trading_crypto.module.signWithdraw3Action" signWithdraw3Action)
