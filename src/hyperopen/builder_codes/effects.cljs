(ns hyperopen.builder-codes.effects
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.trading.http :as http]
            [hyperopen.api.trading.user-actions :as user-actions]
            [hyperopen.builder-codes.actions :as builder-codes-actions]))

(defn- route-active?
  [store]
  (builder-codes-actions/builder-codes-route?
   (get-in @store [:router :path] "")))

(defn- owner-address*
  [store owner]
  (or (account-context/normalize-address owner)
      (account-context/owner-address @store)))

(defn- request-json!
  [body]
  (-> (http/post-info! body)
      (.then (fn [resp] (.json resp)))))

(defn- max-fee-percent
  "The maxBuilderFee info response is the approved fee in tenths of a
   basis point; 10 means 1 bp, so 1000 tenths-of-a-bp equal 1 percent."
  [value]
  (when (number? value)
    (str (js/Number (.toFixed (/ value 1000) 4)) "%")))

(defn- valid-builder-address?
  [value]
  (boolean (and (string? value)
                (re-matches #"0x[a-fA-F0-9]{40}" value))))

(defn- fetch-max-fee!
  [owner builder]
  (-> (request-json! {"type" "maxBuilderFee"
                      "user" owner
                      "builder" builder})
      (.then (fn [fee]
               {:address builder
                :max-fee (max-fee-percent fee)}))
      (.catch (fn [_err]
                {:address builder
                 :max-fee nil}))))

(defn- fetch-builder-rows!
  [owner]
  (-> (request-json! {"type" "approvedBuilders"
                      "user" owner})
      (.then (fn [builders]
               (let [addresses (->> (js->clj builders)
                                    (map str/trim)
                                    (filter valid-builder-address?)
                                    distinct
                                    vec)]
         (if (empty? addresses)
           []
           (-> (js/Promise.all
                (to-array (mapv (fn [builder]
                                  (fetch-max-fee! owner builder))
                                addresses)))
               (.then vec))))))))

(defn- apply-rows!
  [store owner rows]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in [:builder-codes :owner] owner)
               (assoc-in [:builder-codes :rows] rows)
               (assoc-in [:builder-codes :loading?] false)
               (assoc-in [:builder-codes :error] nil)))))

(defn- apply-load-error!
  [store message]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in [:builder-codes :loading?] false)
               (assoc-in [:builder-codes :error] message)))))

(defn api-load-builder-codes!
  [{:keys [store]} owner]
  (if-not (route-active? store)
    (js/Promise.resolve nil)
    (let [owner* (owner-address* store owner)]
      (if-not (seq owner*)
        (do
          (swap! store assoc-in [:builder-codes :rows] [])
          (js/Promise.resolve nil))
        (do
          (swap! store
                 (fn [state]
                   (-> state
                       (assoc-in [:builder-codes :loading?] true)
                       (assoc-in [:builder-codes :error] nil))))
          (-> (fetch-builder-rows! owner*)
              (.then (fn [rows]
                       (apply-rows! store owner* rows)
                       rows))
              (.catch (fn [_err]
                        (apply-load-error!
                         store
                         "Failed to load builder codes. Check your connection and try again.")))))))))

(defn- exchange-response-error
  [resp]
  (or (:error resp)
      (:response resp)
      (:message resp)
      "Unknown exchange error"))

(defn- runtime-error-message
  [err]
  (or (some-> err .-message)
      (str err)))

(defn- set-error-message!
  [store message]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in [:builder-codes :revoking] nil)
               (assoc-in [:builder-codes-ui :error-message] message)
               (assoc-in [:builder-codes-ui :success-message] nil)))))

(defn- set-success!
  [store]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in [:builder-codes :revoking] nil)
               (assoc-in [:builder-codes-ui :error-message] nil)
               (assoc-in [:builder-codes-ui :success-message] "Builder code removed.")))))

(defn api-revoke-builder!
  "Removing an approved builder code is an approveBuilderFee exchange action
   with maxFeeRate 0%: Hyperliquid revokes the approval once the approved
   max fee is signed down to zero. This is how the official UI removes
   builder codes, so the low-level approveBuilderFee signer is still used."
  [{:keys [store
           load-builder-codes!
           submit-approve-builder-fee!]
    :or {load-builder-codes! api-load-builder-codes!
         submit-approve-builder-fee! user-actions/submit-approve-builder-fee!}}
   {:keys [owner builder]}]
  (let [owner* (owner-address* store owner)]
    (if-not (seq owner*)
      (set-error-message! store "Connect your wallet before removing a builder code.")
      (-> (submit-approve-builder-fee!
           store
           owner*
           {:type "approveBuilderFee"
            :builder builder
            :maxFeeRate "0%"})
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (do
                       (set-success! store)
                       (load-builder-codes! {:store store} owner*))
                     (set-error-message! store (str (exchange-response-error resp))))))
          (.catch (fn [err]
                    (set-error-message! store (runtime-error-message err))))))))
