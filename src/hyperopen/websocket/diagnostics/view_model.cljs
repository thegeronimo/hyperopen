(ns hyperopen.websocket.diagnostics.view-model
  (:require [clojure.string :as str]
            [hyperopen.trade.layout-actions :as trade-layout-actions]
            [hyperopen.websocket.diagnostics.catalog :as catalog]
            [hyperopen.websocket.diagnostics.policy :as policy]
            [hyperopen.websocket.diagnostics-sanitize :as diagnostics-sanitize]))

(def footer-links
  [])

(def ^:private developer-event-preview-limit 3)

(def ^:private developer-stream-preview-limit 5)

(defn format-age-ms
  [age-ms]
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

(defn- display-value
  [reveal-sensitive? value]
  (if reveal-sensitive?
    value
    (diagnostics-sanitize/sanitize-value :mask value)))

(defn- display-string
  [value fallback]
  (let [text (some-> value str)]
    (if (str/blank? text)
      fallback
      text)))

(defn- timeline-rows
  [timeline limit now-ms reveal-sensitive?]
  (->> (vec (or timeline []))
       (take-last limit)
       (mapv (fn [entry]
               (let [age-ms (when (number? (:at-ms entry))
                              (max 0 (- now-ms (:at-ms entry))))
                     details* (display-value reveal-sensitive? (:details entry))]
                 {:key (str (:event entry) "|" (:at-ms entry))
                  :event-label (catalog/timeline-event-label (:event entry))
                  :age-label (if (number? age-ms)
                               (str (format-age-ms age-ms) " ago")
                               "n/a")
                  :details-text (when (map? details*)
                                  (pr-str details*))})))))

(defn- stream-sort-key
  [{:keys [group topic sub-key]}]
  [(catalog/group-rank (or group :account))
   (str topic)
   (pr-str sub-key)])

(def ^:private state-copy
  {:online {:title "Everything is live"
            :reason "All data is streaming normally."}
   :delayed {:title "Market data is behind"
             :reason "One live stream is approaching its stale threshold. We'll refresh it automatically."}
   :partial {:title "Some streams are lagging"
             :reason "Order updates are live, but some data streams have gone quiet. Trading is still safe."}
   :reconnecting {:title "Reconnecting..."
                  :reason "Attempting to restore the data connection. Your open orders are safe on the exchange."}
   :offline {:title "Disconnected"
             :reason "We couldn't reach HyperOpen servers. Check your network, then reconnect."}})

(def ^:private group-display-labels
  {:orders_oms "Orders"
   :market_data "Market data"
   :account "Account"})

(defn- display-group-label
  [group]
  (get group-display-labels group (catalog/group-title group)))

(defn- popover-state
  [health meter]
  (let [status (catalog/normalize-status (:status meter))
        transport-state (get-in health [:transport :state])
        transport-freshness (catalog/normalize-status
                             (get-in health [:transport :freshness]))
        active-bars (:active-bars meter)]
    (cond
      (or (= :reconnecting status)
          (= :reconnecting transport-freshness)
          (contains? #{:connecting :reconnecting} transport-state))
      :reconnecting

      (or (= :offline status)
          (= :unknown status)
          (= :offline transport-freshness)
          (= :disconnected transport-state)
          (zero? (or active-bars 0)))
      :offline

      (and (= :delayed status)
           (<= (or active-bars 0) 2))
      :partial

      (= :delayed status)
      :delayed

      :else
      :online)))

(defn- status->group-state
  [popover-state status]
  (let [status* (catalog/normalize-status status)]
    (cond
      (or (= :offline popover-state)
          (= :reconnecting popover-state)
          (contains? #{:offline :reconnecting :unknown} status*))
      :offline

      (= :delayed status*)
      :delayed

      :else
      :live)))

(defn- display-topic
  [topic]
  (case (some-> topic str str/lower-case)
    "l2book" "Order book"
    "trades" "Trades"
    "openorders" "Open orders"
    "userfills" "Fills"
    "clearinghousestate" "Account state"
    (display-string topic "Unknown stream")))

(defn- stream-key-text
  [sub-key]
  (let [parts (if (sequential? sub-key)
                (rest sub-key)
                [sub-key])]
    (or (some (fn [part]
                (let [text (display-string part "")]
                  (when-not (str/blank? text)
                    text)))
              parts)
        "n/a")))

(defn- stream-detail
  [{:keys [display-topic age-text]}]
  (str display-topic " is " age-text " behind"))

(defn- delayed-group-detail
  [streams]
  (if-let [stream (first (filter #(= :delayed (:status %)) streams))]
    (stream-detail stream)
    "Streams are behind"))

(defn- group-detail
  [popover-state group group-state streams]
  (case group-state
    :offline (if (= :reconnecting popover-state) "Waiting" "Disconnected")
    :delayed (delayed-group-detail streams)
    :live (if (= :market_data group) "Streaming" "Updates on activity")
    "Updates on activity"))

(defn- group-models
  [health popover-state stream-groups]
  (let [streams-by-group (into {}
                               (map (juxt :group :streams))
                               stream-groups)]
    (mapv (fn [group]
            (let [status (catalog/normalize-status
                          (get-in health [:groups group :worst-status]))
                  state* (status->group-state popover-state status)]
              {:key (name group)
               :group group
               :label (display-group-label group)
               :state state*
               :status-label (case state*
                               :offline "Offline"
                               :delayed "Delayed"
                               "Live")
               :detail (group-detail popover-state
                                      group
                                      state*
                                      (get streams-by-group group []))}))
          catalog/group-order)))

(defn- popover-last-update-label
  [health now-ms]
  (let [age-ms (policy/transport-last-recv-age-ms now-ms health)]
    (str (format-age-ms age-ms) " ago")))

(defn- summary-counter-rows
  [state]
  (let [reset-counts (merge {:market_data 0 :orders_oms 0 :all 0}
                            (get-in state [:websocket-ui :reset-counts]))]
    [{:label "Reconnect count"
      :value (str (or (get-in state [:websocket-ui :reconnect-count]) 0))}
     {:label "Reset count"
      :value (str (get reset-counts :market_data 0)
                  " / "
                  (get reset-counts :orders_oms 0)
                  " / "
                  (get reset-counts :all 0))}
     {:label "Auto-recover count"
      :value (str (or (get-in state [:websocket-ui :auto-recover-count]) 0))}]))

(defn- popover-stream-groups
  [health now-ms]
  (let [rows (->> (get health :streams {})
                  (map (fn [[sub-key stream]]
                         (assoc stream :sub-key sub-key)))
                  (sort-by stream-sort-key)
                  vec)]
    (->> rows
         (group-by (fn [{:keys [group]}]
                     (catalog/normalize-group (or group :account))))
         (sort-by (fn [[group _]]
                    (catalog/group-rank group)))
         (mapv (fn [[group streams]]
                 {:key (name group)
                  :group group
                  :title (display-group-label group)
                  :count (count streams)
                  :streams
                  (mapv (fn [{:keys [sub-key topic status last-payload-at-ms]}]
                          (let [status* (catalog/normalize-status status)
                                age-ms (policy/stream-age-ms
                                        now-ms
                                        {:last-payload-at-ms last-payload-at-ms})]
                            {:key (str topic "|" (pr-str sub-key))
                             :topic (or topic "unknown")
                             :display-topic (display-topic topic)
                             :stream-key-text (stream-key-text (display-value false sub-key))
                             :status status*
                             :state (case status*
                                      :offline :offline
                                      :reconnecting :offline
                                      :delayed :delayed
                                      :event-driven :event
                                      :idle :event
                                      :live :live
                                      :unknown :offline
                                      :live)
                             :status-label (case status*
                                             :offline "Off"
                                             :reconnecting "Off"
                                             :delayed "Delayed"
                                             :event-driven "Waiting"
                                             :idle "Waiting"
                                             :live "Live"
                                             :unknown "Off"
                                             "Live")
                             :age-text (format-age-ms age-ms)}))
                        streams)})))))

(defn- popover-stream-preview
  [stream-groups]
  (let [streams (mapcat :streams stream-groups)
        total-count (reduce + 0 (map :count stream-groups))]
    (when (seq streams)
      {:key "streams"
       :title "Streams"
       :count total-count
       :streams (vec (take developer-stream-preview-limit streams))})))

(defn- diagnostics-model
  [state health meter {:keys [app-version
                              build-id
                              diagnostics-timeline-limit
                              display-now-ms
                              effective-now-ms]}]
  (let [timeline (vec (get-in state [:websocket-ui :diagnostics-timeline] []))
        reveal-sensitive? false
        copy-status (get-in state [:websocket-ui :copy-status])
        reconnect-availability (policy/reconnect-availability state health effective-now-ms)
        state* (popover-state health meter)
        stream-groups (popover-stream-groups health display-now-ms)
        stream-preview (popover-stream-preview stream-groups)
        copy (get state-copy state*)]
    {:state state*
     :tone (case state*
             :online :ok
             :offline :err
             :warn)
     :title (:title copy)
     :reason (:reason copy)
     :last-update-label (popover-last-update-label health display-now-ms)
     :developer-open? (not= :online state*)
     :reconnect-control reconnect-availability
     :show-surface-freshness-cues?
     (boolean (get-in state [:websocket-ui :show-surface-freshness-cues?] false))
     :copy-status (when copy-status
                    {:kind (:kind copy-status)
                     :message (:message copy-status)
                     :fallback-json (:fallback-json copy-status)})
     :counter-rows (summary-counter-rows state)
     :timeline (->> (timeline-rows timeline diagnostics-timeline-limit display-now-ms reveal-sensitive?)
                    (take-last developer-event-preview-limit)
                    vec)
     :groups (group-models health state* stream-groups)
     :stream-groups (if stream-preview [stream-preview] [])}))

(defn- mobile-nav-model
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
    {:items [{:label "Markets"
              :active? markets-active?
              :click-action (if trade-route?
                              [[:actions/select-trade-mobile-surface :chart]]
                              [[:actions/navigate "/trade"]])
              :icon-kind :markets
              :data-role "mobile-bottom-nav-markets"}
             {:label "Trade"
              :active? trade-active?
              :click-action (if trade-route?
                              [[:actions/select-trade-mobile-surface :ticket]]
                              [[:actions/navigate "/trade"]])
              :icon-kind :trade
              :data-role "mobile-bottom-nav-trade"}
             {:label "Account"
              :active? account-active?
              :click-action (if trade-route?
                              [[:actions/select-trade-mobile-surface :account]]
                              [[:actions/navigate "/portfolio"]])
              :icon-kind :account
              :data-role "mobile-bottom-nav-account"}]}))

(defn footer-view-model
  ([state]
   (footer-view-model state {}))
  ([state {:keys [app-version
                  build-id
                  wall-now-ms
                  diagnostics-timeline-limit
                  network-hint]
           :or {app-version nil
                build-id nil
                wall-now-ms nil
                diagnostics-timeline-limit 25
                network-hint nil}}]
   (let [health (get-in state [:websocket :health] {})
         generated-at-ms (:generated-at-ms health)
         display-now-ms (policy/display-now-ms generated-at-ms wall-now-ms)
         effective-now-ms (policy/effective-now-ms generated-at-ms wall-now-ms)
         meter (policy/connection-meter-model health
                                              {:wall-now-ms display-now-ms
                                               :network-hint network-hint})
         diagnostics-open? (boolean (get-in state [:websocket-ui :diagnostics-open?] false))
         banner (policy/banner-model state health)
         diagnostics (when diagnostics-open?
                       (diagnostics-model state
                                          health
                                          meter
                                          {:app-version app-version
                                           :build-id build-id
                                           :diagnostics-timeline-limit diagnostics-timeline-limit
                                           :display-now-ms display-now-ms
                                           :effective-now-ms effective-now-ms}))]
     (cond-> {:diagnostics-open? diagnostics-open?
              :connection-meter
              (assoc meter
                     :diagnostics-open? diagnostics-open?
                     :show-surface-freshness-cues?
                     (boolean (get-in state
                                      [:websocket-ui :show-surface-freshness-cues?]
                                      false)))
              :mobile-nav (mobile-nav-model state)
              :footer-links footer-links}
       banner
       (assoc :banner banner)

       diagnostics
       (assoc :diagnostics diagnostics)))))
