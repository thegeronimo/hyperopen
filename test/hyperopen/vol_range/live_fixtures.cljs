(ns hyperopen.vol-range.live-fixtures
  "SYNTHETIC WebSocket and REST evidence for Vol Range live-fill tests. The
  frames are shaped after the documented Hyperliquid `userFills` and
  `openOrders` subscription payloads and `userFillsByTime` rows; none was
  captured from the venue. Account `0xabc` and coin `SYN/USDC` match
  `hyperopen.vol-range.simulator`, whose first resting order gets oid 9000.

  `grid` builds the matching ladder: buy rungs 0 and 1 and sell rung 0,
  resting with oids 9000, 9001 and 9002."
  (:require [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]))

(def account "0xabc")
(def coin "SYN/USDC")
(def network :hyperliquid-mainnet)

(def cloid-buy-0 "0x484f5652000000000000000000000a01")
(def cloid-buy-1 "0x484f5652000000000000000000000a02")
(def cloid-sell-0 "0x484f5652000000000000000000000a03")

(def scope {:network network :account-address account :coin coin :asset-id 10001})

(def placed-at-ms 1700000001000)
(def fill-time-ms 1700000005000)
(def received-at-ms 1700000005400)

(defn grid
  "The fixture ladder. `delivery` :response (default) leaves every leg
  accepted and resting; :unknown leaves every placement unknown; :pending
  leaves them pending (sent, no response yet)."
  ([] (grid :response))
  ([delivery]
   (let [g (-> (lifecycle/new-grid {:grid-id "grid-live" :scope scope :scope-key "k" :operation-id "op"
                                    :plan-id "p" :expires-after-ms 31000 :now-ms 1700000000000
                                    :legs [{:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4" :size "5"
                                            :cloid cloid-buy-0}
                                           {:leg-id "buy-1" :side :buy :rung-index 1 :price "75" :size "5"
                                            :cloid cloid-buy-1}
                                           {:leg-id "sell-0" :side :sell :rung-index 0 :price "84" :size "4"
                                            :cloid cloid-sell-0}]})
               (lifecycle/mark-sending "op" "a1" 1700000000500))]
     (case delivery
       :pending g
       :unknown (evidence/apply-placement-delivery g "op" {:delivery :unknown :error "timeout"} placed-at-ms)
       (evidence/apply-placement-delivery
        g "op"
        {:delivery :response
         :leg-results [{:cloid cloid-buy-0 :oid "9000" :placement-state :accepted :order-state :resting}
                       {:cloid cloid-buy-1 :oid "9001" :placement-state :accepted :order-state :resting}
                       {:cloid cloid-sell-0 :oid "9002" :placement-state :accepted :order-state :resting}]}
        placed-at-ms)))))

;; ---------------------------------------------------------------------------
;; Fill rows
;; ---------------------------------------------------------------------------

(def partial-fill
  "2 of buy rung 0's 5, as a WebSocket `userFills` row."
  {:coin coin :px "76.4" :sz "2" :side "B" :time fill-time-ms :startPosition "150.0" :dir "Buy"
   :closedPnl "0.0" :hash "0x0000000000000000000000000000000000000000000000000000000000004401"
   :oid 9000 :crossed false :fee "0.0012" :tid 4401 :feeToken "SYN" :builderFee "0.0002"})

(def rest-partial-fill
  "The same fill as a REST `userFillsByTime` row: identical time, tid and oid,
  venue strings for money."
  (assoc partial-fill :px "76.4" :sz "2"))

(def other-coin-fill
  "Another asset's fill with the SAME trade id as `partial-fill`."
  (assoc partial-fill :coin "@107" :oid 777 :px "1.2" :sz "10"))

(def manual-fill
  "A same-coin fill of an order no ladder knows."
  (assoc partial-fill :oid 8888 :tid 4402 :sz "1"))

(defn- fills-frame [data] {:channel "userFills" :data data})

(def fills-snapshot
  (fills-frame {:isSnapshot true :user account :fills [partial-fill other-coin-fill manual-fill]}))

(def fills-increment
  (fills-frame {:user account :fills [partial-fill]}))

(def fills-250
  "A 250-row increment whose LAST row is the ladder's fill (the display list
  keeps only 200)."
  (fills-frame {:user account
                :fills (conj (mapv #(assoc manual-fill :oid (+ 20000 %) :tid (+ 50000 %)) (range 249))
                             partial-fill)}))

(def fills-without-address
  (fills-frame {:fills [partial-fill]}))

(def fills-vector-frame
  "The payload as a bare vector with the snapshot flag on the message."
  {:channel "userFills" :user account :isSnapshot true :data [partial-fill]})

;; ---------------------------------------------------------------------------
;; Open-order rows
;; ---------------------------------------------------------------------------

(defn order-row
  "An `openOrders` row as the venue lists it."
  [{:keys [oid cloid side px sz orig-sz]}]
  (cond-> {:coin coin :side side :limitPx px :sz sz :origSz (or orig-sz sz) :oid oid
           :timestamp placed-at-ms :reduceOnly false :orderType "Limit" :tif "Alo"}
    cloid (assoc :cloid cloid)))

(def buy-0-row (order-row {:oid 9000 :cloid cloid-buy-0 :side "B" :px "76.4" :sz "5"}))
(def buy-1-row (order-row {:oid 9001 :side "B" :px "75" :sz "5"}))
(def sell-0-row (order-row {:oid 9002 :cloid cloid-sell-0 :side "A" :px "84" :sz "4"}))

(defn open-orders-frame
  ([rows] (open-orders-frame "" rows))
  ([dex rows] {:channel "openOrders" :data {:user account :dex dex :orders rows}}))

(def open-orders-default
  "Every leg listed: buy rung 0 by CLOID and oid, buy rung 1 by oid only."
  (open-orders-frame [buy-0-row buy-1-row sell-0-row]))

(def open-orders-xyz
  "A builder-dex frame: it says nothing about spot orders."
  (open-orders-frame "xyz" []))

(def open-orders-2000
  "2000 rows, none informative for the ladder: other coins and unknown oids."
  (open-orders-frame (mapv (fn [i]
                             (if (even? i)
                               (assoc (order-row {:oid (+ 30000 i) :side "B" :px "1" :sz "1"}) :coin "@107")
                               (order-row {:oid (+ 30000 i) :side "A" :px "90" :sz "1"})))
                           (range 2000))))
