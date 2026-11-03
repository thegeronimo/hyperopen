(ns hyperopen.borrow-lend.effects
  "Side-effect interpreters for Earn: info fetches and the signed `borrowLend`
  submit. Collaborators (request fns, projections, toast, dispatch) are
  injected so these functions stay testable without a network."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.borrow-lend.account :as bl-account]))

;; Upper bound on pages per paged info request.
(def ^:private max-pages 20)

(defn- still-current?
  [store address]
  (= (account-context/normalize-address address)
     (account-context/normalize-address (bl-account/account-address @store))))

(defn api-fetch-borrow-lend-reserves!
  [{:keys [store request-borrow-lend-reserve-states!
           begin-borrow-lend-reserves-load
           apply-borrow-lend-reserves-success
           apply-borrow-lend-reserves-error
           opts]}]
  (swap! store begin-borrow-lend-reserves-load)
  (-> (request-borrow-lend-reserve-states! (or opts {}))
      (.then (promise-effects/apply-success-and-return
              store apply-borrow-lend-reserves-success))
      (.catch (promise-effects/apply-error-and-reject
               store apply-borrow-lend-reserves-error))))

(defn- fetch-for-address!
  "Runs `request!` for `address` and applies the result only while that
  address is still the one Earn shows, so a slow response for a previous
  account never overwrites the current one."
  [{:keys [store address request! begin-load apply-success apply-error]}]
  (if (seq address)
    (do
      (swap! store begin-load)
      (-> (request!)
          (.then (fn [payload]
                   (when (still-current? store address)
                     (swap! store apply-success address payload))
                   payload))
          (.catch (fn [err]
                    (when (still-current? store address)
                      (swap! store apply-error err))
                    (promise-effects/reject-error err)))))
    (js/Promise.resolve nil)))

(defn api-fetch-borrow-lend-user-state!
  [{:keys [store address request-borrow-lend-user-state!
           begin-borrow-lend-user-state-load
           apply-borrow-lend-user-state-success
           apply-borrow-lend-user-state-error
           opts]}]
  (fetch-for-address!
   {:store store
    :address address
    :request! #(request-borrow-lend-user-state! address (or opts {}))
    :begin-load begin-borrow-lend-user-state-load
    :apply-success apply-borrow-lend-user-state-success
    :apply-error apply-borrow-lend-user-state-error}))

(defn- fetch-pages!
  "Pages a time-ordered info request forward from `start-ms`. Each page starts
  at the previous page's last timestamp (inclusive, so rows sharing it are not
  lost) and paging stops on a page that adds nothing new. Resolves to every
  row, oldest first."
  [request-page! start-ms time-fn key-fn]
  (letfn [(step [start seen acc n]
            (-> (request-page! start)
                (.then (fn [rows]
                         (let [fresh (remove #(contains? seen (key-fn %)) rows)
                               acc* (into acc fresh)]
                           (if (or (empty? fresh) (>= n max-pages))
                             acc*
                             (step (time-fn (last rows))
                                   (into seen (map key-fn) fresh)
                                   acc*
                                   (inc n))))))))]
    (step start-ms #{} [] 1)))

(def ^:private ledger-row-key
  (juxt :time :hash (comp :type :delta) (comp :token :delta)))

(defn api-fetch-borrow-lend-interest!
  "Loads the account's whole interest history, then the borrow/lend supply and
  withdraw events since its first row, and stores both as
  `{:interest rows :ledger rows}`."
  [{:keys [store address request-borrow-lend-interest!
           request-user-non-funding-ledger-updates!
           normalize-supply-ledger-rows
           begin-borrow-lend-interest-load
           apply-borrow-lend-interest-success
           apply-borrow-lend-interest-error
           opts]}]
  (let [opts* (merge {:priority :low} opts)]
    (fetch-for-address!
     {:store store
      :address address
      :request! (fn []
                  (-> (fetch-pages! #(request-borrow-lend-interest! address % opts*)
                                    0 :time-ms (juxt :time-ms :token))
                      (.then (fn [interest]
                               (if-let [first-ms (:time-ms (first interest))]
                                 (-> (fetch-pages! #(request-user-non-funding-ledger-updates! address % nil opts*)
                                                   first-ms :time ledger-row-key)
                                     (.then (fn [ledger]
                                              {:interest interest
                                               :ledger (normalize-supply-ledger-rows ledger)})))
                                 {:interest interest :ledger []})))))
      :begin-load begin-borrow-lend-interest-load
      :apply-success apply-borrow-lend-interest-success
      :apply-error apply-borrow-lend-interest-error})))

(def ^:private operation-labels
  {:supply "Supply"
   :withdraw "Withdraw"
   :borrow "Borrow"
   :repay "Repay"})

(defn- finish-submit!
  [store error]
  (swap! store
         (fn [state]
           (cond-> (assoc-in state [:borrow-lend-ui :modal :submitting?] false)
             error (assoc-in [:borrow-lend-ui :modal :error] error)
             (nil? error) (assoc-in [:borrow-lend-ui :modal :open?] false)))))

(defn api-submit-borrow-lend!
  "Signs and posts a `borrowLend` action through the agent session. On success
  closes the modal, toasts, and reloads Earn and the account's balances."
  [{:keys [store request dispatch! submit-borrow-lend!
           exchange-response-error runtime-error-message show-toast!]
    :or {show-toast! (fn [_store _kind _message] nil)}}]
  (let [state @store
        blocked (account-context/mutations-blocked-message state)
        owner (account-context/owner-address state)
        vault-address (account-context/exchange-vault-address state)
        refresh-address (or (account-context/effective-account-address state) owner)
        {:keys [kind action]} request
        label (get operation-labels kind "Request")
        fail! (fn [text fallback]
                (let [text* (str/trim (str text))
                      message (str label " failed: " (if (seq text*) text* fallback))]
                  (finish-submit! store message)
                  (show-toast! store :error message)))]
    (cond
      (seq blocked) (do (finish-submit! store blocked) (js/Promise.resolve nil))
      (nil? owner) (do (finish-submit! store "Connect your wallet first.") (js/Promise.resolve nil))
      (not (map? action)) (do (finish-submit! store "Nothing to submit.") (js/Promise.resolve nil))
      :else
      (-> (submit-borrow-lend! store owner action
                               (cond-> {} vault-address (assoc :vault-address vault-address)))
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (do
                       (finish-submit! store nil)
                       (show-toast! store :success (str label " submitted."))
                       (dispatch! store nil [[:actions/load-borrow-lend]
                                             [:actions/load-user-data refresh-address]])
                       resp)
                     (do (fail! (exchange-response-error resp) "Unknown exchange error")
                         resp))))
          (.catch (fn [err]
                    (fail! (runtime-error-message err) "Unknown runtime error")
                    nil))))))
