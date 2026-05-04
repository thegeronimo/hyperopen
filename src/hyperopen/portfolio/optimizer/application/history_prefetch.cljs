(ns hyperopen.portfolio.optimizer.application.history-prefetch
  (:require [hyperopen.portfolio.optimizer.application.history-loader.instruments :as instruments]))

(def default-state
  {:queue []
   :active-instrument-id nil
   :by-instrument-id {}})

(def selection-prefetch-effect
  [:effects/load-portfolio-optimizer-history
   {:source :selection-prefetch
    :queue? true
    :merge? true}])

(defn prefetch-state
  [state]
  (merge default-state
         (get-in state [:portfolio :optimizer :history-prefetch])))

(defn instrument-id
  [instrument]
  (instruments/normalize-instrument-id instrument))

(defn- history-data
  [state]
  (get-in state [:portfolio :optimizer :history-data]))

(defn- rows-present?
  [value]
  (boolean (seq value)))

(defn required-history-loaded?
  [state instrument]
  (let [history-data* (history-data state)]
    (cond
      (instruments/vault-instrument? instrument)
      (boolean
       (when-let [address (instruments/vault-address instrument)]
         (get-in history-data* [:vault-details-by-address address])))

      (instruments/perp-instrument? instrument)
      (let [coin (instruments/normalize-coin instrument)]
        (and (rows-present?
              (get-in history-data* [:candle-history-by-coin coin]))
             (rows-present?
              (get-in history-data* [:funding-history-by-coin coin]))))

      :else
      (rows-present?
       (get-in history-data* [:candle-history-by-coin
                              (instruments/normalize-coin instrument)])))))

(defn active?
  [prefetch-state* instrument-id*]
  (= instrument-id* (:active-instrument-id prefetch-state*)))

(defn queued?
  [prefetch-state* instrument-id*]
  (boolean
   (some #(= instrument-id* (instrument-id %))
         (:queue prefetch-state*))))

(defn prefetch-active?
  [state]
  (boolean (:active-instrument-id (prefetch-state state))))

(defn prefetch-instrument?
  [state prefetch-state* instrument]
  (let [instrument-id* (instrument-id instrument)]
    (and instrument-id*
         (not (required-history-loaded? state instrument))
         (not (active? prefetch-state* instrument-id*))
         (not (queued? prefetch-state* instrument-id*)))))

(defn queued-status
  []
  {:status :queued
   :started-at-ms nil
   :completed-at-ms nil
   :error nil
   :warnings []})

(defn loading-status
  [started-at-ms]
  {:status :loading
   :started-at-ms started-at-ms
   :completed-at-ms nil
   :error nil
   :warnings []})

(defn succeeded-status
  [started-at-ms completed-at-ms warnings]
  {:status :succeeded
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error nil
   :warnings (vec warnings)})

(defn failed-status
  [started-at-ms completed-at-ms error]
  {:status :failed
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error error
   :warnings []})

(defn enqueue-missing-instruments
  [state instruments*]
  (let [initial-prefetch-state (prefetch-state state)
        result (reduce (fn [{:keys [prefetch-state queued] :as acc} instrument]
                         (if (prefetch-instrument? state prefetch-state instrument)
                           (let [instrument-id* (instrument-id instrument)
                                 prefetch-state* (-> prefetch-state
                                                     (update :queue conj instrument)
                                                     (assoc-in [:by-instrument-id
                                                                instrument-id*]
                                                               (queued-status)))]
                             (assoc acc
                                    :prefetch-state prefetch-state*
                                    :queued (conj queued instrument)))
                           acc))
                       {:prefetch-state initial-prefetch-state
                        :queued []}
                       (vec instruments*))
        queued (vec (:queued result))]
    {:state (:prefetch-state result)
     :queued queued
     :changed? (not= initial-prefetch-state (:prefetch-state result))
     :start? (and (seq queued)
                  (nil? (:active-instrument-id initial-prefetch-state)))}))

(defn cleanup-to-instrument-ids
  [prefetch-state* instrument-ids]
  (let [ids (set instrument-ids)
        active-id (:active-instrument-id prefetch-state*)]
    (-> prefetch-state*
        (update :queue
                #(vec (filter (fn [instrument]
                                (contains? ids (instrument-id instrument)))
                              (or % []))))
        (update :by-instrument-id
                #(into {}
                       (filter (fn [[instrument-id* _status]]
                                 (contains? ids instrument-id*)))
                       (or % {})))
        (cond-> (and active-id (not (contains? ids active-id)))
          (assoc :active-instrument-id nil)))))

(defn remove-instrument
  [state instrument-id*]
  (cleanup-to-instrument-ids
   (prefetch-state state)
   (remove #{instrument-id*}
           (concat (map instrument-id
                        (get-in state [:portfolio :optimizer :draft :universe]))
                   (keys (:by-instrument-id (prefetch-state state)))))))

(defn first-queued-instrument
  [state]
  (first (:queue (prefetch-state state))))

(defn instrument-selected?
  [state instrument-id*]
  (boolean
   (some #(= instrument-id* (:instrument-id %))
         (get-in state [:portfolio :optimizer :draft :universe]))))
