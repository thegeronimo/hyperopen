(ns hyperopen.account.chart-fill-history-effects
  (:require [hyperopen.account.chart-fill-history :as chart-fill-history]
            [hyperopen.api.default.orders :as api-orders]
            [hyperopen.api.default.state :as api-state]))

;; 5 full pages = the API's 10000-fill horizon
(def ^:private max-requests-per-window 5)

(defn- request-user-fills-by-time!
  [address window opts]
  (api-orders/request-user-fills-by-time! {:post-info! api-state/post-info!} address window opts))

(defn- fetch-window!
  "All fills in [start-ms, end-ms]. `userFillsByTime` returns at most 2000
  rows, the OLDEST first, so a full page means more remain: page forward from
  the last returned time (inclusive; duplicates are merged away) until a short
  page. Resolves {:rows [...] :truncated? bool}."
  [request-fn address start-ms end-ms]
  (letfn [(step [cursor acc requests]
            ;; unaggregated, so a short page reliably means the window is done
            (-> (request-fn address {:start-ms cursor :end-ms end-ms :aggregate? false} {})
                (.then (fn [rows]
                         (let [rows* (if (sequential? rows) (vec rows) [])
                               acc* (into acc rows*)
                               last-ms (some->> rows* (keep chart-fill-history/fill-time-ms) seq (apply max))
                               full? (>= (count rows*) chart-fill-history/page-size)]
                           (cond
                             (not full?) {:rows acc* :truncated? false}
                             ;; no progress possible (a page of one millisecond) or the
                             ;; horizon is reached: stop and report the gap
                             (or (>= (inc requests) max-requests-per-window)
                                 (nil? last-ms)
                                 (<= last-ms cursor))
                             {:rows acc* :truncated? true}
                             :else (step last-ms acc* (inc requests))))))))]
    (step start-ms [] 0)))

(defn fetch-older-chart-fills!
  "Load the window of the account's fills just older than the covered
  history. The in-flight endTime is recorded first, so the action refuses a
  second request for the same window, and a response for another account or
  another window is dropped by `apply-older-window`."
  [{:keys [store address end-ms request-fn]
    :or {request-fn request-user-fills-by-time!}}]
  (swap! store chart-fill-history/begin-older-page address end-ms)
  (let [history (chart-fill-history/history-for @store address)]
    (if-not (and history (= end-ms (:loading-end-ms history)))
      (js/Promise.resolve nil)
      (let [start-ms (max chart-fill-history/history-floor-ms (- end-ms (:window-ms history)))]
        (-> (fetch-window! request-fn address start-ms end-ms)
            (.then (fn [{:keys [rows truncated?]}]
                     (swap! store chart-fill-history/apply-older-window address end-ms start-ms rows
                            {:truncated? truncated?})
                     rows))
            (.catch (fn [err]
                      (swap! store chart-fill-history/fail-older-page address end-ms)
                      (js/console.warn "Failed to load older chart fills:" err)
                      nil)))))))

(defn fetch-older-chart-fills-effect
  [_ store {:keys [address end-ms]}]
  (fetch-older-chart-fills! {:store store :address address :end-ms end-ms}))
