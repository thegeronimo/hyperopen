(ns hyperopen.borrow-lend.effects
  "Side-effect interpreters for Earn: info fetches and the signed `borrowLend`
  submit. Collaborators (request fns, projections, toast, dispatch) are
  injected so these functions stay testable without a network."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.borrow-lend.account :as bl-account]))

;; Interest history loaded with the page.
(def interest-window-ms (* 30 24 60 60 1000))

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

(defn api-fetch-borrow-lend-interest!
  [{:keys [store address request-borrow-lend-interest!
           begin-borrow-lend-interest-load
           apply-borrow-lend-interest-success
           apply-borrow-lend-interest-error
           now-ms-fn
           opts]
    :or {now-ms-fn #(.now js/Date)}}]
  (let [start-ms (- (now-ms-fn) interest-window-ms)]
    (fetch-for-address!
     {:store store
      :address address
      :request! #(request-borrow-lend-interest! address start-ms (or opts {}))
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
