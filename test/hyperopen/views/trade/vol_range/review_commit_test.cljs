(ns hyperopen.views.trade.vol-range.review-commit-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.vol-range.application.dock-model :as dm]
            [hyperopen.views.trade.vol-range.review :as review]))

(defn- model [commit]
  {:open? true :title "Review 2 post-only orders" :identity-line "SYN" :plan-as-of "now"
   :base-symbol "SYN" :tiles [] :legs [] :cash [] :inventory [] :details []
   :footer {:hint "Pressing Enter in a field never places orders." :back-label "Back to edit"
            :text "Placement isn't available yet"}
   :commit commit})

(defn- state [execution plan]
  {:vol-range {:execution execution :frozen-review plan}})

(def ready {:status :ready :legs [{:price "60" :size "1" :notional "60"} {:price "60" :size "1" :notional "60"}]})

(defn- render [execution plan]
  (review/review-dialog (model (dm/review-commit (state execution plan)))))

(deftest hidden-when-not-enabled-keeps-unavailable-text-test
  (let [html (render {:enabled? false} ready)]
    (is (nil? (hiccup/find-by-data-role html "vol-range-submit")))
    (is (= "Placement isn't available yet"
           (str/join "" (hiccup/collect-strings (hiccup/find-by-data-role html "vol-range-placement-unavailable")))))))

(deftest visible-enabled-commit-button-test
  (let [html (render {:enabled? true :max-ladder-usdc "150"} ready)
        b (hiccup/find-by-data-role html "vol-range-submit")]
    (is (= :button (first b)))
    (is (= "button" (get-in b [1 :type])))
    (is (= "Place 2 orders" (str/join "" (hiccup/collect-strings b))))
    (is (false? (get-in b [1 :disabled])))
    (is (false? (get-in b [1 :aria-busy])))
    (is (= [[:actions/place-vol-range-orders]] (get-in b [1 :on :click])))
    (is (nil? (hiccup/find-by-data-role html "vol-range-placement-unavailable")))
    (is (str/includes? (str/join "" (hiccup/collect-strings (hiccup/find-by-data-role html "vol-range-submit-note")))
                       "Post-only orders"))
    (testing "Enter never places: no form, no keydown/submit handler on the button"
      (is (empty? (hiccup/find-all-nodes html #(= :form (first %)))))
      (is (nil? (get-in b [1 :on :keydown])))
      (is (= [[:actions/handle-vol-range-review-keydown [:event/key]]]
             (get-in (hiccup/find-by-data-role html "vol-range-review") [1 :on :keydown]))))))

(deftest disabled-when-plan-not-ready-or-over-cap-test
  (is (true? (get-in (hiccup/find-by-data-role (render {:enabled? true :max-ladder-usdc "150"}
                                                       (assoc ready :status :blocked))
                                               "vol-range-submit")
                     [1 :disabled])))
  (let [html (render {:enabled? true :max-ladder-usdc "100"} ready)]
    (is (true? (get-in (hiccup/find-by-data-role html "vol-range-submit") [1 :disabled])))
    (is (str/includes? (str/join "" (hiccup/collect-strings (hiccup/find-by-data-role html "vol-range-submit-note")))
                       "local test cap"))))

(deftest placing-is-busy-and-disabled-test
  (let [html (render {:enabled? true :max-ladder-usdc "150" :busy {[:place] true}} ready)
        b (hiccup/find-by-data-role html "vol-range-submit")]
    (is (true? (get-in b [1 :aria-busy])))
    (is (true? (get-in b [1 :disabled])))
    (is (= "Placing orders…" (str/join "" (hiccup/collect-strings b))))))
