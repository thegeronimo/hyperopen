(ns hyperopen.views.active-asset-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.active-asset-view :as view]))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings node)
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- collect-path-ds [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          d-value (:d attrs)
          children (if (map? (second node))
                     (drop 2 node)
                     (drop 1 node))]
      (concat (when d-value [d-value])
              (mapcat collect-path-ds children)))

    (seq? node)
    (mapcat collect-path-ds node)

    :else
    []))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- contains-class? [node class-name]
  (letfn [(walk [n]
            (cond
              (vector? n)
              (let [attrs (when (map? (second n)) (second n))
                    children (if attrs (drop 2 n) (drop 1 n))
                    class-set (set (class-values (:class attrs)))]
                (or (contains? class-set class-name)
                    (some walk children)))

              (seq? n)
              (some walk n)

              :else
              nil))]
    (boolean (walk node))))

(defn- find-first-img-node [node]
  (letfn [(walk [n]
            (cond
              (vector? n)
              (let [tag (first n)
                    attrs (when (map? (second n)) (second n))
                    children (if attrs (drop 2 n) (drop 1 n))]
                (if (and (keyword? tag)
                         (str/starts-with? (name tag) "img"))
                  n
                  (some walk children)))

              (seq? n)
              (some walk n)

              :else
              nil))]
    (walk node)))

(deftest active-asset-row-symbol-fallback-test
  (let [ctx-data {:coin "SOL"
                  :mark 87.0
                  :oracle 86.9
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        ;; Simulates malformed/partial market state missing display fields.
        market {:market-type :perp}
        view-node (view/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})
        strings (set (collect-strings view-node))]
    (is (contains? strings "SOL"))))

(deftest active-asset-list-spot-id-market-resolution-fallback-test
  (let [full-state {:active-asset "@1"
                    :active-market nil
                    :asset-selector {:missing-icons #{}
                                     :market-by-key {"spot:@1" {:key "spot:@1"
                                                                 :coin "@1"
                                                                 :symbol "HYPE/USDC"
                                                                 :base "HYPE"
                                                                 :quote "USDC"
                                                                 :market-type :spot
                                                                 :mark 10.0
                                                                 :markRaw "10.0"
                                                                 :change24h 1.0
                                                                 :change24hPct 11.11
                                                                 :volume24h 100000.0}}}}
        view-node (view/active-asset-list {} {:visible-dropdown nil} full-state)
        strings (set (collect-strings view-node))]
    (is (contains? strings "HYPE/USDC"))
    (is (contains? strings "SPOT"))
    (is (not (contains? strings "Loading...")))))

(deftest asset-icon-spot-includes-chevron-test
  (let [spot-market {:key "spot:@1"
                     :coin "@1"
                     :symbol "HYPE/USDC"
                     :base "HYPE"
                     :market-type :spot}
        icon-node (view/asset-icon spot-market false #{} #{})
        path-ds (set (collect-path-ds icon-node))]
    (is (contains? path-ds "M19 9l-7 7-7-7"))))

(deftest asset-icon-renders-image-immediately-and-wires-load-events-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp}
        icon-node (view/asset-icon market false #{} #{})
        img-node (find-first-img-node icon-node)
        attrs (second img-node)
        classes (set (class-values (:class attrs)))]
    (is (some? img-node))
    (is (not (contains? classes "hidden")))
    (is (not (contains? classes "opacity-0")))
    (is (= [[:actions/mark-loaded-asset-icon "perp:BTC"]]
           (get-in attrs [:on :load])))))

(deftest asset-icon-renders-namespaced-icon-for-component-markets-test
  (let [market {:key "perp:xyz:XYZ100"
                :coin "xyz:XYZ100"
                :symbol "XYZ100-USDC"
                :base "XYZ100"
                :dex "xyz"
                :market-type :perp}
        icon-node (view/asset-icon market false #{} #{})
        img-node (find-first-img-node icon-node)
        attrs (second img-node)]
    (is (some? img-node))
    (is (= "https://app.hyperliquid.xyz/coins/xyz:XYZ100.svg"
           (:src attrs)))))

(deftest asset-icon-renders-cross-dex-alias-icon-when-primary-key-missing-test
  (let [market {:key "perp:cash:MSFT"
                :coin "cash:MSFT"
                :symbol "MSFT-USDT0"
                :base "MSFT"
                :dex "cash"
                :market-type :perp}
        icon-node (view/asset-icon market false #{} #{})
        img-node (find-first-img-node icon-node)
        attrs (second img-node)]
    (is (some? img-node))
    (is (= "https://app.hyperliquid.xyz/coins/xyz:MSFT.svg"
           (:src attrs)))))

(deftest active-asset-row-uses-app-shell-left-gutter-test
  (let [ctx-data {:coin "SOL"
                  :mark 87.0
                  :oracle 86.9
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        market {:coin "SOL"
                :symbol "SOL"
                :base "SOL"
                :market-type :perp}
        view-node (view/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})]
    (is (contains-class? view-node "app-shell-gutter-left"))))

(deftest active-asset-row-applies-numeric-utility-to-live-values-test
  (let [ctx-data {:coin "SOL"
                  :mark 87.0
                  :markRaw "87.0"
                  :oracle 86.9
                  :oracleRaw "86.9"
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        market {:coin "SOL"
                :symbol "SOL"
                :base "SOL"
                :market-type :perp}
        view-node (view/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})]
    (is (contains-class? view-node "num"))))

(deftest active-asset-row-prioritizes-symbol-column-during-resize-test
  (let [ctx-data {:coin "SOL-USD"
                  :mark 87.0
                  :markRaw "87.0"
                  :oracle 86.9
                  :oracleRaw "86.9"
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        market {:coin "SOL-USD"
                :symbol "SOL-USD"
                :base "SOL"
                :market-type :perp}
        view-node (view/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})]
    (is (contains-class? view-node "md:grid-cols-[minmax(max-content,1.4fr)_minmax(0,0.9fr)_minmax(0,0.9fr)_minmax(0,1.1fr)_minmax(0,1.1fr)_minmax(0,1.2fr)_minmax(0,1.6fr)]"))))

(deftest active-asset-row-renders-dex-and-leverage-chips-test
  (let [ctx-data {:coin "XYZ100-USDC"
                  :mark 87.0
                  :oracle 86.9
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        market {:coin "XYZ100-USDC"
                :symbol "XYZ100-USDC"
                :base "XYZ100"
                :dex "xyz"
                :maxLeverage 25
                :market-type :perp}
        view-node (view/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})
        strings (set (collect-strings view-node))]
    (is (contains? strings "xyz"))
    (is (contains? strings "25x"))
    (is (contains-class? view-node "bg-emerald-500/20"))
    (is (not (contains-class? view-node "bg-primary")))))

(deftest active-asset-row-renders-coin-namespace-chip-when-dex-missing-test
  (let [ctx-data {:coin "xyz:XYZ100-USDC"
                  :mark 87.0
                  :oracle 86.9
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        market {:coin "xyz:XYZ100-USDC"
                :symbol "XYZ100-USDC"
                :base "XYZ100"
                :maxLeverage 25
                :market-type :perp}
        view-node (view/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})
        strings (set (collect-strings view-node))]
    (is (contains? strings "xyz"))))

(deftest active-asset-panel-passes-scroll-top-to-selector-wrapper-test
  (let [captured-props (atom nil)
        dropdown-state {:visible-dropdown :asset-selector
                        :search-term ""
                        :sort-by :volume
                        :sort-direction :desc
                        :favorites #{}
                        :favorites-only? false
                        :missing-icons #{}
                        :loaded-icons #{}
                        :scroll-top 144
                        :render-limit 120
                        :strict? false
                        :active-tab :all}
        full-state {:active-asset nil
                    :asset-selector {:markets [{:key "perp:BTC"
                                                :coin "BTC"
                                                :symbol "BTC-USDC"
                                                :market-type :perp}]}}]
    (with-redefs [hyperopen.views.asset-selector-view/asset-selector-wrapper
                  (fn [props]
                    (reset! captured-props props)
                    [:div])]
      (view/active-asset-panel {} false dropdown-state full-state))
    (is (= 144 (:scroll-top @captured-props)))))
