(ns hyperopen.funding.application.hyperevm-transfer-followup-test
  "What happens after a HyperEVM Transfer run: a move left pending is
   settled in the background, and the moved token can be added to the
   wallet."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.application.hyperevm-transfer-effects :as transfer-effects]
            [hyperopen.funding.domain.transfer-run :as transfer-run]
            [hyperopen.funding.test-support.effects :as effects-support]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.funding.test-support.hyperevm-wallet :as fake]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]
            [hyperopen.funding.effects.hyperevm-runtime :as hyperevm-runtime]
            [hyperopen.funding.infrastructure.wallet-rpc :as wallet-rpc]
            [hyperopen.test-support.async :as async-support]))

(def ^:private hash-1
  "0x1111111111111111111111111111111111111111111111111111111111111111")

(def ^:private hype-request
  {:route :evm->core
   :action {:kind "native"}
   :evm {:amount "10" :symbol "HYPE" :token-index support/hype-index :owner support/owner}})

(defn- pending-store
  "A HYPE move left pending at `step`, still shown by the open modal."
  [step]
  (let [run (-> (transfer-run/start-run "flow-1" hype-request support/now-ms)
                (transfer-run/pending step hash-1))]
    (atom (-> (support/state {:transfer-evm run})
              (assoc-in [:hyperevm :in-flight support/owner]
                        {:flow-id "flow-1" :status :pending :hashes [hash-1] :step step
                         :waiting-receipt? false :asset "HYPE" :amount "10"
                         :token-index support/hype-index
                         :arrival {:location :spot :token-index support/hype-index
                                   :owner support/owner :before "412.08" :expected "10"}})))))

(defn- resolve!
  [store receipt]
  (let [toasts (atom [])
        dispatches (atom [])]
    (-> (transfer-effects/resolve-in-flight-receipt!
         {:store store
          :owner support/owner
          :tx-hash hash-1
          :dispatch! (fn [_ _ actions] (swap! dispatches into actions))
          :get-transaction-receipt! (fn [_] (if (instance? js/Error receipt)
                                              (js/Promise.reject receipt)
                                              (js/Promise.resolve receipt)))
          :now-ms-fn (constantly support/now-ms)
          :show-toast! (effects-support/capture-toast! toasts)})
        (.then (fn [result] {:result result :toasts @toasts :dispatches @dispatches})))))

(defn- run-of
  [store]
  (get-in @store [:funding-ui :modal :transfer-evm]))

(deftest a-confirmed-pending-move-clears-and-finishes-its-run-test
  (async done
    (let [store (pending-store :send)]
      (-> (resolve! store {:status "0x1"})
          (.then (fn [{:keys [toasts dispatches]}]
                   (is (nil? (transfer-state/in-flight-entry @store support/owner)))
                   (is (= {:phase :succeeded :arrival :arriving} (select-keys (run-of store) [:phase :arrival])))
                   (is (= [] toasts) "the open modal shows the outcome; no toast over it")
                   (is (some #{[:actions/refresh-hyperevm-bridge-capacity support/hype-index]} dispatches))
                   (is (some #(= :actions/refresh-hyperevm-balances (first %)) dispatches))
                   (swap! store assoc-in [:spot :clearinghouse-state :balances 1 :total] "422.08")
                   (is (= :arrived (:arrival (run-of store))) "arrival is tracked from the stored plan")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest a-confirmed-pending-approve-ends-the-run-for-a-retry-test
  (async done
    (let [store (pending-store :approve)]
      (-> (resolve! store {:status "0x1"})
          (.then (fn [{:keys [toasts]}]
                   (is (nil? (transfer-state/in-flight-entry @store support/owner)))
                   (is (= {:phase :failed :error (:approved-only transfer-run/messages)}
                          (select-keys (run-of store) [:phase :error])))
                   (is (= [] toasts))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest a-reverted-pending-move-fails-its-run-test
  (async done
    (let [store (pending-store :send)]
      (-> (resolve! store {:status "0x0"})
          (.then (fn [{:keys [toasts]}]
                   (is (nil? (transfer-state/in-flight-entry @store support/owner)))
                   (is (= {:phase :failed :error "Transaction reverted on HyperEVM."}
                          (select-keys (run-of store) [:phase :error])))
                   (is (= "Reverted on HyperEVM"
                          (:detail (some #(when (= :send (:id %)) %) (:steps (run-of store)))))
                       "the failed step says why, not \"Confirm in your wallet\"")
                   (is (= [] toasts))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest a-reverted-pending-approve-says-the-approval-reverted-test
  (async done
    (let [store (pending-store :approve)]
      (swap! store assoc-in [:hyperevm :in-flight support/owner :asset] "USDC")
      (-> (resolve! store {:status "0x0"})
          (.then (fn [{:keys [toasts]}]
                   (is (nil? (transfer-state/in-flight-entry @store support/owner)))
                   (is (= {:phase :failed :error (:approve-reverted transfer-run/messages)}
                          (select-keys (run-of store) [:phase :error])))
                   (is (= [] toasts) "the open modal says it")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest a-pending-move-settled-after-the-modal-closed-toasts-with-grouped-amounts-test
  (async done
    (let [closed (fn [step asset amount]
                   (doto (pending-store step)
                     (swap! assoc-in [:funding-ui :modal :open?] false)
                     (swap! update-in [:hyperevm :in-flight support/owner]
                            assoc :asset asset :amount amount)))]
      (-> (js/Promise.all
           #js [(resolve! (closed :send "HYPE" "1000") {:status "0x1"})
                (resolve! (closed :send "HYPE" "1000") {:status "0x0"})
                (resolve! (closed :approve "USDC" "1000") {:status "0x0"})
                (resolve! (closed :approve "USDC" "1000") {:status "0x1"})])
          (.then (fn [results]
                   (let [[confirmed reverted approve-reverted approved] (mapv :toasts results)]
                     (is (= [[:success "Your HyperEVM transfer of 1,000 HYPE confirmed."]] confirmed))
                     (is (= [[:error "Your HyperEVM transfer of 1,000 HYPE reverted."]] reverted))
                     (is (= [[:error "Your USDC approval for 1,000 USDC reverted on HyperEVM, so nothing was sent."]]
                            approve-reverted)
                         "no transfer was sent, so none is reported as reverted")
                     (is (= :info (ffirst approved))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest nothing-settles-without-a-receipt-for-the-same-hash-test
  (async done
    (let [no-receipt (pending-store :send)
          failed-read (pending-store :send)
          other-hash (pending-store :send)
          closed (pending-store :send)]
      (swap! other-hash assoc-in [:hyperevm :in-flight support/owner :hashes] ["0xother"])
      (swap! closed assoc-in [:funding-ui :modal] (support/modal {}))
      (-> (js/Promise.all #js [(resolve! no-receipt nil)
                               (resolve! failed-read (js/Error. "rate limited"))
                               (resolve! other-hash {:status "0x1"})
                               (resolve! closed {:status "0x1"})])
          (.then (fn [_]
                   (is (= :pending (:status (transfer-state/in-flight-entry @no-receipt support/owner))))
                   (is (= :pending (:phase (run-of no-receipt))))
                   (is (= :pending (:status (transfer-state/in-flight-entry @failed-read support/owner))))
                   (is (some? (transfer-state/in-flight-entry @other-hash support/owner)))
                   (is (nil? (transfer-state/in-flight-entry @closed support/owner))
                       "a closed modal still gets its entry cleared...")
                   (is (nil? (run-of closed)) "...without a run written into the new draft")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

;; --- add to wallet ------------------------------------------------------------------

(defn- watch!
  [wallet-config request]
  (let [log (atom [])
        toasts (atom [])
        store (atom (support/state))
        wallet (fake/fake-wallet log wallet-config)]
    (-> (transfer-effects/wallet-watch-asset!
         {:store store
          :request request
          :wallet-provider-fn (constantly (:provider wallet))
          :ensure-wallet-chain! wallet-rpc/ensure-wallet-chain!
          :watch-asset! wallet-rpc/watch-asset!
          :chain-config hyperevm-runtime/wallet-chain-config
          :show-toast! (effects-support/capture-toast! toasts)})
        (.then (fn [result] {:result result :log @log :toasts @toasts :store store})))))

(def ^:private purr-asset
  {:chain-id "0x3e7" :address "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e" :symbol "PURR" :decimals 18})

(deftest add-to-wallet-switches-to-hyperevm-then-watches-the-token-test
  (async done
    (-> (watch! {} purr-asset)
        (.then (fn [{:keys [result log toasts store]}]
                 (is (true? result))
                 (is (= [["eth_chainId" nil]
                         ["wallet_switchEthereumChain" [{:chainId "0x3e7"}]]
                         ["eth_chainId" nil]
                         ["wallet_watchAsset" {:type "ERC20"
                                               :options {:address "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
                                                         :symbol "PURR"
                                                         :decimals 18}}]]
                        (fake/wallet-requests log)))
                 (is (= "0x3e7" (get-in @store [:wallet :chain-id])))
                 (is (= [[:success "PURR added to your wallet."]] toasts))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest add-to-wallet-for-hype-only-needs-the-network-test
  (async done
    (-> (watch! {:chain "0x3e7"} {:chain-id "0x3e7" :address nil :symbol "HYPE" :decimals 18})
        (.then (fn [{:keys [result log]}]
                 (is (true? result))
                 (is (= ["eth_chainId"] (fake/wallet-methods log)) "no watchAsset for the native coin")
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest add-to-wallet-caches-a-wallet-that-cannot-switch-test
  (async done
    (-> (watch! {:switch-error [4200 "Unsupported method"]} purr-asset)
        (.then (fn [{:keys [result log toasts store]}]
                 (is (false? result))
                 (is (not-any? #{"wallet_watchAsset"} (fake/wallet-methods log)))
                 (is (transfer-state/chain-switch-unsupported? @store))
                 (is (= :error (ffirst toasts)))
                 (done)))
        (.catch (async-support/unexpected-error done)))))

(deftest add-to-wallet-refuses-other-chains-test
  (async done
    (-> (watch! {} (assoc purr-asset :chain-id "0xa4b1"))
        (.then (fn [{:keys [result log]}]
                 (is (false? result))
                 (is (= [] log))
                 (done)))
        (.catch (async-support/unexpected-error done)))))
