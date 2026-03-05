(ns hyperopen.websocket.domain.policy)

(def default-channel-tier-policy
  {"l2Book" :market
   "trades" :market
   "candle" :market
   "activeAssetCtx" :market
   "webData2" :lossless
   "openOrders" :lossless
   "userFills" :lossless
   "userFundings" :lossless
   "userNonFundingLedgerUpdates" :lossless})

(def default-backpressure-policy
  {:mailbox-buffer-size 4096
   :effects-buffer-size 4096
   :control-buffer-size 1024
   :socket-event-buffer-size 8192
   :ingress-decoded-buffer-size 4096
   :market-buffer-size 2048
   :lossless-buffer-size 4096
   :outbound-intent-buffer-size 2048
   :metrics-buffer-size 1024
   :dead-letter-buffer-size 512
   :handler-buffer-size 64
   :lossless-depth-alert-threshold 500
   :market-coalesce-window-ms 16})

(defn topic->tier
  ([topic]
   (topic->tier default-channel-tier-policy topic))
  ([tier-policy topic]
   (get tier-policy topic :lossless)))

(defn merge-tier-policy
  "Merge policy overrides without mutating defaults."
  [base-policy overrides]
  (merge (or base-policy {}) (or overrides {})))
