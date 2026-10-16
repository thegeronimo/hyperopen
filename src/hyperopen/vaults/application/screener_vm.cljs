(ns hyperopen.vaults.application.screener-vm
  "View model for the vault screener: the builder (universe, criteria,
  estimate), the run panel (progress, pacing, backoff) and the results
  (grouped, sorted, formatted)."
  (:require [hyperopen.vaults.application.list-vm.cache :as list-cache]
            [hyperopen.vaults.application.list-vm.preview :as preview]
            [hyperopen.vaults.application.list-vm.rows :as rows]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]
            [hyperopen.vaults.screener.commands :as commands]
            [hyperopen.vaults.screener.model :as model]))

(def ^:private max-metric-columns
  6)

(def ^:private window-label-by-value
  (into {} (map (juxt :value :label)) model/window-options))

(def ^:private age-label-by-value
  (into {} (map (juxt :value :label)) model/age-options))

(def ^:private tvl-label-by-value
  (into {} (map (juxt :value :label)) model/tvl-options))

(defn- parsed-rows
  [state now-ms]
  (list-cache/cached-parsed-rows
   (preview/live-rows-source state)
   (rows/normalize-address (get-in state [:wallet :address]))
   (or (get-in state [:vaults :user-equity-by-address]) {})
   (vault-ui-state/normalize-vault-snapshot-range (get-in state [:vaults-ui :snapshot-range]))
   now-ms))

(defn- options
  [opts current control]
  (mapv (fn [{:keys [value label]}]
          {:value value
           :label label
           :active? (= value current)
           :action [:actions/set-vault-screener-control control value]})
        opts))

(defn- op-label
  [metric]
  (if (= :min (:direction (model/metric-def-by-key metric))) "≥" "≤"))

(defn- unit-label
  [metric]
  (case (:unit (model/metric-def-by-key metric))
    :percent "%"
    :days "days"
    ""))

(defn format-metric
  "Display text for an engine metric value."
  [metric value]
  (if-not (number? value)
    "—"
    (let [{:keys [unit]} (model/metric-def-by-key metric)]
      (case unit
        :percent (let [pct (* 100 value)
                       digits (.toFixed (js/Math.abs pct) 1)
                       ;; The sign follows the digits shown, so -0.004% reads 0.0%.
                       sign (cond (= digits "0.0") "" (pos? pct) "+" :else "−")]
                   (str (if (= metric :volatility-ann) "" sign) digits "%"))
        :days (str (js/Math.round value) " d")
        (let [digits (.toFixed (js/Math.abs value) 2)]
          (if (and (neg? value) (not= digits "0.00")) (str "−" digits) digits))))))

(defn- criterion-row
  [{:keys [metric value]}]
  (let [{:keys [label hint step]} (model/metric-def-by-key metric)]
    {:metric metric
     :label label
     :hint hint
     :op (op-label metric)
     :unit (unit-label metric)
     :value value
     :step step}))

(defn- criteria-summary
  [criteria]
  (->> criteria
       (keep (fn [{:keys [metric value]}]
               (when (model/parse-number value)
                 (str (:label (model/metric-def-by-key metric)) " " (op-label metric) " "
                      value (let [u (unit-label metric)]
                              (cond (= u "%") "%" (seq u) (str " " u) :else ""))))))
       vec))

(defn- universe-chips
  [config]
  [(str "Active ≥ " (age-label-by-value (:min-age-days config)))
   (str "TVL ≥ " (tvl-label-by-value (:min-tvl config)))
   (str (window-label-by-value (:window config)) " window")])

(defn- metric-columns
  [config]
  (->> (concat [:sharpe :max-drawdown] (map :metric (:criteria config)))
       distinct
       (take max-metric-columns)
       vec))

(defn- outcome-by-metric
  [evaluation]
  (into {} (map (juxt :metric :outcome)) (:results evaluation)))

(defn- thin-reason
  [{:keys [reason metric]}]
  (case reason
    :error "Couldn't load history"
    :history "Too few history points"
    :metric (str (:label (model/metric-def-by-key metric)) " unavailable")
    nil))

(defn- near-reason
  [{:keys [metric gap]}]
  (let [{:keys [label direction]} (model/metric-def-by-key metric)
        u (unit-label metric)
        gap* (if (= u "%") (str (.toFixed gap 1) " pts") (.toFixed gap 2))]
    (str label " " gap* (if (= direction :min) " under" " over") " the limit")))

(defn- result-row
  [row result evaluation columns]
  (let [outcomes (outcome-by-metric evaluation)]
    (merge (select-keys row [:vault-address :name :leader :tvl :age-days :your-deposit :has-deposit?])
           {:values (:values result)
            :status (:status evaluation)
            :reason (case (:status evaluation)
                      :thin (thin-reason evaluation)
                      :near (near-reason (:miss evaluation))
                      nil)
            :cells (mapv (fn [metric]
                           {:metric metric
                            :text (format-metric metric (get-in result [:values metric]))
                            :outcome (get outcomes metric)
                            :low-confidence? (= :low-confidence (get-in result [:statuses metric]))})
                         columns)})))

(defn- run-model
  [run cache-index results config now-ms]
  (when (map? run)
    (let [{:keys [status total done cached fetched interval-ms backoff-until-ms addresses]} run
          pending (remove #(contains? results %) addresses)
          pending-fetch (count (remove #(model/fresh-cache-entry? (get cache-index %) now-ms) pending))
          interval (or interval-ms model/request-interval-ms)
          pct (fn [n] (if (pos? total) (* 100 (/ (or n 0) total)) 0))]
      {:id (:id run)
       :status status
       :total total
       :done (or done 0)
       :cached (or cached 0)
       :fetched (or fetched 0)
       :failed (or (:failed run) 0)
       :queued (- total (or done 0))
       :cached-pct (pct cached)
       :fetched-pct (pct (- (or done 0) (or cached 0)))
       :progress-pct (js/Math.round (pct done))
       :eta-label (model/format-duration (/ (* pending-fetch interval) 1000))
       :interval-label (str "1 vault / " (js/Math.round (/ interval 1000)) " s")
       :backoff-seconds (when (and (number? backoff-until-ms) (> backoff-until-ms now-ms))
                          (js/Math.ceil (/ (- backoff-until-ms now-ms) 1000)))
       :stale? (not= (model/universe-config-signature (:config run))
                     (model/universe-config-signature config))
       :chips (universe-chips (:config run))})))

(defn screener-vm
  ([state]
   (screener-vm state (.now js/Date)))
  ([state now-ms]
   (let [config (commands/current-config state)
         rows* (parsed-rows state now-ms)
         row-by-address (into {} (map (juxt :vault-address identity)) rows*)
         universe (model/universe-rows rows* config)
         base-count (model/base-universe-count rows*)
         screener (get-in state commands/screener-state-path)
         cache-index (or (:cache-index screener) {})
         estimate (model/estimate (mapv :vault-address universe) cache-index now-ms)
         run (:run screener)
         results (or (:results screener) {})
         run* (run-model run cache-index results config now-ms)
         panel (if (and run (not= :builder (get-in state commands/panel-path)))
                 :run
                 :builder)
         columns (metric-columns config)
         evaluated (->> (or (:addresses run) [])
                        (keep (fn [address]
                                (when-let [result (get results address)]
                                  (let [row (or (get row-by-address address)
                                                {:vault-address address :name address})
                                        evaluation (model/evaluate config result)]
                                    (result-row row result evaluation columns)))))
                        vec)
         grouped (group-by :status evaluated)
         tab (:results-tab config)
         used (set (map :metric (:criteria config)))]
     {:config config
      :panel panel
      :loading? (empty? rows*)
      :base-count base-count
      :universe-count (count universe)
      :funnel-pct (if (pos? base-count)
                    (min 100 (max 1 (* 100 (/ (count universe) base-count))))
                    0)
      :ages (options model/age-options (:min-age-days config) :min-age-days)
      :tvls (options model/tvl-options (:min-tvl config) :min-tvl)
      :windows (options model/window-options (:window config) :window)
      :min-history (options model/min-history-options (:min-history-points config) :min-history-points)
      :include-deposited? (:include-deposited? config)
      :criteria (mapv criterion-row (:criteria config))
      :available-metrics (->> model/metric-defs
                              (remove #(contains? used (:key %)))
                              (mapv #(select-keys % [:key :label])))
      :presets (mapv #(select-keys % [:id :label]) model/presets)
      :estimate (assoc estimate
                       :eta-label (model/format-duration (:seconds estimate))
                       :cache-loaded? (true? (:cache-index-loaded? screener)))
      :criteria-summary (criteria-summary (:criteria config))
      :run run*
      :columns (mapv (fn [metric]
                       {:metric metric
                        :label (:label (model/metric-def-by-key metric))
                        :sorted? (= metric (get-in config [:results-sort :metric]))
                        :direction (get-in config [:results-sort :direction])})
                     columns)
      :results-tab tab
      :tab-counts (into {} (map (fn [t] [t (count (get grouped t))])) model/results-tabs)
      :evaluated-count (count evaluated)
      :result-rows (model/sort-results (get grouped tab []) (:results-sort config))
      :passing-rows (->> (model/sort-results (get grouped :pass []) {:metric :sharpe :direction :desc})
                         (take 8)
                         vec)})))
