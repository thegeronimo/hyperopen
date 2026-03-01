(ns hyperopen.views.portfolio.vm.summary
  (:require [hyperopen.portfolio.metrics.parsing :as parsing]
            [hyperopen.views.portfolio.vm.utils :as utils]
            [hyperopen.views.portfolio.vm.history :as vm-history]))

(defn normalize-summary-by-key
  [summary-by-key]
  (or summary-by-key {}))

(defn selected-summary-key
  [scope time-range]
  (let [scope-key (utils/canonical-summary-key scope)
        time-key (vm-history/range-all-time-key time-range)]
    (keyword (str (name scope-key) "-" (name time-key)))))

(defn summary-key-candidates
  [scope time-range]
  (let [primary (selected-summary-key scope time-range)
        scope-key (utils/canonical-summary-key scope)]
    [primary
     (keyword (str (name scope-key) "-all"))
     :all-all]))

(defn selected-summary-entry
  [summary-by-key scope time-range]
  (let [normalized (normalize-summary-by-key summary-by-key)
        candidates (summary-key-candidates scope time-range)]
    (loop [remaining candidates]
      (if (empty? remaining)
        nil
        (let [candidate (first remaining)
              entry (get normalized candidate)]
          (if entry
            entry
            (recur (rest remaining))))))))

(defn derived-summary-entry
  [entry time-range]
  (if (or (nil? entry) (= time-range :all-time))
    entry
    (let [last-ms (some-> (:accountValueHistory entry) last parsing/history-point-time-ms)
          cutoff-ms (vm-history/summary-window-cutoff-ms time-range last-ms)]
      (if cutoff-ms
        (let [account-history (vm-history/history-window-rows (:accountValueHistory entry) cutoff-ms)
              pnl-history (vm-history/history-window-rows (:pnlHistory entry) cutoff-ms)
              base-pnl (some-> pnl-history first parsing/history-point-value)]
          (assoc entry
                 :accountValueHistory account-history
                 :pnlHistory (vm-history/rebase-history-rows pnl-history (or base-pnl 0))))
        entry))))