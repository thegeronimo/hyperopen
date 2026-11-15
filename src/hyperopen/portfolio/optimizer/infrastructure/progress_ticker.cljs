(ns hyperopen.portfolio.optimizer.infrastructure.progress-ticker
  (:require [hyperopen.platform :as platform]
            [hyperopen.portfolio.optimizer.application.progress :as progress]))

(def ^:private ticker-watch-key ::optimization-progress-ticker)

(def default-interval-ms
  "Trickle/clock cadence. ~8 frames/sec keeps the bar gliding and the elapsed
  timer counting in tenths with negligible render cost (the app already
  re-renders far more often on live market data)."
  120)

(defn install-optimization-progress-ticker!
  "While an optimization run is in flight, tick a smoothed display percent and a
  fresh wall clock into the progress map a few times a second.

  The optimizer worker emits progress sparsely and in bursts (each synchronous
  solve/frontier batch flushes all its messages at once), so between bursts the
  store does not change, no render is scheduled, and both the single progress bar
  and the elapsed timer freeze until the next burst lands. This watcher runs an
  interval only while status is :running that nudges :display-percent / :now-ms,
  guaranteeing a steady re-render so the bar trickles and the clock counts
  smoothly. It is purely cosmetic: it never runs more than the trickle headroom
  ahead of true progress and stops the moment the run settles."
  [{:keys [store progress-path now-ms-fn set-interval-fn clear-interval-fn
           interval-ms tick-fn]
    :or {now-ms-fn platform/now-ms
         set-interval-fn platform/set-interval!
         clear-interval-fn platform/clear-interval!
         interval-ms default-interval-ms
         tick-fn progress/tick-progress}}]
  (let [path (vec progress-path)
        status-path (conj path :status)
        timer (atom nil)
        running? (fn [state] (= :running (get-in state status-path)))
        stop! (fn []
                (when-let [id @timer]
                  (clear-interval-fn id)
                  (reset! timer nil)))
        start! (fn []
                 (when-not @timer
                   (reset! timer
                           (set-interval-fn
                            (fn [] (swap! store update-in path tick-fn (now-ms-fn)))
                            interval-ms))))]
    (remove-watch store ticker-watch-key)
    (add-watch store ticker-watch-key
               (fn [_ _ old-state new-state]
                 (let [was? (running? old-state)
                       now? (running? new-state)]
                   (when (not= was? now?)
                     (if now? (start!) (stop!))))))
    (if (running? @store) (start!) (stop!))
    store))
