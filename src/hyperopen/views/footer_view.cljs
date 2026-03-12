(ns hyperopen.views.footer-view
  (:require [clojure.string :as str]
            [hyperopen.config :as app-config]
            [hyperopen.platform :as platform]
            [hyperopen.trade.layout-actions :as trade-layout-actions]
            [hyperopen.websocket.diagnostics-sanitize :as diagnostics-sanitize]))

(def footer-link-classes
  ["text-sm" "text-trading-text" "hover:text-primary" "transition-colors"])

(def ^:private default-app-version
  (:app-version app-config/config))

(def ^:private diagnostics-timeline-limit
  (get-in app-config/config [:diagnostics :timeline-limit]))

(def ^:private market-flush-sparkline-sample-limit
  24)

(def ^:private market-flush-table-row-limit
  25)

(def ^:private market-flush-sparkline-width
  144)

(def ^:private market-flush-sparkline-height
  36)

(declare diagnostics-display-value)

(def ^:private neutral-statuses
  #{:idle :n-a nil})

(def ^:private group-priority
  [:orders_oms :market_data :account])

(def ^:private group-rank
  {:orders_oms 0
   :market_data 1
   :account 2})

(def ^:private meter-bars-total
  4)

(def ^:private meter-bar-heights
  [6 9 12 15])

(def ^:private meter-group-weight
  {:orders_oms 1.0
   :market_data 0.85
   :account 0.65})

(def ^:private meter-status-penalty
  {:idle 0
   :n-a 0
   :live 0
   :delayed 14
   :reconnecting 24
   :offline 36
   :unknown 20
   nil 20})

(def ^:private meter-transport-state-penalty
  {:connected 0
   :connecting 12
   :reconnecting 20
   :disconnected 32
   :unknown 20
   nil 20})

(def ^:private meter-transport-freshness-penalty
  {:idle 6
   :n-a 0
   :live 0
   :delayed 18
   :reconnecting 34
   :offline 55
   :unknown 28
   nil 28})

(def ^:private meter-transport-live-threshold-ms
  10000)

(defn- status-label [status]
  (case status
    :n-a "EVENT-DRIVEN"
    :idle "IDLE"
    :live "LIVE"
    :delayed "DELAYED"
    :reconnecting "RECONNECTING"
    :offline "OFFLINE"
    :unknown "UNKNOWN"
    (-> (or status :unknown) name str/upper-case)))

(defn- source-label [source]
  (case source
    :orders_oms "orders/oms"
    :market_data "market data"
    :account "account"
    :transport "transport"
    "transport"))

(defn- timeline-event-label [event]
  (case event
    :connected "connected"
    :reconnecting "reconnecting"
    :offline "offline"
    :reset-market "reset-market"
    :reset-oms "reset-oms"
    :reset-all "reset-all"
    :auto-recover-market "auto-recover-market"
    :gap-detected "gap-detected"
    "unknown"))

(defn- app-build-id []
  (some-> js/globalThis
          (aget "HYPEROPEN_BUILD_ID")
          str))

(defn- view-now-ms [generated-at-ms]
  (let [generated* (or generated-at-ms 0)
        wall-now-ms (platform/now-ms)]
    (if (>= generated* 1000000000000)
      (max generated* wall-now-ms)
      generated*)))

(defn- status-tone [status]
  (case status
    :idle {:border "border-base-300"
           :bg "bg-base-200/40"
           :text "text-base-content/70"}
    :n-a {:border "border-base-300"
          :bg "bg-base-200/40"
          :text "text-base-content/70"}
    :live {:border "border-success/50"
           :bg "bg-success/10"
           :text "text-success"}
    :delayed {:border "border-warning/50"
              :bg "bg-warning/10"
              :text "text-warning"}
    :reconnecting {:border "border-warning/50"
                   :bg "bg-warning/10"
                   :text "text-warning"}
    {:border "border-error/50"
     :bg "bg-error/10"
     :text "text-error"}))

(defn- non-neutral-status? [status]
  (not (contains? neutral-statuses status)))

(defn- dominant-status-from-groups [health]
  (some (fn [group]
          (let [status (get-in health [:groups group :worst-status])]
            (when (non-neutral-status? status)
              {:source group
               :status status})))
        group-priority))

(defn- dominant-pill-state [health]
  (or (dominant-status-from-groups health)
      {:source :transport
       :status (get-in health [:transport :freshness] :offline)}))

(defn- meter-status-label [status]
  (case status
    :live "Online"
    :n-a "Online"
    :delayed "Delayed"
    :reconnecting "Reconnecting"
    :offline "Offline"
    :idle "Idle"
    "Offline"))

(defn- format-age-ms [age-ms]
  (cond
    (not (number? age-ms))
    "n/a"

    (< age-ms 1000)
    "<1s"

    (< age-ms 60000)
    (str (quot age-ms 1000) "s")

    :else
    (let [minutes (quot age-ms 60000)
          seconds (quot (mod age-ms 60000) 1000)]
      (str minutes "m " seconds "s"))))

(defn- transport-last-recv-age-ms [now-ms health]
  (let [last-recv-at-ms (get-in health [:transport :last-recv-at-ms])]
    (when (and (number? now-ms)
               (number? last-recv-at-ms))
      (max 0 (- now-ms last-recv-at-ms)))))

(defn- stream-age-ms [now-ms stream]
  (let [generated-at-ms now-ms
        last-payload-at-ms (:last-payload-at-ms stream)]
    (when (and (number? generated-at-ms)
               (number? last-payload-at-ms))
      (max 0 (- generated-at-ms last-payload-at-ms)))))

(defn- weighted-status-penalty [group status]
  (let [weight (get meter-group-weight group 0.5)
        base (get meter-status-penalty status
                  (get meter-status-penalty :unknown 20))]
    (int (js/Math.round (* weight base)))))

(defn- age-threshold-ratio [age-ms threshold-ms]
  (when (and (number? age-ms)
             (number? threshold-ms)
             (pos? threshold-ms))
    (/ age-ms threshold-ms)))

(defn- live-stream-headroom-penalty [generated-at-ms stream]
  (let [age-ms (or (:age-ms stream)
                   (stream-age-ms generated-at-ms stream))
        ratio (age-threshold-ratio age-ms (:stale-threshold-ms stream))]
    (cond
      (not (:subscribed? stream)) 0
      (not= :live (:status stream)) 0
      (not (number? ratio)) 0
      (< ratio 0.12) 0
      (< ratio 0.2) 2
      (< ratio 0.32) 4
      (< ratio 0.45) 6
      (< ratio 0.6) 9
      (< ratio 0.8) 12
      :else 16)))

(defn- stream-headroom-penalty [health]
  (let [generated-at-ms (:generated-at-ms health)
        penalties (->> (vals (or (:streams health) {}))
                       (map #(live-stream-headroom-penalty generated-at-ms %))
                       (filter pos?)
                       (sort >)
                       vec)
        top-penalty (reduce + (take 3 penalties))
        overflow-penalty (max 0 (- (count penalties) 3))]
    (min 22 (+ top-penalty overflow-penalty))))

(defn- transport-headroom-penalty [health]
  (let [transport-state (get-in health [:transport :state])
        transport-freshness (get-in health [:transport :freshness])
        age-ms (transport-last-recv-age-ms (:generated-at-ms health) health)
        ratio (age-threshold-ratio age-ms meter-transport-live-threshold-ms)]
    (cond
      (not= :connected transport-state) 0
      (not= :live transport-freshness) 0
      (not (number? ratio)) 0
      (< ratio 0.08) 0
      (< ratio 0.16) 2
      (< ratio 0.28) 4
      (< ratio 0.42) 6
      (< ratio 0.58) 9
      (< ratio 0.75) 12
      :else 16)))

(defn- delayed-stream-severity-penalty [generated-at-ms stream]
  (let [age-ms (or (:age-ms stream)
                   (stream-age-ms generated-at-ms stream))
        stale-threshold-ms (:stale-threshold-ms stream)
        ratio (when (and (= :delayed (:status stream))
                         (:subscribed? stream)
                         (number? age-ms)
                         (number? stale-threshold-ms)
                         (pos? stale-threshold-ms))
                (/ age-ms stale-threshold-ms))]
    (cond
      (not (number? ratio)) 0
      (<= ratio 1.25) 3
      (<= ratio 1.5) 6
      (<= ratio 2.0) 10
      (<= ratio 3.0) 14
      :else 18)))

(defn- stream-delay-penalty [health]
  (let [generated-at-ms (:generated-at-ms health)
        penalties (->> (vals (or (:streams health) {}))
                       (map #(delayed-stream-severity-penalty generated-at-ms %))
                       (filter pos?)
                       (sort >)
                       vec)
        top-penalty (reduce + (take 3 penalties))
        overflow-penalty (* 2 (max 0 (- (count penalties) 3)))]
    (min 28 (+ top-penalty overflow-penalty))))

(defn- any-gap-detected? [health]
  (boolean
   (some true?
         (for [group group-priority]
           (get-in health [:groups group :gap-detected?])))))

(defn- browser-network-connection []
  (let [navigator (or (.-navigator js/globalThis)
                      (some-> js/globalThis .-window .-navigator))]
    (or (some-> navigator .-connection)
        (some-> navigator (aget "mozConnection"))
        (some-> navigator (aget "webkitConnection")))))

(defn- browser-network-hint-penalty []
  (let [connection (browser-network-connection)
        effective-type (some-> connection .-effectiveType str str/lower-case)
        rtt (some-> connection .-rtt)
        downlink (some-> connection .-downlink)
        save-data? (true? (some-> connection .-saveData))
        effective-type-penalty (case effective-type
                                 "slow-2g" 32
                                 "2g" 24
                                 "3g" 14
                                 0)
        rtt-penalty (cond
                      (not (number? rtt)) 0
                      (> rtt 1200) 12
                      (> rtt 800) 9
                      (> rtt 400) 6
                      (> rtt 250) 3
                      :else 0)
        downlink-penalty (cond
                           (not (number? downlink)) 0
                           (< downlink 0.4) 10
                           (< downlink 0.8) 7
                           (< downlink 1.5) 4
                           :else 0)
        save-data-penalty (if save-data? 4 0)]
    (min 20 (+ effective-type-penalty
               rtt-penalty
               downlink-penalty
               save-data-penalty))))

(defn- connection-meter-penalty [health]
  (let [transport-state (get-in health [:transport :state])
        transport-freshness (get-in health [:transport :freshness])
        transport-state-penalty (get meter-transport-state-penalty
                                     transport-state
                                     (get meter-transport-state-penalty :unknown 20))
        transport-freshness-penalty (get meter-transport-freshness-penalty
                                         transport-freshness
                                         (get meter-transport-freshness-penalty :unknown 28))
        groups-penalty (reduce (fn [total group]
                                 (+ total
                                    (weighted-status-penalty
                                     group
                                     (get-in health [:groups group :worst-status]))))
                               0
                               group-priority)
        transport-headroom (transport-headroom-penalty health)
        stream-headroom (stream-headroom-penalty health)
        delayed-stream-penalty (stream-delay-penalty health)
        gap-penalty (if (any-gap-detected? health) 8 0)
        browser-network-penalty (browser-network-hint-penalty)]
    (+ transport-state-penalty
       transport-freshness-penalty
       groups-penalty
       transport-headroom
       stream-headroom
       delayed-stream-penalty
       gap-penalty
       browser-network-penalty)))

(defn- penalty->active-bars [penalty]
  (let [score (max 0 (- 100 (or penalty 100)))]
    (cond
      (>= score 88) 4
      (>= score 68) 3
      (>= score 45) 2
      (>= score 20) 1
      :else 0)))

(defn- connection-meter-tone [status active-bars]
  (cond
    (or (= status :offline) (zero? active-bars))
    {:border "border-error/50"
     :bg "bg-error/10"
     :label-text "text-error"
     :bar-active "bg-error"}

    (= status :reconnecting)
    {:border "border-warning/50"
     :bg "bg-warning/10"
     :label-text "text-warning"
     :bar-active "bg-warning"}

    (or (= status :delayed)
        (<= active-bars 2))
    {:border "border-warning/50"
     :bg "bg-warning/10"
     :label-text "text-warning"
     :bar-active "bg-warning"}

    :else
    {:border "border-success/50"
     :bg "bg-success/10"
     :label-text "text-success"
     :bar-active "bg-success"}))

(defn- connection-meter-model [health]
  (let [{:keys [source status]} (dominant-pill-state health)
        penalty (connection-meter-penalty health)
        active-bars (penalty->active-bars penalty)
        label (meter-status-label status)]
    {:source source
     :status status
     :active-bars active-bars
     :label label
     :tooltip (str label
                   " ("
                   active-bars
                   "/"
                   meter-bars-total
                   " bars) - "
                   (source-label source)
                   " "
                   (str/lower-case (status-label status)))}))

(defn- signal-meter-bars [active-bars bar-active-class]
  [:span {:class ["inline-flex" "items-end" "gap-[2px]"]
          :data-role "footer-connection-meter-bars"}
   (for [idx (range meter-bars-total)]
     ^{:key (str "meter-bar|" idx)}
     (let [active? (< idx active-bars)]
       [:span {:class (into ["block"
                             "w-[3px]"
                             "rounded-sm"
                             "transition-colors"
                             "duration-150"]
                            (if active?
                              [bar-active-class]
                              ["bg-base-300/70"]))
               :style {:height (str (nth meter-bar-heights idx 15) "px")}
               :data-role "footer-connection-meter-bar"
               :data-active (if active? "true" "false")}]))])

(defn- threshold-label [stale-threshold-ms]
  (if (number? stale-threshold-ms)
    (str stale-threshold-ms " ms")
    "n/a"))

(defn- format-ms [value]
  (if (number? value)
    (str (js/Math.round value) " ms")
    "n/a"))

(defn- sparkline-y
  [value max-value]
  (let [height market-flush-sparkline-height
        ratio (if (and (number? value) (pos? max-value))
                (/ (double value) (double max-value))
                0)]
    (-> (* (- 1 ratio) height)
        (max 0)
        (min height))))

(defn- sparkline-points
  [samples]
  (let [samples* (->> samples
                      (filter number?)
                      (take-last market-flush-sparkline-sample-limit)
                      vec)
        sample-count (count samples*)
        max-value (double (max 1 (or (reduce max 0 samples*) 1)))
        step-x (if (> sample-count 1)
                 (/ market-flush-sparkline-width (dec sample-count))
                 0)]
    {:samples samples*
     :max-value max-value
     :polyline-points (str/join
                       " "
                       (map-indexed
                        (fn [idx sample]
                          (let [x (js/Math.round (* idx step-x))
                                y (js/Math.round (sparkline-y sample max-value))]
                            (str x "," y)))
                        samples*))}))

(defn- flush-duration-sparkline
  [samples p95]
  (let [{:keys [samples max-value polyline-points]} (sparkline-points samples)]
    (if (seq samples)
      [:svg {:viewBox (str "0 0 "
                           market-flush-sparkline-width
                           " "
                           market-flush-sparkline-height)
             :preserveAspectRatio "none"
             :class ["h-9" "w-full"]}
       (when (number? p95)
         (let [y (js/Math.round (sparkline-y (min p95 max-value) max-value))]
           [:line {:x1 0
                   :x2 market-flush-sparkline-width
                   :y1 y
                   :y2 y
                   :stroke-width 1
                   :stroke "currentColor"
                   :class ["text-warning/70"]}]))
       [:polyline {:fill "none"
                   :stroke-width 2
                   :stroke "currentColor"
                   :class ["text-info"]
                   :points polyline-points}]]
      [:div {:class ["h-9" "text-xs" "text-base-content/60" "flex" "items-center"]}
       "No flush samples"])))

(defn- store-cell-tooltip
  [store-id-text]
  [:div {:class ["pointer-events-none"
                 "absolute"
                 "left-0"
                 "bottom-full"
                 "z-50"
                 "mb-1"
                 "opacity-0"
                 "transition-opacity"
                 "duration-150"
                 "group-hover:opacity-100"
                 "group-focus-within:opacity-100"]
         :style {:min-width "max-content"}}
   [:div {:class ["max-w-[20rem]"
                  "break-all"
                  "rounded"
                  "border"
                  "border-base-300"
                  "bg-base-100"
                  "px-2"
                  "py-1"
                  "text-xs"
                  "leading-4"
                  "text-base-content"
                  "spectate-lg"]}
    store-id-text]])

(defn- market-metric-label
  [label tooltip]
  [:span {:class ["relative" "group" "inline-flex" "items-center"]}
   [:span {:class ["cursor-help"
                   "border-b"
                   "border-dotted"
                   "border-base-content/30"]
           :title tooltip
           :tabindex 0}
    label]
   [:span {:class ["pointer-events-none"
                   "absolute"
                   "left-0"
                   "bottom-full"
                   "z-50"
                   "mb-1"
                   "max-w-[16rem]"
                   "rounded"
                   "border"
                   "border-base-300"
                   "bg-base-100"
                   "px-2"
                   "py-1"
                   "text-xs"
                   "leading-4"
                   "text-base-content"
                   "spectate-lg"
                   "opacity-0"
                   "transition-opacity"
                   "duration-150"
                   "group-hover:opacity-100"
                   "group-focus-within:opacity-100"]}
    tooltip]])

(defn- market-projection-section
  [health reveal-sensitive?]
  (let [market-projection (or (:market-projection health) {})
        stores (->> (:stores market-projection)
                    (sort-by :store-id)
                    vec)
        flush-events (vec (or (:flush-events market-projection) []))
        flush-events-by-store (group-by :store-id flush-events)]
    [:section {:class ["space-y-2"]
               :data-role "market-projection-diagnostics"}
     [:h3 {:class ["text-xs" "font-semibold" "uppercase" "tracking-wide" "text-base-content/70"]}
      "Market projection"]
     (if (seq stores)
       [:div {:class ["grid" "grid-cols-1" "gap-2"]}
        (for [store-summary stores]
          ^{:key (str "market-store|" (:store-id store-summary))}
          (let [store-id* (diagnostics-display-value reveal-sensitive? (:store-id store-summary))
                store-events (get flush-events-by-store (:store-id store-summary))
                durations (mapv :flush-duration-ms store-events)]
            [:article {:class ["rounded"
                               "border"
                               "border-base-300"
                               "bg-base-200/50"
                               "p-2"
                               "space-y-1.5"]}
             [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
              [:code {:class ["max-w-[11rem]" "truncate" "text-xs" "font-semibold"]
                      :title (str (or store-id* "n/a"))}
               (or (str store-id*) "n/a")]
              [:span {:class ["text-xs" "text-base-content/60"]}
               (str "Flushes: " (or (:flush-count store-summary) 0))]]
             [:div {:class ["grid" "grid-cols-2" "gap-x-3" "gap-y-1" "text-xs"]}
              (market-metric-label
               "Pending"
               "Current number of coalesced keys waiting for the next frame flush.")
              [:span {:class ["text-right"]} (str (or (:pending-count store-summary) 0))]
              (market-metric-label
               "Max pending"
               "Highest pending queue depth observed for this store since runtime reset.")
              [:span {:class ["text-right"]} (str (or (:max-pending-depth store-summary) 0))]
              (market-metric-label
               "Overwrites"
               "Total queued updates that replaced an existing coalesce key before a flush.")
              [:span {:class ["text-right"]} (str (or (:overwrite-total store-summary) 0))]
              (market-metric-label
               "P95 flush"
               "95th percentile flush duration from the bounded recent flush sample window.")
              [:span {:class ["text-right"]} (format-ms (:p95-flush-duration-ms store-summary))]
              (market-metric-label
               "Last flush"
               "Duration of the most recent flush for this store.")
              [:span {:class ["text-right"]} (format-ms (:last-flush-duration-ms store-summary))]
              (market-metric-label
               "Last queue wait"
               "Time between frame scheduling and flush start for the most recent flush.")
              [:span {:class ["text-right"]} (format-ms (:last-queue-wait-ms store-summary))]]
             [:div {:class ["border-t" "border-base-300/60" "pt-1"]}
              (flush-duration-sparkline durations
                                        (:p95-flush-duration-ms store-summary))
              [:div {:class ["mt-0.5" "text-xs" "text-base-content/60" "flex" "justify-between"]}
               (market-metric-label
                (str "Samples: " (count durations))
                "Count of recent flush durations currently represented in the sparkline window.")
               (market-metric-label
                "Blue=flush ms, amber=p95"
                "Blue line shows each flush duration. Amber line shows the p95 threshold.")]]]))]
       [:div {:class ["rounded" "border" "border-base-300" "bg-base-200/50" "p-3" "text-xs" "text-base-content/70"]}
        "No market projection telemetry yet."])]))

(defn- market-projection-recent-flushes-section
  [health now-ms reveal-sensitive?]
  (let [market-projection (or (:market-projection health) {})
        flush-events (vec (or (:flush-events market-projection) []))
        latest-events (->> flush-events
                           (sort-by (fn [entry]
                                      (or (:seq entry) 0)))
                           (take-last market-flush-table-row-limit)
                           reverse
                           vec)]
    [:section {:class ["space-y-2"]}
     [:h3 {:class ["text-xs" "font-semibold" "uppercase" "tracking-wide" "text-base-content/70"]}
      (str "Recent flushes (" (count latest-events) ")")]
     (if (seq latest-events)
       [:div {:class ["rounded" "border" "border-base-300" "bg-base-200/50" "p-3" "overflow-visible"]}
        [:table {:class ["w-full" "text-xs"]}
         [:thead
          [:tr {:class ["text-base-content/60"]}
           [:th {:class ["py-1" "pr-2" "text-left"]} "Age"]
           [:th {:class ["py-1" "pr-2" "text-left"]} "Store"]
           [:th {:class ["py-1" "pr-2" "text-left"]} "Pending"]
           [:th {:class ["py-1" "pr-2" "text-left"]} "Overwrite"]
           [:th {:class ["py-1" "pr-2" "text-left"]} "Flush"]
           [:th {:class ["py-1" "pr-2" "text-left"]} "Queue wait"]]]
         [:tbody
          (for [entry latest-events]
            ^{:key (str "market-flush|" (or (:seq entry)
                                            (:at-ms entry)
                                            (:store-id entry)))}
            (let [event-at-ms (:at-ms entry)
                  age-ms (when (and (number? now-ms) (number? event-at-ms))
                           (max 0 (- now-ms event-at-ms)))
                  store-id* (diagnostics-display-value reveal-sensitive? (:store-id entry))
                  store-id-text (or (some-> store-id* str) "n/a")]
              [:tr {:class ["border-t" "border-base-300/40"]}
               [:td {:class ["py-1" "pr-2"]} (format-age-ms age-ms)]
               [:td {:class ["py-1" "pr-2" "max-w-[10rem]"]}
                [:div {:class ["relative" "group" "max-w-[10rem]"]}
                 [:span {:class ["block" "truncate" "cursor-help"]
                         :title store-id-text
                         :tabindex 0}
                  store-id-text]
                 (store-cell-tooltip store-id-text)]]
               [:td {:class ["py-1" "pr-2"]} (str (or (:pending-count entry) 0))]
               [:td {:class ["py-1" "pr-2"]} (str (or (:overwrite-count entry) 0))]
               [:td {:class ["py-1" "pr-2"]} (format-ms (:flush-duration-ms entry))]
               [:td {:class ["py-1" "pr-2"]} (format-ms (:queue-wait-ms entry))]]))]]]
       [:div {:class ["rounded" "border" "border-base-300" "bg-base-200/50" "p-3" "text-xs" "text-base-content/70"]}
        "No flush events recorded in the telemetry ring."])]))

(defn- format-last-close [health]
  (let [close-info (get-in health [:transport :last-close])
        generated-at-ms (:generated-at-ms health)]
    (if (map? close-info)
      (let [code (or (:code close-info) "n/a")
            reason (or (:reason close-info) "n/a")
            at-ms (:at-ms close-info)
            close-age-ms (when (and (number? generated-at-ms) (number? at-ms))
                           (max 0 (- generated-at-ms at-ms)))]
        (str code
             " / "
             reason
             " / "
             (if (number? close-age-ms)
               (str (format-age-ms close-age-ms) " ago")
               "n/a")))
      "n/a")))

(defn- diagnostics-display-value [reveal-sensitive? value]
  (if reveal-sensitive?
    value
    (diagnostics-sanitize/sanitize-value :mask value)))

(defn- surface-freshness-toggle [checked?]
  [:label {:class ["flex"
                   "items-center"
                   "justify-between"
                   "gap-3"
                   "rounded"
                   "border"
                   "border-base-300"
                   "bg-base-200/50"
                   "px-3"
                   "py-2.5"
                   "text-xs"
                   "text-base-content"
                   "cursor-pointer"
                   "select-none"]
           :data-role "surface-freshness-toggle"}
   [:span "Show freshness cues"]
   [:input {:type "checkbox"
            :class ["h-5"
                    "w-5"
                    "rounded-[3px]"
                    "border"
                    "border-base-300"
                    "bg-transparent"
                    "trade-toggle-checkbox"
                    "transition-colors"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"
                    "focus:shadow-none"]
            :checked checked?
            :on {:click [[:actions/toggle-show-surface-freshness-cues]]}}]])

(defn- group-title [group]
  (case group
    :orders_oms "Orders/OMS"
    :market_data "Market Data"
    :account "Account"
    "Other"))

(defn- stream-groups [health]
  (let [rows (->> (get health :streams {})
                  (map (fn [[sub-key stream]]
                         (assoc stream :sub-key sub-key)))
                  (sort-by (fn [{:keys [group topic sub-key]}]
                             [(get group-rank (or group :account) 99)
                              (str topic)
                              (pr-str sub-key)])))]
    (group-by :group rows)))

(defn- banner-model [state health]
  (let [orders-status (get-in health [:groups :orders_oms :worst-status])
        market-status (get-in health [:groups :market_data :worst-status])
        market-banner-enabled? (boolean (get-in state [:websocket-ui :show-market-offline-banner?] false))]
    (cond
      (= :reconnecting orders-status)
      {:class ["border-warning/40" "bg-warning/10" "text-warning"]
       :message "Orders/OMS websocket reconnecting. Order lifecycle updates may be delayed."}

      (= :offline orders-status)
      {:class ["border-error/40" "bg-error/10" "text-error"]
       :message "Orders/OMS websocket offline. Trading activity status may be stale."}

      (and market-banner-enabled? (= :offline market-status))
      {:class ["border-info/40" "bg-info/10" "text-info"]
       :message "Market data websocket offline. Quotes and chart updates may be stale."}

      :else
      nil)))

(defn- status-chip [status]
  (let [{:keys [border bg text]} (status-tone status)]
    [:span {:class ["inline-flex"
                    "items-center"
                    "rounded"
                    "border"
                    "px-2"
                    "py-0.5"
                    "text-xs"
                    "font-semibold"
                    "uppercase"
                    "tracking-wide"
                    border
                    bg
                    text]}
     (status-label status)]))

(defn- hover-tooltip [message child]
  [:div {:class ["relative" "group" "w-full"]}
   child
   [:div {:class ["pointer-events-none"
                  "absolute"
                  "left-1/2"
                  "top-full"
                  "z-50"
                  "mt-1.5"
                  "-translate-x-1/2"
                  "opacity-0"
                  "transition-opacity"
                  "duration-150"
                  "group-hover:opacity-100"
                  "group-focus-within:opacity-100"]
          :style {:min-width "max-content"}}
    [:div {:class ["max-w-[18rem]"
                   "whitespace-normal"
                   "rounded"
                   "border"
                   "border-base-300"
                   "bg-base-100"
                   "px-2"
                   "py-1"
                   "text-xs"
                   "leading-4"
                   "text-base-content"
                   "spectate-lg"]}
     message]]])

(defn- diagnostics-drawer [state health]
  (let [grouped-streams (stream-groups health)
        generated-at-ms (or (:generated-at-ms health) 0)
        now-ms (view-now-ms generated-at-ms)
        transport-age-ms (transport-last-recv-age-ms now-ms health)
        reconnect-count (or (get-in state [:websocket-ui :reconnect-count]) 0)
        reset-counts (merge {:market_data 0 :orders_oms 0 :all 0}
                            (get-in state [:websocket-ui :reset-counts]))
        auto-recover-count (or (get-in state [:websocket-ui :auto-recover-count]) 0)
        timeline (vec (get-in state [:websocket-ui :diagnostics-timeline] []))
        reveal-sensitive? (boolean (get-in state [:websocket-ui :reveal-sensitive?] false))
        copy-status (get-in state [:websocket-ui :copy-status])
        copy-success? (= :success (:kind copy-status))
        show-surface-freshness-cues?
        (boolean (get-in state [:websocket-ui :show-surface-freshness-cues?] false))
        transport-state (get-in health [:transport :state])
        reset-in-progress? (boolean (get-in state [:websocket-ui :reset-in-progress?] false))
        reset-cooldown-until-ms (get-in state [:websocket-ui :reset-cooldown-until-ms])
        reset-cooldown-active? (and (number? reset-cooldown-until-ms)
                                    (> reset-cooldown-until-ms now-ms))
        reset-disabled? (or reset-in-progress?
                            reset-cooldown-active?
                            (contains? #{:connecting :reconnecting} transport-state))
        reset-label (cond
                      reset-in-progress? "Resetting..."
                      reset-cooldown-active?
                      (str "Reset in "
                           (max 1 (js/Math.ceil (/ (max 0 (- reset-cooldown-until-ms now-ms)) 1000)))
                           "s")
                      :else "Reset")
        reconnecting? (contains? #{:connecting :reconnecting} transport-state)
        cooldown-until-ms (get-in state [:websocket-ui :reconnect-cooldown-until-ms])
        cooldown-active? (and (number? cooldown-until-ms)
                              (> cooldown-until-ms now-ms))
        reconnect-disabled? (or reconnecting? cooldown-active?)
        cooldown-remaining-ms (when cooldown-active?
                                (max 0 (- cooldown-until-ms now-ms)))
        reconnect-label (cond
                          reconnecting? "Reconnecting..."
                          cooldown-active? (str "Reconnect in "
                                                (max 1 (js/Math.ceil (/ cooldown-remaining-ms 1000)))
                                                "s")
                          :else "Reconnect now")]
    [:div {:class ["fixed" "inset-0" "z-[220]"]}
     [:button {:type "button"
               :class ["absolute" "inset-0" "bg-black/40"]
               :on {:click [[:actions/close-ws-diagnostics]]}}
      [:span {:class ["sr-only"]} "Close diagnostics"]]
     [:aside {:class ["absolute"
                      "right-0"
                      "top-0"
                      "h-full"
                      "w-full"
                      "max-w-[30rem]"
                      "border-l"
                      "border-base-300"
                      "bg-base-100"
                      "shadow-2xl"
                      "overflow-y-auto"
                      "p-4"
                      "space-y-4"]}
      [:div {:class ["flex" "items-center" "justify-between"]}
       [:h2 {:class ["text-sm" "font-semibold" "uppercase" "tracking-wide"]}
        "Connection diagnostics"]
       [:button.btn.btn-xs.btn-spectate
        {:type "button"
         :on {:click [[:actions/close-ws-diagnostics]]}}
        "Close"]]

      [:div {:class ["flex" "gap-2" "pt-1"]}
       [:button.btn.btn-sm.btn-outline
        {:type "button"
         :on {:click [[:actions/ws-diagnostics-copy]]}}
        "Copy diagnostics"]
       [:button.btn.btn-sm.btn-warning
        {:type "button"
         :disabled reconnect-disabled?
         :on {:click [[:actions/ws-diagnostics-reconnect-now]]}}
        reconnect-label]]

      [:div {:class ["grid" "grid-cols-1" "gap-2" "sm:grid-cols-3"]}
       (hover-tooltip
        "Unsubscribe and resubscribe active market-data streams only. Use this when order book or trades look stuck."
        [:button.btn.btn-sm.btn-outline
         {:type "button"
          :title "Unsubscribe and resubscribe active market-data streams only. Use this when order book or trades look stuck."
          :disabled reset-disabled?
          :on {:click [[:actions/ws-diagnostics-reset-market-subscriptions]]}}
         (if (= reset-label "Reset")
           "Reset market subs"
           reset-label)])
       (hover-tooltip
        "Unsubscribe and resubscribe active Orders/OMS streams only, without forcing a full websocket reconnect."
        [:button.btn.btn-sm.btn-outline
         {:type "button"
          :title "Unsubscribe and resubscribe active Orders/OMS streams only, without forcing a full websocket reconnect."
          :disabled reset-disabled?
          :on {:click [[:actions/ws-diagnostics-reset-orders-subscriptions]]}}
         (if (= reset-label "Reset")
           "Reset OMS subs"
           reset-label)])
       (hover-tooltip
        "Run both market-data and Orders/OMS subscription resets in one pass, without a full reconnect."
        [:button.btn.btn-sm.btn-spectate
         {:type "button"
          :title "Run both market-data and Orders/OMS subscription resets in one pass, without a full reconnect."
          :disabled reset-disabled?
          :on {:click [[:actions/ws-diagnostics-reset-all-subscriptions]]}}
         (if (= reset-label "Reset")
           "Reset all subs"
           reset-label)])]

      [:div {:class ["flex" "items-center" "justify-between" "text-xs" "text-base-content/70"]}
       [:span "Sensitive values are masked by default"]
       [:button.btn.btn-xs.btn-spectate
        {:type "button"
         :on {:click [[:actions/toggle-ws-diagnostics-sensitive]]}}
        (if reveal-sensitive? "Hide sensitive" "Reveal sensitive")]]

      (when copy-status
        [:div {:class (if copy-success?
                        ["rounded" "border" "border-success/40" "bg-success/10" "px-3" "py-2" "text-xs" "text-success"]
                        ["rounded" "border" "border-warning/40" "bg-warning/10" "px-3" "py-2" "text-xs" "text-warning"])}
         (:message copy-status)])

      (when-let [fallback-json (:fallback-json copy-status)]
        [:pre {:class ["max-h-40"
                       "overflow-auto"
                       "rounded"
                       "border"
                       "border-base-300"
                       "bg-base-200/50"
                       "p-2"
                       "text-xs"
                       "leading-5"
                       "break-all"]}
         fallback-json])

      [:section {:class ["space-y-2"]}
       [:h3 {:class ["text-xs" "font-semibold" "uppercase" "tracking-wide" "text-base-content/70"]}
        "Diagnostics"]
       (surface-freshness-toggle show-surface-freshness-cues?)
       [:div {:class ["rounded" "border" "border-base-300" "bg-base-200/50" "p-3" "space-y-1.5" "text-xs"]}
        [:div {:class ["flex" "justify-between"]}
         [:span "App version"]
         [:span (or default-app-version "n/a")]]
        [:div {:class ["flex" "justify-between"]}
         [:span "Build id"]
         [:span (or (app-build-id) "n/a")]]
        [:div {:class ["flex" "justify-between"]}
         [:span "Reconnect count"]
         [:span (str reconnect-count)]]
        [:div {:class ["flex" "justify-between"]}
         [:span "Reset count (market/oms/all)"]
         [:span (str (get reset-counts :market_data 0)
                     "/"
                     (get reset-counts :orders_oms 0)
                     "/"
                     (get reset-counts :all 0))]]
        [:div {:class ["flex" "justify-between"]}
         [:span "Auto-recover count"]
         [:span (str auto-recover-count)]]]
       [:div {:class ["rounded" "border" "border-base-300" "bg-base-200/50" "p-3" "space-y-1.5" "text-xs"]}
        [:div {:class ["font-semibold" "uppercase" "tracking-wide" "text-base-content/70"]}
         "Recent timeline"]
        (if (seq timeline)
          (for [entry (take-last diagnostics-timeline-limit timeline)]
            ^{:key (str (:event entry) "|" (:at-ms entry))}
            (let [age-ms (when (number? (:at-ms entry))
                           (max 0 (- now-ms (:at-ms entry))))
                  details* (diagnostics-display-value reveal-sensitive? (:details entry))]
              [:div {:class ["space-y-0.5"]}
               [:div {:class ["flex" "justify-between"]}
                [:span (timeline-event-label (:event entry))]
                [:span (if (number? age-ms)
                         (str (format-age-ms age-ms) " ago")
                         "n/a")]]
               (when (map? details*)
                 [:div {:class ["text-base-content/60" "break-all"]}
                  (pr-str details*)])]))
          [:div {:class ["text-base-content/70"]}
           "No events yet"])]] 

      (market-projection-section health reveal-sensitive?)

      [:section {:class ["space-y-2"]}
       [:h3 {:class ["text-xs" "font-semibold" "uppercase" "tracking-wide" "text-base-content/70"]}
        "Transport"]
       [:div {:class ["rounded" "border" "border-base-300" "bg-base-200/50" "p-3" "space-y-1.5" "text-xs"]}
        [:div {:class ["flex" "justify-between"]}
         [:span "State"]
         [:span (status-label (get-in health [:transport :state]))]]
        [:div {:class ["flex" "justify-between"]}
         [:span "Freshness"]
         [:span (status-label (get-in health [:transport :freshness]))]]
        [:div {:class ["flex" "justify-between"]}
         [:span "Expected traffic"]
         [:span (if (get-in health [:transport :expected-traffic?]) "yes" "no")]]
        [:div {:class ["flex" "justify-between"]}
         [:span "Last message age"]
         [:span (format-age-ms transport-age-ms)]]
        [:div {:class ["flex" "justify-between"]}
         [:span "Reconnect attempts"]
         [:span (str (or (get-in health [:transport :attempt]) 0))]]
        [:div {:class ["flex" "justify-between"]}
         [:span "Last close"]
         [:span {:class ["ml-3" "text-right"]} (format-last-close health)]]]]

      [:section {:class ["space-y-2"]}
       [:h3 {:class ["text-xs" "font-semibold" "uppercase" "tracking-wide" "text-base-content/70"]}
        "Group health"]
       [:div {:class ["rounded" "border" "border-base-300" "bg-base-200/50" "p-3" "space-y-2"]}
        (for [group group-priority]
          ^{:key (name group)}
          [:div {:class ["flex" "items-center" "justify-between" "text-xs"]}
           [:span (group-title group)]
           (status-chip (get-in health [:groups group :worst-status]))])]]

      [:section {:class ["space-y-2"]}
       [:h3 {:class ["text-xs" "font-semibold" "uppercase" "tracking-wide" "text-base-content/70"]}
        "Streams"]
       (if (seq grouped-streams)
         (for [[group streams] (sort-by (fn [[group _]]
                                          (get group-rank (or group :account) 99))
                                        grouped-streams)]
           ^{:key (name (or group :account))}
           [:details {:class ["rounded" "border" "border-base-300" "bg-base-200/50"] :open true}
            [:summary {:class ["cursor-pointer" "px-3" "py-2" "text-xs" "font-semibold" "uppercase" "tracking-wide"]}
             (str (group-title group) " (" (count streams) ")")]
            [:div {:class ["px-3" "pb-3" "space-y-2"]}
             (for [{:keys [sub-key topic status last-payload-at-ms stale-threshold-ms descriptor last-seq seq-gap-detected? seq-gap-count last-gap]} streams]
               ^{:key (str topic "|" (pr-str sub-key))}
               (let [sub-key* (diagnostics-display-value reveal-sensitive? sub-key)
                     age-ms (stream-age-ms now-ms {:last-payload-at-ms last-payload-at-ms})
                     descriptor* (diagnostics-display-value reveal-sensitive? descriptor)
                     last-gap* (diagnostics-display-value reveal-sensitive? last-gap)]
                 [:div {:class ["rounded" "border" "border-base-300" "bg-base-100" "p-2" "space-y-1"]}
                  [:div {:class ["flex" "items-center" "justify-between" "text-xs"]}
                   [:code (or topic "unknown")]
                   (status-chip status)]
                  [:div {:class ["text-xs" "text-base-content/70"]}
                   (str "Age: " (format-age-ms age-ms)
                        " | Threshold: " (threshold-label stale-threshold-ms))]
                  [:div {:class ["text-xs" "text-base-content/70"]}
                   (str "Seq: " (if (number? last-seq) last-seq "n/a")
                        " | Gap: " (if seq-gap-detected?
                                     (str "yes (" (or seq-gap-count 0) ")")
                                     "no"))]
                  (when (map? last-gap*)
                    [:div {:class ["text-xs" "text-base-content/60" "break-all"]}
                     (str "Last gap: " (pr-str last-gap*))])
                  [:div {:class ["text-xs" "text-base-content/70" "break-all"]}
                   (str "Subscription: " (pr-str sub-key*))]
                 [:div {:class ["text-xs" "text-base-content/70" "break-all"]}
                   (str "Descriptor: " (pr-str descriptor*))]]))]])
         [:div {:class ["rounded" "border" "border-base-300" "bg-base-200/50" "p-3" "text-xs" "text-base-content/70"]}
          "No active stream diagnostics available."])]

      (market-projection-recent-flushes-section health now-ms reveal-sensitive?)]]))

(defn- mobile-nav-icon
  [kind active?]
  (let [icon-classes (into ["h-[18px]" "w-[18px]"]
                           (if active?
                             ["text-[#61e6cf]"]
                             ["text-trading-text-secondary"]))]
    (case kind
      :markets
      [:svg {:viewBox "0 0 20 20" :fill "currentColor" :class icon-classes}
       [:path {:d "M3 11a1 1 0 011-1h1a1 1 0 011 1v5H3v-5z"}]
       [:path {:d "M8 7a1 1 0 011-1h1a1 1 0 011 1v9H8V7z"}]
       [:path {:d "M13 4a1 1 0 011-1h1a1 1 0 011 1v12h-3V4z"}]]

      :trade
      [:svg {:viewBox "0 0 20 20" :fill "currentColor" :class icon-classes}
       [:path {:fill-rule "evenodd"
               :clip-rule "evenodd"
               :d "M10 3a7 7 0 100 14 7 7 0 000-14zm1 3a1 1 0 10-2 0v4c0 .265.105.52.293.707l2.5 2.5a1 1 0 001.414-1.414L11 9.586V6z"}]]

      :account
      [:svg {:viewBox "0 0 20 20" :fill "currentColor" :class icon-classes}
       [:path {:fill-rule "evenodd"
               :clip-rule "evenodd"
               :d "M10 9a3 3 0 100-6 3 3 0 000 6zm-6 8a6 6 0 1112 0H4z"}]]

      nil)))

(defn- mobile-nav-button
  [{:keys [label active? click icon-kind data-role]}]
  [:button {:type "button"
            :class (into ["inline-flex"
                          "h-10"
                          "items-center"
                          "justify-center"
                          "gap-1.5"
                          "whitespace-nowrap"
                          "px-1"
                          "py-1"
                          "text-sm"
                          "font-medium"
                          "transition-colors"]
                         (if active?
                           ["text-[#61e6cf]"]
                           ["text-trading-text-secondary" "hover:text-trading-text"]))
            :on {:click click}
            :data-role data-role}
   (mobile-nav-icon icon-kind active?)
   [:span label]])

(defn- mobile-bottom-nav
  [state]
  (let [route (get-in state [:router :path] "/trade")
        trade-route? (str/starts-with? route "/trade")
        portfolio-route? (str/starts-with? route "/portfolio")
        mobile-surface (trade-layout-actions/normalize-trade-mobile-surface
                        (get-in state [:trade-ui :mobile-surface]))
        markets-active? (and trade-route?
                             (contains? trade-layout-actions/market-mobile-surfaces
                                        mobile-surface))
        account-active? (or portfolio-route?
                            (and trade-route? (= mobile-surface :account)))
        trade-active? (and trade-route? (= mobile-surface :ticket))]
    [:div {:class ["lg:hidden"
                   "w-full"
                   "bg-[#07161b]/95"
                   "backdrop-blur"
                   "app-shell-gutter"
                   "pt-1"
                   "pb-[calc(0.25rem+env(safe-area-inset-bottom))]"]
           :data-role "mobile-bottom-nav"}
      [:div {:class ["grid" "grid-cols-3" "gap-2"]}
      (mobile-nav-button
       {:label "Markets"
        :active? markets-active?
        :click (if trade-route?
                 [[:actions/select-trade-mobile-surface :chart]]
                 [[:actions/navigate "/trade"]])
        :icon-kind :markets
        :data-role "mobile-bottom-nav-markets"})
      (mobile-nav-button
       {:label "Trade"
        :active? trade-active?
        :click (if trade-route?
                 [[:actions/select-trade-mobile-surface :ticket]]
                 [[:actions/navigate "/trade"]])
        :icon-kind :trade
        :data-role "mobile-bottom-nav-trade"})
      (mobile-nav-button
       {:label "Account"
        :active? account-active?
        :click (if trade-route?
                 [[:actions/select-trade-mobile-surface :account]]
                 [[:actions/navigate "/portfolio"]])
        :icon-kind :account
        :data-role "mobile-bottom-nav-account"})]]))

(defn footer-view [state]
  (let [health (get-in state [:websocket :health] {})
        {:keys [status active-bars label tooltip]} (connection-meter-model health)
        {:keys [border bg label-text bar-active]} (connection-meter-tone status active-bars)
        diagnostics-open? (boolean (get-in state [:websocket-ui :diagnostics-open?] false))
        footer-z-class (if diagnostics-open? "z-[260]" "z-40")
        banner (banner-model state health)]
    [:footer {:class ["fixed" "inset-x-0" "bottom-0" footer-z-class "isolate" "w-full" "shrink-0" "bg-base-200" "border-t" "border-base-300"]
              :data-parity-id "footer"}
     (mobile-bottom-nav state)
     [:div {:class ["hidden" "lg:block" "w-full" "app-shell-gutter" "py-2" "relative"]}
      (when banner
        [:div {:class (into ["mb-2"
                             "rounded"
                             "border"
                             "px-3"
                             "py-2"
                             "text-xs"
                             "font-medium"]
                            (:class banner))}
         (:message banner)])
      [:div {:class ["flex" "justify-between" "items-center"]}
       [:div {:class ["flex" "items-center" "space-x-3"]}
        [:button {:type "button"
                  :class ["inline-flex"
                          "items-center"
                          "gap-2"
                          "rounded"
                          "border"
                          "px-2.5"
                          "py-1"
                          "text-xs"
                          "font-medium"
                          "transition-colors"
                          border
                          bg
                          label-text]
                  :on {:click [[:actions/toggle-ws-diagnostics]]}
                  :title tooltip
                  :data-role "footer-connection-meter-button"}
         (signal-meter-bars active-bars bar-active)
         [:span label]]]

       [:div {:class ["flex" "space-x-6"]}
        [:a {:class footer-link-classes
             :href "#"}
         "Docs"]
        [:a {:class footer-link-classes
             :href "#"}
         "Support"]
        [:a {:class footer-link-classes
             :href "#"}
         "Terms"]
        [:a {:class footer-link-classes
             :href "#"}
         "Privacy Policy"]]]

      (when diagnostics-open?
        (diagnostics-drawer state health))]]))
