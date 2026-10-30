(ns hyperopen.vol-range.application.campaign-test
  "Tracking campaign records (HO-SPEC-014 v2.1 sections 7.4, 12.1): the key,
  the immutable baseline, closures and the open/by-time queries."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.campaign :as campaign]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.contracts :as contracts]))

(def scope
  {:network :hyperliquid-mainnet :owner-address "0xABC" :account-address "0xABC" :vault-address nil
   :market-type :spot :dex nil :spot-pair-index 107 :asset-id 10107 :coin "@107"
   :base-token-id "150" :quote-token-id "0" :symbol "HYPE" :quote-symbol "USDC" :sz-decimals 2
   :metadata-version nil})

(def read
  {:account-address "0xabc" :barrier-id "rb-1" :requested-at-ms 1761040000000
   :received-at-ms 1761040000180 :complete? true
   :balances [{:coin "USDC" :token 0 :total "3000" :hold "534.8"}
              {:coin "HYPE" :token 150 :total "150" :hold "30"}]
   :open-orders []})

(def bbo {:bid "79.99" :ask "80.01" :observed-at-ms 1761040000123})

(defn- baseline [& [overrides]]
  (campaign/baseline-record (merge {:campaign-id "cmp-1" :scope scope :read read :bbo bbo
                                    :operation-id "op-1" :grid-id "grid-1" :now-ms 1761040000200}
                                   overrides)))

(deftest campaign-key-format-test
  (is (= "hyperliquid-mainnet|0xabc|spot|150|107" (campaign/campaign-key scope))
      "network, lower-cased account, spot, base token id and pair index")
  (is (nil? (campaign/campaign-key (assoc scope :market-type :perp))))
  (is (nil? (campaign/campaign-key (assoc scope :account-address nil))))
  (is (nil? (campaign/campaign-key (assoc scope :spot-pair-index nil))))
  (is (= "hyperliquid-mainnet|0xabc|spot||107" (campaign/campaign-key (assoc scope :base-token-id nil)))))

(deftest baseline-record-captures-the-spec-12-1-fields-test
  (let [r (baseline)]
    (is (= {:q0 "150" :p0 "80" :p0-source :book-midpoint :p0-bid "79.99" :p0-ask "80.01"
            :p0-observed-at-ms 1761040000123 :c0 "0"
            :boundary {:barrier-id "rb-1" :requested-at-ms 1761040000000 :received-at-ms 1761040000180
                       :skew-ms 5000}}
           (:baseline r))
        "q0 is the total including held units; P0 is the trimmed book midpoint")
    (is (= 1761040000000 (:t0-ms r)) "T0 is the barrier read's request time")
    (is (= "campaign:cmp-1" (:record-id r)))
    (is (= :campaign (:kind r)))
    (is (= "hyperliquid-mainnet|0xabc|spot|150|107" (:campaign-key r)))
    (is (= :first-placement (:started-by r)))
    (is (= ["op-1" "grid-1"] [(:first-operation-id r) (:first-grid-id r)]))
    (is (nil? (:supersedes-campaign-id r)))
    (is (= {:campaign-schema-version contracts/campaign-schema-version
            :accounting-model-version contracts/accounting-model-version
            :model-versions {:model 1 :precision 1 :estimator "parkinson-v1"}}
           (select-keys r [:campaign-schema-version :accounting-model-version :model-versions])))
    (is (= {:network :hyperliquid-mainnet :account-address "0xabc" :coin "@107" :spot-pair-index 107
            :base-token-id "150" :quote-token-id "0" :symbol "HYPE" :quote-symbol "USDC" :sz-decimals 2}
           (:scope r)))
    (is (jr/record-valid? r) "versioned, keyed, timestamped and free of signing material")))

(deftest baseline-balance-lookup-test
  (testing "a read without the base token is a zero balance"
    (is (= "0" (get-in (baseline {:read (assoc read :balances [{:coin "USDC" :token 0 :total "3000"}])})
                       [:baseline :q0]))))
  (testing "rows without token ids match by symbol"
    (is (= "12.5" (get-in (baseline {:read (assoc read :balances [{:coin "HYPE" :total "12.50" :hold "0"}])})
                          [:baseline :q0])))))

(deftest baseline-midpoint-rounding-test
  (is (= "80.005" (get-in (baseline {:bbo {:bid "80" :ask "80.01" :observed-at-ms 1}}) [:baseline :p0])))
  (is (= "0.333333333334"
         (get-in (baseline {:bbo {:bid "0.333333333333" :ask "0.333333333334" :observed-at-ms 1}})
                 [:baseline :p0]))
      "12 places, half-up"))

(deftest baseline-is-total-on-a-missing-price-test
  (doseq [b [nil {} {:bid "79.99"} {:bid "x" :ask "80"} {:bid "81" :ask "80"} {:bid "0" :ask "80"}]]
    (let [r (baseline {:bbo b})]
      (is (nil? (get-in r [:baseline :p0])) (pr-str b))
      (is (= :unavailable (get-in r [:baseline :p0-source])) (pr-str b))
      (is (= "150" (get-in r [:baseline :q0])))
      (is (jr/record-valid? r)))))

(defn- closed [c successor]
  (campaign/closure-record {:campaign c :successor successor :now-ms 1761300000100}))

(deftest closure-record-test
  (let [old (baseline)
        new (baseline {:campaign-id "cmp-2" :read (assoc read :requested-at-ms 1761300000000)})
        cl (closed old new)]
    (is (= {:schema-version 1 :record-id "campaign-closed:cmp-1" :kind :campaign-closed
            :campaign-id "cmp-1" :campaign-key (:campaign-key old) :ended-at-ms 1761300000000
            :reason :user-restart :successor-campaign-id "cmp-2" :saved-at-ms 1761300000100}
           cl))
    (is (jr/record-valid? cl))))

(deftest open-campaign-and-time-queries-test
  (let [old (baseline)
        new (baseline {:campaign-id "cmp-2" :read (assoc read :requested-at-ms 1761300000000)})
        other (baseline {:campaign-id "cmp-3" :scope (assoc scope :spot-pair-index 108)})
        k (:campaign-key old)
        records [old new other (closed old new) {:kind :grid :record-id "grid:g"}]
        {:keys [campaigns closures]} (campaign/campaigns-from-records records)]
    (is (= "cmp-2" (:campaign-id (campaign/open-campaign records k))) "the closed campaign is not open")
    (is (= "cmp-1" (:campaign-id (campaign/open-campaign [old] k))))
    (is (nil? (campaign/open-campaign [] k)))
    (is (nil? (campaign/open-campaign [old new (closed old new) (closed new old)] k))
        "a closed campaign and no open one: none")
    (testing "[T0, ended-at)"
      (is (nil? (campaign/campaign-for-time campaigns closures k 1761039999999)))
      (is (= "cmp-1" (:campaign-id (campaign/campaign-for-time campaigns closures k 1761040000000))))
      (is (= "cmp-1" (:campaign-id (campaign/campaign-for-time campaigns closures k 1761299999999))))
      (is (= "cmp-2" (:campaign-id (campaign/campaign-for-time campaigns closures k 1761300000000))))
      (is (= "cmp-2" (:campaign-id (campaign/campaign-for-time campaigns closures k 1861300000000))))
      (is (nil? (campaign/campaign-for-time campaigns closures "other" 1761300000000))))))

(deftest campaigns-for-account-test
  (let [mine (baseline)
        theirs (baseline {:campaign-id "cmp-9" :scope (assoc scope :account-address "0xdef")})
        records [mine theirs (closed theirs mine)]]
    (is (= {:campaigns {"cmp-1" mine} :closures {}} (campaign/campaigns-for-account records "0xAbC")))
    (is (= #{"cmp-9"} (set (keys (:closures (campaign/campaigns-for-account records "0xdef"))))))
    (is (= {:campaigns {} :closures {}} (campaign/campaigns-for-account records nil)))))

(deftest baseline-records-for-placement-test
  (let [args {:records [] :scope scope :read read :bbo bbo :operation-id "op-1" :grid-id "grid-1"
              :campaign-id "cmp-1" :now-ms 1761040000200}]
    (testing "the first placement in a market starts a campaign"
      (is (true? (campaign/campaign-needed? [] scope)))
      (is (= [(baseline)] (campaign/baseline-records-for-placement args))))
    (testing "an open campaign is reused"
      (is (false? (campaign/campaign-needed? [(baseline)] scope)))
      (is (= [] (campaign/baseline-records-for-placement (assoc args :records [(baseline)])))))
    (testing "another market's campaign does not count"
      (let [other (baseline {:scope (assoc scope :spot-pair-index 108)})]
        (is (= 1 (count (campaign/baseline-records-for-placement (assoc args :records [other])))))))
    (testing "a closed campaign and no open one starts a new campaign"
      (let [old (baseline {:campaign-id "cmp-0"})
            new (baseline {:campaign-id "cmp-00"})
            records [old new (closed old new) (closed new old)]]
        (is (true? (campaign/campaign-needed? records scope)))
        (is (= ["cmp-1"] (mapv :campaign-id (campaign/baseline-records-for-placement
                                              (assoc args :records records)))))))
    (testing "no id or no market identity: nothing"
      (is (= [] (campaign/baseline-records-for-placement (assoc args :campaign-id nil))))
      (is (false? (campaign/campaign-needed? [] (assoc scope :market-type :perp))))
      (is (= [] (campaign/baseline-records-for-placement (assoc args :scope (assoc scope :market-type :perp))))))))

(deftest grid-campaign-test
  (let [c (baseline)
        k-scope scope
        grid {:grid-id "g" :scope k-scope :created-at-ms 1761040000500}
        campaigns {"cmp-1" c}]
    (is (= c (campaign/grid-campaign campaigns {} grid false)))
    (is (= c (campaign/grid-campaign campaigns {} grid true)))
    (is (nil? (campaign/grid-campaign campaigns {} (assoc grid :recovered? true) false)) "recovered: untracked")
    (is (nil? (campaign/grid-campaign campaigns {} (assoc grid :created-at-ms 1761030000000) false))
        "a ladder placed before tracking began")
    (is (nil? (campaign/grid-campaign {} {} grid false)))
    (testing "after a restart a working ladder belongs to the open campaign; a closed one keeps its own"
      (let [new (baseline {:campaign-id "cmp-2" :read (assoc read :requested-at-ms 1761300000000)})
            campaigns {"cmp-1" c "cmp-2" new}
            closures {"cmp-1" (closed c new)}]
        (is (= "cmp-2" (:campaign-id (campaign/grid-campaign campaigns closures grid false))))
        (is (= "cmp-1" (:campaign-id (campaign/grid-campaign campaigns closures grid true))))))))
