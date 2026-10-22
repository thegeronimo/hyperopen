(ns hyperopen.vol-range.fixtures
  "Shared Vol Range test data (HO-SPEC-014 section 13). Data only.")

(def now-2026-10-19-14z (js/Date.UTC 2026 9 19 14 0 0))

(def day-ms 86400000)

(defn daily-rows
  "`n` exchange-shaped daily candles starting at `first-day-ms`."
  [coin first-day-ms n high low]
  (mapv (fn [i]
          (let [open-ms (+ first-day-ms (* i day-ms))]
            {:t open-ms :T (+ open-ms 86399999) :s coin :i "1d"
             :o "100" :c "100" :h high :l low :v "1000" :n 10}))
        (range n)))

(def seven-order-inputs
  {:anchor "80"
   :daily-fraction "0.06"
   :tilt-enabled? false
   :inventory "150"
   :core "120"
   :target "160"
   :maximum "190"
   :available-base "150"
   :available-usdc "3000"
   :buy {:quantity "40" :cap "2900" :count 4 :z-start "0.75" :z-end "2.25"
         :skew "1.857142857143"}
   :sell {:quantity "30" :count 3 :z-start "1.25" :z-end "2.25" :skew "1"}
   :bbo {:bid "79.99" :ask "80.01"}
   :sz-decimals 2
   :market-type :spot})

;; ---------------------------------------------------------------------------
;; Planner snapshots (spec 13.2 synthetic market)
;; ---------------------------------------------------------------------------

(def synthetic-fees
  {:status :ok :maker-fraction "0.0004" :buy-fee-token :quote :sell-fee-token :quote
   :settlement-verified? true :source :synthetic :loaded-at-ms 0})

(defn market-snapshot
  ([] (market-snapshot {}))
  ([overrides]
   (merge {:status :ready :errors [] :coin "SYN/USDC" :market-type :spot
           :base-symbol "SYN" :quote-symbol "USDC" :sz-decimals 2
           :bbo {:bid "79.99" :ask "80.01" :observed-at-ms 1000}
           :anchors {:vwap-24h {:price "80" :observed-at-ms 1000}
                     :utc-open {:price "79" :observed-at-ms 500}
                     :market-price nil}
           :auto-volatility {:status :ok :source :auto :daily-fraction "0.06" :zero? false
                             :window {:first-day-ms 0 :last-day-ms 1} :estimator-version "parkinson-v1"}
           :observed-at-ms 1000}
          overrides)))

(defn account-snapshot
  ([] (account-snapshot {}))
  ([overrides]
   (merge {:status :ready :errors [] :account-address "0xabc" :account-mode :classic
           :read-only? false
           :base {:total "150" :hold "0" :available "150"}
           :quote {:total "3000" :hold "0" :available "3000"}
           :open-orders [] :observed-at-ms 1000}
          overrides)))

(def draft-13-2
  {:anchor {:mode :custom :custom-price "80"}
   :volatility {:mode :manual :manual-daily-pct "6.00"}
   :inventory {:protected-core "120" :target "160" :maximum "190"
               :tilt-enabled? false :tilt-strength "0.45"}
   :buy {:enabled? true :quantity "40" :max-usdc-debit "2900" :count 4
         :start-sigma "0.75" :end-sigma "2.25" :skew "1.857142857143"}
   :sell {:enabled? true :quantity "30" :count 3 :start-sigma "1.25" :end-sigma "2.25" :skew "1"}})

(defn plan-input
  ([] (plan-input {}))
  ([overrides]
   (merge {:plan-id "plan-1" :scope {:network :hyperliquid-mainnet :account-address "0xabc" :spot-pair-index 1}
           :draft draft-13-2 :market (market-snapshot) :account (account-snapshot)
           :fees synthetic-fees :pending-intents [] :clock 1000
           :model-version 1 :precision-version 1}
          overrides)))
