(ns hyperopen.views.trading-chart.utils.chart-options)

(def default-right-offset-bars 4)

(def chart-visual-profile-local-storage-key "chart-visual-profile")
(def default-chart-visual-profile :subtle-v1)

(def supported-chart-visual-profiles
  #{:legacy :subtle-v1})

(def ^:private chart-visual-profile-tokens
  {:legacy {:text-color "#e5e7eb"
            :background-color "rgb(15, 26, 31)"
            :grid-line-color "#374151"
            :scale-border-color "#374151"
            :pane-separator-color "#374151"
            :pane-separator-hover-color "#4b5563"}
   :subtle-v1 {:text-color "#e5e7eb"
               :background-color "rgb(15, 26, 31)"
               :grid-line-color "rgba(139, 148, 158, 0.16)"
               :scale-border-color "rgba(139, 148, 158, 0.24)"
               :pane-separator-color "rgba(139, 148, 158, 0.22)"
               :pane-separator-hover-color "rgba(139, 148, 158, 0.30)"}})

(defn normalize-chart-visual-profile [profile]
  (let [candidate (cond
                    (keyword? profile) profile
                    (string? profile) (keyword profile)
                    :else nil)]
    (if (contains? supported-chart-visual-profiles candidate)
      candidate
      default-chart-visual-profile)))

(defn- resolve-local-storage-chart-visual-profile []
  (if (exists? js/window)
    (try
      (normalize-chart-visual-profile
        (.getItem ^js (.-localStorage js/window)
                  chart-visual-profile-local-storage-key))
      (catch :default _
        default-chart-visual-profile))
    default-chart-visual-profile))

(defn- effective-chart-visual-profile [profile]
  (if (some? profile)
    (normalize-chart-visual-profile profile)
    (resolve-local-storage-chart-visual-profile)))

(defn- common-chart-options [profile]
  (let [{:keys [text-color
                background-color
                grid-line-color
                scale-border-color
                pane-separator-color
                pane-separator-hover-color]}
        (get chart-visual-profile-tokens (effective-chart-visual-profile profile))]
    {:layout {:textColor text-color
              :background {:type "solid"
                           :color background-color}
              :panes {:separatorColor pane-separator-color
                      :separatorHoverColor pane-separator-hover-color}}
     :grid {:vertLines {:color grid-line-color}
            :horzLines {:color grid-line-color}}
     :rightPriceScale {:borderColor scale-border-color}
     :timeScale {:borderColor scale-border-color
                 :rightOffset default-right-offset-bars}}))

(defn base-chart-options
  ([] (base-chart-options nil))
  ([profile]
   (assoc (common-chart-options profile) :autoSize true)))

(defn fixed-height-chart-options
  ([height] (fixed-height-chart-options height nil))
  ([height profile]
   (assoc (common-chart-options profile) :height height)))
