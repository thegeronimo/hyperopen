(ns hyperopen.api-wallets.actions
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.wallet.agent-session :as agent-session]))

(def canonical-route
  "/API")

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

(def ^:private api-wallet-route-kinds
  #{"/api"})

(defn default-api-wallet-form
  []
  {:name ""
   :address ""
   :days-valid ""})

(defn default-api-wallet-modal-state
  []
  {:open? false
   :type nil
   :row nil
   :error nil
   :submitting? false})

(defn normalize-api-wallet-sort-column
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

(defn normalize-api-wallet-sort-direction
  [value]
  (let [direction (cond
                    (keyword? value) value
                    (string? value) (-> value str/trim str/lower-case keyword)
                    :else nil)]
    (if (contains? valid-sort-directions direction)
      direction
      default-sort-direction)))

(defn normalize-api-wallet-form-field
  [value]
  (let [field (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)]
    (if (contains? valid-form-fields field)
      field
      nil)))

(defn- split-path-from-query-fragment
  [path]
  (let [path* (if (string? path) path (str (or path "")))]
    (or (first (str/split path* #"[?#]" 2))
        "")))

(defn- trim-trailing-slashes
  [path]
  (loop [path* path]
    (if (and (> (count path*) 1)
             (str/ends-with? path* "/"))
      (recur (subs path* 0 (dec (count path*))))
      path*)))

(defn normalize-route-path
  [path]
  (-> path
      split-path-from-query-fragment
      str/trim
      trim-trailing-slashes))

(defn parse-api-wallet-route
  [path]
  (let [path* (normalize-route-path path)
        path-lower (str/lower-case path*)]
    (if (contains? api-wallet-route-kinds path-lower)
      {:kind :page
       :path path*}
      {:kind :other
       :path path*})))

(defn api-wallet-route?
  [path]
  (= :page (:kind (parse-api-wallet-route path))))

(defn normalize-api-wallet-form-value
  [field value]
  (case (normalize-api-wallet-form-field field)
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

(defn api-wallet-form-errors
  [form]
  (let [form* (merge (default-api-wallet-form) (or form {}))
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

(defn api-wallet-form-valid?
  [form]
  (every? nil? (vals (api-wallet-form-errors form))))

(defn- first-form-error
  [form]
  (some identity (vals (api-wallet-form-errors form))))

(defn load-api-wallet-route
  [state path]
  (if (api-wallet-route? path)
    (let [connected-owner? (seq (account-context/owner-address state))
          path-values [[[:api-wallets :extra-agents] []]
                       [[:api-wallets :default-agent-row] nil]
                       [[:api-wallets :owner-webdata2] nil]
                       [[:api-wallets :server-time-ms] nil]
                       [[:api-wallets :loading :extra-agents?] (boolean connected-owner?)]
                       [[:api-wallets :loading :default-agent?] (boolean connected-owner?)]
                       [[:api-wallets :errors :extra-agents] nil]
                       [[:api-wallets :errors :default-agent] nil]
                       [[:api-wallets :loaded-at-ms :extra-agents] nil]
                       [[:api-wallets :loaded-at-ms :default-agent] nil]
                       [[:api-wallets-ui :form-error] nil]
                       [[:api-wallets-ui :modal] (default-api-wallet-modal-state)]]]
      (cond-> [[:effects/save-many path-values]]
        connected-owner?
        (conj [:effects/api-load-api-wallets])))
    []))

(defn set-api-wallet-form-field
  [_state field value]
  (if-let [field* (normalize-api-wallet-form-field field)]
    [[:effects/save-many [[[:api-wallets-ui :form field*]
                           (normalize-api-wallet-form-value field* value)]
                          [[:api-wallets-ui :form-error] nil]]]]
    []))

(defn set-api-wallet-sort
  [state column]
  (let [column* (normalize-api-wallet-sort-column column)
        current-sort (or (get-in state [:api-wallets-ui :sort])
                         {:column default-sort-column
                          :direction default-sort-direction})
        current-column (normalize-api-wallet-sort-column (:column current-sort))
        current-direction (normalize-api-wallet-sort-direction (:direction current-sort))
        next-direction (if (= column* current-column)
                         (if (= :asc current-direction) :desc :asc)
                         (if (= :valid-until column*) :desc :asc))]
    [[:effects/save [:api-wallets-ui :sort]
      {:column column*
       :direction next-direction}]]))

(defn generate-api-wallet
  [_state]
  [[:effects/generate-api-wallet]])

(defn open-api-wallet-authorize-modal
  [state]
  (let [owner-address (account-context/owner-address state)
        form (get-in state [:api-wallets-ui :form])]
    (cond
      (not (seq owner-address))
      [[:effects/save [:api-wallets-ui :form-error]
        "Connect your wallet before authorizing an API wallet."]]

      (not (api-wallet-form-valid? form))
      [[:effects/save [:api-wallets-ui :form-error]
        (first-form-error form)]]

      :else
      [[:effects/save [:api-wallets-ui :modal]
        {:open? true
         :type :authorize
         :row nil
         :error nil
         :submitting? false}]])))

(defn open-api-wallet-remove-modal
  [_state row]
  (if (map? row)
    [[:effects/save [:api-wallets-ui :modal]
      {:open? true
       :type :remove
       :row row
       :error nil
       :submitting? false}]]
    []))

(defn close-api-wallet-modal
  [_state]
  [[:effects/save-many [[[:api-wallets-ui :modal] (default-api-wallet-modal-state)]
                        [[:api-wallets-ui :generated] {:address nil
                                                      :private-key nil}]
                        [[:api-wallets-ui :form :days-valid] ""]
                        [[:api-wallets-ui :form-error] nil]]]])

(defn confirm-api-wallet-modal
  [state]
  (let [modal (get-in state [:api-wallets-ui :modal])
        modal-type (:type modal)
        owner-address (account-context/owner-address state)
        form (get-in state [:api-wallets-ui :form])
        validation-error (cond
                           (not (seq owner-address))
                           "Connect your wallet before approving an API wallet."

                           (= :authorize modal-type)
                           (first-form-error form)

                           (and (= :remove modal-type)
                                (not (map? (:row modal))))
                           "Select an API wallet row to remove."

                           :else nil)]
    (if (seq validation-error)
      [[:effects/save-many [[[:api-wallets-ui :modal :error] validation-error]
                            [[:api-wallets-ui :modal :submitting?] false]]]]
      (case modal-type
        :authorize
        [[:effects/save-many [[[:api-wallets-ui :modal :error] nil]
                              [[:api-wallets-ui :modal :submitting?] true]]]
         [:effects/api-authorize-api-wallet]]

        :remove
        [[:effects/save-many [[[:api-wallets-ui :modal :error] nil]
                              [[:api-wallets-ui :modal :submitting?] true]]]
         [:effects/api-remove-api-wallet]]

        []))))
