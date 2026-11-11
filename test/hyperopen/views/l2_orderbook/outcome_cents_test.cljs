(ns hyperopen.views.l2-orderbook.outcome-cents-test
  "Outcome books read in cents; every other book is unchanged."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.l2-orderbook.panel :as panel]
            [hyperopen.views.l2-orderbook.trades :as trades]))

(defn- attrs [node] (when (map? (second node)) (second node)))

(defn- nodes [node pred]
  (cond
    (vector? node) (concat (when (pred node) [node])
                           (mapcat #(nodes % pred) (if (attrs node) (drop 2 node) (drop 1 node))))
    (seq? node) (mapcat #(nodes % pred) node)
    :else []))

(defn- role= [role] #(= role (:data-role (attrs %))))

(defn- text [node]
  (cond
    (string? node) node
    (vector? node) (apply str (map text (if (attrs node) (drop 2 node) (drop 1 node))))
    (seq? node) (apply str (map text node))
    :else ""))

(def ^:private coin "#9900010")

(def ^:private outcome-market
  {:market-type :outcome :coin coin :base "DET" :szDecimals 0})

(def ^:private outcome-book
  {:bids [{:px "0.22500" :sz "5000.0"} {:px "0.20500" :sz "5000.0"}]
   :asks [{:px "0.24000" :sz "5000.0"} {:px "0.26000" :sz "5000.0"}]})

(defn- render
  ([market book] (render market book {}))
  ([market book orderbook-ui]
   (panel/l2-orderbook-panel (:coin market) market book (merge {:size-unit :base} orderbook-ui)
                             nil false {:desktop-layout? true} true nil)))

(defn- price-cells [node]
  (map text (nodes node (role= "orderbook-level-price-cell"))))

(deftest outcome-book-prices-read-in-cents-test
  (let [view (render outcome-market outcome-book)
        header (first (nodes view (role= "orderbook-price-header-cell")))
        rows (nodes view (role= "orderbook-level-row"))
        bid-row (some #(when (= "22.500" (text (first (nodes % (role= "orderbook-level-price-cell"))))) %) rows)]
    (is (= "Price ¢" (text header)))
    (is (= ["26.000" "24.000" "22.500" "20.500"] (price-cells view)))
    (is (= "Bid 22.500¢, set limit price" (:aria-label (attrs bid-row))))
    (testing "a click still sends the dollar price to the order form"
      (is (= [[:actions/set-order-price-from-orderbook "0.22500"]]
             (get-in (attrs bid-row) [:on :click]))))
    (testing "the spread reads in cents"
      (is (re-find #"1\.500¢" (text (first (nodes view #(some #{"ob-spread-row"} (:class (attrs %))))))))))
  (testing "a coarser grouping drops digits: 0.01 -> whole cents"
    (let [view (render outcome-market outcome-book {:price-aggregation-by-coin {coin :sf2}})]
      (is (= ["26" "24" "23" "21"] (price-cells view))))))

(deftest outcome-grouping-options-read-in-cents-test
  (let [view (render outcome-market outcome-book {:price-aggregation-dropdown-visible? true})
        option-labels (->> (nodes view #(= [:actions/select-orderbook-price-aggregation]
                                           (some-> (get-in (attrs %) [:on :click 0]) (subvec 0 1))))
                           (map text))]
    (is (= ["0.001¢" "0.01¢" "0.1¢" "1¢"] option-labels))))

(deftest perp-book-is-unchanged-test
  (let [market {:market-type :perp :coin "BTC" :base "BTC" :quote "USDC" :szDecimals 4}
        view (render market {:bids [{:px "100.5" :sz "2"}] :asks [{:px "101" :sz "3"}]})]
    (is (= "Price" (text (first (nodes view (role= "orderbook-price-header-cell"))))))
    (is (= ["101.0" "100.5"] (price-cells view)))
    (is (= "Bid 100.5, set limit price"
           (some #(when (= "Bid 100.5, set limit price" (:aria-label (attrs %))) (:aria-label (attrs %)))
                 (nodes view (role= "orderbook-level-row")))))))

(deftest outcome-mobile-tap-targets-name-cents-test
  (let [view (panel/l2-orderbook-panel coin outcome-market outcome-book {:size-unit :base}
                                       nil false {:desktop-layout? false} true nil)
        labels (map #(:aria-label (attrs %)) (nodes view (role= "orderbook-mobile-bid-level")))]
    (is (= "Bid 22.500¢, set limit price" (first labels)))))

(deftest outcome-trades-read-in-cents-test
  (let [trade {:price 0.225 :price-raw "0.22500" :size 10 :size-raw "10" :side "B" :time-ms 0}]
    (is (= "Price ¢" (text (first (nodes (trades/trades-column-headers "DET" "Price ¢")
                                         (role= "trades-price-header-cell"))))))
    (is (= "22.500" (text (first (nodes (trades/trades-row trade true)
                                        (role= "trades-level-price-cell"))))))
    (testing "a sub-cent trade keeps all its digits"
      (is (= "0.0123" (text (first (nodes (trades/trades-row {:price 0.000123 :price-raw "0.000123"
                                                              :size 1 :size-raw "1" :side "A" :time-ms 0}
                                                             true)
                                          (role= "trades-level-price-cell")))))))
    (is (= "0.225" (text (first (nodes (trades/trades-row trade)
                                         (role= "trades-level-price-cell"))))))))
