(ns hyperopen.hyperevm.domain.bridge-freshness-test
  "The HyperEVM system-balance cap on HyperCore -> HyperEVM moves is only
   trusted while it is current. An under-funded system address debits
   HyperCore and never credits HyperEVM, so a reading frozen by failed,
   rate-limited or paused polls, a lost chunk, or the user's own send must
   never pass as the cap."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.test-support.bridge-fixtures :as bridge-fixtures]
            [hyperopen.hyperevm.test-support.rpc-stubs :as stubs]))

(def ^:private spot-meta bridge-fixtures/bridge-spot-meta)
(def ^:private catalog (tokens/linked-tokens spot-meta))
(defn- token [token-name] (tokens/token-by-name spot-meta token-name))

(def ^:private purr (token "PURR"))
(def ^:private six (token "SIX"))
(def ^:private hype (token "HYPE"))
(def ^:private usdc (token "USDC"))

(defn- ok-result
  [units]
  {:success? true :return-data (stubs/amount-data units)})

(defn- read-at
  "State after a poll requested at `requested-at-ms` read PURR's and SIX's
   system balances (and nothing else)."
  [state requested-at-ms]
  (bridge/apply-health state
                       (bridge/health-result
                        catalog
                        {:extra-results {[:system 1] (ok-result (get bridge-fixtures/live-system-units 1))
                                         [:system 6] (ok-result "0")}})
                       requested-at-ms))

(deftest a-poll-stamps-the-system-balances-it-read-test
  (let [state (read-at {} 1000)]
    (is (= {1 1000 6 1000} (get-in state [:hyperevm :bridge :evm-system-read-at-ms])))
    (testing "a later poll whose PURR chunk went unread leaves PURR's stamp alone"
      (let [state* (bridge/apply-health state
                                        (bridge/health-result
                                         catalog
                                         {:extra-results {[:system 1] (ok-result "1")
                                                          [:system 6] (ok-result "0")}
                                          :extra-unread [[:system 1]]})
                                        50000)]
        (is (= {1 1000 6 50000} (get-in state* [:hyperevm :bridge :evm-system-read-at-ms])))
        (is (= (get bridge-fixtures/live-system-units 1)
               (get-in state* [:hyperevm :bridge :evm-system-units 1]))
            "the unread chunk keeps the last amount in state")
        (is (nil? (bridge/core->evm-capacity state* purr 62000))
            "but once it is a minute old it no longer gates a move")
        (is (= "0" (bridge/core->evm-capacity state* six 62000)))))))

(deftest the-cap-is-unknown-once-a-minute-old-test
  (let [state (read-at {} 1000)]
    (is (= "508596915.62267" (bridge/core->evm-capacity state purr 1000)))
    (is (= "508596915.62267" (bridge/core->evm-capacity state purr 60999)))
    (is (nil? (bridge/core->evm-capacity state purr 61000))
        "a failed or backed-off poll freezes the reading; it must read as checking")
    (is (= "508596915.62267" (bridge/core->evm-capacity state purr))
        "without the clock (the Balances table's empty-bridge check) the amount stands")
    (testing "a reading with no stamp is never current"
      (is (nil? (bridge/core->evm-capacity
                 (assoc-in {} [:hyperevm :bridge :evm-system-units 1] "1000000000000000000")
                 purr
                 1000))))
    (testing "HYPE and USDC have no cap to age"
      (is (= :unlimited (bridge/core->evm-capacity {} hype 999999999)))
      (is (= :unlimited (bridge/core->evm-capacity {} usdc 999999999))))))

(deftest a-send-voids-the-cap-until-a-later-read-test
  (let [state (-> (read-at {} 10000)
                  (bridge/mark-core->evm-sent purr 12000))]
    (is (nil? (bridge/core->evm-capacity state purr 12500))
        "the reading predates the send, which drew the bridge down")
    (is (= "0" (bridge/core->evm-capacity state six 12500)) "other tokens are unaffected")
    (testing "a read requested right after the send may not show it yet"
      (is (nil? (bridge/core->evm-capacity (read-at state 14000) purr 14500))))
    (testing "a read requested once the send settled counts again"
      (is (= "508596915.62267" (bridge/core->evm-capacity (read-at state 17000) purr 17500))))
    (testing "HYPE and USDC sends stamp nothing"
      (is (= state (bridge/mark-core->evm-sent state hype 13000)))
      (is (= state (bridge/mark-core->evm-sent state usdc 13000))))))
