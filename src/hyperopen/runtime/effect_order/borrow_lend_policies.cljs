(ns hyperopen.runtime.effect-order.borrow-lend-policies
  "Effect-order policies for the Earn (HyperCore borrow/lend) actions, merged
  into `hyperopen.runtime.effect-order-contract`. Mirrored in the Lean corpus
  at spec/lean/Hyperopen/Formal/EffectOrderContract.lean.")

(def ^:private fetch-effect-ids
  #{:effects/api-fetch-borrow-lend-reserves
    :effects/api-fetch-borrow-lend-user-state
    :effects/api-fetch-borrow-lend-interest})

(def policy-by-action-id
  {:actions/load-borrow-lend
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids fetch-effect-ids}

   :actions/load-borrow-lend-route
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids fetch-effect-ids}

   :actions/submit-borrow-lend
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-submit-borrow-lend}}})
