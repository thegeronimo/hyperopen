(ns hyperopen.hyperevm.domain.balances-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.hyperevm.domain.balances :as balances]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.infrastructure.rpc :as rpc]
            [hyperopen.hyperevm.test-support.fixtures :as fixtures]))

(def ^:private owner "0x1111111111111111111111111111111111111111")
(def ^:private spectated "0x2222222222222222222222222222222222222223")

(defn- token [token-name] (tokens/token-by-name fixtures/mainnet-spot-meta token-name))

(def ^:private base-state
  {:router {:path "/trade"}
   :wallet {:address "0x1111111111111111111111111111111111111111"}
   :spot {:meta fixtures/mainnet-spot-meta}
   :funding-ui {:modal {:open? false}}
   :hyperevm (balances/default-state)})

(def ^:private queried #{0 1 122 197 478})

(defn- loaded
  "`base-state` after one successful read of `result` for the owner."
  ([result] (loaded base-state result))
  ([state result]
   (-> state
       (balances/apply-loading {:addresses [owner] :requested-at-ms 100})
       (balances/apply-success owner 100 result queried 200))))

(deftest default-state-test
  (is (= {:balances {:by-address {}}
          :bridge {:evm-system-units {} :evm-system-read-at-ms {} :core->evm-sent-at-ms {}
                   :core-system-balances {} :token-health {} :health-requested-at-ms nil}
          :in-flight {}
          :fast-poll-until-ms nil
          :wallet-capabilities {}
          :core-account {}
          :backoff {:strikes 0 :until-ms nil}}
         (balances/default-state))))

(deftest balance-addresses-test
  (is (= [owner] (balances/balance-addresses base-state)))
  (testing "spectating shows the spectated account first and keeps the owner"
    (is (= [spectated owner]
           (balances/balance-addresses
            (assoc base-state :account-context {:spectate-mode {:active? true
                                                                :address spectated}})))))
  (testing "addresses are lowercased"
    (is (= [owner] (balances/balance-addresses
                    (assoc-in base-state [:wallet :address] (.toUpperCase owner))))))
  (is (= [] (balances/balance-addresses (dissoc base-state :wallet)))))

(deftest token-amount-text-reads-core-precision-strings-test
  (let [state (loaded {:native-wei "12500000000000000000"
                       :token-units {1 "98000000000000000000"
                                     197 "123456789"}
                       :unread-token-indexes [478]
                       :gas-price-wei "100000000"})]
    (is (= "12.5" (balances/native-hype-text state owner)))
    (is (= "12.5" (balances/token-amount-text state owner (token "HYPE"))))
    (is (= "98" (balances/token-amount-text state owner (token "PURR"))))
    (is (= "1.23456789" (balances/token-amount-text state owner (token "UBTC"))))
    (is (= "0" (balances/token-amount-text state owner (token "USDC")))
        "a read token with no balance is zero")
    (is (nil? (balances/token-amount-text state owner (token "FUNT")))
        "an unread token is unknown, not zero")
    (is (nil? (balances/token-amount-text state spectated (token "PURR")))
        "an address never read is unknown")
    (is (nil? (balances/token-amount-text base-state owner (token "PURR"))))))

(deftest token-amount-text-floors-to-core-precision-test
  ;; HYPE: 18 EVM decimals, 8 on Core.
  (let [state (loaded {:native-wei "123456789012345678" :token-units {} :gas-price-wei "1"})]
    (is (= "0.12345678" (balances/native-hype-text state owner)))))

(deftest token-amount-text-is-unknown-for-tokens-outside-the-read-test
  (let [state (-> base-state
                  (balances/apply-loading {:addresses [owner] :requested-at-ms 100})
                  (balances/apply-success owner 100 {:native-wei "0" :token-units {} :gas-price-wei "1"}
                                          #{1} 200))]
    (is (= "0" (balances/token-amount-text state owner (token "PURR"))))
    (is (nil? (balances/token-amount-text state owner (token "UBTC")))
        "spotMeta changed since the read, so UBTC was never asked for")))

(deftest evm-gas-status-test
  (let [with-native (fn [wei]
                      (loaded {:native-wei wei :token-units {} :gas-price-wei "100000000"}))]
    (is (nil? (balances/evm-gas-status base-state owner)) "unknown before any read")
    (is (= :none (balances/evm-gas-status (with-native "0") owner)))
    ;; one native transfer: 30000 gas x 2 x 0.2 gwei floor = 0.000012 HYPE
    (is (= :low (balances/evm-gas-status (with-native "11999999999999") owner)))
    (is (= :ok (balances/evm-gas-status (with-native "12000000000000") owner)))
    (is (= :low (balances/evm-gas-status (with-native "12000000000000") owner
                                         [:usdc-approve :usdc-deposit]))
        "USDC's two transactions cost more")))

(deftest loading-and-errors-keep-last-good-values-test
  (let [state (loaded {:native-wei "5" :token-units {1 "7"} :gas-price-wei "9"})
        reloading (balances/apply-loading state {:addresses [owner] :requested-at-ms 300})
        failed (balances/apply-error reloading owner 300 {:message "rate limited" :kind :rate-limited})]
    (is (= :loading (:status (balances/entry reloading owner))))
    (is (= "5" (:native-wei (balances/entry reloading owner))))
    (is (= {:status :error :stale? true :native-wei "5" :token-units {1 "7"}
            :error "rate limited" :error-kind :rate-limited}
           (select-keys (balances/entry failed owner)
                        [:status :stale? :native-wei :token-units :error :error-kind])))
    (testing "an error before any read is not stale, and stays unknown"
      (let [first-error (-> base-state
                            (balances/apply-loading {:addresses [owner] :requested-at-ms 1})
                            (balances/apply-error owner 1 {:message "down" :kind :network}))]
        (is (false? (:stale? (balances/entry first-error owner))))
        (is (nil? (balances/native-hype-text first-error owner)))))))

(deftest a-failed-balance-call-is-unknown-not-zero-test
  ;; The owner's own balanceOf failed inside a chunk that was otherwise read
  ;; (a transient revert, or gas starvation from another token's call).
  (let [state (loaded {:native-wei "1" :token-units {1 "7000000000000000000"} :gas-price-wei "1"})
        state* (-> state
                   (balances/apply-loading {:addresses [owner] :requested-at-ms 300})
                   (balances/apply-success owner 300
                                           {:native-wei "1" :token-units {}
                                            :failed-token-indexes [1 197]
                                            :gas-price-wei "1"}
                                           queried 400))]
    (is (= "7" (balances/token-amount-text state* owner (token "PURR")))
        "PURR keeps its previous value")
    (is (nil? (balances/token-amount-text state* owner (token "UBTC")))
        "UBTC had none, so it is unknown rather than 0")
    (is (= [1 197] (:unread-token-indexes (balances/entry state* owner))))))

(deftest apply-success-keeps-unread-tokens-previous-values-test
  (let [state (loaded {:native-wei "1" :token-units {1 "7" 197 "3"} :gas-price-wei "1"})
        state* (-> state
                   (balances/apply-loading {:addresses [owner] :requested-at-ms 300})
                   (balances/apply-success owner 300
                                           {:native-wei "2" :token-units {}
                                            :unread-token-indexes [197] :gas-price-wei "1"}
                                           queried 400))]
    (is (= {197 "3"} (:token-units (balances/entry state* owner)))
        "PURR's read came back empty (zero); UBTC's chunk failed and keeps 3")
    (is (= [197] (:unread-token-indexes (balances/entry state* owner))))
    (is (= "0" (balances/token-amount-text state* owner (token "PURR"))))))

(deftest stale-and-foreign-replies-are-dropped-test
  (let [state (-> base-state
                  (balances/apply-loading {:addresses [owner] :requested-at-ms 100})
                  (balances/apply-loading {:addresses [owner] :requested-at-ms 150}))
        result {:native-wei "1" :token-units {} :gas-price-wei "1"}]
    (is (= state (balances/apply-success state owner 100 result queried 200))
        "a reply overtaken by a newer request is dropped")
    (is (= state (balances/apply-error state owner 100 {:message "x"}))
        "so is an overtaken error")
    (is (= state (balances/apply-success state spectated 150 result queried 200))
        "a reply for an address no longer shown is dropped")
    (is (= :ready (:status (balances/entry (balances/apply-success state owner 150 result queried 200)
                                           owner))))))

(deftest apply-loading-prunes-addresses-no-longer-shown-test
  (let [state (assoc-in base-state [:hyperevm :balances :by-address spectated] {:native-wei "1"})
        state* (balances/apply-loading state {:addresses [owner] :requested-at-ms 5})]
    (is (= [owner] (keys (get-in state* [:hyperevm :balances :by-address]))))))

(deftest rate-limit-backoff-doubles-and-resets-on-success-test
  (let [s1 (balances/apply-rate-limit base-state 1000)
        s2 (balances/apply-rate-limit s1 2000)
        s3 (balances/apply-rate-limit s2 3000)
        s4 (balances/apply-rate-limit s3 4000)]
    (is (= {:strikes 1 :until-ms 31000} (get-in s1 [:hyperevm :backoff])))
    (is (= {:strikes 2 :until-ms 62000} (get-in s2 [:hyperevm :backoff])))
    (is (= {:strikes 3 :until-ms 123000} (get-in s3 [:hyperevm :backoff])))
    (is (= {:strikes 4 :until-ms 124000} (get-in s4 [:hyperevm :backoff])) "capped at 120 s")
    (is (= {:strikes 3 :until-ms 123000}
           (get-in (loaded s3 {:native-wei "1" :token-units {} :gas-price-wei "1"})
                   [:hyperevm :backoff]))
        "one address's success alone does not settle a poll's backoff")))

(deftest apply-poll-backoff-counts-one-strike-per-poll-test
  (let [s1 (balances/apply-poll-backoff base-state 1000 [:rate-limited :rate-limited])
        s2 (balances/apply-poll-backoff s1 2000 [:ok :rate-limited])]
    (is (= {:strikes 1 :until-ms 31000} (get-in s1 [:hyperevm :backoff]))
        "two addresses rate-limited in one poll are one strike: 30 s")
    (is (= {:strikes 2 :until-ms 62000} (get-in s2 [:hyperevm :backoff]))
        "a success beside a rate limit does not reset: the limit is per IP")
    (is (= {:strikes 0 :until-ms nil}
           (get-in (balances/apply-poll-backoff s2 3000 [:ok :error]) [:hyperevm :backoff]))
        "a poll with no rate limit and a success resets")
    (is (= s2 (balances/apply-poll-backoff s2 3000 [:error]))
        "a poll that only failed otherwise changes nothing")))

(deftest surface-active-test
  (is (true? (balances/surface-active? base-state)))
  (is (true? (balances/surface-active? (assoc-in base-state [:router :path] "/portfolio"))))
  (is (true? (balances/surface-active?
              (assoc-in base-state [:router :path] "/portfolio/trader/0x1111111111111111111111111111111111111111"))))
  (is (false? (balances/surface-active? (assoc-in base-state [:router :path] "/portfolio/optimize"))))
  (is (false? (balances/surface-active? (assoc-in base-state [:router :path] "/vaults"))))
  (is (true? (balances/surface-active? (-> base-state
                                           (assoc-in [:router :path] "/vaults")
                                           (assoc-in [:funding-ui :modal :open?] true))))
      "the funding modal counts wherever it opens"))

(deftest watch-fingerprint-test
  (is (= [[owner] [false] 6 true] (balances/watch-fingerprint base-state)))
  (is (= [[owner] [true] 6 true]
         (balances/watch-fingerprint
          (balances/apply-loading base-state {:addresses [owner] :requested-at-ms 1})))
      "an account reset that clears the entries changes the fingerprint back")
  (is (= [[] [] 0 false] (balances/watch-fingerprint {:router {:path "/vaults"}}))))

(deftest refresh-plan-test
  (testing "a due address is planned"
    (is (= {:addresses [owner] :requested-at-ms 1000}
           (balances/refresh-plan base-state 1000 {}))))
  (testing "nothing without spotMeta, an address, or during backoff"
    (is (nil? (balances/refresh-plan (assoc-in base-state [:spot :meta] nil) 1000 {:force? true})))
    (is (nil? (balances/refresh-plan (dissoc base-state :wallet) 1000 {:force? true})))
    (is (nil? (balances/refresh-plan (balances/apply-rate-limit base-state 1000) 2000 {:force? true}))))
  (testing "an inactive surface pauses unforced refreshes, unless a fast poll runs"
    (let [inactive (assoc-in base-state [:router :path] "/vaults")]
      (is (nil? (balances/refresh-plan inactive 1000 {})))
      (is (some? (balances/refresh-plan inactive 1000 {:force? true})))
      (is (some? (balances/refresh-plan (assoc-in inactive [:hyperevm :fast-poll-until-ms] 5000)
                                        1000 {})))))
  (testing "an address requested within 10 s is skipped unless forced or fast-polling"
    (let [recent (loaded {:native-wei "1" :token-units {} :gas-price-wei "1"})]
      (is (nil? (balances/refresh-plan recent 5000 {})))
      (is (some? (balances/refresh-plan recent 11000 {})))
      (is (some? (balances/refresh-plan recent 5000 {:force? true})))
      (is (some? (balances/refresh-plan (assoc-in recent [:hyperevm :fast-poll-until-ms] 9000)
                                        5000 {}))))))

(deftest refresh-plan-never-overtakes-a-read-still-loading-test
  (let [loading (-> base-state
                    (balances/apply-loading {:addresses [owner] :requested-at-ms 1000})
                    (assoc-in [:hyperevm :fast-poll-until-ms] 60000))]
    (is (nil? (balances/refresh-plan loading 5000 {}))
        "a fast-poll tick 4 s later leaves the slow read alone")
    (is (= {:addresses [owner] :requested-at-ms 11000}
           (balances/refresh-plan loading 11000 {}))
        "after the RPC deadline the read has settled or timed out")
    (is (some? (balances/refresh-plan loading 5000 {:force? true}))
        "an explicit post-transfer refresh supersedes it")
    (is (= rpc/default-timeout-ms balances/read-timeout-ms))))

(deftest receipt-wait-pause-is-bounded-and-spares-unread-addresses-test
  (let [waiting (fn [state entry] (assoc-in state [:hyperevm :in-flight owner] entry))
        read-state (loaded {:native-wei "1" :token-units {} :gas-price-wei "1"})
        wait {:waiting-receipt? true :submitted-at-ms 50000}]
    (testing "addresses already read pause during a recent wait"
      (is (nil? (balances/refresh-plan (waiting read-state wait) 60000 {})))
      (is (some? (balances/refresh-plan (waiting read-state wait) 60000 {:force? true}))
          "an explicit post-transfer refresh bypasses the pause"))
    (testing "fast-poll ticks pause too"
      (is (nil? (balances/refresh-plan (-> (waiting read-state wait)
                                           (assoc-in [:hyperevm :fast-poll-until-ms] 90000))
                                       60000 {}))))
    (testing "an address never read is always planned"
      (is (= {:addresses [owner] :requested-at-ms 60000}
             (balances/refresh-plan (waiting base-state wait) 60000 {})))
      (let [switched (-> (waiting read-state wait)
                         (assoc :account-context {:spectate-mode {:active? true
                                                                  :address spectated}}))]
        (is (= {:addresses [spectated] :requested-at-ms 60000}
               (balances/refresh-plan switched 60000 {}))
            "an account switch mid-wait still reads the new account")))
    (testing "the pause ends 180 s after submission, and needs a submission time"
      (is (some? (balances/refresh-plan (waiting read-state wait) 230000 {})))
      (is (some? (balances/refresh-plan (waiting read-state {:waiting-receipt? true}) 60000 {})))
      (is (= rpc/default-receipt-timeout-ms balances/receipt-wait-pause-ms)))))

(deftest fast-polling-and-waiting-receipt-test
  (is (false? (balances/fast-polling? base-state 1)))
  (is (true? (balances/fast-polling? (assoc-in base-state [:hyperevm :fast-poll-until-ms] 10) 5)))
  (is (false? (balances/fast-polling? (assoc-in base-state [:hyperevm :fast-poll-until-ms] 10) 10)))
  (is (false? (balances/waiting-receipt? base-state 1)))
  (let [waiting (assoc-in base-state [:hyperevm :in-flight owner]
                          {:waiting-receipt? true :submitted-at-ms 1000})]
    (is (true? (balances/waiting-receipt? waiting 1000)))
    (is (true? (balances/waiting-receipt? waiting 180999)))
    (is (false? (balances/waiting-receipt? waiting 181000)) "bounded by the receipt timeout"))
  (is (false? (balances/waiting-receipt?
               (assoc-in base-state [:hyperevm :in-flight owner] {:waiting-receipt? true})
               1000))
      "an entry with no submission time pauses nothing"))

(deftest apply-core-account-role-test
  (is (= :missing (get-in (balances/apply-core-account-role base-state owner {:role "missing"})
                          [:hyperevm :core-account owner])))
  (is (= :active (get-in (balances/apply-core-account-role base-state (.toUpperCase owner) {:role "user"})
                         [:hyperevm :core-account owner])))
  (is (= base-state (balances/apply-core-account-role base-state owner nil))
      "an unreadable response leaves the status unknown"))

(deftest a-retry-of-a-failed-first-read-still-reads-failed-test
  (let [failed (-> base-state
                   (balances/apply-loading {:addresses [owner] :requested-at-ms 1})
                   (balances/apply-error owner 1 {:message "down" :kind :network}))
        retrying (balances/apply-loading failed {:addresses [owner] :requested-at-ms 2})
        landed (balances/apply-success retrying owner 2
                                       {:native-wei "1" :token-units {} :gas-price-wei "1"}
                                       queried 3)]
    (is (false? (balances/first-read-failed? nil)))
    (is (false? (balances/first-read-failed? {:status :loading})) "a first read in flight")
    (is (true? (balances/first-read-failed? (balances/entry failed owner))))
    (is (= :loading (:status (balances/entry retrying owner))))
    (is (true? (balances/first-read-failed? (balances/entry retrying owner)))
        "the retry keeps the failure's :error, so readers keep saying unavailable")
    (is (false? (balances/first-read-failed? (balances/entry landed owner))))
    (testing "a failed refresh of a read entry is not a failed first read"
      (let [refresh-failed (-> landed
                               (balances/apply-loading {:addresses [owner] :requested-at-ms 4})
                               (balances/apply-error owner 4 {:message "down" :kind :network}))]
        (is (false? (balances/first-read-failed? (balances/entry refresh-failed owner))))))))

(deftest a-chunk-never-answered-leaves-the-read-partial-test
  (let [read (fn [state requested-at-ms result]
               (-> state
                   (balances/apply-loading {:addresses [owner] :requested-at-ms requested-at-ms})
                   (balances/apply-success owner requested-at-ms
                                           (merge {:native-wei "1" :token-units {} :gas-price-wei "1"}
                                                  result)
                                           queried (inc requested-at-ms))))
        first-chunk-lost (read base-state 100 {:unread-token-indexes [197 478]})
        answered (read first-chunk-lost 200 {:token-units {197 "3"}})
        lost-again (read answered 300 {:unread-token-indexes [197 478]})
        joff-only (read base-state 100 {:failed-token-indexes [296]})]
    (is (false? (balances/partial-read? base-state owner)) "nothing read is not partial, it is unknown")
    (is (true? (balances/partial-read? first-chunk-lost owner)))
    (is (= #{197 478} (:never-read-token-indexes (balances/entry first-chunk-lost owner))))
    (is (= [197 478] (:unread-token-indexes (balances/entry first-chunk-lost owner)))
        "the union reader keeps listing them as unknown")
    (is (false? (balances/partial-read? answered owner)) "the next read answered them")
    (is (false? (balances/partial-read? lost-again owner))
        "a chunk lost after it was once answered keeps its last values: not partial")
    (is (= (balances/token-amount-text answered owner (token "UBTC"))
           (balances/token-amount-text lost-again owner (token "UBTC"))))
    (is (some? (balances/token-amount-text lost-again owner (token "UBTC"))))
    (is (false? (balances/partial-read? joff-only owner))
        "a call that fails on every read (JOFF) never makes the read partial")
    (is (= [296] (:unread-token-indexes (balances/entry joff-only owner))))
    (testing "a token first asked about in a lost chunk is never-read"
      (let [newly (-> answered
                      (balances/apply-loading {:addresses [owner] :requested-at-ms 400})
                      (balances/apply-success owner 400
                                              {:native-wei "1" :token-units {} :gas-price-wei "1"
                                               :unread-token-indexes [197 999]}
                                              (conj queried 999) 401))]
        (is (= #{999} (:never-read-token-indexes (balances/entry newly owner))))))))
