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

(def addr "0x1234567890abcdef1234567890abcdef12345678")

(def live-monitoring
  {:account-address addr :status :live :reasons [] :epoch 4 :resync {:state :idle :attempt 0}})

(defn- state
  "Live monitoring for the connected account unless `execution` sets its own."
  [execution plan]
  {:wallet {:address addr}
   :vol-range {:execution (merge {:monitoring live-monitoring} execution) :frozen-review plan}})

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
                       "ladder cap is 100"))))

(deftest placing-is-busy-and-disabled-test
  (let [html (render {:enabled? true :max-ladder-usdc "150" :busy {[:place] true}} ready)
        b (hiccup/find-by-data-role html "vol-range-submit")]
    (is (true? (get-in b [1 :aria-busy])))
    (is (true? (get-in b [1 :disabled])))
    (is (= "Placing orders…" (str/join "" (hiccup/collect-strings b))))))

(deftest disabled-with-the-monitoring-note-until-live-updates-catch-up-test
  (doseq [m [nil
             (assoc live-monitoring :status :resyncing :reasons [:reconnected]
                    :resync {:state :required :attempt 0})
             (assoc live-monitoring :status :stale :reasons [:transport-down])
             (assoc live-monitoring :account-address "0xfedcba0987654321fedcba0987654321fedcba09")]]
    (let [html (render {:enabled? true :max-ladder-usdc "150" :monitoring m} ready)
          b (hiccup/find-by-data-role html "vol-range-submit")]
      (is (true? (get-in b [1 :disabled])) (pr-str m))
      (is (= "Placement waits for live order and fill updates to catch up."
             (str/join "" (hiccup/collect-strings (hiccup/find-by-data-role html "vol-range-submit-note"))))
          (pr-str m))))
  (testing "a tick gap alone does not disable the view (the executor applies it with real time)"
    (is (false? (get-in (hiccup/find-by-data-role (render {:enabled? true :monitoring (assoc live-monitoring :last-tick-ms 0)}
                                                          ready)
                                                  "vol-range-submit")
                        [1 :disabled])))))

(deftest a-monitoring-refusal-shows-the-refusal-copy-test
  (let [text "Nothing was placed: live order and fill updates were catching up. Try again in a moment."
        plan (assoc ready :plan-id "p1")]
    (doseq [last-result [{:op :place :status :refused :errors [:monitoring/resync-required] :plan-id "p1"}
                         {:op :place :status :not-placed :delivery :not-sent :phase :final-guard
                          :errors [:monitoring/stale] :error "[:monitoring/stale]" :plan-id "p1"}]]
      (let [html (render {:enabled? true :last last-result} plan)]
        (is (= text (str/join "" (hiccup/collect-strings (hiccup/find-by-data-role html "vol-range-submit-error"))))
            (pr-str last-result))))))
