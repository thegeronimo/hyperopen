(ns hyperopen.views.footer-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.config :as app-config]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.footer-view :as footer-view]))

(defn- find-node [pred node]
  (cond
    (vector? node)
    (or (when (pred node) node)
        (some #(find-node pred %) (rest node)))

    (seq? node)
    (some #(find-node pred %) node)

    :else nil))

(defn- count-nodes [pred node]
  (cond
    (vector? node)
    (+ (if (pred node) 1 0)
       (reduce + (map #(count-nodes pred %) (rest node))))

    (seq? node)
    (reduce + (map #(count-nodes pred %) node))

    :else 0))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- root-class-set [node]
  (let [attrs (when (and (vector? node) (map? (second node)))
                (second node))]
    (set (class-values (:class attrs)))))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (rest node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- node-text [node]
  (str/join " " (collect-strings node)))

(defn- with-browser-connection
  [connection f]
  (let [navigator-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis "navigator")
        original-navigator (or (some-> navigator-descriptor .-value)
                               (.-navigator js/globalThis))
        navigator* (or original-navigator #js {})
        had-navigator? (some? navigator-descriptor)
        connection-descriptor (js/Object.getOwnPropertyDescriptor navigator* "connection")
        had-connection? (some? connection-descriptor)]
    (when-not had-navigator?
      (js/Object.defineProperty js/globalThis
                                "navigator"
                                #js {:value navigator*
                                     :configurable true
                                     :writable true}))
    (js/Object.defineProperty navigator*
                              "connection"
                              #js {:value connection
                                   :configurable true
                                   :writable true})
    (try
      (f)
      (finally
        (if had-connection?
          (js/Object.defineProperty navigator* "connection" connection-descriptor)
          (js/Reflect.deleteProperty navigator* "connection"))
        (if had-navigator?
          (js/Object.defineProperty js/globalThis "navigator" navigator-descriptor)
          (js/Reflect.deleteProperty js/globalThis "navigator"))))))

(defn- find-pill [view]
  (find-node #(and (vector? %)
                   (= :button (first %))
                   (= "footer-connection-meter-button"
                      (get-in % [1 :data-role]))
                   (= [[:actions/toggle-ws-diagnostics]]
                      (get-in % [1 :on :click])))
             view))

(defn- pill-label-node [pill]
  (nth pill 3 nil))

(defn- meter-bar-count [view]
  (count-nodes #(and (vector? %)
                     (= "footer-connection-meter-bar"
                        (get-in % [1 :data-role])))
               view))

(defn- meter-active-bar-count [view]
  (count-nodes #(and (vector? %)
                     (= "footer-connection-meter-bar"
                        (get-in % [1 :data-role]))
                     (= "true" (get-in % [1 :data-active])))
               view))

(defn- find-surface-freshness-toggle [view]
  (find-node #(and (vector? %)
                   (= "surface-freshness-toggle"
                      (get-in % [1 :data-role])))
             view))

(defn- find-node-by-data-role
  [node data-role]
  (find-node #(and (vector? %)
                   (= data-role (get-in % [1 :data-role])))
             node))

(defn- count-nodes-by-data-role
  [node data-role]
  (count-nodes #(and (vector? %)
                     (= data-role (get-in % [1 :data-role])))
               node))

(defn- button-tag? [node]
  (and (keyword? node)
       (str/starts-with? (name node) "button")))

(defn- base-health []
  {:generated-at-ms 10000
   :transport {:state :connected
               :freshness :live
               :last-recv-at-ms 9500
               :expected-traffic? true
               :attempt 2
               :last-close {:code 1006
                            :reason "abnormal"
                            :at-ms 9000}}
   :groups {:orders_oms {:worst-status :idle}
            :market_data {:worst-status :live}
            :account {:worst-status :n-a}}
   :streams {["trades" "BTC" nil nil nil]
             {:group :market_data
              :topic "trades"
              :subscribed? true
              :status :live
              :last-payload-at-ms 5000
              :stale-threshold-ms 10000
              :descriptor {:type "trades" :coin "BTC"}
              :message-count 3}}})

(defn- base-state []
  {:websocket {:health (base-health)}
   :websocket-ui {:diagnostics-open? false
                  :reveal-sensitive? false
                  :copy-status nil
                  :reconnect-cooldown-until-ms nil}})

(deftest status-meter-is-snapshot-driven-and-retry-button-removed-test
  (let [state (assoc-in (base-state) [:websocket :health :groups :orders_oms :worst-status] :offline)
        view (footer-view/footer-view state)
        pill (find-pill view)
        retry-btn (find-node #(and (vector? %)
                                   (= :button (first %))
                                   (= "Retry" (last %)))
                             view)]
    (is (some? pill))
    (is (str/includes? (node-text pill) "Offline"))
    (is (nil? retry-btn))))

(deftest status-meter-uses-deterministic-group-precedence-test
  (let [state (-> (base-state)
                  (assoc-in [:websocket :health :groups :orders_oms :worst-status] :idle)
                  (assoc-in [:websocket :health :groups :market_data :worst-status] :delayed)
                  (assoc-in [:websocket :health :groups :account :worst-status] :offline))
        view (footer-view/footer-view state)
        pill (find-pill view)]
    (is (some? pill))
    (is (str/includes? (node-text pill) "Delayed"))))

(deftest status-meter-falls-back-to-transport-when-groups-are-neutral-test
  (let [state (-> (base-state)
                  (assoc-in [:websocket :health :groups :orders_oms :worst-status] :idle)
                  (assoc-in [:websocket :health :groups :market_data :worst-status] :n-a)
                  (assoc-in [:websocket :health :groups :account :worst-status] :idle)
                  (assoc-in [:websocket :health :transport :freshness] :offline))
        view (footer-view/footer-view state)
        pill (find-pill view)]
    (is (some? pill))
    (is (str/includes? (node-text pill) "Offline"))))

(deftest status-meter-shows-online-for-live-state-test
  (let [view (footer-view/footer-view (base-state))
        pill (find-pill view)]
    (is (some? pill))
    (is (str/includes? (node-text pill) "Online"))
    (is (not (str/includes? (node-text pill) "500ms")))))

(deftest status-meter-shows-latency-only-when-freshness-labels-enabled-test
  (let [off-view (footer-view/footer-view (base-state))
        on-view (footer-view/footer-view
                 (assoc-in (base-state)
                           [:websocket-ui :show-surface-freshness-cues?]
                           true))]
    (is (not (str/includes? (node-text (find-pill off-view)) "500ms")))
    (is (str/includes? (node-text (find-pill on-view)) "500ms"))))

(deftest status-meter-button-has-no-border-or-shaded-background-test
  (let [view (footer-view/footer-view (base-state))
        pill (find-pill view)
        first-bar (find-node-by-data-role pill "footer-connection-meter-bar")
        classes (set (class-values (get-in pill [1 :class])))
        bar-classes (set (class-values (get-in first-bar [1 :class])))]
    (is (some? pill))
    (is (some? first-bar))
    (is (not (contains? classes "border")))
    (is (not (contains? classes "border-success/50")))
    (is (not (contains? classes "border-warning/50")))
    (is (not (contains? classes "border-error/50")))
    (is (not (contains? classes "bg-success/10")))
    (is (not (contains? classes "bg-warning/10")))
    (is (not (contains? classes "bg-error/10")))
    (is (not (contains? classes "transition-colors")))
    (is (not (contains? bar-classes "transition-colors")))
    (is (not (contains? bar-classes "duration-150")))))

(deftest status-meter-button-bottom-aligns-label-with-bars-test
  (let [view (footer-view/footer-view (base-state))
        pill (find-pill view)
        classes (set (class-values (get-in pill [1 :class])))
        label-node (pill-label-node pill)
        label-classes (set (class-values (get-in label-node [1 :class])))]
    (is (some? pill))
    (is (contains? classes "items-end"))
    (is (not (contains? classes "items-center")))
    (is (some? label-node))
    (is (contains? label-classes "leading-none"))
    (is (contains? label-classes "top-px"))))

(deftest status-meter-click-dispatches-diagnostics-toggle-test
  (let [view (footer-view/footer-view (base-state))
        pill (find-pill view)]
    (is (= [[:actions/toggle-ws-diagnostics]]
           (get-in pill [1 :on :click])))))

(deftest status-meter-renders-four-bars-and-full-strength-for-base-health-test
  (let [view (footer-view/footer-view (base-state))]
    (is (= 4 (meter-bar-count view)))
    (is (= 4 (meter-active-bar-count view)))))

(deftest status-meter-degrades-for-browser-3g-hints-test
  (with-browser-connection
    #js {:effectiveType "3g"
         :rtt 450
         :downlink 0.7
         :saveData false}
    (fn []
      (let [view (footer-view/footer-view (base-state))
            pill (find-pill view)]
        (is (str/includes? (node-text pill) "Online"))
        (is (not (str/includes? (node-text pill) "450ms")))
        (is (= 3 (meter-active-bar-count view)))))))

(deftest diagnostics-popover-keeps-network-penalty-out-of-visible-ui-test
  (with-browser-connection
    #js {:effectiveType "3g"
         :rtt 450
         :downlink 0.7
         :saveData false}
    (fn []
      (let [view (footer-view/footer-view (assoc-in (base-state) [:websocket-ui :diagnostics-open?] true))
            text (node-text view)]
        (is (not (str/includes? text "Browser network hint")))
        (is (not (str/includes? text "Score")))
        (is (not (str/includes? text "Penalty")))))))

(deftest status-meter-degrades-for-live-headroom-before-delayed-threshold-test
  (let [state (-> (base-state)
                  (assoc-in [:websocket :health :generated-at-ms] 10000)
                  (assoc-in [:websocket :health :streams ["trades" "BTC" nil nil nil] :status] :live)
                  (assoc-in [:websocket :health :streams ["trades" "BTC" nil nil nil] :last-payload-at-ms] 2100)
                  (assoc-in [:websocket :health :streams ["trades" "BTC" nil nil nil] :stale-threshold-ms] 8000))
        view (footer-view/footer-view state)
        pill (find-pill view)]
    (is (str/includes? (node-text pill) "Online"))
    (is (= 3 (meter-active-bar-count view)))))

(deftest status-meter-degrades-in-steps-for-slight-versus-severe-delay-test
  (let [slight-delay-state (-> (base-state)
                               (assoc-in [:websocket :health :generated-at-ms] 20000)
                               (assoc-in [:websocket :health :groups :market_data :worst-status] :delayed)
                               (assoc-in [:websocket :health :streams ["trades" "BTC" nil nil nil] :status] :delayed)
                               (assoc-in [:websocket :health :streams ["trades" "BTC" nil nil nil] :last-payload-at-ms] 8000)
                               (assoc-in [:websocket :health :streams ["trades" "BTC" nil nil nil] :stale-threshold-ms] 10000))
        severe-delay-state (-> (base-state)
                               (assoc-in [:websocket :health :generated-at-ms] 40000)
                               (assoc-in [:websocket :health :transport :freshness] :delayed)
                               (assoc-in [:websocket :health :groups :orders_oms :worst-status] :delayed)
                               (assoc-in [:websocket :health :groups :market_data :worst-status] :delayed)
                               (assoc-in [:websocket :health :groups :account :worst-status] :delayed)
                               (assoc-in [:websocket :health :streams ["trades" "BTC" nil nil nil] :status] :delayed)
                               (assoc-in [:websocket :health :streams ["trades" "BTC" nil nil nil] :last-payload-at-ms] 2000)
                               (assoc-in [:websocket :health :streams ["trades" "BTC" nil nil nil] :stale-threshold-ms] 5000)
                               (assoc-in [:websocket :health :streams ["l2Book" "BTC" nil nil nil]]
                                         {:group :market_data
                                          :topic "l2Book"
                                          :subscribed? true
                                          :status :delayed
                                          :last-payload-at-ms 1000
                                          :stale-threshold-ms 5000
                                          :descriptor {:type "l2Book" :coin "BTC"}})
                               (assoc-in [:websocket :health :streams ["openOrders" nil "0xabc" nil nil]]
                                         {:group :orders_oms
                                          :topic "openOrders"
                                          :subscribed? true
                                          :status :delayed
                                          :last-payload-at-ms 1500
                                          :stale-threshold-ms 4000
                                          :descriptor {:type "openOrders" :user "0xabc"}}))
        slight-view (footer-view/footer-view slight-delay-state)
        severe-view (footer-view/footer-view severe-delay-state)]
    (is (= "Delayed" (node-text (find-pill slight-view))))
    (is (= "Delayed" (node-text (find-pill severe-view))))
    (is (= 3 (meter-active-bar-count slight-view)))
    (is (<= 0 (meter-active-bar-count severe-view) 1))
    (is (> (meter-active-bar-count slight-view)
           (meter-active-bar-count severe-view)))))

(deftest diagnostics-popover-renders-surface-freshness-toggle-test
  (let [off-view (footer-view/footer-view (base-state))
        off-toggle (find-surface-freshness-toggle off-view)
        on-view (footer-view/footer-view (-> (base-state)
                                             (assoc-in [:websocket-ui :diagnostics-open?] true)
                                             (assoc-in [:websocket-ui :show-surface-freshness-cues?] true)))
        on-toggle (find-surface-freshness-toggle on-view)]
    (is (nil? off-toggle))
    (is (some? on-toggle))
    (is (= "switch" (get-in on-toggle [1 :role])))
    (is (= "true" (get-in on-toggle [1 :aria-checked])))
    (is (= [[:actions/toggle-show-surface-freshness-cues]]
           (get-in on-toggle [1 :on :click])))))

(deftest footer-renders-mobile-bottom-nav-actions-test
  (let [view (footer-view/footer-view (assoc (base-state)
                                             :router {:path "/trade"}))
        mobile-nav (find-node #(= "mobile-bottom-nav" (get-in % [1 :data-role])) view)
        markets-button (find-node #(= "mobile-bottom-nav-markets" (get-in % [1 :data-role])) view)
        trade-button (find-node #(= "mobile-bottom-nav-trade" (get-in % [1 :data-role])) view)
        account-button (find-node #(= "mobile-bottom-nav-account" (get-in % [1 :data-role])) view)]
    (is (some? mobile-nav))
    (is (= [[:actions/select-trade-mobile-surface :chart]]
           (get-in markets-button [1 :on :click])))
    (is (= [[:actions/select-trade-mobile-surface :ticket]]
           (get-in trade-button [1 :on :click])))
    (is (= [[:actions/select-trade-mobile-surface :account]]
           (get-in account-button [1 :on :click])))))

(deftest footer-mobile-bottom-nav-uses-flat-single-line-styling-test
  (let [view (footer-view/footer-view (assoc (base-state)
                                             :router {:path "/trade"}))
        markets-button (find-node #(= "mobile-bottom-nav-markets" (get-in % [1 :data-role])) view)
        classes (set (class-values (get-in markets-button [1 :class])))]
    (is (contains? classes "h-10"))
    (is (contains? classes "whitespace-nowrap"))
    (is (contains? classes "text-[#61e6cf]"))
    (is (not (contains? classes "flex-col")))
    (is (not (contains? classes "rounded-xl")))
    (is (not (contains? classes "bg-[#0d2a31]")))))

(deftest footer-mobile-bottom-nav-highlights-account-surface-test
  (let [view (footer-view/footer-view (assoc (base-state)
                                             :router {:path "/trade"}
                                             :trade-ui {:mobile-surface :account}))
        account-button (find-node #(= "mobile-bottom-nav-account" (get-in % [1 :data-role])) view)
        classes (set (class-values (get-in account-button [1 :class])))]
    (is (contains? classes "text-[#61e6cf]"))
    (is (not (contains? classes "bg-[#0d2a31]")))))

(deftest footer-mobile-bottom-nav-keeps-markets-active-for-trades-surface-test
  (let [view (footer-view/footer-view (assoc (base-state)
                                             :router {:path "/trade"}
                                             :trade-ui {:mobile-surface :trades}))
        markets-button (find-node #(= "mobile-bottom-nav-markets" (get-in % [1 :data-role])) view)
        trade-button (find-node #(= "mobile-bottom-nav-trade" (get-in % [1 :data-role])) view)
        markets-classes (set (class-values (get-in markets-button [1 :class])))
        trade-classes (set (class-values (get-in trade-button [1 :class])))]
    (is (contains? markets-classes "text-[#61e6cf]"))
    (is (not (contains? trade-classes "text-[#61e6cf]")))))

(deftest diagnostics-popover-renders-only-when-open-test
  (let [closed-view (footer-view/footer-view (base-state))
        open-view (footer-view/footer-view (assoc-in (base-state) [:websocket-ui :diagnostics-open?] true))
        connection-slot (find-node-by-data-role open-view "footer-connection-slot")
        popover (find-node-by-data-role open-view "connection-diagnostics-popover")
        backdrop (find-node-by-data-role open-view "connection-diagnostics-backdrop")]
    (is (nil? (find-node #(and (vector? %)
                               (= "connection-diagnostics-popover"
                                  (get-in % [1 :data-role])))
                         closed-view)))
    (is (some? popover))
    (is (some? connection-slot))
    (is (some? (find-node-by-data-role connection-slot "footer-connection-meter-button")))
    (is (some? (find-node-by-data-role connection-slot "connection-diagnostics-layer")))
    (is (= :div (first popover)))
    (is (= "dialog" (get-in popover [1 :role])))
    (is (= "Connection status" (get-in popover [1 :aria-label])))
    (is (= [[:actions/handle-ws-diagnostics-keydown [:event/key]]]
           (get-in popover [1 :on :keydown])))
    (is (some? backdrop))
    (is (= [[:actions/close-ws-diagnostics]]
           (get-in backdrop [1 :on :click])))))

(deftest diagnostics-popover-prioritizes-trader-comprehension-test
  (let [view (footer-view/footer-view (assoc-in (base-state) [:websocket-ui :diagnostics-open?] true))
        text (node-text view)]
    (is (str/includes? text "Everything is live"))
    (is (str/includes? text "All data is streaming normally."))
    (is (str/includes? text "Orders"))
    (is (str/includes? text "Market data"))
    (is (str/includes? text "Account"))
    (is (str/includes? text "Show freshness labels"))
    (is (str/includes? text "Developer details"))
    (is (not (str/includes? text "Build id")))
    (is (not (str/includes? text "Reveal sensitive")))
    (is (not (str/includes? text "Market projection")))
    (is (not (str/includes? text "Recent flushes")))
    (is (not (str/includes? text "Transport")))))

(deftest diagnostics-popover-stream-ages-update-from-snapshot-test
  (let [view-a (footer-view/footer-view (-> (base-state)
                                            (assoc-in [:websocket-ui :diagnostics-open?] true)
                                            (assoc-in [:websocket :health :streams ["trades" "BTC" nil nil nil] :last-payload-at-ms] 5000)))
        view-b (footer-view/footer-view (-> (base-state)
                                            (assoc-in [:websocket-ui :diagnostics-open?] true)
                                            (assoc-in [:websocket :health :generated-at-ms] 11000)
                                            (assoc-in [:websocket :health :streams ["trades" "BTC" nil nil nil] :last-payload-at-ms] 5000)))
        text-a (node-text view-a)
        text-b (node-text view-b)]
    (is (str/includes? text-a "5s"))
    (is (str/includes? text-b "6s"))
    (is (not (str/includes? text-a "Threshold")))))

(deftest diagnostics-developer-details-remains-compact-preview-test
  (let [many-events (mapv (fn [idx]
                            {:event (if (even? idx) :connected :gap-detected)
                             :at-ms (* 100 idx)
                             :details {:idx idx}})
                          (range 10))
        many-streams (into {}
                           (map (fn [idx]
                                  [["trades" (str "COIN" idx) nil nil nil]
                                   {:group :market_data
                                    :topic "trades"
                                    :subscribed? true
                                    :status :live
                                    :last-payload-at-ms idx
                                    :stale-threshold-ms 10000
                                    :descriptor {:type "trades"
                                                 :coin (str "COIN" idx)}
                                    :message-count idx}]))
                           (range 12))
        view (footer-view/footer-view
              (-> (base-state)
                  (assoc-in [:websocket-ui :diagnostics-open?] true)
                  (assoc-in [:websocket :health :groups :market_data :worst-status] :delayed)
                  (assoc-in [:websocket-ui :diagnostics-timeline] many-events)
                  (assoc-in [:websocket :health :streams] many-streams)))
        text (node-text view)]
    (is (= 3 (count-nodes-by-data-role view "connection-diagnostics-dev-event")))
    (is (= 5 (count-nodes-by-data-role view "connection-diagnostics-dev-stream")))
    (is (str/includes? text "Recent events"))
    (is (str/includes? text "Streams (12)"))
    (is (not (str/includes? text "COIN9")))))

(deftest diagnostics-actions-dispatch-correct-events-test
  (let [view (footer-view/footer-view (-> (base-state)
                                          (assoc-in [:websocket-ui :diagnostics-open?] true)
                                          (assoc-in [:websocket :health :groups :market_data :worst-status] :delayed)))
        close-btn (find-node-by-data-role view "connection-diagnostics-close")
        reconnect-btn (find-node-by-data-role view "connection-diagnostics-reconnect")
        copy-btn (find-node #(and (vector? %)
                                  (button-tag? (first %))
                                  (str/includes? (node-text %) "Copy diagnostics"))
                            view)]
    (is (= [[:actions/close-ws-diagnostics]]
           (get-in close-btn [1 :on :click])))
    (is (= [[:actions/ws-diagnostics-reconnect-now]]
           (get-in reconnect-btn [1 :on :click])))
    (is (= [[:actions/ws-diagnostics-copy]]
           (get-in copy-btn [1 :on :click])))
    (is (not (str/includes? (node-text view) "Reset market streams")))
    (is (not (str/includes? (node-text view) "Reset Orders streams")))
    (is (not (str/includes? (node-text view) "Reset all streams")))))

(deftest diagnostics-masks-addresses-by-default-test
  (let [address "0x1234567890abcdef1234567890abcdef12345678"
        view (footer-view/footer-view
               (-> (base-state)
                   (assoc-in [:websocket-ui :diagnostics-open?] true)
                   (assoc-in [:websocket :health :streams ["openOrders" nil address nil nil]]
                             {:group :orders_oms
                              :topic "openOrders"
                              :status :n-a
                              :last-payload-at-ms 9500
                              :stale-threshold-ms nil
                              :descriptor {:type "openOrders"
                                           :user address}})))
        text (node-text view)]
    (is (not (str/includes? text address)))
    (is (str/includes? text "0x1234...45678"))))

(deftest diagnostics-does-not-render-sensitive-reveal-toggle-test
  (let [view (footer-view/footer-view (assoc-in (base-state) [:websocket-ui :diagnostics-open?] true))
        reveal-btn (find-node #(and (vector? %)
                                    (button-tag? (first %))
                                    (= "Reveal sensitive" (last %)))
                              view)]
    (is (nil? reveal-btn))))

(deftest diagnostics-event-driven-status-uses-trader-language-test
  (let [view (footer-view/footer-view (assoc-in (base-state) [:websocket-ui :diagnostics-open?] true))
        chip (find-node #(and (vector? %)
                              (= :span (first %))
                              (str/includes? (node-text %) "Live"))
                        view)
        classes (set (class-values (get-in chip [1 :class])))]
    (is (some? chip))
    (is (contains? classes "hx-group-chip"))
    (is (str/includes? (node-text view) "Updates on activity"))
    (is (not (str/includes? (node-text view) "EVENT-DRIVEN")))))

(deftest reconnect-button-disabled-when-reconnecting-or-cooldown-active-test
  (let [reconnecting-view (footer-view/footer-view
                            (-> (base-state)
                                (assoc-in [:websocket-ui :diagnostics-open?] true)
                                (assoc-in [:websocket :health :transport :state] :reconnecting)))
        reconnecting-btn (find-node-by-data-role reconnecting-view "connection-diagnostics-reconnect")
        cooldown-view (footer-view/footer-view
                        (-> (base-state)
                            (assoc-in [:websocket-ui :diagnostics-open?] true)
                            (assoc-in [:websocket :health :groups :market_data :worst-status] :delayed)
                            (assoc-in [:websocket-ui :reconnect-cooldown-until-ms] 12000)))
        cooldown-btn (find-node-by-data-role cooldown-view "connection-diagnostics-reconnect")]
    (is (true? (get-in reconnecting-btn [1 :disabled])))
    (is (true? (get-in cooldown-btn [1 :disabled])))))

(deftest diagnostics-copy-feedback-renders-inline-success-and-fallback-json-test
  (let [view (footer-view/footer-view
               (-> (base-state)
                   (assoc-in [:websocket-ui :diagnostics-open?] true)
                   (assoc-in [:websocket-ui :copy-status]
                             {:kind :success
                              :message "Copied (redacted)"})))
        error-view (footer-view/footer-view
                    (-> (base-state)
                        (assoc-in [:websocket-ui :diagnostics-open?] true)
                        (assoc-in [:websocket-ui :copy-status]
                                  {:kind :error
                                   :message "Couldn't access clipboard. Copy the redacted JSON below."
                                   :fallback-json "{\"redacted\":true}"})))
        error-text (node-text error-view)
        copy-btn (find-node #(and (vector? %)
                                  (button-tag? (first %))
                                  (str/includes? (node-text %) "Copied"))
                            view)]
    (is (str/includes? (node-text copy-btn) "✓"))
    (is (str/includes? error-text "Couldn't access clipboard. Copy the redacted JSON below."))
    (is (str/includes? error-text "{\"redacted\":true}"))))

(deftest diagnostics-reconnecting-copy-and-reconnect-buttons-show-loading-state-test
  (let [view (footer-view/footer-view
              (-> (base-state)
                  (assoc-in [:websocket-ui :diagnostics-open?] true)
                  (assoc-in [:websocket :health :transport :state] :reconnecting)
                  (assoc-in [:websocket :health :transport :freshness] :reconnecting)))
        text (node-text view)
        reconnect-btn (find-node-by-data-role view "connection-diagnostics-reconnect")
        spinner (find-node-by-data-role reconnect-btn "connection-diagnostics-reconnect-spinner")]
    (is (str/includes? text "Reconnecting"))
    (is (str/includes? text "Your open orders are safe on the exchange"))
    (is (true? (get-in reconnect-btn [1 :disabled])))
    (is (some? spinner))))

(deftest diagnostics-removes-developer-only-tables-from-visible-ui-test
  (let [view (footer-view/footer-view
               (-> (base-state)
                   (assoc-in [:websocket-ui :copy-status]
                             nil)
                   (assoc-in [:websocket-ui :diagnostics-open?] true)
                   (assoc-in [:websocket :health :market-projection]
                             {:stores [{:store-id "app-store"
                                        :pending-count 0
                                        :queued-total 1
                                        :overwrite-total 0
                                        :flush-count 1
                                        :max-pending-depth 1
                                        :p95-flush-duration-ms 6
                                        :last-flush-duration-ms 6
                                        :last-queue-wait-ms 2}]
                              :flush-events [{:seq 1
                                              :at-ms 9900
                                              :store-id "app-store"
                                              :pending-count 1
                                              :overwrite-count 0
                                              :flush-duration-ms 6
                                              :queue-wait-ms 2}]})))
        text (node-text view)]
    (is (not (str/includes? text "app-store")))
    (is (not (str/includes? text "P95 flush")))
    (is (not (str/includes? text "Queue wait")))
    (is (not (str/includes? text "Last close")))
    (is (not (str/includes? text "Descriptor")))))

(deftest persistent-banner-rules-test
  (let [orders-offline-view (footer-view/footer-view
                             (assoc-in (base-state) [:websocket :health :groups :orders_oms :worst-status] :offline))
        orders-reconnecting-view (footer-view/footer-view
                                  (assoc-in (base-state) [:websocket :health :groups :orders_oms :worst-status] :reconnecting))
        market-delayed-view (footer-view/footer-view
                             (assoc-in (base-state) [:websocket :health :groups :market_data :worst-status] :delayed))
        market-offline-default-hidden-view (footer-view/footer-view
                                            (-> (base-state)
                                                (assoc-in [:websocket :health :groups :market_data :worst-status] :offline)
                                                (assoc-in [:websocket-ui :show-market-offline-banner?] false)))]
    (is (str/includes? (node-text orders-offline-view)
                       "Orders/OMS websocket offline. Trading activity status may be stale."))
    (is (str/includes? (node-text orders-reconnecting-view)
                       "Orders/OMS websocket reconnecting. Order lifecycle updates may be delayed."))
    (is (not (str/includes? (node-text market-delayed-view) "Market data websocket offline")))
    (is (not (str/includes? (node-text market-offline-default-hidden-view) "Market data websocket offline")))))

(deftest footer-root-includes-fixed-layering-classes-test
  (let [view (footer-view/footer-view (base-state))
        classes (root-class-set view)]
    (is (contains? classes "fixed"))
    (is (contains? classes "inset-x-0"))
    (is (contains? classes "bottom-0"))
    (is (contains? classes "z-[170]"))
    (is (contains? classes "bg-base-200"))
    (is (contains? classes "isolate"))))

(deftest footer-root-class-attr-is-collection-test
  (let [view (footer-view/footer-view (base-state))
        class-attr (get-in view [1 :class])]
    (is (coll? class-attr))
    (is (not (string? class-attr)))))

(deftest diagnostics-open-raises-footer-z-layer-test
  (let [view (footer-view/footer-view (assoc-in (base-state) [:websocket-ui :diagnostics-open?] true))
        classes (root-class-set view)]
    (is (contains? classes "z-[260]"))
    (is (not (contains? classes "z-[170]")))))

(deftest footer-hides-text-links-when-no-footer-links-are-configured-test
  (let [view (footer-view/footer-view (base-state))
        utility-links (find-node-by-data-role view "footer-utility-links")
        text-links (find-node-by-data-role utility-links "footer-text-links")
        divider (find-node-by-data-role utility-links "footer-links-divider")]
    (is (some? utility-links))
    (is (nil? text-links))
    (is (nil? divider))
    (is (zero? (count-nodes #(and (vector? %)
                                  (= "footer-text-link" (get-in % [1 :data-role])))
                            view)))))

(deftest footer-social-icons-render-inline-current-color-svgs-in-utility-cluster-test
  (let [view (footer-view/footer-view (base-state))
        utility-links (find-node-by-data-role view "footer-utility-links")
        telegram-icon (find-node-by-data-role utility-links "footer-social-telegram")
        github-icon (find-node-by-data-role utility-links "footer-social-github")
        telegram-svg (find-node #(and (vector? %)
                                      (= :svg (first %)))
                                telegram-icon)
        github-svg (find-node #(and (vector? %)
                                    (= :svg (first %)))
                              github-icon)
        legacy-telegram-img (find-node #(and (vector? %)
                                             (= :img (first %))
                                             (= "/telegram_logo.svg" (get-in % [1 :src])))
                                       view)]
    (is (some? utility-links))
    (is (some? (find-node-by-data-role utility-links "footer-social-links")))
    (is (some? telegram-icon))
    (is (some? github-icon))
    (is (= "Telegram" (get-in telegram-icon [1 :aria-label])))
    (is (= "GitHub" (get-in github-icon [1 :aria-label])))
    (is (nil? legacy-telegram-img))
    (doseq [svg [telegram-svg github-svg]]
      (is (some? svg))
      (is (= "currentColor" (get-in svg [1 :fill])))
      (is (= "none" (get-in svg [1 :stroke]))))))

(deftest footer-view-uses-app-shell-gutter-test
  (let [view-node (footer-view/footer-view {:websocket {:status :connected}})]
    (is (hiccup/contains-class? view-node "app-shell-gutter"))
    (is (hiccup/contains-class? view-node "fixed"))
    (is (hiccup/contains-class? view-node "inset-x-0"))
    (is (hiccup/contains-class? view-node "bottom-0"))
    (is (hiccup/contains-class? view-node "z-[170]"))
    (is (hiccup/contains-class? view-node "bg-base-200"))
    (is (hiccup/contains-class? view-node "isolate"))))
