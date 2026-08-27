(ns hyperopen.portfolio.optimizer.application.view-model.exposure
  "Pure view-model for the 2D exposure-map Positioning control. Turns the canonical draft
  constraints (plus the current portfolio exposure and the infeasible-control highlight set)
  into a flat display map the view renders: the policy targets/bands, the pad marker fractions,
  the band rectangle, the current-portfolio dot, the preset chips, the read-only generated-
  constraints echo numbers, and per-axis warning flags. All numbers come from the pure
  `exposure-policy` namespace so the view, actions, and tests stay in agreement."
  (:require [hyperopen.portfolio.optimizer.contracts.constants :as contracts-constants]
            [hyperopen.portfolio.optimizer.domain.exposure-policy :as policy]))

(defn snapshot->current-exposure
  "Reduce a current-portfolio snapshot to {:gross r :net r} exposure ratios (multiples of
  capital), or nil when capital is not loaded. The pad plots this as the faint 'current' dot."
  [snapshot]
  (let [nav (get-in snapshot [:capital :nav-usdc])
        gross (get-in snapshot [:capital :gross-exposure-usdc])
        net (get-in snapshot [:capital :net-exposure-usdc])]
    (when (and (number? nav) (pos? nav) (number? gross) (number? net))
      {:gross (/ gross nav)
       :net (/ net nav)})))

(defn- within?
  [v lo hi]
  (and (number? v)
       (or (not (number? lo)) (>= v lo))
       (or (not (number? hi)) (<= v hi))))

(def ^:private gross-eps 1e-9)

(defn net-gross-ratio
  "net / gross, or nil when gross is zero or numerically near zero (the UI
  renders — instead of a misleading percentage)."
  [net gross]
  (when (and (number? net) (number? gross) (> (js/Math.abs gross) gross-eps))
    (/ net gross)))

(defn exposure-preview
  "Honest pre-run preview: compares the CURRENT portfolio exposure to the target band and reports
  whether it is already on policy. The net band is a percentage of REALIZED gross, so the
  allowed net range for the current book is net-min/max widened by pct · current gross. It
  deliberately does NOT estimate a trade count — that needs a solve, and the setup panel is
  shown while constraints are being edited (which makes any prior run stale), so a number here
  would mislead. The exact trades appear in Results after Run."
  [{:keys [current-exposure constraints]}]
  (when current-exposure
    (let [{:keys [gross net]} current-exposure
          {:keys [gross-min gross-max net-min net-max]} constraints
          pct (policy/clamp-net-band-pct (:net-band-pct constraints))
          slack (* pct (max 0.0 (or gross 0.0)))
          gross-ok? (within? gross gross-min gross-max)
          net-ok? (within? net
                           (when (number? net-min) (- net-min slack))
                           (when (number? net-max) (+ net-max slack)))]
      {:current-exposure current-exposure
       :gross-ok? gross-ok?
       :net-ok? net-ok?
       :net-gross-ratio (net-gross-ratio net gross)
       :net-band-pct pct
       :net-target (when (and (number? net-min) (number? net-max))
                     (/ (+ net-min net-max) 2))
       :on-policy? (and gross-ok? net-ok?)})))

(defn- gross-highlighted?
  ;; `some` calls the set as a predicate, so an absent :highlighted-controls
  ;; would invoke nil rather than read as "nothing highlighted".
  [highlighted-controls]
  (boolean (some (or highlighted-controls #{}) [:gross-min :gross-max])))

(defn- net-highlighted?
  [highlighted-controls]
  (boolean (some (or highlighted-controls #{}) [:net-min :net-max])))

(defn- net-direction
  [net-target]
  (cond
    (and (number? net-target) (< 0.001 net-target)) :long
    (and (number? net-target) (< net-target -0.001)) :short
    :else :neutral))

(def equal-risk-net-output-copy
  "Resulting net is determined by Equal Risk from covariance and selected sides.")

(def inverse-volatility-net-output-copy
  "Resulting net is determined by Risk-weighted sizing from volatilities and selected sides.")

(defn- net-output-copy
  [objective-kind]
  (case objective-kind
    :inverse-volatility inverse-volatility-net-output-copy
    equal-risk-net-output-copy))

(defn exposure-map-model
  "Build the exposure-map display model. `current-exposure` is `{:gross r :net r}` (ratios of
  capital) or nil when the current portfolio is not loaded; `highlighted-controls` is the set of
  constraint keys the last run flagged infeasible; `zoom-level` is the trader's stored zoom
  (optimizer UI state) — the pad scale is fixed at one of the paired zoom levels and only the
  zoom control (never a drag) changes it."
  [{:keys [objective-kind constraints current-exposure highlighted-controls
           has-saved-default? zoom-level]}]
  (let [constraints* (or constraints {})
        covariance-only? (contains? contracts-constants/covariance-only-objective-kinds
                                    objective-kind)
        stored-policy (policy/constraints->policy constraints*)
        policy* (if covariance-only?
                  (assoc stored-policy :net-target 0.0 :net-band-pct 0.0)
                  stored-policy)
        active (policy/active-preset constraints*)
        gross-min (:gross-min constraints*)
        gross-max (:gross-max constraints*)
        net-min (:net-min constraints*)
        net-max (:net-max constraints*)
        ;; Fixed scale: the smallest zoom level framing the policy band and the current
        ;; portfolio dot, widened (never narrowed) by the trader's stored zoom.
        {:keys [axis] :as zoom} (policy/render-axis
                                 (assoc policy*
                                        :current-gross (:gross current-exposure)
                                        :current-net (:net current-exposure))
                                 zoom-level)]
    {:policy policy*
     :interaction-mode (if covariance-only? :gross-only :gross-net)
     :gross-editable? true
     :net-editable? (not covariance-only?)
     :net-output-only? covariance-only?
     :net-output-copy (when covariance-only? (net-output-copy objective-kind))
     :net-direction (if covariance-only?
                      :neutral
                      (net-direction (:net-target policy*)))
     :zoom (select-keys zoom [:level :fit-level :zoom-in-level :zoom-out-level])
     :target-marker (policy/target-marker policy* axis)
     :band-rect (policy/band-rect policy* axis)
     :band-wedge (policy/band-wedge policy* axis)
     :band-wedge-stripe (policy/band-wedge-stripe policy* axis)
     ;; Allowed net range at the TARGET gross — the pad's horizontal range bar.
     :band-edges (let [{:keys [left right]} (policy/net-band-edges
                                             (:net-target policy*)
                                             (:net-band-pct policy*)
                                             (:gross-target policy*))]
                   (let [n-ext (:net-extent axis)
                         ->x (fn [v] (-> (/ (+ v n-ext) (* 2 n-ext))
                                         (max 0.0) (min 1.0)))]
                     {:left-x (->x left) :right-x (->x right)}))
     :current-marker (policy/current-exposure-marker current-exposure axis)
     :current-exposure current-exposure
     :gross-band (:gross-band stored-policy)
     :net-band-pct (:net-band-pct stored-policy)
     ;; Approximate ABSOLUTE net half-width at the configured max gross — a UI
     ;; preview only; the solver scales the band with realized gross.
     :net-band-abs-preview (let [gmax (+ (max 0.0 (or (:gross-target stored-policy) 0.0))
                                         (max 0.0 (or (:gross-band stored-policy) 0.0)))]
                             (* (:net-band-pct stored-policy) gmax))
     :max-band policy/max-band
     :max-net-band-pct policy/max-net-band-pct
     :net-band-pct-slider-max policy/net-band-pct-slider-max
     :axis axis
     :echo {:gross-min gross-min
            :gross-max gross-max
            :gross-floored? (some? gross-min)
            :net-min net-min
            :net-max net-max
            :net-band-pct (:net-band-pct stored-policy)}
     :preview (exposure-preview {:current-exposure current-exposure
                                 :constraints (if covariance-only?
                                                (dissoc constraints*
                                                        :net-min
                                                        :net-max
                                                        :net-band-pct)
                                                constraints*)})
     :active-preset active
     :profile {:has-default? (boolean has-saved-default?)}
     ;; No :presets chip vector since 2026-07-10: the preset BUTTONS left the UI
     ;; (owner request). :active-preset stays — the panel header labels the
     ;; recognized policy ("Conservative" / "Custom · from holdings"), and the
     ;; preset action + domain presets remain for programmatic use.
     :highlighted {:gross (gross-highlighted? highlighted-controls)
                   :net (and (not covariance-only?)
                             (net-highlighted? highlighted-controls))}}))
