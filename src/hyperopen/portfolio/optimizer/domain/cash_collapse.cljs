(ns hyperopen.portfolio.optimizer.domain.cash-collapse
  "The degenerate-minimum-risk gate. Minimum risk minimises w'Ew with NO budget
  row - the exposure policy replaced the textbook sum(w) = 1 constraint - so when
  every remaining control is a CEILING, w = 0 is feasible and GLOBALLY OPTIMAL at
  exactly zero variance. The engine returned that all-cash book and called it
  solved.

  Split out of `domain.objectives` (whose size exception names the move) with the
  remediation it publishes, because the remediation is the hard part: the panel's
  flagship one-click fix writes the gross floor this namespace suggests, and a
  floor clamped only to the gross cap and the box capacity trips the JOINT
  net/gross reachability presolve on the very next run - whose own fix clears the
  floor, landing the trader back here. The suggested floor is therefore solved
  against the encoded net rows in `domain.exposure-feasibility`."
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.domain.encoded-rows :as rows]
            [hyperopen.portfolio.optimizer.domain.exposure-feasibility
             :as feasibility]))

(def ^:private finite-number? coercion/finite-number?)

(def ^:private exposure-epsilon
  "Float slack for the at-zero exposure tests below. Several encodings land
  exactly on a bound (a 0.0 net pin, a 0 gross floor), so the comparisons must
  not treat rounding dust as a real lower bound on activity."
  1.0e-9)

(defn- positive-beyond-epsilon?
  [value]
  (and (finite-number? value) (> value exposure-epsilon)))

(defn- negative-beyond-epsilon?
  [value]
  (and (finite-number? value) (< value (- exposure-epsilon))))

(defn- non-zero?
  [value]
  (or (positive-beyond-epsilon? value)
      (negative-beyond-epsilon? value)))


(defn- zero-weights-forbidden?
  "True when the ENCODED rows already rule out the all-cash vector w = 0.
  Every row family is evaluated AT w = 0, where net(w), realized gross(w) and
  mu'w are all 0, so cash is excluded exactly when one of these holds: a
  positive gross floor; a non-zero net equality; a positive net floor or a
  negative net ceiling (identical for the plain net rows and for the coupled
  band rows, since q·gross is 0 at cash); a box bound that pushes an asset off
  zero (held-position locks encode as lower = upper = held weight); a turnover
  cap too tight to liquidate the current book (the split encoding is
  sum|w − w0| <= 2·max-turnover); or a positive return floor.

  Deliberately biased toward TRUE: a false positive here only lets a run
  proceed, while a false negative would block a legitimate request, so an
  unreadable current book under an active turnover cap counts as forbidding."
  [{:keys [objective expected-returns encoded-constraints]}]
  (let [{:keys [lower-bounds upper-bounds locked-weights
                max-turnover current-weights]} encoded-constraints
        net-bounds (rows/net-row-bounds encoded-constraints)
        return-floor (rows/target-return-inequality expected-returns objective)]
    (boolean
     (or (non-zero? (rows/net-equality-target encoded-constraints))
         (positive-beyond-epsilon? (:min net-bounds))
         (negative-beyond-epsilon? (:max net-bounds))
         (positive-beyond-epsilon? (:lower (rows/gross-floor-inequality encoded-constraints)))
         (some positive-beyond-epsilon? lower-bounds)
         (some negative-beyond-epsilon? upper-bounds)
         (some #(non-zero? (:weight %)) locked-weights)
         (when (finite-number? max-turnover)
           (or (not (and (sequential? current-weights)
                         (every? finite-number? current-weights)))
               (> (reduce + 0 (map js/Math.abs current-weights))
                  (+ (* 2 max-turnover) exposure-epsilon))))
         (positive-beyond-epsilon? (:lower return-floor))))))

(defn- current-gross
  [encoded-constraints]
  (let [weights (:current-weights encoded-constraints)]
    (when (and (sequential? weights)
               (every? finite-number? weights))
      (reduce + 0 (map js/Math.abs weights)))))

(defn- encoded-net-window
  "The net rows as `exposure-feasibility` reads them, mirroring
  `constraints/finite-net-limits`: a finite :net-target pins both ends, otherwise
  the stored net bounds stand, and :pct is the coupled band's."
  [encoded-constraints]
  (let [target (:net-target encoded-constraints)
        net-exposure (:net-exposure encoded-constraints)
        pinned? (finite-number? target)]
    {:min (if pinned? target (:min net-exposure))
     :max (if pinned? target (:max net-exposure))
     :pct (get-in encoded-constraints [:net-band-spec :pct])}))

(defn- gross-floor-ceiling
  "The largest gross floor this setup can carry, from the EXACT reachable gross
  window rather than the cap alone. 0 when no positive floor is reachable, nil
  when nothing here constrains one.

  Clamping to the cap and the box capacity alone is what made the flagship
  one-click fix write a floor that immediately tripped the joint net/gross
  reachability presolve: net = gross - 2*short, so a book whose short side is
  thin cannot hold a floor the net window would otherwise allow, and the two
  banners' buttons then cycled between each other with no way out."
  [encoded-constraints]
  (let [gross-max (get-in encoded-constraints [:gross-exposure :max])
        capacity (:gross-capacity encoded-constraints)]
    (if-let [geometry (feasibility/exposure-geometry
                       {:lower-bounds (:lower-bounds encoded-constraints)
                        :upper-bounds (:upper-bounds encoded-constraints)
                        :gross-max gross-max
                        :gross-capacity capacity})]
      (or (feasibility/max-feasible-gross-floor
           geometry
           (encoded-net-window encoded-constraints))
          0)
      ;; Unusable bounds: the joint presolve needs the same box and is silent
      ;; too, so only the declared cap and capacity can bound a floor here.
      (when-let [declared (seq (filterv finite-number? [gross-max capacity]))]
        (apply min declared)))))

(defn- suggested-gross-floor
  "A floor the panel can write in ONE click: the current book's gross, clamped to
  what this setup can actually hold. nil when nothing positive survives.

  Unclamped it proposed a floor ABOVE :gross-leverage whenever the cap sat under
  the book (Conservative caps gross at 1.0x; a 1.30x book suggested 1.3), and
  the next run then failed :gross-floor-above-gross-max - a code with no
  remediation model, so the flagship one-click fix landed the trader on a WORSE
  banner than the one it was offered from."
  [current-gross ceiling]
  (when (finite-number? current-gross)
    (let [value (if (finite-number? ceiling)
                  (min current-gross ceiling)
                  current-gross)]
      (when (positive-beyond-epsilon? value) value))))

(defn- fmt-x
  [value]
  (str (.toFixed value 2) "x"))

(defn- gross-floor-hint
  "The parenthetical after 'Set Gross Exposure Min'. Says 'your current book' only
  while the suggestion IS that book; once a ceiling or the box capacity has
  clamped it, quoting the book would misdescribe the number on the button."
  [current-gross suggested]
  (when suggested
    (if (and (finite-number? current-gross)
             (>= suggested (- current-gross exposure-epsilon)))
      (str " (your current book is " (fmt-x current-gross) " gross)")
      (str " (up to " (fmt-x suggested) " here)"))))

(defn- collapse-to-cash-message
  "Names the control that fixes it - but only when that control CAN fix it. A
  net window with no room for any positive gross floor (net pinned at 0 against
  a book with no short capacity, say) would otherwise be told to set a floor
  whose every value trips the joint reachability presolve instead."
  [{:keys [current-gross suggested-gross-floor max-feasible-gross-floor]}]
  (str "Minimum risk has an all-cash optimum here: every exposure control in "
       "this setup is a ceiling, so nothing requires the portfolio to hold a "
       "position and the lowest-risk answer is to invest nothing. "
       (if (or (nil? max-feasible-gross-floor)
               (positive-beyond-epsilon? max-feasible-gross-floor))
         (str "Set Gross Exposure Min"
              (gross-floor-hint current-gross suggested-gross-floor)
              ", or a non-zero Net Exposure Min, so the run has to stay invested.")
         (str "The net exposure window leaves no room for a gross floor here, "
              "so set a non-zero Net Exposure Min instead, so the run has to "
              "stay invested."))))

(defn collapse-to-cash-plan
  "Minimum risk minimises w'Ew with NO budget row — the exposure policy replaced
  the textbook sum(w) = 1 constraint. When nothing in the encoding forbids
  w = 0, cash is both feasible and GLOBALLY OPTIMAL at exactly zero variance,
  so the engine returns an all-cash book and calls it solved. Reject the
  request up front, naming the control that fixes it, instead of shipping that
  answer.

  :minimum-variance only. Equal Risk and Inverse Volatility pin gross to a
  positive exposure TARGET, and equal-risk-presolve already rejects a
  non-positive one (:equal-risk-gross-target-not-positive), so cash is
  infeasible for them before this gate would ever apply."
  [{:keys [objective encoded-constraints] :as opts}]
  (when (and (= :minimum-variance (:kind objective))
             (not (zero-weights-forbidden? opts)))
    (let [gross (current-gross encoded-constraints)
          ceiling (gross-floor-ceiling encoded-constraints)
          suggested (suggested-gross-floor gross ceiling)
          payload {:code :minimum-risk-without-exposure-floor
                   :objective-kind :minimum-variance
                   :current-gross gross
                   ;; Both floors the panel may write or quote, already clamped
                   ;; to what the net rows leave reachable. Nothing else is
                   ;; published: the gate only fires when no positive floor is
                   ;; encoded, so the encoded floor and the net bounds it was
                   ;; measured against say nothing a consumer reads.
                   :max-feasible-gross-floor ceiling
                   :suggested-gross-floor suggested}]
      {:status :infeasible
       :reason :objective-collapses-to-cash
       :details {:violations
                 [(assoc payload :message (collapse-to-cash-message payload))]}})))
