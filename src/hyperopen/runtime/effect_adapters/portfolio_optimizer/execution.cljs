(ns hyperopen.runtime.effect-adapters.portfolio-optimizer.execution
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.order.feedback-runtime :as feedback-runtime]
            [hyperopen.portfolio.optimizer.application.execution :as execution]
            [hyperopen.portfolio.optimizer.application.execution-carryover :as carryover]
            [hyperopen.portfolio.optimizer.application.execution-cloid :as cloid]
            [hyperopen.portfolio.optimizer.application.execution-workflow :as execution-workflow]
            [hyperopen.api.trading.cancel-request :as cancel-request]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(defn- execution-outcome-toast
  "Plain-language summary toast for a finished run, so success/failure is announced (the toast
  region is aria-live) and stays visible for a user who tab-switched mid-run — most importantly
  for resting/limit orders, where no fill event ever fires. Returns [kind message] or nil."
  [ledger]
  (let [by (frequencies (map :status (:rows ledger)))
        filled (get by :submitted 0)
        resting (get by :resting 0)
        failed (get by :failed 0)
        blocked (get by :blocked 0)
        blocked-note (when (pos? blocked) (str " · " blocked " below minimum"))]
    (cond
      (and (pos? failed) (pos? resting))
      [:error {:headline "Execution partial"
               :subline (str filled " filled · " resting " resting · " failed
                             " failed — resting orders keep working; review from the Execution tab.")}]
      (pos? failed)
      [:error {:headline "Execution halted"
               :subline (str filled " filled · " failed
                             " failed — review and resume from the Execution tab.")}]
      (pos? resting)
      [:info {:headline "Orders resting on the book"
              :subline (str resting " resting"
                            (when (pos? filled) (str " · " filled " filled"))
                            blocked-note " — they fill as the market reaches your price.")}]
      (pos? filled)
      [:success {:headline "Execution complete"
                 :subline (str filled " order" (when (not= 1 filled) "s")
                               " filled on Hyperliquid" blocked-note ".")}]
      :else nil)))

(defn- mark-row-failed
  [row err]
  (assoc row
         :status :failed
         :error {:message (execution-workflow/error-message err)}))

(defn- submit-action!
  [submit-order! store target action]
  (apply submit-order!
         (cond-> [store (:owner-address target) action]
           (seq (:options target)) (conj (:options target)))))

(defn- submit-actions!
  [submit-order! store target actions]
  (reduce
   (fn [promise action]
     (.then promise
            (fn [responses]
              (-> (submit-action! submit-order! store target action)
                  (.then (fn [resp]
                           (conj responses resp)))))))
   (js/Promise.resolve [])
   actions))

(defn- failed-pre-action-response
  [responses]
  (some #(when-not (execution/response-ok? %) %) responses))

(defn- random-optimizer-cloid
  "A fresh magic-tagged cloid for one optimizer order. The 12-byte uniqueness suffix
  comes from crypto.getRandomValues; falls back to a time+counter hex string in the
  (test/headless) case where crypto is unavailable, since uniqueness — not
  unpredictability — is all that matters here."
  []
  (let [hex (if (and (exists? js/crypto) (.-getRandomValues js/crypto))
              (let [buf (js/Uint8Array. 12)]
                (.getRandomValues js/crypto buf)
                (->> (array-seq buf)
                     (map #(.padStart (.toString % 16) 2 "0"))
                     (apply str)))
              (.toString (js/Math.floor (* (js/Date.now) 1000000)) 16))]
    (cloid/make-cloid hex)))

(defn- fail-ready-rows
  [rows message]
  (mapv #(if (= :ready (:status %))
           (assoc % :status :failed :error {:message message})
           %)
        rows))

(defn- cancel-failed-message
  [n detail]
  (str "Couldn't cancel " n " resting order" (when (not= 1 n) "s")
       " from the previous run — no new orders were sent"
       (when (seq detail) (str " (" detail ")"))
       ". Cancel them from the trade ticket, then resume."))

(defn- resolve-cancel-wires
  "Turns each :cancel-orders entry into a wire cancel {:a <asset-idx> :o <oid>}, split
  into {:cancels [...] :unresolved [...]}. Two entry shapes converge here: same-session
  carryover entries carry a frozen :asset-id (used directly, robust even if the market
  metadata later drops out of state); live-book-recognized entries carry only :coin +
  :oid, so their asset index is resolved from state via the shared cancel-request builder
  (the same resolver manual order cancellation uses). An entry that resolves to neither
  halts the run — submitting new orders while a stale one may still fill is the bug."
  [state entries]
  (reduce (fn [acc entry]
            (let [frozen (first (:cancels (carryover/carryover-cancels [entry])))
                  resolved (or frozen
                               (get-in (cancel-request/build-cancel-order-request state entry)
                                       [:action :cancels 0]))]
              (if resolved
                (update acc :cancels conj resolved)
                (update acc :unresolved conj entry))))
          {:cancels [] :unresolved []}
          (or entries [])))

(defn- cancel-stale-orders!
  "Cancels the plan's :cancel-orders — resting orders left on the book by PREVIOUS
  optimizer runs — before any new order is released, so a stale order can't fill on top
  of the new run and over-allocate the account. Resolves to
  {:ok? <bool> :message <halt reason> :cancellations <audit entry for the ledger>}.

  The exchange accepting the batch (top-level \"ok\") counts as success even when
  individual cancels error (\"already canceled or filled\" means the order is off the
  book either way). A transport-level failure, a thrown error, or an entry we cannot
  build a wire cancel for (missing asset index / oid) halts the run: submitting anyway
  would recreate exactly the bug this cancellation prevents."
  [submit-order! store target plan]
  (let [entries (:cancel-orders plan)]
    (if-not (seq entries)
      (js/Promise.resolve {:ok? true :cancellations nil})
      (let [{:keys [cancels unresolved]} (resolve-cancel-wires @store entries)
            oids (mapv :oid entries)
            base {:requested (count entries) :oids oids}]
        (if (seq unresolved)
          (js/Promise.resolve
           {:ok? false
            :cancellations (assoc base :status :failed)
            :message (cancel-failed-message (count entries)
                                            "couldn't resolve their market ids")})
          (-> (submit-action! submit-order! store target
                              {:type "cancel" :cancels cancels})
              (.then (fn [resp]
                       (if (= "ok" (:status resp))
                         {:ok? true
                          :cancellations (assoc base :status :ok)}
                         {:ok? false
                          :cancellations (assoc base :status :failed)
                          :message (cancel-failed-message
                                    (count entries)
                                    (execution-workflow/order-error-message resp))})))
              (.catch (fn [err]
                        {:ok? false
                         :cancellations (assoc base :status :failed)
                         :message (cancel-failed-message
                                   (count entries)
                                   (execution-workflow/error-message err))}))))))))

(def ^:private post-only-reprice-attempts
  2)

(defn- submit-with-reprice!
  "Submits one order action. If Hyperliquid rejects a post-only order because it would have
  immediately matched, reprices it to rest at the live touch carried in the rejection bbo and
  resubmits (up to `attempts-left` times). Any other outcome (ok, or a non-cross error) resolves
  as-is. This makes a passive order self-correct to the current book instead of hard-failing when
  the book moved since the plan was built."
  [submit-order! store target action attempts-left]
  (-> (submit-action! submit-order! store target action)
      (.then (fn [resp]
               (if (and (pos? attempts-left)
                        (not (execution/response-ok? resp)))
                 (if-let [bbo (execution/post-only-cross-bbo resp)]
                   (if-let [action* (execution/reprice-post-only-action action bbo)]
                     (submit-with-reprice! submit-order! store target action*
                                           (dec attempts-left))
                     (js/Promise.resolve resp))
                   (js/Promise.resolve resp))
                 (js/Promise.resolve resp))))))

(defn- submit-execution-row!
  [submit-order! store target row]
  (if-not (= :ready (:status row))
    (js/Promise.resolve row)
    (do
      (swap! store execution-workflow/set-run-attempt-row-status
             (:row-id row) {:status :working})
      (let [request (:request row)
            pre-actions (->> (:pre-actions request)
                             (filter map?)
                             vec)
            action (:action request)
            result-promise
            (if-not (map? action)
              (js/Promise.resolve
               (assoc row
                      :status :failed
                      :error {:message "Execution row is missing an order action."}))
              (let [submit-promise
                    (.then (submit-actions! submit-order! store target pre-actions)
                           (fn [pre-responses]
                             (if-let [failed-pre-action (failed-pre-action-response pre-responses)]
                               (assoc row
                                      :status :failed
                                      :pre-action-responses pre-responses
                                      :error {:message (execution-workflow/order-error-message
                                                        failed-pre-action)})
                               (.then (submit-with-reprice! submit-order! store target action
                                                            post-only-reprice-attempts)
                                      (fn [resp]
                                        (if (execution/response-ok? resp)
                                          (let [status (execution/settled-row-status resp)
                                                realized (execution/realized-fill row resp)]
                                            (cond-> (assoc row
                                                           :status status
                                                           :response resp)
                                              (some? realized) (assoc :realized realized)))
                                          (assoc row
                                                 :status :failed
                                                 :response resp
                                                 :error {:message (execution-workflow/order-error-message
                                                                   resp)})))))))]
                (.catch submit-promise
                        (fn [err]
                          (mark-row-failed row err)))))]
        (.then result-promise
               (fn [result-row]
                 (swap! store execution-workflow/set-run-attempt-row-status
                        (:row-id result-row)
                        (select-keys result-row [:status :error]))
                 result-row))))))

(defn- submit-execution-rows!
  [submit-order! store target rows]
  (reduce
   (fn [promise row]
     (.then promise
            (fn [submitted-rows]
              (if (and (= :ready (:status row))
                       (get-in @store contracts/execution-abort-requested-path))
                (let [aborted (assoc row :status :skipped :reason :aborted)]
                  (swap! store execution-workflow/set-run-attempt-row-status
                         (:row-id aborted) {:status :skipped :reason :aborted})
                  (js/Promise.resolve (conj submitted-rows aborted)))
                (-> (submit-execution-row! submit-order! store target row)
                    (.then (fn [submitted-row]
                             (conj submitted-rows submitted-row))))))))
   (js/Promise.resolve [])
   rows))

(defn- refresh-after-execution!
  [{:keys [dispatch! refresh-open-orders!]} store address ledger]
  (when (and address
             (or (some #(contains? #{:submitted :resting} (:status %)) (:rows ledger))
                 (= :ok (get-in ledger [:cancellations :status]))))
    (dispatch! store nil [[:actions/load-user-data address]
                          [:actions/refresh-order-history]])
    (when (and (fn? refresh-open-orders!)
               (some #(= :resting (:status %)) (:rows ledger)))
      (refresh-open-orders! store))))

(defn- apply-persistence-result!
  [store result]
  (reset! store (:state result))
  result)

(declare interpret-ledger-persistence-result!)

(defn- fail-ledger-persistence!
  [store ledger err]
  (apply-persistence-result!
   store
   (execution-workflow/fail-ledger-persistence
    {:state @store
     :error err}))
  (js/Promise.resolve ledger))

(defn- advance-command-result
  [result]
  (update result :commands #(vec (rest %))))

(defn- merge-result-context
  [operation result]
  (merge operation
         (select-keys result [:scenario-record :scenario-index])))

(defn- interpret-ledger-persistence-command!
  [env store ledger operation result command]
  (let [{:keys [load-scenario!
                load-scenario-index!
                save-scenario!
                save-scenario-index!]} env]
    (case (:command/type command)
      :optimizer.workflow/load-scenario
      (-> (load-scenario! (:scenario-id command))
          (.then (fn [scenario-record]
                   (let [result* (execution-workflow/continue-ledger-persistence-after-record
                                  {:state @store
                                   :address (:address operation)
                                   :ledger ledger
                                   :scenario-record scenario-record})]
                     (interpret-ledger-persistence-result!
                      env
                      store
                      ledger
                      (merge-result-context operation result*)
                      result*))))
          (.catch (fn [err]
                    (fail-ledger-persistence! store ledger err))))

      :optimizer.workflow/load-scenario-index
      (-> (load-scenario-index! (:address command))
          (.then (fn [loaded-index]
                   (let [result* (execution-workflow/continue-ledger-persistence-after-index
                                  {:state @store
                                   :address (:address operation)
                                   :ledger ledger
                                   :scenario-record (:scenario-record operation)
                                   :loaded-index loaded-index})]
                     (interpret-ledger-persistence-result!
                      env
                      store
                      ledger
                      (merge-result-context operation result*)
                      result*))))
          (.catch (fn [err]
                    (fail-ledger-persistence! store ledger err))))

      :optimizer.workflow/save-scenario
      (-> (save-scenario! (:scenario-id command)
                          (:scenario-record command))
          (.then (fn [_]
                   (interpret-ledger-persistence-result!
                    env
                    store
                    ledger
                    operation
                    (advance-command-result result))))
          (.catch (fn [err]
                    (fail-ledger-persistence! store ledger err))))

      :optimizer.workflow/save-scenario-index
      (let [operation* (assoc operation
                              :scenario-index (:scenario-index command))]
        (-> (save-scenario-index! (:address command)
                                  (:scenario-index command))
            (.then (fn [_]
                     (interpret-ledger-persistence-result!
                      env
                      store
                      ledger
                      operation*
                      (execution-workflow/complete-ledger-persistence
                       {:state @store
                        :scenario-index (:scenario-index operation*)
                        :scenario-record (:scenario-record operation*)}))))
            (.catch (fn [err]
                      (fail-ledger-persistence! store ledger err)))))

      (js/Promise.resolve ledger))))

(defn- interpret-ledger-persistence-result!
  [env store ledger operation result]
  (let [result* (apply-persistence-result! store result)]
    (if-let [command (first (:commands result*))]
      (interpret-ledger-persistence-command! env
                                             store
                                             ledger
                                             operation
                                             result*
                                             command)
      (js/Promise.resolve ledger))))

(defn- persist-execution-ledger!
  [env store address ledger]
  (interpret-ledger-persistence-result!
   env
   store
   ledger
   {:address address}
   (execution-workflow/begin-ledger-persistence
    {:state @store
     :address address
     :ledger ledger})))

(defn- execution-mutation-target
  [state]
  (let [owner-address (or (account-context/owner-address state)
                          (get-in state [:wallet :address]))
        account-address (or (account-context/active-trading-account-address state)
                            owner-address)
        vault-address (account-context/exchange-vault-address state)]
    {:owner-address owner-address
     :account-address account-address
     :options (cond-> {}
                vault-address (assoc :vault-address vault-address))}))

(defn execute-portfolio-optimizer-plan-effect
  [env _ store plan]
  (let [now-ms-fn (:now-ms env)
        submit-order! (:submit-order! env)
        dispatch! (:dispatch! env)
        persistence-env (select-keys env
                                     [:load-scenario!
                                      :load-scenario-index!
                                      :save-scenario!
                                     :save-scenario-index!])
        state @store
        {:keys [owner-address account-address] :as target} (execution-mutation-target state)
        started-at-ms (now-ms-fn)
        attempt (execution/build-execution-attempt
                 {:plan plan
                  :market-by-key (get-in state [:asset-selector :market-by-key])
                  :orderbooks (:orderbooks state)
                  :cloid-fn (when-not (= :revert (:kind plan))
                              random-optimizer-cloid)})]
    (if-not owner-address
      (let [completed-at-ms (now-ms-fn)
            rows (mapv #(if (= :ready (:status %))
                          (assoc % :status :failed
                                 :error {:message "Connect your wallet before executing."})
                          %)
                       (:rows attempt))
            ledger (execution-workflow/execution-ledger attempt
                                                        started-at-ms
                                                        completed-at-ms
                                                        rows)]
        (swap! store execution-workflow/apply-execution-ledger ledger)
        (js/Promise.resolve ledger))
      (do
        (swap! store assoc-in
               contracts/execution-path
               (execution-workflow/begin-execution-state attempt started-at-ms))
        (-> (cancel-stale-orders! submit-order! store target plan)
            (.then (fn [{:keys [ok? message cancellations]}]
                     (-> (if ok?
                           (submit-execution-rows! submit-order! store target
                                                   (:rows attempt))
                           (js/Promise.resolve (fail-ready-rows (:rows attempt) message)))
                         (.then (fn [rows] {:rows rows :cancellations cancellations})))))
            (.then (fn [{:keys [rows cancellations]}]
                     (let [completed-at-ms (now-ms-fn)
                           ledger (cond-> (execution-workflow/execution-ledger
                                           attempt
                                           started-at-ms
                                           completed-at-ms
                                           rows)
                                    (some? cancellations)
                                    (assoc :cancellations cancellations))]
                       (swap! store execution-workflow/apply-execution-ledger ledger)
                       (when-let [[kind message] (execution-outcome-toast ledger)]
                         (feedback-runtime/set-order-feedback-toast! store kind message))
                       (refresh-after-execution!
                        {:dispatch! dispatch!
                         :refresh-open-orders! (:refresh-open-orders! env)}
                        store account-address ledger)
                       (persist-execution-ledger! persistence-env
                                                  store
                                                  account-address
                                                  ledger)))))))))
