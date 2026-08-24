(ns hyperopen.portfolio.optimizer.instrument-keyed-codec
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.ids :as ids]))

(def enum-value-keys
  #{:behavior
    :code
    :default-order-type
    :fee-mode
    :funding-source
    :instrument-type
    :kind
    :market-type
    ;; Equal-risk result sections (:risk-contributions / :equal-risk-solver):
    ;; contribution method, exact/approximate quality, solver termination, and
    ;; the initializer name all cross the worker boundary as keyword values.
    :method
    :model
    :objective-kind
    :order-type
    :position-side
    :prior-source
    :quality
    :reason
    :regression-status
    :regression-skip-reason
    :relationship-strength
    :selected-initialization
    :seed-kind
    :side
    :source
    :status
    :strategy
    :termination-reason
    :type})

(def instrument-keyed-map-keys
  #{:by-instrument
    :return-series-by-instrument
    :price-series-by-instrument
    :raw-price-series-by-instrument
    :served-observations-by-instrument
    :cadence-by-instrument
    :expected-return-series-by-instrument
    :expected-return-intervals-by-instrument
    :funding-by-instrument
    :weights-by-instrument
    :history-assumptions
    ;; Proxy history-assumption payloads: prior/regression exposures and the
    ;; short-overlap return series are keyed by proxy instrument-id, both on the
    ;; request (engine-shaped assumption) and in result diagnostics.
    :proxy-prior-weights
    :prior-weights
    :proxy-returns-by-id
    :regression-beta
    :regression-beta-raw
    :final-beta
    :per-asset-overrides
    :per-perp-leverage-caps
    :prices-by-id
    :cost-contexts-by-id
    :fee-bps-by-id
    :return-decomposition-by-instrument
    :expected-returns-by-instrument
    :current-weights-by-instrument
    :target-weights-by-instrument
    ;; Result-only map (engine/payload.cljs). Without this entry its keys survive
    ;; the worker boundary as keywords (js->clj :keywordize-keys true) and later
    ;; stringify to leading-colon ids (":perp:BTC"), spawning phantom blocked
    ;; "market-metadata-missing" rows in the rebalance preview.
    :current-portfolio-weights-by-instrument
    :weight-sensitivity-by-instrument
    ;; Equal-risk :risk-contributions submaps (result-only).
    :relative-contributions-by-instrument
    :target-relative-contributions-by-instrument
    ;; Equal-risk :risk-structure submaps (result-only): the correlation-view
    ;; decomposition and P&L-to-portfolio correlations.
    :standalone-share-by-instrument
    :diversification-share-by-instrument
    :pnl-portfolio-correlation-by-instrument
    :pair-metadata
    :labels-by-instrument})

;; Compatibility only. Normalization is key-driven and does not depend on this
;; list when new request or result payloads add another nesting level.
(def instrument-keyed-map-paths
  [[:current-portfolio :by-instrument]
   [:history :return-series-by-instrument]
   [:history :price-series-by-instrument]
   [:history :raw-price-series-by-instrument]
   [:history :served-observations-by-instrument]
   [:history :cadence-by-instrument]
   [:history :expected-return-series-by-instrument]
   [:history :expected-return-intervals-by-instrument]
   [:history :funding-by-instrument]
   [:black-litterman-prior :weights-by-instrument]
   [:constraints :per-asset-overrides]
   [:constraints :per-perp-leverage-caps]
   [:execution-assumptions :prices-by-id]
   [:execution-assumptions :cost-contexts-by-id]
   [:execution-assumptions :fee-bps-by-id]
   [:payload :return-decomposition-by-instrument]
   [:payload :expected-returns-by-instrument]
   [:payload :current-weights-by-instrument]
   [:payload :target-weights-by-instrument]
   [:payload :diagnostics :weight-sensitivity-by-instrument]
   [:payload :diagnostics :pair-metadata]
   [:return-decomposition-by-instrument]
   [:expected-returns-by-instrument]
   [:current-weights-by-instrument]
   [:target-weights-by-instrument]
   [:diagnostics :weight-sensitivity-by-instrument]
   [:diagnostics :pair-metadata]])

(defn- keyword-value
  [value]
  (cond
    (keyword? value) value
    (string? value) (some-> value coercion/non-blank-text keyword)
    :else value))

(def instrument-id-key ids/instrument-id-key)

(defn stringify-instrument-keyed-map
  [value]
  (if (map? value)
    (into {}
          (map (fn [[key item]]
                 [(ids/instrument-id-key key) item]))
          value)
    value))

(declare normalize-instrument-keyed-maps)

(defn normalize-wire-values
  [value]
  (cond
    (map? value)
    (into {}
          (map (fn [[key item]]
                 (let [item* (normalize-wire-values item)]
                   [key (if (contains? enum-value-keys key)
                          (keyword-value item*)
                          item*)])))
          value)

    (vector? value)
    (mapv normalize-wire-values value)

    (seq? value)
    (doall (map normalize-wire-values value))

    :else value))

(defn normalize-instrument-keyed-maps
  [value]
  (cond
    (map? value)
    (into {}
          (map (fn [[key item]]
                 (let [item* (normalize-instrument-keyed-maps item)]
                   [key (if (and (contains? instrument-keyed-map-keys key)
                                 (map? item*))
                          (stringify-instrument-keyed-map item*)
                          item*)])))
          value)

    (vector? value)
    (mapv normalize-instrument-keyed-maps value)

    (seq? value)
    (doall (map normalize-instrument-keyed-maps value))

    :else value))

(defn- update-existing-in
  [value path f]
  (if (nil? (get-in value path))
    value
    (update-in value path f)))

(defn- normalize-black-litterman-view-weights
  "Stringifies the instrument-keyed `:weights` map inside each Black-Litterman
  view.

  Total by construction, which matters more here than the rewrite does: this
  runs inside the worker's message listener on every inbound message, so
  throwing on a payload it does not recognise takes the listener down rather
  than producing a result. `mapv` over a non-sequence used to do exactly that
  for a scalar `:views`, and -- worse, because it was silent -- shredded a
  string `:views` into a vector of characters, since strings are seqable in
  ClojureScript.

  The rest of the boundary normalization already works this way: every walk in
  this namespace ends in `:else value`, normalizing what it recognises and
  passing through what it does not. This is the one step that did not."
  [value]
  (update-existing-in
   value
   [:return-model :views]
   (fn [views]
     (if (sequential? views)
       (mapv (fn [view]
               (update-existing-in view [:weights] stringify-instrument-keyed-map))
             views)
       views))))

(defn normalize-boundary-node
  "`normalize-wire-values` and `normalize-instrument-keyed-maps` in a single
  post-order walk instead of two.

  This runs on every message crossing the worker boundary in both directions,
  including every progress tick, so it was walking a whole engine payload twice
  per message for two rules that never interact.

  Output-identical to `(-> value normalize-wire-values
  normalize-instrument-keyed-maps)`, and the reason is stronger than the two
  key sets happening to be disjoint today -- the rules commute outright.
  `keyword-value` changes a value only when it is a string or a keyword, and
  both walks leave scalars alone; `stringify-instrument-keyed-map` changes a
  value only when it is a map, and `keyword-value` leaves maps alone. So
  applying both at a node in either order, before or after the recursion,
  lands in the same place. Nothing here depends on the key sets, and a future
  key landing in both would not break it.

  What the equivalence DOES depend on is that this stays post-order: the child
  is fully normalized before either key rule is applied to it. A pre-order
  variant -- apply the key rules, then recurse -- is genuinely wrong, and
  measurably so: it diverges on nested instrument-keyed maps, because
  stringifying a parent's keys before its children are normalized loses the
  inner rewrite. instrument_keyed_codec_test pins that.

  Public so the equivalence can be asserted directly against the two-pass
  composition. Asserting it through `normalize-worker-boundary` would drag in
  `normalize-black-litterman-view-weights`, which is a root-level step, not
  part of the walk."
  [value]
  (cond
    (map? value)
    (into {}
          (map (fn [[key item]]
                 (let [normalized (normalize-boundary-node item)
                       enumerated (if (contains? enum-value-keys key)
                                    (keyword-value normalized)
                                    normalized)]
                   [key (if (and (contains? instrument-keyed-map-keys key)
                                 (map? enumerated))
                          (stringify-instrument-keyed-map enumerated)
                          enumerated)])))
          value)

    (vector? value)
    (mapv normalize-boundary-node value)

    (seq? value)
    (doall (map normalize-boundary-node value))

    :else value))

(defn normalize-worker-boundary
  [value]
  (-> value
      normalize-boundary-node
      normalize-black-litterman-view-weights))
