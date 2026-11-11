(ns hyperopen.views.account-equity.outcome-shares-metrics-test
  "Outcome shares in Account Equity: counted once (spot equity excludes the
   `+`/`#` outcome balances) and valued only when their inputs change."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.outcomes.test-support.fixture :as fixture]
            [hyperopen.views.account-equity.metrics :as metrics]
            [hyperopen.views.account-info.derived-cache :as derived-cache]))

(use-fixtures :each
  (fn [f]
    (derived-cache/reset-derived-cache!)
    (metrics/reset-account-equity-metrics-cache!)
    (f)
    (derived-cache/reset-derived-cache!)
    (metrics/reset-account-equity-metrics-cache!)))

(defn- market-by-key []
  (into {} (map (juxt :key identity))
        (markets/build-outcome-markets fixture/outcome-meta fixture/asset-ctxs)))

(defn- classic-state
  [balances]
  {:account {:mode :classic}
   :asset-selector {:market-by-key (market-by-key)}
   :orderbooks {"#270370" {:bids [{:px "0.5" :sz "1000"}] :asks [{:px "0.52" :sz "1000"}]}}
   :spot {:clearinghouse-state {:balances balances}}})

(def ^:private usdc {:coin "USDC" :token 0 :total "100" :hold "0" :entryNtl "0"})
(def ^:private det {:coin "+270370" :token 100270370 :total "10" :hold "0" :entryNtl "6"})

(deftest outcome-shares-are-not-also-spot-equity-test
  (let [with-shares (metrics/account-equity-metrics (classic-state [usdc det]))
        _ (metrics/reset-account-equity-metrics-cache!)
        _ (derived-cache/reset-derived-cache!)
        without (metrics/account-equity-metrics (classic-state [usdc]))]
    (is (number? (:outcome-shares-value with-shares)))
    (is (pos? (:outcome-shares-value with-shares)))
    (is (= (:spot-equity without) (:spot-equity with-shares))
        "the outcome balance adds nothing to spot equity, so the total counts it once")
    (is (nil? (:outcome-shares-value without)))))

(deftest outcome-shares-are-valued-behind-the-cache-test
  (let [calls (atom 0)
        state (classic-state [usdc det])]
    (with-redefs [metrics/account-equity-outcome-shares (fn [_] (swap! calls inc) 5)]
      (metrics/account-equity-metrics state)
      (metrics/account-equity-metrics state)
      (metrics/account-equity-metrics (assoc state :unrelated-key 1))
      (is (= 1 @calls) "repeat reads of unchanged inputs do not re-value the shares")
      (metrics/account-equity-metrics (assoc-in state [:orderbooks "#270370" :bids 0 :px] "0.6"))
      (is (= 2 @calls) "a changed book does"))))
