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

  (testing "hip3 tab applies eligibility gate when available and keeps legacy cached rows visible"
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
          results (view/filter-and-sort-assets assets "" :name :asc #{} false false :hip3)]
      (is (= ["perp:xyz:LEGACY" "perp:xyz:USA500"]
             (mapv :key results))))))

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

(deftest asset-list-wires-scroll-action-and-renders-progressive-chunk-test
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
        strings (set (collect-strings hiccup))]
    (is (= [[:actions/maybe-increase-asset-selector-render-limit
             [:event.target/scrollTop]
             [:event/timeStamp]]]
           (get-in attrs [:on :scroll])))
    (is (= "none" (get-in attrs [:style :overflow-anchor])))
    (is (= "none" (get-in inner-attrs [:style :overflow-anchor])))
    (is (< (count-selectable-asset-rows hiccup) 40))
    (is (>= (count-selectable-asset-rows hiccup) 8))
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
        hiccup (view/asset-list assets nil nil #{} #{} #{} 120 0)
        strings (set (collect-strings hiccup))]
    (is (= 8 (count-selectable-asset-rows hiccup)))
    (is (not (contains? strings "Showing 120 of 8 markets")))))

(deftest asset-list-virtual-window-tracks-scroll-position-test
  (let [assets (vec (for [n (range 200)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        top-hiccup (view/asset-list assets nil nil #{} #{} #{} 120 0)
        deep-hiccup (view/asset-list assets nil nil #{} #{} #{} 120 2200)
        top-strings (set (collect-strings top-hiccup))
        deep-strings (set (collect-strings deep-hiccup))]
    (is (contains? top-strings "T0-USDC"))
    (is (not (contains? deep-strings "T0-USDC")))
    (is (contains? deep-strings "T90-USDC"))))

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

(deftest asset-selector-loading-state-test
  (let [base-props {:visible? true
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
                    :highlighted-market-key nil}
        full-view (view/asset-selector-dropdown (assoc base-props :loading? true :phase :full))
        bootstrap-view (view/asset-selector-dropdown (assoc base-props :loading? true :phase :bootstrap))
        full-strings (set (collect-strings full-view))
        bootstrap-strings (set (collect-strings bootstrap-view))]
    (is (contains? full-strings "Loading markets..."))
    (is (contains? bootstrap-strings "Loading markets (bootstrap)..."))))

(deftest asset-selector-dropdown-renders-shortcut-footer-and-keydown-dispatch-test
  (let [dropdown (view/asset-selector-dropdown
                   {:visible? true
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
        attrs (second dropdown)
        strings (set (collect-strings dropdown))
        navigate-icon (find-first-node
                        dropdown
                        (fn [candidate]
                          (and (vector? candidate)
                               (= :svg (first candidate))
                               (= "0 0 22 13"
                                  (get-in candidate [1 :viewBox])))))]
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
