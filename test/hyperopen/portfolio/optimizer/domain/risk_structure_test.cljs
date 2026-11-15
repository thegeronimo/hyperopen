(ns hyperopen.portfolio.optimizer.domain.risk-structure-test
  "The correlation/decomposition math behind the Equal Risk correlation view:
  underlying correlations (unit diagonal, symmetry, degenerate assets go
  nil), the exact standalone + diversification = net identity, P&L-to-
  portfolio correlation sign behavior, and the capped/ordered/position-only
  correlation section of the :risk-structure payload."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.domain.risk-structure :as risk-structure]))

(defn- near?
  ([expected actual] (near? expected actual 1e-12))
  ([expected actual tolerance]
   (and (number? actual)
        (< (js/Math.abs (- expected actual)) tolerance))))

(def ^:private hedged-pair-covariance
  [[1.0 0.5]
   [0.5 1.0]])

(def ^:private hedged-pair-weights [1.0 -1.0])

(deftest correlation-matrix-diagonal-symmetry-and-clamp-test
  (let [matrix (risk-structure/correlation-matrix hedged-pair-covariance)]
    (is (= 1.0 (get-in matrix [0 0])))
    (is (= 1.0 (get-in matrix [1 1])))
    (is (= 0.5 (get-in matrix [0 1])))
    (is (= (get-in matrix [0 1]) (get-in matrix [1 0]))))
  (testing "entries clamp into [-1, 1] against float noise"
    (let [matrix (risk-structure/correlation-matrix
                  [[1.0 1.0000000001]
                   [1.0000000001 1.0]])]
      (is (= 1.0 (get-in matrix [0 1]))))))

(deftest correlation-matrix-degenerate-asset-goes-nil-test
  (let [matrix (risk-structure/correlation-matrix
                [[1.0 0.5 0.0]
                 [0.5 1.0 0.0]
                 [0.0 0.0 0.0]])]
    (is (= 0.5 (get-in matrix [0 1])))
    (is (nil? (get-in matrix [0 2])))
    (is (nil? (get-in matrix [2 2])))))

(deftest hedged-pair-decomposition-test
  (let [{:keys [status portfolio-volatility standalone-shares
                diversification-shares net-shares
                pnl-portfolio-correlations]}
        (risk-structure/decomposition hedged-pair-covariance
                                      hedged-pair-weights)]
    (is (= :ok status))
    (is (= 1 portfolio-volatility))
    (is (= [1.0 1.0] standalone-shares))
    (is (= [-0.5 -0.5] diversification-shares))
    (is (= [0.5 0.5] net-shares))
    (is (= [0.5 0.5] pnl-portfolio-correlations))))

(deftest decomposition-identity-holds-on-a-mixed-book-test
  (let [covariance [[0.04 0.01 0.0 -0.005]
                    [0.01 0.09 -0.02 0.0]
                    [0.0 -0.02 0.02 0.005]
                    [-0.005 0.0 0.005 0.0625]]
        weights [0.75 -0.25 0.5 -0.125]
        {:keys [status standalone-shares diversification-shares net-shares]}
        (risk-structure/decomposition covariance weights)]
    (is (= :ok status))
    (is (every? #(>= % 0) standalone-shares))
    (is (near? 1 (reduce + 0 net-shares)))
    (doseq [idx (range (count weights))]
      (is (near? (nth net-shares idx)
                 (+ (nth standalone-shares idx)
                    (nth diversification-shares idx))
                 1e-15)))))

(deftest decomposition-degenerate-entries-test
  (testing "zero weight => no P&L stream => nil portfolio correlation"
    (let [{:keys [status pnl-portfolio-correlations standalone-shares]}
          (risk-structure/decomposition
           [[1.0 0.5 0.25]
            [0.5 1.0 0.25]
            [0.25 0.25 1.0]]
           [1.0 -1.0 0.0])]
      (is (= :ok status))
      (is (nil? (nth pnl-portfolio-correlations 2)))
      (is (= 0 (nth standalone-shares 2)))))
  (testing "degenerate portfolio variance fails explicitly"
    (let [{:keys [status reason]}
          (risk-structure/decomposition [[0.0 0.0] [0.0 0.0]] [1.0 -1.0])]
      (is (= :error status))
      (is (= :degenerate-variance reason)))))

(deftest structure-summary-shape-and-position-only-correlation-test
  (let [summary (risk-structure/structure-summary
                 {:instrument-ids ["perp:BTC" "perp:ETH" "perp:GOLD"]
                  :covariance [[1.0 0.5 0.25]
                               [0.5 1.0 0.25]
                               [0.25 0.25 1.0]]
                  :weights [1.0 -1.0 0.0]})]
    (is (= :ok (:status summary)))
    (is (= :signed-euler-decomposition (:method summary)))
    (testing "zero-weight instruments keep decomposition zeros but leave the
              P&L map and the correlation matrix"
      (is (= 0 (get-in summary [:standalone-share-by-instrument "perp:GOLD"])))
      (is (not (contains? (:pnl-portfolio-correlation-by-instrument summary)
                          "perp:GOLD")))
      (is (= ["perp:BTC" "perp:ETH"]
             (get-in summary [:correlation :instrument-ids]))))
    (is (= [[1.0 0.5] [0.5 1.0]]
           (get-in summary [:correlation :matrix])))
    (is (= 0 (get-in summary [:correlation :hidden-count])))))

(deftest structure-summary-cap-order-and-hidden-count-test
  (let [summary (risk-structure/structure-summary
                 {:instrument-ids ["A" "B" "C" "D"]
                  :covariance [[1.0 0.0 0.0 0.0]
                               [0.0 1.0 0.0 0.0]
                               [0.0 0.0 1.0 0.0]
                               [0.0 0.0 0.0 1.0]]
                  :weights [0.5 -1.0 2.0 -1.5]
                  :correlation-cap 2})]
    (is (= :ok (:status summary)))
    (is (= ["C" "D"] (get-in summary [:correlation :instrument-ids])))
    (is (= [[1.0 0.0] [0.0 1.0]] (get-in summary [:correlation :matrix])))
    (is (= 2 (get-in summary [:correlation :hidden-count])))))

(deftest structure-summary-degenerate-variance-propagates-test
  (let [summary (risk-structure/structure-summary
                 {:instrument-ids ["A" "B"]
                  :covariance [[0.0 0.0] [0.0 0.0]]
                  :weights [1.0 -1.0]})]
    (is (= :error (:status summary)))
    (is (= :degenerate-variance (:reason summary)))))

(deftest portfolio-diversification-and-positive-cross-covariance-coexist-test
  (let [covariance [[1.0 0.5]
                    [0.5 1.0]]
        weights [0.5 0.5]
        summary (risk-structure/portfolio-diversification-summary covariance
                                                                  weights)
        decomposition (risk-structure/decomposition covariance weights)]
    (is (= :ok (:status summary)))
    (is (near? (js/Math.sqrt 0.75) (:modeled-volatility summary)))
    (is (near? 1.0 (:all-move-together-volatility summary)))
    (is (near? (js/Math.sqrt 0.5) (:zero-correlation-volatility summary)))
    (is (near? (- 1.0 (js/Math.sqrt 0.75))
               (:reduction-vs-all-move-together summary)))
    (is (near? (- 1.0 (js/Math.sqrt 0.75))
               (:reduction-ratio-vs-all-move-together summary)))
    (is (pos? (:modeled-minus-zero-correlation summary)))
    (is (every? pos? (:diversification-shares decomposition))
        "positive cross-covariance attribution can coexist with portfolio diversification")
    (doseq [[own cross net]
            (map vector (:standalone-shares decomposition)
                 (:diversification-shares decomposition)
                 (:net-shares decomposition))]
      (is (near? net (+ own cross))))))
