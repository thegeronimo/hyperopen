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
  #{:plan :progress})

(def defaults
  {:open? false
   :screen :plan
   :amount-input ""
   :split :target
   :include-existing? true
   :withdraw-over? true
   :closed-mode :spread
   :skipped #{}
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
      (not (contains? plan/splits (:split stored))) (assoc :split :target)
      (not (set? (:skipped stored))) (assoc :skipped #{})
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

(def leader-min-fraction
  "Hyperliquid refuses a deposit that takes the leader's share below this."
  0.05)

(def min-useful-room-usd
  "A vault with less room than this before its leader hits 5% counts as
  closed: a few dollars is not worth a transfer."
  10)

(def ^:private limit-margin
  ;; Stay a little under the exact limit: shares move with every deposit and
  ;; withdrawal between the read and the send.
  0.98)

(defn deposit-limit
  "What a vault will accept, from its vaultDetails: whether deposits are on,
  its leader, and the most a follower can deposit before the leader's share
  falls under 5% (nil when the leader's equity is not listed, and for a
  protocol vault such as HLP, whose leader is exempt from the 5% rule)."
  [details]
  (let [fraction (:leader-fraction details)
        protocol? (= :parent (get-in details [:relationship :type]))
        leader-equity (some #(when (:leader? %) (:vault-equity %)) (:followers details))]
    {:allow-deposits? (:allow-deposits? details)
     :leader (some-> (:leader details) str str/lower-case)
     :leader-fraction fraction
     :max-deposit (when (and (not protocol?) (number? fraction) (pos? fraction) (number? leader-equity) (pos? leader-equity))
                    (max 0 (* limit-margin leader-equity (- (/ 1 leader-min-fraction) (/ 1 fraction)))))}))

(defn limits
  "The sheet's fresh per-vault deposit limits: {:status :by-address}."
  [state]
  (:limits (funding-state state)))

(defn- wallet
  [state]
  (some-> (get-in state [:wallet :address]) str/lower-case))

(defn- limit-for
  "This wallet's limit for the vault: none when it leads the vault (its own
  deposits raise the leader's share)."
  [state address]
  (let [limit (get-in (limits state) [:by-address address])]
    (when (and limit (not= (wallet state) (:leader limit)))
      limit)))

(defn targets
  "Every vault leg of the run, a 0% leg included (it may hold money to take out)."
  [state result]
  (let [labels (:labels-by-instrument result)]
    (->> (map vector (:instrument-ids result) (:target-weights result))
         (keep (fn [[id weight]]
                 (when-let [address (ids/vault-address-from-value id)]
                   (let [details-name (:name (vault-details state address))]
                     {:id id
                      :address address
                      :label (or (get labels id) details-name address)
                      :weight (max 0 (or weight 0))
                      :lock-days (transfer-policy/deposit-lockup-days address details-name)
                      :max-deposit (:max-deposit (limit-for state address))}))))
         vec)))

(defn closed-reasons
  "{address reason} for vaults that take no deposits from this wallet:
  :deposits-off (the leader turned them off) or :leader-floor (the leader's
  share is at Hyperliquid's 5% minimum). Fresh limits win over the details
  loaded with the run's history; unknown counts as open, and the exchange's
  answer then shows per step."
  [state addresses]
  (let [wallet* (wallet state)]
    (into {}
          (keep (fn [address]
                  (let [details (vault-details state address)
                        limit (limit-for state address)
                        name (some-> (:name details) str/trim str/lower-case)
                        allow? (if limit (:allow-deposits? limit) (:allow-deposits? details))
                        leader (or (:leader limit) (some-> (:leader details) str/lower-case))]
                    (cond
                      (= "liquidator" name) [address :deposits-off]
                      (and (false? allow?) (not= wallet* leader)) [address :deposits-off]
                      (and limit (number? (:max-deposit limit)) (< (:max-deposit limit) min-useful-room-usd))
                      [address :leader-floor]
                      :else nil))))
          addresses)))

(defn closed-addresses
  [state addresses]
  (set (keys (closed-reasons state addresses))))

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
        closed-reasons* (closed-reasons state (map :address legs))
        closed (set (keys closed-reasons*))
        skipped (into #{} (filter (:skipped opts)) (map :address legs))
        plan-for (fn [split]
                   (plan/build-plan {:targets legs
                                     :split split
                                     :holdings (or holdings {})
                                     :amount (or amount 0)
                                     :available available
                                     :include-existing? include?
                                     :withdraw-over? (:withdraw-over? opts)
                                     :closed closed
                                     :skipped skipped
                                     :closed-mode (:closed-mode opts)
                                     :now-ms now-ms}))
        plans (into {} (map (juxt identity plan-for)) plan/splits)
        funding-plan (get plans (:split opts))
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
        open-legs (remove #(or (contains? closed (:address %)) (contains? skipped (:address %))) legs)
        steps (:steps funding-plan)
        account-block* (account-block state)
        block (cond
                account-block* account-block*
                holdings-pending? (case holdings-status*
                                    :error "Couldn't load your vault holdings. Close and reopen to retry."
                                    "Loading your vault holdings…")
                unreadable (str "Couldn't read your holding in " unreadable ". Close and reopen to retry.")
                (= :loading (:status (limits state))) "Checking each vault's deposit limit…"
                (some #{:no-vaults} (:errors funding-plan)) "This run holds no vaults."
                (and amount (pos? amount) (not (number? available))) "Loading your USDC balance…"
                (some #{:over-available} (:errors funding-plan)) "That is more USDC than you can spend on deposits."
                (seq steps) nil
                (and (empty? open-legs) (pos? (or amount 0))) "Every vault is skipped or closed to deposits."
                (some #(= :too-small (:status %)) (:rows funding-plan)) "Amount too small: each vault would get under $1."
                (and amount (pos? amount)) "Nothing to send: every vault is already on target."
                :else "Enter an amount to add.")]
    {:options opts
     :legs legs
     :plan funding-plan
     :splits (into {}
                   (map (fn [[split p]]
                          [split {:weights (mapv :weight (:rows p))
                                  :withdrawals (count (filter #(pos? (:withdraw %)) (:rows p)))
                                  :deposits (count (filter #(pos? (:deposit %)) (:rows p)))
                                  :drift-after (:drift-after p)
                                  :vaults (count (filter #(pos? (:weight %)) (:rows p)))}]))
                   plans)
     :amount amount
     :available available
     :account-mode (get-in state [:account :mode])
     :holdings-status holdings-status*
     :held-total (- held-total other-held)
     :held-count held-count
     :other-held other-held
     :closed closed
     :closed-reasons closed-reasons*
     :skipped skipped
     :spot-shortfall (spot-shortfall state amount available)
     :mixed? (not (all-vault-result? result))
     :agent-ready? (= :ready (get-in state [:wallet :agent :status]))
     :block block
     :ready? (nil? block)}))
