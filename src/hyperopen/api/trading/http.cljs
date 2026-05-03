(ns hyperopen.api.trading.http
  (:require [clojure.string :as str]
            [hyperopen.api.trading.debug-exchange-simulator :as debug-exchange-simulator]
            [hyperopen.platform :as platform]
            [hyperopen.schema.contracts :as contracts]))

(def exchange-url "https://api.hyperliquid.xyz/exchange")
(def info-url "https://api.hyperliquid.xyz/info")

(defn json-post! [url body]
  (js/fetch url
            (clj->js {:method "POST"
                      :headers {"Content-Type" "application/json"}
                      :body (js/JSON.stringify (clj->js body))})))

(defn parse-text-body
  [raw status]
  (let [raw* (some-> raw str str/trim)]
    (if (str/blank? raw*)
      {:status "err"
       :error (str "HTTP " status)}
      (try
        (js->clj (js/JSON.parse raw*) :keywordize-keys true)
        (catch :default _
          {:status "err"
           :error raw*})))))

(defn parse-json! [resp]
  (let [parse-response-promise
        (if (fn? (.-text resp))
          (-> (.text resp)
              (.then (fn [raw]
                       (parse-text-body raw (.-status resp)))))
          (-> (.json resp)
              (.then (fn [payload]
                       (js->clj payload :keywordize-keys true)))))]
    (-> parse-response-promise
        (.then (fn [parsed]
                 (when (contracts/validation-enabled?)
                   (contracts/assert-exchange-response!
                    parsed
                    {:boundary :api-trading/parse-json}))
                 parsed)))))

(defn nonce-error-response? [resp]
  (let [text (-> (or (:error resp)
                     (:response resp)
                     (:message resp)
                     "")
                 str
                 str/lower-case)]
    ;; This predicate is intentionally data-only: we only care whether the
    ;; returned error text indicates a nonce mismatch, regardless of whether
    ;; upstream set :status.
    (str/includes? text "nonce")))

(defn response-error-text
  [resp]
  (-> (or (:error resp)
          (:response resp)
          (:message resp)
          "")
      str))

(defn missing-api-wallet-response?
  [resp]
  (let [text (-> (response-error-text resp)
                 str/lower-case)]
    (and (str/includes? text "user or api wallet")
         (str/includes? text "does not exist"))))

(def missing-api-wallet-error-message
  "Agent wallet not recognized by Hyperliquid. Enable Trading again.")

(def missing-api-wallet-preserved-message
  "Agent wallet lookup was inconclusive. Preserved local trading key.")

(defn enable-trading-recovery-error?
  [value]
  (let [text (cond
               (map? value) (response-error-text value)
               :else (some-> value str))]
    (contains? #{missing-api-wallet-error-message
                 missing-api-wallet-preserved-message}
               (some-> text str str/trim))))

(defn normalize-address
  [address]
  (let [text (some-> address str str/trim)]
    (when (seq text)
      (str/lower-case text))))

(defn parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn normalize-display-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn next-nonce [cursor]
  (let [now (platform/now-ms)
        cursor* (when (number? cursor)
                  (js/Math.floor cursor))
        monotonic-candidate (if (number? cursor*)
                              (inc cursor*)
                              now)]
    (max now monotonic-candidate)))

(defn maybe-assert-signed-exchange-payload! [payload action]
  (when (contracts/validation-enabled?)
    (contracts/assert-signed-exchange-payload!
     payload {:boundary :api-trading/post-signed-action
              :action-type (:type action)})))
(defn post-signed-action!
  ([action nonce signature]
   (post-signed-action! action nonce signature {}))
  ([action nonce signature options]
   (let [{:keys [vault-address expires-after]} options
         payload (cond-> {:action action
                          :nonce nonce
                          :signature signature}
                   vault-address (assoc :vaultAddress vault-address)
                   expires-after (assoc :expiresAfter expires-after))]
     (maybe-assert-signed-exchange-payload! payload action)
     (or (debug-exchange-simulator/simulated-fetch-response
          [[:signedActions (:type action)]
           [:signedActions :default]])
         (json-post! exchange-url payload)))))

(defn post-info!
  [body]
  (or (debug-exchange-simulator/simulated-fetch-response
       [[:info (keyword (str (:type body)))]
        [:info :default]])
      (json-post! info-url body)))

(defn fetch-user-role!
  [address]
  (-> (post-info! {:type "userRole"
                   :user address})
      (.then parse-json!)))

(defn user-role-agent-for-owner?
  [owner-address role-response]
  (let [owner* (normalize-address owner-address)
        role (some-> (:role role-response)
                     str
                     str/lower-case)
        linked-user* (normalize-address (or (get-in role-response [:data :user])
                                            (:user role-response)))]
    (and (= role "agent")
         (seq owner*)
         (= owner* linked-user*))))
