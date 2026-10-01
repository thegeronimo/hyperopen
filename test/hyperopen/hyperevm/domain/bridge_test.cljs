(ns hyperopen.hyperevm.domain.bridge-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.test-support.bridge-fixtures :as bridge-fixtures]
            [hyperopen.hyperevm.test-support.rpc-stubs :as stubs]))

(def ^:private spot-meta bridge-fixtures/bridge-spot-meta)
(def ^:private catalog (tokens/linked-tokens spot-meta))
(defn- token [token-name] (tokens/token-by-name spot-meta token-name))

(def ^:private purr (token "PURR"))
(def ^:private six (token "SIX"))
(def ^:private hope (token "HOPE"))
(def ^:private joff (token "JOFF"))
(def ^:private ubtc (token "UBTC"))
(def ^:private hype (token "HYPE"))
(def ^:private usdc (token "USDC"))

(defn- ok-result
  [units]
  {:success? true :return-data (stubs/amount-data units)})

(def ^:private reverted {:success? false :return-data "0x"})

(defn- live-results
  "extra-results as the live probe returned them: system balances for PURR,
   SIX, HOPE and UBTC, HOPE's wrong decimals(), and JOFF reverting both."
  []
  {[:system 1] (ok-result (get bridge-fixtures/live-system-units 1))
   [:system 6] (ok-result "0")
   [:system 122] (ok-result (get bridge-fixtures/live-system-units 122))
   [:system 197] (ok-result (get bridge-fixtures/live-system-units 197))
   [:system 296] reverted
   [:decimals 1] (ok-result 18)
   [:decimals 6] (ok-result 18)
   [:decimals 122] (ok-result 0)
   [:decimals 197] (ok-result 8)
   [:decimals 296] reverted})

(defn- healthy-state
  []
  (bridge/apply-health {} (bridge/health-result catalog {:extra-results (live-results)})))

(deftest health-calls-read-each-erc20-system-address-and-pending-decimals-test
  (let [calls (bridge/health-calls catalog [1 122])
        by-key (into {} (map (juxt :key identity)) calls)]
    (testing "every :erc20 token gets a system-address balanceOf; HYPE and USDC none"
      (is (= #{1 6 122 197 296 478}
             (set (keep (fn [[kind index]] (when (= :system kind) index)) (keys by-key))))))
    (testing "decimals() only for the pending indexes"
      (is (= #{1 122}
             (set (keep (fn [[kind index]] (when (= :decimals kind) index)) (keys by-key))))))
    (is (= {:key [:system 1]
            :target "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
            :call-data (abi/encode-balance-of "0x2000000000000000000000000000000000000001")}
           (get by-key [:system 1]))
        "PURR's balanceOf(0x2000…0001) on PURR's ERC-20")
    (is (= {:key [:decimals 122]
            :target "0x869ac826b78bc1d9501014994196e41025b5224b"
            :call-data "0x313ce567"}
           (get by-key [:decimals 122])))))

(deftest pending-decimals-indexes-skips-checked-tokens-test
  (is (= [1 6 122 197 296 478] (bridge/pending-decimals-indexes {} catalog)))
  (is (= [6 197 296 478]
         (bridge/pending-decimals-indexes
          {:hyperevm {:bridge {:token-health {1 {:decimals-ok? true}
                                              122 {:decimals-ok? false}
                                              6 {:balance-of-ok? true}}}}}
          catalog))
      "a token checked either way is not re-read; one with only a balance read is"))

(deftest health-result-projects-the-live-probe-test
  (let [{:keys [evm-system-units token-health]}
        (bridge/health-result catalog {:extra-results (live-results)})]
    (is (= {1 "508596915622676377079152451" 6 "0" 122 "167579739" 197 "2099941788654544"}
           evm-system-units)
        "a zero system balance is stored as \"0\", a reverted one not at all")
    (is (= {:decimals-ok? true :balance-of-ok? true} (get token-health 1)))
    (is (= {:decimals-ok? false :balance-of-ok? true} (get token-health 122))
        "HOPE's decimals() returns 0 where spotMeta implies 5")
    (is (= {:balance-of-ok? false} (get token-health 296))
        "JOFF reverts both calls; only the balanceOf failure is recorded")))

(deftest a-failed-decimals-call-is-rechecked-not-cached-test
  ;; One gas-starved chunk can fail every decimals() call after the call that
  ;; burned the gas. Those tokens must be re-checked, not marked unmovable
  ;; for the session.
  (let [state (bridge/apply-health {} (bridge/health-result
                                       catalog
                                       {:extra-results {[:system 1] (ok-result "5")
                                                        [:decimals 1] reverted
                                                        [:decimals 122] (ok-result 0)}}))]
    (is (= {:balance-of-ok? true} (get-in state [:hyperevm :bridge :token-health 1])))
    (is (= :unknown (bridge/token-health-status state purr)) "PURR is unknown, not bad")
    (is (= [1 6 197 296 478] (bridge/pending-decimals-indexes state catalog))
        "PURR is asked again next poll; HOPE's definite mismatch is cached")
    (let [recovered (bridge/apply-health state (bridge/health-result
                                                catalog
                                                {:extra-results {[:decimals 1] (ok-result 18)}}))]
      (is (= :ok (bridge/token-health-status recovered purr))))))

(deftest health-result-ignores-unread-chunks-test
  (let [result (bridge/health-result catalog {:extra-results {[:system 1] (ok-result "5")}
                                              :extra-unread [[:system 6] [:decimals 6]]})]
    (is (= {1 "5"} (:evm-system-units result)))
    (is (nil? (get-in result [:token-health 6]))
        "an unread chunk is unknown, never a failed call")))

(deftest apply-health-merges-and-drops-units-of-failed-reads-test
  (let [state (healthy-state)
        state* (bridge/apply-health state
                                    (bridge/health-result catalog
                                                          {:extra-results {[:system 1] reverted}}))]
    (is (nil? (get-in state* [:hyperevm :bridge :evm-system-units 1]))
        "a failed balanceOf drops the cached capacity")
    (is (= {:decimals-ok? true :balance-of-ok? false}
           (get-in state* [:hyperevm :bridge :token-health 1]))
        "the earlier decimals check survives")
    (is (= "0" (get-in state* [:hyperevm :bridge :evm-system-units 6]))
        "tokens the read did not cover keep their values")))

(deftest apply-health-with-a-request-time-drops-older-replies-test
  (let [newer (bridge/health-result catalog {:extra-results {[:system 1] (ok-result "9")}})
        older (bridge/health-result catalog {:extra-results {[:system 1] (ok-result "5")}})
        state (bridge/apply-health {} newer 2000)]
    (is (= "9" (get-in state [:hyperevm :bridge :evm-system-units 1])))
    (is (= 2000 (get-in state [:hyperevm :bridge :health-requested-at-ms])))
    (is (= state (bridge/apply-health state older 1000))
        "a slower reply to an older request never overwrites fresher capacity")
    (is (= state (bridge/apply-health state older 2000)) "nor does a repeat")
    (is (= "5" (get-in (bridge/apply-health state older 3000)
                       [:hyperevm :bridge :evm-system-units 1])))))

(deftest token-health-status-and-movable-test
  (let [state (healthy-state)]
    (is (= :ok (bridge/token-health-status state purr)))
    (is (= :ok (bridge/token-health-status state six))
        "SIX is healthy; its empty bridge is a capacity problem, not a health one")
    (is (= :bad (bridge/token-health-status state hope)))
    (is (= :bad (bridge/token-health-status state joff)))
    (is (= :unknown (bridge/token-health-status state (token "FUNT")))
        "no read yet")
    (is (= :ok (bridge/token-health-status {} hype)) "HYPE is always healthy")
    (is (= :ok (bridge/token-health-status {} usdc)) "USDC is always healthy")
    (is (= :unknown (bridge/token-health-status state nil)))
    (is (true? (bridge/movable? state purr)))
    (is (false? (bridge/movable? state hope)))
    (is (false? (bridge/movable? state joff)))
    (is (false? (bridge/movable? {} purr)) "unknown health is not movable")
    (is (false? (bridge/movable? state nil)))
    (is (true? (bridge/movable? {} hype)))))

(deftest core-to-evm-capacity-test
  (let [state (healthy-state)]
    (is (= "508596915.62267" (bridge/core->evm-capacity state purr))
        "PURR's 18-decimal system balance floored to 5 Core decimals")
    (is (= "0" (bridge/core->evm-capacity state six))
        "SIX's empty system address caps a move at zero")
    (is (= "20999417.88654544" (bridge/core->evm-capacity state ubtc)))
    (is (nil? (bridge/core->evm-capacity state (token "FUNT"))) "unknown until read")
    (is (= :unlimited (bridge/core->evm-capacity {} hype)))
    (is (= :unlimited (bridge/core->evm-capacity {} usdc)))
    (is (nil? (bridge/core->evm-capacity state nil)))))

(deftest core-system-balances-cap-evm-to-core-test
  (let [state (-> {}
                  (bridge/apply-core-system-balance 1 bridge-fixtures/purr-system-core-state 1000)
                  (bridge/apply-core-system-balance 6 bridge-fixtures/six-system-core-state 1000)
                  (bridge/apply-core-system-balance 150 bridge-fixtures/hype-system-core-state 1000)
                  (bridge/apply-core-system-balance 197 {:balances []} 1000))]
    (is (= {:amount "91403084.7764399946" :loaded-at-ms 1000}
           (get-in state [:hyperevm :bridge :core-system-balances 1]))
        "Core's extra decimals are kept in state")
    (is (= "91403084.77643" (bridge/evm->core-capacity state purr))
        "and floored to Core precision for the cap")
    (is (= "293.0535384" (bridge/evm->core-capacity state six)))
    (is (= "51277474.28299424" (bridge/evm->core-capacity state hype))
        "HYPE's cap is 0x2222…'s Core balance")
    (is (= "0" (bridge/evm->core-capacity state ubtc))
        "no row means the system address holds none")
    (is (nil? (bridge/evm->core-capacity {} purr)) "unknown until fetched")
    (is (= :unlimited (bridge/evm->core-capacity {} usdc)))))

(deftest core-system-balance-subtracts-hold-and-keeps-amount-on-error-test
  (let [state (bridge/apply-core-system-balance
               {} 1 {:balances [{:coin "PURR" :token 1 :total "10.5" :hold "2.25"}]} 5)]
    (is (= "8.25" (get-in state [:hyperevm :bridge :core-system-balances 1 :amount])))
    (let [failed (bridge/apply-core-system-balance-error state 1 "boom")]
      (is (= {:amount "8.25" :loaded-at-ms 5 :error "boom"}
             (get-in failed [:hyperevm :bridge :core-system-balances 1]))))
    (is (= "HyperCore returned an unreadable balance."
           (get-in (bridge/apply-core-system-balance {} 1 nil 5)
                   [:hyperevm :bridge :core-system-balances 1 :error])))))

(deftest evm-to-core-capacity-is-unknown-when-failed-or-stale-test
  (let [state (bridge/apply-core-system-balance
               {} 1 {:balances [{:coin "PURR" :token 1 :total "10.5" :hold "2.25"}]} 5000)
        failed (bridge/apply-core-system-balance-error state 1 "boom")
        refetched (bridge/apply-core-system-balance failed 1 {:balances []} 9000)]
    (is (= "8.25" (bridge/evm->core-capacity state purr)))
    (is (nil? (bridge/evm->core-capacity failed purr))
        "the latest fetch failed, so the kept amount is not a current cap")
    (is (= "0" (bridge/evm->core-capacity refetched purr)) "a later success clears the error")
    (testing "given the clock, an old amount is unknown"
      (is (= "8.25" (bridge/evm->core-capacity state purr 64999)))
      (is (nil? (bridge/evm->core-capacity state purr
                                           (+ 5000 bridge/core-capacity-max-age-ms))))
      (is (= :unlimited (bridge/evm->core-capacity {} usdc 999999999))))))

(deftest core-capacity-fetch-needed-test
  (is (true? (bridge/core-capacity-fetch-needed? purr)))
  (is (true? (bridge/core-capacity-fetch-needed? hype)))
  (is (false? (bridge/core-capacity-fetch-needed? usdc))))

(deftest core-capacity-refresh-due-test
  (let [loaded (bridge/apply-core-system-balance
                {} 1 {:balances [{:coin "PURR" :token 1 :total "10" :hold "0"}]} 5000)
        refresh-at (+ 5000 bridge/core-capacity-refresh-ms)]
    (is (true? (bridge/core-capacity-refresh-due? {} purr 1)) "never read")
    (is (false? (bridge/core-capacity-refresh-due? loaded purr (dec refresh-at))) "fresh")
    (is (true? (bridge/core-capacity-refresh-due? loaded purr refresh-at))
        "re-read before `core-capacity-max-age-ms` makes it unknown")
    (is (< bridge/core-capacity-refresh-ms bridge/core-capacity-max-age-ms))
    (testing "a read in flight is not sent again until the retry gap"
      (let [requested (bridge/mark-core-system-balance-requested loaded 1 refresh-at)]
        (is (= "10" (get-in requested [:hyperevm :bridge :core-system-balances 1 :amount]))
            "the last amount is kept while the new read is in flight")
        (is (false? (bridge/core-capacity-refresh-due? requested purr (inc refresh-at))))
        (is (true? (bridge/core-capacity-refresh-due?
                    requested purr (+ refresh-at bridge/core-capacity-retry-ms))))))
    (testing "a failed read is due again, and reports its error"
      (let [failed (bridge/apply-core-system-balance-error loaded 1 "429")]
        (is (true? (bridge/core-capacity-refresh-due? failed purr 5001)))
        (is (= "429" (bridge/core-capacity-error failed purr)))
        (is (nil? (bridge/core-capacity-error loaded purr)))))
    (is (false? (bridge/core-capacity-refresh-due? {} usdc 1)) "USDC needs no read")
    (is (false? (bridge/core-capacity-refresh-due? {} purr nil)) "no clock, no refresh")))
