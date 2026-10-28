(ns hyperopen.vol-range.application.ladder-settings
  "The settings a ladder was placed with (HO-SPEC-014 v2.1 sections 6.6, 7.4,
  10.2 step 1).

  Every grid placed from a review records `:settings`: the normalized
  effective draft frozen with the review, the inventory limits in base units
  (from the plan's inventory outcomes, so limits entered in USDC are already
  converted), and the plan's totals and inventory outcomes. Preview update
  reads the deployed draft; repair bounds read the deployed limits. Grids
  placed before settings were recorded have none, and every reader treats the
  key as optional.

  Pure and A0-scan safe: requires nothing from the execution foundations. The
  values are plain data (decimal strings, keywords, booleans, counts), so they
  round-trip through the journal unchanged. Nothing here is a secret."
  (:require [hyperopen.trading.vol-range-draft :as vol-range-draft]))

(defn- limits
  "{:core :target :maximum} in base units as strings (nil when absent)."
  [plan]
  (let [outcomes (:inventory-outcomes plan)]
    {:core (:core outcomes)
     :target (:target outcomes)
     :maximum (:maximum outcomes)}))

(defn settings-from-plan
  "The `:settings` map for a grid placed from the reviewed `plan`, with
  `draft` the effective draft frozen with that review. `:draft` is nil when no
  draft was frozen (never the defaults, which would misstate the ladder)."
  [plan draft]
  (when (map? plan)
    {:plan-id (:plan-id plan)
     :draft (when (map? draft) (vol-range-draft/normalize-vol-range-draft draft))
     :limits (limits plan)
     :totals (:totals plan)
     :inventory-outcomes (:inventory-outcomes plan)}))
