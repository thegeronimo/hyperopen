(ns hyperopen.portfolio.optimizer.application.vault-funding.model
  "Read model of the Fund-this-allocation sheet: the run's vault legs, the
  user's vault holdings and spendable USDC, the plan, and every reason the
  plan cannot be sent yet."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.domain.availability :as funding-availability]
            [hyperopen.portfolio.optimizer.application.vault-funding.plan :as plan]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.ids :as ids]
            [hyperopen.vaults.domain.transfer-policy :as transfer-policy]))

(def screens
  #{:plan :review :progress})

(def defaults
  {:open? false
   :screen :plan
   :amount-input ""
   :include-existing? true
   :withdraw-over? true
   :closed-mode :spread
   :error nil})

(defn funding-state
  [state]
  (get-in state contracts/ui-vault-funding-path))

(defn options
  [state]
  (let [stored (or (funding-state state) {})]
    (cond-> (merge defaults (select-keys stored (keys defaults)))
      (not (contains? screens (:screen stored))) (assoc :screen :plan)
      (not (contains? plan/closed-modes (:closed-mode stored))) (assoc :closed-mode :spread)
      true (update :include-existing? #(not (false? %)))
      true (update :withdraw-over? #(not (false? %))))))

(defn run-state
  [state]
  (get-in state contracts/ui-vault-funding-run-path))

(defn running?
  [state]
  (= :running (:status (run-state state))))

(defn parse-amount
  "Typed amount → USD, read in the user's number locale (\"1.000,50\" in de-DE
  is 1000.5) at USDC precision. Nil when blank or unreadable."
  [text locale]
  (some-> (transfer-policy/parse-usdc-micros (str/replace (str (or text "")) "$" "") locale)
          (/ 1000000)))

(defn format-amount-input
  "USD → what the amount box shows: the user's locale number format with
  grouping and two decimals (\"20,000.00\" in en-US, \"20.000,00\" in de-DE)."
  [usd locale]
  (.toLocaleString (js/Number. (plan/floor-cents usd))
                   (or locale "en-US")
                   #js {:useGrouping true :minimumFractionDigits 2 :maximumFractionDigits 2}))

(defn source-id
  "Identity of the optimizer result a plan comes from: a run planned from one
  result must never be resumed against another."
  [state]
  (let [run (get-in state contracts/last-successful-run-path)]
    [(:computed-at-ms run)
     (get-in run [:request-signature :input-signature])
     (:scenario-id (:result run))]))

(defn owner
  [state]
  (some-> (account-context/owner-address state) str/lower-case))

(defn vault-result?
  [result]
  (boolean (and (= :solved (:status result))
                (some #(ids/vault-address-from-value %) (:instrument-ids result)))))

(defn all-vault-result?
  [result]
  (boolean (and (vault-result? result)
                (every? #(ids/vault-address-from-value %) (:instrument-ids result)))))

(defn- vault-details
  [state address]
  (or (get-in state (conj contracts/history-data-path :vault-details-by-address address))
      (get-in state [:vaults :details-by-address address])))

(defn targets
  "Every vault leg of the run, a 0% leg included (it may hold money to take out)."
  [state result]
  (let [labels (:labels-by-instrument result)]
    (->> (map vector (:instrument-ids result) (:target-weights result))
         (keep (fn [[id weight]]
                 (when-let [address (ids/vault-address-from-value id)]
                   {:id id
                    :address address
                    :label (or (get labels id)
                               (:name (vault-details state address))
                               address)
                    :weight (max 0 (or weight 0))})))
         vec)))

(defn closed-addresses
  "Vaults whose loaded details say they take no deposits from this wallet.
  Unknown details count as open; the exchange's answer then shows per step."
  [state addresses]
  (let [wallet (some-> (get-in state [:wallet :address]) str/lower-case)]
    (into #{}
          (filter (fn [address]
                    (let [details (vault-details state address)
                          name (some-> (:name details) str/trim str/lower-case)]
                      (or (= "liquidator" name)
                          (and (map? details)
                               (false? (:allow-deposits? details))
                               (not= wallet (some-> (:leader details) str/lower-case)))))))
          addresses)))

(defn holdings-status
  "Whether the user's vault holdings are known for the account being funded:
  :ready, :loading, :error or :missing (never loaded)."
  [state]
  (let [effective (account-context/effective-account-address state)
        owner (get-in state [:vaults :user-equities-for-address])]
    (cond
      (nil? effective) :missing
      (= effective (get-in state [:vaults :user-equities-error-for-address])) :error
      (and (= effective owner)
           (vector? (get-in state [:vaults :user-equities]))
           (not (get-in state [:vaults :loading :user-equities?]))) :ready
      (get-in state [:vaults :loading :user-equities?]) :loading
      :else :missing)))

(defn account-block
  "Why this account cannot send transfers right now, or nil."
  [state]
  (let [blocked (account-context/mutations-blocked-message state)
        owner (some-> (account-context/owner-address state) str/lower-case)
        effective (some-> (account-context/effective-account-address state) str/lower-case)]
    (cond
      blocked blocked
      (nil? owner) "Connect your wallet to fund vaults."
      (not= owner effective) "Switch to your main account to fund vaults; transfers are signed by it."
      :else nil)))

(defn- spot-shortfall
  "On a classic account, USDC sitting in spot that would cover the gap."
  [state amount available]
  (when (and (= :classic (get-in state [:account :mode]))
             (number? amount) (number? available) (> amount available))
    (let [spot (funding-availability/spot-usdc-available state)]
      (when (and (number? spot) (>= (+ available spot) amount))
        {:spot spot :needed (- amount available)}))))

(defn funding-model
  [state result now-ms]
  (let [opts (options state)
        legs (targets state result)
        holdings-status* (holdings-status state)
        include? (:include-existing? opts)
        holdings (when (= :ready holdings-status*)
                   (get-in state [:vaults :user-equity-by-address]))
        available (transfer-policy/deposit-available-usdc state)
        amount (parse-amount (:amount-input opts) (get-in state [:ui :locale]))
        closed (closed-addresses state (map :address legs))
        funding-plan (plan/build-plan {:targets legs
                                       :holdings (or holdings {})
                                       :amount (or amount 0)
                                       :available available
                                       :include-existing? include?
                                       :withdraw-over? (:withdraw-over? opts)
                                       :closed closed
                                       :closed-mode (:closed-mode opts)
                                       :now-ms now-ms})
        held-total (reduce + 0 (keep :equity (vals holdings)))
        held-count (count (filter #(pos? (or (:equity (get holdings (:address %))) 0)) legs))
        other-held (reduce + 0 (keep (fn [[address {:keys [equity]}]]
                                       (when-not (some #(= address (:address %)) legs) equity))
                                     holdings))
        holdings-pending? (and include? (not= :ready holdings-status*))
        unreadable (when (and include? holdings)
                     (some (fn [{:keys [address label]}]
                             (let [row (get holdings address)]
                               (when (and row (not (and (number? (:equity row)) (js/isFinite (:equity row)))))
                                 label)))
                           legs))
        open-legs (remove #(contains? closed (:address %)) legs)
        steps (:steps funding-plan)
        account-block* (account-block state)
        block (cond
                account-block* account-block*
                holdings-pending? (case holdings-status*
                                    :error "Couldn't load your vault holdings. Close and reopen to retry."
                                    "Loading your vault holdings…")
                unreadable (str "Couldn't read your holding in " unreadable ". Close and reopen to retry.")
                (some #{:no-vaults} (:errors funding-plan)) "This run holds no vaults."
                (and amount (pos? amount) (not (number? available))) "Loading your USDC balance…"
                (some #{:over-available} (:errors funding-plan)) "That is more USDC than you can spend on deposits."
                (seq steps) nil
                (and (empty? open-legs) (pos? (or amount 0))) "Every vault in this run is closed to deposits."
                (some #(= :too-small (:status %)) (:rows funding-plan)) "Amount too small: each vault would get under $1."
                (and amount (pos? amount)) "Nothing to send: every vault is already on target."
                :else "Enter an amount to add.")]
    {:options opts
     :legs legs
     :plan funding-plan
     :amount amount
     :available available
     :account-mode (get-in state [:account :mode])
     :holdings-status holdings-status*
     :held-total (- held-total other-held)
     :held-count held-count
     :other-held other-held
     :closed closed
     :spot-shortfall (spot-shortfall state amount available)
     :mixed? (not (all-vault-result? result))
     :agent-ready? (= :ready (get-in state [:wallet :agent :status]))
     :block block
     :ready? (nil? block)}))
