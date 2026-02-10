(ns hyperopen.views.footer-view
  (:require [clojure.string :as str]
            [hyperopen.websocket.diagnostics-sanitize :as diagnostics-sanitize]))

(def footer-link-classes
  ["text-sm" "text-trading-text" "hover:text-primary" "transition-colors"])

(def ^:private default-app-version
  "0.1.0")

(def ^:private diagnostics-timeline-limit
  50)

(def ^:private neutral-statuses
  #{:idle :n-a nil})

(def ^:private group-priority
  [:orders_oms :market_data :account])

(def ^:private group-rank
  {:orders_oms 0
   :market_data 1
   :account 2})

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
        wall-now-ms (.now js/Date)]
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

(defn- threshold-label [stale-threshold-ms]
  (if (number? stale-threshold-ms)
    (str stale-threshold-ms " ms")
    "n/a"))

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
        "WebSocket diagnostics"]
       [:button.btn.btn-xs.btn-ghost
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
       [:button.btn.btn-sm.btn-outline
        {:type "button"
         :disabled reset-disabled?
         :on {:click [[:actions/ws-diagnostics-reset-market-subscriptions]]}}
        (if (= reset-label "Reset")
          "Reset market subs"
          reset-label)]
       [:button.btn.btn-sm.btn-outline
        {:type "button"
         :disabled reset-disabled?
         :on {:click [[:actions/ws-diagnostics-reset-orders-subscriptions]]}}
        (if (= reset-label "Reset")
          "Reset OMS subs"
          reset-label)]
       [:button.btn.btn-sm.btn-ghost
        {:type "button"
         :disabled reset-disabled?
         :on {:click [[:actions/ws-diagnostics-reset-all-subscriptions]]}}
        (if (= reset-label "Reset")
          "Reset all subs"
          reset-label)]]

      [:div {:class ["flex" "items-center" "justify-between" "text-xs" "text-base-content/70"]}
       [:span "Sensitive values are masked by default"]
       [:button.btn.btn-xs.btn-ghost
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
          "No active stream diagnostics available."])]]]))

(defn footer-view [state]
  (let [health (get-in state [:websocket :health] {})
        {:keys [status source]} (dominant-pill-state health)
        {:keys [border bg text]} (status-tone status)
        diagnostics-open? (boolean (get-in state [:websocket-ui :diagnostics-open?] false))
        footer-z-class (if diagnostics-open? "z-[260]" "z-40")
        banner (banner-model state health)]
    [:footer {:class ["fixed" "inset-x-0" "bottom-0" footer-z-class "isolate" "w-full" "shrink-0" "bg-base-200" "border-t" "border-base-300"]}
     [:div {:class ["w-full" "app-shell-gutter" "py-2" "relative"]}
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
                          "px-3"
                          "py-1"
                          "text-xs"
                          "font-semibold"
                          "uppercase"
                          "tracking-wide"
                          "transition-colors"
                          border
                          bg
                          text]
                  :on {:click [[:actions/toggle-ws-diagnostics]]}}
         [:span "WebSocket:"]
         [:span (status-label status)]
         [:span {:class ["normal-case" "font-medium"]} (str "(" (source-label source) ")")]]]

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
