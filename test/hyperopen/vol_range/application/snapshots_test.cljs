(ns hyperopen.vol-range.application.snapshots-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.fixtures :as fx]
            [hyperopen.vol-range.state-fixtures :as sf]))

(def now sf/now-ms)

(deftest current-scope-test
  (let [state (sf/base-state)
        scope (snapshots/current-scope state)]
    (is (= {:network :hyperliquid-mainnet
            :owner-address sf/owner
            :account-address sf/owner
            :vault-address nil
            :market-type :spot
            :spot-pair-index 107
            :asset-id 10107
            :coin "@107"
            :base-token-id "150"
            :quote-token-id "0"
            :symbol "SYN"
            :quote-symbol "USDC"
            :sz-decimals 2
            :metadata-version nil}
           scope))
    (testing "szDecimals has no default"
      (is (nil? (:sz-decimals (snapshots/current-scope (update state :active-market dissoc :szDecimals))))))
    (testing "an owned selected subaccount becomes the account and the vault"
      (let [sub (-> state
                    (assoc-in [:account-context :subaccounts :selected-address] sf/subaccount)
                    (assoc-in [:account-context :subaccounts :rows]
                              [{:sub-account-user sf/subaccount :master sf/owner}]))
            scope (snapshots/current-scope sub)]
        (is (= sf/subaccount (:account-address scope)))
        (is (= sf/subaccount (:vault-address scope)))
        (is (= sf/owner (:owner-address scope)))))))

(deftest market-snapshot-bbo-from-string-levels-test
  (let [state (-> (sf/base-state)
                  (assoc-in [:orderbooks "@107"]
                            {:bids [{:px "79.5"} {:px "79.99"} {:px "78"}]
                             :asks [{:px "81"} {:px "80.01"} {:px "90"}]}))
        snap (snapshots/market-snapshot state now)]
    (is (= {:bid "79.99" :ask "80.01" :observed-at-ms now} (:bbo snap)))
    (is (= {:price "80" :observed-at-ms now} (get-in snap [:anchors :market-price])))
    (is (= :ready (:status snap)))
    (is (= "SYN" (:base-symbol snap)))
    (is (= 2 (:sz-decimals snap)))
    (is (contracts/market-snapshot-valid? snap))
    (testing "no book means no BBO and no market-price anchor"
      (let [empty-book (snapshots/market-snapshot (assoc-in state [:orderbooks "@107"] {:bids [] :asks []}) now)]
        (is (nil? (:bbo empty-book)))
        (is (nil? (get-in empty-book [:anchors :market-price])))
        (is (contracts/market-snapshot-valid? empty-book))))))

(deftest market-snapshot-vwap-anchor-test
  (let [state (sf/base-state)
        snap (snapshots/market-snapshot state now)]
    (is (= {:price "80" :observed-at-ms now} (get-in snap [:anchors :vwap-24h])))
    (testing "VWAP divides raw quote volume by raw base volume at 12 places"
      (let [s (snapshots/market-snapshot
               (assoc-in state [:active-assets :contexts "@107"] {:dayNtlVlmRaw "1000" :dayBaseVlmRaw "3"}) now)]
        (is (= "333.333333333333" (get-in s [:anchors :vwap-24h :price])))))
    (testing "unavailable when base volume is missing, zero or malformed"
      (doseq [ctx [{:dayNtlVlmRaw "8000"}
                   {:dayNtlVlmRaw "8000" :dayBaseVlmRaw "0"}
                   {:dayNtlVlmRaw "8000" :dayBaseVlmRaw "abc"}
                   {:dayBaseVlmRaw "100"}
                   nil]]
        (is (nil? (get-in (snapshots/market-snapshot (assoc-in state [:active-assets :contexts "@107"] ctx) now)
                          [:anchors :vwap-24h]))
            (pr-str ctx))))))

(deftest market-snapshot-utc-open-and-auto-volatility-test
  (let [state (sf/base-state)
        today (+ (* 86400000 (js/Math.floor (/ now 86400000))))
        with-today (update-in state [:candles "@107" :1d]
                              conj {:t today :T (+ today 86399999) :s "@107" :i "1d"
                                    :o "79" :c "80" :h "81" :l "78" :v "1" :n 1})
        snap (snapshots/market-snapshot with-today now)]
    (is (= {:price "79" :observed-at-ms today} (get-in snap [:anchors :utc-open])))
    (is (nil? (get-in (snapshots/market-snapshot state now) [:anchors :utc-open])))
    (is (= :ok (get-in snap [:auto-volatility :status])))
    (is (= "0.036044488206" (get-in snap [:auto-volatility :daily-fraction])))
    (is (= {:first-day-ms sf/first-window-day :last-day-ms (+ sf/first-window-day (* 13 86400000))}
           (get-in snap [:auto-volatility :window])))
    (testing "auto volatility is memoized on rows identity, coin and UTC day"
      (let [a (snapshots/market-snapshot state now)
            b (snapshots/market-snapshot state (+ now 1000))
            next-day (+ now 86400000)
            ;; refetched successfully on the next UTC day, so the data is fresh
            c (snapshots/market-snapshot
               (assoc-in state [:vol-range :daily-fetch "@107" :completed-at-ms] next-day)
               next-day)]
        (is (identical? (:auto-volatility a) (:auto-volatility b)) "a new tick does not recompute")
        (is (not (identical? (:auto-volatility a) (:auto-volatility c))) "a new UTC day recomputes")
        (is (= :unavailable (get-in c [:auto-volatility :status])) "the window moved past the loaded rows")))
    (testing "missing rows make auto volatility unavailable without substituting data"
      (let [s (snapshots/market-snapshot (update-in state [:candles "@107" :1d] #(vec (rest %))) now)]
        (is (= :unavailable (get-in s [:auto-volatility :status])))))))

(deftest unsupported-market-test
  (testing "a perp market is unsupported"
    (let [state (assoc (sf/base-state) :active-market {:coin "BTC" :market-type :perp :base "BTC"
                                                       :quote "USDC" :szDecimals 5})
          snap (snapshots/market-snapshot state now)]
      (is (= :unavailable (:status snap)))
      (is (= [:capability/market-unsupported] (mapv :code (:errors snap))))
      (is (nil? (:bbo snap)))
      (is (contracts/market-snapshot-valid? snap))))
  (testing "a non-USDC spot quote is unsupported"
    (let [snap (snapshots/market-snapshot
                (assoc-in (sf/base-state) [:active-market :quote] "USDH") now)]
      (is (= [:capability/market-unsupported] (mapv :code (:errors snap))))))
  (testing "missing szDecimals makes metadata unavailable"
    (let [snap (snapshots/market-snapshot (update (sf/base-state) :active-market dissoc :szDecimals) now)]
      (is (= :unavailable (:status snap)))
      (is (= [:data/market-metadata-unavailable] (mapv :code (:errors snap))))))
  (testing "no active market"
    (let [snap (snapshots/market-snapshot (dissoc (sf/base-state) :active-market) now)]
      (is (= [:data/market-metadata-unavailable] (mapv :code (:errors snap)))))))

(deftest account-snapshot-balances-test
  (let [snap (snapshots/account-snapshot (sf/base-state) now)]
    (is (= :ready (:status snap)))
    (is (= sf/owner (:account-address snap)))
    (is (= {:total "150" :hold "0" :available "150"} (:base snap)))
    (is (= {:total "3000" :hold "0" :available "3000"} (:quote snap)))
    (is (= :classic (:account-mode snap)))
    (is (false? (:read-only? snap)))
    (is (contracts/account-snapshot-valid? snap)))
  (testing "available is total minus hold"
    (let [state (assoc-in (sf/base-state) [:spot :clearinghouse-state :balances]
                          [{:coin "USDC" :token 0 :total "3000" :hold "100.5"}
                           {:coin "SYN" :token 150 :total "150" :hold "20"}])
          snap (snapshots/account-snapshot state now)]
      (is (= {:total "150" :hold "20" :available "130"} (:base snap)))
      (is (= {:total "3000" :hold "100.5" :available "2899.5"} (:quote snap)))))
  (testing "token ids win over a clashing symbol"
    (let [state (assoc-in (sf/base-state) [:spot :clearinghouse-state :balances]
                          [{:coin "SYN" :token 999 :total "1" :hold "0"}
                           {:coin "SYN" :token 150 :total "150" :hold "0"}
                           {:coin "USDC" :token 0 :total "3000" :hold "0"}])]
      (is (= "150" (get-in (snapshots/account-snapshot state now) [:base :total])))))
  (testing "balances without token ids match by coin symbol"
    (let [state (assoc-in (sf/base-state) [:spot :clearinghouse-state :balances]
                          [{:coin "USDC" :total "3000" :hold "0"} {:coin "SYN" :total "150" :hold "0"}])]
      (is (= "150" (get-in (snapshots/account-snapshot state now) [:base :total])))))
  (testing "a loaded list without the base token is a zero balance; no list is unavailable data"
    (let [zero (snapshots/account-snapshot
                (assoc-in (sf/base-state) [:spot :clearinghouse-state :balances]
                          [{:coin "USDC" :token 0 :total "3000" :hold "0"}]) now)
          none (snapshots/account-snapshot (update (sf/base-state) :spot dissoc :clearinghouse-state) now)]
      (is (= {:total "0" :hold "0" :available "0"} (:base zero)))
      (is (nil? (:base none)))
      (is (contracts/account-snapshot-valid? none))))
  (testing "a selected owned subaccount uses its own spot state"
    (let [sub (-> (sf/base-state)
                  (assoc-in [:account-context :subaccounts :selected-address] sf/subaccount)
                  (assoc-in [:account-context :subaccounts :rows]
                            [{:sub-account-user sf/subaccount :master sf/owner
                              :spot-state {:balances [{:coin "SYN" :token 150 :total "7" :hold "1"}
                                                      {:coin "USDC" :token 0 :total "50" :hold "0"}]}}]))
          snap (snapshots/account-snapshot sub now)]
      (is (= sf/subaccount (:account-address snap)))
      (is (= {:total "7" :hold "1" :available "6"} (:base snap)))
      (is (= "50" (get-in snap [:quote :total]))))))

(deftest account-snapshot-open-orders-test
  (let [state (-> (sf/base-state)
                  (assoc-in [:orders :open-orders]
                            [{:coin "@107" :oid 11 :side "B" :limitPx "70" :sz "2.5" :origSz "3"}
                             {:coin "@107" :oid 12 :side "A" :limitPx "90" :sz "1" :origSz "1"}
                             {:coin "@108" :oid 13 :side "B" :limitPx "5" :sz "9" :origSz "9"}])
                  (assoc-in [:orders :pending-cancel-oids] #{11}))
        snap (snapshots/account-snapshot state now)]
    (testing "open orders for this coin only, optimistic cancels still counted"
      (is (= [{:oid "11" :side :buy :price "70" :remaining "2.5"}
              {:oid "12" :side :sell :price "90" :remaining "1"}]
             (:open-orders snap))))
    (is (contracts/account-snapshot-valid? snap))))

(deftest account-snapshot-mode-and-read-only-test
  (let [unified (snapshots/account-snapshot (assoc-in (sf/base-state) [:account :mode] :unified) now)]
    (is (= :unified (:account-mode unified))))
  (testing "spectate mode is read-only"
    (let [state (-> (sf/base-state)
                    (assoc-in [:account-context :spectate-mode] {:active? true :address sf/other-owner}))]
      (is (true? (:read-only? (snapshots/account-snapshot state now))))))
  (testing "no wallet is unavailable"
    (let [snap (snapshots/account-snapshot (assoc (sf/base-state) :wallet {}) now)]
      (is (= :unavailable (:status snap)))
      (is (= [:capability/account-required] (mapv :code (:errors snap))))
      (is (nil? (:base snap)))
      (is (= [] (:open-orders snap)))
      (is (contracts/account-snapshot-valid? snap)))))

(deftest fee-snapshot-test
  (testing "the generic fallback quote makes the capability unavailable"
    (let [snap (snapshots/fee-snapshot (sf/base-state) now)]
      (is (= :unavailable (:status snap)))
      (is (contracts/fee-capability-valid? snap))))
  (testing "a user fee quote becomes a verified-false, fraction-based capability"
    (let [state (assoc-in (sf/base-state) [:portfolio :user-fees]
                          {:userSpotCrossRate "0.0007" :userSpotAddRate "0.0004"})
          snap (snapshots/fee-snapshot state now)]
      (is (= :ok (:status snap)))
      (is (= "0.0004" (:maker-fraction snap)) "0.04 percent converts exactly once to 0.0004")
      (is (= :base (:buy-fee-token snap)))
      (is (= :quote (:sell-fee-token snap)))
      (is (false? (:settlement-verified? snap)))
      (is (= now (:loaded-at-ms snap)))
      (is (contracts/fee-capability-valid? snap))))
  (is (= :ok (:status fx/synthetic-fees))))

(def ^:private day-ms 86400000)

(deftest auto-volatility-staleness-test
  (let [state (sf/base-state)
        day-start (* day-ms (js/Math.floor (/ sf/now-ms day-ms)))
        midday (+ day-start (* 10 3600000))
        fetched (assoc-in state [:vol-range :daily-fetch "@107" :completed-at-ms] midday)
        rollover (+ day-start day-ms 1800000)]
    (testing "rows fetched mid-day D are fresh inside D"
      (is (= :ok (get-in (snapshots/market-snapshot fetched (+ midday 60000)) [:auto-volatility :status]))))
    (testing "rows fetched mid-day D at D+1 00:30 give an unavailable stale result, never a value"
      (let [av (:auto-volatility (snapshots/market-snapshot fetched rollover))]
        (is (= :unavailable (:status av)))
        (is (= :stale (:reason av)))
        (is (= :previous-day (:stale-reason av)))
        (is (nil? (:daily-fraction av)))))
    (testing "older than the fetch TTL within the same day is stale"
      (let [av (:auto-volatility (snapshots/market-snapshot fetched (+ midday 900001)))]
        (is (= :stale (:reason av)))
        (is (= :ttl-expired (:stale-reason av)))))
    (testing "rows with no successful-fetch provenance are not trusted"
      (let [av (:auto-volatility (snapshots/market-snapshot
                                  (update state :vol-range dissoc :daily-fetch) sf/now-ms))]
        (is (= :stale (:reason av)))
        (is (= :never-fetched (:stale-reason av)))))
    (testing "a request that has not completed does not make data fresh"
      (let [requested-only (-> state
                               (update :vol-range dissoc :daily-fetch)
                               (assoc-in [:vol-range :daily-fetch "@107" :requested-at-ms] sf/now-ms))]
        (is (= :stale (get-in (snapshots/market-snapshot requested-only sf/now-ms) [:auto-volatility :reason])))))
    (testing "a successful refetch after rollover is no longer reported stale"
      (let [refetched (assoc-in fetched [:vol-range :daily-fetch "@107" :completed-at-ms] (+ rollover 1000))]
        (is (not= :stale (get-in (snapshots/market-snapshot refetched (+ rollover 2000)) [:auto-volatility :reason])))))))
