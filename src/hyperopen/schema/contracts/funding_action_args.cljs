(ns hyperopen.schema.contracts.funding-action-args
  (:require [cljs.spec.alpha :as s]
            [hyperopen.schema.contracts.common :as common]
            [hyperopen.schema.contracts.state :as state]))

;; Funding modal action payload contracts. Merged into
;; hyperopen.schema.contracts.action-args/action-args-spec-by-id.

(s/def ::funding-modal-args (s/tuple any?))
(s/def ::funding-modal-field-args (s/tuple ::common/state-path any?))
(s/def ::submit-funding-repay-args (s/tuple number?))
(s/def ::set-hyperunit-lifecycle-args (s/tuple ::state/hyperunit-lifecycle-input))
(s/def ::set-hyperunit-lifecycle-error-args (s/tuple (s/nilable string?)))
(s/def ::funding-send-open-args
  (s/or :none ::common/no-args
        :context-only (s/tuple map?)
        :context-and-anchor (s/tuple map? any?)
        :context-anchor-and-data-role (s/tuple map? any? (s/nilable string?))))
(s/def ::funding-modal-open-args
  (s/or :none ::common/no-args
        :anchor-only (s/tuple any?)
        :anchor-and-data-role (s/tuple any? (s/nilable string?))))
(s/def ::funding-transfer-open-args
  (s/or :none ::common/no-args
        :anchor-only (s/tuple any?)
        :anchor-and-data-role (s/tuple any? (s/nilable string?))
        :anchor-data-role-and-context (s/tuple any? (s/nilable string?) map?)))

(s/def ::transfer-side #{:from :to "from" "to"})
(s/def ::transfer-location #{:perps :spot :hyperevm "perps" "spot" "hyperevm"})
(s/def ::set-funding-transfer-location-args
  (s/tuple ::transfer-side ::transfer-location))
(s/def ::select-funding-transfer-asset-args
  (s/tuple nat-int?))
(s/def ::set-funding-transfer-amount-percent-args
  (s/tuple (s/and number? pos? #(<= % 100))))

(def funding-action-args-spec-by-id
  {:actions/set-funding-modal ::funding-modal-args
   :actions/open-funding-send-modal ::funding-send-open-args
   :actions/open-funding-transfer-modal ::funding-transfer-open-args
   :actions/open-funding-withdraw-modal ::funding-modal-open-args
   :actions/open-funding-deposit-modal ::funding-modal-open-args
   :actions/close-funding-modal ::common/no-args
   :actions/handle-funding-modal-keydown ::common/key-args
   :actions/set-funding-modal-field ::funding-modal-field-args
   :actions/search-funding-deposit-assets ::common/single-input-args
   :actions/search-funding-withdraw-assets ::common/single-input-args
   :actions/select-funding-deposit-asset ::common/keyword-or-string-args
   :actions/return-to-funding-deposit-asset-select ::common/no-args
   :actions/return-to-funding-withdraw-asset-select ::common/no-args
   :actions/enter-funding-deposit-amount ::common/single-input-args
   :actions/set-funding-deposit-amount-to-minimum ::common/no-args
   :actions/enter-funding-transfer-amount ::common/single-input-args
   :actions/select-funding-withdraw-asset ::common/keyword-or-string-args
   :actions/enter-funding-withdraw-destination ::common/single-input-args
   :actions/enter-funding-withdraw-amount ::common/single-input-args
   :actions/set-hyperunit-lifecycle ::set-hyperunit-lifecycle-args
   :actions/clear-hyperunit-lifecycle ::common/no-args
   :actions/set-hyperunit-lifecycle-error ::set-hyperunit-lifecycle-error-args
   :actions/set-funding-transfer-direction ::common/boolean-args
   :actions/set-funding-amount-to-max ::common/no-args
   :actions/submit-funding-send ::common/no-args
   :actions/submit-funding-transfer ::common/no-args
   :actions/submit-funding-repay ::submit-funding-repay-args
   :actions/submit-funding-withdraw ::common/no-args
   :actions/submit-funding-deposit ::common/no-args
   :actions/set-funding-transfer-location ::set-funding-transfer-location-args
   :actions/swap-funding-transfer-locations ::common/no-args
   :actions/select-funding-transfer-asset ::select-funding-transfer-asset-args
   :actions/set-funding-transfer-amount-percent ::set-funding-transfer-amount-percent-args
   :actions/submit-funding-transfer-gas-topup ::common/no-args
   :actions/reset-funding-transfer-evm ::common/no-args
   :actions/retry-funding-transfer-capability ::common/no-args
   :actions/add-funding-transfer-token-to-wallet ::common/no-args})
