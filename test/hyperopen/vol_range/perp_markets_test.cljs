(ns hyperopen.vol-range.perp-markets-test
  "Vol Range on perp and HIP-3 markets: the signed position and margin
  capacity in the account snapshot, margin admission and signed inventory
  limits in the planner, the dex-scoped barrier read, and perp fills and
  funding in the report."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.vol-range.application.campaign :as campaign]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.replace-state :as replace-state]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.application.report-coverage :as coverage]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.fees :as vr-fees]
            [hyperopen.vol-range.domain.report-equations :as equations]
            [hyperopen.vol-range.domain.report-ledger :as ledger]
            [hyperopen.vol-range.domain.report-matching :as matching]
            [hyperopen.vol-range.fixtures :as fx]
            [hyperopen.vol-range.infrastructure.reads :as reads]
            [hyperopen.vol-range.report-fixtures :as rfx]
            [hyperopen.vol-range.state-fixtures :as sf]))

(def coin "xyz:NVDA")

;; ---------------------------------------------------------------------------
;; Eligibility and snapshots
;; ---------------------------------------------------------------------------

(defn- perp-state [position]
  (-> (sf/base-state)
      (assoc :active-asset coin
             :active-market {:coin coin :market-type :perp :dex "xyz" :base "NVDA" :quote "USDC"
                             :szDecimals 2 :idx 3 :asset-id 110003 :maxLeverage 20}
             :perp-dex-clearinghouse
             {"xyz" {:withdrawable "1000"
                     :assetPositions (if position
                                       [{:position {:coin coin :szi position
                                                    :leverage {:type "cross" :value 10}}}]
                                       [])}})
      (assoc-in [:orderbooks coin] {:bids [{:px "79.99" :sz "5"}] :asks [{:px "80.01" :sz "5"}]
                                    :timestamp sf/book-observed-ms})))

(deftest supported-markets-test
  (is (contracts/supported-market? {:market-type :spot :quote "USDC"}))
  (is (contracts/supported-market? {:market-type :perp :quote "USDC"}))
  (is (contracts/supported-market? {:market-type :perp :quote-symbol "USDC"}))
  (is (not (contracts/supported-market? {:market-type :outcome :quote "USDC"})))
  (is (not (contracts/supported-market? {:market-type :perp :quote "USDH"})))
  (is (not (contracts/supported-market? {}))))

(deftest perp-market-snapshot-is-ready-test
  (let [state (perp-state "-4")
        snap (snapshots/market-snapshot state sf/now-ms)
        scope (snapshots/current-scope state)]
    (is (= :ready (:status snap)) (pr-str (:errors snap)))
    (is (= :perp (:market-type snap)))
    (is (= {:bid "79.99" :ask "80.01"} (select-keys (:bbo snap) [:bid :ask])))
    (is (contracts/market-snapshot-valid? snap))
    (is (= "perp:xyz:NVDA" (:base-token-id scope)))
    (is (= ["xyz" 110003 coin] ((juxt :dex :asset-id :coin) scope)))))

(deftest perp-account-snapshot-is-the-signed-position-and-margin-test
  (testing "a short position: selling adds to it, buying covers it without margin"
    (let [a (snapshots/account-snapshot (perp-state "-4") sf/now-ms)]
      (is (= :ready (:status a)))
      (is (contracts/account-snapshot-valid? a) (pr-str a))
      (is (= {:total "-4" :hold "0" :available "125"} (:base a)) "10,000 of capacity at the 80 mid")
      (is (= "10320" (get-in a [:quote :available])) "capacity plus the 4 it can cover at 80")
      (is (= {:leverage "10" :type :cross :available "1000" :notional-capacity "10000"} (:margin a)))))
  (testing "a long position can be sold without margin"
    (let [a (snapshots/account-snapshot (perp-state "6") sf/now-ms)]
      (is (= "131" (get-in a [:base :available])))
      (is (= "10000" (get-in a [:quote :available])))))
  (testing "flat: the ticket's leverage setting stands in until the barrier read"
    (let [a (snapshots/account-snapshot (assoc-in (perp-state nil) [:order-form-ui :ui-leverage] 5) sf/now-ms)]
      (is (= "0" (get-in a [:base :total])))
      (is (= "5" (get-in a [:margin :leverage])))
      (is (= "5000" (get-in a [:quote :available])))))
  (testing "no clearinghouse state yet: balances are unknown, never zero"
    (let [a (snapshots/account-snapshot (assoc (perp-state nil) :perp-dex-clearinghouse {}) sf/now-ms)]
      (is (nil? (:base a)))
      (is (nil? (:margin a)))))
  (testing "a spot snapshot has no margin"
    (is (nil? (:margin (snapshots/account-snapshot (sf/base-state) sf/now-ms))))))

(deftest perp-account-snapshot-from-the-barrier-read-test
  (let [scope {:market-type :perp :coin coin :dex "xyz" :sz-decimals 2 :symbol "NVDA" :quote-symbol "USDC"}
        read {:account-address "0xabc" :complete? true :received-at-ms 5 :balances []
              :open-orders [{:coin coin :side "A" :limitPx "90" :sz "2" :oid 9}
                            {:coin "BTC" :side "B" :limitPx "1" :sz "1" :oid 10}]
              :perps {coin {:position "3" :withdrawable "500" :leverage 4 :leverage-type :isolated
                            :mark-px "100"}}}
        live {:raw-account-mode "default" :read-only? false}]
    (testing "priced at the venue mark without a book"
      (let [a (snapshots/account-snapshot-from-read scope read live)]
        (is (= :ready (:status a)))
        (is (= {:total "3" :hold "0" :available "23"} (:base a)))
        (is (= :isolated (get-in a [:margin :type])))
        (is (= [{:oid "9" :side :sell :price "90" :remaining "2"}] (:open-orders a)))))
    (testing "priced at the fresh book midpoint when there is one"
      (let [a (snapshots/account-snapshot-from-read
               scope (reads/with-book-mid read {:bid "79" :ask "81" :complete? true}) live)]
        (is (= "28" (get-in a [:base :available])))))
    (testing "a read that did not cover the coin is unavailable"
      (is (= :unavailable (:status (snapshots/account-snapshot-from-read scope (assoc read :perps {}) live)))))))

;; ---------------------------------------------------------------------------
;; Planner
;; ---------------------------------------------------------------------------

(def perp-fees
  (vr-fees/fee-capability {:quote {:effective {:maker 0.04}} :loaded-at-ms 1000
                           :settlement (vr-fees/perp-settlement) :settlement-verified? true
                           :source :user-fees}))

(defn- perp-account [facts]
  (fx/account-snapshot (snapshots/perp-balances (merge {:position "0" :withdrawable "1000" :leverage 5
                                                        :mid "80" :sz-decimals 2}
                                                       facts))))

(defn- perp-plan [{:keys [facts inventory]}]
  (planner/prepare-plan
   (fx/plan-input {:market (fx/market-snapshot {:market-type :perp :coin coin})
                   :account (perp-account facts)
                   :fees perp-fees
                   :draft (assoc fx/draft-13-2 :inventory (merge {:tilt-enabled? false} inventory))})))

(defn- codes [r] (mapv :code (:errors r)))

(deftest a-flat-perp-account-can-ladder-both-sides-test
  (let [r (perp-plan {})]
    (is (= :ready (:status r)) (pr-str (:errors r)))
    (is (contracts/plan-result-valid? r))
    (is (= 7 (count (:legs r))))
    (is (= {:token :quote :amount "1.0608"} (get-in r [:totals :sell :fee-reserve])) "sell fees settle in quote")
    (is (= "0" (:base-debit-bound (:fee-reserves r))))
    (is (= "40" (get-in r [:inventory-outcomes :all-buys-first])))
    (is (= "-30" (get-in r [:inventory-outcomes :all-sells-first])) "the sells open a short")
    (is (nil? (get-in r [:inventory-outcomes :core])) "no lower limit unless one is entered")
    (is (not-any? #(= :capability/fee-settlement-unverified (:code %)) (:warnings r)))))

(deftest perp-margin-admission-test
  (testing "each side is refused when its notional exceeds the margin capacity"
    (let [r (perp-plan {:facts {:withdrawable "100"}})]
      (is (= :invalid (:status r)))
      (is (= #{:buy :sell} (set (map #(get-in % [:data :side])
                                     (filter #(= :plan/insufficient-margin (:code %)) (:errors r))))))
      (is (not-any? #{:plan/insufficient-usdc :plan/insufficient-base} (codes r)))))
  (testing "sells that only close a long need no margin"
    (let [r (perp-plan {:facts {:withdrawable "0" :position "30"}})]
      (is (= [:buy] (mapv #(get-in % [:data :side])
                          (filter #(= :plan/insufficient-margin (:code %)) (:errors r)))))))
  (testing "sells above the mid are judged on their own notional, not the mid"
    ;; 30 units fit 2,400 of capacity at the 80 mid, but rest at 86-90.8 (2,652).
    (let [r (perp-plan {:facts {:withdrawable "500"}})]
      (is (some #(and (= :plan/insufficient-margin (:code %)) (= :sell (get-in % [:data :side]))) (:errors r))))))

(deftest perp-inventory-limits-are-signed-test
  (testing "a negative Keep at least bounds the short"
    (is (some #{:plan/protected-core-breach} (codes (perp-plan {:inventory {:protected-core "-20"}}))))
    (is (= :ready (:status (perp-plan {:inventory {:protected-core "-30"}})))))
  (testing "a short account may cap its position below zero"
    ;; -50 with 40 buys can reach -10.
    (is (= :ready (:status (perp-plan {:facts {:position "-50"} :inventory {:maximum "-5"}}))))
    (is (some #{:plan/max-inventory-breach}
              (codes (perp-plan {:facts {:position "-50"} :inventory {:maximum "-20"}})))))
  (testing "spot limits stay non-negative"
    (let [r (planner/prepare-plan
             (fx/plan-input {:draft (assoc-in fx/draft-13-2 [:inventory :protected-core] "-1")}))]
      (is (some #{:input/invalid-number} (codes r))))))

;; ---------------------------------------------------------------------------
;; Barrier read
;; ---------------------------------------------------------------------------

(defn- venue [calls overrides]
  (fn [body _opts]
    (swap! calls conj body)
    (js/Promise.resolve
     (let [k [(get body "type") (get body "dex")]]
       (if (contains? overrides k)
         (get overrides k)
         (case (get body "type")
           "spotClearinghouseState" {:balances [{:coin "USDC" :token 0 :total "5" :hold "0"}]}
           "frontendOpenOrders" (if (= "xyz" (get body "dex"))
                                  [{:coin coin :side "A" :limitPx "90" :sz "2" :oid 9}]
                                  [{:coin "BTC" :side "B" :limitPx "1" :sz "1" :oid 10}])
           "clearinghouseState" {:withdrawable "500"
                                 :assetPositions [{:position {:coin coin :szi "-2.5"}}]}
           "activeAssetData" {:coin coin :leverage {:type "isolated" :value 4} :markPx "100.5"}
           nil))))))

(def perp-scope
  {:network :hyperliquid-mainnet :account-address "0xABC" :market-type :perp :dex "xyz" :coin coin
   :spot-pair-index 3 :base-token-id "perp:xyz:NVDA" :quote-token-id nil :symbol "NVDA" :quote-symbol "USDC"
   :sz-decimals 2 :asset-id 110003})

(deftest barrier-read-covers-the-market-dex-test
  (async done
    (let [calls (atom [])]
      (-> (reads/read-account-snapshot! (venue calls {}) {:account-address "0xABC" :barrier-id "b"
                                                          :scopes [perp-scope] :now-ms! (constantly 7)})
          (.then (fn [r]
                   (is (:complete? r))
                   (is (= {coin {:position "-2.5" :withdrawable "500" :leverage 4 :leverage-type :isolated
                                 :mark-px "100.5"}}
                          (:perps r)))
                   (is (= #{"BTC" coin} (set (map :coin (:open-orders r))))
                       "the first dex's orders and the HIP-3 dex's orders")
                   (is (= #{{"type" "spotClearinghouseState" "user" "0xabc"}
                            {"type" "frontendOpenOrders" "user" "0xabc"}
                            {"type" "frontendOpenOrders" "user" "0xabc" "dex" "xyz"}
                            {"type" "clearinghouseState" "user" "0xabc" "dex" "xyz"}
                            {"type" "activeAssetData" "user" "0xabc" "coin" coin}}
                          (set @calls)))
                   (is (= "-2.5" (campaign/base-total r perp-scope)))
                   (done)))))))

(deftest barrier-read-without-perp-scopes-is-unchanged-test
  (async done
    (let [calls (atom [])]
      (-> (reads/read-account-snapshot! (venue calls {}) {:account-address "0xabc" :barrier-id "b"})
          (.then (fn [r]
                   (is (:complete? r))
                   (is (= {} (:perps r)))
                   (is (= 2 (count @calls)))
                   (done)))))))

(deftest barrier-read-is-incomplete-without-the-perp-facts-test
  (async done
    (let [calls (atom [])]
      (-> (reads/read-account-snapshot! (venue calls {["activeAssetData" nil] {:coin coin}})
                                        {:account-address "0xabc" :barrier-id "b" :scopes [perp-scope]})
          (.then (fn [r]
                   (is (false? (:complete? r)))
                   (is (= {} (:perps r)))
                   (is (nil? (campaign/base-total r perp-scope)) "an uncovered position is unknown, never zero")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(def markets (attribution/spot-markets rfx/spot-meta))

(defn- perp-campaign []
  (campaign/baseline-record
   {:campaign-id "cmp-perp" :scope perp-scope
    :read {:barrier-id "rb" :requested-at-ms rfx/t0 :received-at-ms (+ rfx/t0 180) :complete? true
           :balances [] :perps {coin {:position "-4"}}}
    :bbo rfx/bbo :started-by :first-placement :operation-id "op-vrt" :grid-id "grid-vrt"
    :now-ms (+ rfx/t0 200)}))

(defn- funding-row [at usdc]
  {:time at :hash (str "0xf" at)
   :delta {:type "funding" :coin coin :usdc usdc :szi "-4" :fundingRate "0.0000125"}})

(defn- funding-records [rows]
  (mapcat #(attribution/funding-records {:tracked #{"perp:xyz:NVDA"} :account-address "0xABC"
                                         :row % :now-ms 1})
          rows))

(defn- perp-findings [fills fundings]
  (attribution/campaign-findings
   {:campaign (perp-campaign) :ended-at-ms nil
    :fills (map rfx/fill fills) :updates [] :fundings fundings :markets markets
    :index (attribution/attribution-index [(rfx/grid {:scope* perp-scope})])}))

(deftest perp-campaign-identity-test
  (let [c (perp-campaign)]
    (is (= "hyperliquid-mainnet|0xabc|perp|xyz:NVDA|" (:campaign-key c)))
    (is (= "-4" (get-in c [:baseline :q0])) "the baseline is the signed position")
    (is (= [:perp "xyz"] ((juxt :market-type :dex) (:scope c))))
    (is (coverage/perp-campaign? c))
    (is (not (coverage/perp-campaign? (rfx/campaign-record))))))

(deftest perp-fills-are-tracked-and-untracked-fills-break-test
  (let [tracked (assoc rfx/buy-7-row :coin coin)
        manual (assoc rfx/manual-row :coin coin)
        other (assoc rfx/manual-row :coin "xyz:GOLD")
        f (perp-findings [tracked] [])]
    (is (= [] (:breaks f)))
    (is (= [{:kind :fill :net-base-delta "7" :net-cash-delta "-534.8" :fee-kind :quote}]
           (mapv #(select-keys % [:kind :net-base-delta :net-cash-delta :fee-kind]) (:events f))))
    (is (= [:break/untracked-same-base-trade] (mapv :reason (:breaks (perp-findings [manual] [])))))
    (is (= {:events [] :breaks [] :unreadable []} (perp-findings [other] []))
        "another perp never touches this position")
    (is (= {:events [] :breaks [] :unreadable []} (perp-findings [rfx/manual-row] []))
        "a spot trade never touches a perp position")))

(deftest funding-is-campaign-cash-test
  (let [before (funding-row (- rfx/t0 rfx/hour) "-9")
        paid (funding-row (+ rfx/t0 rfx/hour) "-1.25")
        received (funding-row (+ rfx/t0 (* 2 rfx/hour)) "0.5")
        records (funding-records [before paid received])
        f (perp-findings [(assoc rfx/buy-7-row :coin coin)] records)
        c (perp-campaign)]
    (is (= 3 (count records)))
    (is (= [] (funding-records [(assoc-in paid [:delta :coin] "BTC")])) "untracked coins are not stored")
    (is (attribution/unparsed-funding-row? {:time 1 :delta {:coin coin :usdc "n/a"}}))
    (is (= [:funding :funding :fill] (mapv :kind (:events f))) "payments before T0 are not the campaign's")
    (is (= {:q "3" :c "-535.55"} (ledger/state-before c (:events f) (+ rfx/t0 (* 3 rfx/hour))))
        "-4 + 7, and 534.8 spent plus 0.75 of net funding paid")
    (let [a (ledger/activity (:events f))]
      (is (= 1 (:fills a)))
      (is (= "-0.75" (:funding a)))
      (is (= 2 (:funding-payments a))))))

(deftest perp-coverage-needs-the-funding-stream-test
  (let [c (perp-campaign)
        from (- rfx/t0 rfx/skew)
        until (+ rfx/t0 rfx/hour)
        proven {:complete [[from until]] :failures []}
        cover (fn [records] (coverage/campaign-coverage {:campaign c :coverage records :breaks []
                                                         :balance-checks [] :until-ms until}))]
    (is (= :partial (:state (cover {:fills proven :ledger proven}))) "funding was never read")
    (is (= :complete (:state (cover {:fills proven :ledger proven
                                     :funding {:complete [[rfx/t0 until]] :failures []}}))))
    (is (= [{:stream :funding :base-token-id "perp:xyz:NVDA" :complete [[from (- until (:read-settle-ms contracts/report-policy))]] :failure nil}]
           (mapv #(update % :complete vec)
                 (coverage/funding-read->coverage
                  {:read {:complete? true :requested-at-ms until :received-at-ms until :rows []}
                   :start-ms from :base-token-ids #{"perp:xyz:NVDA"} :unparsed nil}))))))

(deftest funding-flows-through-the-window-figures-and-the-matched-total-test
  (let [c (perp-campaign)
        a rfx/t0
        b (+ rfx/t0 (* 3 rfx/hour))
        records (funding-records [(funding-row (+ rfx/t0 rfx/hour) "-1.25")
                                  (funding-row (+ rfx/t0 (* 2 rfx/hour)) "0.5")])
        events (:events (perp-findings [(assoc rfx/buy-7-row :coin coin)] records))
        sa (ledger/state-before c events a)
        sb (ledger/state-before c events b)
        figures (equations/window-figures {:q-a (:q sa) :c-a (:c sa) :p-a "80"
                                           :q-b (:q sb) :c-b (:c sb) :p-b "90"})
        m (matching/match-window {:campaign c :events events :a-ms a :b-ms b :p-end "90"})]
    (is (= {:q "-4" :c "0"} sa))
    ;; Hold: -4 x (90 - 80). Contribution: -534.8 - 0.75 + 7 x 90.
    (is (= {:net-pnl "54.45" :hold-pnl "-40" :contribution "94.45"} figures))
    (is (equations/identity-holds? figures))
    (is (= "-0.75" (:funding m)))
    (is (= 1 (:fills m)) "funding is never matched as a fill")
    (is (matching/reconciles-with? m (:contribution figures)))))

(deftest a-perp-update-keeps-its-confirmed-read-while-the-book-moves-test
  (let [at (fn [mid] (fx/account-snapshot (snapshots/perp-balances {:position "3" :withdrawable "1000" :leverage 5
                                                                    :mid mid :sz-decimals 2})))]
    (is (not= (select-keys (at "80") [:base :quote]) (select-keys (at "80.5") [:base :quote]))
        "the available figures are priced at the mid")
    (is (= (replace-state/store-balances (at "80")) (replace-state/store-balances (at "80.5")))
        "a book tick alone is not a balance change")
    (is (not= (replace-state/store-balances (at "80"))
              (replace-state/store-balances (fx/account-snapshot
                                             (snapshots/perp-balances {:position "4" :withdrawable "1000"
                                                                       :leverage 5 :mid "80" :sz-decimals 2}))))
        "a fill is")
    (is (= {:base {:total "150" :hold "0" :available "150"} :quote {:total "3000" :hold "0" :available "3000"}}
           (replace-state/store-balances (fx/account-snapshot)))
        "spot is unchanged")))

(deftest a-perp-scope-never-takes-a-spot-token-id-test
  ;; Spot pair 107 exists in the fixture metadata; a perp with the same index
  ;; is a different market.
  (let [state (assoc-in (perp-state nil) [:active-market :idx] 107)
        scope (snapshots/current-scope state)]
    (is (nil? (:quote-token-id scope)))
    (is (= "perp:xyz:NVDA" (:base-token-id scope)))))

(deftest the-venue-leverage-read-beats-the-ticket-setting-test
  (let [state (-> (perp-state nil)
                  (assoc-in [:order-form-ui :ui-leverage] 20)
                  (assoc-in [:vol-range :perp-asset] {:account-address sf/owner :coin coin :leverage 3
                                                      :type :isolated :loaded-at-ms 1}))]
    (is (= {:leverage "3" :type :isolated}
           (select-keys (:margin (snapshots/account-snapshot state sf/now-ms)) [:leverage :type])))
    (is (= "20" (get-in (snapshots/account-snapshot (assoc-in state [:vol-range :perp-asset :coin] "BTC") sf/now-ms)
                        [:margin :leverage]))
        "a read for another market is ignored")))

(deftest perp-fee-snapshot-from-state-test
  (let [fees {:userCrossRate "0.00045" :userAddRate "0.00015" :userSpotCrossRate "0.0007"
              :userSpotAddRate "0.0004" :activeReferralDiscount 0 :activeStakingDiscount {:discount 0}}
        base-perp (-> (perp-state nil)
                      (assoc :active-asset "BTC"
                             :active-market {:coin "BTC" :market-type :perp :base "BTC" :quote "USDC"
                                             :szDecimals 5 :idx 0 :asset-id 0})
                      (assoc-in [:portfolio :user-fees] fees))
        f (snapshots/fee-snapshot base-perp sf/now-ms)
        spot (snapshots/fee-snapshot (assoc-in (sf/base-state) [:portfolio :user-fees] fees) sf/now-ms)]
    (is (= :ok (:status f)) (pr-str f))
    (is (= "0.00015" (:maker-fraction f)) "the perp maker rate, not the spot one")
    (is (= [:quote :quote true] ((juxt :buy-fee-token :sell-fee-token :settlement-verified?) f)))
    (is (= "0.0004" (:maker-fraction spot)))
    (is (= [:base :quote false] ((juxt :buy-fee-token :sell-fee-token :settlement-verified?) spot)))
    (is (contracts/fee-capability-valid? f))
    (testing "a HIP-3 market still yields a quote-settled capability"
      (let [h (snapshots/fee-snapshot (assoc-in (perp-state nil) [:portfolio :user-fees] fees) sf/now-ms)]
        (is (= :ok (:status h)) (pr-str h))
        (is (= [:quote :quote] ((juxt :buy-fee-token :sell-fee-token) h)))))))

(deftest the-margin-refusal-names-the-dex-account-test
  (let [msgs (fn [facts scope]
               (->> (planner/prepare-plan
                     (fx/plan-input {:scope (merge {:network :hyperliquid-mainnet :account-address "0xabc"} scope)
                                     :market (fx/market-snapshot {:market-type :perp :coin coin})
                                     :account (perp-account facts) :fees perp-fees
                                     :draft (assoc fx/draft-13-2 :inventory {:tilt-enabled? false})}))
                    :errors
                    (filter #(= :plan/insufficient-margin (:code %)))
                    (map (juxt #(get-in % [:data :side]) :message))
                    (into {})))]
    (testing "nothing in the HIP-3 dex: funds in the main perps account do not back it"
      (is (= {:buy "No margin in your xyz perps account. Transfer USDC there to trade this market."
              :sell "No margin in your xyz perps account. Transfer USDC there to trade this market."}
             (msgs {:withdrawable "0"} {:dex "xyz"}))))
    (testing "some, but not enough: the margin needed at the venue leverage"
      (is (= {:buy "Not enough margin for the buys: about 577.83 USDC needed at 5x, 100.00 USDC available in your perps account."
              :sell "Not enough margin for the sells: about 530.61 USDC needed at 5x, 100.00 USDC available in your perps account."}
             (msgs {:withdrawable "100"} {:dex nil}))))))
