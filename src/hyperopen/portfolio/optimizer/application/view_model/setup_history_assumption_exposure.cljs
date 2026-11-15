(ns hyperopen.portfolio.optimizer.application.view-model.setup-history-assumption-exposure
  "Exposure-story projections for the proxy workflow card (split from
  view-model.setup-history-assumption-cards when the loading-visibility pass
  pushed it past the namespace size gate, 2026-07-07): the pre-run exposure
  preview, regression-estimate panel model, diagnostics strip, and the
  in-flight history-loading signals that keep every one of those verdicts
  honest while background fetches are still running."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.domain.history-assumption-proxy :as history-assumption-proxy]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]))

(def ^:private in-flight-prefetch-statuses
  #{:queued :loading})

(defn history-load-in-progress?
  "True while background history work is actually running: the aggregate load
  state reports :loading, or the selection-prefetch queue is non-idle."
  [state load-state]
  (let [prefetch (get-in state contracts/history-prefetch-path)]
    (boolean (or (= :loading (:status load-state))
                 (some? (:active-instrument-id prefetch))
                 (seq (:queue prefetch))))))

(defn proxy-in-flight-fn
  "Per-instrument \"history still fetching\" predicate: the instrument's own
  prefetch entry is queued/loading, or an aggregate load that may cover it is
  in progress."
  [state load-in-progress?]
  (let [by-id (get-in state (conj contracts/history-prefetch-path :by-instrument-id))]
    (fn [id]
      (boolean (or load-in-progress?
                   (contains? in-flight-prefetch-statuses
                              (get-in by-id [id :status])))))))

(defn selected-proxy-rows
  "The card's picked proxies as chips, label-resolved (works for reference-only
  proxies outside the universe). `usable-ids` is the aligned set that reached the
  risk model; a picked proxy not yet in it reads :loading? true ONLY while its
  history fetch is actually in flight — after the load settles, an unusable
  proxy is a real problem and must never keep claiming \"loading\"."
  [selected-ids resolve-label usable-ids in-flight?]
  (mapv (fn [id]
          {:instrument-id id
           :label (resolve-label id)
           :loading? (boolean (and (in-flight? id)
                                   (not (contains? (or usable-ids #{}) id))))})
        selected-ids))

(defn confidence-tier
  "Low/Medium/High tier for a [0,1] confidence fraction."
  [fraction]
  (cond
    (nil? fraction) "Low"
    (< fraction 0.33) "Low"
    (< fraction 0.66) "Medium"
    :else "High"))

(defn confidence-tier-label
  "Regression-confidence tier. When the preview ran the actual regression, the
  tier comes from the realized q (sample count AND fit quality). Until then it
  falls back to the sample-count upper bound n/(n+n0) - q can never exceed it,
  so the pre-data preview stays an honest ceiling."
  [preview observations]
  (if (= :estimated (get-in preview [:regression :status]))
    (confidence-tier (:confidence-q preview))
    (confidence-tier
     (when (and observations (pos? observations))
       (/ observations
          (+ observations history-assumption-proxy/confidence-observation-scale))))))

(defn specific-risk-tier-label
  [observations relationship-strength]
  (if (< (or observations 0) 60)
    "High"
    (case relationship-strength
      :low "High"
      :high "Moderate"
      "Medium")))

(defn basket-summary-text
  "\"ETH 60% / BTC 40%\"-style rendering of basket rows."
  ([rows] (basket-summary-text rows " / "))
  ([rows separator]
   (str/join separator
             (map (fn [{:keys [label weight-percent]}]
                    (str label " " (js/Math.round weight-percent) "%"))
                  rows))))

(defn final-model-line
  "Summary strip under the diagnostics cells. Once a final basket exists it IS
  the summary - never the prior dressed up as the model."
  [final-rows cap-percent-label]
  (if (seq final-rows)
    (str "Final model: " (basket-summary-text final-rows)
         " + specific risk"
         (if cap-percent-label
           (str " + " cap-percent-label " cap")
           " + cap"))
    "Final model: proxy basket + shrinkage + specific risk + cap"))

(defn observations-label
  [observations]
  (if (and observations (pos? observations))
    (str observations " days of returns")
    "No usable native returns"))

(defn- covariance-window-detail
  "Detail line under the covariance-window cell. Only rendered once the aligned
  window is known: the proxies carry the asset's risk links across the FULL
  shared window; the native overlap merely calibrates the basket weights."
  [covariance-observations observations]
  (when covariance-observations
    (if (and observations (pos? observations))
      (str "Extended via proxies · " observations "-day overlap calibrates weights")
      "Extended via proxies · no native overlap (prior weights only)")))

(defn proxy-diagnostics
  [{:keys [observations relationship-strength covariance-observations preview
           history-loading?]}]
  (let [window-observations (or covariance-observations observations)
        window-loading? (boolean (and history-loading?
                                      (not (and window-observations
                                                (pos? window-observations)))))]
    [{:key :regression-confidence
      :label "Regression confidence"
      :value (confidence-tier-label preview observations)
      :detail "R² used for confidence, not weights"}
     {:key :specific-risk
      :label "Specific risk"
      :value (specific-risk-tier-label observations relationship-strength)
      :detail "Unique risk not explained by proxies"}
     {:key :history-window
      :label "Covariance window"
      :value (if window-loading?
               "Loading history…"
               (observations-label window-observations))
      :detail (if window-loading?
                "Proxy history is still downloading"
                (covariance-window-detail covariance-observations observations))}]))

(defn exposure-preview
  "Pre-run exposure story for a proxy card, computed by the SAME domain pipeline
  the engine runs (`history-assumption-proxy/model-exposure`) over the same
  inputs the request builder hands the engine: the readiness request's flattened
  assumption entry, which carries the short-overlap regression series. While
  history/readiness are still assembling (or the entry is incomplete) there is
  no series yet, so the regression reports itself skipped and the final basket
  equals the prior - honest, and exactly what the engine would use."
  [readiness draft-entry id selected-ids]
  (when (seq selected-ids)
    (let [request-entry (get-in readiness [:request :history-assumptions id])]
      (history-assumption-proxy/model-exposure
       {:proxy-ids (vec selected-ids)
        :user-prior-weights (or (:proxy-prior-weights request-entry)
                                (history-assumptions/proxy-prior-weights draft-entry))
        :regression-series (:regression-series request-entry)}))))

(defn basket-rows
  "Weights map -> display rows in the user's selection order (deterministic and
  stable as chips come and go)."
  [weights-by-id ordered-ids resolve-label]
  (->> ordered-ids
       (keep (fn [id]
               (when-let [weight (get weights-by-id id)]
                 {:instrument-id id
                  :label (resolve-label id)
                  :weight weight
                  :weight-percent (* 100 weight)})))
       vec))

(def ^:private regression-skip-messages
  {:no-overlap "No return overlap with the proxies yet. Using the prior only."
   :insufficient-overlap "Not enough overlap to estimate. Using the prior only."
   :singular-regression "The overlap can't identify exposures. Using the prior only."
   :degenerate-overlap "The overlap can't identify exposures. Using the prior only."})

(defn regression-estimate-model
  "Panel B of the exposure story: the joint regression's own estimate (before
  confidence shrinkage), or why it was skipped. A skip while proxy history is
  still downloading is not a verdict, so the message says loading instead."
  [preview ordered-ids resolve-label history-loading?]
  (when-let [regression (:regression preview)]
    (if (= :estimated (:status regression))
      {:status :estimated
       :rows (basket-rows (:beta regression) ordered-ids resolve-label)
       :r2 (:r2 regression)
       :sample-count (:sample-count regression)
       :summary (str "R² " (.toFixed (:r2 regression) 2)
                     " · " (:sample-count regression) " observations")}
      {:status :skipped
       :reason (:reason regression)
       :loading? (boolean history-loading?)
       :sample-count (:sample-count regression)
       :message (if history-loading?
                  "Waiting for proxy history to load. The estimate updates automatically."
                  (get regression-skip-messages (:reason regression)
                       "Regression unavailable. Using the prior only."))})))
