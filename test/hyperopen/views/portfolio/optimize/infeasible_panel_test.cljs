(ns hyperopen.views.portfolio.optimize.infeasible-panel-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.engine.payload :as engine-payload]
            [hyperopen.portfolio.optimizer.infrastructure.wire :as wire]
            [hyperopen.views.portfolio.optimize.infeasible-panel :as panel]
            [hyperopen.views.portfolio.optimize.test-support :as support]))

;; The reported Minimum-risk failure, verbatim in shape and numbers as
;; domain/exposure_reachability emits it: an 18-long / 2-short universe whose two
;; shorts are capped at 3% and 5% by their history assumptions, so a 3.995x gross
;; floor forces net to at least 3.835x while the net band allows at most 1.362x.
(def ^:private net-unreachable-violation
  {:code :net-unreachable-given-sides
   :constraint-code :gross-exposure
   :direction :net-above-band
   :driver :gross-floor-vs-short-capacity
   :reachable-net {:min 3.835 :max 4.005}
   ;; The domain's inward snap of :reachable-net; the panel quotes THIS one.
   :reachable-net-display {:min 3.84 :max 4.0}
   :long-only? false
   :net-band-encodable? true
   :net-bounds {:min 1.25 :max 1.25}
   :net-band-pct 0.028
   :gross-floor 3.995
   :gross-window {:min 3.995 :max 4.005}
   :required-short-notional 1.31643
   :available-short-notional 0.08
   ;; Both thresholds are SOLVED against the encoded rows at the gross floor,
   ;; where the coupled band row binds: (1.25 + 2*0.08) / (1 - 0.028), and the
   ;; smallest whole-percent band that holds the 3.995x floor instead.
   :max-feasible-gross-floor 1.4506172839506173
   :min-feasible-net-band-pct 0.65
   :binding-capacity [{:instrument-id "perp:UNITREE" :capacity 0.05}
                      {:instrument-id "perp:SPCX" :capacity 0.03}]
   :zero-capacity-count 18
   :message (str "A 3.99x gross floor with only 0.08x of short capacity forces net to at "
                 "least 3.84x, above the 1.36x the net band allows.")})

(defn- reachability-result
  [& violation-overrides]
  {:status :infeasible
   :reason :constraint-presolve
   :details {:violations [(merge net-unreachable-violation
                                 (apply merge violation-overrides))]}})

(def ^:private net-unreachable-result (reachability-result))

(def ^:private minimum-risk-violation
  {:code :minimum-risk-without-exposure-floor
   :objective-kind :minimum-variance
   :current-gross 4.0
   :max-feasible-gross-floor 4.0
   :suggested-gross-floor 4.0
   :message (str "Minimum risk has an all-cash optimum here: every exposure control in "
                 "this setup is a ceiling. Set Gross Exposure Min (your current book is "
                 "4.00x gross), or a non-zero Net Exposure Min.")})

(defn- cash-collapse-result
  [& violation-overrides]
  {:status :infeasible
   :reason :objective-collapses-to-cash
   :details {:violations [(merge minimum-risk-violation
                                 (apply merge violation-overrides))]}})

(def ^:private minimum-risk-result (cash-collapse-result))

(defn- suggestion-ids
  [result]
  (mapv :id (panel/remediation-suggestions result)))

(defn- suggestion-by-id
  [result id]
  (first (filter #(= id (:id %)) (panel/remediation-suggestions result))))

(defn- banner
  [result]
  (panel/infeasible-banner result (panel/highlighted-control-keys result)))

(defn- fix-actions
  [node id]
  (support/click-actions
   (support/node-by-role node (str "portfolio-optimizer-infeasible-fix-" (name id)))))

(defn- copy-of
  [result id]
  (:copy (suggestion-by-id result id)))

;; --- :net-unreachable-given-sides --------------------------------------------

(deftest net-unreachable-offers-every-payload-derived-path-in-order-test
  (is (= [:raise-net-target
          :lower-gross-floor
          :clear-gross-floor
          :add-short-capacity
          :widen-net-band]
         (suggestion-ids net-unreachable-result))))

(deftest net-unreachable-raise-net-target-pins-net-to-the-reachable-bound-test
  (let [suggestion (suggestion-by-id net-unreachable-result :raise-net-target)]
    ;; Both quoted ends come from :reachable-net-display, snapped INWARD by the
    ;; domain, so the button writes a number inside the exact [3.835, 4.005].
    (is (= "Set net to +3.84x" (:label suggestion)))
    (is (= [[:actions/set-portfolio-optimizer-constraint :net-min 3.84]
            [:actions/set-portfolio-optimizer-constraint :net-max 3.84]]
           (:actions suggestion)))
    (is (str/includes? (:copy suggestion) "a net of +3.84x to +4.00x"))
    ;; The exact upper bound rounds OUTWARD to 4.01x, which is above 4.005.
    (is (not (str/includes? (:copy suggestion) "4.01x")))))

(deftest net-unreachable-without-a-display-interval-omits-the-net-target-test
  ;; A window narrower than 0.01x holds no two-decimal value, so the domain
  ;; publishes no display interval and no honest number exists for a button.
  (let [result (reachability-result {:reachable-net-display nil})]
    (is (nil? (suggestion-by-id result :raise-net-target)))
    (is (nil? (suggestion-by-id result :widen-net-band)))))

(deftest net-unreachable-gross-floor-paths-use-the-payload-threshold-test
  (let [lower (suggestion-by-id net-unreachable-result :lower-gross-floor)
        clear (suggestion-by-id net-unreachable-result :clear-gross-floor)]
    ;; 1.45x, not the 1.52x the band widened at the gross CEILING produced -
    ;; that value was still infeasible, so the button returned the trader here.
    (is (= "Lower gross floor to 1.45x" (:label lower)))
    (is (= [[:actions/set-portfolio-optimizer-constraint :gross-min 1.45]]
           (:actions lower)))
    ;; The floor is quoted DOWN: 4.00x is above the 3.995x actually set.
    (is (str/includes? (:copy lower)
                       "The 3.99x gross floor is what forces net out of range."))
    (is (str/includes? (:copy lower) "Lowering Gross Exposure Min to 1.45x"))
    (is (str/includes? (:copy clear) "Or drop the 3.99x floor entirely"))
    (is (= "Clear gross floor" (:label clear)))
    ;; :gross-min is clearable, so ::portfolio-optimizer-constraint-args takes nil.
    (is (= [[:actions/set-portfolio-optimizer-constraint :gross-min nil]]
           (:actions clear))))
  ;; No published threshold means NO gross value satisfies the net rows, so
  ;; neither lowering the floor nor clearing it unblocks the run.
  (let [hopeless (reachability-result {:max-feasible-gross-floor nil})]
    (is (nil? (suggestion-by-id hopeless :lower-gross-floor)))
    (is (nil? (suggestion-by-id hopeless :clear-gross-floor))))
  ;; A threshold under a cent leaves no positive floor to write; clearing owns it.
  (let [sub-cent (reachability-result {:max-feasible-gross-floor 0.004})]
    (is (nil? (suggestion-by-id sub-cent :lower-gross-floor)))
    (is (some? (suggestion-by-id sub-cent :clear-gross-floor)))))

(deftest net-unreachable-short-capacity-path-is-copy-only-and-names-the-assets-test
  (let [suggestion (suggestion-by-id net-unreachable-result :add-short-capacity)]
    (is (nil? (:actions suggestion)))
    (is (nil? (:label suggestion)))
    (is (= (str "Reaching +1.25x net against a 3.99x gross floor needs 1.32x of shorts; "
                "this universe offers 0.08x (UNITREE 0.05x, SPCX 0.03x). Flip more assets "
                "to the short side, or raise those assets' weight caps. 18 selected assets "
                "carry no short capacity at all.")
           (:copy suggestion)))
    (is (str/includes? (copy-of (reachability-result {:zero-capacity-count 1})
                                :add-short-capacity)
                       "1 selected asset carries no short capacity at all."))))

(deftest net-unreachable-side-capacity-names-assets-or-says-nothing-test
  ;; Spot ids and vault addresses have no readable tail, which is why
  ;; engine/payload resolves a symbol onto these rows.
  (is (str/includes?
       (copy-of (reachability-result
                 {:binding-capacity [{:instrument-id "spot:@142"
                                      :display-symbol "PURR"
                                      :capacity 0.05}
                                     {:instrument-id "perp:SPCX" :capacity 0.03}]})
                :add-short-capacity)
       "offers 0.08x (PURR 0.05x, SPCX 0.03x)"))
  ;; No asset named AND no capacity leaves "offers 0.00x" with nothing behind it.
  (let [result (reachability-result {:binding-capacity []
                                     :available-short-notional 0
                                     :zero-capacity-count 20})]
    (is (nil? (suggestion-by-id result :add-short-capacity)))
    (is (not-any? #(str/includes? % "this universe offers")
                  (support/collect-strings (banner result))))))

(deftest net-unreachable-widen-net-band-quotes-the-solved-threshold-test
  (let [suggestion (suggestion-by-id net-unreachable-result :widen-net-band)]
    (is (= "Widen net band to 65%" (:label suggestion)))
    (is (= [[:actions/set-portfolio-optimizer-constraint :net-band-pct 0.65]]
           (:actions suggestion)))
    (is (str/includes? (:copy suggestion) "far enough to reach +3.84x")))
  ;; The threshold is the DOMAIN's: the band scales with realized gross, so it
  ;; binds at the gross floor, and the panel carries no box to solve that from.
  ;; Absent, the suggestion is omitted rather than re-derived from the ceiling.
  (is (nil? (suggestion-by-id (reachability-result {:min-feasible-net-band-pct nil})
                              :widen-net-band)))
  ;; Above the hard 100% ceiling the write would be clamped on read, leaving the
  ;; control and the solver disagreeing.
  (is (nil? (suggestion-by-id (reachability-result {:min-feasible-net-band-pct 1.4})
                              :widen-net-band)))
  ;; A band above the SLIDER's 50% still writes and still holds, so capping the
  ;; suggestion there would drop every fix in the upper half of the legal range.
  (is (= [[:actions/set-portfolio-optimizer-constraint :net-band-pct 0.8]]
         (:actions (suggestion-by-id (reachability-result
                                      {:min-feasible-net-band-pct 0.8})
                                     :widen-net-band)))))

(deftest net-unreachable-omits-suggestions-whose-payload-fields-are-missing-test
  (let [result (reachability-result
                ;; A dropped floor, and a required-short the presolve could not pin.
                {:gross-floor nil
                 :gross-window {:min 0 :max 4.005}
                 :max-feasible-gross-floor nil
                 :required-short-notional nil})
        strings (support/collect-strings (banner result))]
    (is (= [:raise-net-target :widen-net-band] (suggestion-ids result)))
    (is (nil? (support/node-by-role (banner result)
                                    "portfolio-optimizer-infeasible-suggestion-lower-gross-floor")))
    (is (nil? (support/node-by-role (banner result)
                                    "portfolio-optimizer-infeasible-suggestion-add-short-capacity")))
    ;; A dropped field removes the suggestion rather than surfacing as "N/A".
    (is (not-any? #(or (str/includes? % "N/A") (str/includes? % "NaN")) strings))))

(deftest net-unreachable-below-band-mirrors-the-long-side-test
  (let [result (reachability-result
                {:direction :net-below-band
                 :driver :gross-max-vs-short-floor
                 :reachable-net {:min -2.0 :max 0.4}
                 :reachable-net-display {:min -2.0 :max 0.4}
                 :net-bounds {:min 1.0 :max 1.0}
                 :net-band-pct 0.0
                 :gross-floor nil
                 :max-feasible-gross-floor nil
                 :min-feasible-net-band-pct 0.6
                 :gross-window {:min 0 :max 1.0}
                 :required-long-notional 0.5
                 :available-long-notional 0.2
                 :binding-capacity [{:instrument-id "perp:BTC" :capacity 0.2}]
                 :zero-capacity-count 3
                 :message "A 1.00x gross cap holds net at or below 0.40x."})
        lower (suggestion-by-id result :lower-net-target)
        capacity (suggestion-by-id result :add-long-capacity)]
    (is (= [:lower-net-target :add-long-capacity :widen-net-band]
           (suggestion-ids result)))
    (is (= "Set net to +0.40x" (:label lower)))
    (is (= [[:actions/set-portfolio-optimizer-constraint :net-min 0.4]
            [:actions/set-portfolio-optimizer-constraint :net-max 0.4]]
           (:actions lower)))
    (is (nil? (:actions capacity)))
    (is (str/includes? (:copy capacity)
                       "needs 0.50x of longs; this universe offers 0.20x (BTC 0.20x)"))
    (is (= [[:actions/set-portfolio-optimizer-constraint :net-band-pct 0.6]]
           (:actions (suggestion-by-id result :widen-net-band))))))

(deftest net-unreachable-banner-renders-the-block-and-wires-every-fix-test
  (let [node (banner net-unreachable-result)
        block (support/node-by-role node "portfolio-optimizer-infeasible-suggestions")
        strings (set (support/collect-strings node))]
    (is (some? block))
    (is (contains? strings "What you can do"))
    (is (contains? strings "Set net to +3.84x"))
    (is (contains? strings "Lower gross floor to 1.45x"))
    (is (contains? strings "Clear gross floor"))
    (is (contains? strings "Widen net band to 65%"))
    (is (= [[:actions/set-portfolio-optimizer-constraint :net-min 3.84]
            [:actions/set-portfolio-optimizer-constraint :net-max 3.84]]
           (fix-actions node :raise-net-target)))
    (is (= [[:actions/set-portfolio-optimizer-constraint :gross-min 1.45]]
           (fix-actions node :lower-gross-floor)))
    (is (= [[:actions/set-portfolio-optimizer-constraint :gross-min nil]]
           (fix-actions node :clear-gross-floor)))
    (is (= [[:actions/set-portfolio-optimizer-constraint :net-band-pct 0.65]]
           (fix-actions node :widen-net-band)))
    ;; Copy-only paths must not ship a button that does nothing.
    (is (nil? (support/node-by-role node
                                    "portfolio-optimizer-infeasible-fix-add-short-capacity")))
    ;; The domain's own message still renders, unchanged.
    (is (contains? strings (:message net-unreachable-violation)))))

(deftest net-unreachable-highlights-the-controls-that-fix-it-test
  (is (= #{:gross-min :gross-max :net-min :net-max :max-asset-weight}
         (panel/highlighted-control-keys net-unreachable-result)))
  (is (str/includes? (panel/remediation-rationale net-unreachable-result)
                     "Net exposure equals gross minus twice the short book"))
  (is (contains? (set (support/collect-strings (banner net-unreachable-result)))
                 "Net target out of reach")))

;; --- Long Only: every net-side remediation is a provable no-op ----------------

(def ^:private long-only-result
  (reachability-result {:long-only? true
                        ;; net-band-spec requires (not long-only?), so the band
                        ;; is dropped and widening it changes nothing.
                        :net-band-encodable? false
                        :net-bounds {:min 1.0 :max 1.0}
                        :reachable-net {:min 3.995 :max 4.005}
                        :reachable-net-display {:min 4.0 :max 4.0}}))

(deftest long-only-suppresses-the-net-controls-it-cannot-move-test
  (is (= [:lower-gross-floor :clear-gross-floor :long-only-forces-net]
         (suggestion-ids long-only-result)))
  (is (nil? (suggestion-by-id long-only-result :raise-net-target)))
  (is (nil? (suggestion-by-id long-only-result :widen-net-band)))
  (is (nil? (suggestion-by-id long-only-result :add-short-capacity))))

(deftest long-only-replaces-the-flip-to-short-copy-with-something-true-test
  (let [copy (copy-of long-only-result :long-only-forces-net)
        strings (support/collect-strings (banner long-only-result))]
    (is (= (str "Long Only forbids short exposure, so net always equals gross here — a "
                "3.99x gross floor is a 3.99x net. Turn Long Only off to allow the shorts "
                "that pull net down, or lower Gross Exposure Min.")
           copy))
    (is (not-any? #(str/includes? % "Flip more assets to the short side") strings))
    (is (not-any? #(str/includes? % "carry no short capacity") strings))
    (is (str/includes? (panel/remediation-rationale long-only-result)
                       "net exposure always equals gross here"))
    ;; The toggle that fixes it is highlighted; the inert net bounds are not.
    (is (= #{:gross-min :gross-max :max-asset-weight :long-only?}
           (panel/highlighted-control-keys long-only-result)))
    (is (str/includes? (str/join " " strings) "Long Only"))))

;; --- :minimum-risk-without-exposure-floor ------------------------------------

(deftest minimum-risk-leads-with-the-suggested-gross-floor-test
  (let [suggestion (suggestion-by-id minimum-risk-result :set-gross-floor)]
    (is (= [:set-gross-floor :pin-net-target] (suggestion-ids minimum-risk-result)))
    (is (= "Set Gross Exposure Min to 4.00x" (:label suggestion)))
    (is (= [[:actions/set-portfolio-optimizer-constraint :gross-min 4.0]]
           (:actions suggestion)))
    (is (str/includes? (:copy suggestion)
                       "A floor at the 4.00x your current book already carries"))
    (is (nil? (:actions (suggestion-by-id minimum-risk-result :pin-net-target))))))

(deftest minimum-risk-does-not-claim-a-clamped-floor-is-the-current-book-test
  ;; The domain clamps the suggestion to the gross ceiling, so a 1.30x book under
  ;; a 1.00x cap suggests 1.00x, which is not "your current book".
  (let [suggestion (suggestion-by-id (cash-collapse-result {:current-gross 1.3
                                                            :max-feasible-gross-floor 1.0
                                                            :suggested-gross-floor 1.0})
                                     :set-gross-floor)]
    (is (= "Set Gross Exposure Min to 1.00x" (:label suggestion)))
    (is (= [[:actions/set-portfolio-optimizer-constraint :gross-min 1.0]]
           (:actions suggestion)))
    (is (str/includes? (:copy suggestion) "A floor at 1.00x, the most this setup allows"))
    (is (not (str/includes? (:copy suggestion) "current book")))))

(deftest minimum-risk-explains-the-all-cash-optimum-test
  (let [node (banner minimum-risk-result)
        strings (set (support/collect-strings node))]
    (is (= (str "Minimum risk with no exposure floor has an all-cash optimum, so the answer "
                "would be zero in every asset.")
           (panel/remediation-rationale minimum-risk-result)))
    (is (some? (support/node-by-role node "portfolio-optimizer-infeasible-rationale")))
    (is (= [[:actions/set-portfolio-optimizer-constraint :gross-min 4.0]]
           (fix-actions node :set-gross-floor)))
    ;; The raw reason keyword must not leak into the headline.
    (is (contains? strings "Reason: Objective collapses to cash"))
    (is (contains? strings "No exposure floor"))
    (is (= #{:gross-min :net-min :net-max}
           (panel/highlighted-control-keys minimum-risk-result)))))

(deftest minimum-risk-without-a-current-book-names-the-ceiling-test
  (let [result (cash-collapse-result {:current-gross 0
                                      :max-feasible-gross-floor 2.5
                                      :suggested-gross-floor nil})
        node (banner result)
        strings (support/collect-strings node)]
    (is (= [:set-any-gross-floor :pin-net-target] (suggestion-ids result)))
    (is (nil? (:actions (suggestion-by-id result :set-any-gross-floor))))
    ;; The quoted ceiling is what this setup can HOLD, not the raw Gross
    ;; Exposure cap: the net window can put the real limit far below it.
    (is (str/includes? (copy-of result :set-any-gross-floor)
                       "Anywhere up to the 2.50x this setup can hold works."))
    (is (nil? (support/node-by-role node
                                    "portfolio-optimizer-infeasible-fix-set-gross-floor")))
    (is (not-any? #(or (str/includes? % "N/A") (str/includes? % "NaN")) strings))))

;; --- Headline reasons ---------------------------------------------------------

(defn- reason-line
  [reason]
  (first (filter #(str/starts-with? % "Reason: ")
                 (support/collect-strings (banner {:status :infeasible :reason reason})))))

(deftest every-engine-reason-renders-a-friendly-headline-test
  ;; :constraint-presolve is what build-solver-plan actually emits for the joint
  ;; reachability code, so this was the first line the reported user read.
  (is (= "Reason: Constraints cannot all be met" (reason-line :constraint-presolve)))
  (is (= "Reason: No portfolio satisfies every constraint"
         (reason-line :constraints-unsatisfiable)))
  (is (= "Reason: Solver answer broke a constraint"
         (reason-line :solver-returned-invalid-solution)))
  (is (= "Reason: Solver found no solution" (reason-line :solver-returned-no-solution)))
  (is (= "Reason: Objective collapses to cash" (reason-line :objective-collapses-to-cash)))
  ;; The solver-quality arm target_selection split out of :constraints-unsatisfiable.
  ;; Its label must not assert infeasibility: the solver returned a real point
  ;; that failed the row check, which says nothing about the request.
  (is (= "Reason: Solver answer outside its own bounds"
         (reason-line :solver-solution-failed-boundary-check)))
  ;; No engine reason may fall back to opt-format/keyword-label's (name value).
  (is (not-any? #(str/includes? (reason-line %) (name %))
                [:constraint-presolve :constraints-unsatisfiable
                 :solver-returned-no-solution :solver-returned-invalid-solution
                 :solver-solution-failed-boundary-check
                 :objective-collapses-to-cash :target-return-above-feasible-maximum
                 :equal-risk-presolve :inverse-volatility-presolve
                 :invalid-return-model :unknown-objective])))

(deftest solver-boundary-row-violation-chips-a-readable-label-test
  ;; The reason target_selection now pairs with this message: a point outside its
  ;; own bounds is a solver-quality failure, NOT the infeasibility certificate
  ;; :constraints-unsatisfiable carries.
  (let [result {:status :infeasible
                :reason :solver-solution-failed-boundary-check
                :message "The solver returned a point that does not satisfy the constraints it was given."
                :details {:violations [{:code :solver-boundary-row-violation
                                        :constraint-code :turnover
                                        :lower 0
                                        :upper 2.0
                                        :value 31.31
                                        :message "turnover left its bounds by 2.93e+01."}]}}
        strings (set (support/collect-strings (banner result)))]
    (is (contains? strings "Constraint row out of bounds"))
    (is (not (contains? strings "solver-boundary-row-violation")))
    ;; The solver's own :constraint-code still steers the highlight.
    (is (= #{:max-turnover} (panel/highlighted-control-keys result)))))

;; --- Worker boundary ----------------------------------------------------------

(def ^:private engine-request
  {:scenario-id "scenario-1"
   :universe [{:instrument-id "spot:@142" :market-type :spot :base "PURR"}
              {:instrument-id "perp:SPCX" :market-type :perp :coin "SPCX"}]})

(defn- worker-round-trip
  "What the app does with an engine payload: worker.cljs serialises through
  clj->js (every keyword VALUE becomes a string), worker_client.cljs keywordizes
  KEYS only, then the boundary codec re-keywordizes the enum-value-keys."
  [value]
  (wire/normalize-worker-boundary
   (js->clj (wire/clj->worker-boundary value) :keywordize-keys true)))

(def ^:private served-result
  (engine-payload/infeasible-payload
   engine-request
   {}
   {}
   (reachability-result
    {:binding-capacity [{:instrument-id "spot:@142" :capacity 0.05}
                        {:instrument-id "perp:SPCX" :capacity 0.03}]})))

(deftest suggestions-survive-the-optimizer-worker-boundary-test
  ;; encode-constraints runs inside the worker, so the panel sees THIS. :direction
  ;; selects the whole list, so a string value silently empties the block.
  (let [delivered (worker-round-trip served-result)
        violation (first (get-in delivered [:details :violations]))]
    (is (= :net-above-band (:direction violation)))
    (is (= :net-unreachable-given-sides (:code violation)))
    (is (= :gross-exposure (:constraint-code violation)))
    (is (= :gross-floor-vs-short-capacity (:driver violation)))
    (is (= :constraint-presolve (:reason delivered)))
    (is (= [:raise-net-target
            :lower-gross-floor
            :clear-gross-floor
            :add-short-capacity
            :widen-net-band]
           (suggestion-ids delivered)))
    (is (some? (support/node-by-role (banner delivered)
                                     "portfolio-optimizer-infeasible-suggestions")))
    ;; The engine resolves the symbols; the id tails would read "@142"/"SPCX".
    (is (str/includes? (copy-of delivered :add-short-capacity)
                       "offers 0.08x (PURR 0.05x, SPCX 0.03x)"))))

(deftest without-the-boundary-codec-the-whole-block-vanishes-test
  ;; Pins the mechanism the fix closes: keys-only keywordization leaves
  ;; :direction a string, so the direction test is false for every value.
  (let [raw (js->clj (wire/clj->worker-boundary served-result) :keywordize-keys true)]
    (is (= "net-above-band" (get-in raw [:details :violations 0 :direction])))
    (is (= [] (panel/remediation-suggestions raw)))))

;; --- Codes with no remediation model ------------------------------------------

(deftest unmodelled-codes-render-no-remediation-block-test
  (let [result {:status :infeasible
                :reason :constraint-presolve
                :details {:violations [{:code :sum-upper-below-target
                                        :sum-upper 0.8
                                        :target-net 1}]}}
        node (banner result)]
    (is (= [] (panel/remediation-suggestions result)))
    (is (nil? (panel/remediation-rationale result)))
    (is (nil? (support/node-by-role node "portfolio-optimizer-infeasible-suggestions")))
    ;; The pre-existing chip copy is untouched for codes with no friendly label.
    (is (contains? (set (support/collect-strings node)) "sum-upper-below-target"))))
