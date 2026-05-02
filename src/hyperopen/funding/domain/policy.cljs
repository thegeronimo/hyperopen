(ns hyperopen.funding.domain.policy
  (:require [hyperopen.funding.domain.amounts :as amounts]
            [hyperopen.funding.domain.availability :as availability]
            [hyperopen.funding.domain.hyperunit :as hyperunit]
            [hyperopen.funding.domain.preview :as preview]))

(def non-blank-text amounts/non-blank-text)
(def parse-num amounts/parse-num)
(def finite-number? amounts/finite-number?)
(def amount->text amounts/amount->text)
(def normalize-amount-input amounts/normalize-amount-input)
(def parse-input-amount amounts/parse-input-amount)
(def normalize-evm-address amounts/normalize-evm-address)
(def normalize-withdraw-destination amounts/normalize-withdraw-destination)
(def format-usdc-display amounts/format-usdc-display)
(def format-usdc-input amounts/format-usdc-input)

(def normalize-mode preview/normalize-mode)
(def normalize-deposit-step preview/normalize-deposit-step)
(def normalize-withdraw-step preview/normalize-withdraw-step)

(def withdraw-assets availability/withdraw-assets)
(def withdraw-assets-filtered availability/withdraw-assets-filtered)
(def withdraw-asset availability/withdraw-asset)
(def transfer-max-amount availability/transfer-max-amount)
(def withdraw-max-amount availability/withdraw-max-amount)

(def hyperunit-lifecycle-failure? hyperunit/hyperunit-lifecycle-failure?)
(def hyperunit-lifecycle-recovery-hint hyperunit/hyperunit-lifecycle-recovery-hint)
(def hyperunit-explorer-tx-url hyperunit/hyperunit-explorer-tx-url)
(def hyperunit-fee-entry hyperunit/hyperunit-fee-entry)
(def hyperunit-withdrawal-queue-entry hyperunit/hyperunit-withdrawal-queue-entry)
(def estimate-fee-display hyperunit/estimate-fee-display)

(def transfer-preview preview/transfer-preview)
(def send-preview preview/send-preview)
(def withdraw-preview preview/withdraw-preview)
(def deposit-preview preview/deposit-preview)
(def preview preview/preview)

(def ^:private direct-balance-row-available availability/direct-balance-row-available)
(def ^:private derived-balance-row-available availability/derived-balance-row-available)
(def ^:private balance-row-available availability/balance-row-available)
(def ^:private summary-derived-withdrawable availability/summary-derived-withdrawable)
(def ^:private withdrawable-usdc availability/withdrawable-usdc)
(def ^:private withdraw-available-amount availability/withdraw-available-amount)
(def ^:private withdraw-available-list-display availability/withdraw-available-list-display)
(def ^:private withdraw-preview-error preview/withdraw-preview-error)
