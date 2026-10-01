(ns hyperopen.hyperevm.effects
  "HyperEVM read effects: balances plus bridge health from the HyperEVM RPC,
   and the on-demand HyperCore reads (a system address's spot balance, the
   owner's `userRole`).

   Every collaborator is injected, so these run against stubs in tests; the
   runtime adapter (`hyperopen.runtime.effect-adapters.hyperevm`) supplies
   the real RPC client, info client, clock and logger. Each function returns
   the fetch promise, which never rejects: failures are projected into state."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.hyperevm.domain.balances :as balances]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.tokens :as tokens]))

(defn- error-info
  [error]
  {:message (or (some-> error .-message) (str error))
   :kind (or (:kind (ex-data error)) :unknown)})

(defn- queried-indexes
  "Indexes of the tokens `read-balances!` reads with `balanceOf`."
  [catalog]
  (into #{} (keep #(when (:erc20-address %) (:index %))) catalog))

(defn- read-address!
  "Read one address and project the reply. Resolves with the poll outcome
   (`:ok`, `:rate-limited` or `:error`) and never rejects. Bridge health is
   applied by its own request time, whether or not the balance reply is
   still current, since it does not depend on the address."
  [{:keys [store read-balances! rpc-deps now-ms-fn log-fn chain]}
   {:keys [address requested-at-ms catalog health-calls]}]
  (-> (read-balances! rpc-deps
                      (cond-> {:owner address :tokens catalog :chain chain}
                        (seq health-calls) (assoc :extra-calls health-calls)))
      (.then (fn [result]
               (swap! store
                      (fn [state]
                        (cond-> (balances/apply-success state
                                                        address
                                                        requested-at-ms
                                                        (dissoc result :extra-results :extra-unread)
                                                        (queried-indexes catalog)
                                                        (now-ms-fn))
                          (seq health-calls)
                          (bridge/apply-health (bridge/health-result catalog result)
                                               requested-at-ms))))
               :ok))
      (.catch (fn [error]
                (let [info (error-info error)]
                  (log-fn "HyperEVM balance read failed" address (:message info))
                  (swap! store balances/apply-error address requested-at-ms info)
                  (if (= :rate-limited (:kind info)) :rate-limited :error))))))

(defn fetch-hyperevm-balances!
  "Read balances for every address in `plan` (see
   `hyperopen.hyperevm.domain.balances/refresh-plan`). The first address's
   batch also carries the bridge-health reads, so one poll reads bridge
   health once whatever the number of addresses. Rate-limit backoff is
   settled once, after every address has answered."
  [{:keys [store plan chain log-fn now-ms-fn]
    :or {chain chain/mainnet
         log-fn (fn [& _] nil)}
    :as deps}]
  (let [state @store
        catalog (tokens/linked-tokens (get-in state [:spot :meta]) chain)
        health-calls (bridge/health-calls catalog
                                          (bridge/pending-decimals-indexes state catalog))
        deps* (assoc deps :chain chain :log-fn log-fn)]
    (swap! store balances/apply-loading plan)
    (-> (js/Promise.all
         (into-array
          (map-indexed (fn [i address]
                         (read-address! deps*
                                        {:address address
                                         :requested-at-ms (:requested-at-ms plan)
                                         :catalog catalog
                                         :health-calls (when (zero? i) health-calls)}))
                       (:addresses plan))))
        (.then (fn [outcomes]
                 (swap! store balances/apply-poll-backoff (now-ms-fn) (vec outcomes)))))))

(defn fetch-core-bridge-balance!
  "Fetch the HyperCore spot balance of token `index` at its system address,
   the cap on HyperEVM -> Core moves. `address` must be the token's system
   address from the catalog; anything else is refused, because a capacity
   read from the wrong address would let a move past the real cap. The
   request time is stamped first, so the Transfer modal's refresh check
   (`bridge/core-capacity-refresh-due?`) does not send it again while it is
   in flight. The read bypasses the info client's 15 s cache: the reply is
   stamped as current when it lands, so a cached one would start the 60 s
   cap age (`bridge/core-capacity-max-age-ms`) up to 15 s late."
  [{:keys [store index address request-spot-clearinghouse-state! now-ms-fn log-fn chain]
    :or {chain chain/mainnet
         log-fn (fn [& _] nil)}}]
  (let [token (tokens/token-by-index (get-in @store [:spot :meta]) chain index)]
    (if-not (and token
                 (bridge/core-capacity-fetch-needed? token)
                 (= (:system-address token) address))
      (do (log-fn "Refused a HyperCore bridge-balance read for a mismatched address" index address)
          (js/Promise.resolve nil))
      (-> (do (swap! store bridge/mark-core-system-balance-requested index (now-ms-fn))
              (request-spot-clearinghouse-state! address {:priority :low
                                                          :force-refresh? true}))
          (.then (fn [payload]
                   (swap! store bridge/apply-core-system-balance index payload (now-ms-fn))))
          (.catch (fn [error]
                    (let [{:keys [message]} (error-info error)]
                      (log-fn "HyperCore bridge-balance read failed" index message)
                      (swap! store bridge/apply-core-system-balance-error index message))))))))

(defn fetch-core-account-status!
  "Read whether `owner` has a HyperCore account (`userRole`). Re-reading a
   `:missing` status bypasses the info client's cache, so an account
   activated since the last read shows up at once. A failed read leaves the
   status as it was."
  [{:keys [store owner request-user-role! log-fn]
    :or {log-fn (fn [& _] nil)}}]
  (-> (request-user-role! owner
                          (cond-> {:priority :low}
                            (= :missing (get-in @store [:hyperevm :core-account
                                                        (account-context/normalize-address owner)]))
                            (assoc :force-refresh? true)))
      (.then (fn [response]
               (swap! store balances/apply-core-account-role owner response)))
      (.catch (fn [error]
                (log-fn "HyperCore userRole read failed" owner (:message (error-info error)))
                nil))))
