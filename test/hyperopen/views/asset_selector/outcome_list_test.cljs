(ns hyperopen.views.asset-selector.outcome-list-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.outcomes.test-support.fixture :as fixture]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.asset-selector.outcome-list :as outcome-list]))

(def ^:private now-ms (js/Date.UTC 2026 10 9 16 25))

(defn- model
  ([active-tab] (model active-tab nil))
  ([active-tab opts]
   (outcome-list/list-model (markets/build-outcome-markets fixture/outcome-meta fixture/asset-ctxs)
                            active-tab
                            now-ms
                            opts)))

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
    (is (= 15 (count rows)))
    (testing "volume reads in shares"
      (is (some #{"227K shares"} strings)))
    (testing "no answer says last"
      (is (not-any? #{"last"} strings)))
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
    (is (= ["Most traded"] (mapv :title sections)))
    (is (= 3 (count (:rows (first sections)))))
    (testing "by start time keeps day and competition sections"
      ;; Day words depend on the runner's time zone; the listing test pins UTC.
      (let [by-start (:sections (model :outcome-sports {:sort :start-time}))]
        (is (= 3 (count by-start)))
        (is (re-find #"· NBA$" (:title (first by-start))))
        (is (= "Futures" (:title (last by-start))))))
    (is (= 15 (:outcome counts)))
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

(deftest failed-load-is-an-error-with-retry-test
  (let [{:keys [load]} (model :outcome {:selector {:loading? false :phase :bootstrap :error "Network error"}})
        view (outcome-list/event-list {:sections [] :suppress-empty-state? false :load load})
        strings (hiccup/collect-strings view)
        retry (first (hiccup/find-all-nodes view #(= "outcome-load-retry" (get-in % [1 :data-role]))))]
    (is (some #{"Couldn’t load outcome markets"} strings))
    (is (not-any? #{"No events found"} strings))
    (is (= [[:actions/refresh-asset-markets]] (get-in retry [1 :on :click])))))

(deftest failed-first-load-under-most-traded-shows-the-error-block-test
  (testing "the default sort yields one section with no rows: that is empty"
    (let [{:keys [load sections]} (outcome-list/list-model [] :outcome 0
                                                           {:sort :most-traded
                                                            :selector {:loading? false :phase :bootstrap
                                                                       :error "Network error"}})
          view (outcome-list/event-list {:sections sections :suppress-empty-state? false :load load})]
      (is (= 1 (count sections)))
      (is (seq (hiccup/find-all-nodes view #(= "outcome-load-error" (get-in % [1 :data-role])))))
      (is (empty? (hiccup/find-all-nodes view #(= "outcome-refresh-failed" (get-in % [1 :data-role]))))))))

(deftest answer-buttons-carry-the-quote-state-test
  (let [placeholder-row {:key "outcome:1" :title "Bitcoin above $76,000" :shape :yes-no :meta "Crypto" :venue "out"
                         :answers [{:label "Yes" :coin "#10" :side-index 0 :state :not-traded :muted? true
                                    :text "Not traded" :detail "bid 1.3¢ · ask 98.7¢"}
                                   {:label "No" :coin "#11" :side-index 1 :state :range :muted? true
                                    :text "60–70%"}]
                         :more 0
                         :flags [{:kind :order :text "Out of order"}]
                         :volume-text "—"}
        view (outcome-list/event-list {:sections [{:id "most-traded" :title "Most traded" :rows [placeholder-row]}]})
        answers (hiccup/find-all-nodes view #(= "outcome-answer" (get-in % [1 :data-role])))
        strings (hiccup/collect-strings view)]
    (is (= ["not-traded" "range"] (mapv #(get-in % [1 :data-quote-state]) answers)))
    (is (= "Yes · Not traded · bid 1.3¢ · ask 98.7¢" (get-in (first answers) [1 :title])))
    (is (some #{"Out of order"} strings))))
