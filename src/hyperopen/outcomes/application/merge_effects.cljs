(ns hyperopen.outcomes.application.merge-effects
  "Effect interpreter for `:effects/api-submit-outcome-merge`: signs and posts
   the `userOutcome` merge action through the agent session, then updates the
   ticket and reloads the account's balances."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]))

(defn- finish-submit!
  [store error]
  (swap! store
         (fn [state]
           (if error
             (-> state
                 (assoc-in [:outcome-ticket :merge :submitting?] false)
                 (assoc-in [:outcome-ticket :merge :error] error))
             (assoc-in state [:outcome-ticket :merge] nil)))))

(defn api-submit-outcome-merge!
  [{:keys [store request dispatch! submit-outcome-merge!
           exchange-response-error runtime-error-message show-toast!]
    :or {show-toast! (fn [_store _kind _message] nil)}}]
  (let [state @store
        blocked (account-context/mutations-blocked-message state)
        owner (account-context/owner-address state)
        vault-address (account-context/exchange-vault-address state)
        refresh-address (or (account-context/effective-account-address state) owner)
        {:keys [action pairs]} request
        fail! (fn [text fallback]
                (let [text* (str/trim (str text))
                      message (str "Merge failed: " (if (seq text*) text* fallback))]
                  (finish-submit! store message)
                  (show-toast! store :error message)))]
    (cond
      (seq blocked) (do (finish-submit! store blocked) (js/Promise.resolve nil))
      (nil? owner) (do (finish-submit! store "Connect your wallet first.") (js/Promise.resolve nil))
      (not (map? action)) (do (finish-submit! store "Nothing to submit.") (js/Promise.resolve nil))
      :else
      (-> (submit-outcome-merge! store owner action
                                 (cond-> {} vault-address (assoc :vault-address vault-address)))
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (do
                       (finish-submit! store nil)
                       (show-toast! store :success (str "Merged " pairs (if (= 1 pairs) " pair." " pairs.")))
                       (dispatch! store nil [[:actions/load-user-data refresh-address]])
                       resp)
                     (do (fail! (exchange-response-error resp) "Unknown exchange error")
                         resp))))
          (.catch (fn [err]
                    (fail! (runtime-error-message err) "Unknown runtime error")
                    nil))))))
