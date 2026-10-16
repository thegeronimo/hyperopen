(ns hyperopen.views.l2-orderbook.depth-readout-test
  "Hover depth readout, book-impact marks on the ladder, and the order form's impact card."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.orderbook.actions :as orderbook-actions]
            [hyperopen.state.trading :as trading]
            [hyperopen.views.l2-orderbook.panel :as panel]
            [hyperopen.views.trade.book-impact :as book-impact]))

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

(def ^:private book
  {:bids [{:px "100" :sz "2"} {:px "99" :sz "1"}]
   :asks [{:px "101" :sz "3"} {:px "102" :sz "1"} {:px "103" :sz "5"}]})

(def ^:private market {:market-type :perp :base "BTC" :quote "USDC" :szDecimals 4})

(defn- render [orderbook-ui extras]
  (panel/l2-orderbook-panel "BTC" market book (merge {:size-unit :base} orderbook-ui)
                            nil false {:desktop-layout? true} true extras))

(defn- row-by-price [node px]
  (some #(when (= px (text (first (nodes % (role= "orderbook-level-price-cell"))))) %)
        (nodes node (role= "orderbook-level-row"))))

(deftest hover-actions-write-only-on-change-test
  (is (= [[:effects/save [:orderbook-ui :hover-level] {:side :ask :depth 1}]]
         (orderbook-actions/set-orderbook-hover-level {} :ask 1)))
  (is (= [] (orderbook-actions/set-orderbook-hover-level
             {:orderbook-ui {:hover-level {:side :ask :depth 1}}} "ask" 1)))
  (is (= [[:effects/save [:orderbook-ui :hover-level] nil]]
         (orderbook-actions/clear-orderbook-hover-level {:orderbook-ui {:hover-level {:side :ask :depth 1}}})))
  (is (= [] (orderbook-actions/clear-orderbook-hover-level {}))))

(deftest rows-select-and-clear-the-hovered-level-test
  (let [node (render {} nil)
        row (row-by-price node "102")]
    (testing "rows identify themselves by side and depth from the spread"
      (is (= [[:actions/set-orderbook-hover-level :ask 1]] (:mouseenter (:on (attrs row)))))
      (is (= [[:actions/set-orderbook-hover-level :ask 1]] (:focus (:on (attrs row)))))
      (is (= [[:actions/set-orderbook-hover-level :ask 0]]
             (:mouseenter (:on (attrs (row-by-price node "101"))))))
      (is (= [[:actions/set-orderbook-hover-level :bid 1]]
             (:mouseenter (:on (attrs (row-by-price node "99")))))))
    (is (= [[:actions/clear-orderbook-hover-level]]
           (:mouseleave (:on (attrs (first (nodes node (role= "orderbook-depth-body"))))))))))

(deftest hovered-level-shows-the-readout-card-test
  (let [node (render {:hover-level {:side :ask :depth 1}} nil)
        cards (nodes node (role= "orderbook-depth-readout"))
        card-text (text (first cards))]
    (is (= 1 (count cards)))
    (is (re-find #"Market buy up to 102" card-text))
    (is (re-find #"Levels swept2" card-text))
    (is (re-find #"Total size4 BTC" card-text))
    (is (re-find #"Notional\$405" card-text))
    (testing "the card sits on the hovered row and describes it"
      (is (= 1 (count (nodes (row-by-price node "102") (role= "orderbook-depth-readout")))))
      (is (= "orderbook-depth-readout" (:aria-describedby (attrs (row-by-price node "102"))))))
    (testing "rows from the best ask through the hovered level are tinted"
      (let [tinted? (fn [px] (seq (nodes (row-by-price node px) (role= "orderbook-readout-tint"))))]
        (is (tinted? "101"))
        (is (tinted? "102"))
        (is (not (tinted? "103")))
        (is (not (tinted? "100")))))
    (testing "only the hovered row is outlined"
      (is (= 1 (count (nodes node (role= "orderbook-readout-outline")))))
      (is (seq (nodes (row-by-price node "102") (role= "orderbook-readout-outline")))))))

(deftest the-card-stays-on-the-hovered-row-when-prices-move-test
  ;; The cursor sits still on the second-best ask. The market moves: 101 is lifted and
  ;; a new level appears at 104. The card must stay on that row and show its new figures.
  (let [moved {:bids (:bids book)
               :asks [{:px "102" :sz "1"} {:px "103" :sz "5"} {:px "104" :sz "2"}]}
        node (panel/l2-orderbook-panel "BTC" market moved {:size-unit :base :hover-level {:side :ask :depth 1}}
                                       nil false {:desktop-layout? true} true nil)
        card (first (nodes node (role= "orderbook-depth-readout")))]
    (is (= 1 (count (nodes (row-by-price node "103") (role= "orderbook-depth-readout")))))
    (is (re-find #"Market buy up to 103" (text card)))
    (is (re-find #"Total size6 BTC" (text card)))))

(deftest no-card-when-no-row-sits-at-the-hovered-depth-test
  (is (empty? (nodes (render {:hover-level {:side :ask :depth 9}} nil)
                     (role= "orderbook-depth-readout")))))

(deftest no-card-on-the-mobile-split-book-test
  (is (empty? (nodes (panel/l2-orderbook-panel "BTC" market book
                                               {:size-unit :base :hover-level {:side :ask :depth 1}}
                                               nil false {:desktop-layout? false} true nil)
                     (role= "orderbook-depth-readout")))))

(deftest impact-marks-the-levels-a-market-order-takes-test
  (let [impact {:side :buy :fill-fraction-by-px {"101" 1 "102" 0.5}}
        node (render {} {:impact impact})
        band (fn [px] (first (nodes (row-by-price node px) (role= "orderbook-impact-band"))))]
    (is (= "scaleX(1)" (get-in (attrs (band "101")) [:style :transform])))
    (is (= "scaleX(0.5)" (get-in (attrs (band "102")) [:style :transform])))
    (is (nil? (band "103")))
    (testing "a buy never marks bids"
      (is (nil? (first (nodes (row-by-price node "100") (role= "orderbook-impact-rail"))))))))

(deftest order-form-market-impact-test
  (let [state {:active-asset "BTC"
               :orderbooks {"BTC" book}
               :order-form (assoc (trading/default-order-form) :type :market :side :buy :size "3.5")}
        impact (book-impact/market-impact state)
        card-text (text (book-impact/book-impact-card impact book "BTC"))]
    (is (= :buy (:side impact)))
    (is (= {"101" 1 "102" 0.5} (:fill-fraction-by-px impact)))
    (is (re-find #"Levels swept2 \(last 50% filled\)" card-text))
    (is (re-find #"Worst price102" card-text))
    (is (re-find #"Visible asks used39%" card-text))
    (testing "limit orders and unsized forms have no impact"
      (is (nil? (book-impact/market-impact (assoc-in state [:order-form :type] :limit))))
      (is (nil? (book-impact/market-impact (assoc-in state [:order-form :size] "")))))
    (testing "a size beyond the visible book warns"
      (let [big (book-impact/market-impact (assoc-in state [:order-form :size] "20"))]
        (is (re-find #"fills only 9 of 20 BTC"
                     (text (book-impact/book-impact-card big book "BTC"))))))))
