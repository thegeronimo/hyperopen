(ns hyperopen.wallet.idle-lock-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.wallet.agent-lockbox :as agent-lockbox]
            [hyperopen.wallet.idle-lock :as idle-lock]))

(def ^:private owner
  "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private minute-ms
  (* 60 1000))

(defn- passkey-state
  ([] (passkey-state {}))
  ([{:keys [status storage-mode local-protection-mode connected?]
     :or {status :ready
          storage-mode :local
          local-protection-mode :passkey
          connected? true}}]
   {:wallet {:connected? connected?
             :address owner
             :agent {:status status
                     :storage-mode storage-mode
                     :local-protection-mode local-protection-mode}}}))

(deftest idle-limit-is-thirty-minutes-test
  (is (= (* 30 minute-ms) idle-lock/idle-limit-ms)))

(deftest passkey-session-unlocked-requires-an-unlocked-passkey-session-test
  (is (true? (idle-lock/passkey-session-unlocked? (passkey-state))))
  (testing "sessions without passkey protection are never locked"
    (is (false? (idle-lock/passkey-session-unlocked?
                 (passkey-state {:local-protection-mode :plain}))))
    (is (false? (idle-lock/passkey-session-unlocked?
                 (passkey-state {:storage-mode :session})))))
  (testing "nothing to lock unless the key is in memory"
    (doseq [status [:locked :unlocking :not-ready :approving]]
      (is (false? (idle-lock/passkey-session-unlocked? (passkey-state {:status status})))
          (str status))))
  (is (false? (idle-lock/passkey-session-unlocked? (passkey-state {:connected? false})))))

(deftest should-lock-only-after-the-limit-test
  (let [state (passkey-state)]
    (is (false? (idle-lock/should-lock? {:state state
                                         :now-ms (+ 1000 (* 30 minute-ms) -1)
                                         :last-activity-ms 1000})))
    (is (true? (idle-lock/should-lock? {:state state
                                        :now-ms (+ 1000 (* 30 minute-ms))
                                        :last-activity-ms 1000})))
    (is (false? (idle-lock/should-lock? {:state state
                                         :now-ms (* 100 minute-ms)
                                         :last-activity-ms nil}))
        "no activity recorded yet means the watcher is not installed"))
  (is (false? (idle-lock/should-lock? {:state (passkey-state {:local-protection-mode :plain})
                                       :now-ms (* 1000 minute-ms)
                                       :last-activity-ms 0}))
      "plain sessions"))

(deftest check-locks-an-idle-passkey-session-test
  (let [store (atom (passkey-state))
        locked (atom [])]
    (idle-lock/note-activity! 0)
    (is (false? (idle-lock/check! store {:now-ms-fn (constantly (* 29 minute-ms))
                                         :lock-fn #(swap! locked conj %)})))
    (is (= [] @locked))
    (is (true? (idle-lock/check! store {:now-ms-fn (constantly (* 30 minute-ms))
                                        :lock-fn #(swap! locked conj %)})))
    (is (= [store] @locked))
    (testing "activity restarts the clock"
      (idle-lock/note-activity! (* 30 minute-ms))
      (is (false? (idle-lock/check! store {:now-ms-fn (constantly (* 59 minute-ms))
                                           :lock-fn #(swap! locked conj %)}))))))

(deftest check-default-lock-drops-the-unlocked-key-test
  (let [store (atom (passkey-state))
        cleared (atom [])]
    (idle-lock/note-activity! 0)
    (with-redefs [agent-lockbox/clear-unlocked-session! #(swap! cleared conj %)]
      (is (true? (idle-lock/check! store {:now-ms-fn (constantly (* 31 minute-ms))}))))
    (is (= [owner] @cleared))
    (is (= :locked (get-in @store [:wallet :agent :status])))))
