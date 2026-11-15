(ns hyperopen.portfolio.optimizer.application.history-assumptions-io
  "Pure export/import model for the proxy workflow's file channel: the user
  downloads a template listing every asset that needs a history assumption, a
  desktop AI agent fills in proxy baskets / conservative choices offline, and
  the completed file is imported to author those assumptions in bulk.

  Honesty invariants, inherited from the return-views IO precedent:
  - unconfigured assets export `approach: null` and import skips null
    approaches, so an unedited round-trip is a no-op;
  - already-configured assets export their current setup and a rebuilt entry
    equal to the existing one (modulo provenance metadata) counts as
    unchanged;
  - import never removes an assumption — forgetting stays the card's explicit
    Clear gesture.

  No DOM and no clock live here; callers supply `now-ms`."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]))

(def export-document-type
  "hyperopen-optimizer-history-assumptions")

(def export-document-version
  1)

(def preferred-history-days
  "Soft preference the instructions state for proxy history length (~2 years):
  the covariance request window is ~3 years, so longer proxies estimate better.
  The hard bar is history-assumptions/short-history-min-observations."
  720)

(def max-basket-size
  "Import clamps proxy baskets to this many members — the same 1-4 contract the
  embedded instructions state, kept honest by enforcement."
  4)

(def export-scopes
  "What the `assets` section covers: just the assets already flagged in the
  proxy workflow, or every included universe asset (so the agent can also flag
  ones IT judges untrustworthy). The file carries ONLY those rows — no
  candidate menu of any kind (maintainer feedback 2026-07-07, twice: first the
  exchange catalog polluted the agent's context, then the universe-wide
  verified menu did). The agent proposes proxies from its own market knowledge;
  import validation drops and reports anything unknown or short-history."
  #{:proxy-workflow :universe})

(defn normalize-export-scope
  [scope]
  (if (contains? export-scopes scope) scope :proxy-workflow))

(def export-instructions
  "The agent-facing prompt embedded in every export, as an ARRAY of markdown
  lines (a single JSON string of this length is unreadable in the pretty-printed
  file). Every number here states what the engine actually does — see
  domain.history-assumptions and domain.history-assumption-proxy."
  ["# What this file is"
   "This is an assumptions template exported from a mean-variance portfolio optimizer (daily returns) trading crypto instruments — perps, spot, and vaults — on Hyperliquid. Each entry under `assets` is in the user's portfolio universe but has too little native price history to estimate risk from data (`native-days` = its daily return observations; under ~360 is flagged, under 30 the optimizer blocks the run). Until an asset gets an assumption it is EXCLUDED from the optimized portfolio."
   "Your task: for each asset in `assets`, EITHER select a proxy basket (`approach: \"proxy\"`) OR mark it conservative (`approach: \"conservative\"`), filling the fields in place in this JSON. The user imports the completed file back into the app."
   "`export-scope` says what `assets` covers: \"proxy-workflow\" lists only the assets already flagged for assumptions; \"universe\" lists every included portfolio asset so you can also flag ones YOU judge statistically untrustworthy. Leave `approach` null for any asset that should keep using its own history."
   "Where proxies come from: propose them from your own market knowledge — liquid, long-established assets listed on Hyperliquid (majors like BTC, ETH, SOL, or sector peers you know are listed there as perps). There is no menu to pick from: the app validates every proxy on import, drops any symbol it doesn't recognize or whose history is too short, tells the user what was dropped, and renormalizes the basket over what survives. In a \"universe\" export, the `assets` list itself shows the user's whole portfolio with a `history-status` per asset — prefer proxies from its \"verified\" entries (at least `history-policy.minimum-history-days` of usable daily history), since those are guaranteed to resolve. Use outside research only to understand what each token IS (ecosystem, sector, value accrual); every row carries its full `name` and market `kind` to help."
   "# How your answers are used"
   "- A proxy basket is a co-movement model, not a lookalike list. The optimizer builds the asset's covariance row from the weighted returns of the basket. Pick assets whose DAILY RETURNS should move with the target — shared ecosystem, sector, risk drivers, volatility tier. Narrative or price similarity is irrelevant, and correlation over a short window alone is not evidence."
   "- Your weights are a prior that starts out as the whole model. The optimizer ridge-regresses the target's short history against the basket (once max(10, k+3) overlapping days exist; k = basket size) and blends it in with weight q ~= (n/(n+120)) * min(1, R^2/0.5). With 12 days of overlap q <= 9%, so your prior is ~91% of the model. Set weights as economic exposure loadings you would defend being used as-is — never as correlation or R^2 scores. Omit all weights for equal weight; weights must be >= 0 and are normalized to sum to 1."
   "- `relationship-strength` caps how much of the asset's variance the basket may ever explain: at least 50% stays idiosyncratic for \"low\", 25% for \"medium\", 15% for \"high\". \"high\" = near-mechanical link (wrapper or derivative of the same underlying); \"medium\" = solid same-sector peers; \"low\" = loose or anchors-only."
   "- `approach: \"conservative\"` models the asset at ~80% annualized volatility with a 0.75 correlation floor to everything and a ~3% allocation cap — it almost always yields a tiny or zero allocation. It is the honest choice when no defensible basket exists."
   "- After import the app re-validates every pick: a member whose history proves unusable is dropped and the basket renormalizes over the rest; if none survive, the asset stays excluded."
   "# Choosing a basket"
   "- Build 2-4 members from different roles: a broad market anchor (BTC/ETH), a chain/ecosystem proxy (e.g. SOL for a Solana-ecosystem token), sector peers (DeFi, L2, AI, gaming, memecoin, exchange/revenue tokens), or a token-economics twin (similar fee/buyback/staking accrual). Diversified beats near-identical; a single proxy imports one asset's idiosyncratic shocks wholesale and is only right for mechanical relationships."
   "- Include an anchor when useful, but never let anchors substitute for real sector peers. If only anchors fit, the basket is crude: set relationship-strength \"low\" and say so in `rationale`."
   "- Never pick a stablecoin or cash-like asset unless the target is one. Prefer long-established, liquid assets (`history-policy.preferred-history-days` or more of trading history is ideal). Never use a recently-listed or short-history asset as a proxy — it cannot anchor another and will be dropped on import."
   "- If you cannot name at least 2 defensible proxies, choose \"conservative\". That is a correct answer, not a failure."
   "# Fields to fill, per asset"
   "- `approach`: \"proxy\" or \"conservative\". Leave null to skip — the asset stays excluded."
   "- `proxies` (proxy only): 1-4 entries, each {\"instrument-id\": \"perp:SOL\", \"weight\": 0.5} or {\"symbol\": \"SOL\", \"weight\": 0.5}. Hyperliquid perp ids are \"perp:\" plus the ticker; either field accepts either form and matching is case-insensitive. Proxies the app can't resolve to a listed asset are dropped on import and reported to the user."
   "- `relationship-strength` (proxy only): \"low\" | \"medium\" | \"high\" (default \"medium\")."
   "- `expected-return-percent`: annualized, in percent (e.g. 12.5). Check `optimization-objective` and `objective-uses-returns` — minimum-variance ignores returns entirely; leave null unless the objective uses returns and you hold a real view (default 0 = no edge)."
   "- `rationale`: 1-3 sentences the user will read: why this basket (or why conservative), plus any notable rejection — e.g. a perfect sector match skipped for short history."
   "# Output rules"
   "1. Return this same document as pure, valid JSON — no surrounding prose, no markdown fences, no new top-level keys."
   "2. Do not edit `instrument-id`, `symbol`, `name`, `kind`, `native-days`, `history-status`, `history-policy`, or any other read-only field. Do not add or remove assets."
   "3. Assets whose `status` is already \"configured\" show the user's current setup — leave them unchanged unless asked to revisit them."])

(defn strip-code-fences
  "Agents wrap JSON in markdown fences no matter what the instructions say.
  When the text opens with ``` the fence line (and a trailing fence line, when
  present) are dropped; clean JSON passes through untouched."
  [text]
  (let [text* (str/trim (str (or text "")))]
    (if-not (str/starts-with? text* "```")
      text*
      (let [body (vec (rest (str/split-lines text*)))
            body (if (and (seq body)
                          (str/starts-with? (str/trim (peek body)) "```"))
                   (pop body)
                   body)]
        (str/trim (str/join "\n" body))))))

(defn- percent-number
  "Decimal -> percent as a clean number (15, not 15.000000000000002)."
  [decimal]
  (when (coercion/finite-number? decimal)
    (let [text (coercion/decimal->percent-text decimal)
          parsed (js/Number text)]
      (when (coercion/finite-number? parsed)
        parsed))))

(defn- export-proxies
  [entry symbol-by-id]
  (let [weights (history-assumptions/proxy-prior-weights entry)]
    (mapv (fn [proxy-id]
            {:instrument-id proxy-id
             :symbol (get symbol-by-id proxy-id)
             :weight (when weights (get weights proxy-id))})
          (history-assumptions/proxy-instrument-ids entry))))

(defn- export-asset
  [symbol-by-id {:keys [instrument-id symbol name kind native-days status entry verified?]}]
  (let [behavior (:behavior entry)
        proxy? (= :proxy behavior)]
    {:instrument-id instrument-id
     :symbol symbol
     :name name
     :kind kind
     :native-days native-days
     :history-status (if verified? "verified" "unverified")
     :status status
     :approach (some-> behavior cljs.core/name)
     :proxies (if proxy? (export-proxies entry symbol-by-id) [])
     :relationship-strength (when proxy?
                              (cljs.core/name
                               (history-assumptions/relationship-strength entry)))
     :expected-return-percent (when entry (percent-number (:expected-return entry)))
     :rationale (when entry
                  (coercion/non-blank-text (get-in entry [:metadata :rationale])))}))

(defn export-payload
  "Download payload for the assumptions template. `rows` are the target assets
  ({:instrument-id :symbol :name :kind :native-days :status :verified? :entry})
  — the workflow's flagged assets or the whole included universe, per `scope`.
  The file carries NOTHING beyond those rows (no candidate menu): the agent
  proposes proxies from its own market knowledge and import validation drops
  anything unknown or short-history. The caller supplies
  `symbol-by-instrument-id` covering every id a stored basket may reference.
  Returns {:filename … :count … :document …} for the download effect."
  [{:keys [rows objective-kind now-ms symbol-by-instrument-id scope]}]
  (let [scope* (normalize-export-scope scope)]
    {:filename (str "history-assumptions-"
                    (if (= :universe scope*) "universe" "workflow")
                    "-" now-ms ".json")
     :count (count rows)
     :document {:type export-document-type
                :version export-document-version
                :exported-at now-ms
                :export-scope (name scope*)
                :instructions export-instructions
                :history-policy {:minimum-history-days
                                 history-assumptions/short-history-min-observations
                                 :preferred-history-days preferred-history-days
                                 :candle-interval "1d"}
                :optimization-objective (some-> objective-kind name)
                :objective-uses-returns (history-assumptions/return-required-for-objective?
                                         objective-kind)
                :assets (mapv #(export-asset symbol-by-instrument-id %) rows)}}))

(defn- entry-field
  [entry & keys*]
  (some (fn [key*]
          (let [value (or (get entry key*)
                          (get entry (name key*)))]
            (when (some? value) value)))
        keys*))

(defn extract-entries
  "Candidate asset maps from parsed import data: the full envelope (`assets`,
  with `entries` tolerated as an alias) or a bare array of asset maps. Nil when
  the structure is unusable."
  [data]
  (cond
    (map? data) (let [entries (entry-field data :assets :entries)]
                  (when (sequential? entries)
                    (filterv map? entries)))
    (sequential? data) (filterv map? data)
    :else nil))

(defn- pool-instrument-id
  [instrument]
  (or (coercion/non-blank-text (:instrument-id instrument))
      (coercion/non-blank-text (:key instrument))))

(defn- instrument-symbol
  [instrument]
  (or (coercion/non-blank-text (:coin instrument))
      (coercion/non-blank-text (:symbol instrument))
      (coercion/non-blank-text (:name instrument))
      (some-> (pool-instrument-id instrument)
              (str/split #":")
              last)))

(defn- instrument-ids
  [instruments]
  (into #{} (keep pool-instrument-id) instruments))

(defn- instrument-id-by-symbol
  "Case-insensitive symbol -> instrument-id, ambiguous symbols dropped."
  [instruments]
  (let [pairs (keep (fn [instrument]
                      (when-let [instrument-id (pool-instrument-id instrument)]
                        (when-let [symbol* (instrument-symbol instrument)]
                          [(str/upper-case symbol*) instrument-id])))
                    instruments)
        grouped (group-by first pairs)]
    (into {}
          (keep (fn [[symbol* matches]]
                  (when (= 1 (count (distinct (map second matches))))
                    [symbol* (second (first matches))])))
          grouped)))

(defn- resolution-index
  "Lenient lookup index over an instrument pool: exact ids, case-insensitive
  ids, and case-insensitive symbols (ambiguous symbols dropped)."
  [instruments]
  (let [ids (instrument-ids instruments)]
    {:ids ids
     :id-by-upper (into {} (map (fn [id] [(str/upper-case id) id])) ids)
     :by-symbol (instrument-id-by-symbol instruments)}))

(defn- resolve-instrument-text
  "One agent-written reference -> instrument-id, leniently: exact id, then
  case-insensitive id, then symbol, then the text after a namespace colon as a
  symbol. Agents routinely write ids like \"perp:AAVE\" into the `symbol`
  field (a real import failed exactly this way, 2026-07-07), so every field
  accepts every form."
  [{:keys [ids id-by-upper by-symbol]} text]
  (when-let [text* (coercion/non-blank-text text)]
    (let [upper (str/upper-case text*)]
      (or (when (contains? ids text*) text*)
          (get id-by-upper upper)
          (get by-symbol upper)
          (when (str/includes? text* ":")
            (get by-symbol (str/upper-case (last (str/split text* #":")))))))))

(defn- resolve-ref
  "One imported reference -> instrument-id: a map carrying `instrument-id`
  and/or `symbol` (either field, either form), or a bare string. Nil when the
  pool doesn't know it."
  [index ref]
  (cond
    (map? ref) (or (resolve-instrument-text index (entry-field ref :instrument-id))
                   (resolve-instrument-text index (entry-field ref :symbol)))
    (string? ref) (resolve-instrument-text index ref)
    :else nil))

(defn- resolve-basket
  "Imported proxies -> {:ids [..] :weights {id w}|nil :dropped n}. Unresolvable
  refs, self-references, and duplicates are dropped (counted); the basket is
  clamped to max-basket-size. Weights apply all-or-nothing: every kept member
  carries a finite >= 0 weight with a positive total -> normalized to sum 1;
  anything else -> nil (equal weight), mirroring the engine's prior fallback."
  [pool-index self-id refs]
  (let [refs* (if (sequential? refs) (vec refs) [])
        resolved (reduce (fn [acc ref]
                           (let [proxy-id (resolve-ref pool-index ref)]
                             (if (and proxy-id
                                      (not= proxy-id self-id)
                                      (not (contains? (:seen acc) proxy-id)))
                               (-> acc
                                   (update :seen conj proxy-id)
                                   (update :members conj
                                           {:id proxy-id
                                            :weight (when (map? ref)
                                                      (coercion/parse-number
                                                       (entry-field ref :weight)))}))
                               acc)))
                         {:seen #{} :members []}
                         refs*)
        members (vec (take max-basket-size (:members resolved)))
        weights (when (and (seq members)
                           (every? (fn [{:keys [weight]}]
                                     (and (coercion/finite-number? weight)
                                          (>= weight 0)))
                                   members)
                           (pos? (reduce + 0 (map :weight members))))
                  (let [total (reduce + 0 (map :weight members))]
                    (into {}
                          (map (fn [{:keys [id weight]}] [id (/ weight total)]))
                          members)))]
    {:ids (mapv :id members)
     :weights weights
     :dropped (- (count refs*) (count members))}))

(defn- imported-metadata
  [rationale]
  (cond-> {:source :agent-import :acknowledged? true}
    rationale (assoc :rationale rationale)))

(defn- base-entry
  "Start from the existing entry when the behavior matches (guardrail edits
  survive); otherwise seed the behavior's defaults preserving the user's
  return/volatility — the same stance as the mode-switch action."
  [existing behavior]
  (if (= behavior (:behavior existing))
    existing
    (cond-> (history-assumptions/default-assumption behavior)
      (some? (:expected-return existing))
      (assoc :expected-return (:expected-return existing))

      (some? (:volatility existing))
      (assoc :volatility (:volatility existing)))))

(defn- build-proxy-entry
  [existing {:keys [ids weights strength return-value rationale]}]
  (let [base (base-entry existing :proxy)
        strength* (or strength (history-assumptions/relationship-strength base))]
    (-> base
        (assoc-in [:proxy :instrument-ids] ids)
        (assoc-in [:proxy :relationship-strength] strength*)
        (assoc-in [:proxy :prior-weights] weights)
        (cond-> (some? return-value) (assoc :expected-return return-value))
        (assoc :metadata (imported-metadata rationale)))))

(defn- build-conservative-entry
  [existing {:keys [return-value rationale]}]
  (-> (base-entry existing :conservative)
      (cond-> (some? return-value) (assoc :expected-return return-value))
      (assoc :metadata (imported-metadata rationale))))

(defn- same-assumption?
  "Round-trip equality, MODULO provenance metadata (a hand-authored entry
  carries :source :user; rewriting it just to restamp provenance would break
  the unedited-round-trip-is-a-no-op invariant). A changed rationale still
  counts as a change — it is user-visible."
  [built existing]
  (and (some? existing)
       (= (dissoc built :metadata) (dissoc existing :metadata))
       (= (get-in built [:metadata :rationale])
          (get-in existing [:metadata :rationale]))))

(defn import-plan
  "Decide what an imported file does to the draft's history assumptions.

  Returns {:status :error :reason :invalid|:empty} for unusable/entry-less
  files, otherwise
  {:status :ok
   :assumptions <final map>
   :applied-instrument-ids [ … ]
   :applied n :unchanged u :unknown k :invalid i :skipped-proxies p}.

  `approach: null` leaves the asset untouched; unknown assets and invalid
  entries (bad approach, empty post-drop basket) are counted and skipped.
  Import never removes an assumption."
  [{:keys [assumptions universe proxy-pool data]}]
  (if-let [entries (extract-entries data)]
    (if (empty? entries)
      {:status :error :reason :empty}
      (let [universe-index (resolution-index universe)
            pool-index (resolution-index (concat universe proxy-pool))]
        (reduce
         (fn [acc entry]
           (let [instrument-id (resolve-ref universe-index entry)
                 approach (coercion/normalize-keyword-like
                           (entry-field entry :approach))]
             (cond
               (nil? instrument-id)
               (update acc :unknown inc)

               (nil? approach)
               (update acc :unchanged inc)

               (not (contains? history-assumptions/behaviors approach))
               (update acc :invalid inc)

               :else
               (let [existing (get (:assumptions acc) instrument-id)
                     return-value (coercion/parse-percent-text
                                   (entry-field entry
                                                :expected-return-percent
                                                :expected-return))
                     rationale (some-> (entry-field entry :rationale)
                                       str
                                       coercion/non-blank-text)
                     basket (when (= :proxy approach)
                              (resolve-basket pool-index instrument-id
                                              (entry-field entry :proxies)))
                     strength (when (= :proxy approach)
                                (let [value (coercion/normalize-keyword-like
                                             (entry-field entry :relationship-strength))]
                                  (when (contains? history-assumptions/relationship-strengths
                                                   value)
                                    value)))
                     acc (update acc :skipped-proxies + (or (:dropped basket) 0))
                     built (case approach
                             :conservative
                             (build-conservative-entry existing
                                                       {:return-value return-value
                                                        :rationale rationale})

                             :proxy
                             (when (seq (:ids basket))
                               (build-proxy-entry existing
                                                  {:ids (:ids basket)
                                                   :weights (:weights basket)
                                                   :strength strength
                                                   :return-value return-value
                                                   :rationale rationale})))]
                 (cond
                   (nil? built)
                   (update acc :invalid inc)

                   (same-assumption? built existing)
                   (update acc :unchanged inc)

                   :else
                   (-> acc
                       (assoc-in [:assumptions instrument-id] built)
                       (update :applied-instrument-ids conj instrument-id)
                       (update :applied inc)))))))
         {:status :ok
          :assumptions (or assumptions {})
          :applied-instrument-ids []
          :applied 0
          :unchanged 0
          :unknown 0
          :invalid 0
          :skipped-proxies 0}
         entries)))
    {:status :error :reason :invalid}))

(defn- pluralize-assets
  [n]
  (if (= 1 n) (str n " asset") (str n " assets")))

(defn export-success-message
  [n]
  (str "Exported " (pluralize-assets n) " to a JSON template — hand it to an AI"
       " agent to fill in, then import the completed file here."))

(def export-empty-message
  "Nothing needs assumptions right now — bring an asset into the workflow before exporting.")

(def export-universe-empty-message
  "Add assets to the universe before exporting.")

(defn import-success-message
  [{:keys [applied unchanged unknown invalid skipped-proxies]}]
  (if (zero? (or applied 0))
    (str/join " · "
              (keep identity
                    ["No assumptions applied"
                     (when (pos? (or unchanged 0))
                       (str unchanged " left as-is (approach null or already matching)"))
                     (when (pos? (or unknown 0))
                       (str unknown " unknown "
                            (if (= 1 unknown) "asset" "assets") " skipped"))
                     (when (pos? (or invalid 0))
                       (str invalid
                            (if (= 1 invalid) " entry" " entries")
                            " skipped — invalid approach or no resolvable proxies"))
                     (when (pos? (or skipped-proxies 0))
                       (str skipped-proxies " unrecognized "
                            (if (= 1 skipped-proxies) "proxy" "proxies")
                            " dropped"))]))
    (str (str/join " · "
                   (keep identity
                         [(str "Configured " (pluralize-assets applied))
                          (when (pos? (or unchanged 0))
                            (str unchanged " left as-is"))
                          (when (pos? (or unknown 0))
                            (str unknown " unknown "
                                 (if (= 1 unknown) "asset" "assets") " skipped"))
                          (when (pos? (or invalid 0))
                            (str invalid " invalid "
                                 (if (= 1 invalid) "entry" "entries") " skipped"))
                          (when (pos? (or skipped-proxies 0))
                            (str skipped-proxies " unknown "
                                 (if (= 1 skipped-proxies) "proxy" "proxies")
                                 " dropped"))]))
         " — history validation continues on the cards below.")))

(defn import-error-message
  [reason]
  (case reason
    :empty "Import failed: no assets found in the file."
    "Import failed: the file is not valid history-assumptions JSON."))
