(ns hyperopen.api.trading.order-result
  "Strict, pure interpretation of exchange order and cancel responses
  (HO-SPEC-014 v2.1 sections 2.2 item 2, 8.4, 8.6).

  Transport delivery and per-leg outcome are separate. Nothing unfamiliar is
  ever counted as success: an empty or wrong-length status vector, an unknown
  response type, an unfamiliar entry shape, or a CLOID that does not match the
  leg at its index leaves the affected legs `:unknown` for reconciliation by
  their original identities."
  (:require [clojure.string :as str]))

(defn- non-blank [x]
  (let [s (cond (string? x) x (some? x) (str x) :else nil)]
    (when (and s (seq (str/trim s))) (str/trim s))))

(defn- oid-string
  "Lossless order id: venue oids can exceed 2^53, so keep them as strings."
  [x]
  (cond (string? x) (non-blank x)
        (number? x) (when (js/Number.isSafeInteger x) (str x))
        :else nil))

(defn- cloid-matches? [requested reported]
  (or (nil? reported) (nil? requested)
      (= (str/lower-case (str requested)) (str/lower-case (str reported)))))

(defn- unknown-leg [leg reason raw]
  {:cloid (:cloid leg) :oid nil :placement-state :unknown :order-state nil
   :reason reason :raw raw})

(defn- leg-outcome
  "One status entry against its requested leg."
  [leg entry]
  (cond
    (and (map? entry) (map? (:resting entry)))
    (let [{:keys [oid cloid]} (:resting entry)]
      (if (and (oid-string oid) (cloid-matches? (:cloid leg) cloid))
        {:cloid (:cloid leg) :oid (oid-string oid) :placement-state :accepted
         :order-state :resting :reason nil :raw nil}
        (unknown-leg leg :identity-mismatch entry)))

    (and (map? entry) (map? (:filled entry)))
    (let [{:keys [oid cloid totalSz avgPx]} (:filled entry)]
      (if (and (oid-string oid) (cloid-matches? (:cloid leg) cloid))
        {:cloid (:cloid leg) :oid (oid-string oid) :placement-state :accepted
         :order-state :filled :filled-base (non-blank totalSz) :avg-px (non-blank avgPx)
         :reason nil :raw nil}
        (unknown-leg leg :identity-mismatch entry)))

    (and (map? entry) (some? (:error entry)))
    (if-let [reason (non-blank (if (map? (:error entry))
                                 (or (:message (:error entry)) (pr-str (:error entry)))
                                 (:error entry)))]
      {:cloid (:cloid leg) :oid nil :placement-state :rejected :order-state :terminal
       :reason reason :raw nil}
      (unknown-leg leg :unfamiliar-entry entry))

    :else (unknown-leg leg :unfamiliar-entry entry)))

(defn- summarize [legs]
  (let [by (frequencies (map :placement-state legs))
        n (count legs)
        accepted (get by :accepted 0)
        rejected (get by :rejected 0)
        unknown (get by :unknown 0)]
    {:accepted accepted :rejected rejected :unknown unknown
     :category (cond (pos? unknown) :unresolved
                     (= rejected n) :all-rejected
                     (= accepted n) :all-accepted
                     :else :partial)}))

(defn normalize-order-response
  "Per-leg outcomes for an `order` action of `legs` ({:cloid ...} in wire
  order) given a PARSED JSON body. Returns
  {:response-category kw :leg-results [...] :summary {...} :diagnostic s}."
  [legs body]
  (let [legs (vec legs)
        n (count legs)
        all-unknown (fn [reason]
                      (let [results (mapv #(unknown-leg % reason nil) legs)]
                        {:response-category :unrecognized
                         :leg-results results
                         :summary (summarize results)
                         :diagnostic (pr-str body)}))]
    (cond
      (not (map? body)) (all-unknown :unreadable-response)

      ;; A recognized whole-action rejection: the venue refused the action
      ;; itself, so no leg was accepted.
      (and (= "err" (:status body)) (non-blank (:response body)) (not (map? (:response body))))
      (let [reason (non-blank (:response body))
            results (mapv (fn [leg] {:cloid (:cloid leg) :oid nil :placement-state :rejected
                                     :order-state :terminal :reason reason :raw nil})
                          legs)]
        {:response-category :whole-action-rejection
         :leg-results results
         :summary (summarize results)
         :diagnostic nil})

      (not= "ok" (:status body)) (all-unknown :unrecognized-status)
      (not= "order" (get-in body [:response :type])) (all-unknown :unexpected-response-type)

      :else
      (let [statuses (get-in body [:response :data :statuses])]
        (if-not (and (sequential? statuses) (pos? n) (= n (count statuses)))
          (all-unknown :status-count-mismatch)
          (let [results (mapv leg-outcome legs statuses)]
            {:response-category :per-leg
             :leg-results results
             :summary (summarize results)
             :diagnostic nil}))))))

(def ^:private missing-order-patterns
  ["already canceled" "already cancelled" "never placed" "or filled" "order was never placed"])

(defn missing-order-text?
  "A cancel error that means the order is no longer resting. It is NOT proof
  of non-placement or of cancellation: the order may have filled (spec 8.6)."
  [text]
  (let [t (str/lower-case (or text ""))]
    (boolean (some #(str/includes? t %) missing-order-patterns))))

(defn normalize-cancel-response
  "Per-order cancel outcomes for `targets` ({:oid ...} or {:cloid ...} in wire
  order). `:cancel-acknowledged` only for an explicit \"success\" entry;
  missing-order errors are `:not-resting` (reconcile fills); anything
  unfamiliar is `:unknown`."
  [targets body]
  (let [targets (vec targets)
        n (count targets)
        unknown (fn [reason] (mapv #(assoc % :cancel-state :unknown :reason reason) targets))]
    (cond
      (not (map? body)) {:category :unrecognized :results (unknown :unreadable-response)}

      (and (= "err" (:status body)) (non-blank (:response body)) (not (map? (:response body))))
      {:category :whole-action-rejection
       :results (mapv #(assoc % :cancel-state :rejected :reason (non-blank (:response body))) targets)}

      (or (not= "ok" (:status body))
          (not (contains? #{"cancel" "cancelByCloid"} (get-in body [:response :type]))))
      {:category :unrecognized :results (unknown :unexpected-response)}

      :else
      (let [statuses (get-in body [:response :data :statuses])]
        (if-not (and (sequential? statuses) (pos? n) (= n (count statuses)))
          {:category :unrecognized :results (unknown :status-count-mismatch)}
          {:category :per-order
           :results (mapv (fn [target entry]
                            (cond
                              (= "success" entry) (assoc target :cancel-state :cancel-acknowledged :reason nil)
                              (and (map? entry) (non-blank (:error entry)))
                              (let [reason (non-blank (:error entry))]
                                (assoc target
                                       :cancel-state (if (missing-order-text? reason) :not-resting :rejected)
                                       :reason reason))
                              :else (assoc target :cancel-state :unknown :reason :unfamiliar-entry)))
                          targets statuses)})))))
