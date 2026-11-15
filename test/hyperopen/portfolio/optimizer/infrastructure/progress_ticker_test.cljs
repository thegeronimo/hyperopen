(ns hyperopen.portfolio.optimizer.infrastructure.progress-ticker-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.infrastructure.progress-ticker :as ticker]))

(def ^:private progress-path [:optimizer :optimization-progress])

(defn- harness
  "Installs the ticker against an in-memory store with controllable timers.
  Returns the moving parts so a test can drive transitions and ticks by hand."
  [initial]
  (let [store (atom initial)
        next-id (atom 0)
        captured (atom {})
        cleared (atom [])
        now (atom 1000)]
    {:store store
     :captured captured
     :cleared cleared
     :now now
     :fire! (fn [id] ((get @captured id)))
     :install! (fn []
                 (ticker/install-optimization-progress-ticker!
                  {:store store
                   :progress-path progress-path
                   :now-ms-fn (fn [] @now)
                   :set-interval-fn (fn [f _ms]
                                      (let [id (swap! next-id inc)]
                                        (swap! captured assoc id f)
                                        id))
                   :clear-interval-fn (fn [id] (swap! cleared conj id))}))}))

(deftest ticker-stays-idle-until-a-run-starts-then-stops-when-it-settles-test
  (let [{:keys [store captured cleared install!]} (harness {:optimizer {:optimization-progress {:status :idle}}})]
    (install!)
    (testing "no interval armed while idle"
      (is (empty? @captured)))
    (swap! store assoc-in (conj progress-path :status) :running)
    (testing "a run starts exactly one interval"
      (is (= 1 (count @captured))))
    (swap! store assoc-in (conj progress-path :status) :succeeded)
    (testing "settling the run clears the interval"
      (is (= [1] @cleared)))))

(deftest ticker-frame-advances-display-and-clock-only-while-running-test
  (let [{:keys [store now fire! install!]} (harness {:optimizer {:optimization-progress {:status :idle}}})]
    (install!)
    (swap! store assoc-in (conj progress-path :status) :running)
    (swap! store assoc-in (conj progress-path :overall-percent) 40)
    (reset! now 2500)
    (fire! 1)
    (let [p (get-in @store progress-path)]
      (is (= 2500 (:now-ms p)))
      (is (< 40 (:display-percent p)) "display trickles ahead of true progress"))
    (reset! now 3000)
    (fire! 1)
    (is (= 3000 (get-in @store (conj progress-path :now-ms))))))

(deftest ticker-does-not-rearm-on-its-own-tick-swaps-test
  (let [{:keys [store captured fire! install!]} (harness {:optimizer {:optimization-progress {:status :idle}}})]
    (install!)
    (swap! store assoc-in (conj progress-path :status) :running)
    (swap! store assoc-in (conj progress-path :overall-percent) 20)
    (fire! 1)
    (fire! 1)
    (is (= 1 (count @captured)) "still exactly one interval after several frames")))

(deftest ticker-arms-immediately-when-installed-during-an-active-run-test
  (let [{:keys [captured install!]} (harness {:optimizer {:optimization-progress {:status :running
                                                                                  :overall-percent 10}}})]
    (install!)
    (is (= 1 (count @captured)))))
