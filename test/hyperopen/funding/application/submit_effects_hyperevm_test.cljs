(ns hyperopen.funding.application.submit-effects-hyperevm-test
  "The Transfer submit effect for routes that touch HyperEVM: which
   submitter runs, what the modal's run shows, what stays in flight, and the
   refreshes and arrival tracking that follow."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.application.submit-effects :as effects]
            [hyperopen.funding.contracts :as contracts]
            [hyperopen.funding.application.hyperevm-transfer-effects :as transfer-effects]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-preview]
            [hyperopen.funding.test-support.effects :as effects-support]
            [hyperopen.funding.test-support.hyperevm-effects
             :refer [hash-1 purr-to-evm hype-to-spot core->evm evm->core store-for harness fire!
                     run-of stub-submitter]]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]
            [hyperopen.platform :as platform]
            [hyperopen.test-support.async :as async-support]))

;; --- HyperCore -> HyperEVM ----------------------------------------------------------

(deftest core-to-evm-signs-the-exact-send-asset-and-keeps-the-modal-on-its-run-test
  (async done
    (let [store (store-for purr-to-evm)
          {:keys [deps signed dispatches spot toasts]} (harness store (core->evm @store support/purr-index "100") {})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (is (= [[:send-asset support/owner
                            {:type "sendAsset"
                             :destination "0x2000000000000000000000000000000000000001"
                             :sourceDex "spot"
                             :destinationDex "spot"
                             :token "PURR:0xc1fb593aeffbeb02f85e0308e9956a90"
                             :amount "100"
                             :fromSubAccount ""}]]
                          @signed))
                   (is (true? (get-in @store [:funding-ui :modal :open?])) "the modal stays open")
                   (is (false? (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= {:phase :succeeded :flow-id "flow-1" :arrival :arriving :route :core->evm}
                          (select-keys (run-of store) [:phase :flow-id :arrival :route])))
                   (is (= [:done] (mapv :status (:steps (run-of store)))))
                   (is (some #{[:actions/load-user-data support/owner]} @dispatches))
                   (is (some #{[:actions/refresh-hyperevm-balances
                                {:force? true :fast-poll-ms 60000 :now-ms support/now-ms}]}
                             @dispatches))
                   (is (= [[support/owner {:priority :high :force-refresh? true}]] @spot))
                   (is (= [] @toasts) "the open modal shows the outcome; no toast over it")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest core-to-evm-flips-to-arrived-once-hyperevm-shows-the-amount-test
  (async done
    (let [store (store-for purr-to-evm)
          {:keys [deps]} (harness store (core->evm @store support/purr-index "100") {})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (is (= :arriving (:arrival (run-of store))))
                   ;; 50 PURR before; the next read shows 149.99999: not yet.
                   (swap! store assoc-in [:hyperevm :balances :by-address support/owner :token-units
                                          support/purr-index]
                          "149999990000000000000")
                   (is (= :arriving (:arrival (run-of store))))
                   (swap! store assoc-in [:hyperevm :balances :by-address support/owner :token-units
                                          support/purr-index]
                          "150000000000000000000")
                   (is (= :arrived (:arrival (run-of store))))
                   (is (= support/now-ms (get-in (run-of store) [:result :arrived-at-ms])))
                   (is (= support/now-ms (get-in (run-of store) [:result :sent-at-ms]))
                       "the arrival time counts from the accepted sendAsset")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest core-to-evm-reads-slow-after-a-minute-and-still-flips-late-test
  (async done
    (let [store (store-for purr-to-evm)
          {:keys [deps timers]} (harness store (core->evm @store support/purr-index "100") {})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (fire! timers 60000)
                   (is (= :slow (:arrival (run-of store))))
                   (swap! store assoc-in [:hyperevm :balances :by-address support/owner :token-units
                                          support/purr-index]
                          "150000000000000000000")
                   (is (= :arrived (:arrival (run-of store))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest core-to-evm-failures-show-the-failed-run-test
  (async done
    (let [store (store-for purr-to-evm)
          {:keys [deps toasts dispatches]}
          (harness store (core->evm @store support/purr-index "100")
                   {:submit-send-asset! (fn [& _]
                                          (js/Promise.resolve {:status "err" :response "Insufficient balance"}))
                    :exchange-response-error (fn [resp] (:response resp))})
          thrown (store-for purr-to-evm)
          {thrown-deps :deps}
          (harness thrown (core->evm @thrown support/purr-index "100")
                   {:submit-send-asset! (fn [& _] (js/Promise.reject (js/Error. "offline")))})]
      (-> (js/Promise.all #js [(effects/api-submit-funding-transfer! deps)
                               (effects/api-submit-funding-transfer! thrown-deps)])
          (.then (fn [_]
                   (is (= {:phase :failed :error "Transfer failed: Insufficient balance"
                           :maybe-sent? false}
                          (select-keys (run-of store) [:phase :error :maybe-sent?]))
                       "the exchange refused it, so nothing moved and a retry is safe")
                   (is (= [:failed] (mapv :status (:steps (run-of store)))))
                   (is (true? (get-in @store [:funding-ui :modal :open?])))
                   (is (false? (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= [] @toasts) "the failed view says it")
                   (is (empty? @dispatches) "nothing moved, so nothing is refreshed")
                   (is (= "Transfer failed: offline. Check your HyperEVM balance before trying again."
                          (:error (run-of thrown))))
                   (is (true? (:maybe-sent? (run-of thrown)))
                       "the POST threw, so the sendAsset may have applied")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

;; --- HyperEVM -> HyperCore ----------------------------------------------------------

(deftest evm-to-core-success-clears-the-entry-and-follows-the-arrival-test
  (async done
    (let [store (store-for (assoc hype-to-spot :amount-input "10"))
          seen (atom [])
          request (evm->core @store support/hype-index "10")
          {:keys [deps signed dispatches spot timers toasts]}
          (harness store request
                   {:submit-hyperevm-to-core!
                    (stub-submitter seen [[:switch-network :done] [:send :active] [:send :done]]
                                    {:status "ok" :txHash hash-1 :hashes [hash-1]})})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (is (= [] @signed) "hyperEvmToCore is never signed as a HyperCore action")
                   (is (= {:status :running :flow-id "flow-1" :hashes [] :asset "HYPE" :amount "10"}
                          (select-keys (:entry (first @seen))
                                       [:status :flow-id :hashes :asset :amount]))
                       "the move is in flight before the wallet is asked for anything")
                   (is (= (:action request) (:action (first @seen))))
                   (is (nil? (transfer-state/in-flight-entry @store support/owner)))
                   (is (= "0x3e7" (get-in @store [:wallet :chain-id])) "the verified switch is recorded")
                   (is (= {:phase :succeeded :arrival :arriving :tx-hash hash-1
                           :tx-url (str "https://hyperevmscan.io/tx/" hash-1)}
                          (select-keys (run-of store) [:phase :arrival :tx-hash :tx-url])))
                   (is (= [:done :done] (mapv :status (:steps (run-of store)))))
                   (is (some #{[:actions/refresh-hyperevm-bridge-capacity support/hype-index]} @dispatches))
                   (is (some #(= :actions/refresh-hyperevm-balances (first %)) @dispatches))
                   (is (= [support/owner] (mapv first @spot)) "Spot is read at once...")
                   (is (= [2000 5000 10000 20000 40000]
                          (sort (keep (fn [[ms]] (when (< ms 60000) ms)) @timers)))
                       "...and again while the credit is arriving")
                   (is (= [] @toasts))
                   ;; Spot shows the 10 HYPE (412.08 before).
                   (swap! store assoc-in [:spot :clearinghouse-state :balances 1 :total] "422.08")
                   (is (= :arrived (:arrival (run-of store))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest evm-to-core-pending-keeps-the-entry-and-blocks-another-move-test
  (async done
    (let [store (store-for (assoc hype-to-spot :amount-input "10"))
          request (evm->core @store support/hype-index "10")
          submitter (fn [store* owner _action {:keys [flow-id]}]
                      (swap! store* transfer-state/record-in-flight-hash owner flow-id
                             {:hash hash-1 :step :send :submitted-at-ms 5})
                      (js/Promise.resolve {:status "pending" :txHash hash-1 :step :send
                                           :hashes [hash-1]}))
          {:keys [deps toasts]} (harness store request {:submit-hyperevm-to-core! submitter})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (is (= {:status :pending :waiting-receipt? false :hashes [hash-1]}
                          (select-keys (transfer-state/in-flight-entry @store support/owner)
                                       [:status :waiting-receipt? :hashes])))
                   (is (= [[support/owner (transfer-state/in-flight-entry @store support/owner)]]
                          (transfer-state/pending-in-flight @store))
                       "the balance poller now resolves it in the background")
                   (is (= {:phase :pending :tx-hash hash-1} (select-keys (run-of store) [:phase :tx-hash])))
                   (is (false? (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= [] @toasts) "the pending view says it")
                   (let [second-seen (atom [])]
                     (swap! store assoc-in [:funding-ui :modal :transfer-evm] nil)
                     (effects/api-submit-funding-transfer!
                      (assoc deps :submit-hyperevm-to-core! (stub-submitter second-seen [] {:status "ok"})))
                     (is (= [] @second-seen) "no second move while one is unresolved")
                     (is (= transfer-state/pending-message
                            (get-in @store [:funding-ui :modal :error]))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest evm-to-core-failure-marks-the-step-and-caches-an-unsupported-switch-test
  (async done
    (let [store (store-for (assoc hype-to-spot :amount-input "10"))
          {:keys [deps toasts]}
          (harness store (evm->core @store support/hype-index "10")
                   {:submit-hyperevm-to-core!
                    (stub-submitter (atom []) []
                                    {:status "err" :error "Your wallet couldn't switch." :step :switch-network
                                     :kind :chain-switch-unsupported :hashes []})})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (is (nil? (transfer-state/in-flight-entry @store support/owner)))
                   (is (transfer-state/chain-switch-unsupported? @store))
                   (is (= {:phase :failed :error "Your wallet couldn't switch."}
                          (select-keys (run-of store) [:phase :error])))
                   (is (= [:failed :pending] (mapv :status (:steps (run-of store)))))
                   (is (= [] @toasts))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest a-submitter-that-rejects-after-a-hash-ends-pending-test
  (async done
    (let [store (store-for (assoc hype-to-spot :amount-input "10"))
          submitter (fn [store* owner _action {:keys [flow-id]}]
                      (swap! store* transfer-state/record-in-flight-hash owner flow-id
                             {:hash hash-1 :step :send :submitted-at-ms 5})
                      (js/Promise.reject (js/Error. "boom")))
          {:keys [deps]} (harness store (evm->core @store support/hype-index "10")
                                  {:submit-hyperevm-to-core! submitter})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (is (= :pending (:phase (run-of store))) "never back to a submittable form")
                   (is (= :pending (:status (transfer-state/in-flight-entry @store support/owner))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest hyperevm-moves-are-refused-before-anything-starts-test
  (let [seen (atom [])
        cases
        {:subaccount [(assoc-in (support/state hype-to-spot) [:account-context :subaccounts]
                                {:selected-address support/subaccount
                                 :rows [{:sub-account-user support/subaccount :master support/owner}]})
                      "HyperEVM transfers are available for the master account only."]
         :spectate [(assoc (support/state hype-to-spot) :account-context
                           {:spectate-mode {:active? true :address support/subaccount}})
                    nil]
         :owner-changed [(assoc-in (support/state hype-to-spot) [:wallet :address]
                                   "0x9999999999999999999999999999999999999999")
                         "Your wallet changed since this transfer was prepared. Review it and try again."]}]
    (doseq [[label [state expected]] cases
            [direction request] [[:evm->core (evm->core (support/state) support/hype-index "10")]
                                 [:core->evm (core->evm (support/state) support/purr-index "100")]]]
      (let [store (atom state)
            {:keys [deps signed]} (harness store request
                                           {:submit-hyperevm-to-core! (stub-submitter seen [] {:status "ok"})})]
        (effects/api-submit-funding-transfer! deps)
        (is (= [] @signed) [label direction])
        (is (nil? (run-of store)) [label direction])
        (is (nil? (transfer-state/in-flight-entry @store support/owner)) [label direction])
        (if expected
          (is (= expected (get-in @store [:funding-ui :modal :error])) [label direction])
          (is (seq (get-in @store [:funding-ui :modal :error])) [label direction]))))
    (is (= [] @seen))))

(deftest a-tampered-move-is-refused-by-the-invariant-test
  (let [store (store-for hype-to-spot)
        seen (atom [])
        request (assoc-in (evm->core @store support/hype-index "10")
                          [:action :recipient] "0x2000000000000000000000000000000000000001")
        {:keys [deps]} (harness store request {:submit-hyperevm-to-core! (stub-submitter seen [] {:status "ok"})})]
    (effects/api-submit-funding-transfer! deps)
    (is (= [] @seen))
    (is (re-find #"^Refusing to sign" (get-in @store [:funding-ui :modal :error])))))

;; --- other routes -------------------------------------------------------------------

(deftest a-send-asset-without-the-hyperevm-route-keeps-the-legacy-close-test
  (async done
    (let [store (atom (assoc (support/state) :funding-ui {:modal (effects-support/seed-modal :transfer)}))
          action {:type "sendAsset" :destination support/owner :sourceDex "" :destinationDex "xyz"
                  :token "USDC:0x6d1e7cde53ba9467b783cb7c530ce054" :amount "5" :fromSubAccount ""}
          {:keys [deps toasts]} (harness store {:action action} {})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (is (false? (get-in @store [:funding-ui :modal :open?])))
                   (is (nil? (run-of store)))
                   (is (= [[:success "Transfer submitted."]] @toasts))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest a-sent-gas-top-up-starts-the-hyperevm-fast-poll-test
  (async done
    (let [store (store-for (assoc hype-to-spot :transfer-gas-topup {:status :submitting}))
          {:keys [deps dispatches]} (harness store (:request (evm-preview/gas-topup-request @store)) {})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (is (= {:status :sent :id "flow-1"}
                          (get-in @store [:funding-ui :modal :transfer-gas-topup])))
                   (is (some #{[:actions/refresh-hyperevm-balances
                                {:force? true :fast-poll-ms 60000 :now-ms support/now-ms}]}
                             @dispatches))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

;; --- the view-model renders every run the effect writes -------------------------

(defn- view-model
  [store]
  (with-redefs [platform/now-ms (constantly support/now-ms)]
    (funding-actions/funding-modal-view-model @store)))

(deftest every-run-phase-the-effect-writes-passes-the-view-model-contract-test
  (async done
    (let [release (atom nil)
          store (store-for (assoc hype-to-spot :amount-input "10"))
          submitter (fn [store* owner _action {:keys [flow-id on-step!]}]
                      (on-step! :switch-network :done)
                      (on-step! :send :active)
                      (js/Promise. (fn [resolve _]
                                     (reset! release
                                             (fn [result]
                                               (swap! store* transfer-state/record-in-flight-hash owner flow-id
                                                      {:hash hash-1 :step :send :submitted-at-ms 5})
                                               (resolve result))))))
          {:keys [deps]} (harness store (evm->core @store support/hype-index "10")
                                  {:submit-hyperevm-to-core! submitter})
          result (effects/api-submit-funding-transfer! deps)
          running (view-model store)]
      (is (contracts/funding-modal-vm-valid? running))
      (is (= :transfer/progress (get-in running [:content :kind])))
      (is (= "Waiting for wallet (step 2 of 2)"
             (get-in running [:transfer :actions :submit-label])))
      (@release {:status "pending" :txHash hash-1 :step :send :hashes [hash-1]})
      (-> result
          (.then (fn [_]
                   (let [pending (view-model store)]
                     (is (contracts/funding-modal-vm-valid? pending))
                     (is (= :transfer/pending (get-in pending [:content :kind])))
                     (is (= (str "https://hyperevmscan.io/tx/" hash-1)
                            (get-in pending [:transfer :evm :tx-url]))))
                   (let [failed (atom @store)]
                     (swap! failed assoc-in [:funding-ui :modal :transfer-evm :phase] :failed)
                     (is (= :transfer/failed
                            (get-in (view-model failed) [:content :kind]))))
                   (let [core-store (store-for purr-to-evm)
                         {core-deps :deps} (harness core-store (core->evm @core-store support/purr-index "100") {})]
                     (-> (effects/api-submit-funding-transfer! core-deps)
                         (.then (fn [_]
                                  (let [succeeded (view-model core-store)]
                                    (is (contracts/funding-modal-vm-valid? succeeded))
                                    (is (= :transfer/success (get-in succeeded [:content :kind])))
                                    (is (true? (get-in succeeded [:transfer :evm :add-to-wallet?])))
                                    (is (= :arriving (get-in succeeded [:transfer :evm :arrival]))))))))))
          (.then (fn [_] (done)))
          (.catch (async-support/unexpected-error done))))))
