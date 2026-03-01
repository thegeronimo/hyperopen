(ns hyperopen.views.portfolio.vm.metrics-bridge-helpers-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.views.portfolio.vm.metrics-bridge :as vm-metrics-bridge]))

(defn- approx=
  [a b]
  (< (js/Math.abs (- a b)) 1e-9))

(deftest worker-result-normalization-covers-nil-and-map-paths-test
  (is (nil? (vm-metrics-bridge/normalize-worker-metric-values nil)))
  (is (= {:cagr 1 :sharpe 2}
         (vm-metrics-bridge/normalize-worker-metric-values #js {:cagr 1 :sharpe 2})))
  (is (nil? (vm-metrics-bridge/normalize-worker-metrics-result nil)))
  (is (= {:values {:alpha 3}
          :rows [{:name "x"}]}
         (vm-metrics-bridge/normalize-worker-metrics-result
          #js {:values #js {:alpha 3}
               :rows #js [#js {:name "x"}]}))))

(deftest request-metrics-computation-fallback-uses-sync-builder-test
  (let [result* (atom nil)]
    (with-redefs [vm-metrics-bridge/metrics-worker nil
                  portfolio-metrics/compute-performance-metrics (fn [{:keys [seed]}]
                                                                  {:value (* seed 2)})
                  portfolio-metrics/metric-rows (fn [values]
                                                  [{:key :value :value (:value values)}])]
      (vm-metrics-bridge/request-metrics-computation! {:seed 7}
                                                      #(reset! result* %))
      (is (= {:values {:value 14}
              :rows [{:key :value :value 14}]}
             @result*)))))

(deftest request-metrics-computation-worker-path-posts-and-handles-message-test
  (let [posted-message (atom nil)
        result* (atom nil)
        fake-worker (js-obj)]
    (with-redefs [vm-metrics-bridge/metrics-worker fake-worker]
      (set! (.-postMessage fake-worker)
            (fn [message]
              (reset! posted-message (js->clj message :keywordize-keys true))))
      (vm-metrics-bridge/request-metrics-computation! {:seed 5}
                                                      #(reset! result* %))
      (is (= {:type "compute-metrics"
              :payload {:seed 5}}
             @posted-message))
      ((.-onmessage fake-worker)
       (js-obj "data" (js-obj "type" "ignored"
                              "payload" (js-obj "values" (js-obj "x" 1)))))
      (is (nil? @result*))
      ((.-onmessage fake-worker)
       (js-obj "data" (js-obj "type" "metrics-result"
                              "payload" (js-obj "values" (js-obj "x" 2)
                                                "rows" #js []))))
      (is (= 2 (get-in @result* [:values :x]))))))

(deftest metrics-request-and-sync-helpers-cover-signature-and-row-defaults-test
  (let [strategy [{:time-ms 101} {:time-ms 102}]
        benchmark [{:time-ms 201}]
        signature-a (vm-metrics-bridge/metrics-request-signature strategy benchmark 0)
        signature-b (vm-metrics-bridge/metrics-request-signature strategy benchmark 0.01)]
    (is (string? signature-a))
    (is (not= signature-a signature-b)))
  (is (= [{:time-ms 1}]
         (vm-metrics-bridge/request-benchmark-daily-rows {:performance-daily-rows [{:time-ms 1}]})))
  (is (= [] (vm-metrics-bridge/request-benchmark-daily-rows {})))
  (is (= [{:time-ms 1}] (vm-metrics-bridge/request-strategy-daily-rows [{:time-ms 1}])))
  (is (= [] (vm-metrics-bridge/request-strategy-daily-rows nil)))
  (with-redefs [portfolio-metrics/compute-performance-metrics (fn [{:keys [seed]}]
                                                                {:metric seed})
                portfolio-metrics/metric-rows (fn [values]
                                                [{:id :metric :value (:metric values)}])]
    (is (= {:values {:metric 9}
            :rows [{:id :metric :value 9}]}
           (vm-metrics-bridge/compute-metrics-sync {:seed 9})))))

(deftest vault-snapshot-and-alignment-helpers-cover-branches-test
  (is (= ["1d" "7d" "30d"]
         (vm-metrics-bridge/vault-snapshot-range-keys)))
  (is (= 2 (vm-metrics-bridge/vault-snapshot-point-value [1 "2"])))
  (is (thrown? js/Error
               (vm-metrics-bridge/vault-snapshot-point-value {:value "3"})))
  (is (nil? (vm-metrics-bridge/vault-snapshot-point-value nil)))
  (is (= 4
         (vm-metrics-bridge/normalize-vault-snapshot-return "1d" {:returns {"1d" 4}})))
  (is (nil?
       (vm-metrics-bridge/normalize-vault-snapshot-return "7d" {:returns {"7d" js/Infinity}})))
  (is (= {"1d" 1 "7d" nil "30d" 3}
         (vm-metrics-bridge/vault-benchmark-snapshot-values
          {:returns {"1d" 1 "30d" 3}})))
  (let [aligned (vm-metrics-bridge/aligned-vault-return-rows
                 {:history [[1 100]
                            [2 110]
                            [3 121]]}
                 [{:time-ms 2}
                  {:time-ms 3}
                  {:time-ms 4}])]
    (is (= [2 3] (mapv :time-ms aligned)))
    (is (approx= 0 (get-in aligned [0 :value])))
    (is (approx= 10 (get-in aligned [1 :value]))))
  (is (= []
         (vm-metrics-bridge/aligned-vault-return-rows
          {:history [[10 50]
                     [11 55]]}
          [{:time-ms 9}
           {:time-ms 10}])))
  (is (= []
         (vm-metrics-bridge/aligned-vault-return-rows
          {:history [[1 100]]}
          []))))
