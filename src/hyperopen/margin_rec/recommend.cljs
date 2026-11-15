(ns hyperopen.margin-rec.recommend
  "Isolated-margin recommendation pipeline.

  Objective: the smallest isolated equity E such that the modeled probability
  of liquidation before the user's next likely intervention stays below the
  selected risk limit alpha, plus explicitly named buffers (funding, exit,
  model uncertainty). The adverse-path core comes from
  hyperopen.margin-rec.paths; the maintenance model from
  hyperopen.margin-rec.tiers; the horizon from hyperopen.margin-rec.episodes.

  Every product-policy constant lives here, named, so the recommendation's
  composition is auditable: nothing is an unlabeled safety factor.

  The pipeline is split so a driver can run it in idle-scheduled batches:
  `prepare` builds an immutable simulation context, `simulate-batch` (from
  paths) produces required-equity slices, `finalize` turns the sorted
  distribution into the recommendation. `recommend-sync` composes all three
  for tests and non-chunked callers."
  (:require [hyperopen.margin-rec.episodes :as episodes]
            [hyperopen.margin-rec.paths :as paths]
            [hyperopen.margin-rec.tiers :as tiers]))

(def risk-mode-alphas
  "Target probability of liquidation before the next intervention."
  {:conservative 0.01
   :balanced 0.02
   :capital-efficient 0.05})

(def default-risk-mode :balanced)

(defn normalize-risk-mode
  [mode]
  (let [mode* (cond
                (keyword? mode) mode
                (string? mode) (keyword mode)
                :else nil)]
    (if (contains? risk-mode-alphas mode*)
      mode*
      default-risk-mode)))

(def min-bars
  "Fewer hourly bars than this and we refuse to model rather than guess."
  60)

(def target-bars
  "Hourly bars requested from history (45 days)."
  1080)

(def ^:private block-len 24)
(def ^:private stress-block-probability 0.15)
(def ^:private ewma-lambda
  (js/Math.exp (/ (js/Math.log 0.5) 72)))
(def ^:private rho-min 0.75)
(def ^:private rho-max 2.5)
(def ^:private calibration-mismatch-threshold 0.10)

(def ^:private exit-fraction-named-dex
  "Exit / slippage buffer as a fraction of notional for HIP-3 (named-dex)
  assets, whose books run materially thinner than main-dex majors."
  0.010)
(def ^:private exit-fraction-main-dex 0.004)

(defn path-count
  [horizon-bars]
  (if (> horizon-bars 240) 2500 4000))

(def batch-size
  "Paths per idle slice in the chunked driver."
  512)

(def ^:private risk-chip-high-threshold 0.10)
(def ^:private risk-chip-elevated-threshold 0.05)

(defn- finite-pos?
  [value]
  (and (number? value) (js/isFinite value) (pos? value)))

(defn- finite?
  [value]
  (and (number? value) (js/isFinite value)))

(defn- clamp
  [value lo hi]
  (-> value (max lo) (min hi)))

(defn- seed-for
  [{:keys [coin]} q horizon-bars n-bars last-t]
  (hash [coin (pos? q) horizon-bars n-bars last-t]))

(defn prepare
  "Build the simulation context from resolved inputs:

  {:coin :dex :szi :mark :margin-used :liquidation-px :max-leverage
   :margin-table :funding-rate-hourly :candle-rows :fills :risk-mode
   :horizon-override-hours}

  Returns {:status :ready ...} with :bars/:params/:paths-count/:meta, or a
  terminal {:status :invalid | :insufficient-history} map."
  [{:keys [coin szi mark margin-used liquidation-px max-leverage margin-table
           funding-rate-hourly candle-rows fills risk-mode
           horizon-override-hours dex]
    :as inputs}]
  (let [q (when (number? szi) szi)
        e0 (when (finite? margin-used) (max 0 margin-used))]
    (cond
      (or (nil? q) (zero? q) (not (finite-pos? mark)) (nil? e0))
      {:status :invalid}

      :else
      (let [bars (paths/prepare-bars candle-rows)
            n-bars (or (:n bars) 0)]
        (if (< n-bars min-bars)
          {:status :insufficient-history
           :n-bars n-bars
           :min-bars min-bars}
          (let [configured (tiers/schedule-for margin-table max-leverage)
                modeled-liq (when configured
                              (tiers/liquidation-price configured q mark e0))
                mismatch (tiers/relative-mismatch modeled-liq liquidation-px mark)
                needs-calibration? (and (finite-pos? liquidation-px)
                                        (or (nil? configured)
                                            (nil? modeled-liq)
                                            (and (some? mismatch)
                                                 (> mismatch calibration-mismatch-threshold))))
                calibrated-rate (when needs-calibration?
                                  (tiers/calibrate-flat-rate q mark e0 liquidation-px))
                schedule (or (some-> calibrated-rate tiers/flat-rate-schedule)
                             configured)
                sigma-hourly (paths/ewma-vol (:rets bars) ewma-lambda)
                sigma-sample (paths/sample-vol (:rets bars))
                rho-raw (when (and (finite-pos? sigma-hourly)
                                   (finite-pos? sigma-sample))
                          (/ sigma-hourly sigma-sample))
                rho (when rho-raw (clamp rho-raw rho-min rho-max))
                horizon (if (finite-pos? horizon-override-hours)
                          {:hours (clamp horizon-override-hours
                                         episodes/min-horizon-hours
                                         episodes/max-horizon-hours)
                           :source :override
                           :samples 0}
                          (episodes/horizon-hours fills coin))
                horizon-bars (js/Math.round (:hours horizon))]
            (cond
              (nil? schedule) {:status :invalid}
              (nil? rho) {:status :insufficient-history
                          :n-bars n-bars
                          :min-bars min-bars}
              :else
              (let [risk-mode* (normalize-risk-mode risk-mode)
                    seed (seed-for {:coin coin}
                                   q horizon-bars n-bars (:last-t bars))
                    paths-count (path-count horizon-bars)]
                {:status :ready
                 :bars bars
                 :paths-count paths-count
                 :params {:q q
                          :p0 mark
                          :mm-fn (tiers/maintenance-fn schedule)
                          :horizon-bars horizon-bars
                          :seed seed
                          :rho rho
                          :block-len block-len
                          :stress-prob stress-block-probability
                          :stress-starts (paths/stress-starts (:rets bars) block-len)}
                 :meta {:coin coin
                        :dex dex
                        :q q
                        :mark mark
                        :equity e0
                        :notional (* (js/Math.abs q) mark)
                        :liquidation-px (when (finite-pos? liquidation-px)
                                          liquidation-px)
                        :schedule schedule
                        :alpha (risk-mode-alphas risk-mode*)
                        :risk-mode risk-mode*
                        :funding-rate-hourly (when (finite? funding-rate-hourly)
                                               funding-rate-hourly)
                        :horizon (assoc horizon :bars horizon-bars)
                        :sigma-hourly sigma-hourly
                        :n-bars n-bars
                        :rho rho
                        :rho-clamped? (boolean (and rho-raw (not= rho rho-raw)))
                        :calibrated? (some? calibrated-rate)
                        :calibration-mismatch? (boolean
                                                (and needs-calibration?
                                                     (nil? calibrated-rate)))}}))))))))

(defn- model-uncertainty-fraction
  [{:keys [n-bars rho-clamped? calibration-mismatch?]}]
  (-> (+ 0.10
         (* 0.40 (max 0 (- 1 (/ n-bars target-bars))))
         (if rho-clamped? 0.10 0)
         (if calibration-mismatch? 0.15 0))
      (min 0.60)))

(defn- adverse-funding-rate
  [q funding-rate-hourly]
  (let [rate (or funding-rate-hourly 0)]
    (if (pos? q)
      (max rate 0)
      (max (- rate) 0))))

(def ^:private curve-sample-count
  "Points sampled from the required-equity distribution for the
  probability-vs-collateral chart."
  50)

(defn- nice-ceiling
  "Smallest 1/2/5 x 10^k value >= x, for round chart axis maxima."
  [x]
  (if (finite-pos? x)
    (let [base (js/Math.pow 10 (js/Math.floor (js/Math.log10 x)))
          m (/ x base)]
      (* base (cond (<= m 1) 1 (<= m 2) 2 (<= m 5) 5 :else 10)))
    1))

(defn- probability-curve
  "Sampled p_liq(E) over [0, nice(2 x recommended equity)] so the panel can
  draw the liquidation-probability-vs-collateral curve without shipping the
  full distribution."
  [sorted-required e-rec]
  (let [x-max (nice-ceiling (* 2 e-rec))
        step (/ x-max (dec curve-sample-count))]
    {:x-max x-max
     :points (mapv (fn [i]
                     (let [e (* step i)]
                       {:e e :p (paths/prob-above sorted-required e)}))
                   (range curve-sample-count))}))

(defn- risk-level
  [p-now]
  (cond
    (>= p-now risk-chip-high-threshold) :high
    (>= p-now risk-chip-elevated-threshold) :elevated
    :else :normal))

(defn- confidence-tier
  [{:keys [n-bars calibration-mismatch?]} horizon-source]
  (cond
    (or calibration-mismatch? (< n-bars 240)) :low
    (and (>= n-bars 720) (not= :default horizon-source)) :high
    :else :medium))

(defn- mode-recommendation
  "The alpha-dependent part of a recommendation, read off the shared sorted
  distribution for one risk mode. The maintenance/funding/exit buffers are
  alpha-independent and passed in; only C*(alpha), the model buffer, and every
  quantity derived from the resulting equity change with the mode."
  [{:keys [q mark equity notional liquidation-px schedule] :as meta}
   {:keys [sorted mm0 funding-buffer exit-buffer exit-fraction model-u
           horizon-hours]}
   risk-mode alpha]
  (let [c-star (paths/quantile-of-sorted sorted (- 1 alpha))
        model-buffer (* model-u (max 0 (- c-star mm0)))
        e-rec (+ c-star funding-buffer exit-buffer model-buffer)
        additional (max 0 (- e-rec equity))
        p-after (paths/prob-above sorted e-rec)
        new-liq (tiers/liquidation-price schedule q mark e-rec)]
    {:risk-mode risk-mode
     :alpha alpha
     :status (if (<= additional 0.005) :within-target :ok)
     :p-after p-after
     :recommended {:equity e-rec
                   :additional additional
                   :new-liquidation-px new-liq
                   :new-liq-change-frac (when (and new-liq (finite-pos? liquidation-px))
                                          (/ (- liquidation-px new-liq) liquidation-px))
                   :effective-leverage (when (pos? e-rec) (/ notional e-rec))}
     :breakdown [{:key :maintenance
                  :label "Maintenance requirement"
                  :amount mm0}
                 {:key :adverse-path
                  :label "Adverse-path protection"
                  :amount (- c-star mm0)}
                 {:key :funding
                  :label (str "Funding buffer ("
                              (js/Math.round (/ horizon-hours 24)) "d)")
                  :amount funding-buffer}
                 {:key :exit
                  :label (str "Exit / slippage buffer ("
                              (.toFixed (* 100 exit-fraction) 1)
                              "% notional)")
                  :amount exit-buffer}
                 {:key :model
                  :label "Model uncertainty buffer"
                  :amount model-buffer}]
     :reduce-suggestion (when (and (pos? e-rec) (> e-rec equity))
                          (let [fraction (clamp (- 1 (/ equity e-rec)) 0 0.95)]
                            {:fraction fraction
                             :notional (* fraction notional)}))}))

(defn finalize
  "Turn the sorted required-equity distribution into the recommendation.

  The distribution is independent of the risk target, so every preset mode's
  recommendation is read off it in this single pass and returned under
  `:by-risk-mode`; the top-level keys mirror the compute-time active mode so
  older consumers keep working. Switching the risk mode is then a pure view
  selection (see hyperopen.margin-rec.state/select-risk-mode) with no
  recompute."
  [{:keys [meta] :as _ctx} sorted-required]
  (let [{:keys [q mark equity notional liquidation-px schedule
                funding-rate-hourly horizon sigma-hourly]} meta
        active-mode (:risk-mode meta)
        mm0 (tiers/maintenance-margin schedule (* (js/Math.abs q) mark))
        horizon-hours (:hours horizon)
        exit-fraction (if (seq (:dex meta))
                        exit-fraction-named-dex
                        exit-fraction-main-dex)
        shared {:sorted sorted-required
                :mm0 mm0
                :funding-buffer (* (adverse-funding-rate q funding-rate-hourly)
                                   horizon-hours
                                   notional)
                :exit-buffer (* exit-fraction notional)
                :exit-fraction exit-fraction
                :model-u (model-uncertainty-fraction meta)
                :horizon-hours horizon-hours}
        by-risk-mode (reduce-kv (fn [acc mode alpha]
                                  (assoc acc mode
                                         (mode-recommendation meta shared mode alpha)))
                                {}
                                risk-mode-alphas)
        active (get by-risk-mode active-mode)
        p-now (paths/prob-above sorted-required equity)
        sigma-daily (when (finite-pos? sigma-hourly)
                      (* sigma-hourly (js/Math.sqrt 24)))
        distance-frac (when (finite-pos? liquidation-px)
                        (/ (js/Math.abs (- mark liquidation-px)) mark))
        buffer-sigmas (when (and distance-frac (finite-pos? sigma-daily))
                        (/ distance-frac sigma-daily))]
    (merge
     {:coin (:coin meta)
      :dex (:dex meta)
      :horizon horizon
      :as-of {:mark mark
              :equity equity
              :liquidation-px liquidation-px
              :notional notional
              :side (if (pos? q) :long :short)}
      :sigma {:hourly sigma-hourly
              :daily sigma-daily
              :annualized (when (finite-pos? sigma-hourly)
                            (* sigma-hourly (js/Math.sqrt 8760)))
              :distance-frac distance-frac
              :buffer-sigmas buffer-sigmas}
      :p-now p-now
      :paths-count (alength sorted-required)
      :curve (probability-curve sorted-required
                                (get-in active [:recommended :equity]))
      :risk-level (risk-level p-now)
      :confidence {:tier (confidence-tier meta (:source horizon))
                   :n-bars (:n-bars meta)
                   :rho-clamped? (:rho-clamped? meta)
                   :calibrated? (:calibrated? meta)
                   :calibration-mismatch? (:calibration-mismatch? meta)}
      :by-risk-mode by-risk-mode}
     active)))

(defn recommend-sync
  "Single-shot pipeline: prepare, simulate every path, finalize. Terminal
  prepare statuses pass through unchanged."
  [inputs]
  (let [ctx (prepare inputs)]
    (if (not= :ready (:status ctx))
      ctx
      (finalize ctx
                (paths/simulate-required-sync (:bars ctx)
                                              (:params ctx)
                                              (:paths-count ctx))))))
