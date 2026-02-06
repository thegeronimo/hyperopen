(ns hyperopen.websocket.domain.policy)

(def default-channel-tier-policy
  {"l2Book" :market
   "trades" :market
   "activeAssetCtx" :market
   "webData2" :lossless
   "openOrders" :lossless
   "userFills" :lossless
   "userFundings" :lossless
   "userNonFundingLedgerUpdates" :lossless})

(def default-backpressure-policy
  {:control-buffer-size 256
   :outbound-buffer-size 1024
   :ingress-raw-buffer-size 2048
   :ingress-decoded-buffer-size 1024
   :market-buffer-size 512
   :lossless-buffer-size 1024
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

