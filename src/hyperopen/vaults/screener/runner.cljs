(ns hyperopen.vaults.screener.runner
  "Interpreter for a vault-screener run.

  A run walks its address list in order (largest TVL first). Each vault's
  history comes from, in order: this session's memory, the IndexedDB cache
  (fresh within `model/cache-ttl-ms`), or one paced `vaultDetails` request.
  Requests are serialized, spaced by the run's interval, sent at low info-client
  priority, and held back while the info client reports a rate-limit cooldown
  (which also doubles the interval for the rest of the run).

  The loop re-reads the store before every step and exits as soon as the run
  is paused, cancelled or replaced, so pause/cancel are plain state writes.
  Only one loop is ever live: starting one retires any other."
  (:require [hyperopen.vaults.screener.metrics :as screener-metrics]
            [hyperopen.vaults.screener.model :as model]
            [hyperopen.vaults.screener.saved-views :as saved-views]))

(def screener-path
  [:vaults :screener])

(def run-path
  (conj screener-path :run))

(def results-path
  (conj screener-path :results))

(def cache-index-path
  (conj screener-path :cache-index))

(def saved-views-path
  [:vaults :saved-views])

(def ^:private index-retention-ms
  (* 7 24 60 60 1000))

(defonce ^:private portfolio-memo
  (atom {}))

(defonce ^:private active-loop
  (atom nil))

(defonce ^:private last-request-at-ms
  (atom nil))

(defn reset-runner-state!
  []
  (reset! portfolio-memo {})
  (reset! active-loop nil)
  (reset! last-request-at-ms nil))

(defn- ->promise
  [value]
  (if (instance? js/Promise value) value (js/Promise.resolve value)))

(defn- active?
  [store run-id loop-token]
  (let [run (get-in @store run-path)]
    (and (identical? loop-token @active-loop)
         (= run-id (:id run))
         (= :running (:status run)))))

(defn- error-message
  [error]
  (or (some-> error .-message str not-empty)
      "Request failed"))

(defn- rate-limit-until-ms
  [store]
  (or (get-in @store [:websocket-ui :info-rate-limit :until-ms]) 0))

(defn- rate-limit-count
  [store]
  (or (get-in @store [:websocket-ui :info-rate-limit :count]) 0))

(defn- update-run!
  [store run-id f & args]
  (swap! store
         (fn [state]
           (if (= run-id (get-in state (conj run-path :id)))
             (apply update-in state run-path f args)
             state))))

(defn- record-result!
  [store run-id address result source]
  (swap! store
         (fn [state]
           (if (= run-id (get-in state (conj run-path :id)))
             (-> state
                 (assoc-in (conj results-path address) result)
                 (update-in run-path
                            (fn [run]
                              (cond-> (update run :done (fnil inc 0))
                                (= source :cache) (update :cached (fnil inc 0))
                                (= source :fetch) (update :fetched (fnil inc 0))
                                (:error result) (update :failed (fnil inc 0))))))
             state))))

(defn- remember-cache-entry!
  [{:keys [store persist-index!]} address saved-at-ms now-ms]
  (swap! store assoc-in (conj cache-index-path address) saved-at-ms)
  (when (fn? persist-index!)
    (persist-index! (into {}
                          (filter (fn [[_ ms]] (< (- now-ms ms) index-retention-ms)))
                          (get-in @store cache-index-path))
                    now-ms)))

(defn- memo-portfolio
  [address now-ms]
  (let [{:keys [saved-at-ms portfolio]} (get @portfolio-memo address)]
    (when (model/fresh-cache-entry? saved-at-ms now-ms)
      portfolio)))

(defn- cached-portfolio!
  "Resolves the vault's fresh cached portfolio, or nil."
  [{:keys [load-record! now-ms]} address]
  (if-let [portfolio (memo-portfolio address (now-ms))]
    (js/Promise.resolve portfolio)
    (if (fn? load-record!)
      (-> (->promise (load-record! address))
          (.then (fn [{:keys [saved-at-ms portfolio]}]
                   (when (model/fresh-cache-entry? saved-at-ms (now-ms))
                     (swap! portfolio-memo assoc address {:saved-at-ms saved-at-ms
                                                          :portfolio portfolio})
                     portfolio)))
          (.catch (fn [_] nil)))
      (js/Promise.resolve nil))))

(defn- wait-for-slot!
  "Sleeps until the next request may start: the run's spacing since the
  previous request, and any info-client rate-limit cooldown. A cooldown seen
  for the first time doubles the run's interval (capped)."
  [{:keys [store now-ms sleep-ms!]} run-id seen-rate-limits]
  (let [run (get-in @store run-path)
        interval (or (:interval-ms run) model/request-interval-ms)
        limit-count (rate-limit-count store)
        new-limit? (> limit-count @seen-rate-limits)
        interval* (if new-limit?
                    (min model/max-request-interval-ms (* 2 interval))
                    interval)
        now (now-ms)
        spacing-wait (if-let [last-at @last-request-at-ms]
                       (- (+ last-at interval*) now)
                       0)
        cooldown-until (rate-limit-until-ms store)
        cooldown-wait (- cooldown-until now)
        wait (max 0 spacing-wait cooldown-wait)]
    (reset! seen-rate-limits limit-count)
    (update-run! store run-id assoc
                 :interval-ms interval*
                 :backoff-until-ms (when (pos? cooldown-wait) cooldown-until))
    (if (pos? wait)
      (->promise (sleep-ms! wait))
      (js/Promise.resolve nil))))

(defn- fetch-portfolio!
  [{:keys [store request-vault-details! persist-record! now-ms] :as deps}
   run-id address seen-rate-limits]
  (-> (wait-for-slot! deps run-id seen-rate-limits)
      (.then (fn [_]
               (update-run! store run-id assoc :backoff-until-ms nil)
               (reset! last-request-at-ms (now-ms))
               (request-vault-details! address {:priority :low
                                                :dedupe-key [:vault-screener address]})))
      (.then (fn [details]
               (let [portfolio (screener-metrics/cacheable-portfolio (:portfolio details))
                     saved-at (now-ms)]
                 (swap! portfolio-memo assoc address {:saved-at-ms saved-at
                                                      :portfolio portfolio})
                 (when (fn? persist-record!)
                   (persist-record! address portfolio saved-at))
                 (remember-cache-entry! deps address saved-at saved-at)
                 portfolio)))))

(defn- evaluate-portfolio
  [compute-metrics portfolio window]
  (try
    (compute-metrics {:portfolio portfolio} window)
    (catch :default error
      {:error (error-message error)})))

(defn- process-address!
  [{:keys [store compute-metrics] :as deps} run-id address seen-rate-limits]
  (let [window (get-in @store (conj run-path :config :window))]
    (-> (cached-portfolio! deps address)
        (.then (fn [portfolio]
                 (if portfolio
                   (record-result! store run-id address
                                   (evaluate-portfolio compute-metrics portfolio window)
                                   :cache)
                   (-> (fetch-portfolio! deps run-id address seen-rate-limits)
                       (.then (fn [portfolio*]
                                (record-result! store run-id address
                                                (evaluate-portfolio compute-metrics portfolio* window)
                                                :fetch)))
                       (.catch (fn [error]
                                 (record-result! store run-id address
                                                 {:error (error-message error)}
                                                 :fetch))))))))))

(defn- pass-count
  [state]
  ;; Thresholds are live UI state (editable mid-run), so count against them.
  (let [config (model/normalize-config (get-in state [:vaults-ui :screener]))
        results (vals (get-in state results-path))]
    (count (filter #(= :pass (:status (model/evaluate config %))) results))))

(defn- persist-views!
  [{:keys [store persist-saved-views! now-ms]}]
  (when (fn? persist-saved-views!)
    (persist-saved-views! (vec (get-in @store saved-views-path)) (now-ms))))

(defn- finish-view-refresh!
  "A refresh run of a saved view rewrites that view's members and numbers."
  [{:keys [store now-ms show-toast!] :as deps} run]
  (let [state @store
        view (saved-views/view-by-id (get-in state saved-views-path) (:saved-view-id run))]
    (when view
      (let [view* (saved-views/refresh-view view run (get-in state results-path) (now-ms))]
        (swap! store update-in saved-views-path saved-views/upsert-view view*)
        (persist-views! deps)
        (when (fn? show-toast!)
          (show-toast! store :success
                       (str "Refreshed \u201c" (:name view*) "\u201d: "
                            (saved-views/diff-summary (:last-diff view*)) ".")))))))

(defn- finish-run!
  [{:keys [store now-ms show-toast!] :as deps} run-id]
  (let [state @store
        run (get-in state run-path)]
    (when (and (= run-id (:id run)) (= :running (:status run)))
      (update-run! store run-id assoc :status :done :finished-at-ms (now-ms) :backoff-until-ms nil)
      (if (:saved-view-id run)
        (finish-view-refresh! deps run)
        (when (fn? show-toast!)
          (show-toast! store :success
                       (str "Vault screen ready: " (pass-count @store)
                            " of " (count (:addresses run)) " vaults pass.")))))))

(defn- next-pending-address
  [state]
  (let [results (get-in state results-path)]
    (some (fn [address]
            (when-not (contains? results address) address))
          (get-in state (conj run-path :addresses)))))

(defn start-run!
  "Start (or resume) the run identified by `run-id`. Resolves when the loop
  exits — the run finished, or it was paused, cancelled or replaced."
  [{:keys [store sleep-ms!] :as deps} run-id]
  (let [loop-token (js-obj)
        seen-rate-limits (atom (rate-limit-count store))]
    (reset! active-loop loop-token)
    (letfn [(step []
              (if-not (active? store run-id loop-token)
                (js/Promise.resolve nil)
                (if-let [address (next-pending-address @store)]
                  (-> (process-address! deps run-id address seen-rate-limits)
                      ;; Yield between vaults so a burst of cached vaults never
                      ;; blocks rendering.
                      (.then (fn [_] (->promise (sleep-ms! 0))))
                      (.then (fn [_] (step))))
                  (do (finish-run! deps run-id)
                      (js/Promise.resolve nil)))))]
      (step))))

(defn load-cache-index!
  [{:keys [store load-index!]}]
  (if (fn? load-index!)
    (-> (->promise (load-index!))
        (.then (fn [index]
                 (swap! store update-in screener-path
                        (fn [screener]
                          (-> screener
                              (update :cache-index #(merge (or index {}) %))
                              (assoc :cache-index-loaded? true))))))
        (.catch (fn [_]
                  (swap! store assoc-in (conj screener-path :cache-index-loaded?) true))))
    (js/Promise.resolve nil)))

(defn load-saved-views!
  [{:keys [store load-saved-views!]}]
  (if (fn? load-saved-views!)
    (-> (->promise (load-saved-views!))
        (.then (fn [views]
                 (swap! store
                        (fn [state]
                          (-> state
                              (update-in saved-views-path
                                         (fn [current]
                                           (reduce saved-views/upsert-view
                                                   (saved-views/normalize-views views)
                                                   (or current []))))
                              (assoc-in [:vaults :saved-views-loaded?] true))))))
        (.catch (fn [_]
                  (swap! store assoc-in [:vaults :saved-views-loaded?] true))))
    (js/Promise.resolve nil)))

(defn persist-saved-views-effect!
  [deps]
  (->promise (persist-views! deps)))
