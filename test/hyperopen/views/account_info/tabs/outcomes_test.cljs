(ns hyperopen.views.account-info.tabs.outcomes-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.projections.outcomes :as outcome-projections]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.tabs.outcomes :as outcomes-tab]))

(def ^:private det-row
  {:key "outcome-#270370"
   :title "Detroit Pistons vs Washington Wizards"
   :market-key "outcome:27037"
   :side-coin "#270370"
   :side-name "DET"
   :position-label "DET"
   :market-meta "NBA"
   :venue "Hyperliquid"
   :clock {:kind :starts :at-ms (js/Date.UTC 2026 10 10 0 0)}
   :side-index 0
   :outcome-id 27037
   :size 122
   :entry-price 0.81902
   :cost 99.9204
   :position-value 99.9
   :value-after-fees 99.76
   :value-basis :bid
   :fee-known? true
   :if-right 121.83})

(def ^:private was-row
  (assoc det-row
         :key "outcome-#270371"
         :side-coin "#270371"
         :side-name "WAS"
         :position-label "WAS"
         :side-index 1
         :size 40
         :entry-price 0.185
         :cost 7.4
         :value-after-fees 7.23
         :if-right 39.94))

(defn- strings
  [content]
  (set (hiccup/collect-strings content)))

(deftest positions-are-grouped-by-event-and-priced-after-fees-test
  (let [content (outcomes-tab/outcomes-tab-content {:outcomes [det-row was-row]
                                                    :now-ms (js/Date.UTC 2026 10 9 16 25)})
        all (strings content)]
    (doseq [label ["Position" "Shares" "Avg price" "Cost" "Value now" "If right" "Action"]]
      (is (contains? all label) label))
    (is (contains? all "Detroit Pistons vs Washington Wizards"))
    (is (contains? all "NBA · starts in 7h 35m"))
    (is (contains? all "DET"))
    (is (contains? all "122"))
    (is (contains? all "81.902¢"))
    (is (contains? all "$99.92"))
    (is (contains? all "$99.76"))
    (is (contains? all "$121.83"))
    (is (contains? all "Cash out"))
    (is (not-any? #(re-find #"#270370|0\.81902" %) all))))

(deftest pair-row-and-totals-test
  (let [groups (outcome-projections/group-by-event [det-row was-row])
        pair (:pair (first groups))
        content (outcomes-tab/outcomes-tab-content {:outcomes [det-row was-row]})
        all (strings content)]
    (is (= 40 (:pairs pair)))
    (is (= "40 pairs · worth $40.00 whoever wins" (outcomes-tab/pair-text pair)))
    (is (= "Merge for $39.94" (outcomes-tab/merge-label pair)))
    (is (contains? all "40 pairs · worth $40.00 whoever wins"))
    (is (contains? all "Merge for $39.94"))
    (is (contains? all "Total"))
    (is (contains? all "$107.32"))
    (is (contains? all "$106.99"))
    (is (contains? all outcomes-tab/footnote))))

(deftest cash-out-is-one-action-that-skips-the-perp-popover-test
  (let [content (outcomes-tab/outcomes-tab-content {:outcomes [det-row]})
        button (hiccup/find-first-node content #(= "outcome-cash-out" (get-in % [1 :data-role])))
        click (get-in button [1 :on :click])]
    (is (= [[:actions/open-outcome-cash-out "outcome:27037" "#270370" 122]] click))
    (is (nil? (get-in button [1 :data-position-reduce-trigger])))))

(deftest merge-button-is-one-action-test
  (let [content (outcomes-tab/outcomes-tab-content {:outcomes [det-row was-row]})
        button (hiccup/find-first-node content #(= "outcome-merge" (get-in % [1 :data-role])))]
    (is (= [[:actions/merge-outcome-pairs "outcome:27037" "#270370" "#270371" 40]]
           (get-in button [1 :on :click])))))

(deftest read-only-mode-omits-actions-test
  (let [content (outcomes-tab/outcomes-tab-content {:outcomes [det-row was-row] :read-only? true})]
    (is (nil? (hiccup/find-first-node content #(= "outcome-cash-out" (get-in % [1 :data-role])))))
    (is (nil? (hiccup/find-first-node content #(= "outcome-merge" (get-in % [1 :data-role])))))))

(deftest event-title-click-is-one-action-test
  (let [content (outcomes-tab/outcomes-tab-content {:outcomes [det-row]})
        button (hiccup/find-first-node content #(= "outcome-market-select" (get-in % [1 :data-role])))]
    (is (= [[:actions/open-outcome-market "outcome:27037" "#270370"]]
           (get-in button [1 :on :click])))))

(deftest unconfirmed-venue-fee-is-said-not-printed-test
  (let [row (assoc det-row :fee-known? false :value-after-fees 99.9 :if-right 122)
        all (strings (outcomes-tab/outcomes-tab-content {:outcomes [row]}))]
    (is (contains? all "$99.90 (fee unconfirmed)"))
    (is (contains? all "$122.00 (fee unconfirmed)"))))

(deftest value-priced-at-the-last-mark-is-marked-approximate-test
  (is (= "~$99.76" (outcomes-tab/value-now-text (assoc det-row :value-basis :mark)))))

(deftest outcomes-tab-empty-state-is-specific-test
  (is (contains? (strings (outcomes-tab/outcomes-tab-content {:outcomes []}))
                 "No active outcomes")))
