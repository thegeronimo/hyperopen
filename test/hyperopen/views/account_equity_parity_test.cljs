(ns hyperopen.views.account-equity-parity-test
  "Both account panels, every fixture, measured against the venue's own
   arithmetic.

   This is the regression harness for the account-equity surface. It exists
   because one function -- `derive-account-equity-metrics` -- computes every
   number on both panels, so a change aimed at the classic panel can silently
   un-fix the unified one. Rather than pinning hand-written constants, which
   encode whatever the code happened to do on the day they were written, each
   fixture is scored against `account-equity-venue-oracle`: an independent,
   differently-shaped transcription of the venue's formulas.

   Where we knowingly print something other than the venue, the fixture declares
   the divergence and this test asserts *both* sides of it. A future change that
   accidentally \"fixes\" a deliberate divergence fails here rather than shipping."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.account-equity-fixtures :as fixtures]
            [hyperopen.views.account-equity-venue-oracle :as oracle]
            [hyperopen.views.account-equity-view :as view]
            [hyperopen.views.account-info.derived-cache :as derived-cache]))

(defn- metrics-for
  [state]
  (derived-cache/reset-derived-cache!)
  (view/reset-account-equity-metrics-cache!)
  (let [result (view/account-equity-metrics state)]
    (derived-cache/reset-derived-cache!)
    (view/reset-account-equity-metrics-cache!)
    result))

(defn- close?
  "Agreement to nine significant figures, with \"neither side has a value\"
   counting as agreement -- both panels render nil as \"--\", so two nils are
   the same cell on screen."
  [expected actual]
  (if (nil? expected)
    (nil? actual)
    (and (number? expected)
         (number? actual)
         (<= (js/Math.abs (- expected actual))
             (* 1e-9 (max 1 (js/Math.abs expected)))))))

(defn- describe
  [value]
  (if (nil? value) "nil" (str value)))

(defn- check-row!
  "Assert one row. A declared divergence is checked on both sides instead of
   being skipped, so neither half can drift unnoticed."
  [{:keys [label divergences]} row venue ours]
  (if-let [divergence (get divergences row)]
    (testing (str label " / " (name row) " (deliberate divergence: "
                  (:reason divergence) ")")
      (is (or (= (:venue divergence) venue)
              (close? (:venue divergence) venue))
          (str "The venue's value moved. Declared " (describe (:venue divergence))
               ", oracle now says " (describe venue)
               ". Re-derive the divergence before editing it away."))
      (is (or (= (:ours divergence) ours)
              (close? (:ours divergence) ours))
          (str "Our value moved. Declared " (describe (:ours divergence))
               ", we now produce " (describe ours)
               ". If this is now parity, delete the divergence from the fixture.")))
    (testing (str label " / " (name row))
      (is (close? venue ours)
          (str "Venue " (describe venue) " vs ours " (describe ours))))))

(defn- classic-expectations
  [{:keys [oracle] :as _fixture}]
  (let [{:keys [per-dex spot-balances price-by-token]} oracle
        spot-usd (reduce + 0 (map #(* (oracle/->num (:total %))
                                      (or (get price-by-token (:coin %)) 0))
                                  spot-balances))]
    (oracle/classic-rows (oracle/aggregate per-dex) spot-usd)))

(defn- unified-expectations
  [{:keys [oracle] :as _fixture}]
  (let [{:keys [per-dex spot-balances collateral-tokens price-by-token]} oracle]
    (oracle/unified-rows (oracle/aggregate per-dex)
                         per-dex
                         spot-balances
                         collateral-tokens
                         price-by-token)))

(def ^:private classic-rows
  "Row key -> the metrics key the classic panel renders it from."
  {:account-value :account-value-display
   :spot :spot-equity
   :perps :perps-value
   :balance :base-balance
   :unrealized-pnl :unrealized-pnl
   :cross-margin-ratio :cross-margin-ratio
   :maintenance-margin :maintenance-margin
   :cross-account-leverage :cross-account-leverage})

(def ^:private unified-rows
  {:portfolio-value :account-value-display
   :unrealized-pnl :unrealized-pnl
   :maintenance-margin :maintenance-margin
   :unified-account-leverage :unified-account-leverage
   :unified-account-ratio :unified-account-ratio
   :isolated-notional :isolated-notional})

(defn- venue-value
  [row expected]
  (if (= :isolated-notional row)
    ;; Not a venue row at all: the venue has no disclosure line, and ours is
    ;; omitted rather than reading "Excludes $0.00 isolated". The oracle's zero
    ;; therefore means "nothing to disclose", which is our nil.
    (let [value (:isolated-notional expected)]
      (when (and (number? value) (pos? value)) value))
    (get expected row)))

(deftest classic-panel-matches-the-venue-test
  (doseq [fixture (fixtures/classic)]
    (let [expected (classic-expectations fixture)
          metrics (metrics-for (:state fixture))]
      (doseq [[row metrics-key] classic-rows]
        (check-row! fixture row (venue-value row expected) (get metrics metrics-key))))))

(deftest unified-panel-matches-the-venue-test
  (doseq [fixture (fixtures/unified)]
    (let [expected (unified-expectations fixture)
          metrics (metrics-for (:state fixture))]
      (doseq [[row metrics-key] unified-rows]
        (check-row! fixture row (venue-value row expected) (get metrics metrics-key))))))

(deftest classic-panel-account-value-reconciles-with-its-own-rows-test
  ;; The oracle cannot catch this one: both halves can come from the venue's
  ;; formula and the panel still display them inconsistently. Account Value is
  ;; our addition to the venue's layout, so it carries the obligation to equal
  ;; the two rows above it, at the precision a reader can see.
  (doseq [{:keys [label state]} (fixtures/classic)]
    (let [{:keys [spot-equity perps-value account-value-display]} (metrics-for state)
          parts (keep identity [spot-equity perps-value])
          expected (when (seq parts) (reduce + parts))]
      (testing (str label " / Account Value = Spot + Perps")
        (is (= (view/display-currency expected)
               (view/display-currency account-value-display))
            (str "Spot " (describe spot-equity)
                 " + Perps " (describe perps-value)
                 " should render as Account Value "
                 (view/display-currency account-value-display)))))))

(deftest every-fixture-asserts-something-non-trivial-test
  ;; A parity assertion that compares nil to nil proves nothing. Each fixture
  ;; must either exercise at least one row with a real, non-zero number on both
  ;; sides, or be a fixture whose entire subject is the divergence -- the
  ;; no-data shapes, where every row is declared. Anything else is decorative.
  (doseq [{:keys [label mode divergences] :as fixture} fixtures/all]
    (let [expected (if (= :unified mode)
                     (unified-expectations fixture)
                     (classic-expectations fixture))
          rows (if (= :unified mode) unified-rows classic-rows)
          metrics (metrics-for (:state fixture))
          substantive (filter (fn [[row metrics-key]]
                                (let [venue (venue-value row expected)
                                      ours (get metrics metrics-key)]
                                  (and (number? venue)
                                       (number? ours)
                                       (not (zero? venue)))))
                              rows)
          fully-declared? (every? #(contains? divergences %) (keys rows))]
      (testing (str label " / carries a non-trivial expectation")
        (is (or (seq substantive) fully-declared?)))
      (doseq [[row {:keys [reason]}] divergences]
        (testing (str label " / " (name row) " divergence states its reason")
          (is (and (string? reason) (> (count reason) 40))))))))
