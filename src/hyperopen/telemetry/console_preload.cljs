(ns hyperopen.telemetry.console-preload
  (:require [hyperopen.platform :as platform]
            [hyperopen.system :as app-system]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.client-compat :as ws-client-compat]
            [hyperopen.websocket.client :as ws-client]))

(def ^:private debug-api-key
  "HYPEROPEN_DEBUG")

(defn- snapshot-map
  []
  {:captured-at-ms (platform/now-ms)
   :app-state @app-system/store
   :runtime-state @app-system/runtime
   :websocket {:runtime-view @ws-client/runtime-view
               :compat-projections (ws-client-compat/compat-projections)
               :client-runtime-state @ws-client/runtime-state
               :flight-recording (ws-client/get-flight-recording-redacted)}
   :telemetry {:event-count (count (telemetry/events))
               :events (telemetry/events)}})

(defn- snapshot-js
  []
  (clj->js (snapshot-map)))

(defn- snapshot-json
  []
  (js/JSON.stringify (snapshot-js) nil 2))

(defn- download-snapshot!
  []
  (when-let [document (some-> js/globalThis .-document)]
    (let [payload (snapshot-json)
          blob (js/Blob. #js [payload] #js {:type "application/json"})
          object-url (js/URL.createObjectURL blob)
          link (.createElement document "a")
          timestamp (platform/now-ms)]
      (set! (.-href link) object-url)
      (set! (.-download link) (str "hyperopen-debug-snapshot-" timestamp ".json"))
      (.appendChild (.-body document) link)
      (.click link)
      (.remove link)
      (js/URL.revokeObjectURL object-url))
    true))

(defn- download-flight-recording!
  []
  (when-let [document (some-> js/globalThis .-document)]
    (let [recording (ws-client/get-flight-recording-redacted)
          payload (js/JSON.stringify (clj->js recording) nil 2)
          blob (js/Blob. #js [payload] #js {:type "application/json"})
          object-url (js/URL.createObjectURL blob)
          link (.createElement document "a")
          timestamp (platform/now-ms)]
      (set! (.-href link) object-url)
      (set! (.-download link) (str "hyperopen-flight-recording-" timestamp ".json"))
      (.appendChild (.-body document) link)
      (.click link)
      (.remove link)
      (js/URL.revokeObjectURL object-url))
    true))

(defn- debug-api
  []
  #js {:snapshot snapshot-js
       :snapshotJson snapshot-json
       :downloadSnapshot download-snapshot!
       :flightRecording (fn []
                          (clj->js (ws-client/get-flight-recording)))
       :flightRecordingRedacted (fn []
                                  (clj->js (ws-client/get-flight-recording-redacted)))
       :clearFlightRecording ws-client/clear-flight-recording!
       :replayFlightRecording (fn []
                               (clj->js (ws-client/replay-flight-recording)))
       :downloadFlightRecording download-flight-recording!
       :events (fn []
                 (clj->js (telemetry/events)))
       :eventsJson telemetry/events-json
       :clearEvents telemetry/clear-events!})

(when ^boolean goog.DEBUG
  (let [global js/globalThis
        api (debug-api)]
    (aset global debug-api-key api)
    ;; Convenience aliases for direct console use.
    (aset global "hyperopenSnapshot" (aget api "snapshot"))
    (aset global "hyperopenSnapshotJson" (aget api "snapshotJson"))
    (aset global "hyperopenDownloadSnapshot" (aget api "downloadSnapshot"))))
