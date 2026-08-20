(ns hyperopen.views.trade.order-form-component-sections-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form-component-sections :as sections]
            [hyperopen.views.trade.order-form-type-extensions :as type-extensions]))

(defn- collect-nodes-by-tag [node tag]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))
          self (when (= tag (first node)) [node])]
      (into (or self [])
            (mapcat #(collect-nodes-by-tag % tag) children)))

    (seq? node)
    (mapcat #(collect-nodes-by-tag % tag) node)

    :else []))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (mapcat collect-strings children))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- input-node-by-aria-label [node aria-label]
  (->> (collect-nodes-by-tag node :input)
       (filter (fn [input-node]
                 (= aria-label (get-in input-node [1 :aria-label]))))
       first))

(def ^:private entry-callbacks
  {:on-close-dropdown [[:actions/close-pro-order-type-dropdown]]
   :on-select-entry-market [[:actions/select-order-entry-mode :market]]
   :on-select-entry-limit [[:actions/select-order-entry-mode :limit]]
   :on-toggle-dropdown [[:actions/toggle-pro-order-type-dropdown]]
   :on-dropdown-keydown [[:actions/handle-pro-order-type-dropdown-keydown [:event/key]]]
   :on-select-pro-order-type (fn [order-type]
                               [[:actions/select-pro-order-type order-type]])})

(deftest entry-mode-tabs-renders-open-and-closed-dropdown-states-test
  (let [closed-node (sections/entry-mode-tabs {:entry-mode :limit
                                               :type :limit
                                               :pro-dropdown-open? false
                                               :pro-tab-label "Pro"
                                               :pro-dropdown-options [:scale :twap]
                                               :order-type-label name}
                                              entry-callbacks)
        open-node (sections/entry-mode-tabs {:entry-mode :pro
                                             :type :scale
                                             :pro-dropdown-open? true
                                             :pro-tab-label "Scale"
                                             :pro-dropdown-options [:scale :twap]
                                             :order-type-label name}
                                            entry-callbacks)
        closed-overlay-count (->> (collect-nodes-by-tag closed-node :div)
                                  (filter #(contains? (set (get-in % [1 :class])) "fixed"))
                                  count)
        closed-panel (first (filter #(= "closed" (get-in % [1 :data-ui-state]))
                                    (collect-nodes-by-tag closed-node :div)))
        closed-indicator (first (filter #(= "entry-mode-active-indicator"
                                            (get-in % [1 :data-role]))
                                        (collect-nodes-by-tag closed-node :div)))
        open-overlays (->> (collect-nodes-by-tag open-node :div)
                           (filter #(contains? (set (get-in % [1 :class])) "fixed")))
        open-panel (first (filter #(= "open" (get-in % [1 :data-ui-state]))
                                  (collect-nodes-by-tag open-node :div)))
        option-buttons (->> (collect-nodes-by-tag open-node :button)
                            (filter #(= :actions/select-pro-order-type
                                        (ffirst (get-in % [1 :on :click])))))
        selected-option-classes (set (get-in (first option-buttons) [1 :class]))
        unselected-option-classes (set (get-in (second option-buttons) [1 :class]))]
    (is (= 0 closed-overlay-count))
    (is (= true (get-in closed-panel [1 :aria-hidden])))
    (is (= 1 (count open-overlays)))
    (is (= [[:actions/close-pro-order-type-dropdown]]
           (get-in (first open-overlays) [1 :on :click])))
    (is (= false (get-in open-panel [1 :aria-hidden])))

    (is (= 2 (count option-buttons)))
    (is (= "33.333333%"
           (get-in closed-indicator [1 :style :left])))
    (is (= "33.333333%"
           (get-in closed-indicator [1 :style :width])))
    (is (= "left 0.3s ease"
           (get-in closed-indicator [1 :style :transition])))
    (is (= [[:actions/select-pro-order-type :scale]]
           (get-in (first option-buttons) [1 :on :click])))
    (is (= [[:actions/select-pro-order-type :twap]]
           (get-in (second option-buttons) [1 :on :click])))
    (is (contains? selected-option-classes "bg-base-200"))
    (is (contains? unselected-option-classes "hover:bg-base-200"))))

(deftest tp-sl-panel-renders-hyperliquid-style-price-and-gain-loss-rows-test
  (let [node (sections/tp-sl-panel {:form {:tp {:trigger "3000"}
                                           :sl {:trigger "2900"}}
                                    :unit :usd
                                    :unit-dropdown-open? false
                                    :tp-offset "150"
                                    :sl-offset "75"
                                    :tp-offset-disabled? false
                                    :sl-offset-disabled? false}
                                   {:on-set-tp-trigger [[:actions/tp-trigger [:event.target/value]]]
                                    :on-set-tp-offset [[:actions/tp-offset [:event.target/value]]]
                                    :on-set-sl-trigger [[:actions/sl-trigger [:event.target/value]]]
                                    :on-set-sl-offset [[:actions/sl-offset [:event.target/value]]]
                                    :on-toggle-unit-dropdown [[:actions/toggle-tpsl-unit-dropdown]]
                                    :on-close-unit-dropdown [[:actions/close-tpsl-unit-dropdown]]
                                    :on-unit-dropdown-keydown [[:actions/handle-tpsl-unit-dropdown-keydown [:event/key]]]
                                    :on-select-tpsl-unit (fn [unit]
                                                           [[:actions/close-tpsl-unit-dropdown]
                                                            [:actions/update-order-form [:tpsl :unit] unit]])})
        labels (set (collect-strings node))
        unit-triggers (filter #(re-find #"^TP/SL gain-loss unit:" (or (get-in % [1 :aria-label]) ""))
                              (collect-nodes-by-tag node :button))
        tp-price-input (input-node-by-aria-label node "TP Price")
        gain-input (input-node-by-aria-label node "Gain")
        sl-price-input (input-node-by-aria-label node "SL Price")
        loss-input (input-node-by-aria-label node "Loss")
        gap-10-containers (->> (collect-nodes-by-tag node :div)
                               (map second)
                               (map :class)
                               (filter #(and (coll? %)
                                             (contains? (set %) "gap-[10px]")))
                               count)
        tp-price-classes (set (get-in tp-price-input [1 :class]))
        gain-classes (set (get-in gain-input [1 :class]))]
    (is (contains? labels "TP"))
    (is (contains? labels "Gain"))
    (is (contains? labels "SL"))
    (is (contains? labels "Loss"))
    (is (not (contains? labels "Enable TP")))
    (is (not (contains? labels "Enable SL")))
    (is (some? tp-price-input))
    (is (some? gain-input))
    (is (some? sl-price-input))
    (is (some? loss-input))
    (is (>= gap-10-containers 3))
    (is (contains? tp-price-classes "min-w-0"))
    (is (contains? gain-classes "min-w-0"))
    (is (not (contains? tp-price-classes "pl-24")))
    (is (= 0 (count (collect-nodes-by-tag node :select))))
    (is (= 2 (count unit-triggers)))
    (is (every? #(= [[:actions/toggle-tpsl-unit-dropdown]]
                    (get-in % [1 :on :click]))
                unit-triggers))
    (is (every? #(= [[:actions/handle-tpsl-unit-dropdown-keydown [:event/key]]]
                    (get-in % [1 :on :keydown]))
                unit-triggers))
    (is (= "$: profit/loss in USDC."
           (get-in (first unit-triggers) [1 :title])))))

(deftest tp-sl-panel-open-unit-menu-renders-overlay-and-options-test
  (let [node (sections/tp-sl-panel {:form {:tp {:trigger "3000"}
                                           :sl {:trigger "2900"}}
                                    :unit :roe-percent
                                    :unit-dropdown-open? true
                                    :tp-offset "150"
                                    :sl-offset "75"
                                    :tp-offset-disabled? false
                                    :sl-offset-disabled? false}
                                   {:on-set-tp-trigger [[:actions/tp-trigger [:event.target/value]]]
                                    :on-set-tp-offset [[:actions/tp-offset [:event.target/value]]]
                                    :on-set-sl-trigger [[:actions/sl-trigger [:event.target/value]]]
                                    :on-set-sl-offset [[:actions/sl-offset [:event.target/value]]]
                                    :on-toggle-unit-dropdown [[:actions/toggle-tpsl-unit-dropdown]]
                                    :on-close-unit-dropdown [[:actions/close-tpsl-unit-dropdown]]
                                    :on-unit-dropdown-keydown [[:actions/handle-tpsl-unit-dropdown-keydown [:event/key]]]
                                    :on-select-tpsl-unit (fn [unit]
                                                           [[:actions/close-tpsl-unit-dropdown]
                                                            [:actions/update-order-form [:tpsl :unit] unit]])})
        overlay (first (filter #(= "Close TP/SL unit menu" (get-in % [1 :aria-label]))
                               (collect-nodes-by-tag node :button)))
        menu (first (filter #(= "TP/SL gain-loss unit options" (get-in % [1 :aria-label]))
                            (collect-nodes-by-tag node :div)))
        options (filter #(= "option" (get-in % [1 :role]))
                        (collect-nodes-by-tag node :button))
        usd-option (nth options 0)
        roe-option (nth options 1)
        position-option (nth options 2)]
    (is (= [[:actions/close-tpsl-unit-dropdown]]
           (get-in overlay [1 :on :click])))
    (is (= 3 (count options)))
    (is (= [[:actions/close-tpsl-unit-dropdown]
            [:actions/update-order-form [:tpsl :unit] :usd]]
           (get-in usd-option [1 :on :click])))
    (is (= [[:actions/close-tpsl-unit-dropdown]
            [:actions/update-order-form [:tpsl :unit] :position-percent]]
           (get-in position-option [1 :on :click])))
    (is (contains? (set (collect-strings roe-option)) "%(E)"))
    (is (contains? (set (collect-strings position-option)) "%(P)"))
    (is (= "%(E): percent of margin/equity used (ROE)."
           (get-in roe-option [1 :title])))
    (is (true? (get-in roe-option [1 :aria-selected])))
    (is (= "open" (get-in menu [1 :data-ui-state])))
    (is (false? (get-in menu [1 :aria-hidden])))))

(deftest tif-inline-control-renders-custom-trigger-caret-and-dispatches-toggle-test
  (let [node (sections/tif-inline-control {:tif :ioc}
                                          {:dropdown-open? false
                                           :on-toggle-dropdown [[:actions/toggle-tif-dropdown]]
                                           :on-close-dropdown [[:actions/close-tif-dropdown]]
                                           :on-dropdown-keydown [[:actions/handle-tif-dropdown-keydown [:event/key]]]
                                           :on-select-tif (fn [tif]
                                                            [[:actions/close-tif-dropdown]
                                                             [:actions/update-order-form [:tif] tif]])})
        button-nodes (collect-nodes-by-tag node :button)
        trigger (first (filter #(re-find #"^Time in force:" (or (get-in % [1 :aria-label]) "")) button-nodes))
        chevron (first (collect-nodes-by-tag node :svg))
        menu (first (filter #(= "TIF options" (get-in % [1 :aria-label]))
                            (collect-nodes-by-tag node :div)))]
    (is (some? trigger))
    (is (contains? (set (collect-strings trigger)) "IOC"))
    (is (= [[:actions/toggle-tif-dropdown]]
           (get-in trigger [1 :on :click])))
    (is (= [[:actions/handle-tif-dropdown-keydown [:event/key]]]
           (get-in trigger [1 :on :keydown])))
    (is (contains? (set (get-in chevron [1 :class])) "rotate-0"))
    (is (= "closed" (get-in menu [1 :data-ui-state])))
    (is (true? (get-in menu [1 :aria-hidden])))))

(deftest tif-inline-control-renders-open-menu-overlay-and-option-actions-test
  (let [node (sections/tif-inline-control {:tif :gtc}
                                          {:dropdown-open? true
                                           :on-toggle-dropdown [[:actions/toggle-tif-dropdown]]
                                           :on-close-dropdown [[:actions/close-tif-dropdown]]
                                           :on-dropdown-keydown [[:actions/handle-tif-dropdown-keydown [:event/key]]]
                                           :on-select-tif (fn [tif]
                                                            [[:actions/close-tif-dropdown]
                                                             [:actions/update-order-form [:tif] tif]])})
        button-nodes (collect-nodes-by-tag node :button)
        overlay (first (filter #(= "Close TIF menu" (get-in % [1 :aria-label])) button-nodes))
        options (filter #(= "option" (get-in % [1 :role])) button-nodes)
        selected-option (first (filter #(contains? (set (collect-strings %)) "GTC") options))
        ioc-option (first (filter #(contains? (set (collect-strings %)) "IOC") options))
        chevron (first (collect-nodes-by-tag node :svg))
        menu (first (filter #(= "TIF options" (get-in % [1 :aria-label]))
                            (collect-nodes-by-tag node :div)))]
    (is (= [[:actions/close-tif-dropdown]]
           (get-in overlay [1 :on :click])))
    (is (= 3 (count options)))
    (is (= [[:actions/close-tif-dropdown]
            [:actions/update-order-form [:tif] :ioc]]
           (get-in ioc-option [1 :on :click])))
    (is (true? (get-in selected-option [1 :aria-selected])))
    (is (contains? (set (get-in selected-option [1 :class])) "text-ho-text"))
    (is (contains? (set (get-in chevron [1 :class])) "rotate-180"))
    (is (= "open" (get-in menu [1 :data-ui-state])))
    (is (false? (get-in menu [1 :aria-hidden])))))

(def ^:private twap-presets
  [{:key :15m :label "15m" :minutes 15 :days 0 :hours 0}
   {:key :30m :label "30m" :minutes 30 :days 0 :hours 0}
   {:key :4h :label "4h" :minutes 0 :days 0 :hours 4}
   {:key :1d :label "1d" :minutes 0 :days 1 :hours 0}])

(def ^:private known-schedule
  {:presets twap-presets
   :preset-key :30m
   :custom? false
   :runtime-window "5m - 7d"
   :known? true
   :verb "Buys"
   :base-symbol "BTC"
   :runtime-phrase "30 minutes"
   :interval-phrase "30 seconds"
   :order-count 61
   :order-notional "$611"
   :slice-notional "$10.02"
   :slice-size "0.0491 BTC"})

(def ^:private empty-schedule
  (assoc known-schedule
         :known? false :order-count nil :order-notional nil
         :slice-notional nil :slice-size nil :interval-phrase nil))

(def ^:private no-guards
  {:stop-price-label "Max Price" :summary "none set" :any-set? false})

(def ^:private twap-section-callbacks
  {:on-set-twap-days [[:actions/twap-days [:event.target/value]]]
   :on-set-twap-hours [[:actions/twap-hours [:event.target/value]]]
   :on-set-twap-minutes [[:actions/twap-minutes [:event.target/value]]]
   :on-toggle-twap-randomize [[:actions/twap-randomize [:event.target/checked]]]
   :on-set-twap-trigger-price [[:actions/twap-trigger-px [:event.target/value]]]
   :on-set-twap-stop-price [[:actions/twap-stop-px [:event.target/value]]]
   :on-select-twap-custom-runtime [[:actions/twap-custom [:event.target/checked]]]
   :on-select-twap-runtime-preset (fn [preset]
                                    [[:actions/twap-preset (:key preset)]])
   :twap-schedule known-schedule
   :twap-guards no-guards})

(defn- twap-section-node
  ([] (twap-section-node {} {}))
  ([twap-form extra-callbacks]
   (first (type-extensions/render-order-type-sections
           :twap
           {:twap (merge {:days 0 :hours 0 :minutes 30 :randomize false}
                         twap-form)}
           (merge twap-section-callbacks extra-callbacks)))))

(deftest twap-section-offers-runtime-presets-instead-of-three-fields-test
  (let [node (twap-section-node)
        labels (set (collect-strings node))]
    (is (contains? labels "Runtime"))
    (doseq [preset ["15m" "30m" "4h" "1d" "Custom"]]
      (is (contains? labels preset)))
    ;; The heading states the venue's allowed window; the sentence below carries the
    ;; resolved schedule, so it is not repeated here.
    (is (contains? labels "5m - 7d"))
    ;; The D/H/M fields stay out of the way until Custom is chosen.
    (is (nil? (input-node-by-aria-label node "Days")))))

(deftest twap-section-custom-runtime-reveals-day-hour-minute-fields-test
  (let [node (twap-section-node {} {:twap-schedule (assoc known-schedule
                                                          :custom? true
                                                          :preset-key :custom)})
        days-input (input-node-by-aria-label node "Days")
        hours-input (input-node-by-aria-label node "Hours")
        minutes-input (input-node-by-aria-label node "Minutes")]
    (is (some? days-input))
    (is (some? hours-input))
    (is (some? minutes-input))
    (is (= [[:actions/twap-days [:event.target/value]]]
           (get-in days-input [1 :on :input])))
    (is (= [[:actions/twap-minutes [:event.target/value]]]
           (get-in minutes-input [1 :on :input])))))

(deftest twap-section-states-the-schedule-as-a-sentence-test
  (let [labels (set (collect-strings (twap-section-node)))]
    (is (contains? labels "Buys"))
    (is (contains? labels "$611"))
    (is (contains? labels "61"))
    (is (contains? labels "30 seconds"))
    (is (contains? labels "30 minutes"))
    (is (contains? labels "Per slice"))
    (is (contains? labels "0.0491 BTC · $10.02"))
    ;; The four-row estimate block is gone.
    (is (not (contains? labels "Every")))
    (is (not (contains? labels "Slices")))
    (is (not (contains? labels "Per Slice")))))

(deftest twap-section-asks-for-a-size-rather-than-guessing-test
  (let [labels (set (collect-strings
                     (twap-section-node {} {:twap-schedule empty-schedule})))]
    (is (contains? labels ". Enter a size to see the pieces."))
    ;; No invented count, and no "up to N" bound to interpret.
    (is (not (contains? labels "61")))))

(deftest twap-section-randomize-states-its-magnitude-test
  (let [node (twap-section-node)
        labels (set (collect-strings node))
        checkbox (first (filter #(= "checkbox" (get-in % [1 :type]))
                                (collect-nodes-by-tag node :input)))]
    (is (contains? labels "Randomize slice size"))
    (is (contains? labels "±20%"))
    (is (= [[:actions/twap-randomize [:event.target/checked]]]
           (get-in checkbox [1 :on :change])))))

(deftest twap-price-guards-state-their-value-when-collapsed-test
  (let [closed (twap-section-node)
        details-attrs (fn [node] (get-in (first (collect-nodes-by-tag node :details)) [1]))]
    (is (contains? (set (collect-strings closed)) "Price guards"))
    (is (contains? (set (collect-strings closed)) "none set"))
    (is (nil? (:open (details-attrs closed))))
    ;; A guard that is set opens the panel and says so on the summary line.
    (let [open (twap-section-node {:trigger-px "65000"}
                                  {:twap-guards (assoc no-guards
                                                       :summary "starts 65,000"
                                                       :any-set? true)})]
      (is (true? (:open (details-attrs open))))
      (is (contains? (set (collect-strings open)) "starts 65,000"))
      (is (= "trade-twap-price-guards" (:replicant/key (details-attrs open)))))))

(deftest twap-price-guards-name-the-kill-switch-and-flip-with-the-side-test
  (let [buy (twap-section-node {} {:twap-guards (assoc no-guards :any-set? true)})
        sell (twap-section-node {} {:twap-guards (assoc no-guards
                                                        :stop-price-label "Min Price"
                                                        :any-set? true)})
        buy-labels (set (collect-strings buy))]
    (is (contains? buy-labels "KILL SWITCH"))
    (is (contains? buy-labels "Max Price"))
    (is (some? (input-node-by-aria-label buy "Max Price")))
    (is (some? (input-node-by-aria-label buy "Trigger Price")))
    ;; The tag and the warning never change; only the field label does.
    (is (contains? (set (collect-strings sell)) "Min Price"))
    (is (contains? (set (collect-strings sell)) "KILL SWITCH"))
    (is (some? (input-node-by-aria-label sell "Min Price")))
    (is (not (contains? (set (collect-strings sell)) "Max Price")))))

(deftest twap-price-guards-draw-the-band-around-the-mark-test
  (let [band {:mark-label "MARK 68,412" :mark-pct 49
              :trigger-label "STARTS 65,000" :trigger-pct 11
              :stop-label "STOPS 72,000" :stop-pct 89
              :range-from 11 :range-to 89}
        node (twap-section-node {} {:twap-guards (assoc no-guards
                                                        :any-set? true
                                                        :band band)})
        labels (set (collect-strings node))]
    (is (contains? labels "STARTS 65,000"))
    (is (contains? labels "MARK 68,412"))
    (is (contains? labels "STOPS 72,000"))
    (is (contains? labels "order works inside this band"))
    ;; Without a band there is no empty axis left behind.
    (is (not (contains? (set (collect-strings (twap-section-node)))
                        "order works inside this band")))))

(deftest section-module-delegates-to-type-extensions-test
  (with-redefs [type-extensions/render-order-type-sections
                (fn [order-type form callbacks]
                  [:delegated order-type form callbacks])
                type-extensions/supported-order-type-sections
                (fn [] #{:trigger :scale :twap})]
    (is (= [:delegated :scale {:x 1} {:on true}]
           (sections/render-order-type-sections :scale {:x 1} {:on true})))
    (is (= #{:trigger :scale :twap}
           (sections/supported-order-type-sections)))))
