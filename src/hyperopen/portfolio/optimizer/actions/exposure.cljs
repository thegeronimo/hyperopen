(ns hyperopen.portfolio.optimizer.actions.exposure
  "Pure action handlers for the 2D exposure-map Positioning control. Each handler reads the
  current draft constraints, asks the pure `exposure-policy` namespace for the next FULL
  constraint map, and persists it whole via `common/save-draft-path-values` (one save-many that
  also flips the dirty flag). Writing the whole map is deliberate: a zero gross band must DISSOC
  :gross-min, and the save-many effect can only assoc path values, so per-key writes could not
  clear the floor.

  These handlers only ever write the existing canonical constraint keys
  (:gross-min/:gross-max/:net-min/:net-max/:max-asset-weight), so the request builder and specs
  are unchanged."
  (:require [hyperopen.portfolio.optimizer.actions.common :as common]
            [hyperopen.portfolio.optimizer.application.constraint-profiles :as profiles]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.contracts.constants :as contracts-constants]
            [hyperopen.portfolio.optimizer.defaults :as defaults]
            [hyperopen.portfolio.optimizer.domain.exposure-policy :as policy]))

(defn- current-constraints
  [state]
  (or (get-in state contracts/draft-constraints-path) {}))

(def ^:private net-policy-keys
  [:net-min :net-max :net-band-pct])

(defn- covariance-only-objective?
  [state]
  (contains? contracts-constants/covariance-only-objective-kinds
             (get-in state (conj contracts/draft-objective-path :kind))))

(defn- preserve-net-policy
  [original updated]
  (reduce (fn [acc k]
            (if (contains? original k)
              (assoc acc k (get original k))
              (dissoc acc k)))
          updated
          net-policy-keys))

(defn- write-constraints
  [constraints]
  (common/save-draft-path-values
   [[contracts/draft-constraints-path constraints]]))

(defn- pin-zoom-effects
  "Pin the stored zoom to the render level an interaction was performed at, so the pad scale can
  never SHRINK under the pointer when the edited policy re-fits smaller (the view model only
  ever widens the render scale past the stored level). No-op when the level is unchanged or the
  pad is on the computed overflow scale (nil level)."
  [state render-level]
  (if (and (number? render-level)
           (not= render-level (get-in state contracts/ui-exposure-zoom-level-path)))
    [[:effects/save contracts/ui-exposure-zoom-level-path render-level]]
    []))

(defn set-portfolio-optimizer-exposure-point
  "Drag/click on the pad. `client-x`/`client-y` are pointer coordinates, `bounds` the pad's
  bounding rect (resolved by :event.currentTarget/bounds), `buttons` the pressed-button bitmask
  (resolved by :event/pointer-buttons). `gross-axis-max`/`net-axis-extent` are the pad's current
  fixed scale (baked into the dispatch) so the pointer maps to the values the axis displays; the
  current bands clamp the reachable targets so the band box never leaves the view (which is what
  keeps a drag from ever rescaling the pad under the pointer). A hover (no pressed button) or
  degenerate bounds is a no-op so passive pointer moves don't rewrite the draft."
  ([state client-x client-y bounds buttons]
   (set-portfolio-optimizer-exposure-point state client-x client-y bounds buttons nil nil nil))
  ([state client-x client-y bounds buttons gross-axis-max net-axis-extent]
   (set-portfolio-optimizer-exposure-point
    state client-x client-y bounds buttons gross-axis-max net-axis-extent nil))
  ([state client-x client-y bounds buttons gross-axis-max net-axis-extent render-level]
   (let [constraints (current-constraints state)
         gross-only? (covariance-only-objective? state)
         {:keys [gross-band net-band-pct]} (policy/constraints->policy constraints)]
     (if-let [targets (policy/point->targets {:client-x client-x
                                              :client-y client-y
                                              :bounds bounds
                                              :buttons buttons
                                              :gross-axis-max gross-axis-max
                                              :net-axis-extent (if gross-only?
                                                                 0
                                                                 net-axis-extent)
                                              :gross-band gross-band
                                              :net-band-pct net-band-pct})]
       (into (write-constraints
              (cond->> (policy/apply-point constraints targets)
                gross-only? (preserve-net-policy constraints)))
             (pin-zoom-effects state render-level))
       []))))

(defn set-portfolio-optimizer-exposure-band
  "Set the gross or net band (half-width) from a slider. `axis` is :gross|:net, `value` the
  band magnitude (clamped in the policy layer). `render-level` is the pad's current zoom level
  (baked by the view) — pinning it keeps narrowing a band from shrinking the pad mid-slide."
  ([state axis value]
   (set-portfolio-optimizer-exposure-band state axis value nil))
  ([state axis value render-level]
   (let [axis* (common/normalize-keyword-like axis)
         value* (common/parse-number-value value)
         [axis* value*] (if (= :net-pct axis*)
                          [:net (when (some? value*) (/ value* 100))]
                          [axis* value*])]
     (if (and (contains? #{:gross :net} axis*) (some? value*))
       (into (write-constraints (policy/apply-band (current-constraints state) axis* value*))
             (pin-zoom-effects state render-level))
       []))))

(defn- clear-zoom-effect
  "Discontinuous policy changes (preset / reset / saved profile) clear the trader's stored zoom
  so the pad re-fits to the new policy. Drags and band edits only ever PIN the level they were
  performed at (see pin-zoom-effects) — they never clear or shrink it."
  []
  [:effects/save contracts/ui-exposure-zoom-level-path nil])

(defn set-portfolio-optimizer-exposure-zoom-level
  "Set the pad's stored zoom level. The view bakes the neighbour level of the CURRENT render
  scale into each zoom-button dispatch (nil buttons are disabled), so this handler only
  validates and stores; the view model still clamps render scale to fit the policy."
  [_state level]
  (let [level* (common/parse-number-value level)]
    (if (and (some? level*)
             (<= 0 level* policy/max-zoom-level)
             (== level* (js/Math.round level*)))
      [[:effects/save contracts/ui-exposure-zoom-level-path level*]]
      [])))

(defn apply-portfolio-optimizer-exposure-preset
  "Apply a named positioning preset (:conservative|:balanced|:high-gross|:long-bias)."
  [state preset]
  (let [preset* (common/normalize-keyword-like preset)]
    (if (contains? policy/presets preset*)
      (conj (write-constraints (policy/apply-preset (current-constraints state) preset*))
            (clear-zoom-effect))
      [])))

(defn reset-portfolio-optimizer-constraints-to-system
  "Replace the draft constraints with the system defaults."
  [_state]
  (conj (write-constraints (:constraints (defaults/default-draft)))
        (clear-zoom-effect)))

(defn save-portfolio-optimizer-constraint-default
  "Remember the current constraints as the default for this wallet + universe. The effect reads
  the draft, stamps the time, updates state, and persists — so the action stays pure."
  [_state]
  [[:effects/save-portfolio-optimizer-constraint-default]])

(defn apply-portfolio-optimizer-constraint-default
  "Apply the remembered default for the current universe to the draft, if one exists."
  [state]
  (let [profile-map (get-in state contracts/constraint-profiles-path)
        universe-key (profiles/universe-key (get-in state contracts/draft-universe-path))
        remembered (profiles/remembered-constraints profile-map universe-key)]
    (if remembered
      (conj (write-constraints remembered) (clear-zoom-effect))
      [])))
