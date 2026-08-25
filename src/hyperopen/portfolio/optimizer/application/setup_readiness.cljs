(ns hyperopen.portfolio.optimizer.application.setup-readiness
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.draft-enrichment :as draft-enrichment]
            [hyperopen.portfolio.optimizer.application.history-loader.calendar :as calendar]
            [hyperopen.portfolio.optimizer.application.history-warning-policy :as warning-policy]
            [hyperopen.portfolio.optimizer.application.orderbook-loader :as orderbook-loader]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]
            [hyperopen.portfolio.optimizer.ids :as ids]))

(def ^:private history-blocking-warning-codes
  warning-policy/history-blocking-warning-codes)

(def ^:private history-assumption-warning-codes
  warning-policy/history-assumption-warning-codes)

(def ^:private insufficient-history-warning-codes
  warning-policy/insufficient-history-warning-codes)

(def ^:private stale-history-warning-codes
  warning-policy/stale-history-warning-codes)

(def ^:private fallback-as-of-bucket-ms
  ;; Quantize the wall-clock fallback so request building can memoize across
  ;; streaming renders. Staleness windows are minutes long, so a five second
  ;; as-of granularity is immaterial to readiness output.
  5000)

(defn- current-as-of-ms
  [state]
  (or (get-in state contracts/runtime-as-of-ms-path)
      (let [now-ms (.now js/Date)]
        (- now-ms (mod now-ms fallback-as-of-bucket-ms)))))

(def ^:private positive-number? coercion/positive-number?)

(def ^:private non-blank-text coercion/non-blank-text)

(defn- vault-ref?
  [value]
  (ids/vault-instrument-id? value))

(defn- with-manual-capital
  [snapshot draft]
  (let [manual-capital (get-in draft [:execution-assumptions :manual-capital-usdc])]
    (if (positive-number? manual-capital)
      (-> snapshot
          (assoc :capital-ready? true)
          (assoc-in [:capital :nav-usdc] manual-capital)
          (assoc-in [:capital :source] :manual)
          (assoc-in [:capital :manual-capital-usdc] manual-capital)
          (update :warnings
                  #(conj (vec %)
                         {:code :manual-capital-base
                          :message "Manual capital base is being used for preview sizing."})))
      snapshot)))

(defonce ^:private build-request-memo
  (volatile! nil))

(defn- build-request
  ;; Request building re-aligns the full instrument history, which is far too
  ;; expensive to redo on every streaming render. The snapshot is memoized on
  ;; its own inputs, so comparing these resolved inputs by value stays cheap.
  [state draft]
  (let [draft (draft-enrichment/enrich-draft-instruments state draft)
        inputs {:draft draft
                :current-portfolio (with-manual-capital
                                     (current-portfolio/current-portfolio-snapshot state)
                                     draft)
                :history-data (get-in state contracts/history-data-path)
                :market-cap-by-coin (get-in state contracts/market-cap-by-coin-path)
                :as-of-ms (current-as-of-ms state)
                :stale-after-ms (or (get-in state contracts/runtime-stale-after-ms-path)
                                    calendar/default-stale-after-ms)
                :funding-periods-per-year
                (get-in state contracts/runtime-funding-periods-per-year-path)
                ;; A user-triggered refinement re-runs at a higher frontier density. The
                ;; budget is injected onto the solved objective only while a refinement is
                ;; active; it is stripped from the input-signature so it never reads as
                ;; stale relative to the draft (see contracts.signatures/stable-objective).
                :frontier-points-override
                (when (get-in state contracts/refinement-active-path)
                  (get-in state contracts/refinement-requested-points-path))}
        cached @build-request-memo]
    (if (and cached (= (:inputs cached) inputs))
      (:value cached)
      (let [value (request-builder/build-engine-request inputs)]
        (vreset! build-request-memo {:inputs inputs :value value})
        value))))

(defn- orderbook-cost-contexts
  [state request]
  (let [fallback-bps (get-in request [:execution-assumptions :fallback-slippage-bps])
        opts {:now-ms (:as-of-ms request)
              :fallback-bps fallback-bps
              :stale-after-ms (or (get-in state
                                          contracts/runtime-orderbook-stale-after-ms-path)
                                  orderbook-loader/default-stale-after-ms)}]
    (into {}
          (map (fn [{:keys [instrument-id coin]}]
                 [instrument-id
                  (dissoc (orderbook-loader/orderbook-cost-context state coin opts)
                          :coin)]))
          (:universe request))))

(defn- with-cost-contexts
  [state request]
  (let [generated-contexts (orderbook-cost-contexts state request)
        existing-contexts (get-in request [:execution-assumptions :cost-contexts-by-id])]
    (assoc-in request
              [:execution-assumptions :cost-contexts-by-id]
              (merge existing-contexts generated-contexts))))

(defn- native-mark-price
  "Live native mark for a market-catalog entry, preferring the precise :markRaw string over the
   display-rounded :mark. Returns nil when neither is a positive number."
  [entry]
  (let [px (or (coercion/parse-float-number (:markRaw entry))
               (coercion/parse-float-number (:mark entry)))]
    (when (coercion/positive-number? px) px)))

(defn- native-marks-by-id
  "Map of optimizer instrument-id -> live native mark, resolved from the global market catalog
   ([:asset-selector :market-by-key]). A HIP-3 perp prices its RETURNS off an equity proxy
   (e.g. xyz:BABA -> the Tiingo Alibaba ADR), so its last history close is the proxy level
   (~112) and not the native perp mark (~95); seeding the execution price from the native mark
   keeps the rebalance preview's order sizing and cost estimate honest. Each instrument is
   resolved by its OWN catalog key — its instrument-id, falling back to a market-type-qualified
   coin key (\"<market-type>:<coin>\") for doubled-prefix ids like \"perp:xyz:xyz:SILVER\" whose
   id does not equal the catalog key \"perp:xyz:SILVER\". Resolving per market-type means a spot
   and a perp sharing a base token never collide onto one another's price."
  [state universe]
  (let [market-by-key (get-in state [:asset-selector :market-by-key])]
    (into {}
          (keep (fn [{:keys [instrument-id coin market-type]}]
                  (when-let [entry (or (get market-by-key instrument-id)
                                       (when (and market-type (non-blank-text coin))
                                         (get market-by-key
                                              (str (name market-type) ":" coin))))]
                    (when-let [px (native-mark-price entry)]
                      [instrument-id px]))))
          universe)))

(defn- with-native-marks
  "Seed [:execution-assumptions :prices-by-id] with live native marks for the eligible universe
   so the rebalance preview prices execution against the native mark rather than an equity-proxy
   daily close. Any explicit price already present wins, and the solver is unaffected (only the
   preview reads :prices-by-id)."
  [state request]
  (let [marks (native-marks-by-id state (:universe request))]
    (cond-> request
      (seq marks)
      (update-in [:execution-assumptions :prices-by-id] #(merge marks %)))))

(defn- instrument-ids
  [instruments]
  (set (keep :instrument-id instruments)))

(defn- incomplete-history?
  [requested-universe request]
  (not= (instrument-ids requested-universe)
        (instrument-ids (:universe request))))

(defn- requested-instrument-by-id
  [request]
  (into {}
        (mapcat (fn [instrument]
                  (cond-> [[(:instrument-id instrument) instrument]]
                    (:optimizer-history/instrument-id instrument)
                    (conj [(:optimizer-history/instrument-id instrument)
                           instrument]))))
        (:requested-universe request)))

(defn- warning-instrument
  [request warning]
  (get (requested-instrument-by-id request) (:instrument-id warning)))

(defn warning-asset-label
  [request warning]
  (let [instrument (warning-instrument request warning)
        coin (non-blank-text (:coin instrument))]
    (or (non-blank-text (:name instrument))
        (non-blank-text (:symbol instrument))
        (when-not (vault-ref? coin) coin)
        (non-blank-text (:vault-address instrument))
        (non-blank-text (:vault-address warning))
        (non-blank-text (:instrument-id warning))
        "Selected asset")))

(defn warning-code-summary
  "A code-level, count-aware sentence for a GROUP of same-code warnings (the grouped readiness
  panel shows this once instead of repeating a per-asset message N times)."
  [code count]
  (let [n count
        assets (str n (if (= 1 n) " asset" " assets"))]
    (cond
      ;; The two staleness codes need DISTINCT sentences: rendering both as
      ;; "use stale cached history" produced two identically-worded groups with
      ;; different counts side by side (audited live: "15 assets…" and
      ;; "6 assets…" with overlapping asset lists).
      (= :source-fetch-failed code)
      (str assets " could not refresh from the history provider — using cached or native history")

      (contains? stale-history-warning-codes code)
      (str assets (if (= 1 n) " uses" " use") " stale history")

      ;; "Proxy" is an internal mapping term; traders read "substitute history".
      (= :proxy-history-used code)
      (str assets (if (= 1 n) " uses" " use") " substitute history")

      (contains? insufficient-history-warning-codes code)
      (str assets (if (= 1 n) " has" " have") " insufficient common history")

      (= :excluded-from-alignment code)
      (str assets (if (= 1 n) " is" " are") " left out of the shared history estimate")

      (= :sparse-native-history code)
      (str assets (if (= 1 n) " is" " are")
           " estimated from their own sparse samples, outside the shared daily window")

      (= :optimizer-history-api-legacy-fallback code)
      (str assets (if (= 1 n) " is" " are") " served from Hyperliquid directly")

      :else
      (str assets " · " (str/replace (name code) "-" " ")))))

(defn- observation-count-message
  [warning noun]
  (if (and (some? (:observations warning))
           (some? (:required warning)))
    (str "only " (:observations warning) " usable " noun
         " observations; " (:required warning) " required.")
    (str "not enough usable " noun " observations.")))

(defn warning-display-message
  [request warning]
  (let [label (warning-asset-label request warning)]
    (case (:code warning)
      :missing-history-coin
      (str label ": missing coin metadata needed to fetch history.")

      :missing-candle-history
      (str label ": no candle history returned"
           (if-let [coin (non-blank-text (:coin warning))]
             (str " for " coin ".")
             "."))

      :missing-return-history
      (str label ": no optimizer return history returned.")

      :insufficient-candle-history
      (str label ": " (observation-count-message warning "candle"))

      :insufficient-return-history
      (str label ": " (observation-count-message warning "optimizer return"))

      :missing-vault-address
      (str label ": missing vault address needed to fetch vault details.")

      :missing-vault-history
      (str label ": vault details returned no usable return history.")

      :insufficient-vault-history
      (str label ": " (observation-count-message warning "vault return"))

      :insufficient-common-history
      (observation-count-message warning "shared return")

      :missing-native-risk-history
      (str label ": no native optimizer price history returned for mixed-frequency risk.")

      :identity-ambiguous
      (str label ": missing backend optimizer history identity.")

      :instrument-kind-mismatch
      (str label ": backend optimizer history identity does not match the selected asset type.")

      :proxy-mapping-unapproved
      (str label ": proxy history is not approved for optimizer use.")

      :proxy-validation-failed
      (str label ": proxy history failed backend validation.")

      :validation-failed
      (str label ": backend validation rejected optimizer history.")

      :rejected
      (str label ": backend rejected optimizer history.")

      :proxy-history-used
      (str label ": approved substitute history is included.")

      :vault-derived-history-used
      (str label ": row uses vault return-index history, not market candles.")

      ;; Long history, published far apart (a downsampled vault window). Says how
      ;; it IS estimated - it is not a defect and must not read like one.
      :sparse-native-history
      (str label ": " (:observations warning) " samples across "
           (js/Math.round (or (:elapsed-days warning) 0))
           " days - estimated from its own samples, outside the shared daily window.")

      ;; Without this the code fell through to the raw-keyword branch below and
      ;; printed "optimizer-history-api-legacy-fallback" at the user.
      :optimizer-history-api-legacy-fallback
      (str label ": served from Hyperliquid directly because the optimizer "
           "history service has no record for it.")

      :funding-history-missing
      (str label ": funding history is missing; price history availability is separate.")

      ;; Portfolio-level prior fallbacks (no :instrument-id): say what happens
      ;; instead of leaking the internal code as a headline.
      :missing-market-cap-prior
      (str "Market-cap baseline unavailable — implied returns start from "
           (if (= :equal-weight (:fallback warning))
             "equal weights"
             "your current holdings")
           " instead.")

      :missing-current-portfolio-prior
      "No current-portfolio baseline — equal weights seed the implied returns."

      :stale-history
      (if-let [age (warning-policy/serve-age-days warning)]
        (str label ": optimizer history is " age (if (= 1 age) " day" " days")
             " stale" (when (warning-policy/stale-incident? warning)
                        " — the refresh pipeline may be failing") ".")
        (str label ": optimizer history may be stale."))

      :excluded-from-alignment
      (case (get-in warning [:details :reason])
        :empty-series
        (str label ": no history was returned, so it is left out of the shared estimate.")
        (str label ": history window does not overlap the rest of the universe, "
             "so it is left out of the shared estimate."))

      :common-window-empty
      "No shared history window could be computed across the selected assets."

      :source-fetch-failed
      (str label ": source refresh failed; cached optimizer history may be stale.")

      :history-assumption-required
      (str label " needs a history assumption. Model it from similar assets or use a conservative assumption.")

      :history-assumption-incomplete
      (case (:missing warning)
        :volatility (str label " needs a modeled annual volatility.")
        :expected-return (str label " needs an expected annual return for this objective.")
        :max-weight (str label " needs a max allocation cap.")
        :max-weight-exceeds-global
        (str label " max allocation cap cannot exceed the global max asset weight.")
        :proxy-instruments
        (str label " is set to be modeled but no proxy assets are selected.")
        :self-proxy
        (str label " cannot use itself as a proxy.")
        :proxy-history
        (let [proxy-labels (->> (:proxy-instrument-ids warning)
                                (map #(warning-asset-label request {:instrument-id %}))
                                (str/join ", "))]
          (str label " proxy asset " proxy-labels " has no usable optimizer history."))
        (str label " needs more history-assumption details."))

      (or (some-> (:code warning) name)
          "Optimizer warning."))))

(defn- with-warning-messages
  [request warnings]
  (mapv (fn [warning]
          (assoc warning :message (warning-display-message request warning)))
        warnings))

(defn- first-missing-assumption-field
  [entry return-required? ctx]
  (if (history-assumptions/proxy? entry)
    (history-assumptions/first-missing-proxy-field
     entry
     (assoc ctx :return-required? return-required?))
    (cond
      (not (positive-number? (:volatility entry)))
      :volatility

      (and return-required? (not (coercion/finite-number? (:expected-return entry))))
      :expected-return

      (not (positive-number? (:max-weight entry)))
      :max-weight

      :else nil)))

(defn- history-assumption-warning
  [entry return-required? instrument-id ctx]
  ;; An assumption behavior is chosen but a required detail is still missing.
  (let [missing (first-missing-assumption-field entry
                                                return-required?
                                                (assoc ctx :self-id instrument-id))]
    (cond-> {:code :history-assumption-incomplete
             :instrument-id instrument-id
             :behavior (:behavior entry)
             :missing missing}
      (= :proxy-history missing)
      (assoc :proxy-instrument-ids
             (history-assumptions/unusable-proxy-ids entry
                                                     (:usable-proxy-ids ctx))))))

(defn- assumption-validation-ctx
  "Validation context shared by the proxy completeness checks: which assets can
  serve as proxies (aligned realized history, no assumption of their own) and the
  global cap a proxy cap must not exceed."
  [request draft]
  {:usable-proxy-ids (request-builder/usable-proxy-id-set
                      (get-in request [:history :eligible-instruments])
                      (:history-assumptions draft)
                      ;; MUST match request-builder's set exactly: this ctx feeds
                      ;; first-missing-proxy-field, so a stale set makes readiness
                      ;; call a proxy anchor usable that the engine rejected, and
                      ;; the user gets "needs more history-assumption details"
                      ;; instead of the name of the unusable proxy.
                      (get-in request [:history :off-calendar-instrument-ids]))
   :max-asset-weight (get-in request [:constraints :max-asset-weight])})

(defn- history-assumption-warnings
  "Honest guidance for dropped assets the user has started configuring. Assets with
  no assumption draft are left to the existing incomplete-history path."
  [requested-universe request draft ctx]
  (let [aligned-ids (instrument-ids (:universe request))
        return-required? (history-assumptions/return-required-for-objective?
                          (get-in request [:objective :kind]))
        assumptions (:history-assumptions draft)]
    (->> requested-universe
         (keep (fn [instrument]
                 (let [id (:instrument-id instrument)
                       entry (get assumptions id)]
                   (when (and id
                              (not (contains? aligned-ids id))
                              (some? (:behavior entry)))
                     (history-assumption-warning entry return-required? id ctx)))))
         vec)))

(defn- native-observations-by-id
  ;; Native price-row counts per instrument: the aligned history's raw series
  ;; where the asset survived alignment, else the pre-alignment served count.
  ;; The raw map covers ELIGIBLE assets only, so counting it alone blinded this
  ;; gate exactly when a poisoned shared calendar excluded everyone (live
  ;; 2026-07-08) - the served fallback keeps the gate honest for excluded assets.
  [request]
  (merge (get-in request [:history :served-observations-by-instrument])
         (into {}
               (map (fn [[id rows]] [id (count rows)]))
               (get-in request [:history :raw-price-series-by-instrument]))))

(defn- short-history-assumption-warnings
  "Assets whose native history is too thin to defensibly estimate covariance
  (fewer than `assumption-required-max-observations` daily rows) block the run
  until they carry a complete history assumption - but only when the universe
  holds at least one asset with real (>= short-history threshold) history to
  borrow from; in an all-young universe the long-standing thin-history behavior
  stands. Complete assumptions (either behavior) silence the warning."
  [requested-universe request draft ctx]
  (let [obs-by-id (native-observations-by-id request)
        max-obs (reduce max 0 (vals obs-by-id))
        return-required? (history-assumptions/return-required-for-objective?
                          (get-in request [:objective :kind]))
        assumptions (:history-assumptions draft)
        ;; Off-calendar sparse members are already estimated natively by the
        ;; mixed-frequency path. Demanding an assumption for them would block the
        ;; run for an asset the engine is happily modeling.
        off-calendar-ids (set (get-in request [:history :off-calendar-instrument-ids]))]
    (if (< max-obs history-assumptions/short-history-min-observations)
      []
      (->> requested-universe
           (keep (fn [instrument]
                   (let [id (:instrument-id instrument)
                         obs (get obs-by-id id)
                         entry (get assumptions id)]
                     (when (and id
                                obs
                                (not (contains? off-calendar-ids id))
                                (< obs history-assumptions/assumption-required-max-observations)
                                (not (history-assumptions/assumption-complete?
                                      entry
                                      return-required?
                                      (assoc ctx :self-id id))))
                       (if (some? (:behavior entry))
                         (history-assumption-warning entry return-required? id ctx)
                         {:code :history-assumption-required
                          :instrument-id id
                          :observations obs})))))
           vec))))

(defn- assumption-readiness-warnings
  "All assumption-driven blocking warnings, one per asset (the dropped-asset
  guidance wins when an asset would qualify for both)."
  [requested-universe request draft]
  (let [ctx (assumption-validation-ctx request draft)
        dropped (history-assumption-warnings requested-universe request draft ctx)
        thin (short-history-assumption-warnings requested-universe request draft ctx)
        seen (set (map :instrument-id dropped))]
    (into dropped
          (remove #(contains? seen (:instrument-id %)))
          thin)))

(defn- blocking-history-warnings
  [requested-universe request]
  (let [missing-ids (set/difference (instrument-ids requested-universe)
                                    (instrument-ids (:universe request)))
        warnings (filter (fn [warning]
                           (and (not (:forgiven? warning))
                                (or (contains? missing-ids (:instrument-id warning))
                                    (contains? history-blocking-warning-codes
                                               (:code warning)))))
                         (:warnings request))
        ;; Prefer actionable assumption guidance over the lower-level raw history
        ;; warnings for the same asset.
        assumption-ids (set (keep (fn [warning]
                                    (when (contains? history-assumption-warning-codes
                                                     (:code warning))
                                      (:instrument-id warning)))
                                  warnings))]
    (->> warnings
         (remove (fn [warning]
                   (and (contains? assumption-ids (:instrument-id warning))
                        (not (contains? history-assumption-warning-codes
                                        (:code warning))))))
         (with-warning-messages request))))

(defn history-status-by-instrument
  [readiness]
  (let [request (:request readiness)
        requested-ids (mapv :instrument-id (:requested-universe request))
        aligned-ids (instrument-ids (:universe request))
        warnings (vec (concat (or (:warnings request) [])
                              (or (:warnings readiness) [])))
        common-gap? (boolean (some #(= :insufficient-common-history (:code %))
                                   warnings))
        warning-status-by-id (warning-policy/strongest-warning-status-by-id
                              warnings)]
    (into {}
          (map (fn [instrument-id]
                 [instrument-id
                  (or (get warning-status-by-id instrument-id)
                      (when (contains? aligned-ids instrument-id)
                        :aligned)
                      (when common-gap?
                        :loaded-but-misaligned)
                      :missing)]))
          requested-ids)))

(defn current-portfolio-history-load-needed?
  [readiness]
  (let [request (:request readiness)
        current-history (:current-portfolio-history request)
        current-ids (instrument-ids (:current-portfolio-universe request))
        selected-ids (instrument-ids (:requested-universe request))
        ;; A holding counts as covered when it aligned OR when the last
        ;; current-portfolio fetch already requested it; holdings with no
        ;; usable backend history must not force a reload on every run.
        covered-ids (set/union (instrument-ids
                                (:eligible-instruments current-history))
                               (set (:requested-instrument-ids current-history)))]
    (and (:runnable? readiness)
         (seq current-ids)
         (not (set/subset? current-ids selected-ids))
         (not (set/subset? current-ids covered-ids)))))

(defn readiness-error-message
  [readiness]
  (let [details (seq (distinct (keep :message (:blocking-warnings readiness))))
        with-details (fn [fallback prefix]
                       (if details
                         (str prefix ": " (str/join " " details))
                         fallback))]
    (case (:reason readiness)
      :missing-universe "Select a universe before running."
      :holdings-loading "Holdings are still loading for this account."
      :history-loading "Optimizer history is already loading."
      :no-eligible-history
      (with-details "No eligible history was available for this universe."
                    "No eligible history was available")
      :incomplete-history
      (with-details "History is incomplete for this universe."
                    "History is incomplete")
      :missing-history-assumptions
      (with-details "Some assets need history assumptions before running."
                    "History assumptions needed")
      "Optimizer inputs are not ready to run.")))

(defn history-refreshing?
  "True when a history load is in flight OVER a bundle that already landed.

  `:loaded-at-ms` is stamped on `history-data` by apply-history-success, and it
  also rides along on a bundle hydrated from the per-wallet IndexedDB cache
  (history-cache/persisted-history-keys includes it). So its presence is exactly
  the question \"is there already a usable bundle behind this fetch?\" — absent
  before the first load ever completes, present for every refresh afterwards.

  This is the distinction the stale-while-revalidate cache always assumed
  readiness was making. history-cache/hydrate-history-cache deliberately leaves
  `history-load-state` alone, on the stated grounds that \"readiness/cards judge
  usability from the hydrated data itself\" — but until 2026-08-24 readiness
  judged only the load state, so a repeat visit showed a blocking \"Loading…\"
  over data that was already complete and on screen."
  [state]
  (boolean
   (and (= :loading (get-in state contracts/history-load-state-status-path))
        (get-in state (conj contracts/history-data-path :loaded-at-ms)))))

(defn build-readiness
  [state]
  (let [draft (get-in state contracts/draft-path)
        requested-universe (vec (or (:universe draft) []))
        ;; Only a COLD load blocks. A refresh over an existing bundle does not:
        ;; the bundle is usable, and a newly added asset is still blocked by
        ;; incomplete-history? below, which compares the requested universe
        ;; against the ids the request could actually serve. See
        ;; docs/exec-plans/active/2026-08-24-optimizer-history-load-status-honesty.md.
        history-loading? (and (= :loading
                                 (get-in state contracts/history-load-state-status-path))
                              (not (history-refreshing? state)))]
    (if (empty? requested-universe)
      {:status :blocked
       :reason :missing-universe
       :runnable? false
       :request nil
       :warnings []}
      (let [request (with-native-marks state
                      (with-cost-contexts state (build-request state draft)))
            risk-blocking-warnings (risk/missing-native-risk-history-warnings
                                    {:risk-model (:risk-model request)
                                     :history (:history request)})
            request (cond-> request
                      (seq risk-blocking-warnings)
                      (update :warnings into risk-blocking-warnings))
            assumption-warnings (assumption-readiness-warnings requested-universe
                                                              request
                                                              draft)
            request (cond-> request
                      (seq assumption-warnings)
                      (update :warnings into assumption-warnings))
            eligible? (boolean (seq (:universe request)))
            incomplete? (incomplete-history? requested-universe request)
            risk-history-incomplete? (boolean (seq risk-blocking-warnings))
            assumptions-flagged? (boolean (seq assumption-warnings))
            runnable? (and eligible?
                           (not incomplete?)
                           (not risk-history-incomplete?)
                           (not assumptions-flagged?)
                           (not history-loading?))
            blocking-warnings (if (or (not eligible?)
                                      incomplete?
                                      risk-history-incomplete?
                                      assumptions-flagged?)
                                (blocking-history-warnings requested-universe
                                                          request)
                                [])]
        {:status (if runnable? :ready :blocked)
         :reason (cond
                   history-loading? :history-loading
                   assumptions-flagged? :missing-history-assumptions
                   (not eligible?) :no-eligible-history
                   (or incomplete? risk-history-incomplete?) :incomplete-history
                   :else nil)
         :runnable? (boolean runnable?)
         ;; Non-blocking: a refresh is running over a bundle that already
         ;; landed. Carried on readiness so the view-model can name the state
         ;; without re-reading the store.
         :refreshing? (history-refreshing? state)
         :request request
         :blocking-warnings blocking-warnings
         :warnings (vec (:warnings request))}))))
