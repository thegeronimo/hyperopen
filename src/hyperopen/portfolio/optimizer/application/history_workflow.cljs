(ns hyperopen.portfolio.optimizer.application.history-workflow
  (:require [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as history-api-v2]
            [hyperopen.portfolio.optimizer.application.history-loader.request-plan :as request-plan]
            [hyperopen.portfolio.optimizer.application.history-merge :as history-merge]
            [hyperopen.portfolio.optimizer.application.history-prefetch :as history-prefetch]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(def default-funding-window-ms
  (* 365 24 60 60 1000))

(defn request-signature
  [request]
  (select-keys request
               [:universe
                :current-portfolio-universe
                :interval
                :bars
                :priority
                :now-ms
                :funding-window-ms
                :funding-start-ms
                :funding-end-ms]))

(defn optimizer-runtime
  [state]
  (get-in state contracts/runtime-path))

(defn- enrich-universe-from-discovery
  [state universe]
  (let [discovery (get-in state contracts/history-discovery-path)]
    (mapv #(history-api-v2/with-discovery-metadata % discovery)
          (or universe []))))

(defn- finite-number?
  [value]
  (coercion/finite-number? value))

(defn- non-zero-current-exposure?
  [exposure]
  (let [weight (:weight exposure)]
    (and (finite-number? weight)
         (not (zero? weight)))))

(defn- current-exposure-instrument
  [exposure]
  (select-keys exposure
               [:instrument-id
                :market-type
                :instrument-type
                :coin
                :dex
                :symbol
                :base
                :quote
                :name
                :vault-address
                :hip3?
                :optimizer-history/instrument-id
                :optimizer-history/display-symbol
                :optimizer-history/instrument-kind
                :optimizer-history/history-status
                :optimizer-history/quality-status
                :optimizer-history/proxy]))

(defn- distinct-instruments
  [instruments]
  (vec (vals (reduce (fn [acc instrument]
                       (if-let [instrument-id (:instrument-id instrument)]
                         (assoc acc instrument-id instrument)
                         acc))
                     {}
                     instruments))))

(defn- current-portfolio-universe
  [state]
  (->> (:exposures (current-portfolio/current-portfolio-snapshot state))
       (filter non-zero-current-exposure?)
       (map current-exposure-instrument)
       distinct-instruments
       (enrich-universe-from-discovery state)))

(defn history-request
  [state opts]
  (let [opts* (or opts {})
        runtime (optimizer-runtime state)
        now-ms (or (:now-ms opts*)
                   (:as-of-ms runtime))
        universe-instruments (vec (get-in state contracts/draft-universe-path))
        universe-ids (into #{} (keep :instrument-id) universe-instruments)
        universe (into universe-instruments
                       (remove #(contains? universe-ids (:instrument-id %)))
                       (get-in state contracts/draft-proxy-reference-instruments-path))
        request (merge {:universe universe
                        :current-portfolio-universe (current-portfolio-universe state)
                        :interval :1d
                        :bars request-plan/default-bars
                        :priority :high
                        :now-ms now-ms
                        :funding-window-ms default-funding-window-ms}
                       (select-keys runtime
                                    [:stale-after-ms
                                     :funding-periods-per-year
                                     :funding-window-ms])
                       (dissoc opts* :now-ms))]
    (let [request* (-> request
                       (update :universe #(enrich-universe-from-discovery state %))
                       (update :current-portfolio-universe
                               #(enrich-universe-from-discovery state %)))]
      (cond-> request*
        (empty? (:current-portfolio-universe request*))
        (dissoc :current-portfolio-universe)))))

(defn begin-history-load-state
  [signature started-at-ms]
  {:status :loading
   :request-signature signature
   :started-at-ms started-at-ms
   :completed-at-ms nil
   :error nil
   :warnings []})

(defn success-history-load-state
  [current-state completed-at-ms bundle]
  {:status :succeeded
   :request-signature (:request-signature current-state)
   :started-at-ms (:started-at-ms current-state)
   :completed-at-ms completed-at-ms
   :error nil
   :warnings (vec (:warnings bundle))})

(defn error-message
  [err]
  (or (when (map? err)
        (:message err))
      (some-> err .-message)
      (str err)))

(defn failed-history-load-state
  [current-state completed-at-ms err]
  {:status :failed
   :request-signature (:request-signature current-state)
   :started-at-ms (:started-at-ms current-state)
   :completed-at-ms completed-at-ms
   :error {:message (error-message err)}
   :warnings []})

(defn request-opts
  [opts]
  (dissoc (or opts {}) :on-progress :source :queue? :merge?))

(defn selection-prefetch?
  [opts]
  (and (= :selection-prefetch (:source opts))
       (:queue? opts)))

(def merge-history-bundle
  "See application.history-merge (split 2026-07-08); re-exposed here because
  the workflow is this bundle-fold's only production caller."
  history-merge/merge-history-bundle)

(defn- queued-instruments
  [prefetch-state]
  (vec (:queue (merge history-prefetch/default-state prefetch-state))))

(defn- instrument-ids
  [instruments]
  (vec (keep history-prefetch/instrument-id instruments)))

(defn- completed-instrument-ids
  [instrument-id* instrument-ids*]
  (vec (distinct (keep identity (or (seq instrument-ids*)
                                    [instrument-id*])))))

(defn remove-queued-instruments
  [queue instrument-ids*]
  (let [ids (set instrument-ids*)]
    (vec (remove #(contains? ids (history-prefetch/instrument-id %))
                 (or queue [])))))

(defn finish-selection-prefetch-state
  [prefetch-state instrument-ids* status-by-instrument-id]
  (let [prefetch-state* (merge history-prefetch/default-state prefetch-state)
        ids (set instrument-ids*)]
    (cond-> (-> prefetch-state*
                (update :queue remove-queued-instruments instrument-ids*)
                (update :by-instrument-id
                        (fn [by-instrument-id]
                          (reduce (fn [acc instrument-id*]
                                    (if-let [status (get status-by-instrument-id
                                                         instrument-id*)]
                                      (assoc acc instrument-id* status)
                                      acc))
                                  (or by-instrument-id {})
                                  instrument-ids*))))
      (contains? ids (:active-instrument-id prefetch-state*))
      (assoc :active-instrument-id nil))))

(defn current-universe-ids
  "Instrument ids the prefetch state is allowed to track: the draft universe
  plus the reference-only proxy instruments (their prefetch entries must survive
  the post-completion cleanup or a queued reference proxy would be dropped
  before its history ever loads)."
  [state]
  (concat (keep :instrument-id
                (get-in state contracts/draft-universe-path))
          (keep :instrument-id
                (get-in state contracts/draft-proxy-reference-instruments-path))))

(defn begin-selection-prefetch-state
  [state instrument-ids* signature started-at-ms]
  (-> state
      (assoc-in contracts/history-load-state-path
                (begin-history-load-state signature started-at-ms))
      (update-in contracts/history-prefetch-path
                 (fn [prefetch-state]
                   (let [loading-status (history-prefetch/loading-status started-at-ms)]
                     (reduce (fn [prefetch-state* instrument-id*]
                               (assoc-in prefetch-state*
                                         [:by-instrument-id instrument-id*]
                                         loading-status))
                             (assoc (merge history-prefetch/default-state prefetch-state)
                                    :active-instrument-id (first instrument-ids*))
                             instrument-ids*))))))

(defn request-history-command
  [{:keys [source instrument-id instrument-ids request signature]}]
  (cond-> {:command/type :optimizer.workflow/request-history-bundle
           :source source
           :instrument-id instrument-id
           :request-signature signature
           :request request}
    (seq instrument-ids)
    (assoc :instrument-ids (vec instrument-ids))))

(defn- point-returns-present?
  [series]
  (boolean (some #(coercion/finite-number? (:return %))
                 (:points series))))

(defn- aligned-only-member?
  "True when some current universe/reference member's usable returns exist
  ONLY in the backend's aligned-returns (no point-level returns in its
  series). Merging a delta bundle forces the all-or-nothing alignment into
  client-side point-level mode, which would silently drop such a member from
  runs — their presence demands the full-universe refetch that keeps one
  consistent aligned response."
  [state]
  (let [api-v2 (get-in state (conj contracts/history-data-path :api-v2-history))
        series-by-id (:series-by-instrument api-v2)
        aligned-by-id (:aligned-returns-by-instrument api-v2)]
    (boolean
     (some (fn [id]
             (and (seq (:returns (get aligned-by-id id)))
                  (not (point-returns-present? (get series-by-id id)))))
           (current-universe-ids state)))))

(defn begin-selection-prefetch
  [{:keys [state opts now-ms started-at-ms]}]
  (let [prefetch-state (history-prefetch/prefetch-state state)
        active-id (:active-instrument-id prefetch-state)
        queued (queued-instruments prefetch-state)
        queued-ids (instrument-ids queued)
        instrument-id* (first queued-ids)]
    (cond
      active-id
      {:state state
       :commands []}

      (nil? instrument-id*)
      {:state state
       :commands []}

      :else
      (let [prefetch-universe (if (and (get-in state (conj contracts/history-data-path
                                                           :api-v2-history))
                                       (aligned-only-member? state))
                                (into (vec (get-in state contracts/draft-universe-path))
                                      (get-in state contracts/draft-proxy-reference-instruments-path))
                                queued)
            request (history-request state
                                     (assoc (request-opts opts)
                                            :universe prefetch-universe
                                            :current-portfolio-universe []
                                            :now-ms now-ms))
            signature (request-signature request)
            started-at-ms* (or started-at-ms now-ms)
            state* (begin-selection-prefetch-state state
                                                   queued-ids
                                                   signature
                                                   started-at-ms*)]
        {:state state*
         :commands [(request-history-command
                     {:source :selection-prefetch
                      :instrument-id instrument-id*
                      :instrument-ids queued-ids
                      :request request
                      :signature signature})]}))))

(defn apply-history-success
  [state signature completed-at-ms bundle]
  (if (= signature
         (get-in state contracts/history-load-state-request-signature-path))
    (let [current-state (get-in state contracts/history-load-state-path)]
      (-> state
          (assoc-in contracts/history-data-path
                    (assoc bundle :loaded-at-ms completed-at-ms))
          (assoc-in contracts/history-load-state-path
                    (success-history-load-state current-state completed-at-ms bundle))))
    state))

(defn apply-history-error
  [state signature completed-at-ms err]
  (if (= signature
         (get-in state contracts/history-load-state-request-signature-path))
    (let [current-state (get-in state contracts/history-load-state-path)]
      (assoc-in state
                contracts/history-load-state-path
                (failed-history-load-state current-state completed-at-ms err)))
    state))

(defn- succeeded-prefetch-statuses
  [prefetch-state instrument-ids* completed-at-ms warnings]
  (into {}
        (map (fn [instrument-id*]
               [instrument-id*
                (history-prefetch/succeeded-status
                 (:started-at-ms (get-in prefetch-state
                                         [:by-instrument-id instrument-id*]))
                 completed-at-ms
                 warnings)])
             instrument-ids*)))

(defn- failed-prefetch-statuses
  [prefetch-state instrument-ids* completed-at-ms err]
  (into {}
        (map (fn [instrument-id*]
               [instrument-id*
                (history-prefetch/failed-status
                 (:started-at-ms (get-in prefetch-state
                                         [:by-instrument-id instrument-id*]))
                 completed-at-ms
                 {:message (error-message err)})])
             instrument-ids*)))

(defn apply-selection-prefetch-success
  [state instrument-id* instrument-ids* signature completed-at-ms bundle]
  (let [current-prefetch-state (history-prefetch/prefetch-state state)
        completed-ids (completed-instrument-ids instrument-id* instrument-ids*)
        selected? (boolean (some #(history-prefetch/instrument-selected? state %)
                                 completed-ids))
        current-signature? (= signature
                              (get-in state
                                      contracts/history-load-state-request-signature-path))
        statuses (succeeded-prefetch-statuses current-prefetch-state
                                              completed-ids
                                              completed-at-ms
                                              (:warnings bundle))]
    (cond-> state
      (and selected? current-signature?)
      (update-in contracts/history-data-path
                 merge-history-bundle
                 bundle
                 completed-at-ms)

      current-signature?
      (assoc-in contracts/history-load-state-path
                (success-history-load-state
                 (get-in state contracts/history-load-state-path)
                 completed-at-ms
                 bundle))

      :always
      (update-in contracts/history-prefetch-path
                 finish-selection-prefetch-state
                 completed-ids
                 statuses)

      :always
      (update-in contracts/history-prefetch-path
                 history-prefetch/cleanup-to-instrument-ids
                 (current-universe-ids state)))))

(defn apply-selection-prefetch-error
  [state instrument-id* instrument-ids* signature completed-at-ms err]
  (let [current-prefetch-state (history-prefetch/prefetch-state state)
        completed-ids (completed-instrument-ids instrument-id* instrument-ids*)
        current-load-state (get-in state contracts/history-load-state-path)
        current-signature? (= signature (:request-signature current-load-state))
        statuses (failed-prefetch-statuses current-prefetch-state
                                           completed-ids
                                           completed-at-ms
                                           err)]
    (cond-> state
      current-signature?
      (assoc-in contracts/history-load-state-path
                (failed-history-load-state current-load-state completed-at-ms err))

      :always
      (update-in contracts/history-prefetch-path
                 finish-selection-prefetch-state
                 completed-ids
                 statuses)

      :always
      (update-in contracts/history-prefetch-path
                 history-prefetch/cleanup-to-instrument-ids
                 (current-universe-ids state)))))

(defn complete-selection-prefetch
  [{:keys [state instrument-id instrument-ids request-signature completed-at-ms
           bundle error opts]}]
  (let [state* (if error
                 (apply-selection-prefetch-error state
                                                 instrument-id
                                                 instrument-ids
                                                 request-signature
                                                 completed-at-ms
                                                 error)
                 (apply-selection-prefetch-success state
                                                   instrument-id
                                                   instrument-ids
                                                   request-signature
                                                   completed-at-ms
                                                   bundle))
        next-result (begin-selection-prefetch {:state state*
                                               :opts opts
                                               :now-ms completed-at-ms})]
    next-result))

(defn begin-history-load
  [{:keys [state opts now-ms started-at-ms]}]
  (let [request (history-request state
                                 (assoc (request-opts opts)
                                        :now-ms now-ms))
        signature (request-signature request)
        started-at-ms* (or started-at-ms now-ms)]
    (if (seq (:universe request))
      {:state (assoc-in state
                        contracts/history-load-state-path
                        (begin-history-load-state signature started-at-ms*))
       :commands [(request-history-command
                   {:source :full-load
                    :request request
                    :signature signature})]}
      {:state state
       :commands []})))

(defn complete-history-load
  [{:keys [state request-signature completed-at-ms bundle error]}]
  {:state (if error
            (apply-history-error state request-signature completed-at-ms error)
            (apply-history-success state request-signature completed-at-ms bundle))
   :commands []})
