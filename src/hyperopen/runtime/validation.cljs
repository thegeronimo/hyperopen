(ns hyperopen.runtime.validation
  (:require [goog.object :as gobj]))

(defonce ^:private contracts-impl
  (atom nil))

(defonce ^:private effect-order-contract-impl
  (atom nil))

(defn install-contracts-impl!
  [impl]
  (reset! contracts-impl impl)
  impl)

(defn install-effect-order-contract-impl!
  [impl]
  (reset! effect-order-contract-impl impl)
  impl)

(defn validation-enabled?
  []
  (boolean (and ^boolean goog.DEBUG
                (when-let [enabled? (:validation-enabled? @contracts-impl)]
                  (enabled?)))))

(defn- contracts-fn
  [impl-key]
  (or (get @contracts-impl impl-key)
      (fn [& _] nil)))

(defn- effect-order-contract-fn
  [impl-key]
  (get @effect-order-contract-impl impl-key))

(defn assert-action-args!
  [action-id args context]
  ((contracts-fn :assert-action-args!) action-id args context))

(defn assert-effect-args!
  [effect-id args context]
  ((contracts-fn :assert-effect-args!) effect-id args context))

(defn assert-emitted-effects!
  [effects context]
  ((contracts-fn :assert-emitted-effects!) effects context))

(defn assert-app-state!
  [state context]
  ((contracts-fn :assert-app-state!) state context))

(defn assert-provider-message!
  [provider-message context]
  ((contracts-fn :assert-provider-message!) provider-message context))

(defn assert-signed-exchange-payload!
  [payload context]
  ((contracts-fn :assert-signed-exchange-payload!) payload context))

(defn assert-exchange-response!
  [response context]
  ((contracts-fn :assert-exchange-response!) response context))

(def ^:private store-state-watch-key
  ::state-schema-validation)

(def ^:private max-debug-action-effect-traces
  100)

(defonce ^:private debug-action-effect-traces
  (atom []))

(defn clear-debug-action-effect-traces!
  []
  (reset! debug-action-effect-traces [])
  true)

(defn debug-action-effect-traces-snapshot
  []
  @debug-action-effect-traces)

(defn- record-debug-action-effect-trace!
  [action-id args effects]
  (when ^boolean goog.DEBUG
    (when-let [effect-order-summary (effect-order-contract-fn :effect-order-summary)]
      (let [summary (assoc (effect-order-summary action-id effects)
                           :captured-at-ms (.now js/Date)
                           :arg-count (count args)
                           :args (vec args))]
        (swap! debug-action-effect-traces
               (fn [entries]
                 (let [next-entries (conj (vec entries) summary)
                       overflow (- (count next-entries) max-debug-action-effect-traces)]
                   (if (pos? overflow)
                     (subvec next-entries overflow)
                     next-entries))))))))

(defn- parse-fixed-arity-key
  [k]
  (when-let [[_ arity-text] (re-matches #"cljs\$lang\$arity\$(\d+)" (str k))]
    (let [arity (js/parseInt arity-text 10)]
      (when-not (js/isNaN arity)
        arity))))

(defn- fixed-arities
  [handler]
  (->> (array-seq (js/Object.keys handler))
       (keep parse-fixed-arity-key)
       set))

(defn- variadic-handler?
  [handler]
  (or (fn? (gobj/get handler "cljs$lang$arity$variadic"))
      (fn? (gobj/get handler "cljs$lang$applyTo"))))

(defn- dispatch-arity-contract
  [handler fixed-leading-args]
  (let [dispatch-arities (->> (fixed-arities handler)
                              (map #(- % fixed-leading-args))
                              (filter #(>= % 0))
                              sort
                              vec)]
    (when (seq dispatch-arities)
      {:fixed (set dispatch-arities)
       :minimum (first dispatch-arities)
       :maximum (last dispatch-arities)
       :variadic? (variadic-handler? handler)})))

(defn- valid-dispatch-arity?
  [{:keys [fixed minimum variadic?]} arg-count]
  (if variadic?
    (>= arg-count minimum)
    (contains? fixed arg-count)))

(defn- expected-arity-text
  [{:keys [fixed minimum variadic?]}]
  (if variadic?
    (str ">=" minimum)
    (pr-str (sort fixed))))

(defn- assert-dispatch-arity!
  [label id arity-contract args context]
  (let [arg-count (count args)]
    (when (and arity-contract
               (not (valid-dispatch-arity? arity-contract arg-count)))
      (throw (js/Error.
              (str label " schema validation failed for " id ". "
                   "expected-arity=" (expected-arity-text arity-contract)
                   " actual-arity=" arg-count
                   " context=" (pr-str context)
                   " args=" (pr-str args)))))))

(defn wrap-action-handler
  [action-id handler]
  (let [validation? (validation-enabled?)
        arity-contract (when validation?
                         (dispatch-arity-contract handler 1))]
    (fn [state & args]
      (when validation?
        (assert-dispatch-arity! "action payload"
                                action-id
                                arity-contract
                                args
                                {:phase :dispatch})
        (assert-action-args! action-id (vec args) {:phase :dispatch}))
      (let [effects (apply handler state args)]
        (when validation?
          (assert-emitted-effects!
           effects
           {:phase :action-emission
            :action-id action-id})
          (when-let [assert-action-effect-order!
                     (effect-order-contract-fn :assert-action-effect-order!)]
            (assert-action-effect-order!
             action-id
             effects
             {:phase :action-emission
              :action-id action-id})))
        (record-debug-action-effect-trace! action-id args effects)
        effects))))

(defn wrap-effect-handler
  [effect-id handler]
  (if-not (validation-enabled?)
    handler
    (let [arity-contract (dispatch-arity-contract handler 2)]
      (fn [ctx store & args]
        (assert-dispatch-arity! "effect request"
                                effect-id
                                arity-contract
                                args
                                {:phase :dispatch})
        (assert-effect-args! effect-id (vec args) {:phase :dispatch})
        (apply handler ctx store args)))))

(defn install-store-state-validation!
  [store]
  (when (validation-enabled?)
    (assert-app-state!
     @store
     {:phase :bootstrap
      :boundary :app-store})
    (remove-watch store store-state-watch-key)
    (add-watch store
               store-state-watch-key
               (fn [_ _ _ new-state]
                 (assert-app-state!
                  new-state
                  {:phase :transition
                   :boundary :app-store}))))
  store)
