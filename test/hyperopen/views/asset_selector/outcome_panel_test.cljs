(ns hyperopen.views.asset-selector.outcome-panel-test
  "The Outcome tab's full-height panel: sort, freshness, visible-row reporting,
   the load-failure states and the All tab's event rows."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.actions :as actions]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.outcomes.test-support.fixture :as fixture]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.asset-selector.layout :as layout]
            [hyperopen.views.asset-selector.outcome-list :as outcome-list]))

(def ^:private now-ms (js/Date.UTC 2026 10 9 16 25))

(defn- catalog
  []
  (markets/build-outcome-markets fixture/outcome-meta fixture/asset-ctxs))

(defn- model
  ([active-tab] (model active-tab nil))
  ([active-tab opts]
   (outcome-list/list-model (catalog) active-tab now-ms opts)))

(defn- by-role
  [view role]
  (hiccup/find-all-nodes view #(= role (get-in % [1 :data-role]))))

(deftest sort-row-offers-three-orders-and-dispatches-sort-test
  (let [m (model :outcome {:sort :ending-soon
                           :selector {:loading? false :phase :full :loaded-at-ms (- now-ms 120000)}})
        view (outcome-list/sort-and-freshness-row m)
        select (first (by-role view "outcome-sort-select"))
        options (hiccup/find-all-nodes view #(= :option (first %)))
        refresh (first (by-role view "outcome-refresh"))
        strings (hiccup/collect-strings view)]
    (is (= [[:actions/set-outcome-list-sort [:event.target/value]]] (get-in select [1 :on :change])))
    (is (= ["Most traded" "Ending soon" "By start time"] (mapv #(first (hiccup/direct-texts %)) options)))
    (is (= ["most-traded" "ending-soon" "start-time"] (mapv #(get-in % [1 :value]) options)))
    (is (= [false true false] (mapv #(boolean (get-in % [1 :selected])) options)))
    (testing "says how old the list is and offers Refresh"
      (is (some #{"Updated 2 min ago"} strings))
      (is (= [[:actions/refresh-asset-markets]] (get-in refresh [1 :on :click]))))))

(deftest panel-list-fills-the-panel-and-reports-visible-rows-test
  (let [view (outcome-list/event-list {:sections (:sections (model :outcome))
                                       :favorites #{}
                                       :panel? true})
        attrs (second view)]
    (is (= "outcome-event-list" (:data-role attrs)))
    (is (some #{"overflow-y-auto"} (:class attrs)))
    (is (some #{"flex-1"} (:class attrs)))
    (is (nil? (get-in attrs [:style :max-height])) "the panel, not a 560 px cap, sets the height")
    (is (fn? (get-in attrs [:on :scroll])))
    (is (fn? (:replicant/on-render attrs)))))

(deftest refresh-failure-with-a-loaded-list-offers-retry-above-the-rows-test
  (let [{:keys [load sections]} (model :outcome {:selector {:loading? false :phase :full :error "boom"}})
        view (outcome-list/event-list {:sections sections :favorites #{} :load load})
        banner (first (by-role view "outcome-refresh-failed"))]
    (is (some? banner))
    (is (seq (by-role view "outcome-event-row")))
    (is (= [[:actions/refresh-asset-markets]]
           (get-in (first (hiccup/find-all-nodes banner #(= :button (first %)))) [1 :on :click])))))

(deftest all-tab-shows-outcome-events-as-event-rows-test
  (let [m (model :outcome)
        block (outcome-list/all-tab-events-block m nil nil #{} false)
        rows (by-role block "outcome-event-row")
        more (first (by-role block "all-tab-show-outcomes"))]
    (is (= 5 (count rows)))
    (is (= 5 (count (outcome-list/all-tab-event-keys m))))
    (is (some #{"Outcome markets"} (hiccup/collect-strings block)))
    (is (= [[:actions/set-asset-selector-tab :outcome]] (get-in more [1 :on :click])))))

(defn- selector-props
  [active-tab search-term]
  {:visible? true
   :desktop? true
   :markets (catalog)
   :market-by-key (into {} (map (juxt :key identity)) (catalog))
   :active-tab active-tab
   :search-term search-term
   :sort-by :volume
   :sort-direction :desc
   :favorites #{}
   :loading? false
   :phase :full
   :outcome-opts {:selector {:loading? false :phase :full :loaded-at-ms (- (.now js/Date) 60000)}}})

(deftest desktop-outcome-tab-is-a-full-height-panel-with-pinned-header-test
  (let [view (layout/asset-selector-dropdown (selector-props :outcome ""))
        panel (first (by-role view "asset-selector-desktop-dropdown"))
        header (first (by-role view "outcome-panel-header"))
        list-node (first (by-role view "outcome-event-list"))]
    (is (= "outcome-panel" (get-in panel [1 :data-layout])))
    (is (some #{"fixed"} (get-in panel [1 :class])))
    (is (fn? (get-in panel [1 :replicant/on-render])) "sized to the room under the top bar")
    (is (some #{"shrink-0"} (get-in header [1 :class])) "search and filters stay pinned")
    (is (some #{"min-h-0"} (get-in list-node [1 :class])) "only the list scrolls")
    (is (seq (by-role view "outcome-sort-select")))
    (is (seq (by-role view "outcome-refresh")))))

(deftest other-tabs-keep-the-dropdown-test
  (let [view (layout/asset-selector-dropdown (selector-props :perps ""))
        panel (first (by-role view "asset-selector-desktop-dropdown"))]
    (is (= "dropdown" (get-in panel [1 :data-layout])))
    (is (empty? (by-role view "outcome-panel-header")))))

(deftest all-tab-search-finds-outcome-events-by-team-or-event-name-test
  (let [view (layout/asset-selector-dropdown (selector-props :all "pistons"))
        rows (by-role view "outcome-event-row")
        strings (hiccup/collect-strings view)]
    (is (seq rows))
    (is (some #{"Detroit Pistons vs Washington Wizards"} strings))))

(deftest toggle-asset-dropdown-reloads-a-stale-catalog-in-one-action-test
  (let [now (.now js/Date)
        refetch? #(= [:effects/fetch-asset-selector-markets] %)
        open (fn [loaded-at-ms]
               (actions/toggle-asset-dropdown
                {:asset-selector {:visible-dropdown nil :markets [{:key "perp:BTC"}] :phase :full
                                  :loaded-at-ms loaded-at-ms}}
                :asset-selector))
        stale (open (- now (* 10 60 1000)))
        fresh (open (- now 1000))]
    (is (= 1 (count (filter refetch? stale))))
    (is (= :effects/save-many (ffirst stale)))
    (is (empty? (filter refetch? fresh)))))
