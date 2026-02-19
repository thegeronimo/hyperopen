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
      (is (= :spot (:market-type (first results)))))))

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

(defn- find-buttons-with-text [node text]
  (cond
    (vector? node)
    (let [children (node-children node)
          button? (and (keyword? (first node))
                       (str/starts-with? (name (first node)) "button"))
          text? (some #(= text %) (collect-strings node))
          self-match (when (and button? text?) [node])]
      (concat self-match (mapcat #(find-buttons-with-text % text) children)))

    (seq? node)
    (mapcat #(find-buttons-with-text % text) node)

    :else []))

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

(deftest asset-list-renders-footer-controls-outside-scroll-container-test
  (let [assets (vec (for [n (range 150)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        hiccup (view/asset-list assets nil #{} #{} #{} 40)
        children (vec (node-children hiccup))
        scroll-container (first children)
        footer-container (second children)
        load-more-button (first (find-buttons-with-text footer-container "Load more"))
        show-all-button (first (find-buttons-with-text footer-container "Show all"))]
    (is (= 2 (count children)))
    (is (some? scroll-container))
    (is (some? footer-container))
    (is (= [[:actions/increase-asset-selector-render-limit]]
           (get-in (second load-more-button) [:on :click])))
    (is (= [[:actions/show-all-asset-selector-markets]]
           (get-in (second show-all-button) [:on :click])))))

(deftest asset-list-wires-scroll-action-and-renders-progressive-chunk-test
  (let [assets (vec (for [n (range 150)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        hiccup (view/asset-list assets nil #{} #{} #{} 40)
        scroll-container (first (node-children hiccup))
        attrs (second scroll-container)
        strings (set (collect-strings hiccup))]
    (is (= [[:actions/maybe-increase-asset-selector-render-limit
             [:event.target/scrollTop]]]
           (get-in attrs [:on :scroll])))
    (is (= 40 (count-selectable-asset-rows hiccup)))
    (is (contains? strings "Showing 40 of 150 markets"))
    (is (contains? strings "Load more"))
    (is (contains? strings "Show all"))))

(deftest asset-list-renders-all-rows-when-render-limit-exceeds-total-test
  (let [assets (vec (for [n (range 8)]
                      {:key (str "perp:T" n)
                       :symbol (str "T" n "-USDC")
                       :coin (str "T" n)
                       :base (str "T" n)
                       :market-type :perp}))
        hiccup (view/asset-list assets nil #{} #{} #{} 120)
        strings (set (collect-strings hiccup))]
    (is (= 8 (count-selectable-asset-rows hiccup)))
    (is (not (contains? strings "Showing 120 of 8 markets")))))

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
          hiccup (view/asset-list-item asset false #{} #{} #{})
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
        hiccup (view/asset-list-item asset false #{} #{} #{})
        strings (set (collect-strings hiccup))]
    (is (contains? strings "—"))
    (is (not (contains? strings "+0.00 (0.00%)")))))

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
                    :missing-icons #{}}
        full-view (view/asset-selector-dropdown (assoc base-props :loading? true :phase :full))
        bootstrap-view (view/asset-selector-dropdown (assoc base-props :loading? true :phase :bootstrap))
        full-strings (set (collect-strings full-view))
        bootstrap-strings (set (collect-strings bootstrap-view))]
    (is (contains? full-strings "Loading markets..."))
    (is (contains? bootstrap-strings "Loading markets (bootstrap)..."))))

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
        row (view/asset-list-item asset false #{} #{} #{})
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
        row (view/asset-list-item asset false #{} #{} #{})
        classes (set (collect-all-classes row))]
    (is (contains? classes "h-12"))
    (is (contains? classes "box-border"))
    (is (contains? classes "border-b"))
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
        row (view/asset-list-item asset false #{} #{} #{})
        img-node (find-first-node
                   row
                   (fn [candidate]
                     (and (vector? candidate)
                          (keyword? (first candidate))
                          (str/starts-with? (name (first candidate)) "img"))))]
    (is (nil? img-node))))
