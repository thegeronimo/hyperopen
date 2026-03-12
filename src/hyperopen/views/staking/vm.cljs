(ns hyperopen.views.staking.vm
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.staking.actions :as staking-actions]))

(def timeframe-options
  [{:value :day :label "1D"}
   {:value :week :label "7D"}
   {:value :month :label "30D"}])

(def validator-page-size
  25)

(defn- finite-number?
  [value]
  (and (number? value)
       (js/isFinite value)
       (not (js/isNaN value))))

(defn- optional-number
  [value]
  (cond
    (number? value)
    (when (finite-number? value)
      value)

    (string? value)
    (let [parsed (js/Number (str/trim value))]
      (when (finite-number? parsed)
        parsed))

    :else
    nil))

(defn- normalize-validator-address
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (seq text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn- normalize-validator-name
  [row]
  (let [name* (some-> (:name row) str str/trim)]
    (if (seq name*)
      name*
      (or (:validator row)
          "Unknown validator"))))

(defn- validator-status
  [row]
  (cond
    (true? (:is-jailed? row)) :jailed
    (true? (:is-active? row)) :active
    :else :inactive))

(defn- stats-for-timeframe
  [row timeframe]
  (let [stats (or (:stats row) {})
        stats* (or (get stats timeframe)
                   (get stats (name timeframe))
                   {})]
    {:uptime-fraction (optional-number (:uptime-fraction stats*))
     :predicted-apr (optional-number (:predicted-apr stats*))
     :sample-count (optional-number (:sample-count stats*))}))

(defn- delegation-map
  [delegations]
  (reduce (fn [acc row]
            (let [validator (normalize-validator-address (:validator row))
                  amount (optional-number (:amount row))]
              (if (and (seq validator)
                       (finite-number? amount))
                (assoc acc validator amount)
                acc)))
          {}
          (or delegations [])))

(defn- compare-string-ci
  [left right]
  (compare (str/lower-case (str (or left "")))
           (str/lower-case (str (or right "")))))

(defn- status-rank
  [status]
  (case status
    :active 3
    :inactive 2
    :jailed 1
    0))

(defn- compare-validators
  [column left right]
  (case column
    :name (compare-string-ci (:name left) (:name right))
    :description (compare-string-ci (:description left) (:description right))
    :stake (compare (or (:stake left) 0) (or (:stake right) 0))
    :your-stake (compare (or (:your-stake left) 0) (or (:your-stake right) 0))
    :uptime (compare (or (:uptime-fraction left) 0) (or (:uptime-fraction right) 0))
    :apr (compare (or (:predicted-apr left) 0) (or (:predicted-apr right) 0))
    :status (compare (status-rank (:status left)) (status-rank (:status right)))
    :commission (compare (or (:commission left) 0) (or (:commission right) 0))
    (compare (or (:stake left) 0) (or (:stake right) 0))))

(defn- sort-validators
  [validator-sort rows]
  (let [{:keys [column direction]} (staking-actions/normalize-staking-validator-sort validator-sort)]
    (vec (sort (fn [left right]
                 (let [cmp (compare-validators column left right)
                       cmp* (if (zero? cmp)
                              (compare-string-ci (:name left) (:name right))
                              cmp)]
                   (if (= :desc direction)
                     (- cmp*)
                     cmp*)))
               (or rows [])))))

(defn- validators-vm
  [validator-summaries delegations timeframe validator-sort]
  (let [delegation-by-validator (delegation-map delegations)]
    (->> validator-summaries
         (mapv (fn [row]
                 (let [validator (normalize-validator-address (:validator row))
                       stats (stats-for-timeframe row timeframe)]
                   {:validator validator
                    :name (normalize-validator-name row)
                    :description (some-> (:description row) str str/trim)
                    :stake (optional-number (:stake row))
                    :your-stake (get delegation-by-validator validator 0)
                    :uptime-fraction (:uptime-fraction stats)
                    :predicted-apr (:predicted-apr stats)
                    :sample-count (:sample-count stats)
                    :status (validator-status row)
                    :commission (optional-number (:commission row))})))
         (sort-validators validator-sort))))

(defn- reward-rows
  [rows]
  (->> (or rows [])
       (map (fn [row]
              {:time-ms (optional-number (:time-ms row))
               :source (some-> (:source row) name)
               :total-amount (optional-number (:total-amount row))}))
       (sort-by :time-ms >)
       vec))

(defn- history-kind-label
  [delta]
  (case (:kind delta)
    :delegate "Stake"
    :undelegate "Unstake"
    :deposit "Transfer In"
    :withdraw "Transfer Out"
    :withdrawal "Withdrawal"
    :unknown "Unknown"
    "Unknown"))

(defn- history-amount
  [delta]
  (optional-number (:amount delta)))

(defn- history-rows
  [rows]
  (->> (or rows [])
       (map (fn [row]
              (let [delta (:delta row)]
                {:time-ms (optional-number (:time-ms row))
                 :hash (:hash row)
                 :kind (history-kind-label delta)
                 :amount (history-amount delta)
                 :status (some-> (:phase delta) name)})))
       (sort-by :time-ms >)
       vec))

(defn- spot-hype-available
  [state]
  (some (fn [row]
          (when (= "HYPE"
                   (some-> (:coin row) str str/trim str/upper-case))
            (or (optional-number (:available row))
                (optional-number (:availableBalance row))
                (let [total (optional-number (:total row))
                      hold (optional-number (:hold row))]
                  (cond
                    (and (finite-number? total) (finite-number? hold)) (max 0 (- total hold))
                    (finite-number? total) (max 0 total)
                    :else nil)))))
        (get-in state [:spot :clearinghouse-state :balances])))

(defn staking-vm
  [state]
  (let [active-tab (staking-actions/normalize-staking-tab
                    (get-in state [:staking-ui :active-tab]))
        timeframe (staking-actions/normalize-staking-validator-timeframe
                   (get-in state [:staking-ui :validator-timeframe]))
        timeframe-dropdown-open? (true? (get-in state [:staking-ui :validator-timeframe-dropdown-open?]))
        validator-sort (staking-actions/normalize-staking-validator-sort
                        (get-in state [:staking-ui :validator-sort]))
        popover-state (or (get-in state [:staking-ui :action-popover]) {})
        popover-kind (staking-actions/normalize-staking-action-popover-kind
                      (:kind popover-state))
        popover-open? (and (true? (:open? popover-state))
                           (some? popover-kind))
        transfer-direction (staking-actions/normalize-staking-transfer-direction
                            (get-in state [:staking-ui :transfer-direction]))
        validator-summaries (or (get-in state [:staking :validator-summaries]) [])
        delegator-summary (or (get-in state [:staking :delegator-summary]) {})
        delegations (or (get-in state [:staking :delegations]) [])
        rewards (or (get-in state [:staking :rewards]) [])
        history (or (get-in state [:staking :history]) [])
        validators-all (validators-vm validator-summaries delegations timeframe validator-sort)
        validators-total-count (count validators-all)
        validator-show-all? (true? (get-in state [:staking-ui :validator-show-all?]))
        effective-validator-page-size (if validator-show-all?
                                      (max 1 validators-total-count)
                                      validator-page-size)
        requested-validator-page (or (some-> (get-in state [:staking-ui :validator-page])
                                             optional-number
                                             js/Math.floor
                                             int)
                                    0)
        validator-page-count (max 1
                                  (int (js/Math.ceil (/ validators-total-count
                                                       effective-validator-page-size))))
        validator-page (-> requested-validator-page
                           (max 0)
                           (min (dec validator-page-count)))
        validator-page-start-index (* validator-page effective-validator-page-size)
        validator-page-end-index (min validators-total-count
                                     (+ validator-page-start-index effective-validator-page-size))
        validators (if (pos? validators-total-count)
                     (subvec validators-all validator-page-start-index validator-page-end-index)
                     [])
        total-staked (or (optional-number (:total-staked delegator-summary))
                         (reduce (fn [sum row]
                                   (+ sum (or (optional-number (:stake row)) 0)))
                                 0
                                 validator-summaries))
        your-stake (or (optional-number (:delegated delegator-summary)) 0)
        staking-balance (or (optional-number (:undelegated delegator-summary)) 0)
        pending-withdrawals (or (optional-number (:total-pending-withdrawal delegator-summary)) 0)
        effective-address (account-context/effective-account-address state)
        loading-map (or (get-in state [:staking :loading]) {})
        loading? (boolean (some true? (vals loading-map)))
        errors (or (get-in state [:staking :errors]) {})
        route-error (or (:validator-summaries errors)
                        (:delegator-summary errors)
                        (:delegations errors)
                        (:rewards errors)
                        (:history errors))]
    {:connected? (true? (get-in state [:wallet :connected?]))
     :effective-address effective-address
     :active-tab active-tab
     :tabs [{:value :validator-performance :label "Validator Performance"}
            {:value :staking-reward-history :label "Staking Reward History"}
            {:value :staking-action-history :label "Staking Action History"}]
     :validator-timeframe timeframe
     :validator-timeframe-dropdown-open? timeframe-dropdown-open?
     :validator-page validator-page
     :validator-show-all? validator-show-all?
     :validator-page-size validator-page-size
     :validator-page-count validator-page-count
     :validators-total-count validators-total-count
     :validator-page-range-start (if (pos? validators-total-count)
                                   (inc validator-page-start-index)
                                   0)
     :validator-page-range-end validator-page-end-index
     :validator-sort validator-sort
     :timeframe-options timeframe-options
     :loading? loading?
     :error (or (get-in state [:staking-ui :form-error]) route-error)
     :summary {:total-staked total-staked
               :your-stake your-stake
               :staking-balance staking-balance}
     :balances {:available-transfer (or (spot-hype-available state) 0)
                :available-stake staking-balance
                :total-staked your-stake
                :pending-withdrawals pending-withdrawals}
     :validators validators
     :rewards (reward-rows rewards)
     :history (history-rows history)
     :action-popover {:open? popover-open?
                      :kind popover-kind
                      :anchor (when popover-open?
                                (:anchor popover-state))
                      :transfer-direction transfer-direction}
     :selected-validator (or (normalize-validator-address (get-in state [:staking-ui :selected-validator]))
                             (normalize-validator-address (get-in state [:staking :delegations 0 :validator]))
                             "")
     :validator-search-query (str (or (get-in state [:staking-ui :validator-search-query]) ""))
     :validator-dropdown-open? (true? (get-in state [:staking-ui :validator-dropdown-open?]))
     :form {:deposit-amount (str (or (get-in state [:staking-ui :deposit-amount]) ""))
            :withdraw-amount (str (or (get-in state [:staking-ui :withdraw-amount]) ""))
            :delegate-amount (str (or (get-in state [:staking-ui :delegate-amount]) ""))
            :undelegate-amount (str (or (get-in state [:staking-ui :undelegate-amount]) ""))}
     :submitting (or (get-in state [:staking-ui :submitting]) {})
     :delegations (vec delegations)}))
