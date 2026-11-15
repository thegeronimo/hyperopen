(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-pipeline
  (:require [hyperopen.portfolio.optimizer.application.pipeline-workflow :as workflow]
            [hyperopen.portfolio.optimizer.application.progress :as progress]))

(defn- error-message
  [err]
  (or (when (map? err)
        (:message err))
      (some-> err .-message)
      (str err)))

(defn- mark-progress-step!
  [store run-id step-id attrs]
  (swap! store workflow/update-progress run-id progress/mark-step step-id attrs))

(defn- count-value
  [value]
  (if (number? value) value 0))

(defn- percent-value
  [value]
  (if (number? value) value 0))

(defn- plural
  [n singular plural-label]
  (if (= 1 n) singular plural-label))

(defn- backend-progress-detail
  [backend]
  (let [requested-count (count-value (:requested-count backend))
        loaded-count (count-value (:loaded-count backend))
        usable-count (count-value (:usable-count backend))]
    (case (:status backend)
      :started
      (str "backend API: loading "
           requested-count
           " "
           (plural requested-count "asset" "assets"))

      :loading
      (str "backend API: "
           loaded-count
           "/"
           requested-count
           " "
           (plural requested-count "asset" "assets"))

      :failed
      (str "backend API: failed ("
           requested-count
           " "
           (plural requested-count "asset" "assets")
           ")")

      :skipped
      "backend API: 0/0 assets"

      :succeeded
      (str "backend API: "
           usable-count
           "/"
           requested-count
           " "
           (plural requested-count "asset" "assets"))

      "backend API: not used")))

(defn- info-progress-detail
  [info]
  (let [completed (count-value (:completed info))
        total (count-value (:total info))]
    (str "/info: "
         completed
         "/"
         total
         " "
         (plural total "request" "requests"))))

(defn- source-progress-detail
  [source-state]
  (str (backend-progress-detail (:backend source-state))
       ", "
       (info-progress-detail (:info source-state))))

(defn- backend-phase-percent
  "Maps backend bundle progress onto the fetch step: 5 on request start,
  chunk completions filling 5-95. Terminal statuses that hand off to the
  legacy /info fallback settle at 50 so the fallback phase owns the rest;
  a clean success holds at 95 until the worker run takes the step over."
  [backend]
  (let [total (count-value (:total backend))
        completed (count-value (:completed backend))
        fallback-count (count-value (:fallback-asset-count backend))]
    (case (:status backend)
      :started 5
      :loading (if (pos? total)
                 (+ 5 (* 90 (/ completed total)))
                 5)
      :succeeded (if (pos? fallback-count) 50 95)
      50)))

(defn- source-progress-percent
  [source-state payload]
  (let [backend (:backend source-state)
        info (:info source-state)
        info-total (count-value (:total info))
        info-percent (percent-value (:percent info))]
    (cond
      (and backend (pos? info-total))
      (+ 50 (/ info-percent 2))

      backend
      (backend-phase-percent backend)

      (pos? info-total)
      info-percent

      :else
      (percent-value (:percent payload)))))

(defn- source-key
  [source]
  (case source
    :backend-api :backend
    :info-endpoint :info
    source))

(defn- source-progress-attrs
  [source-state payload]
  (let [source (source-key (:source payload))
        source-state* (if source
                        (update source-state source merge payload)
                        source-state)]
    [source-state*
     {:status :running
      :percent (source-progress-percent source-state* payload)
      :detail (source-progress-detail source-state*)}]))

(defn- fetch-progress-callback
  [store run-id]
  (let [source-state (atom {})
        max-percent (atom 0)
        monotonic-percent! (fn [percent]
                             (swap! max-percent max (percent-value percent)))]
    (fn [{:keys [percent completed total source] :as payload}]
      (if source
        (let [[source-state* attrs] (source-progress-attrs @source-state payload)]
          (reset! source-state source-state*)
          (mark-progress-step! store
                               run-id
                               :fetch-returns
                               (update attrs :percent monotonic-percent!)))
        (mark-progress-step! store
                             run-id
                             :fetch-returns
                             {:status :running
                              :percent (monotonic-percent! percent)
                              :detail (str completed "/" total " requests")})))))

(defn- wait-for-history-load-idle!
  [store {:keys [poll-ms timeout-ms]}]
  (let [poll-ms (or poll-ms 50)
        timeout-ms (or timeout-ms 30000)
        started-at-ms (.now js/Date)]
    (js/Promise.
     (fn [resolve reject]
       (letfn [(tick []
                 (cond
                   (not (workflow/selection-prefetch-loading? @store))
                   (resolve true)

                   (> (- (.now js/Date) started-at-ms) timeout-ms)
                   (reject (js/Error. "Timed out waiting for optimizer history prefetch to settle."))

                   :else
                   (js/setTimeout tick poll-ms)))]
         (tick))))))

(declare interpret-result!)

(defn- apply-result!
  [store result]
  (reset! store (:state result))
  result)

(defn- request-worker-run!
  [request-run! store {:keys [run-id request request-signature]}]
  (request-run! {:request request
                 :request-signature request-signature
                 :store store
                 :run-id run-id})
  (js/Promise.resolve run-id))

(defn- load-history-then-run!
  [{:keys [load-history!] :as env} store run-id]
  (-> (load-history! store
                     {:on-progress (fetch-progress-callback store run-id)})
      (.then (fn [_bundle]
               (interpret-result!
                env
                store
                (workflow/after-history-loaded {:state @store
                                                :run-id run-id}))))))

(defn- interpret-command!
  [{:keys [request-run! history-idle-poll-ms history-idle-timeout-ms] :as env} store command]
  (case (:command/type command)
    :optimizer.workflow/request-worker-run
    (request-worker-run! request-run! store command)

    :optimizer.workflow/load-history
    (load-history-then-run! env store (:run-id command))

    :optimizer.workflow/wait-for-history-idle
    (-> (wait-for-history-load-idle!
         store
         {:poll-ms (or (:poll-ms command) history-idle-poll-ms)
          :timeout-ms (or (:timeout-ms command) history-idle-timeout-ms)})
        (.then (fn [_]
                 (interpret-result!
                  env
                  store
                  (workflow/after-history-idle {:state @store
                                                :run-id (:run-id command)})))))

    (js/Promise.resolve nil)))

(defn- interpret-result!
  [env store result]
  (let [result* (apply-result! store result)]
    (if-let [command (first (:commands result*))]
      (interpret-command! env store command)
      (js/Promise.resolve nil))))

(defn run-portfolio-optimizer-pipeline-effect
  [{:keys [now-ms next-run-id] :as env} _ store]
  (let [run-id (next-run-id)
        started-at-ms (now-ms)]
    (-> (interpret-result!
         env
         store
         (workflow/begin-run
          {:state @store
           :run-id run-id
           :started-at-ms started-at-ms
           :history-idle-poll-ms (:history-idle-poll-ms env)
           :history-idle-timeout-ms (:history-idle-timeout-ms env)}))
        (.catch (fn [err]
                  (apply-result!
                   store
                   (workflow/fail-run
                    {:state @store
                     :run-id run-id
                     :completed-at-ms (now-ms)
                     :error (workflow/progress-error :pipeline-failed
                                                     (error-message err))}))
                  nil)))))
