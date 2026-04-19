(ns hyperopen.views.trading-chart.toolbar-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
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
