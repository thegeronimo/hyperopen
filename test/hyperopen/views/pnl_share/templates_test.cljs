(ns hyperopen.views.pnl-share.templates-test
  (:require [clojure.string :as str]
            [cljs.test :refer [deftest is testing]]
            [hyperopen.views.account-info.positions-vm :as positions-vm]
            [hyperopen.views.pnl-share.card-data :as card-data]
            [hyperopen.views.pnl-share.card-view :as card-view]
            [hyperopen.views.pnl-share.svg :as svg]
            [hyperopen.views.pnl-share.templates.number-hero :as number-hero]))

(defn- position-row
  [overrides]
  {:type "oneWay"
   :dex (:dex overrides)
   :position (merge {:coin "SOL"
                     :szi "41.2"
                     :entryPx "142.08"
                     :markPx "168.90"
                     :positionValue "6958.68"
                     :unrealizedPnl "18204.11"
                     :returnOnEquity "2.146"
                     :liquidationPx "98.4"
                     :marginUsed "8481.2"
                     :leverage {:type "cross" :value 20}
                     :cumFunding {:allTime "24.5" :sinceOpen "18.4"}}
                    (dissoc overrides :dex))})

(defn- card-data-for
  ([overrides] (card-data-for overrides {}))
  ([overrides ctx]
   (card-data/position-card-data
    (positions-vm/position-row-vm (position-row overrides))
    (merge {:owner-address "0x1234567890abcdef1234567890abcdef12345678"
            :referral-code "sleepy"
            :site-origin "https://hyperopen.xyz"
            :now-ms 1787356320000
            :fills []}
           ctx))))

(defn- nodes
  "Every hiccup vector in the tree, depth first."
  [tree]
  (tree-seq vector? seq tree))

(defn- strings
  [tree]
  (->> (tree-seq (some-fn vector? seq?) seq tree)
       (filter string?)
       vec))

(defn- attr-maps
  [tree]
  (->> (nodes tree)
       (keep (fn [node]
               (when (and (vector? node)
                          (keyword? (first node))
                          (map? (second node)))
                 (second node))))
       vec))

(defn- attr-keys
  [tree]
  (->> (attr-maps tree)
       (mapcat keys)
       (filter keyword?)
       set))

(defn- fills
  [tree]
  (->> (attr-maps tree)
       (keep :fill)
       (filter string?)
       set))

(deftest both-templates-render-a-standalone-svg-with-explicit-size
  (doseq [template [:neon-arrow :number-hero]]
    (let [tree (card-view/card-svg (card-data-for {} {:template template}))
          attrs (second tree)]
      (is (= :svg (first tree)) (str template " must render an <svg> root"))
      (is (= 1080 (:width attrs))
          "Firefox rasterizes a viewBox-only SVG at zero size")
      (is (= 608 (:height attrs)))
      (is (= "0 0 1080 608" (:viewBox attrs)))
      (is (= "http://www.w3.org/2000/svg" (:xmlns attrs)))
      (is (= "pnl-share-card-svg" (:data-role attrs))))))

(deftest both-templates-print-the-same-numbers-from-the-same-data
  ;; Size is opt-in, so switch it on here: the point of this test is that the
  ;; two templates agree on every value they both carry.
  (let [data (card-data-for {} {:options {:show-size? true}})
        neon (strings (card-view/card-svg (assoc data :template :neon-arrow)))
        hero (strings (card-view/card-svg (assoc data :template :number-hero)))]
    (doseq [value ["+214.6%" "$142.08" "$168.90" "41.2 SOL"]]
      ;; neon-arrow folds size and funding into one footer line, so match on
      ;; containment rather than on a standalone text node.
      (is (some #(str/includes? % value) neon) (str "neon-arrow is missing " value))
      (is (some #(str/includes? % value) hero) (str "number-hero is missing " value)))
    (testing "the artwork differs even though the figures do not"
      (is (not= neon hero)))))

(deftest the-card-says-the-figure-is-unrealized
  (let [neon (strings (card-view/card-svg (card-data-for {} {:template :neon-arrow})))]
    (is (some #(str/includes? % "UNREALIZED") neon)
        "an open position's PnL must be labelled unrealized on a public card")))

(deftest a-losing-card-recolours-and-relabels
  (let [win (card-view/card-svg (card-data-for {} {:template :neon-arrow}))
        loss (card-view/card-svg (card-data-for {:unrealizedPnl "-3946.44"
                                                 :returnOnEquity "-0.384"}
                                                {:template :neon-arrow}))]
    (is (not= (fills win) (fills loss))
        "the loss treatment must repaint, not just change the number")
    (is (some #{"-38.4%"} (strings loss)))
    (is (some #{"CERTIFIED L"} (strings loss)))
    (is (not (some #{"CERTIFIED L"} (strings win))))))

(deftest toggled-off-fields-leave-no-empty-node-behind
  (let [full (card-view/card-svg (card-data-for {} {:template :neon-arrow}))
        stripped (card-view/card-svg
                  (card-data-for {} {:template :neon-arrow
                                     :options {:show-prices? false
                                               :show-funding? false
                                               :show-handle? false}}))]
    (is (some #{"Entry Price"} (strings full)))
    (is (not (some #{"Entry Price"} (strings stripped))))
    (is (not (some #{"Mark Price"} (strings stripped))))
    (is (not (some #{"0x1234…5678"} (strings stripped))))
    (testing "no blank text nodes are left where a field used to be"
      (is (not (some (fn [s] (and (string? s) (str/blank? s)))
                     (strings stripped)))))))

(deftest svg-attribute-keys-follow-the-svg-spec-not-a-guess
  (let [keys* (attr-keys (card-view/card-svg (card-data-for {})))
        ;; Genuinely camelCase in the SVG spec; everything else must be kebab.
        camel-allowed #{:viewBox :gradientUnits :gradientTransform :maskUnits
                        :patternUnits :preserveAspectRatio :clipPathUnits
                        :stdDeviation}
        offenders (->> keys*
                       (remove camel-allowed)
                       (filter (fn [k] (re-find #"[A-Z]" (name k))))
                       set)]
    (is (empty? offenders)
        (str "Replicant writes attribute keys verbatim; these would be ignored: "
             offenders))))

(deftest the-card-never-references-an-external-resource
  (let [tree (card-view/card-svg (card-data-for {}))
        refs (->> (attr-maps tree)
                  (mapcat vals)
                  (filter string?)
                  (filter (fn [v] (re-find #"https?://" v)))
                  (remove #{"http://www.w3.org/2000/svg"})
                  set)]
    (is (empty? refs)
        (str "a rasterized SVG cannot fetch anything, and the coin icon CDN "
             "sends no CORS header: " refs))))

(deftest stat-cells-skip-what-cannot-be-derived
  (is (= [["ENTRY" "$142.08"] ["MARK" "$168.90"] ["FUNDING" "-$18.40"]]
         (number-hero/stat-cells (card-data-for {})))
      "no opening fill is loaded so HELD is absent, and SIZE is off by default")
  (is (= [["ENTRY" "$142.08"] ["MARK" "$168.90"] ["SIZE" "41.2 SOL"] ["FUNDING" "-$18.40"]]
         (number-hero/stat-cells (card-data-for {} {:options {:show-size? true}}))))
  (is (= [["SIZE" "41.2 SOL"]]
         (number-hero/stat-cells
          (card-data-for {} {:options {:show-size? true
                                       :show-prices? false
                                       :show-funding? false}})))))

(deftest monospace-width-maths-is-what-the-layout-depends-on
  (is (= 0 (svg/text-width "" 32)))
  (is (= (* 5 32 0.6) (svg/text-width "hyper" 32)))
  (testing "tracking widens a run by one gap per pair"
    (is (= (+ (* 3 20 0.6) (* 2 20 0.1)) (svg/text-width "abc" 20 0.1)))))

(deftest an-unknown-template-still-renders-a-card
  (let [tree (card-view/card-svg (assoc (card-data-for {}) :template :nope))]
    (is (= :svg (first tree)))
    (is (some #{"+214.6%"} (strings tree)))))
