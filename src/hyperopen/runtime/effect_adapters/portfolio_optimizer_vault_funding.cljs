(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-vault-funding
  "Interpreter for a vault-funding run: sends the run's `vaultTransfer` rows
  one at a time (withdrawals first), agent-signed, recording each outcome in
  the run as it lands. Sequential on purpose: the signer's nonce cursor is
  only saved after each response, and deposits may spend withdrawn cash."
  (:require [clojure.string :as str]
            [hyperopen.api.default :as api]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.order.feedback-runtime :as feedback-runtime]
            [hyperopen.portfolio.optimizer.application.vault-funding.model :as model]
            [hyperopen.portfolio.optimizer.application.vault-funding.run :as run]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.runtime.effect-adapters.common :as common]
            [hyperopen.runtime.effect-adapters.vaults :as vault-adapters]
            [nexus.registry :as nxr]))

(def ^:dynamic *submit-vault-transfer!* trading-api/submit-vault-transfer!)

(def ^:dynamic *dispatch!* nxr/dispatch)

(def ^:dynamic *now-ms* (fn [] (.now js/Date)))

(def ^:dynamic *refresh-vault-equities!*
  (fn [store address]
    (vault-adapters/api-fetch-user-vault-equities-effect
     nil store address {:priority :high :force-refresh? true :skip-route-gate? true})))

(def ^:dynamic *toast!* feedback-runtime/set-order-feedback-toast!)

(def ^:dynamic *request-vault-details!* api/request-vault-details!)

(def ^:private run-path contracts/ui-vault-funding-run-path)

(defn- env
  "Seams captured by value so test rebinding survives the promise chain."
  []
  {:submit! *submit-vault-transfer!*
   :dispatch! *dispatch!*
   :now-ms *now-ms*
   :refresh-vault-equities! *refresh-vault-equities!*
   :toast! *toast!*})

(defn- swap-run!
  "Apply `f` to the run only while it is still attempt `attempt-id`."
  [store attempt-id f & args]
  (swap! store update-in run-path
         (fn [current]
           (if (= attempt-id (:attempt-id current))
             (apply f current args)
             current))))

(defn- precheck
  "Reason the batch cannot send, re-read inside the effect (and before every
  transfer) because state can change after the confirm click: the account
  must still be the wallet the run was planned for, writable, with trading on."
  [state run-owner]
  (or (model/account-block state)
      (when (not= run-owner (model/owner state))
        "The connected wallet changed since this run was planned.")
      (when-not (= :ready (get-in state [:wallet :agent :status]))
        "Trading is not enabled. Enable trading and retry.")))

(def ^:private precheck-failed ::precheck-failed)

(def ^:private reasonless-errors
  "Refusals Hyperliquid sends without saying why."
  #{"vault transfer error"})

(defn- refusal-message
  [resp]
  (let [text (common/exchange-response-error resp)]
    (cond
      (nil? text) "Transfer refused."
      (contains? reasonless-errors (str/lower-case (str/trim text)))
      (str "Hyperliquid refused it without giving a reason (\u201c" text "\u201d). Retry it, or leave it as cash.")
      :else text)))

(defn- record-response!
  "An exchange refusal ({:status \"err\" :response text}) means the transfer
  did not happen: :failed, safe to retry. Anything else that is not \"ok\"
  (no body, an HTTP error page) leaves its fate unknown: never resent."
  [store attempt-id row-id resp]
  (cond
    (= precheck-failed resp) nil
    (and (map? resp) (= "ok" (:status resp))) (swap-run! store attempt-id run/mark-done row-id)
    (and (map? resp) (= "err" (:status resp)) (some? (:response resp)))
    (swap-run! store attempt-id run/mark-failed row-id (refusal-message resp))
    :else (swap-run! store attempt-id run/mark-unknown row-id
                     (str "No clear answer from the exchange"
                          (when-let [text (and (map? resp) (common/exchange-response-error resp))]
                            (str " (" text ")"))
                          ". Check your vault balance before sending again."))))

(defn- claim!
  "Atomically move the row from :queued to :sending; true when this chain
  won it, so a row is never sent twice."
  [store attempt-id row-id]
  (let [[before after] (swap-vals! store update-in run-path
                                   (fn [current]
                                     (if (= attempt-id (:attempt-id current))
                                       (run/claim current row-id)
                                       current)))
        status-of (fn [state] (:status (run/row-by-id (get-in state run-path) row-id)))]
    (and (= :queued (status-of before)) (= :sending (status-of after)))))

(defn- send-row!
  [{:keys [submit!]} store attempt-id run-owner {:keys [row-id]}]
  (let [current (get-in @store run-path)
        row (run/row-by-id current row-id)]
    (if-not (and row (= attempt-id (:attempt-id current)))
      (js/Promise.resolve nil)
      (case (run/next-action current row)
        :pass (js/Promise.resolve nil)
        :skip-aborted (do (swap-run! store attempt-id run/mark-skipped row-id :aborted)
                          (js/Promise.resolve nil))
        :skip-halted (do (swap-run! store attempt-id run/mark-skipped row-id :halted)
                         (js/Promise.resolve nil))
        :send
        (if-not (claim! store attempt-id row-id)
          (js/Promise.resolve nil)
          (-> (js/Promise.resolve nil)
              (.then (fn [_]
                       (if-let [reason (precheck @store run-owner)]
                         (do (swap-run! store attempt-id run/mark-failed row-id reason)
                             precheck-failed)
                         (submit! store run-owner (run/transfer-action row)))))
              (.then (fn [resp] (record-response! store attempt-id row-id resp)))
              (.catch (fn [err]
                        ;; The request may have reached the exchange before it
                        ;; broke off, so its outcome is unknown, not failed.
                        (swap-run! store attempt-id run/mark-unknown row-id
                                   (str (common/runtime-error-message err)
                                        ". Check your vault balance before sending again."))))))))))

(defn- outcome-toast
  [{:keys [status]} {:keys [done total unknown]}]
  (case status
    :done [:success (str "Vault funding complete: " done (if (= 1 done) " transfer" " transfers") " sent.")]
    [:error (str done " of " total " vault transfers sent"
                 (when (pos? unknown) (str "; " unknown " could not be confirmed"))
                 ". Open the sheet for details.")]))

(defn- finish!
  [{:keys [now-ms toast! dispatch! refresh-vault-equities!]} store attempt-id owner]
  (swap-run! store attempt-id run/finish (now-ms))
  (let [current (get-in @store run-path)]
    (when (= attempt-id (:attempt-id current))
      (when-let [[kind message] (outcome-toast current (run/summary current))]
        (toast! store kind message))
      (when owner
        ;; Always, even when nothing landed for sure: an :unknown transfer may
        ;; have, and the next plan must start from real holdings. Vault
        ;; equities sit behind a 5 s response cache, so force past it.
        (refresh-vault-equities! store owner)
        (dispatch! store nil [[:actions/load-user-data owner]])))))

(defn run-vault-funding-effect
  "Walk the current run's rows. Returns a promise that always settles."
  [_ctx store]
  (let [deps (env)
        state @store
        current (get-in state run-path)
        attempt-id (:attempt-id current)
        owner (:owner current)]
    (cond
      (not= :running (:status current))
      (js/Promise.resolve nil)

      (precheck state owner)
      (do (swap-run! store attempt-id run/fail-run (precheck state owner) ((:now-ms deps)))
          (js/Promise.resolve nil))

      :else
      (-> (reduce (fn [chain row]
                    (.then chain (fn [_] (send-row! deps store attempt-id owner row))))
                  (js/Promise.resolve nil)
                  (:rows current))
          (.then (fn [_] (finish! deps store attempt-id owner)))
          (.catch (fn [err]
                    (swap-run! store attempt-id run/fail-run
                               (common/runtime-error-message err) ((:now-ms deps)))))))))

(def ^:private limits-path
  (conj contracts/ui-vault-funding-path :limits))

(defn fetch-vault-funding-limits-effect
  "Read each vault leg's live vaultDetails, one at a time, into the sheet's
  deposit limits (deposits on/off, leader, and the most it will take before
  the leader's share falls under 5%). A failed read leaves that vault
  unlimited: the exchange's answer then shows per step. Always settles."
  [_ctx store]
  (let [request! *request-vault-details!*
        now-ms *now-ms*
        state @store
        result (get-in state contracts/last-successful-run-result-path)
        addresses (distinct (map :address (model/targets state result)))
        token (str "limits_" (now-ms))
        current? #(= token (:token %))]
    (swap! store assoc-in limits-path {:status :loading :token token :by-address {}})
    (-> (reduce (fn [chain address]
                  (.then chain
                         (fn [_]
                           (-> (request! address {:priority :high :dedupe-key [:vault-funding-limits address]})
                               (.then (fn [details]
                                        (swap! store update-in limits-path
                                               (fn [limits]
                                                 (if (and (current? limits) (map? details))
                                                   (assoc-in limits [:by-address address] (model/deposit-limit details))
                                                   limits)))))
                               (.catch (fn [_] nil))))))
                (js/Promise.resolve nil)
                addresses)
        (.then (fn [_]
                 (swap! store update-in limits-path #(if (current? %) (assoc % :status :ready) %)))))))

