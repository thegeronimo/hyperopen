(ns hyperopen.portfolio.optimizer.vault-funding-actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.actions.vault-funding :as actions]
            [hyperopen.portfolio.optimizer.application.vault-funding.model :as model]
            [hyperopen.portfolio.optimizer.application.vault-funding.run :as run]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.schema.vault-transfer-contracts :as vault-transfer-contracts]))

(def owner "0x9999999999999999999999999999999999999999")
(def va "0x1111111111111111111111111111111111111111")
(def vb "0x2222222222222222222222222222222222222222")
(def vc "0x3333333333333333333333333333333333333333")

(def now 1800000000000)

(def signature {:input-signature "sig-1" :scenario-id nil})

(defn base-state
  []
  (-> {:wallet {:address owner :agent {:status :ready}}
       :router {:path "/portfolio/optimize/draft"}
       :account {:mode :classic}
       :webdata2 {:clearinghouseState {:withdrawable "24180"}}
       :vaults {:user-equities [{:vault-address vb :equity 6000}]
                :user-equity-by-address {vb {:vault-address vb :equity 6000 :locked-until-ms nil}}
                :user-equities-for-address owner}}
      (assoc-in contracts/last-successful-run-path
                {:request-signature signature
                 :result {:status :solved
                          :instrument-ids [(str "vault:" va) (str "vault:" vb) (str "vault:" vc)]
                          :target-weights [0.5 0.3 0.2]
                          :labels-by-instrument {(str "vault:" va) "Alpha"
                                                 (str "vault:" vb) "Beta"
                                                 (str "vault:" vc) "Gamma"}}})
      (assoc-in contracts/run-state-path {:status :succeeded :request-signature signature})))

(defn apply-saves
  [state effects]
  (reduce (fn [s [id & args]]
            (case id
              :effects/save (assoc-in s (first args) (second args))
              :effects/save-many (reduce (fn [s* [path value]] (assoc-in s* path value)) s (first args))
              s))
          state
          effects))

(defn- with-amount
  [state amount]
  (apply-saves state (actions/set-portfolio-optimizer-vault-funding-field state :amount-input amount)))

(defn reviewed
  "State after the user pressed Review."
  [state]
  (apply-saves state (actions/portfolio-optimizer-vault-funding-command state :review now)))

(deftest open-fetches-holdings-and-shows-the-plan-test
  (let [effects (actions/portfolio-optimizer-vault-funding-command (base-state) :open now)
        after (apply-saves (base-state) effects)]
    (is (some #{[:effects/api-fetch-user-vault-equities owner]} effects))
    (is (true? (:open? (model/options after))))
    (is (= :plan (:screen (model/options after)))))
  (is (= [] (actions/portfolio-optimizer-vault-funding-command
             (assoc-in (base-state) (conj contracts/last-successful-run-result-path :instrument-ids) ["perp:BTC"])
             :open now))
      "a run without vaults has nothing to fund"))

(deftest plan-follows-the-owner-rules-test
  (let [state (with-amount (base-state) "4,000")
        funding (model/funding-model state (get-in state contracts/last-successful-run-result-path) now)
        rows (into {} (map (juxt :address identity)) (get-in funding [:plan :rows]))]
    (is (= 4000 (:amount funding)) "commas are read")
    (is (= 24180 (:available funding)))
    (testing "Beta holds 6,000 of a 10,000 total but targets 30%: 3,000 comes out"
      (is (= :withdraw (get-in rows [vb :status])))
      (is (= 3000 (get-in rows [vb :withdraw]))))
    (is (= 5000 (get-in rows [va :deposit])))
    (is (= 2000 (get-in rows [vc :deposit])))
    (is (:ready? funding))))

(deftest fraction-buttons-use-spendable-usdc-test
  (let [after (apply-saves (base-state) (actions/set-portfolio-optimizer-vault-funding-field (base-state) :amount-fraction 0.5))]
    (is (= "12,090.00" (:amount-input (model/options after))))))

(deftest typed-amount-is-reformatted-on-blur-test
  (let [blurred (fn [text locale]
                  (let [state (assoc-in (base-state) [:ui :locale] locale)]
                    (:amount-input (model/options (apply-saves state (actions/set-portfolio-optimizer-vault-funding-field state :amount-format text))))))]
    (is (= "20,000.00" (blurred "20000" "en-US")))
    (is (= "1,234.50" (blurred "1234.5" "en-US")))
    (is (= "20.000,00" (blurred "20000" "de-DE")))
    (is (= "" (blurred "abc" "en-US")) "unreadable text is left for the user to fix")))

(deftest review-needs-a-sendable-plan-test
  (let [blocked (actions/portfolio-optimizer-vault-funding-command
                 (assoc-in (base-state) [:vaults :user-equities-for-address] nil) :review now)]
    (is (= [[:effects/save (conj contracts/ui-vault-funding-path :error) "Loading your vault holdings…"]] blocked)
        "holdings must be known before existing deposits can count"))
  (is (= :review (get-in (apply-saves (base-state) (actions/portfolio-optimizer-vault-funding-command (with-amount (base-state) "100") :review now))
                         (conj contracts/ui-vault-funding-path :screen)))))

(deftest confirm-starts-a-run-test
  (let [state (reviewed (with-amount (base-state) "4000"))
        effects (actions/portfolio-optimizer-vault-funding-command state :confirm now)
        after (apply-saves state effects)
        current (model/run-state after)]
    (is (= [:effects/run-portfolio-optimizer-vault-funding] (last effects)))
    (is (= :running (:status current)))
    (is (= [:withdraw :deposit :deposit] (mapv :kind (:rows current))) "withdrawals first")
    (is (= [3000000000 5000000000 2000000000] (mapv :usd-micros (:rows current))))
    (is (every? #(vault-transfer-contracts/vault-transfer-action-valid? (run/transfer-action %)) (:rows current))
        "every row builds a contract-valid vaultTransfer action")
    (is (= :progress (:screen (model/options after))))
    (is (= owner (:owner current)) "the run remembers the wallet that signs it")
    (is (= (model/source-id state) (:source current)))))

(deftest confirm-sends-only-what-was-reviewed-test
  (let [state (reviewed (with-amount (base-state) "4000"))
        moved (assoc-in state [:vaults :user-equity-by-address vb :equity] 6500)
        effects (actions/portfolio-optimizer-vault-funding-command moved :confirm now)
        after (apply-saves moved effects)]
    (is (not-any? #(= :effects/run-portfolio-optimizer-vault-funding (first %)) effects)
        "holdings moved between Review and Confirm: nothing is sent")
    (is (re-find #"plan changed" (str (get-in after (conj contracts/ui-vault-funding-path :error)))))
    (is (= :review (:screen (model/options after))) "the new plan is shown for review")
    (is (= [:effects/run-portfolio-optimizer-vault-funding]
           (last (actions/portfolio-optimizer-vault-funding-command after :confirm now)))
        "confirming the re-reviewed plan sends it")))

(deftest amounts-follow-the-number-locale-test
  (is (= 1000.5 (model/parse-amount "1,000.50" "en-US")))
  (is (= 1000.5 (model/parse-amount (str "1\u202F000,50") "fr-FR")) "a decimal comma is never read as a thousands mark")
  (is (= 1000.5 (model/parse-amount "1000,50" "fr-FR")))
  (is (nil? (model/parse-amount "abc" "en-US")))
  (is (= "12\u202F090,00" (model/format-amount-input 12090 "fr-FR")))
  (is (= 12090 (model/parse-amount (model/format-amount-input 12090 "fr-FR") "fr-FR")) "what is shown reads back")
  (is (= 1234567.89 (model/parse-amount (model/format-amount-input 1234567.89 "en-US") "en-US"))))

(deftest unknown-balance-blocks-the-plan-test
  (let [state (with-amount (assoc (base-state) :webdata2 {}) "100")]
    (is (= "Loading your USDC balance…"
           (:block (model/funding-model state (get-in state contracts/last-successful-run-result-path) now))))))

(deftest confirm-gates-test
  (let [state (with-amount (base-state) "4000")
        error-of (fn [effects] (some (fn [[id path v]] (when (and (= id :effects/save) (= path (conj contracts/ui-vault-funding-path :error))) v)) effects))]
    (testing "spectating another wallet is read-only"
      (is (re-find #"(?i)spectate|read-only"
                   (str (error-of (actions/portfolio-optimizer-vault-funding-command
                                   (assoc-in state [:account-context :spectate-mode] {:active? true :address vc}) :confirm now))))))
    (testing "a changed setup must be rerun first"
      (is (re-find #"Rerun" (str (error-of (actions/portfolio-optimizer-vault-funding-command
                                            (assoc-in state (conj contracts/draft-path :metadata :dirty?) true) :confirm now))))))
    (testing "a locked agent unlocks with confirm as the replay"
      (let [effects (actions/portfolio-optimizer-vault-funding-command (assoc-in state [:wallet :agent :status] :locked) :confirm now)]
        (is (some #(= [:effects/unlock-agent-trading
                       {:after-success-actions [[:actions/portfolio-optimizer-vault-funding-command :confirm]]}] %)
                  effects))
        (is (not-any? #(= :effects/run-portfolio-optimizer-vault-funding (first %)) effects))))
    (testing "trading not enabled opens the recovery prompt"
      (let [effects (actions/portfolio-optimizer-vault-funding-command (assoc-in state [:wallet :agent :status] :not-ready) :confirm now)]
        (is (some #{[:effects/save [:wallet :agent :recovery-modal-open?] true]} effects))))
    (testing "spending more than is available is refused"
      (is (re-find #"more USDC" (str (error-of (actions/portfolio-optimizer-vault-funding-command (with-amount state "90000") :confirm now))))))))

(deftest stop-and-retry-test
  (let [ready (reviewed (with-amount (base-state) "4000"))
        state (apply-saves ready (actions/portfolio-optimizer-vault-funding-command ready :confirm now))]
    (is (= [[:effects/save (conj contracts/ui-vault-funding-run-path :abort-requested?) true]]
           (actions/portfolio-optimizer-vault-funding-command state :stop now)))
    (is (= [] (actions/set-portfolio-optimizer-vault-funding-field state :amount-input "1")) "the plan is frozen while sending")
    (let [failed (assoc-in state contracts/ui-vault-funding-run-path
                           (-> (model/run-state state)
                               (run/mark-done (str "withdraw:" vb))
                               (run/mark-failed (str "deposit:" va) "boom")
                               (run/mark-skipped (str "deposit:" vc) :aborted)
                               (run/finish now)))
          effects (actions/portfolio-optimizer-vault-funding-command failed :retry (inc now))
          after (apply-saves failed effects)]
      (is (= :partial (:status (model/run-state failed))))
      (is (= [:done :queued :queued] (mapv :status (:rows (model/run-state after)))) "landed rows are not resent")
      (is (= [:effects/run-portfolio-optimizer-vault-funding] (last effects)))
      (testing "an unconfirmed transfer is never resent"
        (let [unknown (update-in failed contracts/ui-vault-funding-run-path
                                 #(run/mark-unknown % (str "deposit:" va) "network"))
              after* (apply-saves unknown (actions/portfolio-optimizer-vault-funding-command unknown :retry (inc now)))]
          (is (= [:done :unknown :queued] (mapv :status (:rows (model/run-state after*)))))))
      (testing "retry refuses a run planned for another wallet or another result"
        (doseq [moved [(assoc-in failed [:wallet :address] "0x8888888888888888888888888888888888888888")
                       (assoc-in failed (conj contracts/last-successful-run-path :computed-at-ms) 42)]]
          (is (not-any? #(= :effects/run-portfolio-optimizer-vault-funding (first %))
                        (actions/portfolio-optimizer-vault-funding-command moved :retry (inc now))))))
      (testing "reopening after the allocation changed starts a fresh plan"
        (let [moved (assoc-in failed (conj contracts/last-successful-run-path :computed-at-ms) 42)
              after* (apply-saves moved (actions/portfolio-optimizer-vault-funding-command moved :open (inc now)))]
          (is (nil? (model/run-state after*)))
          (is (= :plan (:screen (model/options after*))))))
      (is (= [[:effects/save (conj contracts/ui-vault-funding-path :open?) false]]
             (actions/portfolio-optimizer-vault-funding-command failed "Escape" now))
          "Escape closes the sheet"))))

(deftest run-transitions-test
  (let [rows [{:row-id "withdraw:b" :kind :withdraw :status :queued}
              {:row-id "deposit:a" :kind :deposit :status :queued}]
        r (run/new-run rows "id" 1)]
    (is (= :send (run/next-action r (first rows))))
    (testing "a failed withdrawal holds the deposits back"
      (let [r* (run/mark-failed r "withdraw:b" "x")]
        (is (= :skip-halted (run/next-action r* (second (:rows r*)))))))
    (is (= :skip-aborted (run/next-action (assoc r :abort-requested? true) (first rows))))
    (is (= :failed (run/final-status [{:status :failed} {:status :skipped}])))
    (is (= :done (run/final-status [{:status :done} {:status :done}])))
    (is (= :partial (run/final-status [{:status :done} {:status :failed}])))))
