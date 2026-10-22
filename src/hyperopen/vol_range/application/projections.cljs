(ns hyperopen.vol-range.application.projections
  "Vol Range projections over app state (HO-SPEC-014 v2.1 sections 4, 11.1).

  `plan-for-state` is the memoized planner run; `ticket-model`, `review-model`
  and `chart-model` return display-ready, exact-key maps documented in
  `hyperopen.schema.vol-range-contracts`. All time enters as `now-ms`.
  VR-24: pure reads; nothing here builds an exchange action."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.display :as display]
            [hyperopen.vol-range.application.plan-state :as ps]
            [hyperopen.vol-range.application.review-model :as rm]
            [hyperopen.vol-range.application.ticket-model :as tm]))

(def plan-for-state ps/plan-for-state)
(def ticket-model tm/ticket-model)
(def review-model rm/review-model)
(def eligibility-for-state tm/eligibility-for-state)
(def ticket-visible? tm/visible?)

(defn- weight [size max-size]
  (if (and size max-size (precision/pos-dec? max-size))
    (let [r (precision/divide size max-size 4 :half-up)]
      (js/Number (precision/decimal->string r)))
    1))

(defn- chart-legs [plan]
  (let [sizes (keep #(display/parse (:size %)) (:legs plan))
        max-size (when (seq sizes) (reduce precision/dec-max sizes))]
    (mapv (fn [leg]
            {:leg-id (:leg-id leg)
             :side (:side leg)
             :price (:price leg)
             :size (:size leg)
             :weight (weight (display/parse (:size leg)) max-size)
             :label (str (if (= :buy (:side leg)) "Buy " "Sell ")
                         (display/exact (:size leg)) " @ " (display/exact (:price leg)))})
          (:legs plan))))

(defn- chart-bands [bands]
  [{:sigma 1 :lower (:minus-1 bands) :upper (:plus-1 bands)}
   {:sigma 2 :lower (:minus-2 bands) :upper (:plus-2 bands)}])

(defn chart-model
  "Draft overlay model: nil unless the ticket is visible and bands are shown;
  also nil while no anchor resolves."
  [state now-ms]
  (when (and (tm/visible? state)
             (:show-bands? (tm/ui-flags state)))
    (let [plan (ps/plan-for-state state now-ms)
          anchor (get-in plan [:model :anchor :price])
          model (:model plan)]
      (when anchor
        {:stage :draft
         :stage-label "Draft"
         :anchor {:price anchor :label (str "Anchor " (display/exact anchor))}
         :shifted-anchor (when (and (get-in model [:tilt :enabled?]) (:shifted-anchor model))
                           {:price (:shifted-anchor model)
                            :label (str "Shifted anchor " (display/exact (:shifted-anchor model)))})
         :bands (chart-bands (:bands model))
         :legs (chart-legs plan)}))))

(defonce ^:private last-chart-model (volatile! nil))

(defn stable-chart-model
  "`chart-model`, returning the previous value when the new one is equal so the
  overlay interop's identical? check skips repaint on unrelated ticks."
  [state now-ms]
  (let [next-model (chart-model state now-ms)
        previous @last-chart-model]
    (if (= next-model previous)
      previous
      (do (vreset! last-chart-model next-model)
          next-model))))
