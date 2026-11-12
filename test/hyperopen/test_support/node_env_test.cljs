(ns hyperopen.test-support.node-env-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform.locks :as locks]
            [hyperopen.test-support.node-env :as node-env]))

(deftest tests-never-see-web-locks-test
  (is (nil? (some-> js/globalThis .-navigator .-locks)))
  (is (= :in-tab-only (locks/coordination-kind))))

(deftest a-clean-exit-before-the-summary-is-a-failure-test
  (is (= 1 (node-env/unfinished-run-exit-code 0 false)))
  (is (= 1 (node-env/unfinished-run-exit-code nil false)))
  (is (= 0 (node-env/unfinished-run-exit-code 0 true)))
  (is (= 2 (node-env/unfinished-run-exit-code 2 false)) "a failing exit keeps its code"))
