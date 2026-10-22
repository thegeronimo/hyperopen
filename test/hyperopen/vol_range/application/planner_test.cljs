(ns hyperopen.vol-range.application.planner-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.domain.trading.precision :as p]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.remediations :as remediations]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.fixtures :as fx]))

(defn- plan [overrides] (planner/prepare-plan (fx/plan-input overrides)))
(defn- codes [r] (set (map :code (:errors r))))
(defn- draft [& kvs] (reduce (fn [d [path v]] (assoc-in d path v)) fx/draft-13-2 (partition 2 kvs)))
(defn- by-id [r id] (first (filter #(= id (:id %)) (:remediations r))))
(defn- ids [r] (mapv :id (:remediations r)))

(deftest seven-order-fixture-test
  (let [r (plan {})]
    (is (= :ready (:status r)) (pr-str (:errors r)))
    (is (contracts/plan-result-valid? r))
    (is (= [["buy-0" "76.4" "7" "534.8"] ["buy-1" "74" "9" "666"] ["buy-2" "71.6" "11" "787.6"]
            ["buy-3" "69.2" "13" "899.6"] ["sell-0" "86" "10" "860"] ["sell-1" "88.4" "10" "884"]
            ["sell-2" "90.8" "10" "908"]]
           (mapv (juxt :leg-id :price :size :notional) (:legs r))))
    (let [{:keys [buy sell] :as totals} (:totals r)]
      (is (= "40" (:quantity buy)))
      (is (= "2888" (:gross buy)))
      (is (= "72.2" (:vwap buy)))
      (is (= {:token :quote :amount "1.1552"} (:fee-reserve buy)))
      (is (= "2889.1552" (:max-debit buy)))
      (is (= "2900" (:cap buy)))
      (is (= "10.8448" (:cap-headroom buy)))
      (is (= "30" (:quantity sell)))
      (is (= "2652" (:gross sell)))
      (is (= "88.4" (:vwap sell)))
      (is (= "30" (:base-committed sell)))
      (is (= "2650.9392" (:net-proceeds sell)))
      (is (= 7 (:leg-count totals)))
      (is (= "30" (:sellable-excess totals)))
      (is (= {:per-unit "16.2" :pct-of-buy-vwap "22.4377"} (:quoted-vwap-gap totals))))
    (is (= "190" (get-in r [:inventory-outcomes :all-buys-first])))
    (is (= "120" (get-in r [:inventory-outcomes :all-sells-first])))
    (is (= "160" (get-in r [:inventory-outcomes :all-fill])))
    (is (= [] (:remediations r)))
    (is (= "80" (get-in r [:model :shifted-anchor])))
    (is (= "0.06" (get-in r [:model :volatility :daily-fraction])))))

(deftest fingerprint-test
  (let [a (plan {}) b (plan {:plan-id "other"})
        c (plan {:draft (draft [:buy :quantity] "41")})]
    (is (= (:input-fingerprint a) (:input-fingerprint b)))
    (is (not= (:input-fingerprint a) (:input-fingerprint c)))
    (is (re-matches #"vr1-.+" (:input-fingerprint a)))))

(deftest disabled-side-test
  (let [r (plan {:draft (-> fx/draft-13-2 (assoc-in [:sell :enabled?] false) (assoc-in [:sell :quantity] "garbage"))})]
    (is (= :ready (:status r)))
    (is (= 4 (count (:legs r))))
    (is (nil? (get-in r [:totals :quoted-vwap-gap])))
    (is (contracts/plan-result-valid? r))))

(deftest crossing-test
  (let [r (plan {:market (fx/market-snapshot {:bbo {:bid "75.89" :ask "75.91" :observed-at-ms 1}})})]
    (is (= :invalid (:status r)))
    (is (= "Buy at 76.4 is above the best ask 75.91"
           (:message (first (filter #(= :plan/post-only-cross (:code %)) (:errors r))))))
    (is (= 7 (count (:legs r))) "diagnostic legs stay")
    (is (= [:anchor-to-market :start-buys-at] (ids r)))
    (is (= "Anchor to market price" (:label (by-id r :anchor-to-market))))
    (is (= [[[:buy :start-sigma] "0.90"]] (:patch (by-id r :start-buys-at))))
    (is (= "Start buys at 0.90σ" (:label (by-id r :start-buys-at))))
    (is (contracts/plan-result-valid? r))))

(deftest one-click-test
  (testing "maximum and cap both blank are offered jointly"
    (let [r (plan {:draft (draft [:inventory :maximum] "" [:buy :max-usdc-debit] "")})]
      (is (= :invalid (:status r)))
      (is (= [[[:inventory :maximum] "190"]] (:patch (by-id r :set-maximum-all-buys))))
      (is (= "Set maximum to 190 · all buys filled" (:label (by-id r :set-maximum-all-buys))))
      (is (= [[[:buy :max-usdc-debit] "2889.16"]] (:patch (by-id r :use-plan-total))))))
  (testing "cap below the plan total"
    (let [r (plan {:draft (draft [:buy :max-usdc-debit] "2800")})]
      (is (contains? (codes r) :plan/usdc-cap-exceeded))
      (is (= ["use-plan-total"] (map (comp name :id) (:remediations r))))
      (is (= "2889.16" (second (first (:patch (by-id r :use-plan-total))))))))
  (testing "maximum below N alone"
    (let [r (plan {:draft (draft [:inventory :maximum] "180")})]
      (is (contains? (codes r) :plan/max-inventory-breach))
      (is (= [[[:inventory :maximum] "190"]] (:patch (by-id r :set-maximum-all-buys)))))))

(deftest maximum-breach-test
  (let [r (plan {:account (fx/account-snapshot {:base {:total "190" :hold "0" :available "190"}})
                 :draft (draft [:inventory :maximum] "200" [:inventory :protected-core] "120"
                               [:inventory :target] "160" [:buy :quantity] "50" [:buy :max-usdc-debit] "9999")})]
    (is (contains? (codes r) :plan/max-inventory-breach))))

(deftest tilt-test
  (let [r (plan {:draft (draft [:inventory :tilt-enabled?] true)})]
    (is (= "80.54" (get-in r [:model :shifted-anchor])))
    (is (= "-0.25" (get-in r [:model :tilt :delta])))
    (is (= "0.1125" (get-in r [:model :tilt :shift-sigma])))
    (is (= "80" (get-in r [:model :anchor :price])))
    (let [buys (map #(p/parse-decimal (:price %)) (filter #(= :buy (:side %)) (:legs r)))
          sells (map #(p/parse-decimal (:price %)) (filter #(= :sell (:side %)) (:legs r)))]
      (is (every? (fn [b] (every? (fn [s] (neg? (p/cmp b s))) sells)) buys)))))

(deftest volatility-test
  (let [unavailable {:auto-volatility {:status :unavailable :reason :missing-day}}
        auto (draft [:volatility :mode] :auto)
        r (plan {:draft auto :market (fx/market-snapshot unavailable)})]
    (is (= :unavailable (:status r)))
    (is (= "14 completed daily candles required"
           (:message (first (filter #(= :data/volatility-unavailable (:code %)) (:errors r))))))
    (is (= :ready (:status (plan {:draft fx/draft-13-2 :market (fx/market-snapshot unavailable)}))))
    (is (contracts/plan-result-valid? r)))
  (let [r (plan {:draft (draft [:volatility :mode] :auto)
                 :market (fx/market-snapshot {:auto-volatility {:status :ok :source :auto :zero? true
                                                                 :daily-fraction "0.000000000000"}})})]
    (is (contains? (codes r) :data/volatility-zero)))
  (let [r (plan {:draft (draft [:volatility :mode] :auto)})]
    (is (= :ready (:status r)))
    (is (= :auto (get-in r [:model :volatility :source])))))

(deftest anchor-sources-test
  (is (= "79" (get-in (plan {:draft (draft [:anchor :mode] :utc-open)}) [:model :anchor :price])))
  (is (= "80" (get-in (plan {:draft (draft [:anchor :mode] :market-price)}) [:model :anchor :price])))
  (let [r (plan {:draft (draft [:anchor :mode] :utc-open)
                 :market (fx/market-snapshot {:anchors {:vwap-24h nil :utc-open nil :market-price nil}})})]
    (is (= :unavailable (:status r)))
    (is (= :utc-open (get-in (first (filter #(= :data/anchor-unavailable (:code %)) (:errors r))) [:data :mode]))))
  (is (= "75.9" (get-in (plan {:draft (draft [:anchor :mode] :market-price)
                               :market (fx/market-snapshot {:bbo {:bid "75.89" :ask "75.91" :observed-at-ms 1}})})
                        [:model :anchor :price]))))

(deftest capability-test
  (is (= :unavailable (:status (plan {:fees {:status :unavailable :maker-fraction nil :buy-fee-token nil
                                             :sell-fee-token nil :settlement-verified? nil :source nil
                                             :loaded-at-ms nil}}))))
  (is (contains? (codes (plan {:fees nil})) :data/fee-quote-unavailable))
  (is (contains? (codes (plan {:market (fx/market-snapshot {:market-type :perp})})) :capability/market-unsupported))
  (is (contains? (codes (plan {:account (fx/account-snapshot {:account-mode :unified})}))
                 :capability/account-mode-unsupported))
  (is (contains? (codes (plan {:capabilities {:preview? false}})) :capability/preview-disabled))
  (let [r (plan {:account (fx/account-snapshot {:account-mode :weird})})]
    (is (= :ready (:status r)))
    (is (contains? (set (map :code (:warnings r))) :capability/raw-account-mode-unverified)))
  (let [r (plan {:fees (assoc fx/synthetic-fees :settlement-verified? false)})]
    (is (= :ready (:status r)))
    (is (contains? (set (map :code (:warnings r))) :capability/fee-settlement-unverified)))
  (let [r (plan {:account {:status :unavailable :errors [] :account-address nil :account-mode nil
                           :read-only? false :base nil :quote nil :open-orders [] :observed-at-ms nil}})]
    (is (= :unavailable (:status r)))
    (is (every? (codes r) [:capability/account-required :data/balances-unavailable]))
    (is (= 7 (count (:legs r))) "diagnostic geometry still computed")
    (is (contracts/plan-result-valid? r))))

(deftest nothing-to-sell-test
  (let [r (plan {:draft (draft [:inventory :protected-core] "150" [:inventory :target] "160")})
        w (first (filter #(= :hint/nothing-to-sell (:code %)) (:warnings r)))]
    (is (some? w))
    (is (= "Nothing to sell above protected core. Lower protected core to distribute inventory." (:message w)))
    (is (= "0" (get-in r [:totals :sellable-excess])))))

(deftest blank-core-target-resolve-to-inventory-test
  (let [r (plan {:draft (draft [:inventory :protected-core] "" [:inventory :target] "" [:sell :enabled?] false
                               [:inventory :maximum] "190")})]
    (is (= :ready (:status r)) (pr-str (:errors r)))
    (is (= "150" (get-in r [:inventory-outcomes :core])))
    (is (= "150" (get-in r [:inventory-outcomes :target])))))

(deftest corrective-plan-test
  (let [acct (fx/account-snapshot {:base {:total "210" :hold "0" :available "210"}})
        sell-only (draft [:buy :enabled?] false [:inventory :maximum] "200")
        r (plan {:account acct :draft sell-only})]
    (is (= :ready (:status r)) (pr-str (:errors r)))
    (is (= :above-maximum (get-in r [:inventory-outcomes :starting-breach])))
    (is (contains? (codes (plan {:account acct :draft (draft [:inventory :maximum] "200")}))
                   :plan/corrective-direction))))

(deftest duplicate-price-test
  (let [d (-> fx/draft-13-2
              (assoc-in [:sell :enabled?] false)
              (assoc-in [:anchor :custom-price] "1.2345")
              (assoc-in [:volatility :manual-daily-pct] "0.10")
              (assoc-in [:buy :count] 12) (assoc-in [:buy :start-sigma] "0.10")
              (assoc-in [:buy :end-sigma] "0.15") (assoc-in [:buy :skew] "1")
              (assoc-in [:buy :quantity] "120") (assoc-in [:buy :max-usdc-debit] "200")
              (assoc-in [:inventory :maximum] "400"))
        r (plan {:draft d :market (fx/market-snapshot {:sz-decimals 0 :bbo {:bid "1.2" :ask "1.3" :observed-at-ms 1}})
                 :account (fx/account-snapshot {:base {:total "150" :hold "0" :available "150"}})})]
    (is (contains? (codes r) :plan/duplicate-price) (pr-str (:errors r)))
    (is (some? (by-id r :reduce-rung-count)))))

(defn- fixtures-for-property []
  [{}
   {:market (fx/market-snapshot {:bbo {:bid "75.89" :ask "75.91" :observed-at-ms 1}})}
   {:market (fx/market-snapshot {:bbo {:bid "86.50" :ask "86.52" :observed-at-ms 1}})}
   {:draft (draft [:inventory :maximum] "" [:buy :max-usdc-debit] "")}
   {:draft (draft [:buy :max-usdc-debit] "2800")}
   {:draft (draft [:inventory :maximum] "180")}
   {:draft (draft [:buy :quantity] "1" [:buy :max-usdc-debit] "3000")}
   {:draft (draft [:sell :quantity] "0.12" [:sell :count] 12)}
   {:draft (draft [:buy :end-sigma] "0.75" [:buy :start-sigma] "0.75")}])

(def ^:private one-click-ids #{:set-maximum-all-buys :use-plan-total})

(deftest every-offer-replans-ready-test
  (doseq [overrides (fixtures-for-property)
          :let [input (fx/plan-input overrides)
                r (planner/prepare-plan input)
                joint? (and (str/blank? (get-in input [:draft :inventory :maximum]))
                            (str/blank? (get-in input [:draft :buy :max-usdc-debit])))
                joint-patch (into [] (comp (filter #(one-click-ids (:id %))) (mapcat :patch)) (:remediations r))]
          o (:remediations r)]
    (is (contracts/remediation-valid? o))
    (let [patch (if (and joint? (one-click-ids (:id o))) joint-patch (:patch o))
          again (planner/prepare-plan (assoc input :draft (remediations/apply-draft-patch (:draft input) patch)))]
      (is (= :ready (:status again)) (str (:id o) " " (pr-str (:errors again)))))))
