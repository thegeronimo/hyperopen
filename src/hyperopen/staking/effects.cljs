(ns hyperopen.staking.effects
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.staking.actions :as staking-actions]))

(defn- staking-route-active?
  [store]
  (staking-actions/staking-route?
   (get-in @store [:router :path] "")))

(defn- allow-route?
  [store opts]
  (or (true? (:skip-route-gate? (or opts {})))
      (staking-route-active? store)))

(defn- request-opts
  [opts]
  (dissoc (or opts {}) :skip-route-gate?))

(defn- resolve-address
  [store address]
  (or address
      (account-context/effective-account-address @store)))

(defn api-fetch-staking-validator-summaries!
  [{:keys [store
           request-staking-validator-summaries!
           begin-staking-validator-summaries-load
           apply-staking-validator-summaries-success
           apply-staking-validator-summaries-error
           opts]}]
  (if (allow-route? store opts)
    (let [request-opts* (request-opts opts)]
      (swap! store begin-staking-validator-summaries-load)
      (-> (request-staking-validator-summaries! request-opts*)
          (.then (promise-effects/apply-success-and-return
                  store
                  apply-staking-validator-summaries-success))
          (.catch (promise-effects/apply-error-and-reject
                   store
                   apply-staking-validator-summaries-error))))
    (js/Promise.resolve nil)))

(defn api-fetch-staking-delegator-summary!
  [{:keys [store
           address
           request-staking-delegator-summary!
           begin-staking-delegator-summary-load
           apply-staking-delegator-summary-success
           apply-staking-delegator-summary-error
           opts]}]
  (let [address* (resolve-address store address)]
    (if (and (allow-route? store opts)
             (seq address*))
      (let [request-opts* (request-opts opts)]
        (swap! store begin-staking-delegator-summary-load)
        (-> (request-staking-delegator-summary! address* request-opts*)
            (.then (promise-effects/apply-success-and-return
                    store
                    apply-staking-delegator-summary-success))
            (.catch (promise-effects/apply-error-and-reject
                     store
                     apply-staking-delegator-summary-error))))
      (js/Promise.resolve nil))))

(defn api-fetch-staking-delegations!
  [{:keys [store
           address
           request-staking-delegations!
           begin-staking-delegations-load
           apply-staking-delegations-success
           apply-staking-delegations-error
           opts]}]
  (let [address* (resolve-address store address)]
    (if (and (allow-route? store opts)
             (seq address*))
      (let [request-opts* (request-opts opts)]
        (swap! store begin-staking-delegations-load)
        (-> (request-staking-delegations! address* request-opts*)
            (.then (promise-effects/apply-success-and-return
                    store
                    apply-staking-delegations-success))
            (.catch (promise-effects/apply-error-and-reject
                     store
                     apply-staking-delegations-error))))
      (js/Promise.resolve nil))))

(defn api-fetch-staking-rewards!
  [{:keys [store
           address
           request-staking-delegator-rewards!
           begin-staking-rewards-load
           apply-staking-rewards-success
           apply-staking-rewards-error
           opts]}]
  (let [address* (resolve-address store address)]
    (if (and (allow-route? store opts)
             (seq address*))
      (let [request-opts* (request-opts opts)]
        (swap! store begin-staking-rewards-load)
        (-> (request-staking-delegator-rewards! address* request-opts*)
            (.then (promise-effects/apply-success-and-return
                    store
                    apply-staking-rewards-success))
            (.catch (promise-effects/apply-error-and-reject
                     store
                     apply-staking-rewards-error))))
      (js/Promise.resolve nil))))

(defn api-fetch-staking-history!
  [{:keys [store
           address
           request-staking-delegator-history!
           begin-staking-history-load
           apply-staking-history-success
           apply-staking-history-error
           opts]}]
  (let [address* (resolve-address store address)]
    (if (and (allow-route? store opts)
             (seq address*))
      (let [request-opts* (request-opts opts)]
        (swap! store begin-staking-history-load)
        (-> (request-staking-delegator-history! address* request-opts*)
            (.then (promise-effects/apply-success-and-return
                    store
                    apply-staking-history-success))
            (.catch (promise-effects/apply-error-and-reject
                     store
                     apply-staking-history-error))))
      (js/Promise.resolve nil))))

(defn api-fetch-staking-spot-state!
  [{:keys [store
           address
           request-spot-clearinghouse-state!
           begin-spot-balances-load
           apply-spot-balances-success
           apply-spot-balances-error
           opts]}]
  (let [address* (resolve-address store address)]
    (if (and (allow-route? store opts)
             (seq address*))
      (let [request-opts* (request-opts opts)]
        (swap! store begin-spot-balances-load)
        (-> (request-spot-clearinghouse-state! address* request-opts*)
            (.then (promise-effects/apply-success-and-return
                    store
                    apply-spot-balances-success))
            (.catch (promise-effects/apply-error-and-reject
                     store
                     apply-spot-balances-error))))
      (js/Promise.resolve nil))))

(defn- fallback-exchange-response-error
  [resp]
  (or (:error resp)
      (:message resp)
      (:response resp)
      "Unknown exchange error"))

(defn- fallback-runtime-error-message
  [err]
  (or (some-> err .-message)
      (str err)))

(def ^:private submitting-keys-by-kind
  {:deposit :deposit?
   :withdraw :withdraw?
   :delegate :delegate?
   :undelegate :undelegate?
   :delegate? :delegate?
   :undelegate? :undelegate?
   :deposit? :deposit?
   :withdraw? :withdraw?})

(defn- submitting-key
  [kind]
  (get submitting-keys-by-kind kind :deposit?))

(defn- submit-label
  [kind]
  (case kind
    :deposit "Transfer to staking balance"
    :withdraw "Transfer to spot balance"
    :delegate "Stake"
    :undelegate "Unstake"
    "Staking action"))

(defn- input-path-for-kind
  [kind]
  (case kind
    :deposit [:staking-ui :deposit-amount]
    :withdraw [:staking-ui :withdraw-amount]
    :delegate [:staking-ui :delegate-amount]
    :undelegate [:staking-ui :undelegate-amount]
    nil))

(defn- set-submit-error!
  [store show-toast! kind message]
  (let [submitting-path [:staking-ui :submitting (submitting-key kind)]
        message* (str/trim (str message))]
    (swap! store
           (fn [state]
             (-> state
                 (assoc-in submitting-path false)
                 (assoc-in [:staking-ui :form-error] (if (seq message*)
                                                       message*
                                                       "Unable to submit staking action.")))))
    (show-toast! store :error (or (not-empty message*) "Unable to submit staking action."))))

(defn- set-submit-success!
  [store kind]
  (let [submitting-path [:staking-ui :submitting (submitting-key kind)]
        input-path (input-path-for-kind kind)]
    (swap! store
           (fn [state]
             (cond-> (-> state
                         (assoc-in submitting-path false)
                         (assoc-in [:staking-ui :form-error] nil))
               input-path (assoc-in input-path ""))))))

(defn- refresh-after-submit!
  [store dispatch!]
  (when (fn? dispatch!)
    (dispatch! store nil [[:actions/load-staking]])))

(defn- submit-staking-action!
  [{:keys [store
           request
           dispatch!
           submit-action!
           exchange-response-error
           runtime-error-message
           show-toast!]
    :or {submit-action! trading-api/submit-token-delegate!
         exchange-response-error fallback-exchange-response-error
         runtime-error-message fallback-runtime-error-message
         show-toast! (fn [_store _kind _message] nil)}}]
  (let [state @store
        blocked-message (account-context/mutations-blocked-message state)
        owner-address (account-context/owner-address state)
        kind (:kind request)
        action (:action request)
        label (submit-label kind)]
    (cond
      (seq blocked-message)
      (set-submit-error! store show-toast! kind blocked-message)

      (nil? owner-address)
      (set-submit-error! store show-toast! kind (str "Connect your wallet before submitting "
                                                    (str/lower-case label)
                                                    "."))

      (not (map? action))
      (set-submit-error! store show-toast! kind "Invalid staking request payload.")

      :else
      (-> (submit-action! store owner-address action)
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (do
                       (set-submit-success! store kind)
                       (show-toast! store :success (str label " submitted."))
                       (refresh-after-submit! store dispatch!)
                       resp)
                     (let [error-text (str/trim (str (exchange-response-error resp)))
                           message (str label " failed: "
                                        (if (seq error-text)
                                          error-text
                                          "Unknown exchange error"))]
                       (set-submit-error! store show-toast! kind message)
                       resp))))
          (.catch (fn [err]
                    (let [error-text (str/trim (str (runtime-error-message err)))
                          message (str label " failed: "
                                       (if (seq error-text)
                                         error-text
                                         "Unknown runtime error"))]
                      (set-submit-error! store show-toast! kind message))))))))

(defn api-submit-staking-deposit!
  [{:keys [submit-c-deposit!]
    :as deps
    :or {submit-c-deposit! trading-api/submit-c-deposit!}}]
  (submit-staking-action!
   (assoc deps :submit-action! submit-c-deposit!)))

(defn api-submit-staking-withdraw!
  [{:keys [submit-c-withdraw!]
    :as deps
    :or {submit-c-withdraw! trading-api/submit-c-withdraw!}}]
  (submit-staking-action!
   (assoc deps :submit-action! submit-c-withdraw!)))

(defn api-submit-staking-delegate!
  [{:keys [submit-token-delegate!]
    :as deps
    :or {submit-token-delegate! trading-api/submit-token-delegate!}}]
  (submit-staking-action!
   (assoc deps :submit-action! submit-token-delegate!)))

(defn api-submit-staking-undelegate!
  [{:keys [submit-token-delegate!]
    :as deps
    :or {submit-token-delegate! trading-api/submit-token-delegate!}}]
  (submit-staking-action!
   (assoc deps :submit-action! submit-token-delegate!)))
