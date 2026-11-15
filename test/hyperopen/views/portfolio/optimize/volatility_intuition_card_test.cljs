(ns hyperopen.views.portfolio.optimize.volatility-intuition-card-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [collect-nodes collect-strings node-attr node-by-role]]
            [hyperopen.views.portfolio.optimize.volatility-intuition-card
             :as card]))

(defn- strings-of
  [node]
  (set (collect-strings node)))

(deftest card-translates-annualized-into-horizon-moves-test
  (let [node (card/volatility-intuition-card (fixtures/sample-solved-result))
        strings (strings-of node)
        target-panel (node-by-role node
                                   "portfolio-optimizer-volatility-intuition-panel-target")]
    (is (some? node))
    (is (contains? strings "Volatility intuition"))
    (is (contains? strings "28.00%"))
    (is (contains? (strings-of target-panel) "±1.47%"))
    (is (contains? (strings-of target-panel) "±3.88%"))
    (is (contains? (strings-of target-panel) "±8.03%"))
    (is (contains? strings
                   "A 1σ move is a volatility scale, not a forecast or maximum loss."))
    (is (contains? strings
                   "Scaled from annualized volatility using √time · 365 calendar days/year."))))

(deftest card-toggle-is-dom-radio-state-test
  (let [node (card/volatility-intuition-card (fixtures/sample-solved-result))
        radios (collect-nodes node #(and (= :input (first %))
                                         (= "radio" (node-attr % :type))))
        current-panel (node-by-role node
                                    "portfolio-optimizer-volatility-intuition-panel-current")]
    (is (= 2 (count radios)))
    (is (= #{"optimizer-volatility-intuition-5000"}
           (set (map #(node-attr % :name) radios)))
        "Both tabs share one group, keyed by the result's :as-of-ms.")
    (is (= #{"target" "current"}
           (set (map #(node-attr % :data-vol-view) radios))))
    (is (some? current-panel))
    (is (contains? (strings-of current-panel) "±1.26%"))))

(deftest card-without-current-volatility-drops-the-toggle-test
  (let [node (card/volatility-intuition-card
              (fixtures/sample-solved-result {:current-volatility nil}))]
    (is (some? node))
    (is (nil? (node-by-role node
                            "portfolio-optimizer-volatility-intuition-tabs")))
    (is (nil? (node-by-role node
                            "portfolio-optimizer-volatility-intuition-panel-current")))))

(deftest card-renders-nothing-without-target-volatility-test
  (is (nil? (card/volatility-intuition-card
             (fixtures/sample-solved-result {:volatility nil}))))
  (is (nil? (card/volatility-intuition-card
             (fixtures/sample-solved-result {:volatility js/NaN})))))

(deftest extreme-volatility-warns-and-explains-the-boundary-uncapped-test
  (let [node (card/volatility-intuition-card
              (fixtures/sample-solved-result {:volatility 4.1182}))
        target-panel (node-by-role node
                                   "portfolio-optimizer-volatility-intuition-panel-target")
        strings (strings-of target-panel)
        severity (node-by-role target-panel
                               "portfolio-optimizer-volatility-intuition-target-severity")
        boundary (node-by-role target-panel
                               "portfolio-optimizer-volatility-intuition-target-boundary")]
    (is (contains? strings "411.8%"))
    (is (contains? strings "±21.56%"))
    (is (contains? strings "±57.03%"))
    (is (contains? strings "±118.1%"))
    (is (some? severity))
    (is (contains? (strings-of severity) "Extreme volatility: "))
    (is (some? boundary))
    (is (some #(str/includes? % "−100% return boundary")
              (collect-strings boundary)))))

(deftest severity-note-tiers-test
  (let [severity-node (fn [volatility]
                        (node-by-role
                         (card/volatility-intuition-card
                          (fixtures/sample-solved-result {:volatility volatility}))
                         "portfolio-optimizer-volatility-intuition-target-severity"))]
    (is (nil? (severity-node 0.28)))
    (is (contains? (strings-of (severity-node 0.6)) "Elevated volatility."))
    (is (contains? (strings-of (severity-node 1.2)) "Very high volatility."))))

(deftest horizon-bars-are-decorative-and-scaled-to-monthly-test
  (let [node (card/volatility-intuition-card (fixtures/sample-solved-result))
        daily-row (node-by-role node
                                "portfolio-optimizer-volatility-intuition-target-daily")
        monthly-row (node-by-role node
                                  "portfolio-optimizer-volatility-intuition-target-monthly")
        track (fn [row]
                (first (collect-nodes row #(= "true" (node-attr % :aria-hidden)))))
        fill-width (fn [row]
                     (-> (collect-nodes row
                                        #(some-> (node-attr % :class)
                                                 set
                                                 (contains? "optimizer-vol-intuition-fill")))
                         first
                         (node-attr :style)
                         :width))]
    (is (some? (track daily-row))
        "Bars are aria-hidden; the ± text carries the value.")
    (is (= "100%" (fill-width monthly-row)))
    (is (str/starts-with? (fill-width daily-row) "18.2"))))
