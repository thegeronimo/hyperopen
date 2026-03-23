(ns hyperopen.views.asset-selector-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.asset-selector-view :as view]))

(def sample-markets
  [{:key "perp:BTC"
    :symbol "BTC-USDC"
    :coin "BTC"
    :base "BTC"
    :market-type :perp
    :category :crypto
    :hip3? false
    :mark 1
    :volume24h 10
    :change24hPct 1}
   {:key "perp:xyz:GOLD"
    :symbol "GOLD-USDC"
    :coin "xyz:GOLD"
   :base "GOLD"
   :market-type :perp
   :category :tradfi
   :hip3? true
    :hip3-eligible? true
   :mark 2
   :volume24h 20
   :change24hPct 2}
   {:key "spot:PURR/USDC"
    :symbol "PURR/USDC"
    :coin "PURR/USDC"
    :base "PURR"
    :market-type :spot
    :category :spot
    :hip3? false
    :mark 0.5
    :volume24h 5
    :change24hPct -1}])

(deftest filter-and-sort-assets-test
  (testing "strict search filters by prefix"
    (let [results (view/filter-and-sort-assets sample-markets "bt" :name :asc #{} false true :all)]
      (is (= 1 (count results)))
      (is (= "BTC-USDC" (:symbol (first results))))))

  (testing "favorites-only filter"
    (let [results (view/filter-and-sort-assets sample-markets "" :name :asc #{"perp:BTC"} true false :all)]
      (is (= 1 (count results)))
      (is (= "perp:BTC" (:key (first results))))))

  (testing "tab filter for spot"
    (let [results (view/filter-and-sort-assets sample-markets "" :name :asc #{} false false :spot)]
      (is (= 1 (count results)))
      (is (= :spot (:market-type (first results))))))

  (testing "hip3 tab strict mode parity: strict off shows full HIP3 set, strict on applies eligibility"
    (let [assets [{:key "perp:xyz:USA500"
                   :symbol "USA500-USDT"
                   :coin "xyz:USA500"
                   :base "USA500"
                   :market-type :perp
                   :category :tradfi
                   :hip3? true
                   :hip3-eligible? true}
                  {:key "perp:xyz:ILLQ"
                   :symbol "ILLQ-USDC"
                   :coin "xyz:ILLQ"
                   :base "ILLQ"
                   :market-type :perp
                   :category :tradfi
                   :hip3? true
                   :hip3-eligible? false}
                  {:key "perp:xyz:LEGACY"
                   :symbol "LEGACY-USDC"
                   :coin "xyz:LEGACY"
                   :base "LEGACY"
                   :market-type :perp
                   :category :tradfi
                   :hip3? true}
                  {:key "perp:BTC"
                   :symbol "BTC-USDC"
                   :coin "BTC"
                   :base "BTC"
                   :market-type :perp
                   :category :crypto
                   :hip3? false}]
          strict-off-results (view/filter-and-sort-assets assets "" :name :asc #{} false false :hip3)
          strict-on-results (view/filter-and-sort-assets assets "" :name :asc #{} false true :hip3)
          perps-strict-on-results (view/filter-and-sort-assets assets "" :name :asc #{} false true :perps)]
      (is (= ["perp:xyz:ILLQ" "perp:xyz:LEGACY" "perp:xyz:USA500"]
             (mapv :key strict-off-results)))
      (is (= ["perp:xyz:LEGACY" "perp:xyz:USA500"]
             (mapv :key strict-on-results)))
      (is (= ["perp:BTC" "perp:xyz:LEGACY" "perp:xyz:USA500"]
             (mapv :key perps-strict-on-results))))))

(deftest filter-and-sort-assets-preserves-cache-order-when-sort-values-missing-test
  (let [cached-markets [{:key "spot:AAA/USDC"
                         :symbol "AAA/USDC"
                         :coin "AAA/USDC"
                         :base "AAA"
                         :market-type :spot
                         :cache-order 2}
                        {:key "spot:BBB/USDC"
                         :symbol "BBB/USDC"
                         :coin "BBB/USDC"
                         :base "BBB"
                         :market-type :spot
                         :cache-order 0}
                        {:key "spot:CCC/USDC"
                         :symbol "CCC/USDC"
                         :coin "CCC/USDC"
                         :base "CCC"
                         :market-type :spot
                         :cache-order 1}]
        results (view/filter-and-sort-assets cached-markets "" :volume :desc #{} false false :all)]
    (is (= ["BBB/USDC" "CCC/USDC" "AAA/USDC"]
           (mapv :symbol results)))))

(deftest processed-assets-returns-cached-result-when-input-identities-match-test
  (view/reset-processed-assets-cache!)
  (let [favorites #{}
        first-result (view/processed-assets sample-markets "" :volume :desc favorites false false :all)
        second-result (view/processed-assets sample-markets "" :volume :desc favorites false false :all)
        changed-result (view/processed-assets sample-markets "btc" :volume :desc favorites false false :all)]
    (is (identical? first-result second-result))
    (is (not (identical? second-result changed-result)))))

(deftest processed-assets-keeps-stable-order-across-live-market-churn-test
  (view/reset-processed-assets-cache!)
  (let [favorites #{}
        initial-results (view/processed-assets sample-markets "" :volume :desc favorites false false :all)
        live-markets [{:key "perp:BTC"
                       :symbol "BTC-USDC"
                       :coin "BTC"
                       :base "BTC"
                       :market-type :perp
                       :category :crypto
                       :hip3? false
                       :mark 99
                       :volume24h 999
                       :change24hPct 8}
                      {:key "perp:xyz:GOLD"
                       :symbol "GOLD-USDC"
                       :coin "xyz:GOLD"
                       :base "GOLD"
                       :market-type :perp
                       :category :tradfi
                       :hip3? true
                       :hip3-eligible? true
                       :mark 1
                       :volume24h 1
                       :change24hPct -4}
                      {:key "spot:PURR/USDC"
                       :symbol "PURR/USDC"
                       :coin "PURR/USDC"
                       :base "PURR"
                       :market-type :spot
                       :category :spot
                       :hip3? false
                       :mark 0.75
                       :volume24h 3
                       :change24hPct 2}]
        live-market-by-key (into {} (map (juxt :key identity) live-markets))
        live-results (view/processed-assets live-markets live-market-by-key "" :volume :desc favorites false false :all)]
    (is (= (mapv :key initial-results)
           (mapv :key live-results)))
    (is (= 99
           (some->> live-results
                    (filter #(= "perp:BTC" (:key %)))
                    first
                    :mark)))
    (is (= 0.75
           (some->> live-results
                    (filter #(= "spot:PURR/USDC" (:key %)))
                    first
                    :mark)))))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings node)
    (seq? node) (mapcat collect-strings node)
    :else []))

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

(defn- collect-all-classes [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (concat (classes-from-tag (first node))
              (class-values (:class attrs))
              (mapcat collect-all-classes children)))

    (seq? node)
    (mapcat collect-all-classes node)

    :else []))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- find-node-by-role [node role]
  (find-first-node node
                   (fn [candidate]
                     (let [attrs (when (and (vector? candidate)
                                            (map? (second candidate)))
                                   (second candidate))]
                       (= role (:data-role attrs))))))

(defn- node-children [node]
  (let [attrs (when (and (vector? node) (map? (second node))) (second node))]
    (if attrs
      (drop 2 node)
      (drop 1 node))))

(defn- count-selectable-asset-rows [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))
          row? (= :actions/select-asset
                  (-> attrs :on :click first first))]
      (+ (if row? 1 0)
         (reduce + 0 (map count-selectable-asset-rows children))))

    (seq? node)
    (reduce + 0 (map count-selectable-asset-rows node))

    :else 0))

(defn- render-asset-list-body
  [& args]
  (apply @#'hyperopen.views.asset-selector-view/asset-list-body args))

(defn- asset-list-window-state
  [assets scroll-top]
  (@#'hyperopen.views.asset-selector-view/asset-list-window-state
    {:assets assets}
    scroll-top))

(defn- asset-list-viewport-covered?
  [current-window-state next-window-state]
  (@#'hyperopen.views.asset-selector-view/asset-list-viewport-covered?
    current-window-state
    next-window-state))

(defn- fake-scroll-node
  []
  (let [listeners* (atom {})
        host-node #js {}
        node #js {}]
    (set! (.-scrollTop node) 0)
    (aset node "querySelector" (fn [_selector] host-node))
    (aset node "firstElementChild" host-node)
    (aset node "addEventListener" (fn [event-type handler]
                                     (swap! listeners* assoc event-type handler)))
    (aset node "removeEventListener" (fn [event-type _handler]
                                        (swap! listeners* dissoc event-type)))
    {:node node
     :host-node host-node
     :listeners* listeners*}))

(deftest tooltip-position-classes-cover-default-and-explicit-directions-test
  (doseq [[position expected-panel-class expected-arrow-class]
          [[nil "bottom-full" "top-full"]
           ["bottom" "top-full" "bottom-full"]
           ["left" "right-full" "left-full"]
           ["right" "left-full" "right-full"]]]
    (let [tooltip-node (view/tooltip [[:span "APR"] "Annualized funding"] position)
          panel-node (last (node-children tooltip-node))
          arrow-node (find-first-node tooltip-node #(contains? (set (collect-all-classes %)) "border-4"))
          panel-classes (set (collect-all-classes panel-node))
          arrow-classes (set (collect-all-classes arrow-node))]
      (is (contains? panel-classes expected-panel-class))
      (is (contains? panel-classes "group-hover:opacity-100"))
      (is (contains? arrow-classes expected-arrow-class)))))

(defn- selector-props [desktop?]
  {:visible? true
   :desktop? desktop?
   :markets sample-markets
   :selected-market-key "perp:BTC"
   :search-term ""
   :sort-by :name
   :sort-direction :asc
   :favorites #{}
   :favorites-only? false
   :strict? false
   :active-tab :all
   :missing-icons #{}
   :loaded-icons #{}
   :highlighted-market-key nil
   :render-limit 120
   :scroll-top 0})

(deftest asset-selector-wrapper-reuses-cached-hiccup-for-equal-props-test
  (let [props (assoc (selector-props true)
                     :market-by-key {"perp:BTC" (first sample-markets)})
        first-result (view/asset-selector-wrapper props)
        second-result (view/asset-selector-wrapper (into {} props))]
    (is (identical? first-result second-result))))

(deftest asset-list-uses-runtime-backed-scroll-container-and-renders-progressive-chunk-test
  (let [assets (vec (for [n (range 150)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        hiccup (view/asset-list assets nil nil #{} #{} #{} 40 0)
        scroll-container hiccup
        attrs (second scroll-container)
        inner-wrapper (first (node-children scroll-container))
        inner-attrs (second inner-wrapper)
        body-hiccup (render-asset-list-body assets nil nil #{} #{} #{} 40 0 false)
        strings (set (collect-strings body-hiccup))]
    (is (ifn? (:replicant/on-render attrs)))
    (is (= "asset-selector-list" (:replicant/key attrs)))
    (is (= "none" (get-in attrs [:style :overflow-anchor])))
    (is (= "asset-selector-list-body-host" (:data-role inner-attrs)))
    (is (= "none" (get-in inner-attrs [:style :overflow-anchor])))
    (is (< (count-selectable-asset-rows body-hiccup) 40))
    (is (>= (count-selectable-asset-rows body-hiccup) 8))
    (is (not (contains? strings "Showing 40 of 150 markets")))
    (is (not (contains? strings "Load more")))
    (is (not (contains? strings "Show all")))))

(deftest asset-list-renders-all-rows-when-render-limit-exceeds-total-test
  (let [assets (vec (for [n (range 8)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        hiccup (render-asset-list-body assets nil nil #{} #{} #{} 120 0 false)
        strings (set (collect-strings hiccup))]
    (is (= 8 (count-selectable-asset-rows hiccup)))
    (is (not (contains? strings "Showing 120 of 8 markets")))))

(deftest asset-list-allows-callers-to-force-a-scroll-runtime-reset-key-test
  (let [hiccup (view/asset-list sample-markets nil nil #{} #{} #{} 120 0 false "search-session")
        attrs (second hiccup)]
    (is (= "search-session" (:replicant/key attrs)))
    (is (ifn? (:replicant/on-render attrs)))))

(deftest asset-selector-dropdown-roots-expose-parity-ids-test
  (let [desktop-view (view/asset-selector-dropdown (selector-props true))
        mobile-view (view/asset-selector-dropdown (selector-props false))
        desktop-dropdown (find-node-by-role desktop-view "asset-selector-desktop-dropdown")
        mobile-dropdown (find-node-by-role mobile-view "asset-selector-mobile-overlay")]
    (is (= "asset-selector-desktop" (get-in desktop-dropdown [1 :data-parity-id])))
    (is (= "asset-selector-mobile" (get-in mobile-dropdown [1 :data-parity-id])))))

(deftest asset-list-virtual-window-tracks-scroll-position-test
  (let [assets (vec (for [n (range 200)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        top-hiccup (render-asset-list-body assets nil nil #{} #{} #{} 120 0 false)
        deep-hiccup (render-asset-list-body assets nil nil #{} #{} #{} 120 2200 false)
        top-strings (set (collect-strings top-hiccup))
        deep-strings (set (collect-strings deep-hiccup))]
    (is (contains? top-strings "T0-USDC"))
    (is (not (contains? deep-strings "T0-USDC")))
    (is (contains? deep-strings "T90-USDC"))))

(deftest asset-list-scroll-window-does-not-clip-to-render-limit-test
  (let [assets (vec (for [n (range 200)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        deep-hiccup (render-asset-list-body assets nil nil #{} #{} #{} 40 2200 false)
        deep-strings (set (collect-strings deep-hiccup))]
    (is (contains? deep-strings "T90-USDC"))
    (is (not (contains? deep-strings "T0-USDC")))))

(deftest asset-list-viewport-coverage-only-breaks-when-scroll-leaves-current-window-test
  (let [assets (vec (for [n (range 200)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        current-window-state (asset-list-window-state assets 0)
        covered-window-state (asset-list-window-state assets 72)
        uncovered-window-state (asset-list-window-state assets 720)]
    (is (true? (asset-list-viewport-covered? current-window-state covered-window-state)))
    (is (false? (asset-list-viewport-covered? current-window-state uncovered-window-state)))))

(deftest asset-list-runtime-defers-prop-sync-until-scroll-settles-test
  (let [assets (vec (for [n (range 60)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        mount-on-render (get-in (view/asset-list assets "perp:T0" nil #{} #{} #{} 120 0 false)
                                [1 :replicant/on-render])
        update-assets (vec (reverse assets))
        update-on-render (get-in (view/asset-list update-assets "perp:T5" nil #{} #{} #{} 120 0 false)
                                 [1 :replicant/on-render])
        remembered* (atom nil)
        rendered* (atom [])
        timeout-installs* (atom 0)
        {node :node host-node :host-node listeners* :listeners*} (fake-scroll-node)]
    (with-redefs [view/render-asset-list-body!
                  (fn [runtime-host props scroll-top]
                    (swap! rendered* conj {:host runtime-host
                                           :selected-market-key (:selected-market-key props)
                                           :first-key (-> props :assets first :key)
                                           :scroll-top scroll-top}))
                  view/schedule-asset-list-render-limit-sync!
                  (fn [& _] nil)
                  view/asset-list-set-timeout!
                  (fn [_f _delay-ms]
                    (swap! timeout-installs* inc)
                    :timeout-handle)
                  view/asset-list-clear-timeout!
                  (fn [_timeout-handle] nil)]
      (mount-on-render {:replicant/life-cycle :replicant.life-cycle/mount
                        :replicant/node node
                        :replicant/remember (fn [memory]
                                              (reset! remembered* memory))})
      (set! (.-scrollTop node) 72)
      ((get @listeners* "scroll") #js {:timeStamp 1})
      (update-on-render {:replicant/life-cycle :replicant.life-cycle/update
                         :replicant/node node
                         :replicant/memory @remembered*
                         :replicant/remember (fn [memory]
                                               (reset! remembered* memory))})
      (is (= 1 @timeout-installs*))
      (is (= [{:host host-node
               :selected-market-key "perp:T0"
               :first-key "perp:T0"
               :scroll-top 0}]
             @rendered*))
      ((:on-scroll-end @remembered*) #js {})
      (is (= [{:host host-node
               :selected-market-key "perp:T0"
               :first-key "perp:T0"
               :scroll-top 0}
              {:host host-node
               :selected-market-key "perp:T5"
               :first-key "perp:T59"
               :scroll-top 72}]
             @rendered*)))))

(deftest asset-list-runtime-reuses-one-settle-timer-during-active-scroll-test
  (let [assets (vec (for [n (range 60)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        on-render (get-in (view/asset-list assets nil nil #{} #{} #{} 120 0 false)
                          [1 :replicant/on-render])
        remembered* (atom nil)
        timeout-installs* (atom 0)
        {node :node listeners* :listeners*} (fake-scroll-node)]
    (with-redefs [view/render-asset-list-body!
                  (fn [& _] nil)
                  view/schedule-asset-list-render-limit-sync!
                  (fn [& _] nil)
                  view/asset-list-set-timeout!
                  (fn [_f _delay-ms]
                    (swap! timeout-installs* inc)
                    :timeout-handle)]
      (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                  :replicant/node node
                  :replicant/remember (fn [memory]
                                        (reset! remembered* memory))})
      (set! (.-scrollTop node) 48)
      ((get @listeners* "scroll") #js {:timeStamp 1})
      (set! (.-scrollTop node) 96)
      ((get @listeners* "scroll") #js {:timeStamp 2})
      (is (= 1 @timeout-installs*)))))

(deftest asset-list-item-sub-cent-formatting-test
  (testing "last price renders adaptive decimals for tiny assets"
    (let [asset {:key "perp:PUMP"
                 :symbol "PUMP-USDC"
                 :coin "PUMP"
                 :base "PUMP"
                 :mark 0.002028
                 :markRaw "0.002028"
                 :volume24h 1000
                 :change24h -0.000329
                 :change24hPct -13.95
                 :fundingRate 0.001
                 :market-type :perp}
          hiccup (view/asset-list-item asset false false #{} #{} #{})
          strings (collect-strings hiccup)
          rendered (set strings)]
      (is (contains? rendered "$0.002028"))
      (is (not (contains? rendered "$0.00"))))))

(deftest asset-list-item-renders-dashes-when-market-data-missing-test
  (let [asset {:key "perp:ABC"
               :symbol "ABC-USDC"
               :coin "ABC"
               :base "ABC"
               :market-type :perp}
        hiccup (view/asset-list-item asset false false #{} #{} #{})
        strings (set (collect-strings hiccup))]
    (is (contains? strings "—"))
    (is (not (contains? strings "+0.00 (0.00%)")))))

(deftest asset-list-item-applies-highlight-class-for-keyboard-navigation-test
  (let [asset {:key "perp:ABC"
               :symbol "ABC-USDC"
               :coin "ABC"
               :base "ABC"
               :market-type :perp}
        row (view/asset-list-item asset false true #{} #{} #{})
        classes (set (collect-all-classes row))]
    (is (contains? classes "bg-base-200/70"))
    (is (not (contains? classes "ring-primary")))))

(deftest search-controls-use-parity-input-styling-test
  (let [controls (view/search-controls "" false false)
        search-input (find-first-node controls
                                      (fn [candidate]
                                        (and (vector? candidate)
                                             (= :input (first candidate))
                                             (= "Search" (get-in candidate [1 :placeholder])))))
        attrs (second search-input)
        classes (set (collect-all-classes search-input))]
    (is (some? search-input))
    (is (= "Search assets" (:aria-label attrs)))
    (is (contains? classes "asset-selector-search-input"))
    (is (contains? classes "focus:outline-none"))
    (is (contains? classes "focus:ring-0"))
    (is (not (contains? classes "ring-primary")))))

(deftest asset-selector-loading-state-test
  (let [base-props (selector-props true)
        full-view (view/asset-selector-dropdown (assoc base-props :loading? true :phase :full))
        bootstrap-view (view/asset-selector-dropdown (assoc base-props :loading? true :phase :bootstrap))
        full-strings (set (collect-strings full-view))
        bootstrap-strings (set (collect-strings bootstrap-view))]
    (is (contains? full-strings "Loading markets..."))
    (is (contains? bootstrap-strings "Loading markets (bootstrap)..."))))

(deftest asset-selector-loading-state-suppresses-empty-state-until-markets-arrive-test
  (let [desktop-view (view/asset-selector-dropdown (assoc (selector-props true)
                                                          :markets []
                                                          :loading? true
                                                          :phase :full))
        mobile-view (view/asset-selector-dropdown (assoc (selector-props false)
                                                         :markets []
                                                         :loading? true
                                                         :phase :full))
        desktop-strings (set (collect-strings desktop-view))
        mobile-strings (set (collect-strings mobile-view))]
    (is (contains? desktop-strings "Loading markets..."))
    (is (contains? mobile-strings "Loading markets..."))
    (is (not (contains? desktop-strings "No assets found")))
    (is (not (contains? mobile-strings "No assets found")))
    (is (not (contains? desktop-strings "Try adjusting your search")))
    (is (not (contains? mobile-strings "Try adjusting your search")))))

(deftest asset-selector-dropdown-renders-desktop-layout-only-when-desktop-test
  (let [dropdown (view/asset-selector-dropdown (selector-props true))
        desktop-dropdown (find-node-by-role dropdown "asset-selector-desktop-dropdown")
        mobile-overlay (find-node-by-role dropdown "asset-selector-mobile-overlay")
        attrs (second desktop-dropdown)
        strings (set (collect-strings dropdown))
        navigate-icon (find-first-node
                        desktop-dropdown
                        (fn [candidate]
                          (and (vector? candidate)
                               (= :svg (first candidate))
                               (= "0 0 22 13"
                                  (get-in candidate [1 :viewBox])))))]
    (is (some? desktop-dropdown))
    (is (nil? mobile-overlay))
    (is (= [[:actions/handle-asset-selector-shortcut
             [:event/key]
             [:event/metaKey]
             [:event/ctrlKey]
             ["perp:BTC" "perp:xyz:GOLD" "spot:PURR/USDC"]]]
           (get-in attrs [:on :keydown])))
    (is (contains? strings "⌘K"))
    (is (contains? strings "Navigate"))
    (is (contains? strings "Enter"))
    (is (contains? strings "⌘S"))
    (is (contains? strings "Esc"))
    (is (contains? strings "Open"))
    (is (contains? strings "Select"))
    (is (contains? strings "Favorite"))
    (is (contains? strings "Close"))
    (is (not (contains? strings "Cmd/Ctrl+K")))
    (is (not (contains? strings "Cmd/Ctrl+S")))
    (is (not (contains? strings "Up/Down")))
    (is (some? navigate-icon))))

(deftest asset-selector-dropdown-renders-mobile-layout-only-when-mobile-test
  (let [dropdown (view/asset-selector-dropdown (selector-props false))
        desktop-dropdown (find-node-by-role dropdown "asset-selector-desktop-dropdown")
        mobile-overlay (find-node-by-role dropdown "asset-selector-mobile-overlay")
        mobile-close-button (find-node-by-role dropdown "asset-selector-mobile-close")
        mobile-strings (set (collect-strings mobile-overlay))]
    (is (nil? desktop-dropdown))
    (is (some? mobile-overlay))
    (is (some? mobile-close-button))
    (is (= [[:actions/close-asset-dropdown]]
           (get-in mobile-close-button [1 :on :click])))
    (is (contains? mobile-strings "Symbol"))
    (is (contains? mobile-strings "Volume"))
    (is (contains? mobile-strings "Open Interest"))
    (is (contains? mobile-strings "Last Price"))
    (is (contains? mobile-strings "24h Change"))
    (is (not (contains? mobile-strings "⌘K")))))

(deftest asset-selector-dropdown-mobile-overlay-uses-fullscreen-shell-test
  (let [dropdown (view/asset-selector-dropdown (selector-props false))
        mobile-overlay (find-node-by-role dropdown "asset-selector-mobile-overlay")
        classes (set (collect-all-classes mobile-overlay))]
    (is (contains? classes "fixed"))
    (is (contains? classes "inset-0"))
    (is (contains? classes "flex-col"))
    (is (contains? classes "lg:hidden"))))

(deftest asset-list-item-applies-left-aligned-numeric-utilities-test
  (let [asset {:key "perp:SOL"
               :symbol "SOL-USDC"
               :coin "SOL"
               :base "SOL"
               :mark 101.55
               :markRaw "101.55"
               :volume24h 123456
               :change24h 2.2
               :change24hPct 1.3
               :fundingRate 0.0001
               :openInterest 99999
               :market-type :perp}
        row (view/asset-list-item asset false false #{} #{} #{})
        classes (set (collect-all-classes row))]
    (is (contains? classes "num"))
    (is (contains? classes "text-left"))
    (is (not (contains? classes "num-right")))))

(deftest asset-list-item-uses-fixed-row-height-and-single-line-symbol-test
  (let [asset {:key "perp:xyz:GOOGL"
               :symbol "GOOGL-USDC"
               :coin "xyz:GOOGL"
               :base "GOOGL"
               :market-type :perp
               :dex "xyz"
               :maxLeverage 10
               :mark 10
               :volume24h 100
               :change24hPct 1}
        row (view/asset-list-item asset false false #{} #{} #{})
        classes (set (collect-all-classes row))]
    (is (contains? classes "h-6"))
    (is (contains? classes "box-border"))
    (is (not (contains? classes "border-b")))
    (is (contains? classes "truncate"))
    (is (contains? classes "whitespace-nowrap"))))

(deftest asset-list-item-does-not-render-market-icon-image-test
  (let [asset {:key "perp:BTC"
               :symbol "BTC-USDC"
               :coin "BTC"
               :base "BTC"
               :market-type :perp
               :mark 1
               :volume24h 10
               :change24hPct 1}
        row (view/asset-list-item asset false false #{} #{} #{})
        img-node (find-first-node
                   row
                   (fn [candidate]
                     (and (vector? candidate)
                          (keyword? (first candidate))
                          (str/starts-with? (name (first candidate)) "img"))))]
    (is (nil? img-node))))
