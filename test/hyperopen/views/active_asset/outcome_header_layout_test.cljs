(ns hyperopen.views.active-asset.outcome-header-layout-test
  "Trade header for outcome markets: two lines for events with many answers,
   one number for Chance, Locked in dollars, Volume in shares."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.outcomes.test-support.fixture :as fixture]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.active-asset.outcome-header-vm :as header-vm]
            [hyperopen.views.active-asset.outcome-row :as outcome-row]))

(def ^:private now-ms (js/Date.UTC 2026 10 9 16 25))

(defn- row-vm
  [market]
  {:is-outcome true
   :icon-market market
   :dropdown-visible? false
   :missing-icons #{}
   :loaded-icons #{}
   :outcome-options (vec (:question-options market))
   :outcome-option-id (:outcome-option-id market)
   :outcome-option-ui {:open? false :query ""}
   :outcome-header (header-vm/header-model market nil now-ms)})

(defn- find-market
  [pred]
  (some #(when (pred %) %) (markets/build-outcome-markets fixture/outcome-meta fixture/asset-ctxs)))

(defn- strings-of
  [node]
  (set (hiccup/collect-strings node)))

(defn- by-role
  [view role]
  (first (hiccup/find-all-nodes view #(= role (get-in % [1 :data-role])))))

(deftest many-answer-event-takes-two-lines-test
  (let [market (find-market #(> (count (:question-options %)) 2))
        _ (is (some? market) "fixture has a multi-answer event")
        vm (row-vm market)
        view (outcome-row/desktop-row vm {:outcome-handlers {}})
        strings (strings-of view)]
    (is (outcome-row/multi-answer? vm))
    (is (= "two-line" (get-in view [1 :data-layout])))
    (testing "line one: the event, its venue and deadline; line two: the answer and its numbers"
      (let [line-1 (by-role view "outcome-header-line-event")
            line-2 (by-role view "outcome-header-line-answer")]
        (is (some? (by-role line-1 "outcome-market-name-hover-region")))
        (is (some? (by-role line-1 "outcome-header-venue")))
        (is (some? (by-role line-1 "outcome-header-clock")))
        (is (some? (by-role line-2 "outcome-header-chance")))
        (is (some? (by-role line-2 "outcome-header-price")))
        (is (some? (by-role line-2 "outcome-header-volume")))
        (is (some? (by-role line-2 "outcome-header-locked")))))
    (testing "labels"
      (is (contains? strings "Chance"))
      (is (contains? strings "Locked"))
      (is (contains? strings "Volume"))
      (is (not (contains? strings "Open interest"))))))

(deftest many-answer-chance-is-one-number-for-the-selected-answer-test
  (let [market (find-market #(> (count (:question-options %)) 2))
        {:keys [chance-text chances quote-state]} (header-vm/header-model market nil now-ms)
        view (outcome-row/desktop-row (row-vm market) {:outcome-handlers {}})
        chance (by-role view "outcome-header-chance")]
    (is (= 1 (count chances)))
    (is (some? quote-state))
    (is (re-matches #"[^·]*" chance-text) "no \"Yes 16% · 84% No\" pair")
    (is (contains? (strings-of chance) chance-text))))

(deftest two-team-game-keeps-one-line-and-both-teams-test
  (let [market (-> (find-market #(= "outcome:27037" (:key %)))
                   (markets/market-with-selected-outcome-coin "#270370"))
        view (outcome-row/desktop-row (row-vm market) {:outcome-handlers {}})]
    (is (= "one-line" (get-in view [1 :data-layout])))
    (is (some #(re-find #"DET .+ · .+ WAS" %) (strings-of view)))
    (testing "the name column takes what the stats leave, with tight gaps"
      (is (= "minmax(10rem, 1fr) repeat(6, minmax(0, max-content))"
             (get-in view [1 :style :grid-template-columns])))
      (is (some #{"gap-x-2"} (get-in view [1 :class])))
      (is (not-any? #{"md:gap-3" "gap-2"} (get-in view [1 :class]))))))

(deftest locked-and-volume-come-from-the-whole-event-test
  (let [market (find-market #(> (count (:question-options %)) 2))
        model (header-vm/header-model market nil now-ms)]
    (is (re-find #"shares$" (:volume-text model)) "volume reads in shares")
    (is (or (= "—" (:open-interest-text model)) (re-find #"^\$" (:open-interest-text model)))
        "locked reads in dollars")))

(deftest payout-currency-is-usdc-test
  (let [market (find-market #(> (count (:question-options %)) 2))
        tooltip (header-vm/tooltip-model market)]
    (when tooltip
      (is (re-find #"Payouts are in USDC" (:footer-label tooltip))))
    (is (= "USDC" (:quote market)))))

(deftest bar-model-carries-both-chances-test
  (let [market (-> (find-market #(= "outcome:27037" (:key %)))
                   (markets/market-with-selected-outcome-coin "#270370"))
        {:keys [title entries]} (header-vm/bar-model market nil)]
    (is (= "Detroit Pistons vs Washington Wizards" title))
    (is (= ["DET" "WAS"] (mapv :label entries)))
    (is (every? #(re-matches #"\d+%|.+" (:text %)) entries))))
