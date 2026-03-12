(ns hyperopen.staking.actions
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.utils.parse :as parse-utils]))

(def default-staking-tab
  :validator-performance)

(def default-validator-timeframe
  :week)

(def default-validator-sort
  {:column :stake
   :direction :desc})

(def ^:private staking-route-kinds
  #{"/staking"})

(def ^:private valid-staking-tabs
  #{:validator-performance
    :staking-reward-history
    :staking-action-history})

(def ^:private valid-validator-timeframes
  #{:day :week :month})

(def ^:private valid-validator-sort-columns
  #{:name
    :description
    :stake
    :your-stake
    :uptime
    :apr
    :status
    :commission})

(def ^:private text-validator-sort-columns
  #{:name :description :status})

(def ^:private valid-action-popover-kinds
  #{:transfer :stake :unstake})

(def default-transfer-direction
  :spot->staking)

(def ^:private valid-transfer-directions
  #{:spot->staking :staking->spot})

(def ^:private valid-form-fields
  #{:deposit-amount
    :withdraw-amount
    :delegate-amount
    :undelegate-amount
    :selected-validator
    :validator-search-query
    :validator-dropdown-open?})

(def ^:private anchor-candidate-keys-by-key
  {:left [:left "left"]
   :right [:right "right"]
   :top [:top "top"]
   :bottom [:bottom "bottom"]
   :width [:width "width"]
   :height [:height "height"]
   :viewport-width [:viewport-width :viewportWidth "viewport-width" "viewportWidth"]
   :viewport-height [:viewport-height :viewportHeight "viewport-height" "viewportHeight"]})

(def ^:private hype-decimals
  8)

(def ^:private hype-wei-factor
  (js/BigInt "100000000"))

(defn- split-path-from-query-fragment
  [path]
  (let [path* (if (string? path) path (str (or path "")))]
    (or (first (str/split path* #"[?#]" 2))
        "")))

(defn- trim-trailing-slashes
  [path]
  (loop [path* path]
    (if (and (> (count path*) 1)
             (str/ends-with? path* "/"))
      (recur (subs path* 0 (dec (count path*))))
      path*)))

(defn normalize-route-path
  [path]
  (-> path
      split-path-from-query-fragment
      str/trim
      trim-trailing-slashes))

(defn parse-staking-route
  [path]
  (let [path* (normalize-route-path path)
        path-lower (str/lower-case path*)]
    (if (contains? staking-route-kinds path-lower)
      {:kind :page
       :path path*}
      {:kind :other
       :path path*})))

(defn staking-route?
  [path]
  (= :page (:kind (parse-staking-route path))))

(defn normalize-staking-tab
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :validators :validator-performance
                     :validator :validator-performance
                     :validator-performance :validator-performance
                     :staking-reward-history :staking-reward-history
                     :staking-rewards :staking-reward-history
                     :rewards :staking-reward-history
                     :staking-action-history :staking-action-history
                     :actions :staking-action-history
                     token)]
    (if (contains? valid-staking-tabs normalized)
      normalized
      default-staking-tab)))

(defn normalize-staking-validator-timeframe
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :1d :day
                     :24h :day
                     :day :day
                     :7d :week
                     :week :week
                     :30d :month
                     :month :month
                     token)]
    (if (contains? valid-validator-timeframes normalized)
      normalized
      default-validator-timeframe)))

(defn normalize-staking-validator-sort-column
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :name :name
                     :description :description
                     :stake :stake
                     :your-stake :your-stake
                     :yourstake :your-stake
                     :uptime :uptime
                     :est-apr :apr
                     :apr :apr
                     :status :status
                     :commission :commission
                     token)]
    (if (contains? valid-validator-sort-columns normalized)
      normalized
      (:column default-validator-sort))))

(defn normalize-staking-validator-sort-direction
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    keyword)
                :else nil)
        normalized (case token
                     :ascending :asc
                     :asc :asc
                     :descending :desc
                     :desc :desc
                     token)]
    (if (contains? #{:asc :desc} normalized)
      normalized
      (:direction default-validator-sort))))

(defn normalize-staking-validator-sort
  [value]
  (let [value* (if (map? value) value {})
        column (normalize-staking-validator-sort-column (:column value*))
        direction (normalize-staking-validator-sort-direction (:direction value*))]
    {:column column
     :direction direction}))

(defn normalize-staking-action-popover-kind
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :transfer :transfer
                     :stake :stake
                     :unstake :unstake
                     token)]
    (when (contains? valid-action-popover-kinds normalized)
      normalized)))

(defn normalize-staking-transfer-direction
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :spot->staking :spot->staking
                     :spot-to-staking :spot->staking
                     :spot-staking :spot->staking
                     :deposit :spot->staking
                     :staking->spot :staking->spot
                     :staking-to-spot :staking->spot
                     :staking-spot :staking->spot
                     :withdraw :staking->spot
                     token)]
    (if (contains? valid-transfer-directions normalized)
      normalized
      default-transfer-direction)))

(defn- finite-number?
  [value]
  (and (number? value)
       (js/isFinite value)
       (not (js/isNaN value))))

(defn- optional-number
  [value]
  (when-let [parsed (trading-domain/parse-num value)]
    (when (finite-number? parsed)
      parsed)))

(defn- normalize-validator-address
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (seq text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn- normalize-coin-token
  [value]
  (some-> value str str/trim str/upper-case))

(defn- balance-row-available
  [row]
  (when (map? row)
    (let [available-direct (or (optional-number (:available row))
                               (optional-number (:availableBalance row))
                               (optional-number (:free row)))
          total (or (optional-number (:total row))
                    (optional-number (:totalBalance row)))
          hold (optional-number (:hold row))
          derived (cond
                    (finite-number? total)
                    (if (finite-number? hold)
                      (- total hold)
                      total)

                    :else nil)
          available (or available-direct derived)]
      (when (finite-number? available)
        (max 0 available)))))

(defn- spot-hype-available
  [state]
  (some (fn [row]
          (when (= "HYPE"
                   (normalize-coin-token (:coin row)))
            (balance-row-available row)))
        (get-in state [:spot :clearinghouse-state :balances])))

(defn- undelegated-hype-available
  [state]
  (or (optional-number (get-in state [:staking :delegator-summary :undelegated]))
      0))

(defn- delegation-amount-by-validator
  [state validator]
  (let [validator* (normalize-validator-address validator)]
    (or (some (fn [row]
                (when (= validator*
                         (normalize-validator-address (:validator row)))
                  (optional-number (:amount row))))
              (get-in state [:staking :delegations]))
        0)))

(defn- parse-amount-number
  [state value]
  (parse-utils/parse-localized-decimal value (get-in state [:ui :locale])))

(defn- canonical-decimal-input
  [state value]
  (let [normalized (parse-utils/normalize-localized-decimal-input
                    value
                    (get-in state [:ui :locale]))
        fallback (some-> value str str/trim)]
    (or normalized fallback)))

(defn- parse-hype-input->wei
  [state value]
  (let [input (canonical-decimal-input state value)
        input* (cond
                 (nil? input) nil
                 (str/starts-with? input ".") (str "0" input)
                 :else input)]
    (when-let [[_ whole fract]
               (and (string? input*)
                    (re-matches #"^([0-9]+)(?:\.([0-9]{1,8}))?$" input*))]
      (let [fract* (subs (str (or fract "") "00000000") 0 hype-decimals)
            wei (+ (* (js/BigInt whole) hype-wei-factor)
                   (js/BigInt fract*))
            wei-number (js/Number (.toString wei))]
        (when (and (js/Number.isSafeInteger wei-number)
                   (> wei-number 0))
          wei-number)))))

(defn- format-hype-input
  [value]
  (if (finite-number? value)
    (trading-domain/number->clean-string (max 0 value) hype-decimals)
    ""))

(defn- selected-validator
  [state]
  (or (normalize-validator-address (get-in state [:staking-ui :selected-validator]))
      (normalize-validator-address (get-in state [:staking :delegations 0 :validator]))))

(defn- normalize-anchor
  [anchor]
  (let [anchor* (cond
                  (map? anchor) anchor
                  (some? anchor) (js->clj anchor :keywordize-keys true)
                  :else nil)]
    (when (map? anchor*)
      (let [normalized (reduce (fn [acc [target-key candidate-keys]]
                                 (if-let [num (some (fn [candidate-key]
                                                      (optional-number (get anchor* candidate-key)))
                                                    candidate-keys)]
                                   (assoc acc target-key num)
                                   acc))
                               {}
                               anchor-candidate-keys-by-key)]
        (when (seq normalized)
          normalized)))))

(defn- start-submit-effects
  [submitting-key]
  [[:effects/save [:staking-ui :form-error] nil]
   [:effects/save [:staking-ui :submitting submitting-key] true]])

(defn- submit-guard-error
  [submitting-key message]
  [[:effects/save [:staking-ui :form-error] message]
   [:effects/save [:staking-ui :submitting submitting-key] false]])

(defn load-staking
  [state]
  (let [address (account-context/effective-account-address state)
        no-user-projection-effects
        [[:effects/save-many
          [[[:staking :delegator-summary] nil]
           [[:staking :delegations] []]
           [[:staking :rewards] []]
           [[:staking :history] []]
           [[:staking :errors :delegator-summary] nil]
           [[:staking :errors :delegations] nil]
           [[:staking :errors :rewards] nil]
           [[:staking :errors :history] nil]]]]
        user-heavy-effects (if (seq address)
                             [[:effects/api-fetch-staking-delegator-summary address]
                              [:effects/api-fetch-staking-delegations address]
                              [:effects/api-fetch-staking-rewards address]
                              [:effects/api-fetch-staking-history address]
                              [:effects/api-fetch-staking-spot-state address]]
                             [])
        projection-effects (if (seq address)
                             [[:effects/save [:staking-ui :form-error] nil]]
                             (into [[:effects/save [:staking-ui :form-error] nil]]
                                   no-user-projection-effects))]
    (into projection-effects
          (into [[:effects/api-fetch-staking-validator-summaries]]
                user-heavy-effects))))

(defn load-staking-route
  [state path]
  (if (staking-route? path)
    (load-staking state)
    []))

(defn set-staking-active-tab
  [_state tab]
  [[:effects/save [:staking-ui :active-tab]
    (normalize-staking-tab tab)]])

(defn toggle-staking-validator-timeframe-menu
  [state]
  [[:effects/save [:staking-ui :validator-timeframe-dropdown-open?]
    (not (true? (get-in state [:staking-ui :validator-timeframe-dropdown-open?])))]])

(defn close-staking-validator-timeframe-menu
  [_state]
  [[:effects/save [:staking-ui :validator-timeframe-dropdown-open?] false]])

(defn set-staking-validator-timeframe
  [_state timeframe]
  [[:effects/save-many
    [[[:staking-ui :validator-timeframe]
      (normalize-staking-validator-timeframe timeframe)]
     [[:staking-ui :validator-timeframe-dropdown-open?] false]
     [[:staking-ui :validator-page] 0]]]])

(defn set-staking-validator-page
  [_state page]
  (let [page* (or (some-> page trading-domain/parse-num js/Math.floor int)
                  0)]
    [[:effects/save [:staking-ui :validator-page]
      (max 0 page*)]]))

(defn set-staking-validator-show-all
  [_state show-all?]
  [[:effects/save-many
    [[[:staking-ui :validator-show-all?] (true? show-all?)]
     [[:staking-ui :validator-page] 0]]]])

(defn set-staking-validator-sort
  [state column]
  (if-let [column* (normalize-staking-validator-sort-column column)]
    (let [current (normalize-staking-validator-sort
                   (get-in state [:staking-ui :validator-sort]))
          default-direction (if (contains? text-validator-sort-columns column*)
                              :asc
                              :desc)
          next-direction (if (= column* (:column current))
                           (if (= :asc (:direction current))
                             :desc
                             :asc)
                           default-direction)]
      [[:effects/save [:staking-ui :validator-sort]
        {:column column*
         :direction next-direction}]
       [:effects/save [:staking-ui :validator-page] 0]])
    []))

(defn open-staking-action-popover
  ([state kind]
   (open-staking-action-popover state kind nil))
  ([state kind trigger-bounds]
   (if-let [kind* (normalize-staking-action-popover-kind kind)]
     [[:effects/save-many
       [[[:staking-ui :action-popover]
         {:open? true
          :kind kind*
          :anchor (normalize-anchor trigger-bounds)}]
        [[:staking-ui :transfer-direction]
         (normalize-staking-transfer-direction (get-in state [:staking-ui :transfer-direction]))]
        [[:staking-ui :validator-search-query] ""]
        [[:staking-ui :validator-dropdown-open?] false]
        [[:staking-ui :form-error] nil]]]]
     [])))

(defn close-staking-action-popover
  [_state]
  [[:effects/save-many
    [[[:staking-ui :action-popover]
      {:open? false
       :kind nil
       :anchor nil}]
     [[:staking-ui :validator-search-query] ""]
     [[:staking-ui :validator-dropdown-open?] false]]]])

(defn handle-staking-action-popover-keydown
  [state key]
  (if (= key "Escape")
    (close-staking-action-popover state)
    []))

(defn set-staking-transfer-direction
  [_state direction]
  [[:effects/save [:staking-ui :transfer-direction]
    (normalize-staking-transfer-direction direction)]])

(defn set-staking-form-field
  [_state field value]
  (if-not (contains? valid-form-fields field)
    []
    [[:effects/save [:staking-ui field]
      (case field
        :selected-validator (or (normalize-validator-address value) "")
        :validator-dropdown-open? (true? value)
        (str (or value "")))]]))

(defn select-staking-validator
  [_state validator]
  [[:effects/save-many
    [[[:staking-ui :selected-validator]
      (or (normalize-validator-address validator) "")]
     [[:staking-ui :validator-search-query] ""]
     [[:staking-ui :validator-dropdown-open?] false]]]])

(defn set-staking-deposit-amount-to-max
  [state]
  [[:effects/save [:staking-ui :deposit-amount]
    (format-hype-input (spot-hype-available state))]])

(defn set-staking-withdraw-amount-to-max
  [state]
  [[:effects/save [:staking-ui :withdraw-amount]
    (format-hype-input (undelegated-hype-available state))]])

(defn set-staking-delegate-amount-to-max
  [state]
  [[:effects/save [:staking-ui :delegate-amount]
    (format-hype-input (undelegated-hype-available state))]])

(defn set-staking-undelegate-amount-to-max
  [state]
  (let [validator (selected-validator state)]
    [[:effects/save [:staking-ui :undelegate-amount]
      (format-hype-input (delegation-amount-by-validator state validator))]]))

(defn submit-staking-deposit
  [state]
  (let [blocked-message (account-context/mutations-blocked-message state)
        owner-address (account-context/owner-address state)
        amount-input (get-in state [:staking-ui :deposit-amount])
        amount (parse-amount-number state amount-input)
        wei (parse-hype-input->wei state amount-input)
        available (spot-hype-available state)]
    (cond
      (seq blocked-message)
      (submit-guard-error :deposit? blocked-message)

      (nil? owner-address)
      (submit-guard-error :deposit? "Connect your wallet before transferring to staking balance.")

      (nil? wei)
      (submit-guard-error :deposit? "Enter a valid amount up to 8 decimals.")

      (and (finite-number? available)
           (finite-number? amount)
           (> amount available))
      (submit-guard-error :deposit? "Amount exceeds available HYPE in spot balance.")

      :else
      (into (start-submit-effects :deposit?)
            [[:effects/api-submit-staking-deposit
              {:kind :deposit
               :action {:type "cDeposit"
                        :wei wei}}]]))))

(defn submit-staking-withdraw
  [state]
  (let [blocked-message (account-context/mutations-blocked-message state)
        owner-address (account-context/owner-address state)
        amount-input (get-in state [:staking-ui :withdraw-amount])
        amount (parse-amount-number state amount-input)
        wei (parse-hype-input->wei state amount-input)
        available (undelegated-hype-available state)]
    (cond
      (seq blocked-message)
      (submit-guard-error :withdraw? blocked-message)

      (nil? owner-address)
      (submit-guard-error :withdraw? "Connect your wallet before withdrawing from staking balance.")

      (nil? wei)
      (submit-guard-error :withdraw? "Enter a valid amount up to 8 decimals.")

      (and (finite-number? amount)
           (> amount available))
      (submit-guard-error :withdraw? "Amount exceeds available staking balance.")

      :else
      (into (start-submit-effects :withdraw?)
            [[:effects/api-submit-staking-withdraw
              {:kind :withdraw
               :action {:type "cWithdraw"
                        :wei wei}}]]))))

(defn submit-staking-delegate
  [state]
  (let [blocked-message (account-context/mutations-blocked-message state)
        owner-address (account-context/owner-address state)
        validator (selected-validator state)
        amount-input (get-in state [:staking-ui :delegate-amount])
        amount (parse-amount-number state amount-input)
        wei (parse-hype-input->wei state amount-input)
        available (undelegated-hype-available state)]
    (cond
      (seq blocked-message)
      (submit-guard-error :delegate? blocked-message)

      (nil? owner-address)
      (submit-guard-error :delegate? "Connect your wallet before staking.")

      (nil? validator)
      (submit-guard-error :delegate? "Select a validator before staking.")

      (nil? wei)
      (submit-guard-error :delegate? "Enter a valid amount up to 8 decimals.")

      (and (finite-number? amount)
           (> amount available))
      (submit-guard-error :delegate? "Amount exceeds available staking balance.")

      :else
      (into (start-submit-effects :delegate?)
            [[:effects/api-submit-staking-delegate
              {:kind :delegate
               :action {:type "tokenDelegate"
                        :validator validator
                        :wei wei
                        :isUndelegate false}}]]))))

(defn submit-staking-undelegate
  [state]
  (let [blocked-message (account-context/mutations-blocked-message state)
        owner-address (account-context/owner-address state)
        validator (selected-validator state)
        amount-input (get-in state [:staking-ui :undelegate-amount])
        amount (parse-amount-number state amount-input)
        wei (parse-hype-input->wei state amount-input)
        delegated-amount (delegation-amount-by-validator state validator)]
    (cond
      (seq blocked-message)
      (submit-guard-error :undelegate? blocked-message)

      (nil? owner-address)
      (submit-guard-error :undelegate? "Connect your wallet before undelegating.")

      (nil? validator)
      (submit-guard-error :undelegate? "Select a validator before undelegating.")

      (nil? wei)
      (submit-guard-error :undelegate? "Enter a valid amount up to 8 decimals.")

      (and (finite-number? amount)
           (> amount delegated-amount))
      (submit-guard-error :undelegate? "Amount exceeds your delegated amount for this validator.")

      :else
      (into (start-submit-effects :undelegate?)
            [[:effects/api-submit-staking-undelegate
              {:kind :undelegate
               :action {:type "tokenDelegate"
                        :validator validator
                        :wei wei
                        :isUndelegate true}}]]))))
