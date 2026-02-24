(ns hyperopen.views.account-info.position-tpsl-modal-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.views.account-info.position-tpsl-modal :as position-tpsl-modal]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]))

(defn- sample-modal-view []
  (position-tpsl-modal/position-tpsl-modal-view
   (position-tpsl/from-position-row
    (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))))

(deftest position-tpsl-modal-text-inputs-use-event-target-value-placeholder-vector-test
  (let [modal-view (sample-modal-view)
        text-inputs (hiccup/find-all-nodes
                     modal-view
                     #(and (= :input (first %))
                           (= "text" (get-in % [1 :type]))))
        editable-text-inputs (filter #(contains? (get-in % [1 :on]) :input) text-inputs)
        input-actions (map #(first (get-in % [1 :on :input])) editable-text-inputs)
        input-paths (set (map second input-actions))]
    (is (= 4 (count editable-text-inputs)))
    (is (= #{[:tp-price] [:tp-gain] [:sl-price] [:sl-loss]}
           input-paths))
    (doseq [action input-actions]
      (is (= :actions/set-position-tpsl-modal-field
             (first action)))
      (is (= [:event.target/value]
             (nth action 2))))))

(deftest position-tpsl-modal-keydown-and-checkboxes-use-event-placeholders-test
  (let [modal-view (sample-modal-view)
        modal-surface (hiccup/find-first-node
                       modal-view
                       #(= "true" (get-in % [1 :data-position-tpsl-surface])))
        checkbox-inputs (hiccup/find-all-nodes
                         modal-view
                         #(and (= :input (first %))
                               (= "checkbox" (get-in % [1 :type]))))]
    (is (= [[:actions/handle-position-tpsl-modal-keydown [:event/key]]]
           (get-in modal-surface [1 :on :keydown])))
    (is (= 2 (count checkbox-inputs)))
    (doseq [checkbox-node checkbox-inputs]
      (is (= [:event.target/checked]
             (last (first (get-in checkbox-node [1 :on :change]))))))))
