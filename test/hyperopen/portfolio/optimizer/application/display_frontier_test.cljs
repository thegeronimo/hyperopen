(ns hyperopen.portfolio.optimizer.application.display-frontier-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.display-frontier :as display-frontier]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]))

;; The reference ("unconstrained") display frontier must reflect the user's
;; actual opportunity set: the per-asset long/short directions they chose, the
;; gross/leverage budget, the turnover budget, and the concentration caps. It
;; relaxes ONLY the held-position locks -- the constraint that freezes specific
;; current holdings at their existing weight. Forcing long-only or dropping the
;; per-asset caps (which silently fall back to a 100% cap) narrows the feasible
;; set and makes a long/short, levered recommendation plot ABOVE the drawn
;; frontier -- the bug this test guards against.

(deftest reference-frontier-constraints-preserves-opportunity-set-test
  (let [constraints {:long-only? false
                     :max-asset-weight 9.0
                     :gross-leverage 10.0
                     :net-exposure {:min -0.2 :max 0.2}
                     :per-asset-overrides {"perp:ETH" {:max-short-weight 5.0}}
                     :per-perp-leverage-caps {"perp:BTC" {:max-long-weight 3.0}}
                     :max-turnover 0.5
                     :held-position-locks ["perp:BTC"]
                     :rebalance-tolerance 0.001}
        reference (display-frontier/reference-frontier-constraints constraints)]
    ;; Opportunity-set and budget constraints survive unchanged.
    (is (false? (:long-only? reference))
        "Reference frontier must not force long-only.")
    (is (= 9.0 (:max-asset-weight reference)))
    (is (= 10.0 (:gross-leverage reference)))
    (is (= {:min -0.2 :max 0.2} (:net-exposure reference)))
    (is (= {"perp:ETH" {:max-short-weight 5.0}} (:per-asset-overrides reference)))
    (is (= {"perp:BTC" {:max-long-weight 3.0}} (:per-perp-leverage-caps reference)))
    (is (= 0.5 (:max-turnover reference))
        "Turnover budget is kept so the reference frontier stays bounded.")
    (is (= 0.001 (:rebalance-tolerance reference)))
    ;; Only held-position locks are relaxed.
    (is (not (contains? reference :held-position-locks)))))

(deftest reference-frontier-constraints-handles-nil-test
  (is (= {} (display-frontier/reference-frontier-constraints nil))))

(def ^:private shortable-universe
  [{:instrument-id "perp:BTC" :market-type :perp :shortable? true}
   {:instrument-id "perp:ETH" :market-type :perp :shortable? true}])

(deftest reference-frontier-encodes-short-bounds-for-shortable-universe-test
  (let [constraints {:long-only? false
                     :max-asset-weight 5.0
                     :gross-leverage 8.0
                     :max-turnover 0.3
                     :held-position-locks ["perp:BTC"]}
        reference (display-frontier/reference-frontier-constraints constraints)
        encoded (constraints/encode-constraints
                 {:universe shortable-universe
                  :current-weights {}
                  :constraints reference})]
    (is (= :ok (:status encoded)))
    (is (false? (:long-only? encoded))
        "Encoded reference frontier stays long/short.")
    (is (every? neg? (:lower-bounds encoded))
        "Shortable assets keep negative lower bounds, so shorts are preserved.")
    (is (= [5.0 5.0] (:upper-bounds encoded))
        "Per-asset concentration caps are retained, not stripped to the 100% default.")
    (is (= {:max 8.0} (:gross-exposure encoded))
        "The gross/leverage budget is retained so the frontier stays bounded.")
    (is (= 0.3 (:max-turnover encoded))
        "The turnover budget is retained so the frontier stays bounded.")
    (is (empty? (:locked-weights encoded))
        "Held-position locks are relaxed.")))

;; The size tier is a CEILING on how many points the display sweep may solve,
;; not a default that a caller can opt out of. It used to be skipped entirely
;; whenever :frontier-points was already set -- which is exactly what the
;; refinement control does -- so a 100-asset universe at :maximum refinement
;; planned 80 display solves against a solver whose heap dies after 36.

(deftest display-frontier-tier-caps-a-requested-point-count-test
  (let [cap-at (fn [n requested]
                 (:frontier-points
                  (display-frontier/display-frontier-objective
                   (cond-> {:kind :max-sharpe}
                     requested (assoc :frontier-points requested))
                   n)))]
    ;; The regression: a refinement-set budget used to pass through untouched.
    (is (= 16 (cap-at 100 80)) "over 50 assets caps at 16 even when 80 is asked for")
    (is (= 24 (cap-at 40 80)) "31-50 assets caps at 24")
    ;; A request below the tier is honoured -- this is a ceiling, not a clamp.
    (is (= 8 (cap-at 100 8)) "a smaller request is left alone")
    (is (= 16 (cap-at 100 16)) "a request equal to the tier is unchanged")
    ;; Small universes have no tier at all, so the request passes through.
    (is (= 80 (cap-at 20 80)) "30 or fewer assets are untiered")
    ;; With no request, the tier still supplies the default it always did.
    (is (= 16 (cap-at 100 nil)))
    (is (= 24 (cap-at 40 nil)))
    (is (nil? (cap-at 20 nil)))))

