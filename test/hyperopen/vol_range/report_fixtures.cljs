(ns hyperopen.vol-range.report-fixtures
  "SYNTHETIC Vol Range reporting fixtures (HO-SPEC-014 v2.1 section 13.5).
  Nothing here was captured from the venue: rows are shaped after the
  documented `userFillsByTime`, `userFills`, `userNonFundingLedgerUpdates`
  and `candleSnapshot` payloads.

  Market `@9001` is VRT/USDC (base token 9001, size decimals 2); `@9002` is
  VRS/USDC (token 9002); `@9003` is VRT/VRS, another pair containing VRT.
  The VRT campaign starts at T0 = 2026-10-21T13:42:00Z with q0 = 150 and
  P0 = 80 (book 79.99/80.01). Its ladder `grid-vrt` was admitted 200 ms
  after T0 with buy rung 0 (oid 7001) and sell rung 0 (oid 7002)."
  (:require [hyperopen.vol-range.application.campaign :as campaign]
            [hyperopen.vol-range.application.fill-records :as fill-records]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]))

(def account "0xabc")
(def network :hyperliquid-mainnet)
(def t0 1792590120000)
(def hour 3600000)
(def skew 5000)

(def spot-meta
  {:universe [{:name "@9001" :index 9001 :tokens [9001 0]}
              {:name "@9002" :index 9002 :tokens [9002 0]}
              {:name "@9003" :index 9003 :tokens [9001 9002]}]
   :tokens [{:name "USDC" :index 0 :szDecimals 8}
            {:name "VRT" :index 9001 :szDecimals 2}
            {:name "VRS" :index 9002 :szDecimals 2}]})

(defn scope
  ([] (scope "@9001" 9001 "VRT"))
  ([coin idx symbol]
   {:network network :account-address account :coin coin :market-type :spot :spot-pair-index idx
    :base-token-id (str idx) :quote-token-id "0" :symbol symbol :quote-symbol "USDC" :sz-decimals 2
    :asset-id (+ 10000 idx)}))

(defn barrier-read
  ([at] (barrier-read at "150"))
  ([at total]
   {:barrier-id (str "rb-" at) :requested-at-ms at :received-at-ms (+ at 180) :complete? true
    :balances [{:coin "USDC" :token 0 :total "3000" :hold "0"}
               {:coin "VRT" :token 9001 :total total :hold "0"}
               {:coin "VRS" :token 9002 :total "40" :hold "0"}]}))

(def bbo {:bid "79.99" :ask "80.01" :observed-at-ms (+ t0 120)})

(defn campaign-record
  "The VRT campaign (or another, with `overrides` for :campaign-id, :t0,
  :scope)."
  ([] (campaign-record {}))
  ([{:keys [campaign-id at scope* bbo*] :or {campaign-id "cmp-vrt" at t0}}]
   (campaign/baseline-record {:campaign-id campaign-id :scope (or scope* (scope)) :read (barrier-read at)
                              :bbo (or bbo* bbo) :started-by :first-placement :operation-id "op-vrt"
                              :grid-id "grid-vrt" :now-ms (+ at 200)})))

(defn vrs-campaign []
  (campaign-record {:campaign-id "cmp-vrs" :scope* (scope "@9002" 9002 "VRS")
                    :bbo* {:bid "9.99" :ask "10.01" :observed-at-ms (+ t0 120)}}))

(def cloid-buy "0x484f5652000000000000000000000b01")
(def cloid-sell "0x484f5652000000000000000000000b02")

(defn grid
  "The VRT ladder, admitted at `admitted-at` (default T0 + 200)."
  ([] (grid {}))
  ([{:keys [grid-id admitted-at scope* recovered? oids]
     :or {grid-id "grid-vrt" admitted-at (+ t0 200) oids ["7001" "7002"]}}]
   (let [g (-> (lifecycle/new-grid {:grid-id grid-id :scope (or scope* (scope)) :scope-key "k-vrt"
                                    :operation-id "op-vrt" :plan-id "p" :expires-after-ms (+ admitted-at 30000)
                                    :now-ms admitted-at
                                    :legs [{:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4" :size "7"
                                            :cloid cloid-buy}
                                           {:leg-id "sell-0" :side :sell :rung-index 0 :price "88.4" :size "30"
                                            :cloid cloid-sell}]})
               (lifecycle/mark-sending "op-vrt" "a1" (+ admitted-at 10))
               (evidence/apply-placement-delivery
                "op-vrt"
                {:delivery :response
                 :leg-results [{:cloid cloid-buy :oid (first oids) :placement-state :accepted :order-state :resting}
                               {:cloid cloid-sell :oid (second oids) :placement-state :accepted :order-state :resting}]}
                (+ admitted-at 400)))]
     (cond-> g recovered? (assoc :recovered? true)))))

(defn fill-row
  "A raw REST `userFillsByTime` row (the WebSocket row has the same keys)."
  [{:keys [coin side px sz time tid oid cloid fee fee-token builder-fee crossed]
    :or {coin "@9001" side "B" fee "0" fee-token "USDC" crossed false}}]
  (cond-> {:coin coin :px px :sz sz :side side :time time :tid tid :oid oid :crossed crossed
           :fee fee :feeToken fee-token :hash (str "0xhash" tid) :dir (if (= "B" side) "Buy" "Sell")
           :startPosition "150.0" :closedPnl "0.0"}
    cloid (assoc :cloid cloid)
    builder-fee (assoc :builderFee builder-fee)))

(defn fill
  "The canonical record of a raw row."
  ([row] (fill row :rest))
  ([row source]
   (fill-records/normalize-fill {:network network :account-address account :row row :source source
                                 :received-at-ms (+ (:time row) 300)})))

;; 13.5 row 1: buy 7 at 76.4 (tracked, buy rung 0), no fees.
(def buy-7-row (fill-row {:side "B" :px "76.4" :sz "7" :time (+ t0 (* 2 hour)) :tid 501 :oid 7001}))

;; 13.5 row 2: sell 30 for 2,652 in total (88.4 each).
(def sell-30-row (fill-row {:side "A" :px "88.4" :sz "30" :time (+ t0 (* 2 hour)) :tid 502 :oid 7002}))

;; 13.5 row 3: buy 10 at 80 with a 0.004 base fee.
(def base-fee-row (fill-row {:side "B" :px "80" :sz "10" :time (+ t0 hour) :tid 503 :oid 7001
                             :fee "0.004" :fee-token "VRT"}))

;; 13.5 row 4: a 0.4 USDC fee that already includes a 0.1 builder fee.
(def builder-fee-row (fill-row {:side "B" :px "80" :sz "10" :time (+ t0 hour) :tid 504 :oid 7001
                                :fee "0.4" :fee-token "USDC" :builder-fee "0.1"}))

(def manual-row
  "A VRT/USDC trade no ladder owns."
  (fill-row {:side "B" :px "79" :sz "1" :time (+ t0 (* 3 hour)) :tid 601 :oid 8888}))

(def other-pair-row
  "A VRT trade in VRT/VRS."
  (fill-row {:coin "@9003" :side "A" :px "8" :sz "1" :time (+ t0 (* 3 hour)) :tid 602 :oid 8889
             :fee-token "VRS"}))

(def spot-transfer-row
  "13.5 row 7: an external VRT transfer."
  {:time (+ t0 (* 4 hour)) :hash "0xtransfer1"
   :delta {:type "spotTransfer" :token "VRT" :amount "-5" :usdcValue "-400" :user account
           :destination "0xdef" :fee "0"}})

(def usdc-deposit-row
  {:time (+ t0 (* 4 hour)) :hash "0xdeposit1" :delta {:type "deposit" :usdc "100"}})

(defn candle
  "A raw 1h candle row opening at `open-ms` and closing at `close`."
  [open-ms close]
  {:t open-ms :T (+ open-ms hour -1) :s "@9001" :i "1h" :o close :c close :h close :l close :v "10" :n 3})

(def candles
  "Closes 80, 70, 100 for the hours opening at T0's hour + 1h, +2h, +3h."
  (let [h0 (* hour (quot t0 hour))]
    [(candle (+ h0 hour) "80") (candle (+ h0 (* 2 hour)) "70") (candle (+ h0 (* 3 hour)) "100")]))

(defn coverage-record
  "A coverage record with the given complete intervals and failures."
  [stream token complete failures]
  {:schema-version 1 :record-id (str "coverage:" account "|" (name stream) "|" token) :kind :coverage
   :account-address account :stream stream :base-token-id token :complete complete :failures failures
   :saved-at-ms (+ t0 hour)})
