(ns hyperopen.portfolio.optimizer.application.view-model.setup-history-assumption-cards
  "History-assumption card projections for the proxy workflow (split from
  view-model.setup when the section outgrew it, 2026-07-05).

  Views render these rows and dispatch the carried action ids; they never touch
  the raw draft or raw history. A card is shown for any selected asset that is
  missing/short on history or that the user has already started configuring.
  The exposure preview (prior -> regression -> confidence -> final basket) runs
  the SAME domain pipeline the engine runs, over the readiness request's own
  inputs, so the card always shows the basket the engine would use."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.default-assumptions :as default-assumptions]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as history-api-v2]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]
            [hyperopen.portfolio.optimizer.application.view-model.setup-history-assumption-exposure :as exposure]
            [hyperopen.portfolio.optimizer.application.view-model.universe :as universe]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]))

(defn apply-percent-label
  [formatter value]
  (if (fn? formatter)
    (formatter value)
    (str value)))

(def ^:private mode-options
  ;; Plain-language button copy (queue restructure, 2026-08-14): the control
  ;; names the outcome the user is choosing, not the estimator's family name.
  [{:value :proxy
    :label "Model on similar assets"
    :description "Model this asset as a basket of assets it behaves like."}
   {:value :conservative
    :label "Assume worst case"
    :description "High volatility, no diversification credit, tight cap."}])

(def ^:private mode-labels
  {:conservative "Conservative assumption"
   :proxy "Modeled"})

(def ^:private relationship-options
  [{:value :low :label "Low"}
   {:value :medium :label "Medium"}
   {:value :high :label "High"}])

(def ^:private relationship-labels
  {:low "Low" :medium "Medium" :high "High"})

(def ^:private assumption-action-ids
  {:set-mode :actions/set-portfolio-optimizer-history-assumption-mode
   :set-expected-return :actions/set-portfolio-optimizer-history-assumption-expected-return
   :set-expected-volatility :actions/set-portfolio-optimizer-history-assumption-expected-volatility
   :set-max-weight-cap :actions/set-portfolio-optimizer-history-assumption-max-weight-cap
   :toggle-proxy-asset :actions/set-portfolio-optimizer-history-assumption-proxy-asset
   :set-proxy-search :actions/set-portfolio-optimizer-history-assumption-proxy-search
   :set-relationship-strength :actions/set-portfolio-optimizer-history-assumption-relationship-strength
   :apply :actions/apply-portfolio-optimizer-history-assumption
   :reset :actions/reset-portfolio-optimizer-history-assumption
   :clear :actions/clear-portfolio-optimizer-history-assumption})

(def ^:private card-needing-adequacy
  ;; universe/history-adequacy values that warrant a card: no usable history, or
  ;; thin-but-present history below the user-facing short threshold. :pending (still
  ;; loading) and :ok (enough history) are excluded.
  #{:none :short})

(defn- percent-field
  [percent-label* value]
  {:value value
   :percent-label (when (some? value) (percent-label* value))
   ;; editable percent text (decimal 0.25 -> "25"); the action parses it back.
   :input-text (when (some? value) (coercion/decimal->percent-text value))})

(def ^:private guardrail-warning-fields
  ;; :missing codes of :history-assumption-incomplete blocking warnings that
  ;; point at a risk-guardrail field (hidden in a collapsed drawer on proxy
  ;; cards, so the drawer needs a visible attention cue).
  #{:volatility :max-weight :max-weight-exceeds-global})

(defn- risk-guardrails-model
  "Auto-vs-edited projection of the entry's volatility + cap guardrails. The
  seeds come from history-assumptions/default-assumption, so :auto? simply
  means both values still equal their behavior's seed. Nil until a behavior is
  chosen (there are no guardrails to summarize on a mode-less card)."
  [entry percent-label* warnings]
  (when-let [behavior (:behavior entry)]
    (let [vol (:volatility entry)
          cap (:max-weight entry)
          auto? (and (= vol history-assumptions/default-conservative-volatility)
                     (= cap (history-assumptions/default-max-weight behavior)))]
      {:auto? auto?
       :source-label (if auto? "Auto-set" "Edited")
       :summary (str (if (some? vol) (percent-label* vol) "--") " vol · "
                     (if (some? cap) (percent-label* cap) "--") " max")
       :attention? (boolean (some #(contains? guardrail-warning-fields (:missing %))
                                  warnings))})))

(defn- card-status
  [entry complete? assumption-required?]
  (cond
    (nil? (:behavior entry)) (if assumption-required?
                               :needs-proxy
                               :needs-assumptions)
    complete? :configured
    :else :incomplete))

(def ^:private card-status-labels
  {:needs-proxy "Needs proxy"
   :needs-assumptions "Excluded - needs assumption"
   :incomplete "Needs assumptions"
   :configured "Configured"})

(defn- card-attention-order
  "Sort weight that floats cards still needing attention above engine-backed
  ones — what the right rail lists by. `:configured` is the only status whose
  work is done; every other status still asks the user for something, so it
  belongs on top regardless of the asset's alphabetical position. Cards with
  equal weight keep their incoming (universe) order because the CLJS sort is
  stable. NOTE the queue deliberately re-sorts back to universe order: a rail
  that reshuffles as you accept is not a map of what is left."
  [card]
  (if (= :configured (:status card)) 1 0))

(defn- card-summary
  [entry proxy-labels percent-label*]
  (let [vol (some-> (:volatility entry) percent-label*)
        cap (some-> (:max-weight entry) percent-label*)
        tail (str (when vol (str " - " vol " vol"))
                  (when cap (str " - " cap " cap")))]
    (case (:behavior entry)
      :conservative (str "conservative" tail)
      :proxy (str "modeled"
                  (when (seq proxy-labels)
                    (str " on " (str/join ", " proxy-labels)))
                  tail)
      nil)))

(defn addable-option-label
  "Dropdown option text for the manual entry point. The day count is the
  asset's native return-day count — the number that limits the shared
  covariance window — so the user can proxy out the most limiting assets
  first instead of guessing. Nil observations (history still loading) leave
  the bare label rather than claiming a count."
  [label observations]
  (if (some? observations)
    (str label " (" observations (if (= 1 observations) " day)" " days)"))
    label))

(defn- addable-sort-key
  "Ascending by day count so the most limiting assets lead; unknown counts
  (history still loading) sink to the bottom; label breaks ties so the order
  is stable."
  [{:keys [observations label]}]
  [(if (some? observations) 0 1) (or observations 0) (or label "")])

(defn- proxy-label-resolver
  "Resolve a proxy instrument-id to a display label: from the universe /
  reference-instrument pool, else the live market catalog, else the raw id.
  Reference-only proxies live outside the universe, so the catalog fallback is
  what labels them."
  [state universe reference-instruments]
  (let [pool (reduce (fn [m instrument] (assoc m (:instrument-id instrument) instrument))
                     {}
                     (concat universe reference-instruments))]
    (fn [id]
      (or (when-let [instrument (get pool id)]
            (universe/instrument-primary-label instrument))
          (when-let [market (get-in state [:asset-selector :market-by-key id])]
            (:label (universe-candidates/market-display market)))
          id))))

(defn- proxy-search-results
  "Proxy candidates matching the card's search query, excluding the thin asset
  itself and any already-selected proxy. Empty until the user types. Candidates
  come from the exact same source, ranking, and limit as the universe asset
  selector, so the proxy picker offers precisely what the left selector would."
  [state self-id selected-ids query]
  (let [query* (when (string? query) (str/trim query))]
    (if (str/blank? query*)
      []
      (let [exclusion (into [{:instrument-id self-id}]
                            (map (fn [id] {:instrument-id id}))
                            selected-ids)]
        (->> (universe-candidates/candidate-markets state exclusion query*)
             (keep (fn [market]
                     (let [mid (:key market)]
                       (when (and mid (not= mid self-id))
                         {:instrument-id mid
                          :label (:label (universe-candidates/market-display market))
                          :market-type (:market-type market)}))))
             vec)))))

(def ^:private prior-source-labels
  {:user "User-specified weights"
   :equal "Equal-weight fallback"})

(def ^:private recommendation-approach-labels
  {:proxy "Model on similar assets"
   :conservative "Assume worst case"})

(def ^:private recommended-action-ids
  {:apply-one :actions/apply-portfolio-optimizer-recommended-history-assumption
   :apply-all :actions/apply-portfolio-optimizer-recommended-history-assumptions})

(defn- recommendation-member-row
  [resolve-proxy-label available? {:keys [instrument-id label role weight]}]
  {:instrument-id instrument-id
   ;; Prefer the richer catalog/universe label; fall back to the discovery
   ;; display symbol (external members never resolve from the catalog).
   :label (let [resolved (resolve-proxy-label instrument-id)]
            (if (= resolved instrument-id)
              (or label resolved)
              resolved))
   :role role
   ;; The served economic prior, carried raw. Applying normalizes it over the
   ;; USABLE members only, so any split shown to the user must normalize the
   ;; same way rather than reading these numbers as percentages.
   :weight weight
   :available? available?})

(defn- card-recommendation
  "Backend default-assumption recommendation for a mode-less card: what the
  server suggests (approach, members, relationship, rationale) and whether a
  one-click apply is possible today. Members whose history the backend cannot
  serve yet render dimmed and are held back on apply. Nil once the user has
  chosen a mode — an authored entry always wins."
  [state instrument entry resolve-proxy-label]
  (when (nil? entry)
    (let [discovery (get-in state contracts/history-discovery-path)
          backend-id (:optimizer-history/instrument-id
                      (history-api-v2/with-discovery-metadata instrument discovery))
          rec (default-assumptions/recommendation discovery backend-id)]
      (when rec
        (let [{:keys [usable held]} (default-assumptions/member-availability
                                     discovery
                                     (:instrument-id instrument)
                                     (:members rec))]
          {:approach (:approach rec)
           :approach-label (get recommendation-approach-labels (:approach rec))
           :members (into (mapv #(recommendation-member-row resolve-proxy-label true %)
                                usable)
                          (mapv #(recommendation-member-row resolve-proxy-label false %)
                                held))
           :held-count (count held)
           :relationship-label (get relationship-labels (:relationship-strength rec))
           :rationale (:rationale rec)
           :applicable? (boolean (or (= :conservative (:approach rec))
                                     (seq usable)))
           :actions recommended-action-ids})))))

(defn- history-assumption-card
  [{:keys [instrument label entry complete? errors warnings note percent-label*
           assumption-required? return-required? observations
           covariance-observations readiness recommendation
           resolve-proxy-label usable-ids proxy-in-flight? search-query state]}]
  (let [id (:instrument-id instrument)
        behavior (:behavior entry)
        proxy? (= :proxy behavior)
        status (card-status entry complete? assumption-required?)
        selected-ids (when proxy? (history-assumptions/proxy-instrument-ids entry))
        selected-proxies (when proxy?
                           (exposure/selected-proxy-rows selected-ids resolve-proxy-label
                                                usable-ids proxy-in-flight?))
        ;; Background history work is still running for this card's basket -
        ;; every "no data" verdict below is provisional while this is true.
        history-loading? (boolean (some :loading? selected-proxies))
        relationship (when proxy? (history-assumptions/relationship-strength entry))
        preview (when proxy?
                  (exposure/exposure-preview readiness entry id selected-ids))
        prior-rows (when preview
                     (exposure/basket-rows (:prior-weights preview) selected-ids resolve-proxy-label))
        final-rows (when preview
                     (exposure/basket-rows (:final-beta preview) selected-ids resolve-proxy-label))
        acknowledged? (boolean (get-in entry [:metadata :acknowledged?]))
        ;; Complete AND accepted. `complete?` alone only says the engine can
        ;; back it; the queue's "settled" needs the user's yes as well.
        configured? (boolean (and complete? acknowledged?))]
    (cond-> {:instrument-id id
             :label label
             :role (str "portfolio-optimizer-history-assumption-card-" id)
             :status status
             :status-label (get card-status-labels status "Needs assumptions")
             :history-loading? history-loading?
             :mode behavior
             :mode-label (get mode-labels behavior)
             :mode-options mode-options
             :expected-return (percent-field percent-label* (:expected-return entry))
             :expected-return-required? (boolean return-required?)
             :volatility (percent-field percent-label* (:volatility entry))
             :max-weight (percent-field percent-label* (:max-weight entry))
             :risk-guardrails (risk-guardrails-model entry percent-label* warnings)
             :observations observations
             :covariance-observations covariance-observations
             :errors (vec errors)
             :note note
             ;; Agent-supplied reason from a file import (metadata rides the
             ;; entry; any edit strips the acknowledgment but keeps the text).
             :rationale (coercion/non-blank-text (get-in entry [:metadata :rationale]))
             :engine-applied? (boolean complete?)
             :acknowledged? acknowledged?
             :configured? configured?
             :summary (when complete?
                        (card-summary entry (mapv :label selected-proxies) percent-label*))
             :actions assumption-action-ids}
      (some? recommendation)
      (assoc :recommendation recommendation)

      proxy?
      (assoc :selected-proxy-ids (vec selected-ids)
             :selected-proxies selected-proxies
             :proxy-search-query (str (or search-query ""))
             :proxy-search-results (proxy-search-results state id selected-ids search-query)
             :relationship-strength relationship
             :relationship-label (get relationship-labels relationship)
             :relationship-options relationship-options
             :prior-basket prior-rows
             :prior-source (:prior-source preview)
             :prior-source-label (get prior-source-labels (:prior-source preview))
             :regression-estimate (exposure/regression-estimate-model preview
                                                             selected-ids
                                                             resolve-proxy-label
                                                             history-loading?)
             :final-basket (when (seq final-rows)
                             {:rows final-rows
                              :confidence-q (:confidence-q preview)
                              :confidence-label (some-> (:confidence-q preview)
                                                        percent-label*)
                              :confidence-tier (exposure/confidence-tier (:confidence-q preview))})
             :diagnostics (exposure/proxy-diagnostics {:observations observations
                                              :relationship-strength relationship
                                              :covariance-observations covariance-observations
                                              :preview preview
                                              :history-loading? history-loading?})
             :final-model-line (exposure/final-model-line final-rows
                                                 (some-> (:max-weight entry)
                                                         percent-label*))))))

(defn history-assumption-cards
  ([state draft readiness history-load-state]
   (history-assumption-cards state draft readiness history-load-state nil))
  ([state draft readiness history-load-state {:keys [percent-label]}]
   (let [universe (vec (or (get-in readiness [:request :requested-universe])
                           (:universe draft)
                           []))
         assumptions (or (:history-assumptions draft) {})
         history-status-by-id (if readiness
                                (setup-readiness/history-status-by-instrument readiness)
                                {})
         load-state (or history-load-state {})
         objective-kind (or (get-in readiness [:request :objective :kind])
                            (get-in draft [:objective :kind]))
         return-required? (history-assumptions/return-required-for-objective? objective-kind)
         percent-label* #(apply-percent-label percent-label %)
         warnings-by-id (group-by :instrument-id (:blocking-warnings readiness))
         required-ids (universe/assumption-required-ids state readiness universe)
         usable-ids (when-let [eligible (get-in readiness
                                                [:request :history :eligible-instruments])]
                      (request-builder/usable-proxy-id-set eligible assumptions))
         reference-instruments (get-in draft [:proxy-reference-instruments])
         resolve-proxy-label (proxy-label-resolver state universe reference-instruments)
         load-in-progress? (exposure/history-load-in-progress? state load-state)
         proxy-in-flight? (exposure/proxy-in-flight-fn state load-in-progress?)
         search-queries (get-in state contracts/ui-proxy-search-queries-path)
         ;; The shared covariance window (proxy-extended: complete proxy assets
         ;; are excluded from alignment, so this is the window the risk model
         ;; estimates over). One number for the whole universe.
         covariance-observations (let [n (get-in readiness
                                                 [:request :history :history-window
                                                  :return-observations])]
                                   (when (and (number? n) (pos? n)) n))
         cards (->> universe
                    (keep (fn [instrument]
                            (let [id (:instrument-id instrument)
                                  entry (get assumptions id)
                                  ;; Same adequacy signal as the universe-row badge:
                                  ;; :pending until history loads (so a historied asset
                                  ;; never flashes a card), and :short for thin-but-
                                  ;; present history below the user-facing threshold.
                                  status (universe/selected-history-status
                                          state readiness load-state
                                          history-status-by-id instrument)
                                  adequacy (universe/history-adequacy status state readiness
                                                                      instrument)]
                              (when (and id
                                         (or (some? entry)
                                             (contains? card-needing-adequacy adequacy)))
                                (let [complete? (and entry
                                                     (history-assumptions/assumption-complete?
                                                      entry
                                                      return-required?
                                                      {:self-id id
                                                       :usable-proxy-ids usable-ids
                                                       :max-asset-weight
                                                       (get-in draft [:constraints :max-asset-weight])}))
                                      warnings (get warnings-by-id id)
                                      errors (keep :message warnings)]
                                  (history-assumption-card
                                   {:instrument instrument
                                    :label (universe/instrument-primary-label instrument)
                                    :entry entry
                                    :recommendation (card-recommendation
                                                     state instrument entry
                                                     resolve-proxy-label)
                                    :complete? complete?
                                    :errors errors
                                    :warnings warnings
                                    :note nil
                                    :percent-label* percent-label*
                                    :assumption-required? (contains? required-ids id)
                                    :return-required? return-required?
                                    :observations (universe/native-history-observations
                                                   state readiness instrument)
                                    :covariance-observations covariance-observations
                                    :readiness readiness
                                    :state state
                                    :usable-ids usable-ids
                                    :proxy-in-flight? proxy-in-flight?
                                    :resolve-proxy-label resolve-proxy-label
                                    :search-query (get search-queries id)}))))))
                    ;; Stack unconfigured cards above configured ones so the
                    ;; asset you still have to act on never hides beneath a
                    ;; finished one; stable, so within each group the universe
                    ;; order is untouched.
                    (sort-by card-attention-order)
                    vec)
         carded-ids (into #{} (map :instrument-id) cards)
         ;; Any selected asset can be brought into the workflow by hand - the user
         ;; may judge an asset statistically unsound (a young listing, a stub
         ;; return stream) even when the thresholds do not flag it. The engine
         ;; backs proxy assumptions by completeness, not by thinness, so this is
         ;; purely an entry-point list.
         addable (->> universe
                      (keep (fn [instrument]
                              (let [id (:instrument-id instrument)]
                                (when (and id (not (contains? carded-ids id)))
                                  (let [label (universe/instrument-primary-label instrument)
                                        observations (universe/native-history-observations
                                                      state readiness instrument)]
                                    {:instrument-id id
                                     :label label
                                     :observations observations
                                     :option-label (addable-option-label label
                                                                         observations)})))))
                      (sort-by addable-sort-key)
                      vec)]
     {:cards cards
      :addable-assets addable
      ;; Cards whose backend recommendation can apply with one click - the
      ;; section-level "Apply all recommended" banner keys off this.
      :recommended-count (count (filter #(get-in % [:recommendation :applicable?])
                                        cards))
      :recommended-actions recommended-action-ids
      ;; Cards whose proxy history is still fetching - the section-level
      ;; "background work in progress" banner keys off this.
      :history-loading-count (count (filter :history-loading? cards))
      ;; :applicable? keeps meaning "cards exist" (the right-rail summary and
      ;; the while-loading suppression key off it); the section itself also
      ;; renders in a compact form when only the manual entry point applies.
      :applicable? (boolean (seq cards))})))
