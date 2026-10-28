(ns hyperopen.vol-range.infrastructure.live-ingress
  "Vol Range ingress for live account-stream evidence. `user-stream-taps`
  returns the raw `userFills` / `openOrders` taps that startup passes to
  `hyperopen.websocket.user/init!` (`{:on-raw-user-fills f
  :on-raw-open-orders g}`, each called as `(f store msg received-at-ms)`
  before any display filtering or truncation). It returns no taps yet; the
  live-fills plan's Stage 4 routes the raw evidence to ladders in memory.")

(defn user-stream-taps
  "Raw user-stream taps for the account runtime. Currently none."
  []
  {})
