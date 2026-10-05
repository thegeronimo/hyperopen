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
    (let [;; Hyperliquid lists the vault's own leader as the literal string
          ;; "Leader" instead of an address.
          leader? (= "Leader" (:user payload))
          normalized {:user (when-not leader? (common/normalize-address (:user payload)))
                      :leader? (when leader? true)
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

(defn- twap-row-status
  [row]
  (when (map? row)
    (let [status (:status row)]
      (cond
        (string? status) status
        (map? status) (:status status)
        :else nil))))

(defn active-twap-rows
  "webData2's twapStates carried only live TWAPs; twapHistory also returns
  finished/terminated rows, so keep only the ones still running."
  [payload]
  (if (sequential? payload)
    (->> payload
         (filter #(contains? #{"activated" "running"} (twap-row-status %)))
         vec)
    []))

(defn- vault-slice-request
  "One /info request for a slice of the retired webData2 vault aggregate.
  Non-core slices resolve to nil on failure so the detail panel can still
  render the clearinghouse data."
  [post-info! body policy-key dedupe-key opts {:keys [core? normalize]}]
  (let [request (post-info! body
                            (request-policy/apply-info-request-policy
                             policy-key
                             (merge {:priority :high
                                     :dedupe-key dedupe-key}
                                    opts)))
        request* (if normalize (.then request normalize) request)]
    (if core?
      request*
      (.catch request* (fn [_err] nil)))))

(defn- vault-child-addresses
  "Parent vaults (e.g. HLP) have no book of their own: the retired webData2
  aggregate folded every child vault's positions/orders into the parent
  response. Resolve the children so the fan-out below can do the same.
  Failure degrades to no children rather than blocking the panel."
  [post-info! vault-address opts]
  (-> (post-info! {"type" "vaultDetails"
                   "vaultAddress" vault-address}
                  (request-policy/apply-info-request-policy
                   :vault-details
                   (merge {:priority :high
                           :dedupe-key [:vault-details vault-address nil]}
                          opts)))
      (.then (fn [payload]
               (->> (get-in payload [:relationship :data :childAddresses])
                    (keep common/normalize-address)
                    vec)))
      (.catch (fn [_err] []))))

(defn- account-slice-requests
  "The three per-address slices of the retired webData2 vault aggregate.
  clearinghouseState is core for the vault's own address (failure rejects);
  everything else degrades to nil."
  [post-info! address opts core-clearinghouse?]
  [(vault-slice-request post-info!
                        {"type" "clearinghouseState"
                         "user" address}
                        :clearinghouse-state
                        [:vault-clearinghouse-state address]
                        opts
                        {:core? core-clearinghouse?})
   (vault-slice-request post-info!
                        {"type" "frontendOpenOrders"
                         "user" address}
                        :vault-open-orders
                        [:vault-open-orders address]
                        opts
                        {})
   (vault-slice-request post-info!
                        {"type" "twapHistory"
                         "user" address}
                        :vault-twap-history
                        [:vault-twap-history address]
                        opts
                        {:normalize active-twap-rows})])

(defn- merge-vault-account-slices
  "Recreate the webData2 vault payload shape from per-address slice results.
  results is a flat seq of [clearinghouse open-orders twap-states] triples,
  vault's own address first, children after."
  [spot-state results]
  (let [triples (partition 3 results)
        [own-clearinghouse _ _] (first triples)
        positions (->> triples
                       (mapcat (fn [[clearinghouse _ _]]
                                 (when (map? clearinghouse)
                                   (:assetPositions clearinghouse))))
                       vec)
        open-orders (->> triples
                         (mapcat (fn [[_ orders _]]
                                   (when (sequential? orders) orders)))
                         vec)
        twap-states (->> triples
                         (mapcat (fn [[_ _ twaps]]
                                   (when (sequential? twaps) twaps)))
                         vec)]
    {:clearinghouseState (if (map? own-clearinghouse)
                           (assoc own-clearinghouse :assetPositions positions)
                           own-clearinghouse)
     :openOrders open-orders
     :spotState spot-state
     :twapStates twap-states}))

(defn request-vault-webdata2!
  "Vault account snapshot. Historically one {\"type\" \"webData2\"} aggregate;
  Hyperliquid deprecated webData2, so this fans out to the supported
  per-concern endpoints — for the vault itself and, for parent vaults, each
  child vault — and merges them into the same slice shape the vault webdata
  adapter reads. The vault's own clearinghouseState failure rejects (core
  data); the other slices degrade."
  [post-info! vault-address opts]
  (if-let [vault-address* (common/normalize-address vault-address)]
    (-> (vault-child-addresses post-info! vault-address* opts)
        (.then (fn [child-addresses]
                 (let [addresses (into [vault-address*]
                                       (remove #(= % vault-address*) child-addresses))
                       spot-request (vault-slice-request post-info!
                                                         {"type" "spotClearinghouseState"
                                                          "user" vault-address*}
                                                         :spot-clearinghouse-state
                                                         [:vault-spot-clearinghouse-state vault-address*]
                                                         opts
                                                         {})
                       account-requests (mapcat (fn [address]
                                                  (account-slice-requests
                                                   post-info!
                                                   address
                                                   opts
                                                   (= address vault-address*)))
                                                addresses)]
                   (-> (js/Promise.all (into-array (cons spot-request account-requests)))
                       (.then (fn [results]
                                (let [results* (vec (array-seq results))]
                                  (merge-vault-account-slices (first results*)
                                                              (rest results*))))))))))
    (js/Promise.resolve nil)))
