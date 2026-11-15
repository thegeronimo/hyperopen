(ns hyperopen.runtime.dispatch-errors-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.runtime.dispatch-errors :as dispatch-errors]))

(use-fixtures :each
  {:before (fn [] (dispatch-errors/clear-dispatch-error-log!))})

(deftest summarize-error-extracts-action-identity-test
  (let [summary (dispatch-errors/summarize-error
                 {:phase :expand-action
                  :action [:actions/select-asset "ETH"]
                  :err (js/Error. "boom")})]
    (is (= :expand-action (:phase summary)))
    (is (= :actions/select-asset (:action-id summary)))
    (is (= "boom" (:error summary)))
    (is (= "Error" (:error-name summary)))))

(deftest summarize-error-extracts-effect-identity-from-effect-k-test
  (let [summary (dispatch-errors/summarize-error
                 {:phase :execute-effect
                  :effect-k :effects/save
                  :err (js/Error. "no such effect")})]
    (is (= :execute-effect (:phase summary)))
    (is (= :effects/save (:effect-id summary)))
    (is (= "no such effect" (:error summary)))))

(deftest summarize-error-extracts-effect-identity-from-effect-vector-test
  (let [summary (dispatch-errors/summarize-error
                 {:phase :before-effect
                  :effect [:effects/persist {:k 1}]
                  :err (ex-info "kaboom" {})})]
    (is (= :effects/persist (:effect-id summary)))
    (is (= "kaboom" (:error summary)))))

(deftest summarize-error-is-defensive-about-missing-fields-test
  (let [summary (dispatch-errors/summarize-error {:phase :action-dispatch})]
    (is (= {:phase :action-dispatch} summary))))

(deftest record-dispatch-errors-appends-summaries-test
  (dispatch-errors/record-dispatch-errors!
   [{:phase :expand-action :action [:actions/a] :err (js/Error. "x")}
    {:phase :execute-effect :effect-k :effects/b :err (js/Error. "y")}])
  (let [entries (dispatch-errors/dispatch-error-log-snapshot)]
    (is (= 2 (count entries)))
    (is (= :actions/a (:action-id (first entries))))
    (is (= :effects/b (:effect-id (second entries))))
    (is (every? :captured-at-ms entries))))

(deftest record-dispatch-errors-ignores-empty-test
  (is (nil? (dispatch-errors/record-dispatch-errors! [])))
  (is (nil? (dispatch-errors/record-dispatch-errors! nil)))
  (is (empty? (dispatch-errors/dispatch-error-log-snapshot))))

(deftest record-dispatch-errors-is-bounded-test
  (dotimes [i 205]
    (dispatch-errors/record-dispatch-errors!
     [{:phase :execute-effect :effect-k (keyword "effects" (str "e" i))}]))
  (let [entries (dispatch-errors/dispatch-error-log-snapshot)]
    (is (= 200 (count entries)))
    (is (= :effects/e5 (:effect-id (first entries))))
    (is (= :effects/e204 (:effect-id (last entries))))))

(deftest clear-dispatch-error-log-empties-buffer-test
  (dispatch-errors/record-dispatch-errors!
   [{:phase :execute-effect :effect-k :effects/b}])
  (is (seq (dispatch-errors/dispatch-error-log-snapshot)))
  (dispatch-errors/clear-dispatch-error-log!)
  (is (empty? (dispatch-errors/dispatch-error-log-snapshot))))

(deftest after-dispatch-interceptor-records-and-returns-ctx-unchanged-test
  (let [{:keys [after-dispatch]} (dispatch-errors/after-dispatch-interceptor)
        ctx {:state {:a 1}
             :errors [{:phase :execute-effect :effect-k :effects/save :err (js/Error. "boom")}]}]
    (is (identical? ctx (after-dispatch ctx)))
    (let [entries (dispatch-errors/dispatch-error-log-snapshot)]
      (is (= 1 (count entries)))
      (is (= :effects/save (:effect-id (first entries)))))))

(deftest after-dispatch-interceptor-noop-without-errors-test
  (let [{:keys [after-dispatch]} (dispatch-errors/after-dispatch-interceptor)
        ctx {:state {:a 1}}]
    (is (identical? ctx (after-dispatch ctx)))
    (is (empty? (dispatch-errors/dispatch-error-log-snapshot)))))

(deftest install-registers-interceptor-exactly-once-test
  (let [registered (atom [])]
    (dispatch-errors/reset-installed-for-test!)
    (is (true? (dispatch-errors/install! #(swap! registered conj %))))
    (is (nil? (dispatch-errors/install! #(swap! registered conj %))))
    (is (= 1 (count @registered)))
    (is (= :hyperopen.runtime.dispatch-errors/surface-dispatch-errors
           (:id (first @registered))))
    (is (fn? (:after-dispatch (first @registered))))
    (dispatch-errors/reset-installed-for-test!)))
