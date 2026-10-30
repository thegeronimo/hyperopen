(ns hyperopen.vol-range.application.live-evidence-test
  "WebSocket evidence envelopes, routing and fill identity (HO-SPEC-014 v2.1
  sections 9.1, 9.2; VR-14, VR-18, VR-19)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.live-evidence :as live]
            [hyperopen.vol-range.live-fixtures :as fx]))

(def opts {:received-at-ms fx/received-at-ms :network fx/network :fallback-address nil :revision "1:1"})

(defn- fills [msg] (live/fills-envelope msg opts))
(defn- orders [msg] (live/open-orders-envelope msg opts))

(defn- apply-routed
  "Route `envelope` to `grids` and apply every routed grid's rows."
  [grids envelope]
  (let [{:keys [by-grid]} (live/route envelope grids)]
    (reduce-kv (fn [gs gid {f :fills o :orders}]
                 (update gs gid live/apply-envelope envelope (into f o)))
               grids by-grid)))

(defn- leg [g cloid] (get-in g [:legs cloid]))

;; ---------------------------------------------------------------------------
;; Envelopes
;; ---------------------------------------------------------------------------

(deftest fills-envelope-reads-nested-and-vector-payloads-test
  (let [e (fills fx/fills-snapshot)]
    (is (= {:kind :fills :scope {:network :hyperliquid-mainnet :account-address "0xabc"} :source :websocket
            :channel "userFills" :request-id nil :received-at-ms fx/received-at-ms
            :observed-at-ms fx/fill-time-ms :snapshot? true :complete? false :address-source :message
            :account-event-revision "1:1" :dex nil}
           (dissoc e :rows)))
    (is (= 3 (count (:rows e)))))
  (testing "an increment is not a snapshot"
    (is (false? (:snapshot? (fills fx/fills-increment)))))
  (testing "a bare vector payload with the flag on the message"
    (let [e (fills fx/fills-vector-frame)]
      (is (= [fx/partial-fill] (:rows e)))
      (is (true? (:snapshot? e)))
      (is (= "0xabc" (get-in e [:scope :account-address])))))
  (testing "an empty snapshot is still an envelope"
    (let [e (fills {:channel "userFills" :data {:isSnapshot true :user "0xABC" :fills []}})]
      (is (= [] (:rows e)))
      (is (true? (:snapshot? e)))
      (is (nil? (:observed-at-ms e)))))
  (testing "numeric-string times count toward the observation time"
    (is (= 1700000009000 (:observed-at-ms (fills {:channel "userFills"
                                                  :data {:user "0xabc"
                                                         :fills [fx/partial-fill
                                                                 (assoc fx/manual-fill :time "1700000009000")]}}))))))

(deftest envelopes-require-an-account-test
  (testing "the message address wins and is lower-cased"
    (let [e (live/fills-envelope (assoc-in fx/fills-increment [:data :user] "0xABC")
                                 (assoc opts :fallback-address "0xdef"))]
      (is (= "0xabc" (get-in e [:scope :account-address])))
      (is (= :message (:address-source e)))))
  (testing "without one, the live subscription's address is used and marked"
    (let [e (live/fills-envelope fx/fills-without-address (assoc opts :fallback-address "0xABC"))]
      (is (= "0xabc" (get-in e [:scope :account-address])))
      (is (= :subscription (:address-source e)))))
  (testing "with neither there is no envelope"
    (is (nil? (fills fx/fills-without-address)))
    (is (nil? (orders {:channel "openOrders" :data {:dex "" :orders []}})))))

(deftest open-orders-envelope-shapes-test
  (let [e (orders fx/open-orders-default)]
    (is (= :orders-snapshot (:kind e)))
    (is (true? (:snapshot? e)))
    (is (false? (:complete? e)))
    (is (nil? (:dex e)) "a blank dex is the default dex")
    (is (= 3 (count (:rows e)))))
  (is (= "xyz" (:dex (orders fx/open-orders-xyz))))
  (testing "rows under :openOrders, :data or a bare vector"
    (is (= [fx/buy-0-row] (:rows (orders {:channel "openOrders" :data {:user "0xabc" :openOrders [fx/buy-0-row]}}))))
    (is (= [fx/buy-0-row] (:rows (orders {:channel "openOrders" :data {:user "0xabc" :data [fx/buy-0-row]}}))))
    (is (= [fx/buy-0-row] (:rows (orders {:channel "openOrders" :user "0xabc" :data [fx/buy-0-row]}))))))

;; ---------------------------------------------------------------------------
;; Routing (VR-18, VR-19)
;; ---------------------------------------------------------------------------

(deftest routing-ignores-other-scopes-and-unknown-orders-test
  (let [g (fx/grid)
        e (fills fx/fills-snapshot)]
    (is (= {:by-grid {"grid-live" {:fills [fx/partial-fill] :orders []}} :absent {} :checking #{}
            :order-coins #{}}
           (live/route e {"grid-live" g}))
        "only the ladder's own coin and order; the manual fill and the other coin stay out")
    (testing "another account"
      (is (= {} (:by-grid (live/route (assoc-in e [:scope :account-address] "0xdef") {"grid-live" g})))))
    (testing "another network"
      (is (= {} (:by-grid (live/route (assoc-in e [:scope :network] :hyperliquid-testnet) {"grid-live" g})))))
    (testing "another coin with a known oid"
      (is (= {} (:by-grid (live/route (fills {:channel "userFills"
                                              :data {:user "0xabc" :fills [(assoc fx/partial-fill :coin "BTC/USDC")]}})
                                      {"grid-live" g})))))
    (testing "an unknown oid on the right coin"
      (is (= {} (:by-grid (live/route (fills {:channel "userFills" :data {:user "0xabc" :fills [fx/manual-fill]}})
                                      {"grid-live" g})))))
    (testing "applying the routed frame changes nothing for another account's grid"
      (let [other (assoc-in g [:scope :account-address] "0xdef")]
        (is (identical? other (get (apply-routed {"x" other} e) "x")))))))

(deftest same-trade-id-on-another-coin-goes-only-to-its-own-ladder-test
  (let [syn (fx/grid)
        other (-> (fx/grid)
                  (assoc :grid-id "grid-107")
                  (assoc-in [:scope :coin] "@107")
                  (assoc-in [:legs fx/cloid-buy-0 :oid] "777"))
        routed (:by-grid (live/route (fills fx/fills-snapshot) {"grid-live" syn "grid-107" other}))]
    (is (= [fx/partial-fill] (get-in routed ["grid-live" :fills])))
    (is (= [fx/other-coin-fill] (get-in routed ["grid-107" :fills])))
    (let [gs (apply-routed {"grid-live" syn "grid-107" other} (fills fx/fills-snapshot))]
      (is (= 1 (count (:fills (get gs "grid-live")))))
      (is (= 1 (count (:fills (get gs "grid-107")))))
      (is (not= (keys (:fills (get gs "grid-live"))) (keys (:fills (get gs "grid-107"))))))))

(deftest the-250th-fill-is-routed-test
  (let [e (fills fx/fills-250)]
    (is (= 250 (count (:rows e))))
    (is (= [fx/partial-fill] (get-in (live/route e {"grid-live" (fx/grid)}) [:by-grid "grid-live" :fills])))))

(deftest a-fill-on-a-pending-leg-waits-for-the-response-test
  (let [g (fx/grid :pending)
        row (assoc fx/partial-fill :cloid fx/cloid-buy-0)
        e (fills {:channel "userFills" :data {:user "0xabc" :fills [row]}})]
    (is (= {} (:by-grid (live/route e {"grid-live" g}))))
    (is (identical? g (live/apply-envelope g e [row])))
    (testing "once the response is in, the same row applies"
      (let [g (get (apply-routed {"grid-live" (fx/grid)} e) "grid-live")]
        (is (= 1 (count (:fills g))))))))

(deftest checking-ladders-are-reported-test
  ;; An unknown placement has no oid yet, so the fill names it by CLOID.
  (let [g (fx/grid :unknown)
        row (assoc fx/partial-fill :cloid fx/cloid-buy-0)
        e (fills {:channel "userFills" :data {:user "0xabc" :fills [row]}})
        r (live/route e {"grid-live" g})]
    (is (= #{"grid-live"} (:checking r)))
    (is (= {} (:by-grid (live/route (fills fx/fills-increment) {"grid-live" g})))
        "an oid the ladder never learned identifies nothing")
    (let [leg* (leg (live/apply-envelope g e [row]) fx/cloid-buy-0)]
      (is (= :accepted (:placement-state leg*)) "a fill proves acceptance")
      (is (= "9000" (:oid leg*))))))

;; ---------------------------------------------------------------------------
;; Fill identity (spec 9.2, VR-14)
;; ---------------------------------------------------------------------------

(deftest snapshot-increment-and-rest-row-count-one-fill-test
  (let [g (get (-> {"grid-live" (fx/grid)}
                   (apply-routed (fills fx/fills-snapshot))
                   (apply-routed (fills fx/fills-increment)))
               "grid-live")
        g (evidence/ingest-fills g {:account-address "0xabc" :rows [fx/rest-partial-fill]
                                    :received-at-ms (+ fx/received-at-ms 15000)})]
    (is (= 1 (count (:fills g))))
    (is (= "2" (:filled (lifecycle/leg-quantities g (leg g fx/cloid-buy-0)))))
    (is (= "3" (:remaining (lifecycle/leg-quantities g (leg g fx/cloid-buy-0)))))
    (testing "a REST row with numeric-string time and tid is the same fill"
      (let [g* (evidence/ingest-fills g {:account-address "0xabc" :received-at-ms 1
                                         :rows [(assoc fx/rest-partial-fill :time (str fx/fill-time-ms) :tid "4401")]})]
        (is (identical? g g*))))))

(deftest fill-key-canonicalizes-time-and-tid-test
  (let [k (evidence/fill-key :hyperliquid-mainnet "0xABC" fx/partial-fill)]
    (is (= "hyperliquid-mainnet|0xabc|SYN/USDC|1700000005000|4401|9000" k))
    (is (= k (evidence/fill-key :hyperliquid-mainnet "0xabc"
                                (assoc fx/partial-fill :time "1700000005000" :tid "4401")))))
  (testing "existing keys for numeric inputs are byte-identical"
    (is (= "hyperliquid-mainnet|0xabc|SYN/USDC|2500|77|12"
           (evidence/fill-key :hyperliquid-mainnet "0xabc" {:coin "SYN/USDC" :time 2500 :tid 77 :oid 12})))
    (is (= "hyperliquid-mainnet|0xabc|SYN/USDC|2500|77|0x484f"
           (evidence/fill-key nil "0xabc" {:coin "SYN/USDC" :time 2500 :tid 77 :cloid "0x484F"})))
    (is (= "hyperliquid-mainnet|0xabc|SYN/USDC|||"
           (evidence/fill-key nil "0xabc" {:coin "SYN/USDC"}))
        "missing parts render as before")))

;; ---------------------------------------------------------------------------
;; Open orders (spec 9.1: presence only, never stale state)
;; ---------------------------------------------------------------------------

(deftest presence-resolves-an-unknown-leg-test
  (let [g (fx/grid :unknown)
        g* (get (apply-routed {"grid-live" g} (orders fx/open-orders-default)) "grid-live")]
    (doseq [c [fx/cloid-buy-0 fx/cloid-sell-0]]
      (is (lifecycle/live-leg? (leg g* c)) c))
    (is (= "9000" (:oid (leg g* fx/cloid-buy-0))) "the venue oid is learned")
    (is (= :unknown (:placement-state (leg g* fx/cloid-buy-1)))
        "a row without a CLOID cannot identify a leg whose oid was never learned")))

(deftest an-unconfirmed-leg-is-never-revived-by-a-frame-test
  (let [g (evidence/apply-orders-snapshot (fx/grid) {:account-address "0xabc" :requested-at-ms 1700000003000
                                                     :received-at-ms 1700000003100 :complete? true
                                                     :open-orders [fx/buy-1-row fx/sell-0-row]})]
    (is (= :unconfirmed (:order-state (leg g fx/cloid-buy-0))))
    (is (= {} (:by-grid (live/route (orders fx/open-orders-default) {"grid-live" g}))))
    (is (identical? g (live/apply-envelope g (orders fx/open-orders-default) [fx/buy-0-row])))))

(deftest remaining-only-moves-down-test
  (let [g (fx/grid)
        smaller (assoc fx/buy-0-row :sz "3")
        g* (get (apply-routed {"grid-live" g} (orders (fx/open-orders-frame [smaller]))) "grid-live")]
    (is (= "3" (:observed-remaining (leg g* fx/cloid-buy-0))))
    (is (= "2" (:reported-filled-base (leg g* fx/cloid-buy-0))))
    (testing "a larger size than observed is ignored"
      (is (= {} (:by-grid (live/route (orders (fx/open-orders-frame [(assoc fx/buy-0-row :sz "4")]))
                                      {"grid-live" g*}))))
      (is (identical? g* (live/apply-envelope g* (orders fx/open-orders-default) [fx/buy-0-row]))))
    (testing "a repeat frame is not informative"
      (is (= {} (:by-grid (live/route (orders (fx/open-orders-frame [smaller])) {"grid-live" g*}))))
      (is (identical? g* (live/apply-envelope g* (orders (fx/open-orders-frame [smaller])) [smaller]))))
    (testing "the full listing of an untouched ladder is not informative either"
      (is (= {} (:by-grid (live/route (orders fx/open-orders-default) {"grid-live" g})))))))

(deftest a-repeated-fill-frame-is-not-informative-test
  (let [g (get (apply-routed {"grid-live" (fx/grid)} (fills fx/fills-increment)) "grid-live")]
    (is (= {} (:by-grid (live/route (fills fx/fills-increment) {"grid-live" g}))))
    (is (identical? g (live/apply-envelope g (fills fx/fills-increment) [fx/partial-fill])))))

(deftest absence-is-reported-not-applied-test
  (let [g (fx/grid)
        frame (fx/open-orders-frame [fx/buy-1-row])
        late (assoc opts :received-at-ms (+ fx/placed-at-ms 2000))
        early (assoc opts :received-at-ms (+ fx/placed-at-ms 1999))]
    (is (= {"grid-live" #{fx/cloid-buy-0 fx/cloid-sell-0}}
           (:absent (live/route (live/open-orders-envelope frame late) {"grid-live" g}))))
    (testing "the margin after the newest evidence"
      (is (= {} (:absent (live/route (live/open-orders-envelope frame early) {"grid-live" g})))))
    (testing "a builder-dex frame says nothing about spot orders"
      (let [r (live/route (live/open-orders-envelope (fx/open-orders-frame "xyz" []) late) {"grid-live" g})]
        (is (= {} (:absent r)))
        (is (= #{} (:order-coins r)))))
    (testing "terminal legs are never absent"
      (let [g (evidence/apply-order-statuses g [{:id "9000" :status "canceled"
                                                 :order {:order {:coin "SYN/USDC" :oid 9000 :sz "5" :origSz "5"}}}]
                                             1700000001500)]
        (is (= {"grid-live" #{fx/cloid-sell-0}}
               (:absent (live/route (live/open-orders-envelope frame late) {"grid-live" g}))))))
    (testing "absence never changes the ladder"
      (is (identical? g (live/apply-envelope g (live/open-orders-envelope frame late) []))))
    (testing "default-dex coins are recorded for the caller's guard"
      (is (= #{"SYN/USDC"} (:order-coins (live/route (live/open-orders-envelope frame late) {"grid-live" g})))))))

(deftest a-large-uninformative-frame-routes-nothing-test
  (let [r (live/route (orders fx/open-orders-2000) {"grid-live" (fx/grid)})]
    (is (= {} (:by-grid r)))
    (is (= #{} (:checking r)))
    (is (= #{"SYN/USDC" "@107"} (:order-coins r)))))
