(ns hyperopen.builder-codes.actions
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.router :as router]))

(def canonical-route
  "/builderCodes")

(defn- valid-address?
  [value]
  (boolean (and (string? value)
                (re-matches #"0x[a-fA-F0-9]{40}" value))))

(defn normalize-builder-address
  [value]
  (some-> value str str/trim not-empty))

(defn builder-codes-route?
  [path]
  (= canonical-route (router/normalize-path path)))

(defn load-builder-codes-route
  [state path]
  (if-not (builder-codes-route? path)
    []
    (let [owner (account-context/owner-address state)]
      (cond-> [[:effects/save-many
                [[[:builder-codes-ui :error-message] nil]
                 [[:builder-codes-ui :success-message] nil]
                 [[:builder-codes :error] nil]
                 [[:builder-codes :revoking] nil]]]]
        owner (conj [:effects/api-load-builder-codes owner])))))

(defn- save-error-effect
  [message]
  [[:effects/save-many [[[:builder-codes-ui :error-message] message]
                        [[:builder-codes-ui :success-message] nil]]]])

(defn revoke-builder
  [state address]
  (let [blocked-message (account-context/mutations-blocked-message state)
        owner (account-context/owner-address state)
        address* (normalize-builder-address address)]
    (cond
      (seq blocked-message)
      (save-error-effect blocked-message)

      (not (seq owner))
      (save-error-effect "Connect your wallet before removing a builder code.")

      (not (valid-address? address*))
      (save-error-effect "Select a valid builder code row to remove.")

      :else
      [[:effects/save-many [[[:builder-codes-ui :error-message] nil]
                            [[:builder-codes-ui :success-message] nil]
                            [[:builder-codes :revoking] address*]]]
       [:effects/api-revoke-builder {:owner owner
                                     :builder address*}]])))
