(ns hyperopen.runtime.effect-order.outcome-chart-policies
  "Effect-order policies for the outcome chart's All answers mode, merged into
  `hyperopen.runtime.effect-order-contract`. Mirrored in the Lean corpus at
  spec/lean/Hyperopen/Formal/EffectOrderContract.lean.

  Both actions project the plan first, then fan out one low-priority candle
  fetch per drawn answer (at most eight; the info client runs four at a
  time), so duplicate heavy effects are expected.")

(def ^:private fan-out-policy
  {:required-phase-order [:projection :heavy-io]
   :require-projection-before-heavy? true
   :allow-duplicate-heavy-effects? true
   :heavy-effect-ids #{:effects/fetch-candle-snapshot}})

(def policy-by-action-id
  {:actions/set-outcome-chart-mode fan-out-policy
   :actions/sync-outcome-answer-chart fan-out-policy})
