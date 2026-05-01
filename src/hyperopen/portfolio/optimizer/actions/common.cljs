(ns hyperopen.portfolio.optimizer.actions.common
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]))

(def supported-universe-market-types
  #{:perp :spot :vault})

(defn normalize-keyword-like
  [value]
  (let [text (cond
               (keyword? value) (name value)
               (string? value) (str/trim value)
               :else nil)]
    (when (seq text)
      (-> text
          (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
          (str/replace #"[_\s]+" "-")
          str/lower-case
          keyword))))

(defn save-draft-path-values
  [path-values]
  [[:effects/save-many
    (conj (vec path-values)
          [[:portfolio :optimizer :draft :metadata :dirty?] true])]])

(defn non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn exposure->universe-instrument
  [exposure]
  (let [instrument-id (non-blank-text (:instrument-id exposure))
        coin (non-blank-text (:coin exposure))
        market-type (:market-type exposure)]
    (when (and instrument-id
               coin
               (keyword? market-type))
      (cond-> {:instrument-id instrument-id
               :market-type market-type
               :coin coin
               :shortable? (= :perp market-type)}
        (non-blank-text (:dex exposure))
        (assoc :dex (non-blank-text (:dex exposure)))
        (non-blank-text (:symbol exposure)) (assoc :symbol (non-blank-text (:symbol exposure)))
        (non-blank-text (:base exposure)) (assoc :base (non-blank-text (:base exposure)))
        (non-blank-text (:quote exposure)) (assoc :quote (non-blank-text (:quote exposure)))
        (contains? exposure :hip3?) (assoc :hip3? (boolean (:hip3? exposure)))))))

(defn market->universe-instrument
  [market]
  (let [instrument-id (non-blank-text (:key market))
        coin (non-blank-text (:coin market))
        market-type (normalize-keyword-like (:market-type market))
        vault-address (universe-candidates/normalize-vault-address (:vault-address market))]
    (when (and instrument-id
               coin
               (contains? supported-universe-market-types market-type)
               (or (not= :vault market-type)
                   vault-address))
      (cond-> {:instrument-id instrument-id
               :market-type market-type
               :coin coin
               :shortable? (= :perp market-type)}
        (= :vault market-type)
        (assoc :vault-address vault-address)
        (non-blank-text (:dex market))
        (assoc :dex (non-blank-text (:dex market)))
        (non-blank-text (:symbol market)) (assoc :symbol (non-blank-text (:symbol market)))
        (non-blank-text (:name market)) (assoc :name (non-blank-text (:name market)))
        (non-blank-text (:base market)) (assoc :base (non-blank-text (:base market)))
        (non-blank-text (:quote market)) (assoc :quote (non-blank-text (:quote market)))
        (contains? market :tvl) (assoc :tvl (:tvl market))
        (contains? market :hip3?) (assoc :hip3? (boolean (:hip3? market)))))))

(defn dedupe-instruments
  [instruments]
  (:items
   (reduce (fn [{:keys [seen] :as acc} instrument]
             (let [instrument-id (:instrument-id instrument)]
               (if (contains? seen instrument-id)
                 acc
                 (-> acc
                     (update :seen conj instrument-id)
                     (update :items conj instrument)))))
           {:seen #{}
            :items []}
           instruments)))

(defn parse-number-value
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [text (str/trim value)]
      (when (seq text)
        (let [parsed (js/Number text)]
          (when (and (number? parsed)
                     (js/isFinite parsed))
            parsed))))

    :else nil))

(defn parse-boolean-value
  [value]
  (cond
    (boolean? value) value
    (string? value) (case (str/lower-case (str/trim value))
                      "true" true
                      "false" false
                      nil)
    :else nil))

(defn constraint-list
  [state constraint-key]
  (vec (or (get-in state [:portfolio :optimizer :draft :constraints constraint-key])
           [])))

(defn draft-universe
  [state]
  (vec (or (get-in state [:portfolio :optimizer :draft :universe])
           [])))

(defn instrument-present?
  [universe instrument-id]
  (boolean
   (some #(= instrument-id (:instrument-id %)) universe)))

(defn instrument-market-type
  [state instrument-id]
  (some (fn [instrument]
          (when (= instrument-id (:instrument-id instrument))
            (:market-type instrument)))
        (get-in state [:portfolio :optimizer :draft :universe])))

(defn set-membership
  [items item enabled?]
  (let [items* (vec (remove #(= item %) items))]
    (if enabled?
      (conj items* item)
      items*)))

(defn build-request-signature
  [request]
  {:scenario-id (:scenario-id request)
   :as-of-ms (:as-of-ms request)
   :request request})

(defn current-scenario-id
  [state]
  (or (non-blank-text (get-in state [:portfolio :optimizer :active-scenario :loaded-id]))
      (non-blank-text (get-in state [:portfolio :optimizer :draft :id]))))

(defn vault-list-metadata-fetch-effects
  [state]
  (if (seq (get-in state [:vaults :merged-index-rows]))
    []
    [[:effects/api-fetch-vault-index]
     [:effects/api-fetch-vault-summaries]]))

(defn scenario-id-effect
  [effect-id scenario-id]
  (if-let [scenario-id* (non-blank-text scenario-id)]
    [[effect-id scenario-id*]]
    []))
