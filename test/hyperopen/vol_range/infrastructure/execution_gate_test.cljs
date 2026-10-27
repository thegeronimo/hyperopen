(ns hyperopen.vol-range.infrastructure.execution-gate-test
  "Placement needs the build flag AND arming in this browser; the cap falls
  back to its default for unreadable or non-positive overrides."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.config :as config]
            [hyperopen.platform :as platform]
            [hyperopen.vol-range.infrastructure.execution-gate :as gate]))

(defn- with-storage [m f]
  (let [orig platform/local-storage-get]
    (set! platform/local-storage-get (fn [k] (get m k)))
    (try (f) (finally (set! platform/local-storage-get orig)))))

(deftest arming-alone-is-not-enough-test
  (with-storage {gate/arm-key "armed"}
    (fn []
      (is (gate/armed?))
      (is (= (and (config/vol-range-preview-enabled?) (config/vol-range-execution-built?)) (gate/enabled?))
          "the test build does not include placement, so it stays off")))
  (with-storage {gate/arm-key "yes"} #(is (not (gate/armed?))))
  (with-storage {} #(is (not (gate/armed?)))))

(deftest cap-override-test
  (with-storage {} #(is (= "150" (gate/max-ladder-usdc))))
  (with-storage {gate/cap-key " 250 "} #(is (= "250" (gate/max-ladder-usdc))))
  (testing "garbage and non-positive values keep the default"
    (with-storage {gate/cap-key "abc"} #(is (= "150" (gate/max-ladder-usdc))))
    (with-storage {gate/cap-key "-5"} #(is (= "150" (gate/max-ladder-usdc))))
    (with-storage {gate/cap-key "0"} #(is (= "150" (gate/max-ladder-usdc))))))

(deftest a-throwing-storage-disarms-test
  (let [orig platform/local-storage-get]
    (set! platform/local-storage-get (fn [_] (throw (js/Error. "SecurityError"))))
    (try (is (not (gate/armed?)))
         (is (= "150" (gate/max-ladder-usdc)))
         (finally (set! platform/local-storage-get orig)))))
