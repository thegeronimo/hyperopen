(ns hyperopen.api-wallets.domain.policy
  (:require [clojure.string :as str]
            [hyperopen.wallet.agent-session :as agent-session]))

(def default-sort-column
  :name)

(def default-sort-direction
  :asc)

(def ^:private valid-sort-columns
  #{:name :address :valid-until})

(def ^:private valid-sort-directions
  #{:asc :desc})

(def ^:private valid-form-fields
  #{:name :address :days-valid})

(defn default-sort-state
  []
  {:column default-sort-column
   :direction default-sort-direction})

(defn default-form
  []
  {:name ""
   :address ""
   :days-valid ""})

(defn default-modal-state
  []
  {:open? false
   :type nil
   :row nil
   :error nil
   :submitting? false})

(defn default-generated-state
  []
  {:address nil
   :private-key nil})

(defn normalize-sort-column
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :wallet :address
                     :wallet-address :address
                     :api-wallet-address :address
                     :validuntil :valid-until
                     :valid-until-ms :valid-until
                     token)]
    (if (contains? valid-sort-columns normalized)
      normalized
      default-sort-column)))

(defn normalize-sort-direction
  [value]
  (let [direction (cond
                    (keyword? value) value
                    (string? value) (-> value str/trim str/lower-case keyword)
                    :else nil)]
    (if (contains? valid-sort-directions direction)
      direction
      default-sort-direction)))

(defn normalize-sort-state
  [sort-state]
  {:column (normalize-sort-column (:column sort-state))
   :direction (normalize-sort-direction (:direction sort-state))})

(defn next-sort-state
  [current-sort column]
  (let [column* (normalize-sort-column column)
        {:keys [column direction] :as normalized-sort}
        (normalize-sort-state
         (merge (default-sort-state) (or current-sort {})))
        current-column (:column normalized-sort)
        next-direction (if (= column* current-column)
                         (if (= :asc direction) :desc :asc)
                         (if (= :valid-until column*) :desc :asc))]
    {:column column*
     :direction next-direction}))

(defn normalize-form-field
  [value]
  (let [field (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)]
    (when (contains? valid-form-fields field)
      field)))

(defn normalize-form-value
  [field value]
  (case (normalize-form-field field)
    :name
    (str (or value ""))

    :address
    (-> (str (or value ""))
        str/trim
        str/lower-case)

    :days-valid
    (-> (str (or value ""))
        str/trim
        (str/replace #"[^0-9]" ""))

    (str (or value ""))))

(defn form-errors
  [form]
  (let [form* (merge (default-form) (or form {}))
        name* (some-> (:name form*) str str/trim)
        address* (some-> (:address form*) agent-session/normalize-wallet-address)
        days-valid* (some-> (:days-valid form*) str str/trim)
        normalized-days (agent-session/normalize-agent-valid-days days-valid*)]
    {:name (when-not (seq name*)
             "Enter an API wallet name.")
     :address (when-not (seq address*)
                "Enter a valid wallet address.")
     :days-valid (when (and (seq days-valid*)
                            (nil? normalized-days))
                   (str "Enter a value from 1 to "
                        agent-session/max-agent-valid-days
                        " days."))}))

(defn form-valid?
  [form]
  (every? nil? (vals (form-errors form))))

(defn first-form-error
  [form]
  (some identity (vals (form-errors form))))

(defn approval-name-for-row
  [row]
  (when (= :named (:row-kind row))
    (or (:approval-name row)
        (:name row))))

(defn merged-rows
  [extra-agents default-agent-row]
  (cond-> (vec (or extra-agents []))
    (map? default-agent-row)
    (conj default-agent-row)))

(defn- compare-string-values
  [left right]
  (compare (str/lower-case (or left ""))
           (str/lower-case (or right ""))))

(defn- compare-valid-until
  [left right]
  (let [left-ms (:valid-until-ms left)
        right-ms (:valid-until-ms right)]
    (cond
      (and (nil? left-ms) (nil? right-ms)) 0
      (nil? left-ms) 1
      (nil? right-ms) -1
      :else (compare left-ms right-ms))))

(defn- compare-rows
  [column left right]
  (let [primary (case column
                  :address (compare-string-values (:address left) (:address right))
                  :valid-until (compare-valid-until left right)
                  (compare-string-values (:name left) (:name right)))]
    (if (zero? primary)
      (let [secondary (compare-string-values (:name left) (:name right))]
        (if (zero? secondary)
          (compare-string-values (:address left) (:address right))
          secondary))
      primary)))

(defn sorted-rows
  [rows sort-state]
  (let [{:keys [column direction]}
        (normalize-sort-state
         (merge (default-sort-state) (or sort-state {})))
        descending? (= :desc direction)]
    (->> (or rows [])
         (sort (fn [left right]
                 (let [comparison (compare-rows column left right)]
                   (if descending?
                     (> comparison 0)
                     (< comparison 0)))))
         vec)))

(defn generated-private-key
  [generated-state form-address]
  (let [generated-address (:address generated-state)
        generated-private-key (:private-key generated-state)]
    (when (= (agent-session/normalize-wallet-address generated-address)
             (agent-session/normalize-wallet-address form-address))
      generated-private-key)))

(defn valid-until-preview-ms
  [server-time-ms days-valid]
  (when-let [normalized-days (agent-session/normalize-agent-valid-days days-valid)]
    (when (number? server-time-ms)
      (+ server-time-ms
         (* normalized-days 24 60 60 1000)))))
