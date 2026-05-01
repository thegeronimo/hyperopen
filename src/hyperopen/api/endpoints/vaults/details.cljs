(ns hyperopen.api.endpoints.vaults.details
  (:require [hyperopen.api.endpoints.account :as account-endpoints]
            [hyperopen.api.endpoints.vaults.common :as common]
            [hyperopen.api.endpoints.vaults.snapshots :as snapshots]
            [hyperopen.api.request-policy :as request-policy]))

(defn normalize-user-vault-equity
  [row]
  (when (map? row)
    (when-let [vault-address (common/normalize-address (:vaultAddress row))]
      {:vault-address vault-address
       :equity (or (common/parse-optional-num (:equity row)) 0)
       :equity-raw (:equity row)
       :locked-until-ms (common/parse-optional-int (:lockedUntilTimestamp row))})))

(defn normalize-user-vault-equities
  [payload]
  (if (sequential? payload)
    (->> payload
         (keep normalize-user-vault-equity)
         vec)
    []))

(defn request-user-vault-equities!
  [post-info! address opts]
  (if-let [requested-address (common/normalize-address address)]
    (-> (post-info! {"type" "userVaultEquities"
                     "user" requested-address}
                    (request-policy/apply-info-request-policy
                     :user-vault-equities
                     (merge {:priority :high
                             :dedupe-key [:user-vault-equities requested-address]}
                            opts)))
        (.then normalize-user-vault-equities))
    (js/Promise.resolve [])))

(defn normalize-follower-state
  [payload]
  (when (map? payload)
    (let [normalized {:user (common/normalize-address (:user payload))
                      :vault-equity (common/parse-optional-num (:vaultEquity payload))
                      :pnl (common/parse-optional-num (:pnl payload))
                      :all-time-pnl (common/parse-optional-num (:allTimePnl payload))
                      :days-following (common/parse-optional-int (:daysFollowing payload))
                      :vault-entry-time-ms (common/parse-optional-int (:vaultEntryTime payload))
                      :lockup-until-ms (common/parse-optional-int (:lockupUntil payload))}
          normalized* (reduce-kv (fn [acc k v]
                                   (if (nil? v)
                                     acc
                                     (assoc acc k v)))
                                 {}
                                 normalized)]
      (when (seq normalized*)
        normalized*))))

(defn- normalize-followers
  [followers]
  (if (sequential? followers)
    (->> followers
         (keep normalize-follower-state)
         vec)
    []))

(defn followers-count
  [followers normalized-followers]
  (if (seq normalized-followers)
    (count normalized-followers)
    (or (common/parse-optional-int followers) 0)))

(defn normalize-vault-details
  [payload]
  (when (map? payload)
    (when-let [vault-address (common/normalize-address (:vaultAddress payload))]
      (let [followers (normalize-followers (:followers payload))]
        {:name (or (common/non-blank-text (:name payload))
                   vault-address)
         :vault-address vault-address
         :leader (common/normalize-address (:leader payload))
         :description (or (common/non-blank-text (:description payload)) "")
         :tvl (common/parse-optional-num (:tvl payload))
         :tvl-raw (:tvl payload)
         :portfolio (account-endpoints/normalize-portfolio-summary (:portfolio payload))
         :apr (or (common/parse-optional-num (:apr payload)) 0)
         :follower-state (normalize-follower-state (:followerState payload))
         :leader-fraction (common/parse-optional-num (:leaderFraction payload))
         :leader-commission (common/parse-optional-num (:leaderCommission payload))
         :followers followers
         :followers-count (followers-count (:followers payload) followers)
         :max-distributable (common/parse-optional-num (:maxDistributable payload))
         :max-withdrawable (common/parse-optional-num (:maxWithdrawable payload))
         :is-closed? (boolean (or (common/boolean-value (:isClosed payload))
                                  false))
         :relationship (snapshots/normalize-vault-relationship (:relationship payload))
         :allow-deposits? (boolean (or (common/boolean-value (:allowDeposits payload))
                                       false))
         :always-close-on-withdraw? (boolean (or (common/boolean-value (:alwaysCloseOnWithdraw payload))
                                                 false))}))))

(defn request-vault-details!
  [post-info! vault-address opts]
  (if-let [vault-address* (common/normalize-address vault-address)]
    (let [opts* (or opts {})
          user-address (common/normalize-address (:user opts*))
          request-opts (request-policy/apply-info-request-policy
                        :vault-details
                        (merge {:priority :high
                                :dedupe-key [:vault-details vault-address* user-address]}
                               (dissoc opts* :user)))
          request-body (cond-> {"type" "vaultDetails"
                                "vaultAddress" vault-address*}
                         user-address (assoc "user" user-address))]
      (-> (post-info! request-body request-opts)
          (.then normalize-vault-details)))
    (js/Promise.resolve nil)))

(defn request-vault-webdata2!
  [post-info! vault-address opts]
  (if-let [vault-address* (common/normalize-address vault-address)]
    (post-info! {"type" "webData2"
                 "user" vault-address*}
                (request-policy/apply-info-request-policy
                 :vault-webdata2
                 (merge {:priority :high
                         :dedupe-key [:vault-webdata2 vault-address*]}
                        opts)))
    (js/Promise.resolve nil)))
