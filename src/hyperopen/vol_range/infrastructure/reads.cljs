(ns hyperopen.vol-range.infrastructure.reads
  "Awaitable, scoped raw reads for Vol Range admission and reconciliation
  (HO-SPEC-014 v2.1 sections 2.2 items 5-6, 7.3, 8.6).

  Each read goes through the shared `post-info!` request policy with caching
  and request sharing disabled, so the result is a fresh observation for THIS
  barrier rather than a UI read that happened to be in flight. Nothing here
  writes to the app store; callers get raw rows plus provenance and decide."
  (:require [clojure.string :as str]
            [hyperopen.api.endpoints.orders :as orders]))

(defn- normalize-address [a] (some-> a str str/trim str/lower-case not-empty))

(defn- fresh-opts [kind address barrier-id]
  {:priority :high
   :cache-ttl-ms 0
   :dedupe-key [:vol-range-barrier kind address barrier-id]})

(defn read-account-snapshot!
  "Spot balances and raw open orders for one account, read together.
  Resolves to {:account-address :barrier-id :requested-at-ms :received-at-ms
  :balances [...] :open-orders [...] :complete? bool :error text-or-nil}.
  `complete?` is true only when BOTH reads succeeded with the expected shapes;
  unknown completeness is false (spec 9.1). Never rejects."
  [post-info! {:keys [account-address barrier-id now-ms!]}]
  (let [address (normalize-address account-address)
        now-ms! (or now-ms! #(.now js/Date))
        requested-at (now-ms!)]
    (if-not (and address barrier-id)
      (js/Promise.resolve {:account-address nil :barrier-id barrier-id :requested-at-ms requested-at
                           :received-at-ms nil :balances [] :open-orders [] :complete? false
                           :error "Account address and barrier id are required"})
      (-> (js/Promise.all
           #js [(post-info! {"type" "spotClearinghouseState" "user" address}
                            (fresh-opts :spot-balances address barrier-id))
                (post-info! {"type" "frontendOpenOrders" "user" address}
                            (fresh-opts :open-orders address barrier-id))])
          (.then (fn [results]
                   (let [[spot open] (array-seq results)
                         balances (:balances spot)
                         complete? (and (map? spot) (sequential? balances) (sequential? open))]
                     {:account-address address
                      :barrier-id barrier-id
                      :requested-at-ms requested-at
                      :received-at-ms (now-ms!)
                      :balances (if (sequential? balances) (vec balances) [])
                      :open-orders (if (sequential? open) (vec open) [])
                      :complete? (boolean complete?)
                      :error (when-not complete? "Unexpected account snapshot shape")})))
          (.catch (fn [e]
                    {:account-address address :barrier-id barrier-id :requested-at-ms requested-at
                     :received-at-ms nil :balances [] :open-orders [] :complete? false
                     :error (if (instance? js/Error e) (.-message e) (str e))}))))))

(defn read-order-statuses!
  "Order-status lookups for original identities (oid string or CLOID), one
  request each. Resolves to a vector of {:id :status :order :error} where
  `:status` is the venue's status string, \"unknownOid\", or nil when the
  lookup itself failed; a failed lookup is never treated as non-placement."
  [post-info! {:keys [account-address ids barrier-id]}]
  (let [address (normalize-address account-address)]
    (-> (js/Promise.all
         (clj->js
          (for [id ids]
            (-> (orders/request-order-status! post-info! address id
                                              (fresh-opts :order-status address [barrier-id id]))
                (.then (fn [resp]
                         (let [status (cond (map? resp) (:status resp) :else nil)]
                           {:id id
                            :status (case status
                                      "order" (get-in resp [:order :status])
                                      "unknownOid" "unknownOid"
                                      nil)
                            :order (when (map? resp) (:order resp))
                            :error (when-not (contains? #{"order" "unknownOid"} status)
                                     "Unrecognized order status response")})))
                (.catch (fn [e]
                          {:id id :status nil :order nil
                           :error (if (instance? js/Error e) (.-message e) (str e))}))))))
        (.then (fn [arr] (vec (array-seq arr)))))))
