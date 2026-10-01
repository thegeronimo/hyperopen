(ns hyperopen.funding.domain.transfer-run-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.domain.transfer-run :as transfer-run]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]))

(def ^:private hash-1
  "0x1111111111111111111111111111111111111111111111111111111111111111")

(defn- request
  [route kind index symbol amount]
  {:route route
   :action {:kind kind}
   :evm {:amount amount :symbol symbol :token-index index :owner support/owner :from :spot}})

(def ^:private usdc-in (request :evm->core "usdcCoreDeposit" support/usdc-index "USDC" "1000"))
(def ^:private hype-in (request :evm->core "native" support/hype-index "HYPE" "10"))
(def ^:private purr-out (request :core->evm nil support/purr-index "PURR" "100"))

(deftest steps-follow-the-route-and-token-test
  (is (= [[:sign "Move 100 PURR to HyperEVM" :active]]
         (mapv (juxt :id :label :status) (transfer-run/initial-steps purr-out))))
  (is (= [[:switch-network "Switch wallet to HyperEVM" :active]
          [:send "Send 10 HYPE to Spot" :pending]]
         (mapv (juxt :id :label :status) (transfer-run/initial-steps hype-in))))
  (is (= [[:switch-network :active "Chain 999 · trading still works on any network"]
          [:approve :pending "Confirm in your wallet"]
          [:deposit :pending "Second wallet confirmation"]]
         (mapv (juxt :id :status :detail) (transfer-run/initial-steps usdc-in))))
  (is (= "Approve 1,000 USDC" (:label (second (transfer-run/initial-steps usdc-in))))
      "step amounts are grouped like the run's heading"))

(deftest step-events-move-one-step-test
  (let [run (transfer-run/start-run "f" usdc-in 5)
        run* (-> run
                 (transfer-run/apply-step-event :switch-network :done)
                 (transfer-run/apply-step-event :approve :skipped)
                 (transfer-run/apply-step-event :deposit :confirming))]
    (is (= [[:switch-network :done "Wallet on HyperEVM"]
            [:approve :skipped "Approve 1,000 USDC"]
            [:deposit :active "Deposit to HyperCore Spot"]]
           (mapv (juxt :id :status :label) (:steps run*))))
    (is (= ["Chain 999 · trading still works on any network" "Already approved"
            transfer-run/confirming-detail]
           (mapv :detail (:steps run*))))
    (is (= "Confirming on HyperEVM…" transfer-run/confirming-detail))
    (is (= run (transfer-run/apply-step-event run :nope :done)))
    (is (= run (transfer-run/apply-step-event run :approve :nope)))))

(deftest run-phases-test
  (let [run (transfer-run/start-run "f" hype-in 5)]
    (is (= {:phase :running :flow-id "f" :route :evm->core :arrival :idle :started-at-ms 5}
           (select-keys run [:phase :flow-id :route :arrival :started-at-ms])))
    (is (= {:amount "10" :symbol "HYPE" :from :hyperevm :to :spot :owner support/owner
            :started-at-ms 5 :sent-at-ms nil :arrived-at-ms nil}
           (:result run)))
    (is (false? (:maybe-sent? run)))
    (is (= 12 (get-in (transfer-run/succeeded run hash-1 12) [:result :sent-at-ms]))
        "arrival counts from when the move went through, not from the wallet prompt")
    (let [done (transfer-run/succeeded run hash-1)]
      (is (= [:succeeded :arriving hash-1 (str "https://hyperevmscan.io/tx/" hash-1)]
             ((juxt :phase :arrival :tx-hash :tx-url) done)))
      (is (every? #(= :done (:status %)) (:steps done)))
      (is (= :arrived (:arrival (transfer-run/arrived done 9))))
      (is (= 9 (get-in (transfer-run/arrived done 9) [:result :arrived-at-ms])))
      (is (= :slow (:arrival (transfer-run/slow done))))
      (is (= :arrived (:arrival (transfer-run/slow (transfer-run/arrived done 9))))
          "an arrived run never turns slow"))
    (let [failed (transfer-run/failed run :send "Transfer rejected in wallet." nil)]
      (is (= [:failed "Transfer rejected in wallet." false]
             ((juxt :phase :error :maybe-sent?) failed)))
      (is (= [:pending :failed] (mapv :status (:steps failed)))
          "a step still active when another one fails goes back to pending"))
    (is (= [:pending :pending] (mapv :status (:steps (transfer-run/failed run nil "x" nil))))
        "a read that failed before the wallet was asked fails no step")
    (is (true? (:maybe-sent? (transfer-run/failed run :sign "x" nil {:maybe-sent? true})))
        "a failure whose outcome is unknown says so")
    (let [done (transfer-run/succeeded run hash-1)]
      (is (= done (transfer-run/failed done :send "late error" nil))
          "a succeeded run never turns failed"))
    (let [pending (transfer-run/pending run :send hash-1)]
      (is (= [:pending hash-1] ((juxt :phase :tx-hash) pending)))
      (is (= "Still confirming on HyperEVM" (:detail (second (:steps pending))))))
    (is (= [:failed (:approved-only transfer-run/messages)]
           ((juxt :phase :error) (transfer-run/approval-only (transfer-run/start-run "f" usdc-in 5)))))))

(deftest arrival-plans-and-checks-test
  (let [state (support/state)
        plan (transfer-run/arrival-plan state purr-out)]
    (is (= {:location :hyperevm :token-index support/purr-index :owner support/owner
            :before "50" :expected "100"}
           plan))
    (is (not (transfer-run/arrived? state plan)))
    (is (transfer-run/arrived?
         (assoc-in state [:hyperevm :balances :by-address support/owner :token-units support/purr-index]
                   "150000000000000000000")
         plan)))
  (let [state (support/state)
        plan (transfer-run/arrival-plan state hype-in)]
    (is (= {:location :spot :before "412.08" :expected "10"} (select-keys plan [:location :before :expected])))
    (is (transfer-run/arrived? (assoc-in state [:spot :clearinghouse-state :balances 1 :total] "422.08") plan)))
  (let [state (support/state)
        plan (transfer-run/arrival-plan state hype-in)
        credited (assoc-in state [:spot :clearinghouse-state :balances 1] {:coin "HYPE" :token 150
                                                                          :total "422.08" :hold "300"})]
    (is (transfer-run/arrived? credited plan)
        "an order placed meanwhile holds part of the balance; the credit still counts")
    (is (not (transfer-run/arrived?
              (assoc credited :account-context {:spectate-mode {:active? true :address support/subaccount}})
              plan))
        "Spot is judged only while it shows the owner's balances")
    (is (nil? (:before (transfer-run/arrival-plan
                        (assoc state :account-context {:spectate-mode {:active? true
                                                                       :address support/subaccount}})
                        hype-in)))))
  (let [missing (assoc-in (support/state) [:hyperevm :core-account support/owner] :missing)]
    (is (= "999" (:expected (transfer-run/arrival-plan missing usdc-in)))
        "a first USDC move pays the 1 USDC activation fee"))
  (let [unknown (assoc-in (support/state) [:spot :clearinghouse-state] nil)
        plan (transfer-run/arrival-plan unknown hype-in)]
    (is (nil? (:before plan)))
    (is (not (transfer-run/arrived? (support/state) plan))
        "an unknown starting balance never reads as arrived")))
