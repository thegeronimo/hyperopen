(ns hyperopen.vaults.screener.model-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vaults.screener.model :as model]))

(defn- row
  [address tvl age-days & {:as extra}]
  (merge {:vault-address address
          :tvl tvl
          :age-days age-days
          :relationship-type :normal
          :is-closed? false
          :has-deposit? false}
         extra))

(def rows
  [(row "0xa" 50000 400)
   (row "0xb" 9000 400)
   (row "0xc" 2000000 200)
   (row "0xd" 80000 20)
   (row "0xe" 30000 300 :is-closed? true)
   (row "0xf" 90000 500 :relationship-type :child)
   (row "0xg" 500 10 :has-deposit? true)])

(deftest normalize-config-defaults-and-coerces-test
  (let [config (model/normalize-config nil)]
    (is (= 180 (:min-age-days config)))
    (is (= 10000 (:min-tvl config)))
    (is (= :six-month (:window config)))
    (is (= [{:metric :sharpe :value "1.0"} {:metric :max-drawdown :value "25"}]
           (:criteria config))))
  (testing "string tokens from the DOM coerce; unknown values fall back"
    (let [config (model/normalize-config {:min-age-days "365"
                                          :min-tvl 12345
                                          :window ":one-year"
                                          :criteria [{:metric "sortino" :value 2}
                                                     {:metric :sortino :value "9"}
                                                     {:metric :bogus :value "1"}]})]
      (is (= 365 (:min-age-days config)))
      (is (= 10000 (:min-tvl config)))
      (is (= :one-year (:window config)))
      (is (= [{:metric :sortino :value "2"}] (:criteria config))
          "duplicates and unknown metrics are dropped"))))

(deftest universe-rows-filters-and-orders-by-tvl-test
  (testing "age and TVL gate, closed and child vaults excluded, deposits kept"
    (is (= ["0xc" "0xa" "0xg"]
           (mapv :vault-address (model/universe-rows rows {:min-age-days 180 :min-tvl 10000})))))
  (testing "deposited vaults are not forced in when the toggle is off"
    (is (= ["0xc" "0xa"]
           (mapv :vault-address (model/universe-rows rows {:min-age-days 180
                                                            :min-tvl 10000
                                                            :include-deposited? false})))))
  (is (= 5 (model/base-universe-count rows))))

(deftest display-value-converts-units-test
  (is (= 25 (model/display-value :max-drawdown -0.25)) "drawdowns compare by magnitude, in percent")
  (is (= 1.5 (model/display-value :sharpe 1.5)))
  (is (= 40 (model/display-value :volatility-ann 0.4)))
  (is (nil? (model/display-value :sharpe nil))))

(def config
  (model/normalize-config {:criteria [{:metric :sharpe :value "1.0"}
                                      {:metric :max-drawdown :value "25"}]
                           :min-history-points 20}))

(deftest evaluate-classifies-results-test
  (is (= :pass (:status (model/evaluate config {:point-count 40
                                                :values {:sharpe 1.4 :max-drawdown -0.2}}))))
  (testing "one criterion missed by less than 10% of its threshold is a near miss"
    (let [{:keys [status miss]} (model/evaluate config {:point-count 40
                                                        :values {:sharpe 0.95 :max-drawdown -0.1}})]
      (is (= :near status))
      (is (= :sharpe (:metric miss)))))
  (is (= :near (:status (model/evaluate config {:point-count 40
                                                :values {:sharpe 2 :max-drawdown -0.26}}))))
  (is (= :fail (:status (model/evaluate config {:point-count 40
                                                :values {:sharpe 0.5 :max-drawdown -0.1}}))))
  (is (= :fail (:status (model/evaluate config {:point-count 40
                                                :values {:sharpe 0.95 :max-drawdown -0.26}})))
      "two near misses are a fail")
  (testing "not enough data"
    (is (= {:status :thin :reason :history}
           (select-keys (model/evaluate config {:point-count 12 :values {:sharpe 3 :max-drawdown 0}})
                        [:status :reason])))
    (is (= {:status :thin :reason :metric :metric :sharpe}
           (select-keys (model/evaluate config {:point-count 40 :values {:sharpe nil :max-drawdown -0.1}})
                        [:status :reason :metric]))
        "a metric suppressed by the tear-sheet quality gates")
    (is (= :error (:reason (model/evaluate config {:error "boom"})))))
  (testing "a blank threshold is ignored rather than failing every vault"
    (is (= :pass (:status (model/evaluate (assoc config :criteria [{:metric :sharpe :value ""}])
                                          {:point-count 40 :values {}}))))))

(deftest estimate-and-duration-test
  (let [now 1000000000
        index {"0xa" (- now 1000) "0xb" (- now model/cache-ttl-ms 1)}
        estimate (model/estimate ["0xa" "0xb" "0xc"] index now)]
    (is (= {:total 3 :cached 1 :to-fetch 2 :seconds 4}
           (select-keys estimate [:total :cached :to-fetch :seconds])))
    (is (= 0.1 (:megabytes estimate))))
  (is (= "instant" (model/format-duration 0)))
  (is (= "45 s" (model/format-duration 45)))
  (is (= "6 min 54 s" (model/format-duration 414)))
  (is (= "7 min" (model/format-duration 420))))

(deftest apply-preset-keeps-other-settings-test
  (let [config (model/apply-preset (model/normalize-config {:min-history-points 20}) :low-drawdown)]
    (is (= 90 (:min-age-days config)))
    (is (= :six-month (:window config)))
    (is (= 20 (:min-history-points config)))
    (is (= [:max-drawdown :volatility-ann] (mapv :metric (:criteria config))))))

(deftest sort-results-puts-missing-values-last-test
  (let [rows [{:vault-address "a" :values {:sharpe 1}}
              {:vault-address "b" :values {}}
              {:vault-address "c" :values {:sharpe 3}}]]
    (is (= ["c" "a" "b"] (mapv :vault-address (model/sort-results rows {:metric :sharpe :direction :desc}))))
    (is (= ["a" "c" "b"] (mapv :vault-address (model/sort-results rows {:metric :sharpe :direction :asc}))))))

(deftest universe-signature-ignores-thresholds-test
  (is (= (model/universe-config-signature config)
         (model/universe-config-signature (assoc config :criteria [{:metric :sharpe :value "3"}]))))
  (is (not= (model/universe-config-signature config)
            (model/universe-config-signature (assoc config :window :one-year)))))
