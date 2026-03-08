(ns hyperopen.views.active-asset-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.system :as app-system]
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

(defn- find-img-nodes [node]
  (letfn [(walk [n]
            (cond
              (vector? n)
              (let [tag (first n)
                    attrs (when (map? (second n)) (second n))
                    children (if attrs (drop 2 n) (drop 1 n))
                    child-results (mapcat walk children)]
                (if (and (keyword? tag)
                         (str/starts-with? (name tag) "img"))
                  (cons n child-results)
                  child-results))

              (seq? n)
              (mapcat walk n)

              :else
              []))]
    (vec (walk node))))

(defn- find-nodes-with-style-key [node style-key]
  (letfn [(walk [n]
            (cond
              (vector? n)
              (let [attrs (when (map? (second n)) (second n))
                    children (if attrs (drop 2 n) (drop 1 n))
                    child-results (mapcat walk children)]
                (if (contains? (or (:style attrs) {}) style-key)
                  (cons n child-results)
                  child-results))

              (seq? n)
              (mapcat walk n)

              :else
              []))]
    (vec (walk node))))

(defn- fake-image-node
  [{:keys [complete? natural-width]}]
  (let [listeners (atom {})
        removed-listeners (atom [])]
    {:node (doto (js-obj)
             (aset "complete" (boolean complete?))
             (aset "naturalWidth" (or natural-width 0))
             (aset "addEventListener"
                   (fn [event handler]
                     (swap! listeners assoc event handler)))
             (aset "removeEventListener"
                   (fn [event handler]
                     (swap! removed-listeners conj [event handler]))))
     :listeners listeners
     :removed-listeners removed-listeners}))

(defn- funding-tooltip-pin-id
  [coin]
  (str "funding-rate-tooltip-pin-"
       (-> (or coin "asset")
           str
           str/lower-case
           (str/replace #"[^a-z0-9_-]" "-"))))

(defn- with-visible-funding-tooltip
  [state coin]
  (assoc-in state
            [:funding-ui :tooltip :visible-id]
            (funding-tooltip-pin-id coin)))

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
        img-attrs (->> (find-img-nodes icon-node)
                       (map second))
        img-srcs (->> img-attrs
                      (map :src)
                      (remove nil?)
                      set)
        img-classes (->> img-attrs
                         (mapcat #(class-values (:class %)))
                         set)
        icon-layer (first (find-nodes-with-style-key icon-node :background-image))
        background-image (get-in icon-layer [1 :style :background-image])
        strings (set (collect-strings icon-node))
        path-ds (set (collect-path-ds icon-node))]
    (is (not (contains? strings "HYPE")))
    (is (contains? img-srcs "https://app.hyperliquid.xyz/coins/HYPE_spot.svg"))
    (is (not (contains? img-classes "bg-white")))
    (is (contains? img-classes "opacity-0"))
    (is (= "url('https://app.hyperliquid.xyz/coins/HYPE_spot.svg')"
           background-image))
    (is (contains? path-ds "M19 9l-7 7-7-7"))))

(deftest asset-icon-renders-neutral-surface-while-probing-and-registers-render-hook-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp}
        icon-node (view/asset-icon market false #{} #{})
        img-node (first (find-img-nodes icon-node))
        attrs (second img-node)
        classes (set (class-values (:class attrs)))
        icon-layer (first (find-nodes-with-style-key icon-node :background-image))
        background-image (get-in icon-layer [1 :style :background-image])
        strings (set (collect-strings icon-node))]
    (is (some? img-node))
    (is (not (contains? strings "BTC")))
    (is (not (contains? classes "bg-white")))
    (is (contains? classes "opacity-0"))
    (is (= "url('https://app.hyperliquid.xyz/coins/BTC.svg')"
           background-image))
    (is (fn? (:replicant/on-render attrs)))))

(deftest asset-icon-probe-hook-dispatches-loaded-for-complete-images-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp}
        icon-node (view/asset-icon market false #{} #{})
        probe-attrs (-> icon-node find-img-nodes first second)
        on-render (:replicant/on-render probe-attrs)
        remembered (atom nil)
        store (atom {:asset-selector {:loaded-icons #{}
                                      :missing-icons #{}}})
        {node :node listeners :listeners} (fake-image-node {:complete? true
                                                            :natural-width 48})]
    (with-redefs [app-system/store store]
      (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                  :replicant/node node
                  :replicant/remember (fn [memory]
                                        (reset! remembered memory))}))
    (is (contains? @listeners "load"))
    (is (contains? @listeners "error"))
    (is (= #{"perp:BTC"} (get-in @store [:asset-selector :loaded-icons])))
    (is (= #{} (get-in @store [:asset-selector :missing-icons])))
    (is (= "https://app.hyperliquid.xyz/coins/BTC.svg"
           (:src @remembered)))))

(deftest asset-icon-probe-hook-dispatches-missing-for-complete-broken-images-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp}
        icon-node (view/asset-icon market false #{} #{})
        probe-attrs (-> icon-node find-img-nodes first second)
        on-render (:replicant/on-render probe-attrs)
        store (atom {:asset-selector {:loaded-icons #{}
                                      :missing-icons #{}}})
        {node :node} (fake-image-node {:complete? true
                                       :natural-width 0})]
    (with-redefs [app-system/store store]
      (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                  :replicant/node node
                  :replicant/remember (fn [_] nil)}))
    (is (= #{} (get-in @store [:asset-selector :loaded-icons])))
    (is (= #{"perp:BTC"} (get-in @store [:asset-selector :missing-icons])))))

(deftest asset-icon-renders-visible-image-when-icon-is-marked-loaded-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp}
        icon-node (view/asset-icon market false #{} #{"perp:BTC"})
        img-nodes (find-img-nodes icon-node)
        img-node (first img-nodes)
        attrs (second img-node)
        classes (set (class-values (:class attrs)))
        strings (set (collect-strings icon-node))]
    (is (= 1 (count img-nodes)))
    (is (= "https://app.hyperliquid.xyz/coins/BTC.svg"
           (:src attrs)))
    (is (contains? classes "object-contain"))
    (is (not (contains? classes "bg-white")))
    (is (not (contains? classes "opacity-0")))
    (is (not (contains? strings "BTC")))
    (is (empty? (find-nodes-with-style-key icon-node :background-image)))))

(deftest asset-icon-falls-back-to-monogram-when-icon-is-known-missing-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp}
        icon-node (view/asset-icon market false #{"perp:BTC"} #{})
        img-nodes (find-img-nodes icon-node)
        strings (set (collect-strings icon-node))]
    (is (empty? img-nodes))
    (is (contains? strings "BTC"))))

(deftest asset-icon-renders-namespaced-icon-for-component-markets-test
  (let [market {:key "perp:xyz:XYZ100"
                :coin "xyz:XYZ100"
                :symbol "XYZ100-USDC"
                :base "XYZ100"
                :dex "xyz"
                :market-type :perp}
        icon-node (view/asset-icon market false #{} #{})
        img-node (first (find-img-nodes icon-node))
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
        img-node (first (find-img-nodes icon-node))
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

(deftest tooltip-click-pinnable-dismiss-target-clears-visible-state-test
  (let [pin-id (funding-tooltip-pin-id "BTC")
        tooltip-node (view/tooltip [[:span "Funding"] [:div "Body"]]
                                   "top"
                                   {:click-pinnable? true
                                    :open? true
                                    :pin-id pin-id
                                    :pinned? true})
        dismiss-target (find-first-node tooltip-node
                                        #(contains? (set (class-values (get-in % [1 :class])))
                                                    "fixed"))]
    (is (= [[:actions/set-funding-tooltip-visible pin-id false]]
           (rest (get-in dismiss-target [1 :on :click]))))
    (is (= [[:actions/set-funding-tooltip-pinned pin-id false]]
           [(first (get-in dismiss-target [1 :on :click]))]))))

(deftest tooltip-click-pinnable-trigger-toggles-pinned-state-test
  (let [pin-id (funding-tooltip-pin-id "BTC")
        closed-tooltip (view/tooltip [[:span "Funding"] [:div "Body"]]
                                     "top"
                                     {:click-pinnable? true
                                      :open? false
                                      :pin-id pin-id
                                      :pinned? false})
        open-tooltip (view/tooltip [[:span "Funding"] [:div "Body"]]
                                   "top"
                                   {:click-pinnable? true
                                    :open? true
                                    :pin-id pin-id
                                    :pinned? true})
        closed-trigger (find-first-node closed-tooltip #(= :button (first %)))
        open-trigger (find-first-node open-tooltip #(= :button (first %)))]
    (is (= [[:actions/set-funding-tooltip-pinned pin-id true]
            [:actions/set-funding-tooltip-visible pin-id true]]
           (get-in closed-trigger [1 :on :click])))
    (is (= [[:actions/set-funding-tooltip-pinned pin-id false]
            [:actions/set-funding-tooltip-visible pin-id false]]
           (get-in open-trigger [1 :on :click])))))

(deftest tooltip-click-pinnable-renders-body-when-open-test
  (let [pin-id (funding-tooltip-pin-id "BTC")
        closed-tooltip (view/tooltip [[:span "Funding"] [:div "Body"]]
                                     "top"
                                     {:click-pinnable? true
                                      :open? false
                                      :pin-id pin-id
                                      :pinned? false})
        open-tooltip (view/tooltip [[:span "Funding"] [:div "Body"]]
                                   "top"
                                   {:click-pinnable? true
                                    :open? true
                                    :pin-id pin-id
                                    :pinned? true})]
    (is (not (contains? (set (collect-strings closed-tooltip)) "Body")))
    (is (contains? (set (collect-strings open-tooltip)) "Body"))))

(deftest active-asset-row-skips-funding-tooltip-derivation-when-closed-test
  (let [ctx-data {:coin "xyz:GOLD"
                  :mark 5000.0
                  :oracle 4998.0
                  :change24h 5.0
                  :change24hPct 0.5
                  :volume24h 2000000
                  :openInterest 200
                  :fundingRate 0.01}
        market {:coin "xyz:GOLD"
                :symbol "GOLD-USDC"
                :base "GOLD"
                :market-type :perp}
        full-state {:active-asset "xyz:GOLD"
                    :asset-selector {:missing-icons #{}}}
        cache* @#'hyperopen.views.active-asset-view/funding-tooltip-model-cache]
    (reset! cache* nil)
    (with-redefs [hyperopen.utils.formatting/format-funding-countdown
                  (fn [] "00:10:00")]
      (view/active-asset-row ctx-data market {:visible-dropdown nil} full-state))
    (is (nil? @cache*))))

(deftest active-asset-row-funding-tooltip-memoizes-by-summary-signature-test
  (let [memoized-tooltip @#'hyperopen.views.active-asset-view/memoized-funding-tooltip-model
        cache* @#'hyperopen.views.active-asset-view/funding-tooltip-model-cache
        position {:coin "xyz:GOLD"
                  :szi "1"
                  :positionValue "5000"}
        market {:coin "xyz:GOLD"
                :symbol "GOLD-USDC"
                :base "GOLD"
                :market-type :perp}
        summary-1 {:mean 0.1008
                   :stddev 0.0916108152
                   :daily-funding-series [{:day-index 1 :daily-rate 0.0288}
                                          {:day-index 2 :daily-rate -0.0096}]
                   :autocorrelation {:lag-1d {:value 0.714}
                                     :lag-5d {:value 0.482}
                                     :lag-15d {:value 0.21}}
                   :autocorrelation-series [{:lag-days 1 :value 0.714}
                                            {:lag-days 2 :value 0.55}]}
        summary-2 {:mean 0.1008
                   :stddev 0.0916108152
                   :daily-funding-series [{:day-index 1 :daily-rate 0.0288}
                                          {:day-index 2 :daily-rate -0.0096}]
                   :autocorrelation {:lag-1d {:value 0.714}
                                     :lag-5d {:value 0.482}
                                     :lag-15d {:value 0.21}}
                   :autocorrelation-series [{:lag-days 1 :value 0.714}
                                            {:lag-days 2 :value 0.55}]}
        summary-3 {:mean 0.0900
                   :stddev 0.0916108152
                   :daily-funding-series [{:day-index 1 :daily-rate 0.0288}
                                          {:day-index 2 :daily-rate -0.0096}]
                   :autocorrelation {:lag-1d {:value 0.714}
                                     :lag-5d {:value 0.482}
                                     :lag-15d {:value 0.21}}
                   :autocorrelation-series [{:lag-days 1 :value 0.714}
                                            {:lag-days 2 :value 0.55}]}
        tooltip-1 (do
                    (reset! cache* nil)
                    (memoized-tooltip position
                                      market
                                      "xyz:GOLD"
                                      5000.0
                                      0.01
                                      {:summary summary-1
                                       :loading? false
                                       :error nil}
                                      nil
                                      nil))
        tooltip-2 (memoized-tooltip {:coin "xyz:GOLD"
                                     :szi "1"
                                     :positionValue "5000"}
                                    {:coin "xyz:GOLD"
                                     :symbol "GOLD-USDC"
                                     :base "GOLD"
                                     :market-type :perp}
                                    "xyz:GOLD"
                                    5000.0
                                    0.01
                                    {:summary summary-2
                                     :loading? false
                                     :error nil}
                                    nil
                                    nil)
        tooltip-3 (memoized-tooltip {:coin "xyz:GOLD"
                                     :szi "1"
                                     :positionValue "5000"}
                                    {:coin "xyz:GOLD"
                                     :symbol "GOLD-USDC"
                                     :base "GOLD"
                                     :market-type :perp}
                                    "xyz:GOLD"
                                    5000.0
                                    0.01
                                    {:summary summary-3
                                     :loading? false
                                     :error nil}
                                    nil
                                    nil)]
    (is (identical? tooltip-1 tooltip-2))
    (is (not (identical? tooltip-2 tooltip-3)))))

(deftest active-asset-row-funding-tooltip-shows-position-projections-test
  (let [ctx-data {:coin "xyz:GOLD"
                  :mark 5372.43
                  :oracle 5370.0
                  :change24h 10.0
                  :change24hPct 1.5
                  :volume24h 1000000
                  :openInterest 100
                  :fundingRate 0.0056}
        market {:coin "xyz:GOLD"
                :symbol "GOLD-USDC"
                :base "GOLD"
                :market-type :perp}
        full-state (with-visible-funding-tooltip
                     {:active-asset "xyz:GOLD"
                      :asset-selector {:missing-icons #{}}}
                     "xyz:GOLD")]
    (with-redefs [hyperopen.state.trading/position-for-active-asset
                  (fn [_]
                    {:coin "xyz:GOLD"
                     :szi "0.0185"
                     :positionValue "99.39"})
                  hyperopen.utils.formatting/format-funding-countdown
                  (fn [] "00:22:01")]
      (let [view-node (view/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (collect-strings view-node))
            rate-node (find-first-node view-node
                                       #(and (= :span (first %))
                                             (contains? (set (collect-strings %)) "+0.1344%")))
            payment-node (find-first-node view-node
                                          #(and (= :span (first %))
                                                (contains? (set (collect-strings %)) "-$0.13")))
            rate-classes (set (class-values (get-in rate-node [1 :class])))
            payment-classes (set (class-values (get-in payment-node [1 :class])))]
        (is (contains? strings "Position"))
        (is (contains? strings "Projections"))
        (is (contains? strings "Predictability (30d)"))
        (is (contains? strings "Size"))
        (is (contains? strings "Value"))
        (is (contains? strings "Rate"))
        (is (contains? strings "Payment"))
        (is (contains? strings "Long 0.0185 GOLD"))
        (is (contains? strings "$99.39"))
        (is (not (contains? strings "Current in 22:01")))
        (is (contains? strings "Next 24h"))
        (is (contains? strings "APY"))
        (is (not (contains? strings "Next 24h *")))
        (is (not (contains? strings "APY *")))
        (is (contains? strings "+0.1344%"))
        (is (contains? strings "+49.0560%"))
        (is (not (contains? strings "-$0.01")))
        (is (contains? strings "-$0.13"))
        (is (contains? strings "-$48.76"))
        (is (contains? rate-classes "justify-self-end"))
        (is (not (contains? rate-classes "justify-self-center")))
        (is (not (contains? rate-classes "text-left")))
        (is (not (contains? rate-classes "text-center")))
        (is (contains? payment-classes "text-left"))
        (is (not (contains? payment-classes "text-center")))))))

(deftest active-asset-row-funding-tooltip-short-position-shows-positive-payment-test
  (let [ctx-data {:coin "xyz:GOLD"
                  :mark 5000.0
                  :oracle 4998.0
                  :change24h 5.0
                  :change24hPct 0.5
                  :volume24h 2000000
                  :openInterest 200
                  :fundingRate 0.01}
        market {:coin "xyz:GOLD"
                :symbol "GOLD-USDC"
                :base "GOLD"
                :market-type :perp}
        full-state (with-visible-funding-tooltip
                     {:active-asset "xyz:GOLD"
                      :asset-selector {:missing-icons #{}}}
                     "xyz:GOLD")]
    (with-redefs [hyperopen.state.trading/position-for-active-asset
                  (fn [_]
                    {:coin "xyz:GOLD"
                     :szi "-2"
                     :positionValue "1500"})
                  hyperopen.utils.formatting/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (view/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (collect-strings view-node))]
        (is (contains? strings "Short 2 GOLD"))
        (is (not (contains? strings "+$0.15")))
        (is (contains? strings "+$3.60"))
        (is (contains? strings "+$1,314.00"))))))

(deftest active-asset-row-funding-tooltip-uses-hypothetical-position-when-no-open-position-test
  (let [ctx-data {:coin "xyz:GOLD"
                  :mark 5000.0
                  :oracle 4998.0
                  :change24h 5.0
                  :change24hPct 0.5
                  :volume24h 2000000
                  :openInterest 200
                  :fundingRate 0.0056}
        market {:coin "xyz:GOLD"
                :symbol "GOLD-USDC"
                :base "GOLD"
                :market-type :perp}
        full-state (with-visible-funding-tooltip
                     {:active-asset "xyz:GOLD"
                      :asset-selector {:missing-icons #{}}}
                     "xyz:GOLD")]
    (with-redefs [hyperopen.state.trading/position-for-active-asset
                  (fn [_]
                    nil)
                  hyperopen.utils.formatting/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (view/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (collect-strings view-node))]
        (is (contains? strings "Hypothetical Position"))
        (is (contains? strings "Edit size or value to estimate payments. Use negative size or value for short."))
        (is (contains? strings "-$1.34"))
        (is (contains? strings "-$490.56"))
        (is (not (contains? strings "No open position")))))))

(deftest active-asset-row-funding-tooltip-uses-negative-hypothetical-value-for-short-direction-test
  (let [ctx-data {:coin "xyz:GOLD"
                  :mark 5000.0
                  :oracle 4998.0
                  :change24h 5.0
                  :change24hPct 0.5
                  :volume24h 2000000
                  :openInterest 200
                  :fundingRate 0.0056}
        market {:coin "xyz:GOLD"
                :symbol "GOLD-USDC"
                :base "GOLD"
                :market-type :perp}
        full-state (with-visible-funding-tooltip
                     {:active-asset "xyz:GOLD"
                      :asset-selector {:missing-icons #{}}
                      :funding-ui {:hypothetical-position-by-coin {"XYZ:GOLD"
                                                                   {:size-input "oops"
                                                                    :value-input "-1000.00"}}}}
                     "xyz:GOLD")]
    (with-redefs [hyperopen.state.trading/position-for-active-asset
                  (fn [_]
                    nil)
                  hyperopen.utils.formatting/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (view/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (collect-strings view-node))]
        (is (contains? strings "Hypothetical Position"))
        (is (contains? strings "+$1.34"))
        (is (contains? strings "+$490.56"))
        (is (not (contains? strings "-$490.56")))))))

(deftest active-asset-row-funding-tooltip-parses-localized-hypothetical-value-input-test
  (let [ctx-data {:coin "xyz:GOLD"
                  :mark 5000.0
                  :oracle 4998.0
                  :change24h 5.0
                  :change24hPct 0.5
                  :volume24h 2000000
                  :openInterest 200
                  :fundingRate 0.0056}
        market {:coin "xyz:GOLD"
                :symbol "GOLD-USDC"
                :base "GOLD"
                :market-type :perp}
        full-state (with-visible-funding-tooltip
                     {:active-asset "xyz:GOLD"
                      :ui {:locale "fr-FR"}
                      :asset-selector {:missing-icons #{}}
                      :funding-ui {:hypothetical-position-by-coin {"XYZ:GOLD"
                                                                   {:size-input "oops"
                                                                    :value-input "-1000,00"}}}}
                     "xyz:GOLD")]
    (with-redefs [hyperopen.state.trading/position-for-active-asset
                  (fn [_]
                    nil)
                  hyperopen.utils.formatting/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (view/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (collect-strings view-node))]
        (is (contains? strings "Hypothetical Position"))
        (is (contains? strings "+$1.34"))
        (is (contains? strings "+$490.56"))
        (is (not (contains? strings "-$490.56")))))))

(deftest active-asset-row-funding-tooltip-renders-predictability-metrics-test
  (let [ctx-data {:coin "xyz:GOLD"
                  :mark 5000.0
                  :oracle 4998.0
                  :change24h 5.0
                  :change24hPct 0.5
                  :volume24h 2000000
                  :openInterest 200
                  :fundingRate 0.01}
        market {:coin "xyz:GOLD"
                :symbol "GOLD-USDC"
                :base "GOLD"
                :market-type :perp}
        full-state (with-visible-funding-tooltip
                     {:active-asset "xyz:GOLD"
                      :asset-selector {:missing-icons #{}}
                      :active-assets {:funding-predictability {:by-coin {"XYZ:GOLD"
                                                                         {:mean 0.1008
                                                                          :stddev 0.0916108152
                                                                          :daily-funding-series [{:day-index 1 :daily-rate 0.0288}
                                                                                                {:day-index 2 :daily-rate -0.0096}
                                                                                                {:day-index 3 :daily-rate 0.0192}]
                                                                          :autocorrelation {:lag-1d {:value 0.714}
                                                                                            :lag-5d {:value 0.482}
                                                                                            :lag-15d {:value 0.21}}
                                                                          :autocorrelation-series [{:lag-days 1 :value 0.714}
                                                                                                   {:lag-days 2 :value 0.55}
                                                                                                   {:lag-days 3 :value 0.44}]}}
                                                            :loading-by-coin {}
                                                            :error-by-coin {}}}} 
                     "xyz:GOLD")]
    (with-redefs [hyperopen.state.trading/position-for-active-asset
                  (fn [_]
                    {:coin "xyz:GOLD"
                     :szi "1"
                     :positionValue "5000"})
                  hyperopen.utils.formatting/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (view/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (collect-strings view-node))]
        (is (contains? strings "Predictability (30d)"))
        (is (contains? strings "Mean APY"))
        (is (contains? strings "Volatility"))
        (is (not (contains? strings "ACF Lag 1d")))
        (is (not (contains? strings "ACF Lag 5d")))
        (is (not (contains? strings "ACF Lag 15d")))
        (is (contains? strings "Rate History"))
        (is (contains? strings "Past Rate Correlation"))
        (is (contains? strings "+3679.2000%"))
        (is (contains? strings "-$183,960.00"))
        (is (contains? strings "175.0222%"))
        (is (contains? strings "-$175,208.89 to -$192,711.11"))))))

(deftest active-asset-row-funding-tooltip-renders-predictability-loading-and-insufficient-copy-test
  (let [ctx-data {:coin "xyz:GOLD"
                  :mark 5000.0
                  :oracle 4998.0
                  :change24h 5.0
                  :change24hPct 0.5
                  :volume24h 2000000
                  :openInterest 200
                  :fundingRate 0.01}
        market {:coin "xyz:GOLD"
                :symbol "GOLD-USDC"
                :base "GOLD"
                :market-type :perp}
        full-state (with-visible-funding-tooltip
                     {:active-asset "xyz:GOLD"
                      :asset-selector {:missing-icons #{}}
                      :active-assets {:funding-predictability {:by-coin {"XYZ:GOLD"
                                                                         {:mean 0.1008
                                                                          :stddev 0.0916108152
                                                                          :daily-funding-series [{:day-index 1 :daily-rate 0.0288}
                                                                                                {:day-index 2 :daily-rate nil}
                                                                                                {:day-index 3 :daily-rate -0.0144}]
                                                                          :autocorrelation {:lag-1d {:value 0.714}
                                                                                            :lag-5d {:value 0.482}
                                                                                            :lag-15d {:value nil
                                                                                                      :lag-days 15
                                                                                                      :minimum-daily-count 16
                                                                                                      :insufficient? true}}
                                                                          :autocorrelation-series [{:lag-days 1 :value 0.714}
                                                                                                   {:lag-days 2 :value nil :undefined? true}
                                                                                                   {:lag-days 3 :value -0.12}]}}
                                                            :loading-by-coin {"XYZ:GOLD" true}
                                                            :error-by-coin {}}}}
                     "xyz:GOLD")]
    (with-redefs [hyperopen.state.trading/position-for-active-asset
                  (fn [_]
                    {:coin "xyz:GOLD"
                     :szi "1"
                     :positionValue "5000"})
                  hyperopen.utils.formatting/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [loading-view (view/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            loading-strings (set (collect-strings loading-view))
            ready-state (assoc-in full-state
                                  [:active-assets :funding-predictability :loading-by-coin "XYZ:GOLD"]
                                  false)
            ready-view (view/active-asset-row ctx-data market {:visible-dropdown nil} ready-state)
            ready-strings (set (collect-strings ready-view))]
        (is (contains? loading-strings "Loading 30d stats..."))
        (is (contains? ready-strings "Rate History"))
        (is (contains? ready-strings "Past Rate Correlation"))
        (is (contains? ready-strings "Lag 15d needs at least 16 daily points"))))))
