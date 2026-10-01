(ns hyperopen.hyperevm.infrastructure.balance-poller-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.hyperevm.domain.balances :as balances]
            [hyperopen.hyperevm.infrastructure.balance-poller :as poller]
            [hyperopen.hyperevm.test-support.fixtures :as fixtures]))

(def ^:private owner "0x1111111111111111111111111111111111111111")

(defn- base-state
  []
  {:router {:path "/trade"}
   :wallet {:address owner}
   :spot {:meta fixtures/mainnet-spot-meta}
   :hyperevm (balances/default-state)})

(defn- harness
  "Install the poller over fake timers. Returns the pieces a test drives."
  ([] (harness {}))
  ([overrides]
   (let [store (atom (base-state))
         now (atom 0)
         dispatched (atom [])
         timeouts (atom [])
         interval (atom nil)
         cleared (atom [])
         visible? (atom true)
         enabled? (atom true)]
     (poller/install-hyperevm-balance-poller!
      (merge {:store store
              :dispatch! (fn [store* _ actions]
                           (is (identical? store store*))
                           (swap! dispatched into actions))
              :now-ms-fn #(deref now)
              :set-timeout-fn (fn [f ms] (swap! timeouts conj [f ms]) :timeout-id)
              :set-interval-fn (fn [f ms] (reset! interval [f ms]) :interval-id)
              :clear-interval-fn (fn [id] (swap! cleared conj id))
              :visible?-fn #(deref visible?)
              :enabled?-fn #(deref enabled?)}
             overrides))
     {:store store :now now :dispatched dispatched :timeouts timeouts
      :interval interval :cleared cleared :visible? visible? :enabled? enabled?})))

(defn- run-timeouts!
  [{:keys [timeouts]}]
  (let [pending @timeouts]
    (reset! timeouts [])
    (doseq [[f _] pending] (f))))

(defn- tick!
  [{:keys [interval]}]
  ((first @interval)))

(deftest fingerprint-changes-dispatch-a-debounced-refresh-test
  (let [{:keys [store now dispatched timeouts] :as h} (harness)]
    (swap! store assoc :touched 1)
    (is (= 1 (count @timeouts)) "the first store change schedules a refresh")
    (is (= 250 (second (first @timeouts))))
    (swap! store assoc :touched 2)
    (is (= 1 (count @timeouts)) "an unchanged fingerprint schedules nothing more")
    (reset! now 700)
    (run-timeouts! h)
    (is (= [[:actions/refresh-hyperevm-balances {:now-ms 700}]] @dispatched))
    (testing "a route change to a HyperEVM-less page changes the fingerprint"
      (swap! store assoc-in [:router :path] "/vaults")
      (is (= 1 (count @timeouts))))))

(deftest an-account-reset-that-clears-entries-triggers-a-re-read-test
  ;; A deferred account bootstrap clears the entries after the first read;
  ;; the addresses are unchanged, but the entries' presence is fingerprinted.
  (let [{:keys [store now dispatched] :as h} (harness)]
    (swap! store balances/apply-loading {:addresses [owner] :requested-at-ms 1})
    (run-timeouts! h)
    (reset! dispatched [])
    (swap! store assoc-in [:hyperevm :balances :by-address] {})
    (reset! now 2000)
    (run-timeouts! h)
    (is (= [[:actions/refresh-hyperevm-balances {:now-ms 2000}]] @dispatched))))

(deftest interval-refreshes-every-30-seconds-on-an-active-visible-surface-test
  (let [{:keys [now dispatched interval visible?] :as h} (harness)]
    (is (= 4000 (second @interval)) "one 4 s tick drives both cadences")
    (reset! now 1000)
    (tick! h)
    (is (= [[:actions/refresh-hyperevm-balances {:now-ms 1000}]] @dispatched))
    (reset! now 5000)
    (tick! h)
    (is (= 1 (count @dispatched)) "not again within 30 s")
    (reset! now 31000)
    (tick! h)
    (is (= 2 (count @dispatched)))
    (reset! visible? false)
    (reset! now 70000)
    (tick! h)
    (is (= 2 (count @dispatched)) "a hidden document is not polled")))

(deftest interval-skips-inactive-surfaces-test
  (let [{:keys [store now dispatched] :as h} (harness)]
    (swap! store assoc-in [:router :path] "/vaults")
    (reset! dispatched [])
    (reset! now 1000)
    (tick! h)
    (is (= [] @dispatched))))

(deftest fast-poll-refreshes-on-every-tick-without-forcing-test
  (let [{:keys [store now dispatched visible?] :as h} (harness)]
    (swap! store assoc-in [:hyperevm :fast-poll-until-ms] 10000)
    (reset! visible? false)
    (reset! now 4000)
    (tick! h)
    (reset! now 8000)
    (tick! h)
    (is (= [[:actions/refresh-hyperevm-balances {:now-ms 4000}]
            [:actions/refresh-hyperevm-balances {:now-ms 8000}]]
           @dispatched)
        "unforced, so the plan's loading guard and receipt-wait pause still apply")
    (reset! now 12000)
    (tick! h)
    (is (= 2 (count @dispatched)) "the window has closed and the page is hidden")))

(deftest kill-switch-stops-every-refresh-test
  (let [{:keys [store now dispatched enabled?] :as h} (harness)]
    (reset! enabled? false)
    (swap! store assoc :touched 1)
    (run-timeouts! h)
    (reset! now 1000)
    (tick! h)
    (is (= [] @dispatched))))

(deftest reinstalling-replaces-the-interval-test
  (let [first-h (harness)
        cleared-before (count @(:cleared first-h))
        second-h (harness)]
    (is (= (inc cleared-before) (count @(:cleared first-h)))
        "the second install clears the first install's interval")
    (is (= [] @(:cleared second-h)))))

(deftest debug-kill-switch-toggles-the-default-gate-test
  (is (true? (poller/enabled?)))
  (is (false? (poller/set-enabled! false)))
  (is (false? (poller/enabled?)))
  (is (true? (poller/set-enabled! true)))
  (is (true? (poller/enabled?))))

(deftest ticks-refresh-an-open-transfer-drafts-bridge-balance-test
  ;; The funding context names the token whose HyperCore reads are due; the
  ;; poller dispatches them at most once per retry gap, only while visible
  ;; and enabled, and whatever the balance cadence did on that tick.
  (let [due-index (atom 150)
        {:keys [store now dispatched visible? enabled?] :as h}
        (harness {:capacity-refresh-index-fn (fn [state now-ms]
                                               (is (map? state))
                                               (is (number? now-ms))
                                               @due-index)
                  :capacity-retry-ms 10000})
        capacity-dispatches #(filterv (fn [[id]] (= :actions/refresh-hyperevm-bridge-capacity id))
                                      @dispatched)]
    (swap! store assoc-in [:router :path] "/vaults")
    (reset! dispatched [])
    (reset! now 1000)
    (tick! h)
    (is (= [[:actions/refresh-hyperevm-bridge-capacity 150]] (capacity-dispatches)))
    (reset! now 5000)
    (tick! h)
    (is (= 1 (count (capacity-dispatches))) "not again within the retry gap")
    (reset! now 11000)
    (tick! h)
    (is (= 2 (count (capacity-dispatches))))
    (testing "nothing due, hidden, or switched off dispatches nothing"
      (reset! due-index nil)
      (reset! now 30000)
      (tick! h)
      (reset! due-index 150)
      (reset! visible? false)
      (reset! now 40000)
      (tick! h)
      (reset! visible? true)
      (reset! enabled? false)
      (reset! now 50000)
      (tick! h)
      (is (= 2 (count (capacity-dispatches)))))))

(deftest without-a-capacity-source-ticks-dispatch-no-capacity-refresh-test
  (let [{:keys [now dispatched] :as h} (harness)]
    (reset! now 1000)
    (tick! h)
    (is (every? (fn [[id]] (not= :actions/refresh-hyperevm-bridge-capacity id)) @dispatched))))

(deftest ticks-settle-a-move-left-pending-in-the-background-test
  ;; A HyperEVM -> Core move whose foreground receipt wait timed out stays
  ;; in flight; ticks read its receipt at most once per check gap, only while
  ;; visible, enabled and not backing off, until the entry is cleared.
  (let [{:keys [store now dispatched visible?] :as h} (harness {:in-flight-check-ms 8000})
        checks #(filterv (fn [[id]] (= :actions/check-hyperevm-in-flight id)) @dispatched)]
    (swap! store assoc-in [:router :path] "/vaults")
    (reset! now 1000)
    (tick! h)
    (is (= [] (checks)) "nothing pending, nothing checked")
    (swap! store assoc-in [:hyperevm :in-flight owner]
           {:flow-id "f" :status :running :hashes ["0xa"] :waiting-receipt? true})
    (reset! now 2000)
    (tick! h)
    (is (= [] (checks)) "a running flow waits for its own receipt")
    (swap! store update-in [:hyperevm :in-flight owner] assoc :status :pending :waiting-receipt? false)
    (reset! now 3000)
    (tick! h)
    (is (= [[:actions/check-hyperevm-in-flight]] (checks)))
    (reset! now 9000)
    (tick! h)
    (is (= 1 (count (checks))) "not again within the check gap")
    (reset! now 11000)
    (tick! h)
    (is (= 2 (count (checks))))
    (testing "hidden or backing off checks nothing"
      (reset! visible? false)
      (reset! now 20000)
      (tick! h)
      (reset! visible? true)
      (swap! store assoc-in [:hyperevm :backoff] {:strikes 1 :until-ms 60000})
      (reset! now 30000)
      (tick! h)
      (is (= 2 (count (checks)))))
    (testing "an old pending move is checked less often"
      (swap! store assoc-in [:hyperevm :backoff] {:strikes 0 :until-ms nil})
      ;; Sent 6+ minutes ago; the last check was at 11000.
      (swap! store assoc-in [:hyperevm :in-flight owner :submitted-at-ms] -360000)
      (reset! now 40000)
      (tick! h)
      (is (= 2 (count (checks))) "29 s after the last check: not yet")
      (reset! now 41000)
      (tick! h)
      (is (= 3 (count (checks))))
      (reset! now 61000)
      (tick! h)
      (is (= 3 (count (checks))) "not within 30 s once it is over 5 minutes old")
      (reset! now 71000)
      (tick! h)
      (is (= 4 (count (checks)))))
    (testing "a cleared entry stops the checks"
      (swap! store assoc-in [:hyperevm :backoff] {:strikes 0 :until-ms nil})
      (swap! store assoc-in [:hyperevm :in-flight] {})
      (reset! now 200000)
      (tick! h)
      (is (= 4 (count (checks)))))))
