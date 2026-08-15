(ns hyperopen.portfolio.optimizer.application.view-model.setup-history-assumption-rail
  "Right-rail summary projection over the history-assumption cards: a one-line
  summary per asset plus compact per-asset facts (final modeled basket first
  among the proxy facts) for the expanded state."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model.setup-history-assumption-cards :as assumption-cards]
            [hyperopen.portfolio.optimizer.application.view-model.setup-history-assumption-exposure :as exposure]))

(defn- diagnostics-cell-value
  [card cell-key]
  (some #(when (= cell-key (:key %)) (:value %))
        (:diagnostics card)))

(defn- rail-observations-label
  "History-day label for a rail pair. While the card's proxy history is still
  fetching, a bare \"No usable native returns\" is a mid-flight falsehood -
  say loading instead (mirrors the card diagnostics cell)."
  [card observations]
  (if (and (:history-loading? card)
           (not (and observations (pos? observations))))
    "Loading history…"
    (exposure/observations-label observations)))

(defn- rail-summary-pairs
  ;; No "Status" pair: the row's own status chip already carries it, and a
  ;; second textual restatement per asset was pure duplication (2026-07-10
  ;; comprehension pass).
  [card]
  (let [proxy? (= :proxy (:mode card))
        final-rows (get-in card [:final-basket :rows])]
    (->> [["Assumption mode" (or (:mode-label card) "Not chosen")]
          (when proxy?
            ["Proxy assets" (if (seq (:selected-proxies card))
                              (str/join ", " (map :label (:selected-proxies card)))
                              "None selected")])
          ;; The confidence-shrunk split that actually drives covariance
          ;; synthesis - never the raw prior.
          (when (and proxy? (seq final-rows))
            ["Final modeled basket" (exposure/basket-summary-text final-rows " · ")])
          (when proxy?
            ["Relationship strength" (or (:relationship-label card) "--")])
          ["Modeled volatility" (or (get-in card [:volatility :percent-label]) "--")]
          ["Max allocation cap" (or (get-in card [:max-weight :percent-label]) "--")]
          (when proxy?
            ["Regression confidence" (diagnostics-cell-value card :regression-confidence)])
          ;; Proxy cards report the window the risk model estimates over (the
          ;; proxy-extended shared window), never the asset's own short overlap
          ;; — that one reads as "the model only uses N days" and is demoted to
          ;; its own calibration line.
          (if (and proxy? (:covariance-observations card))
            ["History used" (str (rail-observations-label card (:covariance-observations card))
                                 " · via proxies")]
            ["History used" (rail-observations-label card (:observations card))])
          (when proxy?
            ["Calibration overlap" (rail-observations-label card (:observations card))])]
         (keep identity)
         vec)))

(defn history-assumption-rail-model
  "Right-rail summary of every asset in the assumption workflow: a one-line
  summary per asset, with the full compact facts behind it. No aggregate ready banner and no disclosure
  note (2026-07-10): \"ready\" belongs to the run verdict + Data health, and
  the results-disclosure fact already surfaces as the folded
  :proxy-history-used data note."
  ([state draft readiness history-load-state]
   (history-assumption-rail-model state draft readiness history-load-state nil))
  ([state draft readiness history-load-state opts]
   (let [{:keys [cards applicable? recommended-count recommended-actions]}
         (assumption-cards/history-assumption-cards state
                                                    draft
                                                    readiness
                                                    history-load-state
                                                    opts)
         any-loading? (boolean (some :history-loading? cards))
         ;; "Ready to run" judged while proxy history is still fetching is the
         ;; same mid-flight falsehood as a green Configured chip - completeness
         ;; against a not-yet-loaded usable set flips on its own moments later.
         all-configured? (and (seq cards)
                              (not any-loading?)
                              (every? :engine-applied? cards))]
     {:applicable? (boolean applicable?)
      :rows (mapv (fn [card]
                    {:instrument-id (:instrument-id card)
                     :label (:label card)
                     :status (:status card)
                     :status-label (:status-label card)
                     :mode (:mode card)
                     :configured? (:engine-applied? card)
                     :history-loading? (boolean (:history-loading? card))
                     :summary (:summary card)
                     :summary-pairs (rail-summary-pairs card)})
                  cards)
      :all-configured? all-configured?
      :any-loading? any-loading?
      :configured-count (count (filter :engine-applied? cards))
      ;; Same aggregates the center section's apply-all banner keys off, passed
      ;; through untouched so the rail's shortcut can never disagree with the
      ;; banner about how many recommendations are applicable.
      :recommended-count (or recommended-count 0)
      :recommended-actions recommended-actions})))
