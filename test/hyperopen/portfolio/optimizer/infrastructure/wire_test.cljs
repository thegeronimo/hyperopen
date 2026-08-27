(ns hyperopen.portfolio.optimizer.infrastructure.wire-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.infrastructure.wire :as wire]))

(deftest normalize-worker-boundary-stringifies-black-litterman-view-weights-test
  (let [decoded-id (keyword "perp:BTC")
        normalized (wire/normalize-worker-boundary
                    {:return-model {:kind "black-litterman"
                                    :views [{:id "view-1"
                                             :kind "absolute"
                                             :instrument-id "perp:BTC"
                                             :return 0.2
                                             :confidence 0.75
                                             :weights {decoded-id 1}}]}})]
    (is (= {"perp:BTC" 1}
           (get-in normalized [:return-model :views 0 :weights])))
    (is (= :black-litterman
           (get-in normalized [:return-model :kind])))))

(deftest normalize-worker-boundary-stringifies-known-instrument-keyed-maps-test
  (let [perp-id (keyword "perp:BTC")
        spot-id (keyword "spot:PURR/USDC")
        normalized (wire/normalize-worker-boundary
                    {:current-portfolio {:by-instrument {spot-id {:weight 0.2}}}
                     :history {:return-series-by-instrument {perp-id [0.01 0.02]}
                               :funding-by-instrument {perp-id {:source "market-funding-history"}}}
                     :payload {:status "solved"
                               :return-decomposition-by-instrument
                               {perp-id {:return-component 0.12
                                         :funding-component 0.04
                                         :funding-source "market-funding-history"}
                                spot-id {:return-component 0.08
                                         :funding-component 0
                                         :funding-source "missing"}}
                               :expected-returns-by-instrument {perp-id 0.12
                                                                spot-id 0.08}
                               :current-weights-by-instrument {spot-id 0.2}
                               :target-weights-by-instrument {perp-id 0.35}
                               :diagnostics {:weight-sensitivity-by-instrument
                                             {perp-id {:max-delta 0.01}}}}})]
    (is (= {"spot:PURR/USDC" {:weight 0.2}}
           (get-in normalized [:current-portfolio :by-instrument])))
    (is (= {"perp:BTC" [0.01 0.02]}
           (get-in normalized [:history :return-series-by-instrument])))
    (is (= :market-funding-history
           (get-in normalized [:history :funding-by-instrument "perp:BTC" :source])))
    (is (= :solved (get-in normalized [:payload :status])))
    (is (= #{ "perp:BTC" "spot:PURR/USDC" }
           (set (keys (get-in normalized [:payload :return-decomposition-by-instrument])))))
    (is (= :market-funding-history
           (get-in normalized
                   [:payload :return-decomposition-by-instrument
                    "perp:BTC"
                    :funding-source])))
    (is (= {"perp:BTC" 0.12
            "spot:PURR/USDC" 0.08}
           (get-in normalized [:payload :expected-returns-by-instrument])))
    (is (= {"spot:PURR/USDC" 0.2}
           (get-in normalized [:payload :current-weights-by-instrument])))
    (is (= {"perp:BTC" 0.35}
           (get-in normalized [:payload :target-weights-by-instrument])))
    (is (= {"perp:BTC" {:max-delta 0.01}}
           (get-in normalized
                   [:payload :diagnostics :weight-sensitivity-by-instrument])))))

;; Presolve and solver diagnostics are produced INSIDE the optimizer worker, so
;; every keyword value in them is stringified by clj->worker-boundary on the way
;; out and only comes back as a keyword if its key is in enum-value-keys. The
;; infeasible banner branches on these values -- :direction picks the whole
;; remediation list, :constraint-code picks the highlighted control -- so a
;; missing key is a silent, test-invisible loss of the whole "What you can do"
;; block in production.
(def ^:private infeasible-payload
  {:status :infeasible
   :reason :constraint-presolve
   :details {:violations
             [{:code :net-unreachable-given-sides
               :constraint-code :gross-exposure
               :direction :net-above-band
               :binding-side :short
               :driver :gross-floor-vs-short-capacity
               :long-only? false
               :net-band-encodable? true
               :gross-floor-encodable? true
               :reachable-net {:min 3.835 :max 4.005}
               :reachable-net-display {:min 3.84 :max 4.0}
               :binding-capacity [{:instrument-id "spot:@142"
                                   :display-symbol "PURR"
                                   :capacity 0.05}]
               :message "A 4.00x gross floor with only 0.08x of short capacity."}
              {:code :solver-boundary-row-violation
               :constraint-code :turnover
               :message "turnover left its bounds by 2.93e+01."}]}})

(deftest normalize-worker-boundary-keywordizes-diagnostic-enum-values-test
  (let [delivered (wire/normalize-worker-boundary
                   (js->clj (wire/clj->worker-boundary infeasible-payload)
                            :keywordize-keys true))
        [reachability boundary-row] (get-in delivered [:details :violations])]
    (is (= :infeasible (:status delivered)))
    (is (= :constraint-presolve (:reason delivered)))
    (is (= :net-unreachable-given-sides (:code reachability)))
    (is (= :gross-exposure (:constraint-code reachability)))
    (is (= :net-above-band (:direction reachability)))
    (is (= :short (:binding-side reachability)))
    (is (= :gross-floor-vs-short-capacity (:driver reachability)))
    (is (= :solver-boundary-row-violation (:code boundary-row)))
    (is (= :turnover (:constraint-code boundary-row)))
    ;; Booleans, numbers, nested intervals and instrument ids are untouched.
    (is (= false (:long-only? reachability)))
    (is (= {:min 3.84 :max 4.0} (:reachable-net-display reachability)))
    (is (= [{:instrument-id "spot:@142" :display-symbol "PURR" :capacity 0.05}]
           (:binding-capacity reachability)))))
