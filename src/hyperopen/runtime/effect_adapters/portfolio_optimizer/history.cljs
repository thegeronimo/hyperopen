(ns hyperopen.runtime.effect-adapters.portfolio-optimizer.history
  (:require [clojure.set :as set]
            [hyperopen.portfolio.optimizer.application.history-workflow :as workflow]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(defn- request-candle-snapshot!
  [{:keys [request-candle-snapshot!]} coin opts]
  (request-candle-snapshot! coin opts))

(defn- request-history-bundle-raw!
  [env on-progress request]
  ((:request-history-bundle! env)
   {:request-candle-snapshot! (partial request-candle-snapshot! env)
    :request-market-funding-history! (:request-market-funding-history! env)
    :request-vault-details! (:request-vault-details! env)
    :optimizer-history-api (:optimizer-history-api env)
    :fetch-fn (:fetch-fn env)
    :request-id (:request-id env)
    :on-progress on-progress}
   request))

(defn- instrument-id-set
  [universe]
  (set (keep :instrument-id universe)))

(defn- current-history-needed?
  "Fetch a separate current-portfolio bundle ONLY when holdings contain
  instruments the selected fetch does not cover. This mirrors the consumer
  (`request-builder/history-data-for-current-portfolio`), which reads the MAIN
  bundle whenever current ⊆ selected — the default holdings-seeded flow, where
  the old any-difference check paid a second full-universe backend fetch
  (~6.8s in the 2026-07-08 trace) that was then provably never read."
  [selected-universe current-universe]
  (let [selected-ids (instrument-id-set selected-universe)
        current-ids (instrument-id-set current-universe)]
    (and (seq current-ids)
         (not (set/subset? current-ids selected-ids)))))

(defn- current-history-error-bundle
  [err]
  {:warnings [{:code :current-portfolio-history-unavailable
               :message "Current portfolio history could not be loaded."
               :error-message (workflow/error-message err)}]})

(defn- request-history-bundle!
  [env on-progress request]
  (let [current-universe (vec (:current-portfolio-universe request))
        selected-request (dissoc request :current-portfolio-universe)
        selected-universe (:universe selected-request)
        fetch-current? (current-history-needed? selected-universe current-universe)]
    (-> (request-history-bundle-raw! env on-progress selected-request)
        (.then (fn [selected-bundle]
                 (if fetch-current?
                   (-> (request-history-bundle-raw!
                        env
                        on-progress
                        (assoc selected-request
                               :universe current-universe
                               :allow-legacy-fallback? false))
                       (.then (fn [current-bundle]
                                (assoc current-bundle
                                       :requested-instrument-ids
                                       (vec (keep :instrument-id
                                                  current-universe)))))
                       (.catch (fn [err]
                                 (js/Promise.resolve
                                  (current-history-error-bundle err))))
                       (.then (fn [current-bundle]
                                (assoc selected-bundle
                                       :current-portfolio-history-data
                                       current-bundle))))
                   selected-bundle))))))

(defn- now-for-request
  [env state opts]
  (or (:now-ms opts)
      (get-in state contracts/runtime-as-of-ms-path)
      ((:now-ms env))))

(defn- apply-result!
  [store result]
  (reset! store (:state result))
  result)

(declare interpret-selection-prefetch-result!)

(defn- interpret-selection-prefetch-command!
  [env store opts command]
  (let [now-ms-fn (:now-ms env)
        on-progress (:on-progress opts)]
    (-> (request-history-bundle! env on-progress (:request command))
        (.then (fn [bundle]
                 (let [completed-at-ms (now-ms-fn)]
                   (interpret-selection-prefetch-result!
                    env
                    store
                    opts
                    (workflow/complete-selection-prefetch
                     {:state @store
                      :instrument-id (:instrument-id command)
                      :instrument-ids (:instrument-ids command)
                      :request-signature (:request-signature command)
                      :completed-at-ms completed-at-ms
                      :bundle bundle
                      :opts opts})))))
        (.catch (fn [err]
                  (let [completed-at-ms (now-ms-fn)]
                    (interpret-selection-prefetch-result!
                     env
                     store
                     opts
                     (workflow/complete-selection-prefetch
                      {:state @store
                       :instrument-id (:instrument-id command)
                       :instrument-ids (:instrument-ids command)
                       :request-signature (:request-signature command)
                       :completed-at-ms completed-at-ms
                       :error err
                       :opts opts}))))))))

(defn- interpret-selection-prefetch-result!
  [env store opts result]
  (let [result* (apply-result! store result)]
    (if-let [command (first (:commands result*))]
      (interpret-selection-prefetch-command! env store opts command)
      (js/Promise.resolve nil))))

(defn- drain-selection-prefetch!
  [env store opts]
  (interpret-selection-prefetch-result!
   env
   store
   opts
   (workflow/begin-selection-prefetch
    {:state @store
     :opts opts
     :now-ms (now-for-request env @store opts)
     :started-at-ms ((:now-ms env))})))

(defn- interpret-full-load-result!
  [env store opts result]
  (let [result* (apply-result! store result)]
    (if-let [command (first (:commands result*))]
      (let [on-progress (:on-progress opts)
            now-ms-fn (:now-ms env)]
        (-> (request-history-bundle! env on-progress (:request command))
            (.then (fn [bundle]
                     (let [completed-at-ms (now-ms-fn)]
                       (apply-result!
                        store
                        (workflow/complete-history-load
                         {:state @store
                          :request-signature (:request-signature command)
                          :completed-at-ms completed-at-ms
                          :bundle bundle}))
                       bundle)))
            (.catch (fn [err]
                      (let [completed-at-ms (now-ms-fn)]
                        (apply-result!
                         store
                         (workflow/complete-history-load
                          {:state @store
                           :request-signature (:request-signature command)
                           :completed-at-ms completed-at-ms
                           :error err})))
                      nil))))
      (js/Promise.resolve nil))))

(defn load-portfolio-optimizer-history-effect
  ([_env store]
   (load-portfolio-optimizer-history-effect _env nil store nil))
  ([env _ store opts]
   (let [opts* (or opts {})
         now-ms (now-for-request env @store opts*)
         started-at-ms ((:now-ms env))]
     (if (workflow/selection-prefetch? opts*)
       (drain-selection-prefetch! env store (assoc opts*
                                                   :now-ms now-ms
                                                   :started-at-ms started-at-ms))
       (interpret-full-load-result!
        env
        store
        (assoc opts* :now-ms now-ms)
        (workflow/begin-history-load
         {:state @store
          :opts (assoc opts* :now-ms now-ms)
          :now-ms now-ms
          :started-at-ms started-at-ms}))))))
