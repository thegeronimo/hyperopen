(ns hyperopen.portfolio.montecarlo.actions
  "Control normalization, defaults, and Nexus action handlers for the portfolio
  Monte Carlo tab.

  The controls (number of simulations, forecast horizon, bust drawdown
  threshold, return goal, and RNG seed) live in app state under
  `[:portfolio-ui :monte-carlo]`. Every handler is a pure function of state +
  arguments that returns Nexus effect descriptors (here, `:effects/save` data),
  so it can be unit-tested without a running app. Side effects are performed by
  the runtime effect interpreters, not here."
  (:require [clojure.string :as str]))

(def state-path
  "App-state path under which Monte Carlo control values live."
  [:portfolio-ui :monte-carlo])

(def sims-options
  "Selectable simulation counts (segmented control)."
  [250 1000 2500])

(def horizon-options
  "Selectable forecast horizons in MONTHS of calendar time (segmented control).
  Vault history is sparse and irregularly spaced, so a forecast is expressed in
  elapsed time, not a count of data points; the view model clamps the chosen
  span to the realized history and converts it to a resample step count."
  [3 6 12 24])

(def method-options
  "Selectable simulation methods (segmented control). `:shuffle` is the faithful
  QuantStats method — reorder the realized returns (sequence risk), terminal
  value pinned to reality. `:bootstrap` is the forward forecast — resample with
  replacement over a horizon."
  [:shuffle :bootstrap])

(def default-controls
  "Default Monte Carlo controls. `:bust` and `:goal` are stored as whole
  percents (matching the UI); the view-model divides them by 100 before calling
  the engine. `:run-nonce` only exists to force a fresh chart animation when the
  user clicks Re-run with otherwise-identical (deterministic) inputs."
  {:method :shuffle
   :sims 1000
   :horizon 12
   :bust -30
   :goal 50
   :seed 42
   :run-nonce 0})

(def control-keys
  "Controls a user can set via `set-portfolio-monte-carlo-control`."
  #{:method :sims :horizon :bust :goal :seed})

(defn- parse-number
  [value]
  (cond
    (number? value) (when (js/isFinite value) value)
    (string? value) (let [n (js/parseFloat (str/replace value #"[^0-9.\-]" ""))]
                      (when (js/isFinite n) n))
    :else nil))

(defn- clamp-int
  [value lo hi fallback]
  (if-let [n (parse-number value)]
    (min hi (max lo (js/Math.round n)))
    fallback))

(defn- normalize-keyword-like
  [value]
  (cond
    (keyword? value) value
    (string? value) (let [trimmed (str/trim value)]
                      (when (seq trimmed) (keyword trimmed)))
    :else nil))

(defn normalize-sims
  [value]
  (let [n (some-> (parse-number value) js/Math.round)]
    (if (some #{n} sims-options) n (:sims default-controls))))

(defn normalize-horizon
  [value]
  (let [n (some-> (parse-number value) js/Math.round)]
    (if (some #{n} horizon-options) n (:horizon default-controls))))

(defn normalize-bust
  "Bust drawdown threshold, a whole negative percent in [-95, -1]."
  [value]
  (clamp-int value -95 -1 (:bust default-controls)))

(defn normalize-goal
  "Return goal, a whole positive percent in [1, 500]."
  [value]
  (clamp-int value 1 500 (:goal default-controls)))

(defn normalize-seed
  [value]
  (clamp-int value 0 9999 (:seed default-controls)))

(defn normalize-method
  "Simulation method: `:shuffle` (QuantStats reordering) or `:bootstrap`
  (forward forecast). Unknown values fall back to the default."
  [value]
  (let [k (normalize-keyword-like value)]
    (if (some #{k} method-options) k (:method default-controls))))

(defn normalize-control
  "Normalize a single control `value` for `control` key. Unknown controls return
  the value unchanged."
  [control value]
  (case control
    :method (normalize-method value)
    :sims (normalize-sims value)
    :horizon (normalize-horizon value)
    :bust (normalize-bust value)
    :goal (normalize-goal value)
    :seed (normalize-seed value)
    value))

(defn controls-at
  "Read the current Monte Carlo controls from `state` at `state-path*`, filling
  defaults. State-path-generic so other surfaces (e.g. the vault detail tab) can
  reuse the same control semantics under their own app-state path."
  [state state-path*]
  (let [stored (get-in state state-path*)]
    (reduce (fn [acc k]
              (assoc acc k (normalize-control k (get stored k (get default-controls k)))))
            (select-keys default-controls [:run-nonce])
            control-keys)))

(defn controls
  "Read the current Monte Carlo controls from `state`, filling defaults."
  [state]
  (controls-at state state-path))

(defn set-control-at
  "Effect that sets one Monte Carlo `control` to `value` under `state-path*`.
  Returns nil for unknown controls. Shared by every Monte Carlo surface."
  [state-path* control value]
  (let [control* (normalize-keyword-like control)]
    (when (contains? control-keys control*)
      [[:effects/save (conj state-path* control*) (normalize-control control* value)]])))

(defn rerun-at
  "Effect that bumps the run nonce under `state-path*` so the chart replays its
  reveal animation. The simulation is deterministic, so the numbers are unchanged
  unless a control differs."
  [state state-path*]
  (let [nonce (get-in state (conj state-path* :run-nonce) 0)]
    [[:effects/save (conj state-path* :run-nonce) (inc (or (parse-number nonce) 0))]]))

(defn set-portfolio-monte-carlo-control
  "Set one Monte Carlo control (`:method`, `:sims`, `:horizon`, `:bust`, `:goal`, `:seed`)."
  [_state control value]
  (set-control-at state-path control value))

(defn rerun-portfolio-monte-carlo
  "Bump the run nonce so the chart replays its reveal animation. The simulation
  is deterministic, so the numbers are unchanged unless a control differs."
  [state]
  (rerun-at state state-path))
