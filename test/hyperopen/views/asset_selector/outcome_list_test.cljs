(ns hyperopen.views.asset-selector.outcome-list-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.outcomes.test-support.fixture :as fixture]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.asset-selector.outcome-list :as outcome-list]))

(def ^:private now-ms (js/Date.UTC 2026 10 9 16 25))

(defn- model
  [active-tab]
  (outcome-list/list-model (markets/build-outcome-markets fixture/outcome-meta fixture/asset-ctxs)
                           active-tab
                           now-ms))

(defn- rendered
  [active-tab]
  (outcome-list/event-list {:sections (:sections (model active-tab))
                            :selected-market-key "outcome:27037"
                            :highlighted-market-key nil
                            :favorites #{}
                            :suppress-empty-state? false}))

(deftest event-rows-render-readable-events-with-answer-buttons-test
  (let [view (rendered :outcome)
        strings (hiccup/collect-strings view)
        rows (hiccup/find-all-nodes view #(= "outcome-event-row" (get-in % [1 :data-role])))
        answers (hiccup/find-all-nodes view #(= "outcome-answer" (get-in % [1 :data-role])))]
    (is (= 12 (count rows)))
    (is (some #{"Detroit Pistons vs Washington Wizards"} strings))
    (is (some #{"Fed — December 2026 decision"} strings))
    (is (empty? (filter #(str/includes? % "template") strings)))
    (testing "every answer is a real button with its own click handler"
      (is (seq answers))
      (is (every? #(= :button (first %)) answers))
      (is (every? #(fn? (get-in % [1 :on :click])) answers)))
    (testing "the selected event is marked"
      (is (= ["selected"]
             (->> rows
                  (filter #(= "outcome:27037" (get-in % [1 :data-market-key])))
                  (mapv #(get-in % [1 :data-row-state]))))))))

(deftest category-chips-filter-and-count-events-test
  (let [{:keys [sections counts]} (model :outcome-sports)
        chips (outcome-list/category-chip-row :outcome-sports counts)
        strings (hiccup/collect-strings chips)]
    ;; Day words depend on the runner's time zone; the listing test pins UTC.
    (is (= 3 (count sections)))
    (is (re-find #"· NBA$" (:title (first sections))))
    (is (= "Futures" (:title (last sections))))
    (is (= 12 (:outcome counts)))
    (is (some #{"Stocks & commodities"} strings))
    (is (some #{"3"} strings))
    (testing "legacy period tabs light the Crypto chip"
      (is (= #{"Crypto"}
             (->> (hiccup/find-all-nodes (outcome-list/category-chip-row :outcome-1d counts)
                                         #(true? (get-in % [1 :aria-selected])))
                  (into #{} (mapcat hiccup/direct-texts))))))))

(deftest empty-list-explains-itself-test
  (is (some #{"No events found"}
            (hiccup/collect-strings (outcome-list/event-list {:sections []
                                                              :suppress-empty-state? false})))))
