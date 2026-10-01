(ns hyperopen.funding.application.hyperevm-transfer-outcomes-test
  "What follows a HyperEVM Transfer submit: Spot re-reads only ever land on
   the owner's own Spot, the arrival watch ends with its run, and nothing
   after a move went through can mark it failed."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.application.hyperevm-run-state :as run-state]
            [hyperopen.funding.application.submit-effects :as effects]
            [hyperopen.funding.domain.transfer-run :as transfer-run]
            [hyperopen.funding.effects.hyperevm-runtime :as hyperevm-runtime]
            [hyperopen.funding.test-support.hyperevm-effects :as h]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.test-support.async :as async-support]))

(def ^:private hype-row
  "Index of HYPE in `support/spot-balances`."
  1)

(defn- with-subaccount
  [state]
  (assoc-in state [:account-context :subaccounts]
            {:selected-address support/subaccount
             :rows [{:sub-account-user support/subaccount :master support/owner}]}))

(defn- evm->core-success!
  "A HYPE HyperEVM -> Spot move that succeeded, with `overrides` on the
   harness. Resolves with the harness and store."
  [overrides]
  (let [store (h/store-for (assoc h/hype-to-spot :amount-input "10"))
        harness (h/harness store (h/evm->core @store support/hype-index "10")
                           (merge {:submit-hyperevm-to-core!
                                   (h/stub-submitter (atom []) [[:switch-network :done] [:send :done]]
                                                     {:status "ok" :txHash h/hash-1 :hashes [h/hash-1]})}
                                  overrides))]
    (-> (effects/api-submit-funding-transfer! (:deps harness))
        (.then (fn [_] (assoc harness :store store))))))

;; --- Spot re-reads never land on another account's Spot ---------------------------

(deftest spot-re-reads-stop-once-another-account-is-shown-test
  (async done
    (let [requests (atom [])
          request-fn (fn [address opts]
                       (js/Promise. (fn [resolve _]
                                      (swap! requests conj {:address address :opts opts
                                                            :resolve resolve}))))
          ;; The real refresh, over a scripted HyperCore info read.
          refresh (fn [store owner opts]
                    (hyperevm-runtime/refresh-spot-clearinghouse! store owner opts request-fn))]
      (-> (evm->core-success! {:refresh-spot-clearinghouse! refresh})
          (.then (fn [{:keys [store timers]}]
                   (is (= [support/owner] (mapv :address @requests)) "Spot is read at once")
                   (let [shown (h/spectating @store)
                         spectated-spot {:balances [{:coin "HYPE" :token support/hype-index
                                                     :total "999" :hold "0"}]}]
                     ;; The user starts spectating; Spot now holds that account.
                     (reset! store (assoc-in shown [:spot :clearinghouse-state] spectated-spot))
                     (doseq [ms run-state/spot-refresh-delays-ms] (h/fire! timers ms))
                     (is (= 1 (count @requests)) "no re-read while another account is shown")
                     ;; The read issued before the switch answers now.
                     ((:resolve (first @requests)) {:balances [{:coin "HYPE" :token support/hype-index
                                                                :total "422.08" :hold "0"}]})
                     (-> (js/Promise.resolve nil)
                         (.then (fn [_]
                                  (is (= spectated-spot (get-in @store [:spot :clearinghouse-state]))
                                      "the owner's balances never replace the spectated account's")
                                  (is (= :arriving (:arrival (h/run-of store)))
                                      "another account's Spot never reads as arrived")
                                  (done)))))))
          (.catch (async-support/unexpected-error done))))))

(deftest spot-re-reads-skip-a-selected-subaccount-test
  (async done
    (-> (evm->core-success! {})
        (.then (fn [{:keys [store timers spot]}]
                 (is (= 1 (count @spot)))
                 (swap! store with-subaccount)
                 (doseq [ms run-state/spot-refresh-delays-ms] (h/fire! timers ms))
                 (is (= 1 (count @spot)) "the owner's Spot is not read into the subaccount's")
                 (done)))
        (.catch (async-support/unexpected-error done)))))

;; --- the arrival watch ends with its run -------------------------------------------

(defn- tracked
  "An open modal showing a succeeded HYPE HyperEVM -> Spot run of flow-1,
   tracked by `run-state/track-arrival!`."
  []
  (let [store (h/store-for (assoc h/hype-to-spot :amount-input "10"))
        request (h/evm->core @store support/hype-index "10")
        arrival (transfer-run/arrival-plan @store request)
        {:keys [deps spot timers]} (h/harness store request {})]
    (swap! store assoc-in [:funding-ui :modal :transfer-evm]
           (-> (transfer-run/start-run "flow-1" request support/now-ms)
               (transfer-run/succeeded h/hash-1)))
    {:store store :spot spot :timers timers
     :done? (run-state/track-arrival! deps "flow-1" arrival)}))

(deftest the-arrival-watch-stops-when-its-run-is-gone-test
  (let [{:keys [store spot timers done?]} (tracked)]
    (is (false? @done?))
    (is (= 1 (count @spot)) "the first Spot re-read happens at once")
    (swap! store assoc-in [:funding-ui :modal :open?] false)
    (is (true? @done?) "closing the modal ends the watch")
    (doseq [ms run-state/spot-refresh-delays-ms] (h/fire! timers ms))
    (is (= 1 (count @spot)) "and the delayed re-reads")))

(deftest the-arrival-watch-ends-on-arrival-or-at-its-deadline-test
  (let [{:keys [store spot timers done?]} (tracked)]
    (h/fire! timers 2000)
    (is (= 2 (count @spot)))
    (swap! store assoc-in [:spot :clearinghouse-state :balances hype-row :total] "422.08")
    (is (true? @done?))
    (is (= :arrived (:arrival (h/run-of store))))
    (doseq [ms (rest run-state/spot-refresh-delays-ms)] (h/fire! timers ms))
    (is (= 2 (count @spot)) "no Spot re-read after the credit showed up"))
  (let [{:keys [store spot timers done?]} (tracked)]
    (h/fire! timers transfer-run/arrival-slow-ms)
    (is (= :slow (:arrival (h/run-of store))))
    (h/fire! timers transfer-run/arrival-watch-ms)
    (is (true? @done?) "the watch ends at its deadline")
    (let [reads (count @spot)]
      (swap! store assoc-in [:spot :clearinghouse-state :balances hype-row :total] "422.08")
      (is (= :slow (:arrival (h/run-of store))) "a credit after the deadline changes nothing")
      (doseq [ms run-state/spot-refresh-delays-ms] (h/fire! timers ms))
      (is (= reads (count @spot))))))

(deftest spot-arrival-counts-the-whole-balance-test
  (let [{:keys [store]} (tracked)]
    ;; An order placed meanwhile holds 100 HYPE; the 10 HYPE still arrived.
    (swap! store update-in [:spot :clearinghouse-state :balances hype-row]
           assoc :total "422.08" :hold "100")
    (is (= :arrived (:arrival (h/run-of store))))))

;; --- nothing after a sent move can fail it -----------------------------------------

(deftest core-to-evm-follow-up-errors-never-fail-a-sent-move-test
  (async done
    (let [store (h/store-for h/purr-to-evm)
          {:keys [deps toasts logs]}
          (h/harness store (h/core->evm @store support/purr-index "100")
                     {:dispatch! (fn [& _] (throw (js/Error. "handler assertion")))
                      :refresh-spot-clearinghouse! (fn [& _] (throw (js/Error. "spot down")))})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (is (= {:phase :succeeded :arrival :arriving}
                          (select-keys (h/run-of store) [:phase :arrival]))
                       "the sendAsset was accepted, so the run stays succeeded")
                   (is (= [] @toasts) "no error toast (and no success toast over the open modal)")
                   (is (= 3 (count @logs)) "user data, HyperEVM and Spot refreshes each logged")
                   (swap! store assoc-in [:hyperevm :balances :by-address support/owner :token-units
                                          support/purr-index]
                          "150000000000000000000")
                   (is (= :arrived (:arrival (h/run-of store))) "arrival is still tracked")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest core-to-evm-runtime-errors-refresh-before-a-retry-test
  (async done
    (let [store (h/store-for h/purr-to-evm)
          {:keys [deps dispatches toasts]}
          (h/harness store (h/core->evm @store support/purr-index "100")
                     {:submit-send-asset! (fn [& _] (js/Promise.reject (js/Error. "request timed out")))})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (let [message (str "Transfer failed: request timed out. "
                                      "Check your HyperEVM balance before trying again.")]
                     (is (= {:phase :failed :error message :maybe-sent? true}
                            (select-keys (h/run-of store) [:phase :error :maybe-sent?]))
                         "no one-click Try again: the sendAsset may have applied")
                     (is (= [] @toasts) "the failed view says it"))
                   (is (some #{[:actions/load-user-data support/owner]} @dispatches))
                   (is (some #{[:actions/refresh-hyperevm-balances
                                {:force? true :fast-poll-ms 60000 :now-ms support/now-ms}]}
                             @dispatches)
                       "the bridge capacity a retry is checked against is read again at once")
                   (done)))
          (.catch (async-support/unexpected-error done))))))
