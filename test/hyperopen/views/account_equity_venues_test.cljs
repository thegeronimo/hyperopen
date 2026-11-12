(ns hyperopen.views.account-equity-venues-test
  "The per-venue perps breakdown: which venues get a row, what each row
   reports, and what the classic panel renders from it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.views.account-equity.metrics :as metrics]
            [hyperopen.views.account-equity.panels :as panels]
            [hyperopen.views.account-equity.venues :as venues]))

(use-fixtures :each {:before metrics/reset-account-equity-metrics-cache!})

(defn- ch-state
  [account-value withdrawable positions]
  {:marginSummary {:accountValue account-value :totalNtlPos "0" :totalRawUsd account-value
                   :totalMarginUsed "0"}
   :crossMarginSummary {:accountValue account-value :totalNtlPos "0" :totalRawUsd account-value
                        :totalMarginUsed "0"}
   :crossMaintenanceMarginUsed "0"
   :withdrawable withdrawable
   :assetPositions positions})

(def ^:private market-by-key
  {"perp:BTC" {:market-type :perp :coin "BTC" :quote "USDC"}
   "perp:xyz:NVDA" {:market-type :perp :coin "xyz:NVDA" :dex "xyz" :quote "USDC"}
   "perp:io:GPU" {:market-type :perp :coin "io:GPU" :dex "io" :quote "USDC"}})

(defn- state
  [{:keys [xyz active-asset mode abstraction-raw]
    :or {xyz (ch-state "10000.0" "10000.0" []) active-asset "xyz:NVDA" mode :classic}}]
  {:account {:mode mode :abstraction-raw abstraction-raw}
   :active-asset active-asset
   :webdata2 {:clearinghouseState (ch-state "10000.01" "10000.01" [])}
   :spot {:clearinghouse-state {:balances [{:coin "USDC" :token 0 :total "500" :hold "0"}]}}
   :asset-selector {:market-by-key market-by-key}
   :perp-dex-clearinghouse {"xyz" xyz
                            "io" (ch-state "0.0" "0.0" [])}})

(defn- venues-of [s] (:perp-venues (metrics/account-equity-metrics s)))

(defn- strings [node]
  (cond (string? node) [node]
        (coll? node) (mapcat strings node)
        :else []))

(defn- find-role [node role]
  (cond
    (and (vector? node) (map? (second node)) (= role (:data-role (second node)))) node
    (coll? node) (some #(find-role % role) node)
    :else nil))

(deftest active-venue-id-test
  (is (= "xyz" (venues/active-venue-id "xyz:NVDA")))
  (is (= "main" (venues/active-venue-id "BTC")))
  (is (nil? (venues/active-venue-id "PURR/USDC")))
  (is (nil? (venues/active-venue-id "@107")))
  (is (nil? (venues/active-venue-id "#30")))
  (is (nil? (venues/active-venue-id nil))))

(deftest funded-venues-get-rows-and-empty-ones-are-counted-test
  (let [{:keys [rows hidden-count funded-count]} (venues-of (state {}))]
    (is (= ["main" "xyz"] (mapv :id rows)))
    (is (= ["Main" "xyz"] (mapv :label rows)))
    (is (= [false true] (mapv :this-market? rows)))
    (is (= [10000.01 10000.0] (mapv :value rows)))
    (is (= [10000.01 10000.0] (mapv :available rows)))
    (is (= [10000.01 10000.0] (mapv :balance rows)))
    (is (= 1 hidden-count) "io holds nothing and is not on screen")
    (is (= 2 funded-count))))

(deftest the-market-venue-shows-even-when-empty-test
  (let [{:keys [rows funded-count]} (venues-of (state {:xyz (ch-state "0.0" "0.0" [])}))
        xyz (second rows)]
    (is (= ["main" "xyz"] (mapv :id rows)))
    (is (false? (:funded? xyz)))
    (is (true? (:this-market? xyz)))
    (is (= 1 funded-count))))

(deftest venue-figures-are-its-own-test
  (let [xyz (ch-state "10250.0" "9000.0" [{:position {:coin "xyz:NVDA" :unrealizedPnl "250.0"}}])
        [main venue] (:rows (venues-of (state {:xyz xyz})))]
    (is (= 250 (:unrealized-pnl venue)))
    (is (= 10000 (:balance venue)))
    (is (= 9000 (:available venue)))
    (is (= 0 (:unrealized-pnl main)))))

(deftest no-breakdown-without-a-second-venue-or-on-a-pooled-account-test
  (testing "only the main venue is funded and the market is on it"
    (is (nil? (venues-of (state {:xyz (ch-state "0.0" "0.0" []) :active-asset "BTC"})))))
  (testing "unified accounts hold one pooled balance"
    (is (nil? (venues-of (state {:mode :unified})))))
  (testing "dex abstraction pools perps collateral too"
    (is (nil? (venues-of (state {:abstraction-raw "dexAbstraction"}))))))

(deftest switching-market-venue-refreshes-the-memoized-metrics-test
  (let [s (state {})]
    (is (= [false true] (mapv :this-market? (:rows (venues-of s)))))
    (is (= [true false] (mapv :this-market? (:rows (venues-of (assoc s :active-asset "BTC"))))))))

(deftest panel-renders-venue-rows-with-transfer-actions-test
  (let [view (panels/account-equity-view (state {}) {:show-funding-actions? false})
        breakdown (find-role view "perp-venue-breakdown")
        transfer (find-role view "account-equity-venue-transfer-xyz")]
    (is (some? breakdown))
    (is (some #{"2 venues"} (strings breakdown)))
    (is (some #{"This market"} (strings (find-role view "perp-venue-row-xyz"))))
    (is (= [[:actions/open-funding-transfer-modal :event.currentTarget/bounds
             "account-equity-venue-transfer-xyz" {:dex "xyz" :to-perp? true}]]
           (get-in (second transfer) [:on :click])))
    (is (= {:dex "" :to-perp? true}
           (last (first (get-in (second (find-role view "account-equity-venue-transfer-main"))
                                [:on :click])))))
    (is (some #{"1 more venue with no balance"} (strings (find-role view "perp-venue-hidden-count"))))
    (testing "Perps Overview has one panel per venue; the market's venue is the default"
      (is (= "true" (:data-default (second (find-role view "perp-venue-panel-xyz")))))
      (is (nil? (:data-default (second (find-role view "perp-venue-panel-main")))))
      (is (some #{"Available to trade"} (strings (find-role view "perp-venue-panel-main")))))))

(deftest panel-offers-funding-for-an-empty-market-venue-test
  (let [view (panels/account-equity-view (state {:xyz (ch-state "0.0" "0.0" [])})
                                         {:show-funding-actions? false})]
    (is (some #{"Fund"} (strings (find-role view "account-equity-venue-transfer-xyz"))))
    (is (some #{"xyz markets use their own margin. Your other perps balances do not back them."}
              (strings (find-role view "perp-venue-unfunded-note"))))
    (is (some #{"1 venue"} (strings (find-role view "perp-venue-breakdown"))))))

(deftest spectating-shows-the-breakdown-without-transfer-actions-test
  (let [view (panels/account-equity-view
              (assoc (state {}) :account-context {:spectate-mode {:active? true
                                                                  :address "0x1111111111111111111111111111111111111111"}})
              {:show-funding-actions? false})
        button (second (find-role view "account-equity-venue-transfer-xyz"))]
    (is (nil? (:on button)))
    (is (some #{"hidden"} (:class button)))))

(deftest panel-is-unchanged-without-a-breakdown-test
  (let [view (panels/account-equity-view (state {:mode :classic :xyz (ch-state "0.0" "0.0" [])
                                                 :active-asset "BTC"})
                                         {:show-funding-actions? false})]
    (is (nil? (find-role view "perp-venue-breakdown")))
    (is (nil? (find-role view "perp-venue-overview")))
    (is (some #{"Perps Overview"} (strings view)))))
