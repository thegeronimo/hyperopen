(ns hyperopen.api-wallets.actions
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api-wallets.domain.policy :as policy]))

(def canonical-route
  "/API")

(def ^:private api-wallet-route-kinds
  #{"/api"})

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
                       [[:api-wallets-ui :modal] (policy/default-modal-state)]]]
      (cond-> [[:effects/save-many path-values]]
        connected-owner?
        (conj [:effects/api-load-api-wallets])))
    []))

(defn set-api-wallet-form-field
  [_state field value]
  (if-let [field* (policy/normalize-form-field field)]
    [[:effects/save-many [[[:api-wallets-ui :form field*]
                           (policy/normalize-form-value field* value)]
                          [[:api-wallets-ui :form-error] nil]]]]
    []))

(defn set-api-wallet-sort
  [state column]
  (let [next-sort (policy/next-sort-state
                   (get-in state [:api-wallets-ui :sort])
                   column)]
    [[:effects/save [:api-wallets-ui :sort]
      next-sort]]))

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

      (not (policy/form-valid? form))
      [[:effects/save [:api-wallets-ui :form-error]
        (policy/first-form-error form)]]

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
  [[:effects/save-many [[[:api-wallets-ui :modal] (policy/default-modal-state)]
                        [[:api-wallets-ui :generated] (policy/default-generated-state)]
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
                           (policy/first-form-error form)

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
