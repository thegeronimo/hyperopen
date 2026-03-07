(ns hyperopen.views.api-wallets.vm
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.wallet.agent-session :as agent-session]))

(defn- sort-state
  [state]
  (let [raw-sort (or (get-in state [:api-wallets-ui :sort])
                     {:column api-wallets-actions/default-sort-column
                      :direction api-wallets-actions/default-sort-direction})]
    {:column (api-wallets-actions/normalize-api-wallet-sort-column (:column raw-sort))
     :direction (api-wallets-actions/normalize-api-wallet-sort-direction (:direction raw-sort))}))

(defn- compare-string-values
  [left right]
  (compare (str/lower-case (or left ""))
           (str/lower-case (or right ""))))

(defn- compare-valid-until
  [left right]
  (let [left-ms (:valid-until-ms left)
        right-ms (:valid-until-ms right)]
    (cond
      (and (nil? left-ms) (nil? right-ms)) 0
      (nil? left-ms) 1
      (nil? right-ms) -1
      :else (compare left-ms right-ms))))

(defn- compare-rows
  [column left right]
  (let [primary (case column
                  :address (compare-string-values (:address left) (:address right))
                  :valid-until (compare-valid-until left right)
                  (compare-string-values (:name left) (:name right)))]
    (if (zero? primary)
      (let [secondary (compare-string-values (:name left) (:name right))]
        (if (zero? secondary)
          (compare-string-values (:address left) (:address right))
          secondary))
      primary)))

(defn- sort-rows
  [rows {:keys [column direction]}]
  (let [descending? (= :desc direction)]
    (->> (or rows [])
         (sort (fn [left right]
                 (let [comparison (compare-rows column left right)]
                   (if descending?
                     (> comparison 0)
                     (< comparison 0)))))
         vec)))

(defn- normalized-generated-private-key
  [state form-address]
  (let [generated-address (get-in state [:api-wallets-ui :generated :address])
        generated-private-key (get-in state [:api-wallets-ui :generated :private-key])]
    (when (= (agent-session/normalize-wallet-address generated-address)
             (agent-session/normalize-wallet-address form-address))
      generated-private-key)))

(defn- valid-until-preview-ms
  [server-time-ms days-valid]
  (when-let [normalized-days (agent-session/normalize-agent-valid-days days-valid)]
    (when (number? server-time-ms)
      (+ server-time-ms
         (* normalized-days 24 60 60 1000)))))

(defn api-wallets-vm
  [state]
  (let [owner-address (account-context/owner-address state)
        spectating? (account-context/spectate-mode-active? state)
        form (merge (api-wallets-actions/default-api-wallet-form)
                    (get-in state [:api-wallets-ui :form]))
        form-errors (api-wallets-actions/api-wallet-form-errors form)
        modal (merge (api-wallets-actions/default-api-wallet-modal-state)
                     (get-in state [:api-wallets-ui :modal]))
        sort (sort-state state)
        rows (sort-rows (cond-> (vec (or (get-in state [:api-wallets :extra-agents]) []))
                          (map? (get-in state [:api-wallets :default-agent-row]))
                          (conj (get-in state [:api-wallets :default-agent-row])))
                        sort)
        loading? (or (true? (get-in state [:api-wallets :loading :extra-agents?]))
                     (true? (get-in state [:api-wallets :loading :default-agent?])))
        error (or (get-in state [:api-wallets :errors :extra-agents])
                  (get-in state [:api-wallets :errors :default-agent]))
        server-time-ms (get-in state [:api-wallets :server-time-ms])
        generated-private-key (normalized-generated-private-key state (:address form))
        authorize-disabled? (or loading?
                                (not (seq owner-address))
                                (not (api-wallets-actions/api-wallet-form-valid? form))
                                (true? (:submitting? modal)))
        modal-type (:type modal)
        modal-confirm-disabled? (or (true? (:submitting? modal))
                                    (case modal-type
                                      :authorize authorize-disabled?
                                      :remove (not (map? (:row modal)))
                                      true))]
    {:connected? (boolean (seq owner-address))
     :owner-address owner-address
     :spectating? spectating?
     :rows rows
     :sort sort
     :loading? loading?
     :error error
     :form form
     :form-errors form-errors
     :form-error (get-in state [:api-wallets-ui :form-error])
     :authorize-disabled? authorize-disabled?
     :modal modal
     :generated-private-key generated-private-key
     :generated-address (get-in state [:api-wallets-ui :generated :address])
     :valid-until-preview-ms (valid-until-preview-ms server-time-ms
                                                     (:days-valid form))
     :modal-confirm-disabled? modal-confirm-disabled?}))
