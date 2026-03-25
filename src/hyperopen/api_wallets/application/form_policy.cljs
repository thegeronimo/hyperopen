(ns hyperopen.api-wallets.application.form-policy
  (:require [clojure.string :as str]
            [hyperopen.api-wallets.application.ui-state :as ui-state]
            [hyperopen.wallet.agent-session :as agent-session]))

(defn form-errors
  [form]
  (let [form* (merge (ui-state/default-form) (or form {}))
        name* (some-> (:name form*) str str/trim)
        address* (some-> (:address form*) agent-session/normalize-wallet-address)
        days-valid* (some-> (:days-valid form*) str str/trim)
        normalized-days (agent-session/normalize-agent-valid-days days-valid*)]
    {:name (when-not (seq name*)
             "Enter an API wallet name.")
     :address (when-not (seq address*)
                "Enter a valid wallet address.")
     :days-valid (when (and (seq days-valid*)
                            (nil? normalized-days))
                   (str "Enter a value from 1 to "
                        agent-session/max-agent-valid-days
                        " days."))}))

(defn form-valid?
  [form]
  (every? nil? (vals (form-errors form))))

(defn first-form-error
  [form]
  (some identity (vals (form-errors form))))

(defn generated-private-key
  [generated-state form-address]
  (let [generated-address (:address generated-state)
        generated-private-key (:private-key generated-state)]
    (when (= (agent-session/normalize-wallet-address generated-address)
             (agent-session/normalize-wallet-address form-address))
      generated-private-key)))

(defn valid-until-preview-ms
  [server-time-ms days-valid]
  (when-let [normalized-days (agent-session/normalize-agent-valid-days days-valid)]
    (when (number? server-time-ms)
      (+ server-time-ms
         (* normalized-days 24 60 60 1000)))))
