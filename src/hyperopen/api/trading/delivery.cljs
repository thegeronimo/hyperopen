(ns hyperopen.api.trading.delivery
  "Agent-signed action send path that reports DELIVERY separately from the
  venue's answer (HO-SPEC-014 v2.1 sections 8.1, 8.3, 8.4).

  Order of steps (spec 8.1): reserve nonce -> sign -> COMMIT the send-attempt
  record (step 7) -> final admission guard immediately before HTTP (step 8) ->
  HTTP. `:delivery :not-sent` is returned only when the failure provably
  happened before the HTTP call. Once the call is invoked, any rejection,
  timeout, non-2xx status or unreadable body is `:unknown`. This function never
  retries, never rejects, and always settles: a stalled request resolves
  `:unknown` after `:timeout-ms`."
  (:require [hyperopen.api.trading.agent-actions :as agent-actions]
            [hyperopen.api.trading.nonce-coordinator :as nonce-coordinator]
            [hyperopen.platform :as platform]))

(def default-timeout-ms
  "How long to wait for the exchange after the request is sent."
  35000)

(def ^:private acceptable-coordination
  "Cross-tab nonce coordination a Vol Range send requires (spec 7.4, 8.2)."
  #{:web-locks})

(defn- error-text [e]
  (cond (instance? js/Error e) (.-message e)
        (some? e) (str e)
        :else "unknown error"))

(defn- not-sent [phase extra]
  (merge {:delivery :not-sent :phase phase :nonce nil :body nil :error nil :http-status nil
          :attempt-recorded? false}
         extra))

(defn- unknown [extra]
  (merge {:delivery :unknown :phase :transport :body nil :http-status nil :attempt-recorded? true} extra))

(defn- parse-strict
  "{:status n :body parsed-map-or-::unreadable}. Unlike `http/parse-json!` this
  never turns an unreadable body into a venue-looking {:status \"err\"} map."
  [resp]
  (let [status (.-status resp)]
    (-> (if (fn? (.-text resp))
          (.text resp)
          (-> (.json resp) (.then (fn [payload] (js/JSON.stringify payload)))))
        (.then (fn [raw]
                 {:status status
                  :body (try
                          (let [parsed (js->clj (js/JSON.parse raw) :keywordize-keys true)]
                            (if (map? parsed) parsed ::unreadable))
                          (catch :default _ ::unreadable))})))))

(defn- with-timeout
  "Settle with `promise`, or with ::timeout after `ms`."
  [promise ms]
  (js/Promise.race
   #js [promise
        (js/Promise. (fn [resolve _] (js/setTimeout #(resolve ::timeout) ms)))]))

(defn- phase-error [phase e]
  (if (:phase (ex-data e)) e (ex-info (name phase) {:phase phase :error e})))

(def default-pre-send-timeout-ms
  "Bound on everything before the HTTP call (crypto load, nonce lock, signing,
  attempt commit, final guard)."
  15000)

(defn sign-and-post-with-delivery!
  "Sign and post `action` for `owner-address` with a freshly reserved nonce.

  Required option: :expires-after (epoch ms action deadline, spec 8.3).
  Options: :vault-address, :is-mainnet, :timeout-ms,
  :on-send-attempt! (fn [{:keys [nonce expires-after]}] -> Promise resolving
  only once the attempt record is COMMITTED; required unless
  :allow-unrecorded-attempt?), :before-send! (fn [ctx] -> Promise of true to
  send, anything else to abort), :pre-send-timeout-ms, and
  :allow-in-tab-coordination? / :allow-unrecorded-attempt? (tests only).

  Resolves to {:delivery :not-sent|:response|:unknown :phase kw :nonce n
  :expires-after ms :http-status n :body map :error text :attempt-recorded? bool}.
  `:attempt-recorded? true` with `:not-sent` means the caller must record the
  proven pre-send outcome for the committed attempt."
  [store owner-address action {:keys [vault-address expires-after is-mainnet before-send! on-send-attempt!
                                      timeout-ms pre-send-timeout-ms allow-in-tab-coordination?
                                      allow-unrecorded-attempt?]}]
  (let [options {:vault-address vault-address :expires-after expires-after
                 :is-mainnet (if (nil? is-mainnet) true is-mainnet)}
        network (nonce-coordinator/network-for-options options)
        acceptable (cond-> acceptable-coordination allow-in-tab-coordination? (conj :in-tab-only))]
    (cond
      ;; The exchange transport is mainnet-only (spec 3.2).
      (not= network :hyperliquid-mainnet)
      (js/Promise.resolve (not-sent :network-mismatch
                                    {:error "A testnet signature cannot be sent to the mainnet exchange."}))

      (not (and (number? expires-after) (js/isFinite expires-after)))
      (js/Promise.resolve (not-sent :invalid-options {:error "An action deadline (:expires-after) is required."}))

      (and (nil? on-send-attempt!) (not allow-unrecorded-attempt?))
      (js/Promise.resolve (not-sent :invalid-options
                                    {:error "A committed send-attempt record (:on-send-attempt!) is required."}))

      :else
      (let [http-started? (atom false)
            abandoned? (atom false)
            timer (atom nil)]
       (js/Promise.race
        #js [;; Bound the pre-send phase: if it has not reached HTTP in time it is
             ;; abandoned (it can no longer send) and the result is :not-sent.
             (js/Promise.
              (fn [resolve _]
                (reset! timer
                 (js/setTimeout (fn []
                                 (when-not @http-started?
                                   (reset! abandoned? true)
                                   (resolve (not-sent :pre-send-timeout
                                                      {:error "Timed out before sending."}))))
                               (or pre-send-timeout-ms default-pre-send-timeout-ms)))))
      (-> (agent-actions/resolve-signing-context! store owner-address)
          (.then
           (fn [{:keys [crypto session]}]
             (-> (nonce-coordinator/reserve-nonce! {:network network
                                                    :signer (agent-actions/signer-address crypto session)
                                                    :floor (agent-actions/session-nonce-floor store session)})
                 (.then
                  (fn [{:keys [nonce coordination]}]
                    (let [ctx {:nonce nonce :expires-after expires-after}
                          recorded? (atom false)]
                      (if-not (contains? acceptable coordination)
                        (not-sent :coordination-unavailable
                                  {:nonce nonce :expires-after expires-after
                                   :error (str "Nonce coordination is " (name coordination)
                                               "; refusing to send.")})
                        (-> (agent-actions/sign-action! crypto session action nonce options)
                            (.catch (fn [e] (throw (phase-error :signing e))))
                            ;; Step 7: commit the send-attempt record.
                            (.then (fn [sig]
                                     (-> (js/Promise.resolve nil)
                                         (.then (fn [_] (when on-send-attempt! (on-send-attempt! ctx))))
                                         (.then (fn [_] (reset! recorded? (some? on-send-attempt!)) sig))
                                         (.catch (fn [e] (throw (phase-error :send-attempt-record e)))))))
                            ;; Step 8: final admission guard, then the deadline.
                            (.then (fn [sig]
                                     (-> (js/Promise.resolve nil)
                                         (.then (fn [_] (if before-send! (before-send! ctx) true)))
                                         (.then (fn [ok]
                                                  (when-not (true? ok)
                                                    (throw (ex-info "guard" {:phase :final-guard :error ok})))
                                                  (when-not (< (platform/now-ms) expires-after)
                                                    (throw (ex-info "deadline" {:phase :deadline-elapsed
                                                                                :error "The action deadline has passed."})))
                                                  sig))
                                         (.catch (fn [e] (throw (phase-error :final-guard e)))))))
                            (.then (fn [sig]
                                     (when @abandoned?
                                       (throw (ex-info "abandoned" {:phase :pre-send-timeout
                                                                    :error "Timed out before sending."})))
                                     (reset! http-started? true)
                                     (try (agent-actions/record-nonce-cursor! store owner-address session nonce)
                                          (catch :default _ nil))
                                     ;; From here on the request may have left the browser.
                                     (-> (with-timeout
                                           (-> (js/Promise.resolve nil)
                                               (.then (fn [_] (agent-actions/post-signed! action nonce sig options)))
                                               (.then parse-strict))
                                           (or timeout-ms default-timeout-ms))
                                         (.then (fn [r]
                                                  (let [base {:nonce nonce :expires-after expires-after
                                                              :attempt-recorded? @recorded?}]
                                                    (cond
                                                      (= ::timeout r)
                                                      (unknown (assoc base :error "No exchange response before the timeout"))

                                                      (= ::unreadable (:body r))
                                                      (unknown (assoc base :phase :response :http-status (:status r)
                                                                      :error "Unreadable exchange response"))

                                                      ;; A JSON body on a non-2xx status (proxy, gateway) is
                                                      ;; not a venue answer: never treat it as a rejection.
                                                      (not (<= 200 (or (:status r) 200) 299))
                                                      (unknown (assoc base :phase :response :http-status (:status r)
                                                                      :error (str "HTTP " (:status r))))

                                                      :else
                                                      (merge base {:delivery :response :phase :response
                                                                   :http-status (:status r) :body (:body r)
                                                                   :error nil})))))
                                         (.catch (fn [e]
                                                   (unknown {:nonce nonce :expires-after expires-after
                                                             :attempt-recorded? @recorded?
                                                             :error (error-text e)}))))))
                            (.catch (fn [e]
                                      (let [{:keys [phase error]} (ex-data e)]
                                        (not-sent (or phase :signing)
                                                  {:nonce nonce :expires-after expires-after
                                                   :attempt-recorded? @recorded?
                                                   :error (error-text (or error e))})))))))))
                 (.catch (fn [e] (not-sent :nonce-reservation {:error (error-text e)}))))))
          (.catch (fn [e] (not-sent :signing-context {:error (error-text e)})))
          (.finally (fn [] (js/clearTimeout @timer))))])))))
