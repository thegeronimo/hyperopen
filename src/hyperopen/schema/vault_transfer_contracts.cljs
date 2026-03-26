(ns hyperopen.schema.vault-transfer-contracts
  (:require [cljs.spec.alpha :as s]
            [clojure.string :as str]
            [hyperopen.vaults.domain.identity :as identity]))

(def ^:private max-safe-integer
  js/Number.MAX_SAFE_INTEGER)

(def ^:private success-preview-keys
  [:ok? :mode :vault-address :display-message :request])

(def ^:private failure-preview-keys
  [:ok? :display-message])

(def ^:private request-keys
  [:vault-address :action])

(def ^:private action-keys
  [:type :vaultAddress :isDeposit :usd])

(def ^:private preview-failure-messages
  #{"Invalid vault address."
    "Deposits are disabled for this vault."
    "Enter an amount greater than 0."})

(defn canonical-vault-address?
  [value]
  (some? (identity/normalize-vault-address value)))

(defn safe-usdc-micros?
  [value]
  (and (integer? value)
       (<= 0 value max-safe-integer)))

(defn canonical-decimal-text?
  [value]
  (and (string? value)
       (boolean
        (re-matches #"^(?:(\d+)(?:\.(\d*))?|\.(\d+))$" value))))

(defn normalized-transfer-mode?
  [value]
  (contains? #{:deposit :withdraw} value))

(defn- exact-key-order?
  [value expected]
  (and (map? value)
       (= expected (vec (keys value)))))

(s/def ::vault-address canonical-vault-address?)
(s/def ::mode normalized-transfer-mode?)
(s/def ::usd safe-usdc-micros?)

(defn vault-transfer-action-valid?
  [action]
  (and (exact-key-order? action action-keys)
       (= "vaultTransfer" (:type action))
       (canonical-vault-address? (:vaultAddress action))
       (boolean? (:isDeposit action))
       (safe-usdc-micros? (:usd action))))

(defn vault-transfer-request-valid?
  [request]
  (and (exact-key-order? request request-keys)
       (canonical-vault-address? (:vault-address request))
       (vault-transfer-action-valid? (:action request))
       (= (:vault-address request)
          (get-in request [:action :vaultAddress]))))

(defn preview-success-valid?
  [preview]
  (and (exact-key-order? preview success-preview-keys)
       (true? (:ok? preview))
       (normalized-transfer-mode? (:mode preview))
       (canonical-vault-address? (:vault-address preview))
       (nil? (:display-message preview))
       (vault-transfer-request-valid? (:request preview))
       (= (:vault-address preview)
          (:vault-address (:request preview)))
       (= (= :deposit (:mode preview))
          (get-in preview [:request :action :isDeposit]))
       (or (pos? (get-in preview [:request :action :usd]))
           (and (= :withdraw (:mode preview))
                (zero? (get-in preview [:request :action :usd]))))))

(defn preview-failure-valid?
  [preview]
  (and (exact-key-order? preview failure-preview-keys)
       (false? (:ok? preview))
       (contains? preview-failure-messages (:display-message preview))))

(defn preview-result-valid?
  [preview]
  (or (preview-success-valid? preview)
      (preview-failure-valid? preview)))

(defn assert-vault-transfer-request!
  [request context]
  (when-not (vault-transfer-request-valid? request)
    (throw (js/Error.
            (str "vault transfer request contract validation failed. "
                 "context=" (pr-str context)
                 " request=" (pr-str request)))))
  request)

(defn assert-preview-result!
  [preview context]
  (when-not (preview-result-valid? preview)
    (throw (js/Error.
            (str "vault transfer preview contract validation failed. "
                 "context=" (pr-str context)
                 " preview=" (pr-str preview)))))
  preview)
