(ns hyperopen.vaults.domain.transfer-policy
  (:require [clojure.string :as str]
            [hyperopen.utils.parse :as parse-utils]
            [hyperopen.vaults.domain.identity :as identity]))

(def default-vault-transfer-mode
  :deposit)

(def ^:private vault-usdc-micros-scale
  1000000)

(def ^:private vault-transfer-modes
  #{:deposit :withdraw})

(defn normalize-vault-transfer-mode
  [value]
  (let [mode (cond
               (keyword? value) value
               (string? value) (some-> value str/trim str/lower-case keyword)
               :else nil)]
    (if (contains? vault-transfer-modes mode)
      mode
      default-vault-transfer-mode)))

(defn default-vault-transfer-modal-state
  []
  {:open? false
   :mode default-vault-transfer-mode
   :vault-address nil
   :amount-input ""
   :withdraw-all? false
   :submitting? false
   :error nil})

(defn parse-usdc-micros
  ([value]
   (parse-usdc-micros value nil))
  ([value locale]
   (let [text (parse-utils/normalize-localized-decimal-input value locale)]
     (when-let [[_ int-part frac-part frac-only]
                (and (seq text)
                     (re-matches #"^(?:(\d+)(?:\.(\d*))?|\.(\d+))$" text))]
       (let [whole (or (parse-utils/parse-int-value int-part) 0)
             fraction-source (or frac-part frac-only "")
             fraction-padded (subs (str fraction-source "000000") 0 6)
             fraction (or (parse-utils/parse-int-value fraction-padded) 0)
             whole-micros (* whole vault-usdc-micros-scale)
             micros (+ whole-micros fraction)]
         (when (and (number? micros)
                    (<= micros js/Number.MAX_SAFE_INTEGER))
           micros))))))

(defn vault-transfer-deposit-allowed?
  [state vault-address]
  (let [vault-address* (identity/normalize-vault-address vault-address)
        allow-deposits? (true? (get-in state [:vaults :details-by-address vault-address* :allow-deposits?]))
        wallet-address (identity/vault-wallet-address state)
        leader-address (identity/vault-leader-address state vault-address*)
        leader? (and (string? wallet-address)
                     (= wallet-address leader-address))
        liquidator-vault? (= "liquidator"
                             (some-> (identity/vault-entity-name state vault-address*)
                                     str/lower-case
                                     str/trim))]
    (and (string? vault-address*)
         (not liquidator-vault?)
         (or leader? allow-deposits?))))

(defn vault-transfer-preview
  ([state modal]
   (vault-transfer-preview {} state modal))
  ([{:keys [route-vault-address-fn]} state modal]
   (let [modal* (merge (default-vault-transfer-modal-state)
                       (if (map? modal) modal {}))
         route-vault-address (when (fn? route-vault-address-fn)
                               (route-vault-address-fn state))
         vault-address (or (identity/normalize-vault-address (:vault-address modal*))
                           route-vault-address)
         mode (normalize-vault-transfer-mode (:mode modal*))
         withdraw-all? (and (= mode :withdraw)
                            (true? (:withdraw-all? modal*)))
         amount-input (:amount-input modal*)
         locale (get-in state [:ui :locale])
         amount-micros (if withdraw-all?
                         0
                         (parse-usdc-micros amount-input locale))
         deposit-allowed? (vault-transfer-deposit-allowed? state vault-address)]
     (cond
       (nil? vault-address)
       {:ok? false
        :display-message "Invalid vault address."}

       (and (= mode :deposit)
            (not deposit-allowed?))
       {:ok? false
        :display-message "Deposits are disabled for this vault."}

       (and (not withdraw-all?)
            (or (nil? amount-micros)
                (<= amount-micros 0)))
       {:ok? false
        :display-message "Enter an amount greater than 0."}

       :else
       {:ok? true
        :mode mode
        :vault-address vault-address
        :display-message nil
        :request {:vault-address vault-address
                  :action {:type "vaultTransfer"
                           :vaultAddress vault-address
                           :isDeposit (= mode :deposit)
                           :usd amount-micros}}}))))
