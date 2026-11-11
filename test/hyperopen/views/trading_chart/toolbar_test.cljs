(ns hyperopen.views.trading-chart.toolbar-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.trading-chart.toolbar :as toolbar]))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- classes-from-tag [tag]
  (if (keyword? tag)
    (let [parts (str/split (name tag) #"\.")]
      (if (> (count parts) 1)
        (rest parts)
        []))
    []))

(defn- node-children [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- collect-all-classes [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))]
      (concat (classes-from-tag (first node))
              (class-values (:class attrs))
              (mapcat collect-all-classes (node-children node))))

    (seq? node)
    (mapcat collect-all-classes node)

    :else []))

(defn- class-strings [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) [class-attr]
    (sequential? class-attr) (mapcat class-strings class-attr)
    :else []))

(defn- collect-class-strings [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))]
      (concat (class-strings (:class attrs))
              (mapcat collect-class-strings (node-children node))))

    (seq? node)
    (mapcat collect-class-strings node)

    :else []))

(defn- spaced-class-string? [value]
  (and (string? value)
       (<= 2 (count (remove str/blank? (str/split value #"\s+"))))))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (or (when (pred node) node)
        (some #(find-first-node % pred) (node-children node)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- toolbar-state
  [chart-options]
  {:active-asset "BTC"
   :chart-options (merge {:timeframes-dropdown-visible false
                          :selected-timeframe :1d
                          :chart-type-dropdown-visible false
                          :selected-chart-type :candlestick
                          :indicators-dropdown-visible false
                          :active-indicators {}}
                         chart-options)})

(deftest chart-top-menu-owner-preserves-toolbar-output-test
  (let [menu (toolbar/chart-top-menu (toolbar-state {:indicators-dropdown-visible true
                                                     :volume-visible? true}))
        classes (set (collect-all-classes menu))
        selected-classes (set (collect-all-classes (find-first-node menu #(= [[:actions/select-chart-timeframe :1d]]
                                                                              (get-in % [1 :on :click])))))]
    (is (= [:5m :1h :1d] toolbar/main-timeframes))
    (is (= "chart-toolbar" (get-in menu [1 :data-parity-id])))
    (is (contains? classes "bg-base-100"))
    (is (contains? classes "min-w-0"))
    (is (contains? classes "z-[120]"))
    (is (contains? classes "isolate"))
    (is (contains? classes "opacity-100"))
    (is (contains? selected-classes "text-trading-green"))
    (is (not (contains? classes "bg-blue-600")))
    (is (not-any? spaced-class-string? (collect-class-strings menu))))

  (let [menu (toolbar/chart-top-menu (toolbar-state {:indicators-dropdown-visible true
                                                     :indicators-search-term "sm"}))
        search-node (find-first-node menu #(= "chart-indicators-search" (get-in % [1 :id])))]
    (is (some? search-node))
    (is (= [[:actions/update-indicators-search [:event.target/value]]]
           (get-in search-node [1 :on :input]))))

  (let [menu (toolbar/chart-top-menu (toolbar-state {:indicators-dropdown-visible true
                                                     :indicators-search-term ""}))
        add-node (find-first-node menu #(= [[:actions/add-indicator :sma {:period 20}]]
                                            (get-in % [1 :on :click])))]
    (is (some? add-node))
    (is (= :button (first add-node))))

  (let [menu (toolbar/chart-top-menu (toolbar-state {:indicators-dropdown-visible true
                                                     :active-indicators {:sma {:period 20}}}))
        remove-node (find-first-node menu #(= [[:actions/remove-indicator :sma]]
                                               (get-in % [1 :on :click])))]
    (is (some? remove-node))
    (is (= true (get-in remove-node [1 :aria-pressed]))))

  (let [hide-menu (toolbar/chart-top-menu (toolbar-state {:indicators-dropdown-visible true
                                                          :volume-visible? true}))
        show-menu (toolbar/chart-top-menu (toolbar-state {:indicators-dropdown-visible true
                                                          :volume-visible? false}))
        hide-node (find-first-node hide-menu #(= [[:actions/hide-volume-indicator]]
                                                 (get-in % [1 :on :click])))
        show-node (find-first-node show-menu #(= [[:actions/show-volume-indicator]]
                                                 (get-in % [1 :on :click])))]
    (is (some? hide-node))
    (is (= true (get-in hide-node [1 :aria-pressed])))
    (is (some? show-node))
    (is (= false (get-in show-node [1 :aria-pressed]))))

  (let [health {:generated-at-ms 5000
                :streams {["trades" "BTC" nil nil nil]
                          {:topic "trades"
                           :status :live
                           :subscribed? true
                           :last-payload-at-ms 4700
                           :stale-threshold-ms 10000}}}
        live-menu (toolbar/chart-top-menu (assoc (toolbar-state {})
                                                 :websocket-ui {:show-surface-freshness-cues? true}
                                                 :websocket {:health health}))
        hidden-menu (toolbar/chart-top-menu (assoc (toolbar-state {})
                                                   :websocket {:health health}))
        cue-node (find-first-node live-menu #(= "chart-freshness-cue" (get-in % [1 :data-role])))]
    (is (some? cue-node))
    (is (str/includes? (str/join " " (collect-strings cue-node)) "Last tick 300ms ago"))
    (is (nil? (find-first-node hidden-menu #(= "chart-freshness-cue" (get-in % [1 :data-role]))))))

  (let [idle-menu (toolbar/chart-top-menu {:active-asset "BTC"
                                           :websocket-ui {:show-surface-freshness-cues? true}
                                           :websocket {:health {:generated-at-ms 5000
                                                                :streams {["trades" "BTC" nil nil nil]
                                                                          {:topic "trades"
                                                                           :status :idle
                                                                           :subscribed? true
                                                                           :last-payload-at-ms nil
                                                                           :stale-threshold-ms 10000}}}}})
        cue-node (find-first-node idle-menu #(= "chart-freshness-cue" (get-in % [1 :data-role])))]
    (is (some? cue-node))
    (is (str/includes? (str/join " " (collect-strings cue-node)) "Waiting for first update...")))

  (let [menu (toolbar/chart-top-menu {})
        text (str/join " " (collect-strings menu))]
    (is (= "chart-toolbar" (get-in menu [1 :data-parity-id])))
    (is (not (str/includes? text "nil")))))

(defn- find-by-data-role [node role]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))]
      (if (= role (:data-role attrs))
        node
        (some #(find-by-data-role % role) (node-children node))))

    (seq? node)
    (some #(find-by-data-role % role) node)

    :else nil))

(deftest chart-trades-toggle-defaults-on-and-dispatches-the-shared-setting-action-test
  (let [default-toggle (find-by-data-role (toolbar/chart-top-menu {}) "chart-toolbar-trades-toggle")
        off-toggle (find-by-data-role (toolbar/chart-top-menu {:trading-settings {:show-chart-trades? false}})
                                      "chart-toolbar-trades-toggle")]
    (is (some? default-toggle))
    (is (= "true" (get-in default-toggle [1 :aria-pressed])))
    (is (= [[:actions/set-chart-trades-enabled false]]
           (get-in default-toggle [1 :on :click])))
    (is (= "false" (get-in off-toggle [1 :aria-pressed])))
    (is (= [[:actions/set-chart-trades-enabled true]]
           (get-in off-toggle [1 :on :click])))))

(defn- axis-unit-group [menu]
  (find-first-node menu #(= "chart-toolbar-axis-unit" (get-in % [1 :data-role]))))

(defn- axis-unit-button [menu unit]
  (find-first-node menu #(and (= "chart-toolbar-axis-unit-option" (get-in % [1 :data-role]))
                              (= unit (get-in % [1 :data-unit])))))

(def ^:private outcome-market
  {:market-type :outcome :coin "#9900010"})

(deftest axis-unit-control-only-on-outcome-markets-test
  (let [perp-menu (toolbar/chart-top-menu (toolbar-state {}))
        lagging-menu (toolbar/chart-top-menu (assoc (toolbar-state {}) :active-market outcome-market))]
    (is (nil? (axis-unit-group perp-menu)))
    (is (nil? (axis-unit-group lagging-menu))
        "an outcome market for another coin shows no control on a BTC chart"))
  (let [state (assoc (toolbar-state {}) :active-asset "#9900010" :active-market outcome-market)
        menu (toolbar/chart-top-menu state)
        group (axis-unit-group menu)
        percent (axis-unit-button menu "percent")
        cents (axis-unit-button menu "cents")]
    (is (= "group" (get-in group [1 :role])))
    (is (string? (get-in group [1 :aria-label])))
    (is (some #{"Axis"} (collect-strings group)))
    (is (= :button (first percent)))
    (is (= "Percent" (get-in percent [1 :aria-label])))
    (is (= "Cents" (get-in cents [1 :aria-label])))
    (is (= "true" (get-in percent [1 :aria-pressed])) "percent by default")
    (is (= "false" (get-in cents [1 :aria-pressed])))
    (is (= [[:actions/set-outcome-chart-unit :cents]] (get-in cents [1 :on :click])))
    (is (contains? (set (class-values (get-in cents [1 :class]))) "h-8"))
    (is (not-any? spaced-class-string? (collect-class-strings menu))))
  (let [state (-> (toolbar-state {:outcome-price-unit :cents})
                  (assoc :active-asset "#9900010" :active-market outcome-market))
        menu (toolbar/chart-top-menu state)]
    (is (= "true" (get-in (axis-unit-button menu "cents") [1 :aria-pressed])))
    (is (= "false" (get-in (axis-unit-button menu "percent") [1 :aria-pressed])))))

(defn- answer-option [id label]
  {:outcome-id id
   :label label
   :sides [{:coin (str "#" (* 10 id)) :side-index 0 :prevDayRaw "0.4"}
           {:coin (str "#" (inc (* 10 id))) :side-index 1}]})

(def ^:private question-market
  {:key "question:7"
   :market-type :outcome
   :outcome-kind :question
   :coin "#10"
   :selected-outcome-option-label "Albania"
   :question-options [(answer-option 1 "Albania") (answer-option 2 "Draw") (answer-option 3 "Finland")]})

(defn- chart-shows-button [menu mode]
  (find-first-node menu #(and (= "chart-toolbar-chart-shows-option" (get-in % [1 :data-role]))
                              (= mode (get-in % [1 :data-mode])))))

(deftest chart-shows-control-only-on-multi-answer-events-test
  (is (nil? (find-by-data-role (toolbar/chart-top-menu (toolbar-state {})) "chart-toolbar-chart-shows")))
  (is (nil? (find-by-data-role (toolbar/chart-top-menu (assoc (toolbar-state {})
                                                               :active-asset "#9900010"
                                                               :active-market outcome-market))
                               "chart-toolbar-chart-shows"))
      "a two-sided market has nothing to compare")
  (let [state (assoc (toolbar-state {:outcome-price-unit :cents}) :active-asset "#10" :active-market question-market)
        menu (toolbar/chart-top-menu state)
        group (find-by-data-role menu "chart-toolbar-chart-shows")
        single (chart-shows-button menu "single")
        all (chart-shows-button menu "all")]
    (is (= "group" (get-in group [1 :role])))
    (is (= "Chart shows" (get-in group [1 :aria-label])))
    (is (= ["Albania only"] (collect-strings single)))
    (is (= ["All answers"] (collect-strings all)))
    (is (= "true" (get-in single [1 :aria-pressed])) "the selected answer by default")
    (is (= "false" (get-in all [1 :aria-pressed])))
    (is (= [[:actions/set-outcome-chart-mode :all]] (get-in all [1 :on :click])))
    (is (some? (find-by-data-role menu "chart-toolbar-trades-toggle")) "candle tools stay in single mode")
    (is (= "true" (get-in (axis-unit-button menu "cents") [1 :aria-pressed])))))

(deftest all-answers-locks-the-axis-and-hides-candle-tools-test
  (let [state (assoc (toolbar-state {:outcome-price-unit :cents :outcome-chart-mode :all})
                     :active-asset "#10" :active-market question-market)
        menu (toolbar/chart-top-menu state)
        percent (axis-unit-button menu "percent")
        cents (axis-unit-button menu "cents")]
    (is (= "true" (get-in (chart-shows-button menu "all") [1 :aria-pressed])))
    (is (= [[:actions/set-outcome-chart-mode :single]]
           (get-in (chart-shows-button menu "single") [1 :on :click])))
    (is (= "true" (get-in percent [1 :aria-pressed])) "% shows pressed whatever unit is stored")
    (is (nil? (get-in percent [1 :on])) "% is inert: a click must not overwrite a stored ¢")
    (is (true? (get-in cents [1 :disabled])))
    (is (= "false" (get-in cents [1 :aria-pressed])))
    (is (= "Cents, not available when all answers are shown" (get-in cents [1 :aria-label])))
    (is (nil? (get-in cents [1 :on])))
    (is (nil? (find-by-data-role menu "chart-toolbar-trades-toggle")))
    (is (not (some #{"Indicators"} (collect-strings menu))))
    (is (not-any? spaced-class-string? (collect-class-strings menu))))
  (testing "a stored All answers mode on a market that cannot draw it shows single"
    (let [state (assoc (toolbar-state {:outcome-chart-mode :all}) :active-asset "#9900010" :active-market outcome-market)]
      (is (some? (find-by-data-role (toolbar/chart-top-menu state) "chart-toolbar-trades-toggle"))))))
