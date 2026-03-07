(ns hyperopen.api-wallets.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api-wallets.actions :as actions]))

(def owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def api-wallet-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(defn- path-value
  [effects path]
  (let [[_ path-values] (first effects)]
    (some (fn [[candidate-path candidate-value]]
            (when (= path candidate-path)
              candidate-value))
          path-values)))

(deftest parse-api-wallet-route-supports-uppercase-and-lowercase-test
  (is (= :page (:kind (actions/parse-api-wallet-route "/API"))))
  (is (= :page (:kind (actions/parse-api-wallet-route "/api?tab=test"))))
  (is (= :other (:kind (actions/parse-api-wallet-route "/trade")))))

(deftest load-api-wallet-route-emits-projection-before-heavy-load-test
  (let [effects (actions/load-api-wallet-route {:wallet {:address owner-address}}
                                               "/API")]
    (is (= :effects/save-many (ffirst effects)))
    (is (true? (path-value effects [:api-wallets :loading :extra-agents?])))
    (is (true? (path-value effects [:api-wallets :loading :default-agent?])))
    (is (= (actions/default-api-wallet-modal-state)
           (path-value effects [:api-wallets-ui :modal])))
    (is (= [:effects/api-load-api-wallets]
           (second effects)))))

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
