(ns hyperopen.api-wallets.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api-wallets.actions :as actions]
            [hyperopen.api-wallets.application.ui-state :as ui-state]))

(def owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def api-wallet-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(defn- path-value
  [effects path]
  (let [[_ path-values] (first effects)
        missing (js-obj)]
    (let [value (reduce (fn [result [candidate-path candidate-value]]
                          (if (= path candidate-path)
                            (reduced candidate-value)
                            result))
                        missing
                        path-values)]
      (when-not (identical? missing value)
        value))))

(deftest parse-api-wallet-route-supports-uppercase-and-lowercase-test
  (is (= :page (:kind (actions/parse-api-wallet-route "/API"))))
  (is (= :page (:kind (actions/parse-api-wallet-route "/api?tab=test"))))
  (is (= :other (:kind (actions/parse-api-wallet-route "/trade")))))

(deftest parse-api-wallet-route-normalizes-trailing-slashes-and-non-string-input-test
  (is (= {:kind :page
          :path "/api"}
         (actions/parse-api-wallet-route " /api///?tab=keys#section ")))
  (is (= {:kind :other
          :path "42"}
         (actions/parse-api-wallet-route 42)))
  (is (true? (actions/api-wallet-route? "/API////")))
  (is (= {:kind :other
          :path ""}
         (actions/parse-api-wallet-route nil))))

(deftest load-api-wallet-route-emits-projection-before-heavy-load-test
  (let [effects (actions/load-api-wallet-route {:wallet {:address owner-address}}
                                               "/API")]
    (is (= :effects/save-many (ffirst effects)))
    (is (true? (path-value effects [:api-wallets :loading :extra-agents?])))
    (is (true? (path-value effects [:api-wallets :loading :default-agent?])))
    (is (= (ui-state/default-modal-state)
           (path-value effects [:api-wallets-ui :modal])))
    (is (= [:effects/api-load-api-wallets]
           (second effects)))))

(deftest load-api-wallet-route-skips-heavy-load-when-route-is-inactive-or-owner-missing-test
  (is (= []
         (actions/load-api-wallet-route {:wallet {:address owner-address}}
                                        "/trade")))
  (let [effects (actions/load-api-wallet-route {:wallet {:address nil}}
                                               "/api")]
    (is (= [[:effects/save-many [[[:api-wallets :extra-agents] []]
                                 [[:api-wallets :default-agent-row] nil]
                                 [[:api-wallets :owner-webdata2] nil]
                                 [[:api-wallets :server-time-ms] nil]
                                 [[:api-wallets :loading :extra-agents?] false]
                                 [[:api-wallets :loading :default-agent?] false]
                                 [[:api-wallets :errors :extra-agents] nil]
                                 [[:api-wallets :errors :default-agent] nil]
                                 [[:api-wallets :loaded-at-ms :extra-agents] nil]
                                 [[:api-wallets :loaded-at-ms :default-agent] nil]
                                 [[:api-wallets-ui :form-error] nil]
                                 [[:api-wallets-ui :modal] (ui-state/default-modal-state)]]]]
           effects))
    (is (false? (path-value effects [:api-wallets :loading :extra-agents?])))
    (is (false? (path-value effects [:api-wallets :loading :default-agent?])))))

(deftest set-api-wallet-form-field-normalizes-supported-fields-and-ignores-unknown-fields-test
  (is (= [[:effects/save-many [[[:api-wallets-ui :form :address]
                                 api-wallet-address]
                                [[:api-wallets-ui :form-error] nil]]]]
         (actions/set-api-wallet-form-field {} "Address" "  0xABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD  ")))
  (is (= [[:effects/save-many [[[:api-wallets-ui :form :days-valid] "30"]
                                [[:api-wallets-ui :form-error] nil]]]]
         (actions/set-api-wallet-form-field {} "days valid" "30 days")))
  (is (= []
         (actions/set-api-wallet-form-field {} :unknown "value"))))

(deftest set-api-wallet-sort-and-generate-api-wallet-return-runtime-effects-test
  (is (= [[:effects/save [:api-wallets-ui :sort]
           {:column :valid-until
            :direction :desc}]]
         (actions/set-api-wallet-sort
          {:api-wallets-ui {:sort {:column :name
                                   :direction :asc}}}
          :valid-until)))
  (is (= [[:effects/generate-api-wallet]]
         (actions/generate-api-wallet {}))))

(deftest open-api-wallet-authorize-modal-validates-form-before-opening-test
  (is (= [[:effects/save [:api-wallets-ui :form-error]
           "Connect your wallet before authorizing an API wallet."]]
         (actions/open-api-wallet-authorize-modal
          {:wallet {:address nil}
           :api-wallets-ui {:form {:name "Desk"
                                   :address api-wallet-address
                                   :days-valid ""}}})))
  (is (= [[:effects/save [:api-wallets-ui :form-error]
           "Enter an API wallet name."]]
         (actions/open-api-wallet-authorize-modal
          {:wallet {:address owner-address}
           :api-wallets-ui {:form {:name ""
                                   :address api-wallet-address
                                   :days-valid ""}}}))))

(deftest open-api-wallet-authorize-modal-opens-when-owner-and-form-are-valid-test
  (is (= [[:effects/save [:api-wallets-ui :modal]
           {:open? true
            :type :authorize
            :row nil
            :error nil
            :submitting? false}]]
         (actions/open-api-wallet-authorize-modal
          {:wallet {:address owner-address}
           :api-wallets-ui {:form {:name "Desk"
                                   :address api-wallet-address
                                   :days-valid "30"}}}))))

(deftest open-and-close-api-wallet-modal-handle-guards-and-reset-state-test
  (is (= []
         (actions/open-api-wallet-remove-modal {} nil)))
  (is (= [[:effects/save [:api-wallets-ui :modal]
           {:open? true
            :type :remove
            :row {:row-kind :named
                  :name "Desk"
                  :address api-wallet-address}
            :error nil
            :submitting? false}]]
         (actions/open-api-wallet-remove-modal
          {}
          {:row-kind :named
           :name "Desk"
           :address api-wallet-address})))
  (is (= [[:effects/save-many [[[:api-wallets-ui :modal] (ui-state/default-modal-state)]
                                [[:api-wallets-ui :generated] (ui-state/default-generated-state)]
                                [[:api-wallets-ui :form :days-valid] ""]
                                [[:api-wallets-ui :form-error] nil]]]]
         (actions/close-api-wallet-modal {}))))

(deftest confirm-api-wallet-modal-emits-submit-projection-and-effect-test
  (let [authorize-effects
        (actions/confirm-api-wallet-modal
         {:wallet {:address owner-address}
          :api-wallets-ui {:form {:name "Desk"
                                  :address api-wallet-address
                                  :days-valid ""}
                           :modal {:open? true
                                   :type :authorize
                                   :submitting? false}}})
        remove-effects
        (actions/confirm-api-wallet-modal
         {:wallet {:address owner-address}
          :api-wallets-ui {:form {:name "Desk"
                                  :address api-wallet-address
                                  :days-valid ""}
                           :modal {:open? true
                                   :type :remove
                                   :row {:row-kind :named
                                         :name "Desk"
                                         :approval-name "Desk"
                                         :address api-wallet-address}
                                   :submitting? false}}})]
    (is (= [[:effects/save-many [[[:api-wallets-ui :modal :error] nil]
                                 [[:api-wallets-ui :modal :submitting?] true]]]
            [:effects/api-authorize-api-wallet]]
           authorize-effects))
    (is (= [[:effects/save-many [[[:api-wallets-ui :modal :error] nil]
                                 [[:api-wallets-ui :modal :submitting?] true]]]
            [:effects/api-remove-api-wallet]]
           remove-effects))))

(deftest confirm-api-wallet-modal-surfaces-validation-errors-test
  (is (= [[:effects/save-many [[[:api-wallets-ui :modal :error]
                                 "Connect your wallet before approving an API wallet."]
                                [[:api-wallets-ui :modal :submitting?] false]]]]
         (actions/confirm-api-wallet-modal
          {:wallet {:address nil}
           :api-wallets-ui {:form {:name "Desk"
                                   :address api-wallet-address
                                   :days-valid ""}
                            :modal {:open? true
                                    :type :authorize
                                    :submitting? true}}})))
  (is (= [[:effects/save-many [[[:api-wallets-ui :modal :error]
                                 "Enter an API wallet name."]
                                [[:api-wallets-ui :modal :submitting?] false]]]]
         (actions/confirm-api-wallet-modal
          {:wallet {:address owner-address}
           :api-wallets-ui {:form {:name ""
                                   :address api-wallet-address
                                   :days-valid ""}
                            :modal {:open? true
                                    :type :authorize
                                    :submitting? true}}})))
  (is (= [[:effects/save-many [[[:api-wallets-ui :modal :error]
                                 "Select an API wallet row to remove."]
                                [[:api-wallets-ui :modal :submitting?] false]]]]
         (actions/confirm-api-wallet-modal
          {:wallet {:address owner-address}
           :api-wallets-ui {:form {:name "Desk"
                                   :address api-wallet-address
                                   :days-valid ""}
                            :modal {:open? true
                                    :type :remove
                                    :row nil
                                    :submitting? true}}}))))

(deftest confirm-api-wallet-modal-returns-no-effects-for-unknown-modal-type-test
  (is (= []
         (actions/confirm-api-wallet-modal
          {:wallet {:address owner-address}
           :api-wallets-ui {:form {:name "Desk"
                                   :address api-wallet-address
                                   :days-valid ""}
                            :modal {:open? true
                                    :type :unknown
                                    :submitting? false}}}))))
