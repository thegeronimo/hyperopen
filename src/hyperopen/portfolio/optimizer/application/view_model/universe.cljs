(ns hyperopen.portfolio.optimizer.application.view-model.universe
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]
            [hyperopen.portfolio.optimizer.application.view-model.universe-search :as universe-search]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]
            [hyperopen.portfolio.optimizer.ids :as ids]))

(def ^:private normalized-text coercion/non-blank-text)
(def ^:private finite-number coercion/parse-number)

(def ^:private missing-history-warning-codes
  #{:missing-history-coin
    :missing-candle-history
    :missing-return-history
    :missing-vault-address
    :missing-vault-history})

(def ^:private insufficient-history-warning-codes
  #{:insufficient-candle-history
    :insufficient-return-history
    :insufficient-vault-history})

(def ^:private history-status-labels
  {:queued "queued"
   :loading "loading"
   :shared-gap "shared gap"
   :sufficient "sufficient"
   :stale "stale"
   :stale-critical "stale"
   :insufficient "insufficient"
   :rejected "rejected"
   :missing "missing"
   :pending "pending"})

(def ^:private all-clear-history-statuses
  "Statuses that need no user attention, so the history chip is hidden (the
  column is shown by-exception). :stale is treated as all-clear: a one-to-two
  day stale tail is immaterial to the covariance estimate and is dominated by
  upstream refresh lag, so it is not worth a per-row badge. Genuine coverage
  problems still surface as :insufficient / :shared-gap / :missing / :rejected."
  #{:sufficient :stale})

(def ^:private in-progress-history-statuses
  "Transient load states; shown with a neutral tone rather than a warning."
  #{:queued :loading :pending})

(defn- history-chip-display
  "By-exception history chip. Returns nil for all-clear statuses (healthy rows
  render no chip), a neutral chip for in-progress load states, and an amber
  warning chip for the genuine coverage problems."
  [history-status]
  (when-not (contains? all-clear-history-statuses history-status)
    {:label (get history-status-labels history-status "pending")
     :tone (if (contains? in-progress-history-statuses history-status)
             :muted
             :warn)}))

(defn- instrument-ids
  [instruments]
  (into #{} (keep :instrument-id) instruments))

(defn- warning-by-instrument-id
  [readiness instrument-id]
  (some (fn [warning]
          (when (= instrument-id (:instrument-id warning))
            warning))
        (:blocking-warnings readiness)))

(defn- history-rows
  [state instrument]
  (if (= :vault (:market-type instrument))
    (get-in state (conj contracts/history-data-path
                        :vault-details-by-address
                        (:vault-address instrument)))
    (get-in state (conj contracts/history-data-path
                        :candle-history-by-coin
                        (:coin instrument)))))

(defn- compact-usd
  [value]
  (if-let [n (finite-number value)]
    (cond
      (>= n 1000000000) (str "$" (.toFixed (/ n 1000000000) 1) "B")
      (>= n 1000000) (str "$" (.toFixed (/ n 1000000) 0) "M")
      (>= n 1000) (str "$" (.toFixed (/ n 1000) 0) "K")
      :else (str "$" (.toFixed n 0)))
    "--"))

(defn- raw-asset-id?
  [value]
  (let [text (normalized-text value)]
    (boolean
     (and text
          (or (str/starts-with? text "@")
              (re-matches #"\d+" text))))))

(defn- vault-instrument?
  [instrument]
  (ids/vault-instrument? instrument))

(defn- vault-address
  [instrument]
  (or (ids/normalize-vault-address (:vault-address instrument))
      (ids/vault-address-from-value (:coin instrument))
      (ids/vault-address-from-value (:instrument-id instrument))))

(defn- hip3-instrument?
  [instrument]
  (boolean
   (or (:dex instrument)
       (:hip3? instrument)
       (:hip3-eligible? instrument))))

(defn- spot-instrument?
  [instrument]
  (= :spot (ids/normalize-market-type (:market-type instrument))))

(defn- symbol-first?
  [instrument]
  (or (spot-instrument? instrument)
      (hip3-instrument? instrument)
      (raw-asset-id? (:coin instrument))))

(defn- base-from-symbol
  [symbol]
  (let [symbol* (normalized-text symbol)]
    (cond
      (and symbol* (str/includes? symbol* "/"))
      (normalized-text (first (str/split symbol* #"/" 2)))

      (and symbol* (str/includes? symbol* "-"))
      (normalized-text (first (str/split symbol* #"-" 2)))

      :else nil)))

(defn instrument-primary-label
  [instrument]
  (or (when (vault-instrument? instrument)
        (or (normalized-text (:name instrument))
            (normalized-text (:symbol instrument))
            (vault-address instrument)))
      (when (symbol-first? instrument)
        (normalized-text (:symbol instrument)))
      (when (raw-asset-id? (:coin instrument))
        (or (normalized-text (:base instrument))
            (base-from-symbol (:symbol instrument))))
      (normalized-text (:coin instrument))
      (normalized-text (:symbol instrument))
      (normalized-text (:instrument-id instrument))
      "--"))

(defn instrument-base-label
  [instrument]
  (or (when (vault-instrument? instrument)
        (vault-address instrument))
      (normalized-text (:base instrument))
      (base-from-symbol (:symbol instrument))
      (when-not (raw-asset-id? (:coin instrument))
        (normalized-text (:coin instrument)))))

(defn- adv-label
  [market]
  (compact-usd (or (:volume24h market)
                   (:volume market)
                   (:openInterest market)
                   (:tvl market))))

(defn- liquidity-label
  [market-or-instrument]
  (let [value (or (:liquidity market-or-instrument)
                  (:liquidity-label market-or-instrument)
                  (:depth market-or-instrument))]
    (or (when (= :vault (:market-type market-or-instrument))
          "vault")
        (normalized-text value)
        (if-let [volume (finite-number (or (:volume24h market-or-instrument)
                                           (:volume market-or-instrument)))]
          (if (>= volume 50000000) "deep" "medium")
          "medium"))))

(defn- position-side
  [instrument]
  (case (coercion/normalize-keyword-like (:position-side instrument))
    :short :short
    :long))

(defn- short-selectable?
  [instrument]
  (cond
    (contains? instrument :shortable?)
    (true? (:shortable? instrument))

    (= :perp (ids/normalize-market-type
              (or (:market-type instrument)
                  (:instrument-type instrument))))
    true

    :else false))

(declare selected-history-status)

(def assumption-badge-labels
  {:ready "Ready"
   :short-history "Thin history"
   :no-history "No history"
   :needs-proxy "Needs proxy"
   :needs-assumptions "Needs assumptions"
   :using-proxy "Using proxy"
   :conservative "Conservative"
   ;; Long history, published far apart. Deliberately NOT "Thin history": the
   ;; asset has plenty of history, it is just sampled coarsely, and conflating
   ;; the two is the bug this badge exists to end.
   :sparse-history "Sparse history"
   ;; The configured "return/risk modeled from a basket of similar assets"
   ;; stance. Named for its RESULT ("Modeled") rather than its mechanism
   ;; ("proxy") so it never collides with the unconfigured "Needs proxy" cue -
   ;; a scanning trader can tell configured from unconfigured at a glance.
   :proxy-behavior "Modeled"})

(def assumption-badge-tooltips
  "Hover copy for the two CONFIGURED assumption chips, expanding the single
  word into what actually happened to the asset. The unconfigured cues
  (Needs proxy / Needs assumptions / Thin history) are self-explanatory and
  carry none."
  {:proxy-behavior
   (str "This asset's return and risk are modeled from a basket of similar "
        "assets you picked, since its own history is too short.")
   :conservative
   (str "Treated cautiously: high assumed volatility, no diversification "
        "credit, and a tight allocation cap.")
   :sparse-history
   (str "Its own history spans a year or more but the venue publishes it far "
        "apart. Return and risk are estimated from its own samples with the "
        "mixed-frequency model, and it is kept out of the shared daily window "
        "so it cannot shorten the other assets' estimate - its allocation is "
        "capped.")})

(def workflow-assumption-badges
  "Badge states the universe rows actually PAINT: actionable gaps, configured
  assumptions, and (muted) thin history - the cue that invites the proxy
  workflow (user feedback 2026-07-05). Plain Ready stays data-role-only so the
  list never reads like an error log (owner decision, 2026-07)."
  #{:needs-proxy :no-history :needs-assumptions :conservative :proxy-behavior
    :short-history :sparse-history})

(defn native-history-observations
  "Native history row count for an instrument. The readiness arity is the honest
  production source: real sessions load optimizer history through the api-v2
  backend, so the state-side candle map is EMPTY and only the aligned history's
  raw per-instrument series (or, for an already-configured proxy asset that
  alignment deliberately excludes, its regression-series overlap) carries the
  count. The state-side candle count remains as the final fallback for
  dev/test-fixture sessions that seed raw candles."
  ([state instrument]
   (let [rows (history-rows state instrument)]
     (when (sequential? rows)
       (count rows))))
  ([state readiness instrument]
   (let [instrument-id (:instrument-id instrument)]
     (or (when-let [rows (seq (get-in readiness
                                      [:request :history
                                       :raw-price-series-by-instrument
                                       instrument-id]))]
           (count rows))
         (get-in readiness [:request :history-assumptions instrument-id
                            :regression-series :observations])
         ;; Excluded from alignment != no history: the pre-alignment served
         ;; count keeps a full-history asset from ever badging "thin".
         (get-in readiness [:request :history
                            :served-observations-by-instrument instrument-id])
         (native-history-observations state instrument)))))

(defn native-history-cadence
  "Cadence summary for an instrument's native series, or nil. Carries
  `:off-calendar? true` when alignment moved the member off the shared daily
  calendar. This is what separates \"31 rows because it is a new listing\" from
  \"31 rows because the venue publishes this one every two weeks\" - a distinction
  a bare row count cannot express."
  [readiness instrument]
  (get-in readiness [:request :history :cadence-by-instrument
                     (:instrument-id instrument)]))

(defn- sparse-long-history?
  "Sampled far apart but genuinely reaching back. Such an asset is estimated by
  the mixed-frequency path from its own samples and must NOT be judged against
  the daily-row bar - 31 samples spread over a year is not `short history`.

  Requires `:off-calendar?`, not merely `:sparse?`. Cadence is computed for EVERY
  eligible member, so a sparse member that still shares the daily calendar would
  otherwise take this branch and be shown the badge tooltip's promise that it is
  \"kept out of the shared daily window\" - a claim that would be false for it."
  [cadence]
  (boolean
   (and (:sparse? cadence)
        (:off-calendar? cadence)
        (>= (or (:elapsed-days cadence) 0)
            history-assumptions/short-history-min-observations))))

(defn assumption-required-ids
  "Ids whose native history is below the assumption-required threshold (their
  covariance cannot be defensibly estimated AND their short calendar would
  shrink the shared estimation window), in a universe that holds real history to
  borrow from. Mirrors the readiness gate so the NEEDS PROXY badge and the run
  block always agree."
  ([state universe]
   (assumption-required-ids state nil universe))
  ([state readiness universe]
   (let [obs-by-id (into {}
                         (keep (fn [instrument]
                                 (when-let [n (native-history-observations
                                               state readiness instrument)]
                                   [(:instrument-id instrument) n])))
                         universe)
         max-obs (reduce max 0 (vals obs-by-id))
         ;; Mirrors setup-readiness: a member the mixed-frequency path already
         ;; estimates natively must not also be told it needs a proxy.
         off-calendar-ids (set (get-in readiness
                                       [:request :history
                                        :off-calendar-instrument-ids]))]
     (if (< max-obs history-assumptions/short-history-min-observations)
       #{}
       (into #{}
             (keep (fn [[id n]]
                     (when (and (not (contains? off-calendar-ids id))
                                (< n history-assumptions/assumption-required-max-observations))
                       id)))
             obs-by-id)))))

(defn history-adequacy
  "Card/badge adequacy: layers the user-facing short-history threshold on top of the
  load-aware history status. The engine's own minimum is only 1-2 observations, so a
  thin-but-present asset (e.g. 75 daily rows) reads as :sufficient there; here it
  is reclassified :short when its native history is below the threshold. Pass
  readiness so the count comes from the aligned (api-v2-backed) history - the
  state-side candle fallback only exists for fixture sessions."
  ([history-status state instrument]
   (history-adequacy history-status state nil instrument))
  ([history-status state readiness instrument]
   (case history-status
     (:missing :rejected) :none
     (:insufficient :shared-gap) :short
     (:queued :loading :pending) :pending
     ;; :sufficient / :stale -> adequate unless the native history is below the bar.
     (let [observations (native-history-observations state readiness instrument)
           cadence (native-history-cadence readiness instrument)]
       (cond
         ;; Checked BEFORE the row-count bar: a sparse member's row count is a
         ;; sample count, not a day count, so comparing it to a ~1-year daily
         ;; threshold is a category error (it is what produced the false
         ;; "31 days of native history - too short to model on its own").
         (sparse-long-history? cadence) :sparse

         (and observations
              (< observations history-assumptions/short-history-min-observations))
         :short

         :else :ok)))))

(def ^:private adequacy-badges
  {:none :no-history
   :short :short-history
   :sparse :sparse-history
   :ok :ready})

(defn- assumption-badge
  [entry adequacy assumption-required? return-required?]
  (cond
    (and entry
         (history-assumptions/assumption-complete? entry return-required?))
    (if (history-assumptions/proxy? entry) :proxy-behavior :conservative)

    (some? (:behavior entry))
    :needs-assumptions

    ;; Below the assumption-required threshold the run is blocked until the user
    ;; configures the asset, so the badge escalates from Thin history.
    (and assumption-required? (not= :none adequacy))
    :needs-proxy

    :else
    (get adequacy-badges adequacy)))

(defn selected-row-model
  ([state readiness history-load-state history-status-by-id instrument]
   (selected-row-model state readiness history-load-state history-status-by-id
                       instrument nil))
  ([state readiness history-load-state history-status-by-id instrument assumption-context]
   (let [instrument-id (:instrument-id instrument)
         history-status (selected-history-status state
                                                 readiness
                                                 history-load-state
                                                 history-status-by-id
                                                 instrument)
         history-chip (history-chip-display history-status)
         primary-label (instrument-primary-label instrument)
         {:keys [name base-label]} (universe-candidates/market-display instrument)
         secondary-label (or (normalized-text (:name instrument))
                             (normalized-text (:full-name instrument))
                             (when (symbol-first? instrument)
                               base-label)
                             name)
         entry (get (:assumptions assumption-context) instrument-id)
         adequacy (history-adequacy history-status state readiness instrument)
         badge (assumption-badge entry
                                 adequacy
                                 (contains? (:assumption-required-ids assumption-context)
                                            instrument-id)
                                 (:return-required? assumption-context))]
     (cond-> {:instrument instrument
              :instrument-id instrument-id
              :coin (:coin instrument)
              :market-type (:market-type instrument)
              :primary-label primary-label
              :secondary-label secondary-label
              :history-status history-status
              :liquidity-label (liquidity-label instrument)
              :position-side (position-side instrument)
              :short-selectable? (short-selectable? instrument)}
       history-chip
       (assoc :history-label (:label history-chip)
              :history-tone (:tone history-chip))
       badge
       (assoc :assumption-badge badge
              :assumption-badge-label (get assumption-badge-labels badge)
              :assumption-badge-tooltip (get assumption-badge-tooltips badge))))))

(defn candidate-row-model
  [market idx active-index]
  (let [market-key (:key market)
        {:keys [label name]} (universe-candidates/market-display market)]
    {:market market
     :market-key market-key
     :market-type (:market-type market)
     :active? (= idx active-index)
     :label label
     :name name
     :adv-label (adv-label market)}))

(defn selected-history-status
  [state readiness history-load-state history-status-by-id instrument]
  (let [instrument-id (:instrument-id instrument)
        prefetch-status (get-in state
                                (conj contracts/history-prefetch-path
                                      :by-instrument-id
                                      instrument-id
                                      :status))
        loading-ids (instrument-ids (get-in history-load-state
                                            [:request-signature :universe]))
        eligible-ids (instrument-ids (get-in readiness [:request :universe]))
        readiness-status (get history-status-by-id instrument-id)
        warning (warning-by-instrument-id readiness instrument-id)
        warning-code (:code warning)
        load-validated? (and (= :succeeded (:status history-load-state))
                             (contains? loading-ids instrument-id))
        cached-history? (seq (history-rows state instrument))]
    (cond
      (= :queued prefetch-status)
      :queued

      (= :loading prefetch-status)
      :loading

      (and (= :loading (:status history-load-state))
           (contains? loading-ids instrument-id))
      :loading

      (= :loaded-but-misaligned readiness-status)
      :shared-gap

      (= :rejected readiness-status)
      :rejected

      (= :stale readiness-status)
      :stale

      ;; A >= 7-day serve-time staleness is a refresh-pipeline incident, not the
      ;; immaterial 1-2 day tail: it escapes the all-clear set and paints a
      ;; visible amber "stale" chip.
      (= :stale-critical readiness-status)
      :stale-critical

      (= :aligned readiness-status)
      :sufficient

      (contains? eligible-ids instrument-id)
      :sufficient

      (and (= :insufficient readiness-status)
           (or load-validated? cached-history?))
      :insufficient

      (and (contains? insufficient-history-warning-codes warning-code)
           (or load-validated? cached-history?))
      :insufficient

      (and (= :missing readiness-status)
           load-validated?)
      :missing

      (and (contains? missing-history-warning-codes warning-code)
           load-validated?)
      :missing

      :else
      :pending)))

(defn selected-history-label
  [state readiness history-load-state history-status-by-id instrument]
  (get history-status-labels
       (selected-history-status state
                                readiness
                                history-load-state
                                history-status-by-id
                                instrument)
       "pending"))

(defn- no-importable-holdings?
  "True when the account snapshot HAS arrived but a holdings import would add
  nothing — perp exposures always import; spot exposures import only when the
  draft's constraints include spot assets. Without this fact the empty state
  keeps promising \"holdings load automatically\" (and the Load-my-holdings
  button keeps no-oping) with zero feedback on a positionless account."
  [state]
  (let [arrived? (:perp? (current-portfolio/holdings-sources-signature state))
        include-spot? (true? (get-in state
                                     (conj contracts/draft-constraints-path
                                           :include-spot?)))
        exposures (when arrived?
                    (:exposures (current-portfolio/current-portfolio-snapshot state)))]
    (boolean
     (and arrived?
          (not-any? #(or (= :perp (:market-type %))
                         (and include-spot? (= :spot (:market-type %))))
                    exposures)))))

(defn universe-section-model
  ([state draft]
   (universe-section-model state draft nil))
  ([state draft {:keys [readiness
                        history-load-state
                        history-status-by-id
                        candidate-options
                        include-blank-candidates?
                        group-results?]}]
   (let [universe (vec (or (:universe draft) []))
         history-load-state* (or history-load-state
                                 (get-in state contracts/history-load-state-path)
                                 (optimizer-defaults/default-history-load-state))
         history-status-by-id* (or history-status-by-id
                                   (when readiness
                                     (setup-readiness/history-status-by-instrument
                                      readiness))
                                   {})
         search-query (or (get-in state contracts/ui-universe-search-query-path) "")
         searching? (boolean (seq (normalized-text search-query)))
         query-candidates? (or searching?
                               include-blank-candidates?)
         query-markets (if query-candidates?
                         (universe-candidates/candidate-markets state
                                                                universe
                                                                search-query
                                                                candidate-options)
                         [])
         type-filter (universe-search/normalize-type-filter
                      (get-in state contracts/ui-universe-search-type-filter-path))
         quote-filter (universe-search/normalize-quote-filter
                       (get-in state contracts/ui-universe-search-quote-filter-path))
         ;; `markets` is the RENDER-ORDERED, facet-filtered vector; active-index,
         ;; market-keys and candidate-rows all derive from it so the cursor, the
         ;; keydown handler's positional lookup and the row ids cannot drift.
         total-match-count (or (:total-match-count (meta query-markets))
                               (count query-markets))
         markets (universe-search/filter-markets query-markets
                                                 type-filter
                                                 quote-filter
                                                 (boolean group-results?))
         active-index (universe-candidates/active-index state markets)
         market-keys (if query-candidates?
                       (mapv :key markets)
                       [])
         assumption-context {:assumptions (or (:history-assumptions draft) {})
                             :assumption-required-ids (assumption-required-ids state
                                                                               readiness
                                                                               universe)
                             :return-required?
                             (history-assumptions/return-required-for-objective?
                              (or (get-in readiness [:request :objective :kind])
                                  (get-in draft [:objective :kind])))}
         selected-rows (mapv #(selected-row-model state
                                                  readiness
                                                  history-load-state*
                                                  history-status-by-id*
                                                  %
                                                  assumption-context)
                             universe)
         candidate-rows (mapv (fn [market idx]
                                (let [row (candidate-row-model market idx active-index)]
                                  (merge row
                                         ;; The flat render index travels WITH the
                                         ;; row so grouping cannot desynchronize the
                                         ;; DOM ids, the aria-activedescendant target
                                         ;; and the positional market-keys lookup.
                                         {:index idx}
                                         (universe-search/row-search-fields
                                          row
                                          search-query))))
                              markets
                              (range))]
     {:state state
      :draft draft
      :readiness readiness
      :history-load-state history-load-state*
      :history-status-by-id history-status-by-id*
      :universe universe
      :selected-rows selected-rows
      :search-query search-query
      :searching? searching?
      :markets markets
      :candidate-rows candidate-rows
      :active-index active-index
      :market-keys market-keys
      ;; Design-1a search projection: facets, grouped render order and the counts
      ;; that make the truncation visible instead of silent.
      :search-type-filter type-filter
      :search-quote-filter quote-filter
      :search-type-chips (universe-search/type-chips query-markets type-filter)
      :search-quote-chips (universe-search/quote-chips query-markets
                                                       type-filter
                                                       quote-filter)
      :search-groups (universe-search/groups candidate-rows)
      ;; From candidate-markets' metadata: the returned vector is already capped,
      ;; so counting it would re-hide the truncation.
      :search-match-count total-match-count
      :search-shown-count (count markets)
      ;; Counts the same set the chips describe, so the hit line can never
      ;; contradict them; only the footer names both numbers.
      :search-match-label (universe-search/match-label (count query-markets))
      :search-footer-label (universe-search/footer-label (count markets)
                                                         total-match-count)
      :search-filtered? (or (not= :all type-filter)
                            (not= :all quote-filter))
      ;; Where the current universe came from. :holdings carries the omission
      ;; accounting recorded at load time; a non-empty universe with no recorded
      ;; source (legacy drafts) reads as :custom.
      :universe-source (or (get-in draft [:metadata :universe-source])
                           (when (seq universe) {:kind :custom}))
      :no-importable-holdings? (when (empty? universe)
                                 (no-importable-holdings? state))})))

(defn universe-panel-model
  [state draft]
  (universe-section-model state
                          draft
                          {:candidate-options {:ranking :asset-query}
                           :include-blank-candidates? true}))
