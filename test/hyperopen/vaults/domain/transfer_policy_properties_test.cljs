(ns hyperopen.vaults.domain.transfer-policy-properties-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop :include-macros true]
            [hyperopen.schema.vault-transfer-contracts :as contracts]
            [hyperopen.vaults.domain.transfer-policy :as transfer-policy]))

(def ^:private vault-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private leader-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(def ^:private other-address
  "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(def ^:private vault-usdc-micros-scale
  1000000)

(defn- digits->text
  [digits]
  (apply str digits))

(def ^:private digit-gen
  (gen/elements [\0 \1 \2 \3 \4 \5 \6 \7 \8 \9]))

(def ^:private whole-part-gen
  (gen/fmap digits->text
            (gen/vector digit-gen 1 10)))

(def ^:private fractional-part-gen
  (gen/fmap digits->text
            (gen/vector digit-gen 0 8)))

(def ^:private fractional-only-gen
  (gen/fmap digits->text
            (gen/vector digit-gen 1 8)))

(def ^:private canonical-decimal-gen
  (gen/one-of
   [whole-part-gen
    (gen/fmap (fn [[whole fraction]]
                (str whole "." fraction))
              (gen/tuple whole-part-gen fractional-part-gen))
    (gen/fmap #(str "." %)
              fractional-only-gen)]))

(defn- decimal-text->micros-spec
  [text]
  (when-let [[_ int-part frac-part frac-only]
             (re-matches #"^(?:(\d+)(?:\.(\d*))?|\.(\d+))$" text)]
    (let [whole (if int-part (js/parseInt int-part 10) 0)
          fraction-source (or frac-part frac-only "")
          fraction-padded (subs (str fraction-source "000000") 0 6)
          fraction (js/parseInt fraction-padded 10)
          micros (+ (* whole vault-usdc-micros-scale) fraction)]
      (when (<= micros js/Number.MAX_SAFE_INTEGER)
        micros))))

(def ^:private deposit-case-gen
  (gen/hash-map
   :details-present? gen/boolean
   :merged-present? gen/boolean
   :details-has-leader? gen/boolean
   :merged-has-leader? gen/boolean
   :allow-deposits? (gen/elements [true false nil])
   :wallet-matches-leader? gen/boolean
   :liquidator-source (gen/elements [:none :details :merged])))

(defn- name-for-source
  [source target]
  (cond
    (= source target) " Liquidator "
    (= target :details) "Vault Detail"
    :else "Merged Vault"))

(defn- deposit-case->state
  [{:keys [details-present?
           merged-present?
           details-has-leader?
           merged-has-leader?
           allow-deposits?
           wallet-matches-leader?
           liquidator-source]}]
  (let [details (cond-> {}
                  details-present? (assoc :name (name-for-source liquidator-source :details))
                  details-has-leader? (assoc :leader leader-address)
                  details-present? (assoc :allow-deposits? allow-deposits?))
        merged-row (cond-> {:vault-address vault-address
                            :name (name-for-source liquidator-source :merged)}
                     merged-has-leader? (assoc :leader leader-address))]
    {:wallet {:address (if wallet-matches-leader? leader-address other-address)}
     :vaults {:details-by-address (cond-> {}
                                    details-present? (assoc vault-address details))
              :merged-index-rows (cond-> []
                                   merged-present? (conj merged-row))}}))

(defn- deposit-allowed-spec
  [{:keys [details-present?
           merged-present?
           details-has-leader?
           merged-has-leader?
           allow-deposits?
           wallet-matches-leader?
           liquidator-source]}]
  (let [leader-present? (or (and details-present? details-has-leader?)
                            (and (not (and details-present? details-has-leader?))
                                 merged-present?
                                 merged-has-leader?))
        leader? (and leader-present? wallet-matches-leader?)
        liquidator? (cond
                      details-present? (= liquidator-source :details)
                      merged-present? (= liquidator-source :merged)
                      :else false)]
    (and (not liquidator?)
         (or leader?
             (and details-present?
                  (true? allow-deposits?))))))

(def ^:private preview-case-gen
  (gen/hash-map
   :mode (gen/elements [:deposit :withdraw])
   :withdraw-all? gen/boolean
   :amount-input canonical-decimal-gen))

(defn- preview-case->expected
  [{:keys [mode withdraw-all? amount-input]}]
  (let [withdraw-all?* (and (= mode :withdraw) withdraw-all?)
        amount-micros (if withdraw-all?*
                        0
                        (decimal-text->micros-spec amount-input))]
    (if (or (nil? amount-micros)
            (and (not withdraw-all?*)
                 (<= amount-micros 0)))
      {:ok? false
       :display-message "Enter an amount greater than 0."}
      {:ok? true
       :mode mode
       :vault-address vault-address
       :display-message nil
       :request {:vault-address vault-address
                 :action {:type "vaultTransfer"
                          :vaultAddress vault-address
                          :isDeposit (= mode :deposit)
                          :usd amount-micros}}})))

(deftest parse-usdc-micros-matches-exact-decimal-spec-property-test
  (let [property (prop/for-all [text canonical-decimal-gen]
                   (= (decimal-text->micros-spec text)
                      (transfer-policy/parse-usdc-micros text)))
        result (tc/quick-check 200 property)]
    (is (:pass? result) (pr-str result))))

(deftest vault-transfer-deposit-allowed-matches-decision-table-property-test
  (let [property (prop/for-all [case deposit-case-gen]
                   (= (deposit-allowed-spec case)
                      (transfer-policy/vault-transfer-deposit-allowed?
                       (deposit-case->state case)
                       vault-address)))
        result (tc/quick-check 200 property)]
    (is (:pass? result) (pr-str result))))

(deftest vault-transfer-preview-success-results-stay-self-consistent-property-test
  (let [base-state {:ui {:locale "en-US"}
                    :wallet {:address leader-address}
                    :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                                 :leader leader-address
                                                                 :allow-deposits? true}}
                             :merged-index-rows [{:vault-address vault-address
                                                  :name "Vault Detail"
                                                  :leader leader-address}]}}
        property (prop/for-all [case preview-case-gen]
                   (let [actual (transfer-policy/vault-transfer-preview
                                 {}
                                 base-state
                                 {:open? true
                                  :mode (:mode case)
                                  :vault-address vault-address
                                  :amount-input (:amount-input case)
                                  :withdraw-all? (:withdraw-all? case)})
                         expected (preview-case->expected case)]
                     (if (and (= :withdraw (:mode case))
                              (true? (:withdraw-all? case)))
                       (and (true? (:ok? actual))
                            (= :withdraw (:mode actual))
                            (= 0 (get-in actual [:request :action :usd]))
                            (contracts/preview-result-valid? actual))
                       (and (= expected actual)
                            (contracts/preview-result-valid? actual)))))
        result (tc/quick-check 200 property)]
    (is (:pass? result) (pr-str result))))
