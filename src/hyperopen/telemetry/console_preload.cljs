(ns hyperopen.telemetry.console-preload
  (:require [clojure.string :as str]
            [nexus.registry :as nxr]
            [hyperopen.platform :as platform]
            [hyperopen.registry.runtime :as runtime-registry]
            [hyperopen.system :as app-system]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.market-projection-runtime :as market-projection-runtime]
            [hyperopen.websocket.client-compat :as ws-client-compat]
            [hyperopen.websocket.client :as ws-client]))

(def ^:private debug-api-key
  "HYPEROPEN_DEBUG")

(def ^:private debug-dispatch-prefix
  "HYPEROPEN_DEBUG.dispatch")

(defn- action-id->debug-string
  [action-id]
  (str action-id))

(defn- registered-action-id-strings
  []
  (->> (runtime-registry/registered-action-ids)
       (map action-id->debug-string)
       sort
       vec))

(defn- normalize-action-vector-input
  [action-vector]
  (cond
    (vector? action-vector) action-vector
    (sequential? action-vector) (vec action-vector)
    (array? action-vector) (js->clj action-vector :keywordize-keys true)
    :else nil))

(defn- normalize-debug-action-id
  [action-id]
  (cond
    (keyword? action-id) action-id
    (string? action-id) (let [trimmed (str/trim action-id)
                              normalized (if (str/starts-with? trimmed ":")
                                           (subs trimmed 1)
                                           trimmed)]
                          (when (seq normalized)
                            (keyword normalized)))
    :else nil))

(defn- invalid-dispatch-arg-error
  []
  (js/Error.
   (str debug-dispatch-prefix
        " expected an action vector whose first item is a registered action id string.")))

(defn- unknown-action-id-error
  [action-id]
  (js/Error.
   (str debug-dispatch-prefix
        " received unregistered action id "
        (pr-str action-id)
        ". Call HYPEROPEN_DEBUG.registeredActionIds() for valid ids.")))

(defn- normalize-debug-action-vector
  [action-vector]
  (let [action* (normalize-action-vector-input action-vector)
        action-id (some-> action* first normalize-debug-action-id)
        args (vec (rest (or action* [])))
        registered-action-ids (runtime-registry/registered-action-ids)]
    (when-not (and (vector? action*)
                   (seq action*)
                   (contains? registered-action-ids action-id))
      (if (some? action-id)
        (throw (unknown-action-id-error action-id))
        (throw (invalid-dispatch-arg-error))))
    (into [action-id] args)))

(defn- dispatch-debug-action!
  [action-vector]
  (let [normalized-action (normalize-debug-action-vector action-vector)
        action-id (first normalized-action)
        args (vec (rest normalized-action))]
    (nxr/dispatch app-system/store nil [normalized-action])
    #js {:dispatched true
         :actionId (action-id->debug-string action-id)
         :argCount (count args)}))

(defn- snapshot-map
  []
  {:captured-at-ms (platform/now-ms)
   :app-state @app-system/store
   :runtime-state @app-system/runtime
   :websocket {:runtime-view @ws-client/runtime-view
               :compat-projections (ws-client-compat/compat-projections)
               :client-runtime-state @ws-client/runtime-state
               :market-projection-telemetry (market-projection-runtime/market-projection-telemetry-snapshot)
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
       :registeredActionIds (fn []
                              (clj->js (registered-action-id-strings)))
       :dispatch dispatch-debug-action!
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
