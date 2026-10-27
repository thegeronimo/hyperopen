(ns hyperopen.views.trade.vol-range.dock-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.vol-range.application.dock-model :as dm]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.lifecycle-recovery :as recovery]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.trade.vol-range.dock :as dock]))

(def addr "0x1234567890abcdef1234567890abcdef12345678")
(def scope {:network :hyperliquid-mainnet :account-address addr :coin "SYN/USDC" :symbol "SYN"
            :quote-symbol "USDC" :asset-id 10001})
(defn- cloid [n] (str "0x484f56520000000000000000000000" (if (< n 10) (str "0" n) n)))

(defn- legs []
  [{:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4" :size "7" :cloid (cloid 1)}
   {:leg-id "buy-1" :side :buy :rung-index 1 :price "74" :size "9" :cloid (cloid 2)}
   {:leg-id "sell-0" :side :sell :rung-index 0 :price "84" :size "10" :cloid (cloid 3)}])

(defn- sent-grid []
  (-> (lifecycle/new-grid {:grid-id "g1" :scope scope :scope-key "k" :operation-id "op" :plan-id "p"
                           :expires-after-ms 31000 :now-ms 1000 :legs (legs)
                           :model {:anchor {:mode :custom :price "80"} :volatility {:daily-fraction "0.06"}
                                   :tilt {:enabled? true}}})
      (lifecycle/mark-sending "op" "a" 1500)))

(defn- response [g rejected]
  (evidence/apply-placement-delivery
   g "op" {:delivery :response
           :leg-results (map-indexed
                         (fn [i l]
                           (if (contains? rejected (:cloid l))
                             {:cloid (:cloid l) :placement-state :rejected :reason "Post only would match"}
                             {:cloid (:cloid l) :oid (str (+ 100 i)) :placement-state :accepted :order-state :resting}))
                         (legs))}
   2000))

(defn- state-with [grids & [extra]]
  {:wallet {:address addr}
   :vol-range {:execution (merge {:enabled? true :grids (into {} (map (juxt :grid-id identity)) grids)} extra)}})

(defn- panel [state] (dock/dock-panel (dm/dock state)))
(defn- text [node] (str/join " " (hiccup/collect-strings node)))
(defn- rows [html] (hiccup/find-all-nodes html #(= "vol-ladder-row" (:data-role (hiccup/node-attrs %)))))

(deftest empty-state-and-live-region-test
  (let [html (panel (state-with [] {:last {:op :place :status :busy}}))
        live (hiccup/find-by-data-role html "vol-ladder-status-message")]
    (is (= "polite" (get-in live [1 :aria-live])))
    (is (str/includes? (text live) "Another placement"))
    (is (str/includes? (text html) "No Vol Range ladders yet."))
    (is (empty? (rows html))))
  (testing "the live region stays mounted with no message"
    (is (some? (hiccup/find-by-data-role (panel (state-with [] {:enabled? false})) "vol-ladder-status-message")))))

(deftest live-row-test
  (let [html (panel (state-with [(response (sent-grid) #{})]))
        t (text html)
        status (hiccup/find-by-data-role html "vol-ladder-status")
        account (hiccup/find-by-data-role html "vol-ladder-account")]
    (is (= 1 (count (rows html))))
    (is (= "Live" (text status)))
    (is (not (str/includes? (text status) "⚠")))
    (is (str/includes? t "SYN/USDC"))
    (is (= addr (get-in account [1 :title])))
    (is (= "0x1234…5678" (text account)))
    (is (str/includes? (text (hiccup/find-by-data-role html "vol-ladder-counts"))
                       "3 live · 0 filled · 0 rejected · 0 checking · 0 canceled"))
    (is (str/includes? t "Live buy units 16"))
    (is (str/includes? t "Live sell units 10"))
    (is (str/includes? t "Execution detail (not P&L)"))
    (is (str/includes? t "daily volatility 6.00%"))
    (is (str/includes? t "tilt on"))
    (is (nil? (hiccup/find-by-data-role html "vol-ladder-partial")))
    (is (nil? (hiccup/find-by-data-role html "vol-ladder-detail")) "no detail until selected")
    (let [view (hiccup/find-by-data-role html "vol-ladder-view-orders")
          cancel (hiccup/find-by-data-role html "vol-ladder-cancel")
          refresh (hiccup/find-by-data-role html "vol-ladder-refresh")]
      (is (= "View orders" (text view)))
      (is (false? (get-in view [1 :aria-expanded])))
      (is (= [[:actions/select-vol-ladder "g1"]] (get-in view [1 :on :click])))
      (is (= [[:actions/refresh-vol-ladder "g1"]] (get-in refresh [1 :on :click])))
      (is (= [[:actions/cancel-vol-ladder "g1"]] (get-in cancel [1 :on :click])))
      (is (= "Cancel SYN/USDC ladder (3 live orders)" (get-in cancel [1 :aria-label])))
      (is (= "Cancel this ladder" (text cancel))))))

(deftest partial-row-test
  (let [html (panel (state-with [(response (sent-grid) #{(cloid 1)})]))
        status (hiccup/find-by-data-role html "vol-ladder-status")]
    (is (= "Partially deployed" (text status)))
    (is (= "1 order was not placed. 2 other orders remain active."
           (text (hiccup/find-by-data-role html "vol-ladder-partial"))))
    (is (str/includes? (text (hiccup/find-by-data-role html "vol-ladder-counts")) "1 rejected"))))

(deftest checking-row-is-flagged-for-attention-test
  (let [g (evidence/apply-placement-delivery (sent-grid) "op" {:delivery :unknown} 2000)
        html (panel (state-with [g]))
        status (hiccup/find-by-data-role html "vol-ladder-status")]
    (is (str/includes? (text status) "Checking status"))
    (is (str/includes? (text status) "⚠") "non-color cue")
    (is (contains? (hiccup/node-class-set status) "text-ho-warn"))
    (is (not (str/includes? (text html) "Live buy units 0 ·Live")))))

(deftest recovered-row-test
  (let [g (recovery/recovered-grid {:grid-id "r1" :scope scope :scope-key "k" :now-ms 5000
                                    :rows [{:cloid (cloid 9) :oid 5 :side "B" :limitPx "70" :sz "4" :origSz "4"}]})
        html (panel (state-with [g]))
        status (hiccup/find-by-data-role html "vol-ladder-status")]
    (is (str/includes? (text status) "Recovered Vol Range orders"))
    (is (str/includes? (text status) "⚠"))
    (is (str/includes? (text html) "Original settings unavailable"))))

(deftest refreshing-and-canceling-buttons-are-busy-test
  (let [html (panel (state-with [(response (sent-grid) #{})]
                                {:busy {[:cancel "g1"] true [:refresh "g1"] true}}))
        cancel (hiccup/find-by-data-role html "vol-ladder-cancel")
        refresh (hiccup/find-by-data-role html "vol-ladder-refresh")]
    (is (true? (get-in cancel [1 :disabled])))
    (is (true? (get-in cancel [1 :aria-busy])))
    (is (true? (get-in refresh [1 :disabled])))
    (is (true? (get-in refresh [1 :aria-busy])))))

(deftest detail-panel-test
  (let [g (response (sent-grid) #{(cloid 1)})
        html (panel (state-with [g] {:selected-grid-id "g1"}))
        view (hiccup/find-by-data-role html "vol-ladder-view-orders")
        legs (hiccup/find-all-nodes html #(= "vol-ladder-leg" (:data-role (hiccup/node-attrs %))))]
    (is (= "Hide orders" (text view)))
    (is (true? (get-in view [1 :aria-expanded])))
    (is (= 3 (count legs)))
    (let [t (text (hiccup/find-by-data-role html "vol-ladder-detail"))]
      (doseq [h ["Side" "Price" "Size" "Filled" "Remaining" "Status" "Reason"]]
        (is (str/includes? t h) h))
      (is (str/includes? t "Rejected"))
      (is (str/includes? t "Post only would match")))
    (is (some #(and (= :td (first %)) (= "Buy" (text %)) (contains? (hiccup/node-class-set %) "text-ho-buy"))
              (hiccup/find-all-nodes html #(= :td (first %))))
        "buy token color plus text")
    (is (some #(and (= :td (first %)) (= "Sell" (text %)) (contains? (hiccup/node-class-set %) "text-ho-sell"))
              (hiccup/find-all-nodes html #(= :td (first %)))))))

(deftest earlier-attempt-legs-are-deemphasized-test
  (let [g (response (sent-grid) #{})
        g (-> g (update-in [:members "buy-0" :cloids] conj (cloid 20))
              (assoc-in [:legs (cloid 20)] (assoc (get-in g [:legs (cloid 1)]) :cloid (cloid 20) :price "76.2")))
        html (panel (state-with [g] {:selected-grid-id "g1"}))
        old (filter #(= "true" (:data-historical (hiccup/node-attrs %)))
                    (hiccup/find-all-nodes html #(= "vol-ladder-leg" (:data-role (hiccup/node-attrs %)))))]
    (is (= 1 (count old)))
    (is (contains? (hiccup/node-class-set (first old)) "opacity-60"))
    (is (str/includes? (text (first old)) "earlier attempt"))))

(deftest tab-appears-only-when-enabled-or-grids-exist-test
  (is (nil? (dock/extra-tab {})))
  (is (nil? (dock/extra-tab (state-with [] {:enabled? false}))))
  (is (= :vol-ladders (:id (dock/extra-tab (state-with [] {:enabled? true})))))
  (is (= "Vol ladders" (:label (dock/extra-tab (state-with [(sent-grid)] {:enabled? false})))))
  (testing "opts are untouched without the tab and extended with it"
    (is (= {:a 1} (dock/with-tab {:a 1} {})))
    (let [opts (dock/with-tab {:default-panel-classes ["h-full"]} (state-with [] {:enabled? true}))]
      (is (= ["h-full"] (:default-panel-classes opts)))
      (is (= :vol-ladders (:id (first (:extra-tabs opts)))))
      (is (= :vol-ladders (last (:tab-order opts)))))))

(deftest tab-renders-in-the-account-panel-test
  (let [state (assoc (state-with [] {:enabled? true}) :account-info {:selected-tab :vol-ladders})
        html (account-info-view/account-info-panel state (dock/with-tab {:default-panel-classes ["h-full"]} state))
        t (text html)]
    (is (str/includes? t "Vol ladders"))
    (is (str/includes? t "No Vol Range ladders yet."))
    (is (some? (hiccup/find-by-data-role html "vol-ladder-dock")))))
