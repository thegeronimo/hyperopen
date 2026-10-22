(ns hyperopen.vol-range.state-fixtures
  "App-state fixtures for Vol Range state, snapshot, projection and action tests
  (HO-SPEC-014 section 13.2 seven-order market). Data and helpers only."
  (:require [hyperopen.state.trading :as trading]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.vol-range.fixtures :as fx]))

(def owner "0x1234567890abcdef1234567890abcdef12345678")
(def other-owner "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")
(def subaccount "0x9999999999999999999999999999999999999999")

(def now-ms fx/now-2026-10-19-14z)

(def first-window-day (js/Date.UTC 2026 9 5))

(defn daily-rows [coin]
  (fx/daily-rows coin first-window-day 14 "103" "97"))

(defn spot-meta []
  {:universe [{:name "@107" :index 107 :tokens [150 0]}
              {:name "@108" :index 108 :tokens [151 0]}]
   :tokens [{:name "USDC" :index 0 :szDecimals 8}
            {:name "SYN" :index 150 :szDecimals 2}
            {:name "OTH" :index 151 :szDecimals 2}]})

(defn market [coin idx base]
  {:coin coin :idx idx :asset-id (+ 10000 idx) :market-type :spot :base base :quote "USDC"
   :key (str "spot:" coin) :szDecimals 2})

(defn base-state
  "Connected classic account, SYN/USDC spot market, BBO 79.99/80.01, 150 SYN and
  3000 USDC, 14 completed daily candles, scale ticket in Volatility range mode
  with the 13.2 draft bound to the current scope."
  []
  (let [state {:active-asset "@107"
               :active-market (market "@107" 107 "SYN")
               :wallet {:address owner}
               :account {:mode :classic}
               :spot {:meta (spot-meta)
                      :clearinghouse-state {:balances [{:coin "USDC" :token 0 :total "3000" :hold "0"}
                                                       {:coin "SYN" :token 150 :total "150" :hold "0"}]}}
               :orderbooks {"@107" {:bids [{:px "79.99" :sz "5"} {:px "79.5" :sz "9"}]
                                    :asks [{:px "80.01" :sz "5"} {:px "81" :sz "9"}]}}
               :active-assets {:contexts {"@107" {:dayNtlVlmRaw "8000" :dayBaseVlmRaw "100"}}}
               :candles {"@107" {:1d (daily-rows "@107")}}
               :order-form (-> (trading/default-order-form)
                               (assoc :type :scale)
                               (assoc-in [:scale :mode] :volatility-range)
                               (assoc-in [:scale :vol-range] fx/draft-13-2))
               :order-form-ui (assoc (trading/default-order-form-ui) :entry-mode :pro)
               :order-form-runtime (trading/default-order-form-runtime)
               :ui {:locale "en-US"}}]
    (-> state
        (assoc-in [:vol-range :draft-binding] (draft-scope/current-scope-key state))
        ;; The daily series was fetched successfully "now": fresh for auto volatility.
        (assoc-in [:vol-range :daily-fetch "@107" :completed-at-ms] now-ms))))

(defn apply-path-values
  "Apply save-many path values the way the store would."
  [state path-values]
  (reduce (fn [acc [path value]] (assoc-in acc path value)) state path-values))

(defn apply-effects
  "Apply every :effects/save and :effects/save-many in `effects` to `state`."
  [state effects]
  (reduce (fn [acc [effect-id & args]]
            (case effect-id
              :effects/save-many (apply-path-values acc (first args))
              :effects/save (assoc-in acc (first args) (second args))
              acc))
          state
          effects))

(def with-synthetic-fees
  "A fee snapshot replacement for `with-redefs`."
  (fn [_state _now-ms] fx/synthetic-fees))
