(ns hyperopen.vol-range.infrastructure.execution-gate-test
  "Placement is on wherever the build includes it unless this browser opted
  out; there is no cap unless this browser set a positive one."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.config :as config]
            [hyperopen.platform :as platform]
            [hyperopen.vol-range.infrastructure.execution-gate :as gate]))

(defn- with-storage [m f]
  (let [orig platform/local-storage-get]
    (set! platform/local-storage-get (fn [k] (get m k)))
    (try (f) (finally (set! platform/local-storage-get orig)))))

(deftest on-by-default-test
  (with-storage {}
    (fn []
      (is (gate/armed?))
      (is (= (and (config/vol-range-preview-enabled?) (config/vol-range-execution-built?)) (gate/enabled?))
          "the build flag still decides; the test build does not include placement")))
  (with-storage {gate/arm-key "armed"} #(is (gate/armed?)))
  (testing "a browser can opt out"
    (with-storage {gate/arm-key "off"} #(is (not (gate/armed?))))
    (with-storage {gate/arm-key " OFF "} #(is (not (gate/armed?))))
    (with-storage {gate/arm-key "off"} #(is (not (gate/enabled?))))))

(deftest cap-override-test
  (with-storage {} #(is (nil? (gate/max-ladder-usdc))))
  (with-storage {gate/cap-key " 250 "} #(is (= "250" (gate/max-ladder-usdc))))
  (testing "garbage and non-positive values mean no cap"
    (with-storage {gate/cap-key "abc"} #(is (nil? (gate/max-ladder-usdc))))
    (with-storage {gate/cap-key "-5"} #(is (nil? (gate/max-ladder-usdc))))
    (with-storage {gate/cap-key "0"} #(is (nil? (gate/max-ladder-usdc))))))

(deftest a-throwing-storage-keeps-the-defaults-test
  (let [orig platform/local-storage-get]
    (set! platform/local-storage-get (fn [_] (throw (js/Error. "SecurityError"))))
    (try (is (gate/armed?))
         (is (nil? (gate/max-ladder-usdc)))
         (finally (set! platform/local-storage-get orig)))))
