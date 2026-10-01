(ns hyperopen.funding.domain.transfer-review-fixes-test
  "Transfer rules the Milestone 9 review tightened: the Core -> HyperEVM
   bridge cap is only trusted while current, Perps -> HyperEVM goes through
   Spot, an asset whose bridge is known empty is not offered first, a
   partial HyperEVM read is unknown rather than empty, and the copy and
   labels a user acts on say what happened."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.domain.evm-transfer-amounts :as evm-amounts]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-preview]
            [hyperopen.funding.domain.transfer-balances :as transfer-balances]
            [hyperopen.funding.domain.transfer-invariants :as transfer-invariants]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.funding.domain.transfer-run :as transfer-run]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.platform :as platform]))

(def ^:private spot->evm {:transfer-from :spot :transfer-to :hyperevm})
(def ^:private evm->spot {:transfer-from :hyperevm :transfer-to :spot})

(defn- preview
  [state now-ms]
  (evm-preview/evm-transfer-preview state (get-in state [:funding-ui :modal]) now-ms))

(defn- purr
  [state]
  (tokens/token-by-index (get-in state [:spot :meta]) support/purr-index))

(defn- vm
  [state]
  (with-redefs [platform/now-ms (constantly support/now-ms)]
    (funding-actions/funding-modal-view-model state)))

;; --- the Core -> HyperEVM cap is current or unknown ----------------------------------

(deftest a-stale-hyperevm-bridge-reading-blocks-as-checking-test
  (let [state (support/state (assoc spot->evm :transfer-asset support/purr-index :amount-input "100"))
        route {:from :spot :to :hyperevm}]
    (is (true? (:ok? (preview state support/now-ms))) "read 2 s ago: current")
    (let [late (+ support/now-ms bridge/evm-capacity-max-age-ms)
          result (preview state late)]
      (is (= :hyperevm-unavailable (get-in result [:blocked :code])))
      (is (= "Checking the bridge balance…" (get-in result [:blocked :message])))
      (is (nil? (:request result)) "nothing to sign against a frozen cap")
      (is (nil? (evm-amounts/max-amount-text state route (purr state) late))
          "MAX is unknown too"))
    (testing "a Core -> EVM send voids the reading until a later read lands"
      (let [sent (bridge/mark-core->evm-sent state (purr state) support/now-ms)]
        (is (= "Checking the bridge balance…"
               (get-in (preview sent (+ support/now-ms 1000)) [:blocked :message])))))))

;; --- Perps -> HyperEVM goes through Spot -----------------------------------------------

(deftest perps-to-hyperevm-is-offered-through-spot-test
  (let [route (transfer-route/transfer-route {:transfer-from :perps :transfer-to :hyperevm})
        options (transfer-route/location-options (support/state) {:from :perps :to :spot})
        to-evm (some #(when (= :hyperevm (:id %)) %) (:to options))]
    (is (false? (:valid? route)))
    (is (= transfer-route/perps-to-evm-reason (:reason to-evm)))
    (is (true? (:disabled? to-evm)) "visible reason, not a silent no-op")
    (is (= [:perps :spot] (transfer-route/with-location {:from :spot :to :hyperevm} :from :perps))
        "choosing Perps as the source of a HyperEVM move sends it to Spot instead")
    (is (= [:perps :spot] (transfer-route/swapped {:from :hyperevm :to :perps})))))

(deftest a-send-to-a-system-address-must-leave-from-spot-test
  (let [state (support/state)
        request (evm-preview/core->evm-request state {:from :spot :to :hyperevm} (purr state) "1")]
    (is (= "spot" (get-in request [:action :sourceDex])))
    (is (nil? (transfer-invariants/check-request state request)))
    (is (= "Refusing to sign: a move to HyperEVM must leave from Spot."
           (transfer-invariants/check-request state (assoc-in request [:action :sourceDex] ""))))))

;; --- known-empty bridges -----------------------------------------------------------------

(deftest an-asset-whose-bridge-is-known-empty-is-disabled-and-not-picked-test
  (let [state (support/state)
        options (transfer-route/asset-options state {:from :spot :to :hyperevm})
        six (some #(when (= support/six-index (:index %)) %) options)]
    (testing "toward HyperEVM it is disabled with the Balances table's reason"
      (is (true? (:disabled? six)))
      (is (true? (:empty-bridge? six)))
      (is (= transfer-route/bridge-empty-evm-reason (:reason six))))
    (testing "a location change never keeps it, and a preset opener never lands on it"
      (is (not= support/six-index (transfer-route/eligible-asset-index options support/six-index)))
      (is (not= support/six-index (transfer-route/eligible-asset-index options nil)))))
  (testing "toward HyperCore it stays selectable (its reading is fetched on selection) but is not picked first"
    (let [state (assoc-in (support/state) [:hyperevm :bridge :core-system-balances support/hype-index]
                          {:amount "0" :loaded-at-ms support/now-ms})
          options (transfer-route/asset-options state {:from :hyperevm :to :spot})
          hype (some #(when (= support/hype-index (:index %)) %) options)]
      (is (true? (:empty-bridge? hype)))
      (is (false? (:disabled? hype)))
      (is (not= support/hype-index (transfer-route/eligible-asset-index options support/hype-index))
          "a route change moves off it while another asset can move"))))

;; --- a partial HyperEVM read is unknown, not empty --------------------------------------

(deftest a-partial-hyperevm-read-is-never-read-as-empty-test
  (let [state (support/with-evm-entry
                (support/state evm->spot)
                (assoc support/evm-entry :token-units {} :never-read-token-indexes #{0 1}))]
    (is (false? (transfer-balances/location-known? state :hyperevm support/owner)))
    (is (= "Some HyperEVM balances couldn't be read. Retrying…"
           (get-in (preview state support/now-ms) [:blocked :message]))
        "with no asset chosen, the form says the read is incomplete")
    (is (nil? (get-in (vm state) [:transfer :asset :empty-message]))
        "never \"No linked tokens on HyperEVM yet.\"")))

;; --- labels and copy ------------------------------------------------------------------

(deftest available-on-hyperevm-keeps-the-gas-reserve-test
  (let [transfer (:transfer (vm (support/state (assoc evm->spot :transfer-asset support/hype-index))))]
    (is (= "12.50 HYPE" (get-in transfer [:balances :from :before])) "the balance itself")
    (is (= "12.499 HYPE" (get-in transfer [:balances :from :available]))
        "what MAX and validation allow, as the Balances table shows it")))

(deftest a-blank-amount-is-not-an-error-on-any-route-test
  (let [legacy (vm (support/state {:to-perp? true :amount-input ""}))]
    (is (nil? (get-in legacy [:transfer :message]))
        "a freshly opened Perps <-> Spot form shows no red error")
    (is (= "Enter a valid amount." (:status-message legacy)) "the preview itself is unchanged"))
  (is (= "Enter a valid amount."
         (get-in (vm (support/state {:to-perp? true :amount-input "abc"})) [:transfer :message]))
      "a typed amount that is not a number still says so"))

(deftest a-one-step-run-counts-no-steps-test
  (let [run (fn [route]
              {:phase :running :flow-id "f" :route route :arrival :idle
               :steps (if (= :core->evm route)
                        [{:id :sign :label "Move 1 PURR to HyperEVM" :status :active
                          :detail "Confirm in your wallet · no network switch"}]
                        [{:id :switch-network :label "Switch" :status :active :detail "x"}
                         {:id :send :label "Send" :status :pending :detail "y"}])})]
    (is (= "Waiting for wallet"
           (get-in (vm (support/state (assoc spot->evm :transfer-asset support/purr-index
                                             :transfer-evm (run :core->evm))))
                   [:transfer :actions :submit-label])))
    (is (= "Waiting for wallet (step 1 of 2)"
           (get-in (vm (support/state (assoc evm->spot :transfer-asset support/hype-index
                                             :transfer-evm (run :evm->core))))
                   [:transfer :actions :submit-label])))))

(deftest a-failed-step-says-why-test
  (let [request {:route :evm->core :action {:kind "native"}
                 :evm {:amount "10" :symbol "HYPE"}}
        run (transfer-run/start-run "f" request support/now-ms)
        step-detail (fn [failed-run]
                      (:detail (some #(when (= :failed (:status %)) %) (:steps failed-run))))]
    (is (= "Rejected in wallet"
           (step-detail (transfer-run/failed run :send (:transfer-rejected transfer-run/messages) nil))))
    (is (= "Reverted on HyperEVM"
           (step-detail (transfer-run/failed run :send (:reverted transfer-run/messages) nil))))
    (is (= "May have been sent — check your wallet"
           (step-detail (transfer-run/failed run :send (:no-hash transfer-run/messages) nil
                                             {:maybe-sent? true}))))
    (is (= "Didn't finish"
           (step-detail (transfer-run/failed run :send "Couldn't read your wallet's network" nil))))
    (is (not-any? #(= "Confirm in your wallet" (step-detail (transfer-run/failed run :send % nil)))
                  (vals transfer-run/messages)))))
