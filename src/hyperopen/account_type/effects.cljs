(ns hyperopen.account-type.effects
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.default :as api-default]
            [hyperopen.api.trading.user-actions :as user-actions]
            [hyperopen.platform :as platform]))

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

(defn- set-error!
  [store message]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in [:account-type-ui :submitting?] false)
               (assoc-in [:account-type-ui :error-message] message)))))

(defn refresh-user-abstraction!
  "Refetches `userAbstraction` for `owner` with the caches bypassed, so the
   account type chip and modal reflect the mode that was just established."
  [store owner]
  (let [owner* (account-context/normalize-address owner)
        token (platform/now-ms)]
    (when (seq owner*)
      (api-default/fetch-user-abstraction!
       store
       owner*
       {:priority :high
        :cache-ttl-ms 1
        :force-refresh? true
        :dedupe-key [:user-abstraction owner* token]
        :cache-key [:user-abstraction owner* token]}))))

(defn api-set-user-abstraction!
  "Changing the account type is the user-signed `userSetAbstraction` exchange
   action (Unified Account -> \"unifiedAccount\", Portfolio Margin ->
   \"portfolioMargin\", Manual -> \"disabled\"). On success the modal closes and
   the projected account abstraction is force-refreshed."
  [{:keys [store
           submit-user-set-abstraction!
           refresh-user-abstraction!]
    :or {submit-user-set-abstraction! user-actions/submit-user-set-abstraction!
         refresh-user-abstraction! refresh-user-abstraction!}}
   {:keys [owner abstraction]}]
  (let [owner* (account-context/normalize-address owner)]
    (if-not (seq owner*)
      (do
        (set-error! store "Connect your wallet before changing account type.")
        (js/Promise.resolve nil))
      (-> (submit-user-set-abstraction!
           store
           owner*
           {:type "userSetAbstraction"
            :user (str/lower-case owner*)
            :abstraction abstraction})
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (do
                       (swap! store
                              (fn [state]
                                (-> state
                                    (assoc-in [:account-type-ui :modal-open?] false)
                                    (assoc-in [:account-type-ui :submitting?] false)
                                    (assoc-in [:account-type-ui :error-message] nil))))
                       (refresh-user-abstraction! store owner*)
                       resp)
                     (do
                       (set-error! store (str (exchange-response-error resp)))
                       resp))))
          (.catch (fn [err]
                    (set-error! store (runtime-error-message err))
                    nil))))))
