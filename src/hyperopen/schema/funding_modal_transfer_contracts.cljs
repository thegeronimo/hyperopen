(ns hyperopen.schema.funding-modal-transfer-contracts
  (:require [cljs.spec.alpha :as s]))

;; Transfer-mode submap of the funding modal VM contract. The spec keywords are
;; the same :funding-modal-vm.* names the parent contract composes.
;; hyperopen.schema.funding-modal-contracts requires this namespace and
;; registers the :funding-modal-vm.transfer/actions alias itself, because
;; cljs.spec resolves keyword aliases eagerly and :funding-modal-vm/actions is
;; defined there. Nothing below aliases a parent spec.

(def ^:private required-transfer-amount-keys
  #{:value :max-display :max-input :symbol :notice})

(def ^:private required-transfer-keys
  #{:to-perp? :route :from-options :to-options :swap-action :asset :balances
    :destination :summary :usd-estimate :percent-actions :blocked :evm :amount
    :message :actions})

(def ^:private required-route-keys #{:from :to :kind :valid?})
(def ^:private required-location-option-keys
  #{:id :label :selected? :disabled? :reason :action :data-role})
(def ^:private required-asset-keys #{:visible? :options :selected :empty-message})
(def ^:private required-asset-option-keys
  #{:index :symbol :balance-display :selected? :disabled? :reason :action})
(def ^:private required-selected-asset-keys #{:symbol :notice :explorer-url})
(def ^:private required-balances-keys #{:from :to})
(def ^:private required-balance-side-keys #{:label :before :after :delta :available})
(def ^:private required-destination-keys #{:display})
(def ^:private required-summary-row-keys #{:label :value :tone})
(def ^:private required-percent-action-keys #{:label :action})
(def ^:private required-blocked-keys #{:code :checking? :title :message :explorer-url :fix})
(def ^:private required-fix-keys
  #{:label :action :status :status-message :disabled? :reason})
(def ^:private required-evm-keys
  #{:phase :flow-id :steps :step-index :step-count :tx-url :error :arrival :result
    :maybe-sent? :retry-action :add-to-wallet?})
(def ^:private required-evm-step-keys #{:id :label :status :detail})

(defn- exact-keys?
  [value expected-keys]
  (= expected-keys (set (keys value))))

(defn- exact-map
  [spec expected-keys]
  (s/and spec #(exact-keys? % expected-keys)))

(s/def ::nilable-string (s/nilable string?))
(s/def ::action (s/and vector? #(keyword? (first %))))
(s/def ::location #{:perps :spot :hyperevm})

(s/def :funding-modal-vm.transfer/to-perp? boolean?)
(s/def :funding-modal-vm.transfer-amount/value string?)
(s/def :funding-modal-vm.transfer-amount/max-display string?)
(s/def :funding-modal-vm.transfer-amount/max-input string?)
(s/def :funding-modal-vm.transfer-amount/symbol string?)
(s/def :funding-modal-vm.transfer-amount/notice ::nilable-string)
(s/def :funding-modal-vm/transfer-amount
  (exact-map (s/keys :req-un [:funding-modal-vm.transfer-amount/value
                              :funding-modal-vm.transfer-amount/max-display
                              :funding-modal-vm.transfer-amount/max-input
                              :funding-modal-vm.transfer-amount/symbol
                              :funding-modal-vm.transfer-amount/notice])
             required-transfer-amount-keys))

(s/def :funding-modal-vm.transfer-route/from (s/nilable ::location))
(s/def :funding-modal-vm.transfer-route/to (s/nilable ::location))
(s/def :funding-modal-vm.transfer-route/kind #{:core-internal :core->evm :evm->core})
(s/def :funding-modal-vm.transfer-route/valid? boolean?)
(s/def :funding-modal-vm.transfer/route
  (exact-map (s/keys :req-un [:funding-modal-vm.transfer-route/from
                              :funding-modal-vm.transfer-route/to
                              :funding-modal-vm.transfer-route/kind
                              :funding-modal-vm.transfer-route/valid?])
             required-route-keys))

(s/def :funding-modal-vm.transfer-location/id ::location)
(s/def :funding-modal-vm.transfer-location/label string?)
(s/def :funding-modal-vm.transfer-location/selected? boolean?)
(s/def :funding-modal-vm.transfer-location/disabled? boolean?)
(s/def :funding-modal-vm.transfer-location/reason ::nilable-string)
(s/def :funding-modal-vm.transfer-location/action ::action)
(s/def :funding-modal-vm.transfer-location/data-role string?)
(s/def :funding-modal-vm/transfer-location-option
  (exact-map (s/keys :req-un [:funding-modal-vm.transfer-location/id
                              :funding-modal-vm.transfer-location/label
                              :funding-modal-vm.transfer-location/selected?
                              :funding-modal-vm.transfer-location/disabled?
                              :funding-modal-vm.transfer-location/reason
                              :funding-modal-vm.transfer-location/action
                              :funding-modal-vm.transfer-location/data-role])
             required-location-option-keys))
(s/def :funding-modal-vm.transfer/from-options
  (s/coll-of :funding-modal-vm/transfer-location-option :kind vector?))
(s/def :funding-modal-vm.transfer/to-options
  (s/coll-of :funding-modal-vm/transfer-location-option :kind vector?))
(s/def :funding-modal-vm.transfer/swap-action ::action)

(s/def :funding-modal-vm.transfer-asset-option/index nat-int?)
(s/def :funding-modal-vm.transfer-asset-option/symbol string?)
(s/def :funding-modal-vm.transfer-asset-option/balance-display string?)
(s/def :funding-modal-vm.transfer-asset-option/selected? boolean?)
(s/def :funding-modal-vm.transfer-asset-option/disabled? boolean?)
(s/def :funding-modal-vm.transfer-asset-option/reason ::nilable-string)
(s/def :funding-modal-vm.transfer-asset-option/action ::action)
(s/def :funding-modal-vm/transfer-asset-option
  (exact-map (s/keys :req-un [:funding-modal-vm.transfer-asset-option/index
                              :funding-modal-vm.transfer-asset-option/symbol
                              :funding-modal-vm.transfer-asset-option/balance-display
                              :funding-modal-vm.transfer-asset-option/selected?
                              :funding-modal-vm.transfer-asset-option/disabled?
                              :funding-modal-vm.transfer-asset-option/reason
                              :funding-modal-vm.transfer-asset-option/action])
             required-asset-option-keys))
(s/def :funding-modal-vm.transfer-selected-asset/symbol string?)
(s/def :funding-modal-vm.transfer-selected-asset/notice ::nilable-string)
(s/def :funding-modal-vm.transfer-selected-asset/explorer-url ::nilable-string)
(s/def :funding-modal-vm.transfer-asset/visible? boolean?)
(s/def :funding-modal-vm.transfer-asset/options
  (s/coll-of :funding-modal-vm/transfer-asset-option :kind vector?))
(s/def :funding-modal-vm.transfer-asset/selected
  (s/nilable (exact-map (s/keys :req-un [:funding-modal-vm.transfer-selected-asset/symbol
                                         :funding-modal-vm.transfer-selected-asset/notice
                                         :funding-modal-vm.transfer-selected-asset/explorer-url])
                        required-selected-asset-keys)))
(s/def :funding-modal-vm.transfer-asset/empty-message ::nilable-string)
(s/def :funding-modal-vm.transfer/asset
  (exact-map (s/keys :req-un [:funding-modal-vm.transfer-asset/visible?
                              :funding-modal-vm.transfer-asset/options
                              :funding-modal-vm.transfer-asset/selected
                              :funding-modal-vm.transfer-asset/empty-message])
             required-asset-keys))

(s/def :funding-modal-vm.transfer-balance-side/label string?)
(s/def :funding-modal-vm.transfer-balance-side/before ::nilable-string)
(s/def :funding-modal-vm.transfer-balance-side/after ::nilable-string)
(s/def :funding-modal-vm.transfer-balance-side/delta ::nilable-string)
;; What may move from this side: `:before` less the gas MAX keeps for native
;; HYPE leaving HyperEVM, else `:before`.
(s/def :funding-modal-vm.transfer-balance-side/available ::nilable-string)
(s/def :funding-modal-vm/transfer-balance-side
  (exact-map (s/keys :req-un [:funding-modal-vm.transfer-balance-side/label
                              :funding-modal-vm.transfer-balance-side/before
                              :funding-modal-vm.transfer-balance-side/after
                              :funding-modal-vm.transfer-balance-side/delta
                              :funding-modal-vm.transfer-balance-side/available])
             required-balance-side-keys))
(s/def :funding-modal-vm.transfer-balances/from :funding-modal-vm/transfer-balance-side)
(s/def :funding-modal-vm.transfer-balances/to :funding-modal-vm/transfer-balance-side)
(s/def :funding-modal-vm.transfer/balances
  (exact-map (s/keys :req-un [:funding-modal-vm.transfer-balances/from
                              :funding-modal-vm.transfer-balances/to])
             required-balances-keys))

(s/def :funding-modal-vm.transfer-destination/display string?)
(s/def :funding-modal-vm.transfer/destination
  (exact-map (s/keys :req-un [:funding-modal-vm.transfer-destination/display])
             required-destination-keys))

(s/def :funding-modal-vm.transfer-summary-row/label string?)
(s/def :funding-modal-vm.transfer-summary-row/value string?)
(s/def :funding-modal-vm.transfer-summary-row/tone #{:neutral :warn})
(s/def :funding-modal-vm/transfer-summary-row
  (exact-map (s/keys :req-un [:funding-modal-vm.transfer-summary-row/label
                              :funding-modal-vm.transfer-summary-row/value
                              :funding-modal-vm.transfer-summary-row/tone])
             required-summary-row-keys))
(s/def :funding-modal-vm.transfer/summary
  (s/coll-of :funding-modal-vm/transfer-summary-row :kind vector?))
(s/def :funding-modal-vm.transfer/usd-estimate ::nilable-string)

(s/def :funding-modal-vm.transfer-percent-action/label string?)
(s/def :funding-modal-vm.transfer-percent-action/action ::action)
(s/def :funding-modal-vm/transfer-percent-action
  (exact-map (s/keys :req-un [:funding-modal-vm.transfer-percent-action/label
                              :funding-modal-vm.transfer-percent-action/action])
             required-percent-action-keys))
(s/def :funding-modal-vm.transfer/percent-actions
  (s/coll-of :funding-modal-vm/transfer-percent-action :kind vector?))

(s/def :funding-modal-vm.transfer-fix/label string?)
(s/def :funding-modal-vm.transfer-fix/action ::action)
(s/def :funding-modal-vm.transfer-fix/status #{:idle :submitting :sent :failed})
(s/def :funding-modal-vm.transfer-fix/status-message ::nilable-string)
(s/def :funding-modal-vm.transfer-fix/disabled? boolean?)
(s/def :funding-modal-vm.transfer-fix/reason ::nilable-string)
(s/def :funding-modal-vm.transfer-blocked/code
  #{:read-only :subaccount :no-owner :meta-missing :hyperevm-unavailable :unmovable-token
    :bridge-empty :no-evm-gas :no-core-fee :no-chain-switch :no-provider
    :core-account-missing :in-flight :invalid-route})
(s/def :funding-modal-vm.transfer-blocked/checking? boolean?)
(s/def :funding-modal-vm.transfer-blocked/title ::nilable-string)
(s/def :funding-modal-vm.transfer-blocked/message string?)
(s/def :funding-modal-vm.transfer-blocked/explorer-url ::nilable-string)
(s/def :funding-modal-vm.transfer-blocked/fix
  (s/nilable (exact-map (s/keys :req-un [:funding-modal-vm.transfer-fix/label
                                         :funding-modal-vm.transfer-fix/action
                                         :funding-modal-vm.transfer-fix/status
                                         :funding-modal-vm.transfer-fix/status-message
                                         :funding-modal-vm.transfer-fix/disabled?
                                         :funding-modal-vm.transfer-fix/reason])
                        required-fix-keys)))
(s/def :funding-modal-vm.transfer/blocked
  (s/nilable (exact-map (s/keys :req-un [:funding-modal-vm.transfer-blocked/code
                                         :funding-modal-vm.transfer-blocked/checking?
                                         :funding-modal-vm.transfer-blocked/title
                                         :funding-modal-vm.transfer-blocked/message
                                         :funding-modal-vm.transfer-blocked/explorer-url
                                         :funding-modal-vm.transfer-blocked/fix])
                        required-blocked-keys)))

(s/def :funding-modal-vm.transfer-evm-step/id keyword?)
(s/def :funding-modal-vm.transfer-evm-step/label string?)
(s/def :funding-modal-vm.transfer-evm-step/status #{:pending :active :done :skipped :failed})
(s/def :funding-modal-vm.transfer-evm-step/detail ::nilable-string)
(s/def :funding-modal-vm/transfer-evm-step
  (exact-map (s/keys :req-un [:funding-modal-vm.transfer-evm-step/id
                              :funding-modal-vm.transfer-evm-step/label
                              :funding-modal-vm.transfer-evm-step/status
                              :funding-modal-vm.transfer-evm-step/detail])
             required-evm-step-keys))
(s/def :funding-modal-vm.transfer-evm/phase #{:running :pending :failed :succeeded})
(s/def :funding-modal-vm.transfer-evm/flow-id (s/nilable (s/or :text string? :number number?)))
(s/def :funding-modal-vm.transfer-evm/steps
  (s/coll-of :funding-modal-vm/transfer-evm-step :kind vector?))
(s/def :funding-modal-vm.transfer-evm/step-index nat-int?)
(s/def :funding-modal-vm.transfer-evm/step-count nat-int?)
(s/def :funding-modal-vm.transfer-evm/tx-url ::nilable-string)
(s/def :funding-modal-vm.transfer-evm/error ::nilable-string)
(s/def :funding-modal-vm.transfer-evm/arrival #{:idle :arriving :arrived :slow})
(s/def :funding-modal-vm.transfer-evm/result (s/nilable map?))
(s/def :funding-modal-vm.transfer-evm/maybe-sent? boolean?)
(s/def :funding-modal-vm.transfer-evm/retry-action (s/nilable ::action))
(s/def :funding-modal-vm.transfer-evm/add-to-wallet? boolean?)
(s/def :funding-modal-vm.transfer/evm
  (s/nilable (exact-map (s/keys :req-un [:funding-modal-vm.transfer-evm/phase
                                         :funding-modal-vm.transfer-evm/flow-id
                                         :funding-modal-vm.transfer-evm/steps
                                         :funding-modal-vm.transfer-evm/step-index
                                         :funding-modal-vm.transfer-evm/step-count
                                         :funding-modal-vm.transfer-evm/tx-url
                                         :funding-modal-vm.transfer-evm/error
                                         :funding-modal-vm.transfer-evm/arrival
                                         :funding-modal-vm.transfer-evm/result
                                         :funding-modal-vm.transfer-evm/maybe-sent?
                                         :funding-modal-vm.transfer-evm/retry-action
                                         :funding-modal-vm.transfer-evm/add-to-wallet?])
                        required-evm-keys)))

(s/def :funding-modal-vm.transfer/amount :funding-modal-vm/transfer-amount)
(s/def :funding-modal-vm.transfer/message ::nilable-string)
(s/def :funding-modal-vm/transfer
  (exact-map (s/keys :req-un [:funding-modal-vm.transfer/to-perp?
                              :funding-modal-vm.transfer/route
                              :funding-modal-vm.transfer/from-options
                              :funding-modal-vm.transfer/to-options
                              :funding-modal-vm.transfer/swap-action
                              :funding-modal-vm.transfer/asset
                              :funding-modal-vm.transfer/balances
                              :funding-modal-vm.transfer/destination
                              :funding-modal-vm.transfer/summary
                              :funding-modal-vm.transfer/usd-estimate
                              :funding-modal-vm.transfer/percent-actions
                              :funding-modal-vm.transfer/blocked
                              :funding-modal-vm.transfer/evm
                              :funding-modal-vm.transfer/amount
                              :funding-modal-vm.transfer/message
                              :funding-modal-vm.transfer/actions])
             required-transfer-keys))
