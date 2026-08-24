(ns hyperopen.portfolio.custom-range-query-state-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.query-state :as portfolio-query-state]
            [hyperopen.vaults.application.query-state :as vault-query-state]))

(defn- ms [iso] (.getTime (js/Date. iso)))

(def ^:private range
  {:from (ms "2026-03-03T00:00:00.000Z")
   :to (ms "2026-06-12T00:00:00.000Z")})

;; --- portfolio ------------------------------------------------------------------------------

(deftest portfolio-custom-range-round-trips-through-the-url-test
  (testing "written only while a custom window is live"
    (is (some #{["from" "2026-03-03"]} (portfolio-query-state/portfolio-query-params
                                        {:portfolio-ui {:summary-custom-range range}})))
    (is (some #{["to" "2026-06-12"]} (portfolio-query-state/portfolio-query-params
                                      {:portfolio-ui {:summary-custom-range range}}))))
  (testing "and omitted entirely otherwise, so a preset URL stays clean"
    (let [params (portfolio-query-state/portfolio-query-params {:portfolio-ui {}})
          keys* (set (map first params))]
      (is (not (contains? keys* "from")))
      (is (not (contains? keys* "to")))))
  (testing "parsed back into the same window"
    (is (= range (:summary-custom-range
                  (portfolio-query-state/parse-portfolio-query "?from=2026-03-03&to=2026-06-12"))))))

(deftest portfolio-custom-range-parse-is-strict-test
  (testing "a half-specified or malformed range yields no window rather than an invented one"
    (is (nil? (:summary-custom-range (portfolio-query-state/parse-portfolio-query "?from=2026-03-03"))))
    (is (nil? (:summary-custom-range (portfolio-query-state/parse-portfolio-query "?from=nonsense&to=2026-06-12"))))
    (is (nil? (:summary-custom-range (portfolio-query-state/parse-portfolio-query "?from=2026-3-3&to=2026-06-12"))))))

(deftest portfolio-url-without-bounds-clears-a-live-custom-range-test
  (let [state {:portfolio-ui {:summary-custom-range range
                              :summary-range-strip :chart}}
        applied (portfolio-query-state/apply-portfolio-query-state
                 state
                 (portfolio-query-state/parse-portfolio-query "?range=3m"))]
    (testing "back-navigating to a preset URL must not leave the old window applied"
      (is (nil? (get-in applied [:portfolio-ui :summary-custom-range]))))
    (testing "and a shared link never lands with the editor already open"
      (is (nil? (get-in applied [:portfolio-ui :summary-range-strip]))))))

;; --- vault detail ---------------------------------------------------------------------------

(deftest vault-detail-custom-range-round-trips-through-the-url-test
  (let [params (vault-query-state/vault-detail-query-params
                {:vaults-ui {:snapshot-range :six-month
                             :detail-chart-series :returns
                             :detail-custom-range range}})]
    (is (some #{["from" "2026-03-03"]} params))
    (is (some #{["to" "2026-06-12"]} params)))
  (testing "omitted when no custom window is applied"
    (let [keys* (set (map first (vault-query-state/vault-detail-query-params
                                 {:vaults-ui {:snapshot-range :six-month
                                              :detail-chart-series :returns}})))]
      (is (not (contains? keys* "from")))))
  (testing "parsed back into the same window"
    (is (= range (:detail-custom-range
                  (vault-query-state/parse-vault-detail-query "?from=2026-03-03&to=2026-06-12"))))))

(deftest vault-url-without-bounds-clears-a-live-custom-range-test
  (let [applied (vault-query-state/apply-vault-query-state
                 {:vaults-ui {:detail-custom-range range
                              :detail-range-strip :chart}}
                 (vault-query-state/parse-vault-detail-query "?range=6m"))]
    (is (nil? (get-in applied [:vaults-ui :detail-custom-range])))
    (is (nil? (get-in applied [:vaults-ui :detail-range-strip])))))

(deftest a-custom-range-never-leaks-into-the-vault-list-preset-test
  (testing "the list surface keeps its own preset key untouched by a detail custom window"
    (let [applied (vault-query-state/apply-vault-query-state
                   {:vaults-ui {:snapshot-range :month
                                :detail-custom-range range}}
                   (vault-query-state/parse-vault-list-query "?range=30d"))]
      (is (= :month (get-in applied [:vaults-ui :snapshot-range])))
      (is (keyword? (get-in applied [:vaults-ui :snapshot-range]))))))
