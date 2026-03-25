(ns hyperopen.api-wallets.application.ui-state
  (:require [clojure.string :as str]))

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
