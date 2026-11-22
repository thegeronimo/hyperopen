(ns hyperopen.account-type.actions
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.account-type.options :as options]))

(defn current-option
  "The account type option the connected account currently runs on, derived
   from the `userAbstraction` payload already projected into app state."
  [state]
  (options/abstraction->option (get-in state [:account :abstraction-raw])))

(defn- save-error-effect
  [message]
  [[:effects/save-many [[[:account-type-ui :error-message] message]]]])

(defn open-account-type-modal
  [state]
  [[:effects/save-many [[[:account-type-ui :modal-open?] true]
                        [[:account-type-ui :selected] (current-option state)]
                        [[:account-type-ui :submitting?] false]
                        [[:account-type-ui :error-message] nil]]]])

(defn close-account-type-modal
  [_state]
  [[:effects/save-many [[[:account-type-ui :modal-open?] false]
                        [[:account-type-ui :submitting?] false]
                        [[:account-type-ui :error-message] nil]]]])

(defn select-account-type
  [_state option]
  (if (options/valid-option? option)
    [[:effects/save-many [[[:account-type-ui :selected] option]]]]
    []))

(defn submit-account-type
  [state option]
  (let [blocked-message (account-context/mutations-blocked-message state)
        owner (account-context/owner-address state)]
    (cond
      (seq blocked-message)
      (save-error-effect blocked-message)

      (not (seq owner))
      (save-error-effect "Connect your wallet before changing account type.")

      (not (options/valid-option? option))
      (save-error-effect "Select an account type.")

      (= option (current-option state))
      (save-error-effect (str (options/title option) " is already your account type."))

      :else
      [[:effects/save-many [[[:account-type-ui :submitting?] true]
                            [[:account-type-ui :error-message] nil]]]
       [:effects/api-set-user-abstraction {:owner owner
                                           :abstraction (options/option->abstraction option)}]])))
