(ns hyperopen.websocket.client-compat
  (:require [hyperopen.websocket.client :as ws-client]))

(defn connection-state
  "Read-only compatibility snapshot of websocket connection projection."
  []
  (get-in @ws-client/runtime-view [:connection]))

(defn stream-runtime
  "Read-only compatibility snapshot of websocket stream projection."
  []
  (get-in @ws-client/runtime-view [:stream]))

(defn compat-projections
  "Read-only compatibility payload for transitional debug tooling."
  []
  {:connection-state (connection-state)
   :stream-runtime (stream-runtime)})
