(ns hyperopen.utils.interval-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.utils.interval :as interval]))

(deftest interval-to-milliseconds-test
  (is (= 60000 (interval/interval-to-milliseconds :1m)))
  (is (= 300000 (interval/interval-to-milliseconds :5m)))
  (is (= 3600000 (interval/interval-to-milliseconds :1h)))
  (is (= 86400000 (interval/interval-to-milliseconds :1d)))
  (is (= 604800000 (interval/interval-to-milliseconds :1w))))

(deftest interval-default-fallback-test
  (is (= 86400000 (interval/interval-to-milliseconds :unknown)))
  (is (= 86400000 (interval/interval-to-milliseconds nil))))
