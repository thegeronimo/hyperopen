(ns hyperopen.views.footer.diagnostics-drawer)

(def ^:private banner-tone-classes
  {:warning ["border-warning/40" "bg-warning/10" "text-warning"]
   :error ["border-error/40" "bg-error/10" "text-error"]
   :info ["border-info/40" "bg-info/10" "text-info"]})

(defn banner-classes
  [tone]
  (get banner-tone-classes tone ["border-base-300" "bg-base-200/50" "text-base-content"]))

(defn- icon-close
  []
  [:svg {:width "12" :height "12" :viewBox "0 0 12 12" :fill "none" :aria-hidden "true"}
   [:path {:d "M2.5 2.5 L9.5 9.5 M9.5 2.5 L2.5 9.5"
           :stroke "currentColor"
           :stroke-width "1.4"
           :stroke-linecap "round"}]])

(defn- icon-copy
  []
  [:svg {:width "12" :height "12" :viewBox "0 0 12 12" :fill "none" :aria-hidden "true"}
   [:rect {:x "3.5" :y "3.5" :width "6" :height "6.5" :rx "1"
           :stroke "currentColor" :stroke-width "1.1"}]
   [:path {:d "M2 7.5 V2.5 A1 1 0 0 1 3 1.5 H7.5"
           :stroke "currentColor" :stroke-width "1.1"}]])

(defn- icon-refresh
  []
  [:svg {:width "12" :height "12" :viewBox "0 0 12 12" :fill "none" :aria-hidden "true"}
   [:path {:d "M10 4 A4 4 0 1 0 10.5 7.5"
           :stroke "currentColor"
           :stroke-width "1.3"
           :stroke-linecap "round"
           :fill "none"}]
   [:path {:d "M7 3.5 L10 4 L10 1"
           :stroke "currentColor"
           :stroke-width "1.3"
           :stroke-linecap "round"
           :stroke-linejoin "round"
           :fill "none"}]])

(defn- tone-class
  [tone]
  (case tone
    :ok "tone-ok"
    :err "tone-err"
    "tone-warn"))

(defn- copy-button
  [copy-status]
  (let [copied? (= :success (:kind copy-status))
        attrs {:type "button"
               :class ["hx-btn" "ghost" (when copied? "done")]
               :on {:click [[:actions/ws-diagnostics-copy]]}}
        children (if copied?
                   ["✓" [:span "Copied"]]
                   [(icon-copy) [:span "Copy diagnostics"]])]
    (into [:button attrs] children)))

(defn- reconnect-button
  [{:keys [state reconnect-control]}]
  (when (not= :online state)
    (let [reconnecting? (:reconnecting? reconnect-control)]
      (into
       [:button {:type "button"
                 :class ["hx-btn" "primary"]
                 :disabled (:disabled? reconnect-control)
                 :on {:click [[:actions/ws-diagnostics-reconnect-now]]}
                 :data-role "connection-diagnostics-reconnect"}]
       (if reconnecting?
         [[:span {:class ["hx-spin"]
                  :data-role "connection-diagnostics-reconnect-spinner"}]
          [:span (:label reconnect-control)]]
         [(icon-refresh)
          [:span (:label reconnect-control)]])))))

(defn- group-card
  [{:keys [key label state status-label detail]}]
  ^{:key key}
  [:div {:class ["hx-group" (str "tone-" (name state))]}
   [:div {:class ["hx-group-top"]}
    [:span {:class ["hx-group-name"]} label]
    [:span {:class ["hx-group-chip" (str "tone-" (name state))]}
     status-label]]
   [:div {:class ["hx-group-detail"]} detail]])

(defn- freshness-toggle
  [checked?]
  [:div {:class ["hx-toggle-row"]}
   [:div {:class ["hx-toggle-text"]}
    [:div {:class ["hx-toggle-title"]} "Show freshness labels"]
    [:div {:class ["hx-toggle-sub"]} "Adds small Updated Ns ago tags across trading views."]]
   [:button {:type "button"
             :class ["hx-toggle" (when checked? "on")]
             :role "switch"
             :aria-checked (if checked? "true" "false")
             :aria-label "Show freshness labels"
             :data-role "surface-freshness-toggle"
             :on {:click [[:actions/toggle-show-surface-freshness-cues]]}}
    [:span {:class ["hx-toggle-knob"]}]]])

(defn- counter-row
  [{:keys [label value]}]
  ^{:key label}
  [:div {:class ["hx-dev-row"]}
   [:span {:class ["hx-dev-k"]} label]
   [:span {:class ["hx-dev-v" "o-mono"]} value]])

(defn- timeline-event-class
  [event-label]
  (let [event (or event-label "unknown")]
    (cond
      (.includes event "connected") "ev-connected"
      (.includes event "reconnecting") "ev-reconnecting"
      (.includes event "offline") "ev-disconnected"
      (.includes event "reset") "ev-delayed"
      :else "ev-stream")))

(defn- timeline-row
  [{:keys [key event-label age-label details-text]}]
  ^{:key key}
  [:div {:class ["hx-tl-row" (timeline-event-class event-label)]
         :data-role "connection-diagnostics-dev-event"}
   [:span {:class ["hx-tl-dot"]}]
   [:span {:class ["hx-tl-event"]}
    event-label
    (when details-text
      [:span {:class ["hx-tl-detail"]} (str " - " details-text)])]
   [:span {:class ["hx-tl-time" "o-mono"]} age-label]])

(defn- stream-chip
  [{:keys [state status-label]}]
  [:span {:class ["hx-stream-chip" (str "tone-" (name state))]}
   status-label])

(defn- stream-row
  [{:keys [key display-topic stream-key-text age-text] :as stream}]
  ^{:key key}
  [:div {:class ["hx-stream" (str "tone-" (name (:state stream)))]
         :data-role "connection-diagnostics-dev-stream"}
   [:div {:class ["hx-stream-l"]}
    [:span {:class ["hx-stream-name" "o-mono"]} display-topic]
    [:span {:class ["hx-stream-key" "o-mono"]} stream-key-text]]
   [:div {:class ["hx-stream-r"]}
    [:span {:class ["hx-stream-age" "o-mono"]} age-text]
    (stream-chip stream)]])

(defn- stream-group
  [{:keys [key title count streams]}]
  ^{:key key}
  [:div {:class ["hx-dev-stream-group"]}
   [:div {:class ["hx-dev-section"]} (str title " (" count ")")]
   [:div {:class ["hx-streams"]}
    (for [stream streams]
      (stream-row stream))]])

(defn- developer-details
  [{:keys [developer-open? counter-rows timeline stream-groups]}]
  [:details {:class ["hx-dev"]
             :open developer-open?}
   [:summary {:class ["hx-dev-head" (when developer-open? "is-open")]
              :aria-expanded (if developer-open? "true" "false")
              :data-role "connection-diagnostics-dev-toggle"}
    [:span {:class ["hx-dev-chev"]} "v"]
    [:span "Developer details"]
    [:span {:class ["hx-dev-hint"]} "share with support"]]
   [:div {:class ["hx-dev-body"]}
    (for [row counter-rows]
      (counter-row row))
    [:div {:class ["hx-dev-section"]} "Recent events"]
    [:div {:class ["hx-dev-timeline"]}
     (if (seq timeline)
       (for [entry timeline]
         (timeline-row entry))
       [:div {:class ["hx-dev-empty"]} "No events yet"])]
    (for [group stream-groups]
      (stream-group group))
    [:div {:class ["hx-dev-hint-row"]}
     "Full redacted diagnostics are included in the "
     [:strong "Copy diagnostics"]
     " payload."]]])

(defn- copy-status-view
  [copy-status]
  (when (= :error (:kind copy-status))
    [:div {:class ["hx-copy-error"]}
     [:div (:message copy-status)]
     (when-let [fallback-json (:fallback-json copy-status)]
       [:pre fallback-json])]))

(defn render
  [diagnostics]
  (let [tone (tone-class (:tone diagnostics))]
    [:div {:class ["hx-pop-layer"]
           :data-role "connection-diagnostics-layer"}
     [:button {:type "button"
               :class ["hx-pop-backdrop"]
               :aria-label "Close diagnostics"
               :data-role "connection-diagnostics-backdrop"
               :on {:click [[:actions/close-ws-diagnostics]]}}]
     [:div {:class ["hx-pop" tone]
            :role "dialog"
            :aria-label "Connection status"
            :tabindex "0"
            :autofocus true
            :data-role "connection-diagnostics-popover"
            :on {:keydown [[:actions/handle-ws-diagnostics-keydown [:event/key]]]}}
      [:div {:class ["hx-pop-head"]}
       [:div {:class ["hx-pop-head-l"]}
        [:span {:class ["hx-dot" tone]}]
        [:div {:class ["hx-head-text"]}
         [:div {:class ["hx-head-title"]} (:title diagnostics)]
         [:div {:class ["hx-head-meta" "o-mono"]}
          (str "Last update " (:last-update-label diagnostics))]]]
       [:button {:type "button"
                 :class ["hx-close"]
                 :aria-label "Close"
                 :data-role "connection-diagnostics-close"
                 :on {:click [[:actions/close-ws-diagnostics]]}}
        (icon-close)]]
      [:div {:class ["hx-reason"]} (:reason diagnostics)]
      [:div {:class ["hx-actions"]}
       (reconnect-button diagnostics)
       (copy-button (:copy-status diagnostics))]
      (copy-status-view (:copy-status diagnostics))
      [:div {:class ["hx-groups"]}
       (for [group (:groups diagnostics)]
         (group-card group))]
      (freshness-toggle (:show-surface-freshness-cues? diagnostics))
      (developer-details diagnostics)]]))
