(ns hyperopen.telemetry
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]))

(def ^:private max-events
  2000)

(def market-projection-flush-event-limit
  60)

(def ^:private market-projection-flush-diagnostics-keys
  [:seq
   :event
   :at-ms
   :store-id
   :pending-count
   :overwrite-count
   :flush-duration-ms
   :queue-wait-ms
   :flush-count
   :max-pending-depth
   :p95-flush-duration-ms
   :queued-total
   :overwrite-total])

(def ^:private market-projection-flush-event
  :websocket/market-projection-flush)

(defonce ^:private event-seq
  (atom 0))

(defonce ^:private event-log
  (atom []))

(defonce ^:private market-projection-flush-event-log
  (atom []))

(defonce ^:private market-projection-flush-diagnostics-event-log
  (atom []))

(defn dev-enabled?
  "Dev-only guard for local diagnostics logging."
  []
  ^boolean goog.DEBUG)

(defn clear-events!
  []
  (reset! event-log [])
  (reset! market-projection-flush-event-log [])
  (reset! market-projection-flush-diagnostics-event-log []))

(defn events
  []
  @event-log)

(defn market-projection-flush-events
  []
  @market-projection-flush-event-log)

(defn market-projection-flush-diagnostics-events
  []
  @market-projection-flush-diagnostics-event-log)

(defn events-json
  []
  (js/JSON.stringify (clj->js @event-log) nil 2))

(defn- safe-log-value
  [value]
  (if (or (nil? value)
          (string? value)
          (number? value)
          (keyword? value)
          (boolean? value))
    value
    (try
      (pr-str value)
      (catch :default _
        "<unprintable>"))))

(defn- append-bounded-entry
  [entries entry limit]
  (let [next-entries (conj (vec entries) entry)
        trim-start (max 0 (- (count next-entries) limit))]
    (subvec next-entries trim-start)))

(defn- append-event!
  [entry]
  (swap! event-log
         (fn [entries]
           (append-bounded-entry entries entry max-events))))

(defn- append-market-projection-flush-event!
  [entry]
  (when (= market-projection-flush-event (:event entry))
    (swap! market-projection-flush-event-log
           (fn [entries]
             (append-bounded-entry entries entry market-projection-flush-event-limit)))
    (swap! market-projection-flush-diagnostics-event-log
           (fn [entries]
             (append-bounded-entry entries
                                   (select-keys entry market-projection-flush-diagnostics-keys)
                                   market-projection-flush-event-limit)))))

(defn emit!
  ([event]
   (emit! event {}))
  ([event attrs]
   (when (dev-enabled?)
     (let [entry (merge {:seq (swap! event-seq inc)
                         :event event
                         :at-ms (platform/now-ms)}
                        (or attrs {}))]
       (append-event! entry)
       (append-market-projection-flush-event! entry)
       entry))))

(defn log!
  [& args]
  (when (dev-enabled?)
    (let [values (mapv safe-log-value args)]
      (emit! :log/message
             {:message (str/join " " (map str values))
              :args values}))))

(def log-fn
  log!)
