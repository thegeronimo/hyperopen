(ns hyperopen.funding.application.modal-vm.lifecycle
  (:require [clojure.string :as str]))

(defn- titleize-token
  [value fallback]
  (if-let [token (some-> value name (str/replace #"-" " "))]
    (let [words (->> (str/split token #"\s+")
                     (remove str/blank?)
                     (map str/capitalize))]
      (if (seq words)
        (str/join " " words)
        fallback))
    fallback))

(defn- lifecycle-next-check-label
  [next-at-ms]
  (when (number? next-at-ms)
    "Scheduled"))

(defn- lifecycle-panel-model
  [lifecycle
   {:keys [selected-asset-key
           direction
           terminal?
           outcome
           outcome-label
           recovery-hint
           destination-explorer-url
           include-queue-position?]}]
  (when (and (= direction (:direction lifecycle))
             (= selected-asset-key (:asset-key lifecycle)))
    {:direction direction
     :stage-label (titleize-token (:state lifecycle)
                                  (if (= direction :withdraw)
                                    "Awaiting Hyperliquid Send"
                                    "Awaiting Source Transfer"))
     :status-label (titleize-token (:status lifecycle)
                                   "Pending")
     :outcome (when terminal?
                {:label (or outcome-label "Terminal")
                 :tone outcome})
     :source-confirmations (:source-tx-confirmations lifecycle)
     :destination-confirmations (:destination-tx-confirmations lifecycle)
     :queue-position (when include-queue-position?
                       (:position-in-withdraw-queue lifecycle))
     :destination-tx (when-let [tx-hash (:destination-tx-hash lifecycle)]
                       {:hash tx-hash
                        :explorer-url destination-explorer-url})
     :next-check-label (lifecycle-next-check-label (:state-next-at lifecycle))
     :error (:error lifecycle)
     :recovery-hint (when (and terminal?
                               (= outcome :failure)
                               (seq recovery-hint))
                      recovery-hint)}))

(defn- lifecycle-outcome
  [hyperunit-lifecycle-failure? lifecycle-terminal? hyperunit-lifecycle]
  (when lifecycle-terminal?
    (if (hyperunit-lifecycle-failure? hyperunit-lifecycle)
      :failure
      :success)))

(defn- lifecycle-outcome-label
  [lifecycle-outcome]
  (case lifecycle-outcome
    :failure "Needs Attention"
    :success "Completed"
    nil))

(defn- lifecycle-recovery-hint
  [hyperunit-lifecycle-recovery-hint lifecycle-outcome hyperunit-lifecycle]
  (when (= lifecycle-outcome :failure)
    (hyperunit-lifecycle-recovery-hint hyperunit-lifecycle)))

(defn- lifecycle-destination-explorer-url
  [hyperunit-explorer-tx-url hyperunit-lifecycle withdraw-chain]
  (hyperunit-explorer-tx-url
   (:direction hyperunit-lifecycle)
   (when (= :withdraw (:direction hyperunit-lifecycle))
     withdraw-chain)
   (:destination-tx-hash hyperunit-lifecycle)))

(defn with-lifecycle-context
  [{:keys [hyperunit-lifecycle
           selected-deposit-asset-key
           selected-withdraw-asset-key
           withdraw-chain] :as ctx}
   {:keys [hyperunit-lifecycle-terminal?
           hyperunit-lifecycle-failure?
           hyperunit-lifecycle-recovery-hint
           hyperunit-explorer-tx-url]}]
  (let [lifecycle-terminal? (hyperunit-lifecycle-terminal? hyperunit-lifecycle)
        lifecycle-outcome (lifecycle-outcome hyperunit-lifecycle-failure?
                                             lifecycle-terminal?
                                             hyperunit-lifecycle)
        lifecycle-outcome-label (lifecycle-outcome-label lifecycle-outcome)
        lifecycle-recovery-hint (lifecycle-recovery-hint hyperunit-lifecycle-recovery-hint
                                                         lifecycle-outcome
                                                         hyperunit-lifecycle)
        lifecycle-destination-explorer-url (lifecycle-destination-explorer-url
                                            hyperunit-explorer-tx-url
                                            hyperunit-lifecycle
                                            withdraw-chain)]
    (assoc ctx
           :hyperunit-lifecycle-terminal? lifecycle-terminal?
           :hyperunit-lifecycle-outcome lifecycle-outcome
           :hyperunit-lifecycle-outcome-label lifecycle-outcome-label
           :hyperunit-lifecycle-recovery-hint lifecycle-recovery-hint
           :hyperunit-lifecycle-destination-explorer-url
           lifecycle-destination-explorer-url
           :deposit-lifecycle (lifecycle-panel-model hyperunit-lifecycle
                                                     {:selected-asset-key selected-deposit-asset-key
                                                      :direction :deposit
                                                      :terminal? lifecycle-terminal?
                                                      :outcome lifecycle-outcome
                                                      :outcome-label lifecycle-outcome-label
                                                      :recovery-hint lifecycle-recovery-hint
                                                      :destination-explorer-url
                                                      lifecycle-destination-explorer-url
                                                      :include-queue-position? false})
           :withdraw-lifecycle (lifecycle-panel-model hyperunit-lifecycle
                                                      {:selected-asset-key selected-withdraw-asset-key
                                                       :direction :withdraw
                                                       :terminal? lifecycle-terminal?
                                                       :outcome lifecycle-outcome
                                                       :outcome-label lifecycle-outcome-label
                                                       :recovery-hint lifecycle-recovery-hint
                                                       :destination-explorer-url
                                                       lifecycle-destination-explorer-url
                                                       :include-queue-position? true}))))
