(ns hyperopen.vaults.application.saved-views-vm
  "View model for saved views on the All vaults page: the Views row and the
  active view's header (recipe, refresh status, refresh estimate)."
  (:require [hyperopen.vaults.screener.commands :as commands]
            [hyperopen.vaults.screener.model :as model]
            [hyperopen.vaults.screener.saved-views :as saved-views]
            [hyperopen.vaults.screener.view-commands :as view-commands]))

(def ^:private stale-after-ms
  model/cache-ttl-ms)

(def ^:private window-labels
  (into {} (map (juxt :value :label)) model/window-options))

(def ^:private age-labels
  (into {} (map (juxt :value :label)) model/age-options))

(def ^:private tvl-labels
  (into {} (map (juxt :value :label)) model/tvl-options))

(defn format-ago
  [then-ms now-ms]
  (if-not (and (number? then-ms) (number? now-ms))
    "never"
    (let [minutes (js/Math.floor (/ (max 0 (- now-ms then-ms)) 60000))]
      (cond
        (< minutes 1) "just now"
        (< minutes 60) (str minutes " min ago")
        (< minutes (* 60 24)) (str (js/Math.floor (/ minutes 60)) " h ago")
        :else (let [days (js/Math.floor (/ minutes (* 60 24)))]
                (str days (if (= 1 days) " day ago" " days ago")))))))

(defn- format-date
  [ms]
  (when (number? ms)
    (.toLocaleDateString (js/Date. ms) "en-US" #js {:month "short" :day "numeric"})))

(defn- criterion-chip
  [{:keys [metric value]}]
  (when (model/parse-number value)
    (let [{:keys [label direction unit]} (model/metric-def-by-key metric)]
      (str label (if (= direction :min) " ≥ " " ≤ ") value
           (case unit :percent "%" :days " days" "")))))

(defn- chips
  [{:keys [config membership scope]}]
  (vec (concat
        [{:text (str "Active ≥ " (age-labels (:min-age-days config))) :accent? false}
         {:text (str "TVL ≥ " (tvl-labels (:min-tvl config))) :accent? false}
         {:text (str (window-labels (:window config)) " window") :accent? false}]
        (keep (fn [c] (when-let [t (criterion-chip c)] {:text t :accent? true})) (:criteria config))
        [{:text (if (= membership :fixed) "Fixed members" "Members update on refresh") :accent? false}]
        (when (= scope :pass-and-near) [{:text "Includes near misses" :accent? false}]))))

(defn- refresh-estimate
  [state view now-ms]
  (let [addresses (if (= :fixed (:membership view))
                    (:members view)
                    (commands/universe-addresses state now-ms (:config view)))
        cache-index (or (get-in state (conj commands/screener-state-path :cache-index)) {})]
    (model/estimate addresses cache-index now-ms)))

(defn- status
  [state view now-ms]
  (let [run (get-in state commands/run-path)
        refreshing? (and (= (:id view) (:saved-view-id run))
                         (#{:running :paused} (:status run)))]
    (cond
      refreshing?
      (let [pending (- (or (:total run) 0) (or (:done run) 0))]
        {:tone :progress
         :refreshing? true
         :paused? (= :paused (:status run))
         :text (str (if (= :paused (:status run)) "Refresh paused · " "Refreshing… ")
                    (:done run) " of " (:total run) " vaults"
                    (when (pos? pending)
                      (str " · ≈ " (model/format-duration (/ (* pending (or (:interval-ms run) model/request-interval-ms)) 1000))
                           " left at most")))})

      (and (:last-diff view) (< (- now-ms (or (:refreshed-at-ms view) 0)) (* 10 60 1000)))
      {:tone :success
       :text (str "Refreshed " (format-ago (:refreshed-at-ms view) now-ms) " · "
                  (saved-views/diff-summary (:last-diff view)))}

      :else
      (let [{:keys [to-fetch total seconds]} (refresh-estimate state view now-ms)
            stale? (> (- now-ms (or (:refreshed-at-ms view) 0)) stale-after-ms)]
        {:tone (if stale? :warn :muted)
         :text (str "Saved " (format-date (:created-at-ms view))
                    " · refreshed " (format-ago (:refreshed-at-ms view) now-ms)
                    " · Refresh takes "
                    (if (zero? to-fetch)
                      "a moment (all cached)"
                      (str "≈ " (model/format-duration seconds) " (" (- total to-fetch) " of " total " cached)")))}))))

(defn saved-views-vm
  ([state]
   (saved-views-vm state (.now js/Date)))
  ([state now-ms]
   (let [views (view-commands/views state)
         active (view-commands/active-view state)
         menu (or (get-in state view-commands/menu-path) {})]
     {:views (mapv (fn [{:keys [id name members]}]
                     {:id id :name name :count (count members) :active? (= id (:id active))})
                   views)
      :active (when active
                (merge (select-keys active [:id :name :membership])
                       {:count (count (:members active))
                        :chips (chips active)
                        :status (status state active now-ms)
                        :menu-open? (true? (:open? menu))
                        :confirm-delete? (true? (:confirm-delete? menu))}))})))
