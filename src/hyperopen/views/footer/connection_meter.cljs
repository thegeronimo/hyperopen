(ns hyperopen.views.footer.connection-meter)

(def ^:private tone-classes
  {:success "tone-ok"
   :warning "tone-warn"
   :error "tone-err"
   :neutral "tone-ok"})

(defn- signal-meter-bars
  [bar-count active-bars tone-class]
  [:span {:class ["hx-bars" tone-class]
          :data-role "footer-connection-meter-bars"}
   (for [idx (range bar-count)]
     ^{:key (str "meter-bar|" idx)}
     (let [active? (< idx active-bars)]
       [:span {:class ["bar"
                       (str "b" (inc idx))
                       (if active? "on" "off")]
               :data-role "footer-connection-meter-bar"
               :data-active (if active? "true" "false")}]))])

(defn render
  [meter]
  (let [tone-class (get tone-classes (:tone meter) "tone-ok")
        reconnecting? (= :reconnecting (:status meter))
        healthy? (= :live (:status meter))]
    [:button {:type "button"
              :class ["hx-trigger"
                      "items-end"
                      tone-class]
              :on {:click [[:actions/toggle-ws-diagnostics]]}
              :aria-expanded (if (:diagnostics-open? meter) "true" "false")
              :aria-haspopup "dialog"
              :title (:tooltip meter)
              :data-role "footer-connection-meter-button"}
     (signal-meter-bars (:bar-count meter) (:active-bars meter) tone-class)
     [:span {:class ["hx-trigger-label" "relative" "top-px" "leading-none"]}
      (:label meter)]
     (when (and healthy?
                (:show-surface-freshness-cues? meter)
                (:latency-label meter))
       [:span {:class ["hx-trigger-latency" "o-mono"]}
        (str "· " (:latency-label meter))])
     (when reconnecting?
       [:span {:class ["hx-trigger-spinner"]
               :data-role "footer-connection-meter-spinner"}])]))
