(ns hyperopen.hyperevm.domain.transfer-state-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]))

(def ^:private owner "0x1234567890abcdef1234567890abcdef12345678")

(deftest in-flight-entry-is-read-per-owner-test
  (let [state {:hyperevm {:in-flight {owner {:hashes ["0x1" "0x2"] :waiting-receipt? true}}}}
        entry (transfer-state/in-flight-entry state (.toUpperCase owner))]
    (is (= "0x2" (transfer-state/in-flight-tx-hash entry)))
    (is (= "https://hyperevmscan.io/tx/0x2" (transfer-state/in-flight-tx-url entry)))
    (is (nil? (transfer-state/in-flight-entry state "0x9999999999999999999999999999999999999999")))
    (is (nil? (transfer-state/in-flight-tx-url {:hashes []})))))

(deftest wallet-capabilities-are-keyed-per-provider-test
  (is (= [:hyperevm :wallet-capabilities "default"] (transfer-state/capabilities-path {})))
  (is (= [:hyperevm :wallet-capabilities "io.rabby"]
         (transfer-state/capabilities-path {:wallet {:selected-provider-id "io.rabby"}})))
  (is (true? (transfer-state/chain-switch-unsupported?
              {:wallet {:selected-provider-id "io.rabby"}
               :hyperevm {:wallet-capabilities {"io.rabby" {:chain-switch :unsupported}}}})))
  (is (false? (transfer-state/chain-switch-unsupported?
               {:wallet {:selected-provider-id "io.metamask"}
                :hyperevm {:wallet-capabilities {"io.rabby" {:chain-switch :unsupported}}}}))
      "another provider starts clean"))

(deftest wallet-chain-id-is-normalized-test
  (is (= "0x3e7" (transfer-state/wallet-chain-id {:wallet {:chain-id "0x03E7"}})))
  (is (= "0x3e7" (transfer-state/wallet-chain-id {:wallet {:chain-id "999"}})))
  (is (nil? (transfer-state/wallet-chain-id {:wallet {:chain-id "0xzz"}})))
  (is (true? (transfer-state/wallet-on-hyperevm? {:wallet {:chain-id "0x3e7"}})))
  (is (false? (transfer-state/wallet-on-hyperevm? {:wallet {:chain-id "0xa4b1"}}))))

(deftest forgetting-a-refused-switch-returns-the-whole-keyword-pathed-map-test
  (is (every? keyword? transfer-state/wallet-capabilities-path)
      "`:effects/save` accepts keyword paths only; provider keys are strings")
  (let [state {:wallet {:selected-provider-id "io.rabby"}
               :hyperevm {:wallet-capabilities {"io.rabby" {:chain-switch :unsupported :other 1}
                                                "io.metamask" {:chain-switch :unsupported}}}}]
    (is (= {"io.rabby" {:other 1} "io.metamask" {:chain-switch :unsupported}}
           (transfer-state/capabilities-without-chain-switch state)))
    (is (= {"io.metamask" {:chain-switch :unsupported}}
           (transfer-state/capabilities-without-chain-switch
            (assoc-in state [:hyperevm :wallet-capabilities "io.rabby"] {:chain-switch :unsupported})))
        "a provider left with nothing cached is dropped")
    (is (= {} (transfer-state/capabilities-without-chain-switch {})))))

(deftest in-flight-entries-belong-to-their-flow-test
  (let [started (transfer-state/start-in-flight {} (.toUpperCase owner)
                                                {:flow-id "f1" :started-at-ms 1 :asset "HYPE"})
        entry (transfer-state/in-flight-entry started owner)]
    (is (= {:flow-id "f1" :status :running :hashes [] :waiting-receipt? false
            :started-at-ms 1 :asset "HYPE"}
           entry)
        "the entry exists before any hash, keyed by the normalized owner")
    (is (= started (transfer-state/start-in-flight started owner {:flow-id "f2"}))
        "an existing entry is never replaced")
    (let [hashed (transfer-state/record-in-flight-hash started owner "f1"
                                                       {:hash "0xa" :step :approve :submitted-at-ms 7})]
      (is (= {:hashes ["0xa"] :step :approve :waiting-receipt? true :submitted-at-ms 7}
             (select-keys (transfer-state/in-flight-entry hashed owner)
                          [:hashes :step :waiting-receipt? :submitted-at-ms])))
      (is (= hashed (transfer-state/record-in-flight-hash hashed owner "other" {:hash "0xb"}))
          "another flow cannot add to it")
      (is (= hashed (transfer-state/merge-in-flight hashed owner "other" {:status :pending})))
      (is (= :pending (:status (transfer-state/in-flight-entry
                                (transfer-state/merge-in-flight hashed owner "f1" {:status :pending})
                                owner))))
      (is (= hashed (transfer-state/clear-in-flight hashed owner "other")))
      (is (= {} (get-in (transfer-state/clear-in-flight hashed owner "f1") [:hyperevm :in-flight]))))))

(deftest only-background-pending-moves-are-resolved-by-the-poller-test
  (let [other "0x9999999999999999999999999999999999999999"
        state {:hyperevm {:in-flight {owner {:flow-id "a" :status :pending :hashes ["0x1"]
                                             :waiting-receipt? false}
                                      other {:flow-id "b" :status :running :hashes ["0x2"]
                                             :waiting-receipt? false}}}}]
    (is (= [owner] (mapv first (transfer-state/pending-in-flight state)))
        "a running flow owns its own entry, even between its transactions")
    (is (= [] (transfer-state/pending-in-flight
               (assoc-in state [:hyperevm :in-flight owner :hashes] []))))
    (is (= [] (transfer-state/pending-in-flight
               (assoc-in state [:hyperevm :in-flight owner :waiting-receipt?] true))))
    (is (= [] (transfer-state/pending-in-flight {})))))

(deftest an-unconfirmed-move-blocks-with-its-own-message-and-is-never-polled-test
  (let [state {:hyperevm {:in-flight {owner {:flow-id "a" :status :unconfirmed :hashes []
                                             :waiting-receipt? false}}}}
        entry (transfer-state/in-flight-entry state owner)]
    (is (= transfer-state/unconfirmed-message (transfer-state/in-flight-blocked-message entry)))
    (is (= transfer-state/pending-message
           (transfer-state/in-flight-blocked-message (assoc entry :status :pending :hashes ["0xab"]))))
    (is (= transfer-state/in-flight-message
           (transfer-state/in-flight-blocked-message (assoc entry :status :running :hashes ["0xab"])))
        "sent and confirming in the foreground")
    (is (= transfer-state/wallet-unanswered-message
           (transfer-state/in-flight-blocked-message (assoc entry :status :running)))
        "no hash yet: the wallet prompt may never be answered, so say how to get out")
    (doseq [message [transfer-state/pending-message transfer-state/wallet-unanswered-message]]
      (is (re-find #"reload the page" message))
      (is (not (re-find #"finish on its own|before trying again" message))))
    (is (nil? (transfer-state/in-flight-blocked-message nil)))
    (is (= [] (transfer-state/pending-in-flight state)) "no hash to read a receipt for")))

(deftest background-receipt-reads-slow-down-as-a-move-ages-test
  (let [state (fn [& ages]
                {:hyperevm {:in-flight
                            (into {}
                                  (map-indexed
                                   (fn [i age]
                                     [(str "0x" i) {:flow-id (str i) :status :pending :hashes ["0x1"]
                                                    :waiting-receipt? false
                                                    :submitted-at-ms (- 10000000 age)}]))
                                  ages)}})
        interval #(transfer-state/in-flight-check-interval-ms % 10000000 8000)]
    (is (nil? (interval {})) "nothing pending, nothing to schedule")
    (is (= 8000 (interval (state 60000))) "the poller's own gap while young")
    (is (= 8000 (interval (state 300000))))
    (is (= 30000 (interval (state 300001))))
    (is (= 30000 (interval (state 1800000))))
    (is (= 120000 (interval (state 1800001))) "a likely replaced or dropped transaction")
    (is (= 8000 (interval (state 3600000 1000))) "the youngest pending move sets the pace")
    (is (= 8000 (transfer-state/in-flight-check-interval-ms
                 {:hyperevm {:in-flight {owner {:status :pending :hashes ["0x1"]}}}}
                 10000000 8000))
        "an entry without a send time is treated as young")))
