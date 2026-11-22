(ns hyperopen.account-type.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account-type.actions :as actions]))

(def owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

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

(defn- owner-state
  ([]
   (owner-state nil))
  ([abstraction-raw]
   {:wallet {:address owner-address}
    :account (when abstraction-raw
               {:mode :classic
                :abstraction-raw abstraction-raw})}))

(deftest current-option-maps-every-abstraction-payload-test
  (doseq [[raw expected]
          [["unifiedAccount" :unified-account]
           ["portfolioMargin" :portfolio-margin]
           ["default" :manual]
           ["disabled" :manual]
           ["dexAbstraction" :manual]
           ["unknown" :manual]
           [nil :manual]]]
    (is (= expected (actions/current-option (owner-state raw)))
        (str "raw abstraction " (pr-str raw)))))

(deftest open-modal-preselects-current-account-type-test
  (let [effects (actions/open-account-type-modal (owner-state "unifiedAccount"))]
    (is (= [[:effects/save-many
             [[[:account-type-ui :modal-open?] true]
              [[:account-type-ui :selected] :unified-account]
              [[:account-type-ui :submitting?] false]
              [[:account-type-ui :error-message] nil]]]]
           effects))))

(deftest close-modal-clears-transient-ui-state-test
  (is (= [[:effects/save-many
           [[[:account-type-ui :modal-open?] false]
            [[:account-type-ui :submitting?] false]
            [[:account-type-ui :error-message] nil]]]]
          (actions/close-account-type-modal (owner-state)))))

(deftest select-option-saves-only-valid-choices-test
  (is (= [[:effects/save-many [[[:account-type-ui :selected] :portfolio-margin]]]]
          (actions/select-account-type {} :portfolio-margin)))
  (is (= [] (actions/select-account-type {} :not-an-option))))

(deftest submit-option-requires-connected-wallet-test
  (let [effects (actions/submit-account-type {:wallet {}} :unified-account)]
    (is (= 1 (count effects)))
    (is (= "Connect your wallet before changing account type."
           (path-value effects [:account-type-ui :error-message])))))

(deftest submit-option-rejects-choice-matching-current-type-test
  (let [effects (actions/submit-account-type (owner-state "unifiedAccount")
                                             :unified-account)]
    (is (= 1 (count effects)))
    (is (= "Unified Account is already your account type."
           (path-value effects [:account-type-ui :error-message])))))

(deftest submit-option-signals-abstraction-change-effect-test
  (let [effects (actions/submit-account-type (owner-state "unifiedAccount")
                                             :portfolio-margin)]
    (is (= [[:effects/save-many
             [[[:account-type-ui :submitting?] true]
              [[:account-type-ui :error-message] nil]]]
            [:effects/api-set-user-abstraction
             {:owner owner-address
              :abstraction "portfolioMargin"}]]
           effects))))

(deftest submit-option-maps-every-option-to-its-wire-abstraction-test
  (doseq [[current-raw option abstraction]
          [["portfolioMargin" :unified-account "unifiedAccount"]
           ["unifiedAccount" :portfolio-margin "portfolioMargin"]
           ["unifiedAccount" :manual "disabled"]]]
    (let [effects (actions/submit-account-type (owner-state current-raw) option)
          request (second (second effects))]
      (is (= abstraction (:abstraction request))
          (str "option " (name option))))))
