(ns hyperopen.views.l2-orderbook.own-orders-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.l2-orderbook.depth :as depth]
            [hyperopen.views.l2-orderbook.own-orders :as own-orders]
            [hyperopen.views.l2-orderbook.panel :as panel]))

(defn- levels [& pxs]
  (mapv (fn [px] {:px px}) pxs))

(deftest exact-levels-sum-every-resting-order-at-that-price-test
  (is (= {:bids {"99" 3} :asks {"102" 0.5}}
         (own-orders/own-order-sizes [{:side "B" :px "99" :sz "1"}
                                      {:side "B" :px "99.0" :sz "2"}
                                      {:side "A" :px "102" :sz "0.5"}]
                                     (levels "100" "99" "98")
                                     (levels "101" "102" "103")
                                     false))))

(deftest aggregated-levels-bucket-bids-down-and-asks-up-test
  (is (= {:bids {"81940" 0.25} :asks {"81960" 1}}
         (own-orders/own-order-sizes [{:side "B" :px "81941.5" :sz "0.25"}
                                      {:side "A" :px "81951" :sz "1"}]
                                     (levels "81950" "81940" "81930")
                                     (levels "81950" "81960" "81970")
                                     true))))

(deftest full-precision-never-moves-an-order-to-a-neighbouring-level-test
  ;; The level an order rests at can be briefly absent from a snapshot. At full
  ;; precision the pip must disappear rather than mark the next level down.
  (is (= {:bids {} :asks {}}
         (own-orders/own-order-sizes [{:side "B" :px "99.5" :sz "1"}
                                      {:side "A" :px "101.5" :sz "1"}]
                                     (levels "100" "99")
                                     (levels "101" "102")
                                     false))))

(deftest orders-that-are-not-on-the-visible-book-are-left-out-test
  (testing "trigger orders rest off-book until they fire"
    (is (= {:bids {} :asks {}}
           (own-orders/own-order-sizes [{:side "B" :px "99" :sz "1" :is-trigger true}]
                                       (levels "100" "99")
                                       (levels "101")
                                       false))))
  (testing "deeper than the displayed levels"
    (is (= {:bids {} :asks {}}
           (own-orders/own-order-sizes [{:side "B" :px "90" :sz "1"}
                                        {:side "A" :px "110" :sz "1"}]
                                       (levels "100" "99")
                                       (levels "101" "102")
                                       true))))
  (testing "unknown side or unparseable price"
    (is (= {:bids {} :asks {}}
           (own-orders/own-order-sizes [{:side "X" :px "99" :sz "1"}
                                        {:side "B" :px "n/a" :sz "1"}]
                                       (levels "100" "99")
                                       (levels "101")
                                       true)))))

(deftest active-asset-open-orders-reads-the-live-orders-feed-test
  (let [state {:active-asset "BTC"
               :orders {:open-orders [{:coin "BTC" :side "B" :limitPx "99" :sz "1" :oid 7}
                                      {:coin "ETH" :side "A" :limitPx "3000" :sz "2" :oid 8}]}}
        orders (own-orders/active-asset-open-orders state)]
    (is (= ["7"] (mapv :oid orders)))
    (is (= "99" (:px (first orders))))
    (testing "a pending cancel is no longer shown as resting"
      (is (empty? (own-orders/active-asset-open-orders
                   (assoc-in state [:orders :pending-cancel-oids] #{7})))))))

;; --- rendering -----------------------------------------------------------------

(defn- attrs [node] (when (map? (second node)) (second node)))

(defn- nodes [node pred]
  (cond
    (vector? node) (concat (when (pred node) [node])
                           (mapcat #(nodes % pred) (if (attrs node) (drop 2 node) (drop 1 node))))
    (seq? node) (mapcat #(nodes % pred) node)
    :else []))

(defn- role= [role] #(= role (:data-role (attrs %))))

(def ^:private row {:px "100" :sz "2" :side :bid :cum-size 2 :cum-value 200})

(deftest a-level-is-a-button-that-sets-the-limit-price-test
  (let [node (depth/order-row row :base true)
        a (attrs node)]
    (is (= :button (first node)))
    (is (= "button" (:type a)))
    (is (= [[:actions/set-order-price-from-orderbook "100"]] (:click (:on a))))
    (is (= "Bid 100.00, set limit price" (:aria-label a)))
    (is (empty? (nodes node (role= "orderbook-own-order-pip"))))))

(deftest a-level-holding-your-order-shows-a-pip-test
  (let [node (depth/order-row (assoc row :side :ask) :base true {:own-size "0.5 BTC"})
        a (attrs node)]
    (is (= 1 (count (nodes node (role= "orderbook-own-order-pip")))))
    (is (= "Your resting order here: 0.5 BTC" (:title a)))
    (is (= "Ask 100.00, set limit price. Your resting order here: 0.5 BTC" (:aria-label a)))))

(deftest mobile-split-row-has-a-tap-target-per-side-test
  (let [node (depth/mobile-split-order-row {:bid row :ask (assoc row :side :ask :px "101")}
                                           :base true nil nil nil "1 BTC")
        bid-btn (first (nodes node (role= "orderbook-mobile-bid-level")))
        ask-btn (first (nodes node (role= "orderbook-mobile-ask-level")))]
    (is (= [[:actions/set-order-price-from-orderbook "100"]] (:click (:on (attrs bid-btn)))))
    (is (= [[:actions/set-order-price-from-orderbook "101"]] (:click (:on (attrs ask-btn)))))
    (is (empty? (nodes bid-btn (role= "orderbook-own-order-pip"))))
    (is (= 1 (count (nodes ask-btn (role= "orderbook-own-order-pip")))))))

(deftest panel-places-your-orders-on-their-levels-test
  (let [node (panel/l2-orderbook-panel "BTC"
                                       {:market-type :perp :base "BTC" :quote "USDC" :szDecimals 4}
                                       {:bids [{:px "100" :sz "2"} {:px "99" :sz "1"}]
                                        :asks [{:px "101" :sz "3"}]}
                                       {:size-unit :base}
                                       nil
                                       false
                                       {:desktop-layout? true}
                                       true
                                       {:open-orders [{:side "B" :px "99" :sz "0.5"}]})
        titled (keep (comp :title attrs) (nodes node (role= "orderbook-level-row")))]
    (is (= ["Your resting order here: 0.5 BTC"] titled))))
