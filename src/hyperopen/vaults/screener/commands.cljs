(ns hyperopen.vaults.screener.commands
  "Vault-screener actions. Each returns effect vectors, projection first."
  (:require [clojure.string :as str]
            [hyperopen.vaults.application.list-vm.cache :as list-cache]
            [hyperopen.vaults.application.list-vm.preview :as preview]
            [hyperopen.vaults.application.list-vm.rows :as rows]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]
            [hyperopen.vaults.screener.model :as model]))

(def view-path
  [:vaults-ui :view])

(def config-path
  [:vaults-ui :screener])

(def panel-path
  [:vaults-ui :screener-panel])

(def screener-state-path
  [:vaults :screener])

(def run-path
  (conj screener-state-path :run))

(def results-path
  (conj screener-state-path :results))

(defn current-config
  [state]
  (model/normalize-config (get-in state config-path)))

(defn- keyword-token
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/replace value #"^:" ""))
    :else nil))

(defn- save-config
  [config]
  [[:effects/save config-path (model/normalize-config config)]])

(defn set-vaults-view
  [state view]
  (let [view* (if (= :screener (keyword-token view)) :screener :list)]
    (cond-> [[:effects/save view-path view*]]
      (and (= view* :screener)
           (not (true? (get-in state (conj screener-state-path :cache-index-loaded?)))))
      (conj [:effects/load-vault-screener-cache-index]))))

(def ^:private config-controls
  #{:min-age-days :min-tvl :window :min-history-points :include-deposited? :results-tab})

(defn set-vault-screener-control
  [state control value]
  (let [control* (keyword-token control)
        config (current-config state)]
    (cond
      (contains? config-controls control*)
      (save-config (assoc config control* (if (= control* :include-deposited?)
                                            (true? value)
                                            value)))

      (= control* :results-sort)
      (let [metric (or (model/normalize-metric-key value)
                       (#{:tvl :age} (keyword-token value)))
            {current :metric direction :direction} (:results-sort config)]
        (if metric
          (save-config (assoc config :results-sort
                              {:metric metric
                               :direction (if (and (= metric current) (= :desc direction))
                                            :asc
                                            :desc)}))
          []))

      :else [])))

(defn add-vault-screener-criterion
  [state metric]
  (let [metric* (model/normalize-metric-key metric)
        config (current-config state)]
    (if (and metric* (not-any? #(= metric* (:metric %)) (:criteria config)))
      (save-config (update config :criteria conj
                           {:metric metric*
                            :value (:default (model/metric-def-by-key metric*))}))
      [])))

(defn remove-vault-screener-criterion
  [state metric]
  (let [metric* (model/normalize-metric-key metric)
        config (current-config state)]
    (if metric*
      (save-config (update config :criteria
                           (fn [criteria] (vec (remove #(= metric* (:metric %)) criteria)))))
      [])))

(defn set-vault-screener-criterion-value
  [state metric value]
  (let [metric* (model/normalize-metric-key metric)
        config (current-config state)]
    (if metric*
      (save-config (update config :criteria
                           (fn [criteria]
                             (mapv #(if (= metric* (:metric %))
                                      (assoc % :value (str (or value "")))
                                      %)
                                   criteria))))
      [])))

(defn apply-vault-screener-preset
  [state preset-id]
  (save-config (model/apply-preset (current-config state) preset-id)))

(defn universe-addresses
  ([state now-ms]
   (universe-addresses state now-ms (current-config state)))
  ([state now-ms config]
   (let [snapshot-range (vault-ui-state/normalize-vault-snapshot-range
                         (get-in state [:vaults-ui :snapshot-range]))
         parsed-rows (list-cache/cached-parsed-rows
                      (preview/live-rows-source state)
                      (rows/normalize-address (get-in state [:wallet :address]))
                      (or (get-in state [:vaults :user-equity-by-address]) {})
                      snapshot-range
                      now-ms)]
     (mapv :vault-address (model/universe-rows parsed-rows config)))))

(defn run-vault-screener
  ([state]
   (run-vault-screener state (.now js/Date)))
  ([state now-ms]
   (let [config (current-config state)
         addresses (universe-addresses state now-ms)
         run-id (inc (or (get-in state (conj run-path :id)) 0))]
     (if (empty? addresses)
       []
       [[:effects/save-many [[run-path {:id run-id
                                        :status :running
                                        :config config
                                        :addresses addresses
                                        :total (count addresses)
                                        :done 0
                                        :cached 0
                                        :fetched 0
                                        :failed 0
                                        :started-at-ms now-ms
                                        :interval-ms model/request-interval-ms}]
                             [results-path {}]
                             [panel-path :run]]]
        [:effects/run-vault-screener run-id]]))))

(defn control-vault-screener-run
  [state command]
  (let [command* (keyword-token command)
        {:keys [id status]} (get-in state run-path)]
    (case command*
      :pause (if (= status :running)
               [[:effects/save (conj run-path :status) :paused]]
               [])
      :resume (if (and id (= status :paused))
                [[:effects/save (conj run-path :status) :running]
                 [:effects/run-vault-screener id]]
                [])
      :cancel (if (#{:running :paused} status)
                [[:effects/save-many [[(conj run-path :status) :cancelled]
                                      [panel-path :builder]]]]
                [])
      :edit [[:effects/save panel-path :builder]]
      :show-results (if id [[:effects/save panel-path :run]] [])
      [])))
