(ns hyperopen.vol-range.infrastructure.executor-cancel
  "Cancel this ladder (HO-SPEC-014 v2.1 sections 7.4, 10.1; VR-11, VR-15, VR-16).

  The complete nonterminal membership of the logical grid is frozen (every
  revision and repair), and each identified order is canceled exactly once:
  by oid when known, by CLOID otherwise. Never account-wide, never by prefix.

  Cancellation does not take the admission section, does not need the
  execution flag, and does not need a successful journal commit: journaling is
  best effort here (spec 7.4). It still goes through the shared signer, nonce
  coordinator and network guard of the delivery path. A cancel acknowledgment
  or a missing-order reply is not terminality: the grid is reconciled
  afterwards and stays Checking until each order's final state and fills are
  established."
  (:require [hyperopen.api.trading.order-result :as order-result]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.revalidation :as revalidation]
            [hyperopen.vol-range.application.wire :as wire]
            [hyperopen.vol-range.infrastructure.executor :as ex]))

(defn- refusal
  "Cancel needs mutation permission, a ready signer and the SAME account and
  market as the grid (VR-18); never the execution flag or admission."
  [live grid]
  (cond
    (:read-only? live) :capability/read-only
    (not (:agent-ready? live)) :signer/agent-not-ready
    (not= (select-keys (:scope grid) revalidation/identity-keys)
          (select-keys (get-in live [:input :scope]) revalidation/identity-keys))
    :guard/scope-changed
    :else nil))

(defn- send-one!
  "Send one cancel action for `targets` as its own operation."
  [deps grid {:keys [action targets]}]
  (let [operation-id ((:random-id! deps) "op")
        attempt-id ((:random-id! deps) "attempt")
        at (ex/now deps)
        expires (+ at ex/action-deadline-ms)
        cloids (mapv :cloid targets)
        grid (lifecycle/add-cancel-operation grid {:operation-id operation-id :cloids cloids
                                                   :expires-after-ms expires :now-ms at})
        grid-ref (atom grid)
        attempt-ref (atom nil)
        digest (wire/action-digest action)]
    (-> (ex/persist-grid! deps grid [])
        (.then
         (fn [_]
           ((:send! deps)
            action
            {:expires-after expires
             :vault-address (get-in grid [:scope :vault-address])
             ;; Best effort: a failed journal commit never blocks a cancel.
             :on-send-attempt!
             (fn [{:keys [nonce expires-after]}]
               (let [at (ex/now deps)
                     attempt (jr/cancel-attempt-record {:operation-id operation-id :attempt-id attempt-id
                                                        :grid-id (:grid-id grid) :scope-key (:scope-key grid)
                                                        :nonce nonce :expires-after-ms expires-after
                                                        :action-digest digest :targets targets :now-ms at})
                     g (lifecycle/mark-sending @grid-ref operation-id attempt-id at)]
                 (reset! attempt-ref attempt)
                 (reset! grid-ref g)
                 (-> (ex/persist-grid! deps g [attempt])
                     (.then (fn [_] true)))))
             :before-send! (fn [_] (if-let [r (refusal ((:live! deps)) grid)] (js/Promise.resolve [r]) true))})))
        (.then
         (fn [{:keys [delivery body error phase]}]
           (let [at (ex/now deps)
                 normalized (when (= :response delivery) (order-result/normalize-cancel-response targets body))
                 grid (evidence/apply-cancel-delivery @grid-ref operation-id
                                                      {:delivery delivery :results (:results normalized)}
                                                      at)
                 attempt (some-> @attempt-ref
                                 (jr/with-cancel-outcome {:delivery delivery :results (:results normalized)} at))]
             (-> (ex/persist-grid! deps grid (if attempt [attempt] []))
                 (.then (fn [{:keys [grid]}]
                          {:grid grid :delivery delivery :phase phase :error error
                           :category (:category normalized)})))))))))

(defn- in-flight-placement? [grid]
  (some #(contains? #{:place :repair} (:kind %)) (lifecycle/in-flight-operations grid)))

(defn- send-all!
  "Send each cancel group in turn, threading the grid."
  [deps grid groups]
  (reduce (fn [p group]
            (.then p (fn [{:keys [grid sends]}]
                       (.then (send-one! deps grid group)
                              (fn [r] {:grid (:grid r) :sends (conj sends (dissoc r :grid))})))))
          (js/Promise.resolve {:grid grid :sends []})
          groups))

(defn cancel!
  "Cancel every identified nonterminal order of `grid`, then reconcile.
  The cancel request is recorded first (best effort), so a placement or
  repair still before its final guard is refused. Live and unknown orders
  are always canceled, even while a placement or repair is in flight; its
  still-pending legs cannot be targeted yet, which the result reports with
  `:in-flight? true` (cancel again once it settles). Resolves to
  {:status :refused|:busy|:nothing-to-cancel|<grid status> :grid :counts
  :sends [...] :in-flight? bool}."
  [deps {:keys [grid]}]
  (if-let [r (refusal ((:live! deps)) grid)]
    (js/Promise.resolve {:status :refused :errors [r] :grid grid})
    (-> (ex/refresh-grid! deps grid)
        (.then (fn [grid] (ex/persist-grid! deps (lifecycle/request-cancel grid (ex/now deps)) [])))
        (.then
         (fn [{:keys [grid]}]
           (let [targets (lifecycle/cancel-targets grid)
                 in-flight? (boolean (in-flight-placement? grid))]
             (cond
               (and in-flight? (empty? targets))
               {:status :busy :errors [:grid/operation-in-flight] :grid grid :counts (lifecycle/counts grid)
                :sends [] :in-flight? true}

               (empty? targets)
               (.then (ex/reconcile! deps grid)
                      (fn [{:keys [grid status counts]}]
                        {:status (if (= :closed status) :closed :nothing-to-cancel)
                         :grid grid :counts counts :sends [] :in-flight? false}))

               :else
               (-> (send-all! deps grid (wire/cancel-actions (get-in grid [:scope :asset-id]) targets))
                   (.then (fn [{:keys [grid sends]}]
                            (.then (ex/reconcile! deps grid)
                                   (fn [{:keys [grid status counts]}]
                                     {:status status :grid grid :counts counts :sends sends
                                      :in-flight? in-flight?})))))))))
        (.catch (fn [e] {:status :error :error (ex/error-text e) :grid grid})))))
