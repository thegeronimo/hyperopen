(ns hyperopen.portfolio.optimizer.application.view-model.exposure-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.view-model.exposure :as vm]))

(deftest snapshot->current-exposure-derives-ratios-test
  (is (= {:gross 1.8 :net 1.2}
         (vm/snapshot->current-exposure
          {:capital {:nav-usdc 1000 :gross-exposure-usdc 1800 :net-exposure-usdc 1200}})))
  (testing "missing or non-positive capital ⇒ nil"
    (is (nil? (vm/snapshot->current-exposure {:capital {:nav-usdc nil}})))
    (is (nil? (vm/snapshot->current-exposure {:capital {:nav-usdc 0
                                                        :gross-exposure-usdc 10
                                                        :net-exposure-usdc 5}})))))

(deftest exposure-preview-reports-on-policy-status-test
  (let [constraints {:gross-max 2.0 :net-min 1.0 :net-max 1.0}]
    (testing "current exposure inside the band is on policy"
      (let [p (vm/exposure-preview {:current-exposure {:gross 1.8 :net 1.0}
                                    :constraints constraints})]
        (is (true? (:on-policy? p)))
        (is (true? (:gross-ok? p)))
        (is (true? (:net-ok? p)))))
    (testing "gross above the ceiling is off policy on the gross axis"
      (let [p (vm/exposure-preview {:current-exposure {:gross 2.4 :net 1.0}
                                    :constraints constraints})]
        (is (false? (:on-policy? p)))
        (is (false? (:gross-ok? p)))
        (is (true? (:net-ok? p)))))
    (testing "no gross floor ⇒ only the ceiling bounds gross"
      (let [p (vm/exposure-preview {:current-exposure {:gross 0.2 :net 1.0}
                                    :constraints constraints})]
        (is (true? (:gross-ok? p)) "no :gross-min means any gross at/under the ceiling is ok")))
    (testing "no current exposure ⇒ nil preview"
      (is (nil? (vm/exposure-preview {:current-exposure nil :constraints constraints}))))))

(deftest exposure-map-model-assembles-display-shape-test
  (let [model (vm/exposure-map-model
               {:constraints {:gross-max 2.0 :net-min 1.0 :net-max 1.0 :max-asset-weight 0.5}
                :current-exposure {:gross 1.8 :net 1.0}
                :highlighted-controls #{:gross-max}})]
    (is (= :balanced (:active-preset model)) "default constraints are the Balanced preset")
    (is (not (contains? model :presets)))
    (is (true? (get-in model [:highlighted :gross])) "gross-max infeasible highlights the gross axis")
    (is (false? (get-in model [:highlighted :net])))
    (is (some? (:target-marker model)))
    (is (some? (:band-rect model)))
    (is (some? (:current-marker model)))
    (is (true? (get-in model [:preview :on-policy?])))
    (is (= 2.0 (get-in model [:echo :gross-max])))
    (is (false? (get-in model [:echo :gross-floored?])))
    (is (= 3.0 (get-in model [:axis :gross-max])) "small default policy uses the floor level")
    (is (= :long (:net-direction model)) "net target 1.0 reads as a long bias")
    (is (= 0 (get-in model [:zoom :level])))
    (is (nil? (get-in model [:zoom :zoom-in-level])) "already at the tightest level that fits")
    (is (= 1 (get-in model [:zoom :zoom-out-level])))))

(deftest exposure-map-model-axis-fits-high-gross-test
  (let [model (vm/exposure-map-model
               {:constraints {:gross-max 6.0 :net-min 1.0 :net-max 1.0 :max-asset-weight 0.5}
                :current-exposure {:gross 5.0 :net 1.0}
                :highlighted-controls #{}})]
    (is (= 10.0 (get-in model [:axis :gross-max]))
        "a 6x gross ceiling fits the 10x zoom level, past the 3x floor")
    (is (<= (get-in model [:target-marker :y]) 1.0))
    (is (>= (get-in model [:target-marker :y]) 0.0)
        "the handle stays inside the pad instead of clipping at the top")))

(deftest exposure-map-model-stored-zoom-widens-the-view-test
  (let [model (vm/exposure-map-model
               {:constraints {:gross-max 2.0 :net-min 1.0 :net-max 1.0 :max-asset-weight 0.5}
                :current-exposure nil
                :highlighted-controls #{}
                :zoom-level 2})]
    (is (= 10.0 (get-in model [:axis :gross-max])) "the trader's stored zoom widens the view")
    (is (= 5.0 (get-in model [:axis :net-extent])))
    (is (= 1 (get-in model [:zoom :zoom-in-level])) "zooming back in steps toward the fit level")
    (is (= 3 (get-in model [:zoom :zoom-out-level])))))

(deftest exposure-map-model-net-direction-reads-sign-test
  (letfn [(direction [net-min net-max]
            (:net-direction (vm/exposure-map-model
                             {:constraints {:gross-max 2.0 :net-min net-min :net-max net-max}
                              :highlighted-controls #{}})))]
    (is (= :long (direction 0.5 1.5)))
    (is (= :short (direction -1.5 -0.5)))
    (is (= :neutral (direction -0.5 0.5)))))

(deftest exposure-preview-net-band-uses-realized-gross-test
  (let [constraints {:gross-max 20.0 :net-min 0.0 :net-max 0.0 :net-band-pct 0.05}]
    (testing "the allowed net widens by pct · CURRENT gross"
      (is (true? (:net-ok? (vm/exposure-preview
                            {:current-exposure {:gross 15.0 :net 0.75}
                             :constraints constraints})))
          "0.75x net at 15x gross is exactly the 5% edge")
      (is (false? (:net-ok? (vm/exposure-preview
                             {:current-exposure {:gross 15.0 :net 0.9}
                              :constraints constraints}))))
      (is (false? (:net-ok? (vm/exposure-preview
                             {:current-exposure {:gross 2.0 :net 0.75}
                              :constraints constraints})))
          "the same 0.75x net at only 2x gross is outside 5%"))
    (testing "net/gross ratio is surfaced for the status line"
      (let [p (vm/exposure-preview {:current-exposure {:gross 15.0 :net 14.955}
                                    :constraints constraints})]
        (is (< (js/Math.abs (- 0.997 (:net-gross-ratio p))) 1e-9))
        (is (= 0.05 (:net-band-pct p)))
        (is (= 0.0 (:net-target p)))))
    (testing "zero gross reports no ratio (— in the UI), never a division"
      (is (nil? (:net-gross-ratio (vm/exposure-preview
                                   {:current-exposure {:gross 0.0 :net 0.0}
                                    :constraints constraints})))))))

(deftest exposure-map-model-carries-percentage-band-shape-test
  (let [model (vm/exposure-map-model
               {:constraints {:gross-max 15.35 :net-min 0.0 :net-max 0.0
                              :net-band-pct 0.05 :max-asset-weight 0.5}
                :highlighted-controls #{}})]
    (is (= 0.05 (:net-band-pct model)))
    (is (< (js/Math.abs (- 0.7675 (:net-band-abs-preview model))) 1e-9)
        "≈ ±0.77x at 15.35x gross — the UI preview at the gross target")
    (is (seq (get-in model [:band-wedge :points])))
    (is (seq (get-in model [:band-wedge-stripe :points])))
    (is (= 0.05 (get-in model [:echo :net-band-pct])))
    (testing "the absolute preview tracks the gross target"
      (is (< (js/Math.abs
              (- 0.1
                 (:net-band-abs-preview
                  (vm/exposure-map-model
                   {:constraints {:gross-max 2.0 :net-min 0.0 :net-max 0.0
                                  :net-band-pct 0.05}
                    :highlighted-controls #{}}))))
             1e-9)))))

(deftest exposure-map-model-equal-risk-makes-net-output-only-test
  (let [constraints {:gross-max 2.0
                     :net-min -0.75
                     :net-max 0.25
                     :net-band-pct 0.15
                     :max-asset-weight 0.5}
        equal-risk-model (vm/exposure-map-model
                          {:objective-kind :equal-risk
                           :constraints constraints
                           :highlighted-controls #{:net-min}})
        other-objective-model (vm/exposure-map-model
                               {:objective-kind :minimum-variance
                                :constraints constraints
                                :highlighted-controls #{:net-min}})]
    (testing "Equal Risk keeps gross editable and exposes net as solver output"
      (is (= :gross-only (:interaction-mode equal-risk-model)))
      (is (true? (:gross-editable? equal-risk-model)))
      (is (false? (:net-editable? equal-risk-model)))
      (is (true? (:net-output-only? equal-risk-model)))
      (is (= "Resulting net is determined by Equal Risk from covariance and selected sides."
             (:net-output-copy equal-risk-model)))
      (is (false? (get-in equal-risk-model [:highlighted :net]))
          "Stored net settings are inactive for Equal Risk and should not highlight the UI.")
      (is (= -0.75 (get-in equal-risk-model [:echo :net-min])))
      (is (= 0.25 (get-in equal-risk-model [:echo :net-max])))
      (is (= 0.15 (get-in equal-risk-model [:echo :net-band-pct])))
      (is (= :neutral (:net-direction equal-risk-model))
          "A disabled net target should not advertise the stored long/short bias as active."))
    (testing "other objectives retain the normal two-axis gross/net control"
      (is (= :gross-net (:interaction-mode other-objective-model)))
      (is (true? (:gross-editable? other-objective-model)))
      (is (true? (:net-editable? other-objective-model)))
      (is (false? (:net-output-only? other-objective-model)))
      (is (true? (get-in other-objective-model [:highlighted :net]))))))
