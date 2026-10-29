(ns hyperopen.telemetry.console-preload.vol-range-fixture
  "Debug fixture for the Vol Range A0 ticket, chart overlays and review
   (HO-SPEC-014 v2.1 section 13.2). Seeds a synthetic USDC spot market and
   the seven-order draft into the app store. With `{report: \"<scenario>\"}`
   it also commits a Vol Range report scenario
   (`vol-range-report-fixture/scenarios`) and returns a promise. Debug/QA
   only; VR-24: seeds state, never builds or sends an order."
  (:require [hyperopen.state.trading :as trading]
            [hyperopen.system :as app-system]
            [hyperopen.telemetry.console-preload.vol-range-report-fixture :as report-fixture]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]))

(def ^:private coin "@9001")
(def ^:private day-ms 86400000)
(def ^:private wallet-address "0x1234567890abcdef1234567890abcdef12345678")

(defn- config-map [config]
  (cond
    (map? config) config
    (and (some? config) (object? config)) (js->clj config :keywordize-keys true)
    :else {}))

(def ^:private market
  {:key "spot:@9001"
   :coin coin
   :symbol "VRT/USDC"
   :base "VRT"
   :quote "USDC"
   :market-type :spot
   :szDecimals 2
   :idx 9001
   :asset-id 19001
   :markRaw "80.00"})

(def ^:private draft
  {:anchor {:mode :custom :custom-price "80"}
   :volatility {:mode :manual :manual-daily-pct "6.00"}
   :inventory {:protected-core "120" :target "160" :maximum "190"
               :tilt-enabled? false :tilt-strength "0.45"}
   :buy {:enabled? true :quantity "40" :max-usdc-debit "2900" :count 4
         :start-sigma "0.75" :end-sigma "2.25" :skew "1.857142857143"}
   :sell {:enabled? true :quantity "30" :count 3
          :start-sigma "1.25" :end-sigma "2.25" :skew "1"}})

(defn- daily-candles
  "Forty completed-looking daily candles around 80 ending at the last UTC midnight."
  [now-ms]
  (let [today (* day-ms (js/Math.floor (/ now-ms day-ms)))]
    (mapv (fn [i]
            (let [open-ms (- today (* day-ms (- 40 i)))
                  wobble (* 1.5 (js/Math.sin (/ i 3)))
                  open (+ 80 wobble)
                  close (+ 80 (* 1.5 (js/Math.sin (/ (inc i) 3))))]
              {:t open-ms :T (+ open-ms (dec day-ms)) :s coin :i "1d"
               :o (.toFixed open 2) :c (.toFixed close 2)
               :h (.toFixed (+ (max open close) 2.5) 2)
               :l (.toFixed (- (min open close) 2.5) 2)
               :v "1000" :n 10}))
          (range 40))))

(defn seed-vol-range-fixture!
  ([] (seed-vol-range-fixture! nil))
  ([config]
   (let [config* (config-map config)
         now-ms (.now js/Date)
         base (-> @app-system/store
                  (assoc :active-asset coin
                         :active-market market
                         :order-form (-> (trading/default-order-form)
                                         (assoc :type :scale)
                                         (assoc-in [:scale :mode] :volatility-range)
                                         (assoc-in [:scale :vol-range]
                                                   (cond-> draft
                                                     (:tilt config*)
                                                     (assoc-in [:inventory :tilt-enabled?] true))))
                         :order-form-ui (assoc (trading/default-order-form-ui) :entry-mode :pro)
                         :order-form-runtime (trading/default-order-form-runtime))
                  (assoc-in [:wallet :address] wallet-address)
                  (assoc-in [:account :mode] :classic)
                  (assoc-in [:account :abstraction-raw] "default")
                  (assoc-in [:spot :meta] {:universe [{:name coin :index 9001 :tokens [9001 0]}]
                                           :tokens [{:name "USDC" :index 0 :szDecimals 8}
                                                    {:name "VRT" :index 9001 :szDecimals 2}]})
                  (assoc-in [:spot :clearinghouse-state]
                            {:balances [{:coin "USDC" :token 0 :total "3000" :hold "0"}
                                        {:coin "VRT" :token 9001 :total "150" :hold "0"}]})
                  (assoc-in [:orderbooks coin] {:bids [{:px "79.99" :sz "100"}]
                                                :asks [{:px "80.01" :sz "100"}]})
                  (assoc-in [:candles coin :1d] (daily-candles now-ms))
                  ;; Provenance: the seeded series counts as fetched successfully now.
                  (assoc-in [:vol-range :daily-fetch coin :completed-at-ms] now-ms)
                  (assoc-in [:portfolio :user-fees] {:userSpotCrossRate "0.0007"
                                                     :userSpotAddRate "0.0004"
                                                     :activeReferralDiscount 0
                                                     :activeStakingDiscount {:discount 0}})
                  (assoc-in [:asset-selector :market-by-key "spot:@9001"] market))
         next-state (assoc-in base [:vol-range :draft-binding]
                              (draft-scope/current-scope-key base))]
     (reset! app-system/store next-state)
     (if-let [scenario (:report config*)]
       (report-fixture/seed-report! scenario wallet-address now-ms)
       (clj->js {:ok true :coin coin :marketKey "spot:@9001"})))))
