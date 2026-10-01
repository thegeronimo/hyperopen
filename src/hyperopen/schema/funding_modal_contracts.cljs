(ns hyperopen.schema.funding-modal-contracts
  (:require [cljs.spec.alpha :as s]
            [hyperopen.schema.funding-modal-transfer-contracts]))

(def ^:private allowed-anchor-keys
  #{:left :right :top :bottom :width :height :viewport-width :viewport-height})

(def ^:private required-top-level-keys
  #{:open?
    :mode
    :legacy-kind
    :anchor
    :title
    :deposit-step
    :deposit-search-input
    :withdraw-step
    :withdraw-search-input
    :deposit-assets
    :deposit-selected-asset
    :deposit-flow-kind
    :deposit-flow-supported?
    :deposit-generated-address
    :deposit-generated-signatures
    :withdraw-assets
    :withdraw-all-assets
    :withdraw-selected-asset
    :withdraw-selected-asset-key
    :withdraw-flow-kind
    :withdraw-generated-address
    :amount-input
    :to-perp?
    :destination-input
    :hyperunit-lifecycle
    :hyperunit-lifecycle-terminal?
    :hyperunit-lifecycle-outcome
    :hyperunit-lifecycle-outcome-label
    :hyperunit-lifecycle-recovery-hint
    :hyperunit-lifecycle-destination-explorer-url
    :hyperunit-withdrawal-queue
    :max-display
    :max-input
    :max-symbol
    :submitting?
    :submit-disabled?
    :preview-ok?
    :status-message
    :deposit-submit-label
    :deposit-quick-amounts
    :deposit-min-usdc
    :deposit-min-amount
    :deposit-estimated-time
    :deposit-network-fee
    :withdraw-estimated-time
    :withdraw-network-fee
    :withdraw-queue-length
    :withdraw-queue-last-operation-tx-id
    :withdraw-queue-last-operation-explorer-url
    :hyperunit-fee-estimate-loading?
    :hyperunit-fee-estimate-error
    :hyperunit-withdrawal-queue-loading?
    :hyperunit-withdrawal-queue-error
    :submit-label
    :min-withdraw-usdc
    :min-withdraw-amount
    :min-withdraw-symbol
    :modal
    :content
    :feedback
    :deposit
    :send
    :transfer
    :withdraw
    :legacy})

(def ^:private required-modal-keys
  #{:open? :mode :title :anchor :opener-data-role})

(def ^:private required-content-keys
  #{:kind})

(def ^:private required-feedback-keys
  #{:message :visible? :tone})

(def ^:private required-search-keys
  #{:value :placeholder})

(def ^:private required-fee-estimate-keys
  #{:state :message})

(def ^:private required-deposit-flow-keys
  #{:kind
    :supported?
    :generated-address
    :generated-signature-count
    :unsupported-detail
    :fee-estimate})

(def ^:private required-deposit-amount-keys
  #{:value :quick-amounts :minimum-value :minimum-input})

(def ^:private required-summary-row-keys
  #{:label :value})

(def ^:private required-summary-keys
  #{:rows})

(def ^:private required-actions-keys
  #{:submit-label :submit-disabled? :submitting?})

(def ^:private required-deposit-keys
  #{:step
    :search
    :assets
    :selected-asset
    :flow
    :amount
    :summary
    :lifecycle
    :actions})

(def ^:private required-send-asset-keys
  #{:token :symbol :prefix-label})

(def ^:private required-destination-keys
  #{:value})

(def ^:private required-send-amount-keys
  #{:value :max-display :symbol})

(def ^:private required-send-keys
  #{:asset :destination :amount :actions})

(def ^:private required-last-operation-keys
  #{:tx-id :explorer-url})

(def ^:private required-withdrawal-queue-keys
  #{:state :length :last-operation :message})

(def ^:private required-withdraw-flow-keys
  #{:kind :protocol-address :fee-estimate :withdrawal-queue})

(def ^:private required-withdraw-amount-keys
  #{:value :max-display :max-input :available-label :symbol})

(def ^:private required-withdraw-keys
  #{:step
    :search
    :assets
    :selected-asset
    :destination
    :amount
    :flow
    :summary
    :lifecycle
    :actions})

(def ^:private required-legacy-keys
  #{:kind :message})

(def ^:private required-deposit-asset-keys
  #{:key :symbol :name :network :flow-kind})

(def ^:private allowed-deposit-asset-keys
  #{:key
    :symbol
    :name
    :network
    :flow-kind
    :minimum
    :maximum
    :route-key
    :hyperunit-source-chain
    :chain-id
    :implemented?})

(def ^:private required-withdraw-asset-keys
  #{:key
    :symbol
    :name
    :network
    :flow-kind
    :available-amount
    :available-display
    :available-detail-display})

(def ^:private allowed-withdraw-asset-keys
  #{:key
    :symbol
    :name
    :network
    :flow-kind
    :minimum
    :maximum
    :route-key
    :hyperunit-source-chain
    :chain-id
    :min
    :max
    :available-amount
    :available-display
    :available-detail-display})

(def ^:private required-raw-hyperunit-lifecycle-keys
  #{:direction
    :asset-key
    :operation-id
    :state
    :status
    :source-tx-confirmations
    :destination-tx-confirmations
    :position-in-withdraw-queue
    :destination-tx-hash
    :state-next-at
    :last-updated-ms
    :error})

(def ^:private required-raw-withdraw-queue-keys
  #{:status :by-chain :requested-at-ms :updated-at-ms :error})

(def ^:private required-raw-withdraw-queue-entry-keys
  #{:chain :last-withdraw-queue-operation-tx-id :withdrawal-queue-length})

(def ^:private required-lifecycle-outcome-keys
  #{:label :tone})

(def ^:private required-lifecycle-destination-tx-keys
  #{:hash :explorer-url})

(def ^:private required-lifecycle-panel-keys
  #{:direction
    :stage-label
    :status-label
    :outcome
    :source-confirmations
    :destination-confirmations
    :queue-position
    :destination-tx
    :next-check-label
    :error
    :recovery-hint})

(defn- exact-keys?
  [value expected-keys]
  (= expected-keys (set (keys value))))

(defn- required-keys-present?
  [value required-keys]
  (every? #(contains? value %) required-keys))

(defn- allowed-keys?
  [value allowed-keys]
  (every? allowed-keys (keys value)))

(defn- nilable-string?
  [value]
  (or (nil? value) (string? value)))

(defn- nilable-number?
  [value]
  (or (nil? value) (number? value)))

(defn- nilable-nat-int?
  [value]
  (or (nil? value) (nat-int? value)))

(defn- nilable-keyword?
  [value]
  (or (nil? value) (keyword? value)))

(defn- nilable-boolean?
  [value]
  (or (nil? value) (boolean? value)))

(defn- allowed-anchor-shape?
  [anchor]
  (and (map? anchor)
       (every? allowed-anchor-keys (keys anchor))
       (every? number? (vals anchor))))

(defn- deposit-asset-variant-valid?
  [asset]
  (case (:flow-kind asset)
    :hyperunit-address (string? (:hyperunit-source-chain asset))
    :route (string? (:route-key asset))
    true))

(defn- deposit-asset-shape?
  [asset]
  (and (map? asset)
       (required-keys-present? asset required-deposit-asset-keys)
       (allowed-keys? asset allowed-deposit-asset-keys)
       (keyword? (:key asset))
       (string? (:symbol asset))
       (string? (:name asset))
       (string? (:network asset))
       (keyword? (:flow-kind asset))
       (nilable-number? (:minimum asset))
       (nilable-number? (:maximum asset))
       (nilable-string? (:route-key asset))
       (nilable-string? (:hyperunit-source-chain asset))
       (nilable-string? (:chain-id asset))
       (nilable-boolean? (:implemented? asset))
       (deposit-asset-variant-valid? asset)))

(defn- withdraw-asset-shape?
  [asset]
  (and (map? asset)
       (required-keys-present? asset required-withdraw-asset-keys)
       (allowed-keys? asset allowed-withdraw-asset-keys)
       (keyword? (:key asset))
       (string? (:symbol asset))
       (string? (:name asset))
       (string? (:network asset))
       (keyword? (:flow-kind asset))
       (number? (:available-amount asset))
       (string? (:available-display asset))
       (string? (:available-detail-display asset))
       (nilable-number? (:minimum asset))
       (nilable-number? (:maximum asset))
       (nilable-number? (:min asset))
       (nilable-number? (:max asset))
       (nilable-string? (:route-key asset))
       (nilable-string? (:hyperunit-source-chain asset))
       (nilable-string? (:chain-id asset))
       (deposit-asset-variant-valid? asset)))

(defn- raw-hyperunit-lifecycle-shape?
  [lifecycle]
  (and (map? lifecycle)
       (exact-keys? lifecycle required-raw-hyperunit-lifecycle-keys)
       (nilable-keyword? (:direction lifecycle))
       (nilable-keyword? (:asset-key lifecycle))
       (nilable-string? (:operation-id lifecycle))
       (nilable-keyword? (:state lifecycle))
       (nilable-keyword? (:status lifecycle))
       (nilable-nat-int? (:source-tx-confirmations lifecycle))
       (nilable-nat-int? (:destination-tx-confirmations lifecycle))
       (nilable-nat-int? (:position-in-withdraw-queue lifecycle))
       (nilable-string? (:destination-tx-hash lifecycle))
       (nilable-nat-int? (:state-next-at lifecycle))
       (nilable-nat-int? (:last-updated-ms lifecycle))
       (nilable-string? (:error lifecycle))))

(defn- raw-withdraw-queue-entry-shape?
  [entry]
  (and (map? entry)
       (exact-keys? entry required-raw-withdraw-queue-entry-keys)
       (string? (:chain entry))
       (nilable-string? (:last-withdraw-queue-operation-tx-id entry))
       (nat-int? (:withdrawal-queue-length entry))))

(defn- raw-withdraw-queue-shape?
  [queue]
  (and (map? queue)
       (exact-keys? queue required-raw-withdraw-queue-keys)
       (keyword? (:status queue))
       (map? (:by-chain queue))
       (every? string? (keys (:by-chain queue)))
       (every? raw-withdraw-queue-entry-shape? (vals (:by-chain queue)))
       (nilable-nat-int? (:requested-at-ms queue))
       (nilable-nat-int? (:updated-at-ms queue))
       (nilable-string? (:error queue))))

(defn- lifecycle-outcome-shape?
  [outcome]
  (and (map? outcome)
       (exact-keys? outcome required-lifecycle-outcome-keys)
       (string? (:label outcome))
       (keyword? (:tone outcome))))

(defn- lifecycle-destination-tx-shape?
  [destination-tx]
  (and (map? destination-tx)
       (exact-keys? destination-tx required-lifecycle-destination-tx-keys)
       (string? (:hash destination-tx))
       (nilable-string? (:explorer-url destination-tx))))

(defn- lifecycle-panel-shape?
  [panel]
  (and (map? panel)
       (exact-keys? panel required-lifecycle-panel-keys)
       (keyword? (:direction panel))
       (string? (:stage-label panel))
       (string? (:status-label panel))
       (or (nil? (:outcome panel))
           (lifecycle-outcome-shape? (:outcome panel)))
       (nilable-nat-int? (:source-confirmations panel))
       (nilable-nat-int? (:destination-confirmations panel))
       (nilable-nat-int? (:queue-position panel))
       (or (nil? (:destination-tx panel))
           (lifecycle-destination-tx-shape? (:destination-tx panel)))
       (nilable-string? (:next-check-label panel))
       (nilable-string? (:error panel))
       (nilable-string? (:recovery-hint panel))))

(s/def :funding-modal-vm/open? boolean?)
(s/def :funding-modal-vm/mode (s/nilable keyword?))
(s/def :funding-modal-vm/legacy-kind keyword?)
(s/def :funding-modal-vm/title string?)
(s/def :funding-modal-vm/anchor
  (s/nilable allowed-anchor-shape?))

(s/def :funding-modal-vm/deposit-step keyword?)
(s/def :funding-modal-vm/deposit-search-input string?)
(s/def :funding-modal-vm/withdraw-step keyword?)
(s/def :funding-modal-vm/withdraw-search-input string?)
(s/def :funding-modal-vm/deposit-asset deposit-asset-shape?)
(s/def :funding-modal-vm/withdraw-asset withdraw-asset-shape?)
(s/def :funding-modal-vm/deposit-assets (s/coll-of :funding-modal-vm/deposit-asset :kind vector?))
(s/def :funding-modal-vm/deposit-selected-asset (s/nilable :funding-modal-vm/deposit-asset))
(s/def :funding-modal-vm/deposit-flow-kind keyword?)
(s/def :funding-modal-vm/deposit-flow-supported? boolean?)
(s/def :funding-modal-vm/deposit-generated-address (s/nilable string?))
(s/def :funding-modal-vm/deposit-generated-signatures any?)
(s/def :funding-modal-vm/withdraw-assets (s/coll-of :funding-modal-vm/withdraw-asset :kind vector?))
(s/def :funding-modal-vm/withdraw-all-assets (s/coll-of :funding-modal-vm/withdraw-asset :kind vector?))
(s/def :funding-modal-vm/withdraw-selected-asset (s/nilable :funding-modal-vm/withdraw-asset))
(s/def :funding-modal-vm/withdraw-selected-asset-key (s/nilable keyword?))
(s/def :funding-modal-vm/withdraw-flow-kind keyword?)
(s/def :funding-modal-vm/withdraw-generated-address (s/nilable string?))
(s/def :funding-modal-vm/amount-input string?)
(s/def :funding-modal-vm/to-perp? boolean?)
(s/def :funding-modal-vm/destination-input string?)
(s/def :funding-modal-vm/hyperunit-lifecycle raw-hyperunit-lifecycle-shape?)
(s/def :funding-modal-vm/hyperunit-lifecycle-terminal? boolean?)
(s/def :funding-modal-vm/hyperunit-lifecycle-outcome (s/nilable keyword?))
(s/def :funding-modal-vm/hyperunit-lifecycle-outcome-label (s/nilable string?))
(s/def :funding-modal-vm/hyperunit-lifecycle-recovery-hint (s/nilable string?))
(s/def :funding-modal-vm/hyperunit-lifecycle-destination-explorer-url (s/nilable string?))
(s/def :funding-modal-vm/hyperunit-withdrawal-queue raw-withdraw-queue-shape?)
(s/def :funding-modal-vm/max-display any?)
(s/def :funding-modal-vm/max-input any?)
(s/def :funding-modal-vm/max-symbol any?)
(s/def :funding-modal-vm/submitting? boolean?)
(s/def :funding-modal-vm/submit-disabled? boolean?)
(s/def :funding-modal-vm/preview-ok? boolean?)
(s/def :funding-modal-vm/status-message (s/nilable string?))
(s/def :funding-modal-vm/deposit-submit-label string?)
(s/def :funding-modal-vm/deposit-quick-amounts (s/coll-of number? :kind vector?))
(s/def :funding-modal-vm/deposit-min-usdc any?)
(s/def :funding-modal-vm/deposit-min-amount any?)
(s/def :funding-modal-vm/deposit-estimated-time string?)
(s/def :funding-modal-vm/deposit-network-fee string?)
(s/def :funding-modal-vm/withdraw-estimated-time string?)
(s/def :funding-modal-vm/withdraw-network-fee string?)
(s/def :funding-modal-vm/withdraw-queue-length any?)
(s/def :funding-modal-vm/withdraw-queue-last-operation-tx-id (s/nilable string?))
(s/def :funding-modal-vm/withdraw-queue-last-operation-explorer-url (s/nilable string?))
(s/def :funding-modal-vm/hyperunit-fee-estimate-loading? boolean?)
(s/def :funding-modal-vm/hyperunit-fee-estimate-error (s/nilable string?))
(s/def :funding-modal-vm/hyperunit-withdrawal-queue-loading? boolean?)
(s/def :funding-modal-vm/hyperunit-withdrawal-queue-error (s/nilable string?))
(s/def :funding-modal-vm/submit-label string?)
(s/def :funding-modal-vm/min-withdraw-usdc any?)
(s/def :funding-modal-vm/min-withdraw-amount any?)
(s/def :funding-modal-vm/min-withdraw-symbol string?)

(s/def :funding-modal-vm.modal/open? boolean?)
(s/def :funding-modal-vm.modal/mode (s/nilable keyword?))
(s/def :funding-modal-vm.modal/title string?)
(s/def :funding-modal-vm.modal/anchor :funding-modal-vm/anchor)
(s/def :funding-modal-vm.modal/opener-data-role (s/nilable string?))
(s/def :funding-modal-vm/modal
  (s/and (s/keys :req-un [:funding-modal-vm.modal/open?
                          :funding-modal-vm.modal/mode
                          :funding-modal-vm.modal/title
                          :funding-modal-vm.modal/anchor :funding-modal-vm.modal/opener-data-role])
         #(exact-keys? % required-modal-keys)))

(s/def :funding-modal-vm.content/kind keyword?)
(s/def :funding-modal-vm/content
  (s/and
   (s/keys :req-un [:funding-modal-vm.content/kind])
   #(exact-keys? % required-content-keys)))

(s/def :funding-modal-vm.feedback/message (s/nilable string?))
(s/def :funding-modal-vm.feedback/visible? boolean?)
(s/def :funding-modal-vm.feedback/tone keyword?)
(s/def :funding-modal-vm/feedback
  (s/and
   (s/keys :req-un [:funding-modal-vm.feedback/message
                    :funding-modal-vm.feedback/visible?
                    :funding-modal-vm.feedback/tone])
   #(exact-keys? % required-feedback-keys)))

(s/def :funding-modal-vm.search/value string?)
(s/def :funding-modal-vm.search/placeholder string?)
(s/def :funding-modal-vm/search
  (s/and
   (s/keys :req-un [:funding-modal-vm.search/value
                    :funding-modal-vm.search/placeholder])
   #(exact-keys? % required-search-keys)))

(s/def :funding-modal-vm.fee-estimate/state keyword?)
(s/def :funding-modal-vm.fee-estimate/message (s/nilable string?))
(s/def :funding-modal-vm/fee-estimate
  (s/and
   (s/keys :req-un [:funding-modal-vm.fee-estimate/state
                    :funding-modal-vm.fee-estimate/message])
   #(exact-keys? % required-fee-estimate-keys)))

(s/def :funding-modal-vm.deposit-flow/kind keyword?)
(s/def :funding-modal-vm.deposit-flow/supported? boolean?)
(s/def :funding-modal-vm.deposit-flow/generated-address (s/nilable string?))
(s/def :funding-modal-vm.deposit-flow/generated-signature-count nat-int?)
(s/def :funding-modal-vm.deposit-flow/unsupported-detail string?)
(s/def :funding-modal-vm.deposit-flow/fee-estimate :funding-modal-vm/fee-estimate)
(s/def :funding-modal-vm/deposit-flow
  (s/and
   (s/keys :req-un [:funding-modal-vm.deposit-flow/kind
                    :funding-modal-vm.deposit-flow/supported?
                    :funding-modal-vm.deposit-flow/generated-address
                    :funding-modal-vm.deposit-flow/generated-signature-count
                    :funding-modal-vm.deposit-flow/unsupported-detail
                    :funding-modal-vm.deposit-flow/fee-estimate])
   #(exact-keys? % required-deposit-flow-keys)))

(s/def :funding-modal-vm.deposit-amount/value string?)
(s/def :funding-modal-vm.deposit-amount/quick-amounts (s/coll-of number? :kind vector?))
(s/def :funding-modal-vm.deposit-amount/minimum-value any?)
(s/def :funding-modal-vm.deposit-amount/minimum-input any?)
(s/def :funding-modal-vm/deposit-amount
  (s/and
   (s/keys :req-un [:funding-modal-vm.deposit-amount/value
                    :funding-modal-vm.deposit-amount/quick-amounts
                    :funding-modal-vm.deposit-amount/minimum-value
                    :funding-modal-vm.deposit-amount/minimum-input])
   #(exact-keys? % required-deposit-amount-keys)))

(s/def :funding-modal-vm.summary-row/label string?)
(s/def :funding-modal-vm.summary-row/value any?)
(s/def :funding-modal-vm/summary-row
  (s/and
   (s/keys :req-un [:funding-modal-vm.summary-row/label
                    :funding-modal-vm.summary-row/value])
   #(exact-keys? % required-summary-row-keys)))

(s/def :funding-modal-vm.summary/rows (s/coll-of :funding-modal-vm/summary-row :kind vector?))
(s/def :funding-modal-vm/summary
  (s/and
   (s/keys :req-un [:funding-modal-vm.summary/rows])
   #(exact-keys? % required-summary-keys)))

(s/def :funding-modal-vm.actions/submit-label string?)
(s/def :funding-modal-vm.actions/submit-disabled? boolean?)
(s/def :funding-modal-vm.actions/submitting? boolean?)
(s/def :funding-modal-vm/actions
  (s/and
   (s/keys :req-un [:funding-modal-vm.actions/submit-label
                    :funding-modal-vm.actions/submit-disabled?
                    :funding-modal-vm.actions/submitting?])
   #(exact-keys? % required-actions-keys)))

(s/def :funding-modal-vm.deposit/step keyword?)
(s/def :funding-modal-vm.deposit/search :funding-modal-vm/search)
(s/def :funding-modal-vm.deposit/assets (s/coll-of :funding-modal-vm/deposit-asset :kind vector?))
(s/def :funding-modal-vm.deposit/selected-asset (s/nilable :funding-modal-vm/deposit-asset))
(s/def :funding-modal-vm.deposit/flow :funding-modal-vm/deposit-flow)
(s/def :funding-modal-vm.deposit/amount :funding-modal-vm/deposit-amount)
(s/def :funding-modal-vm.deposit/summary :funding-modal-vm/summary)
(s/def :funding-modal-vm.deposit/lifecycle (s/nilable lifecycle-panel-shape?))
(s/def :funding-modal-vm.deposit/actions :funding-modal-vm/actions)
(s/def :funding-modal-vm/deposit
  (s/and
   (s/keys :req-un [:funding-modal-vm.deposit/step
                    :funding-modal-vm.deposit/search
                    :funding-modal-vm.deposit/assets
                    :funding-modal-vm.deposit/selected-asset
                    :funding-modal-vm.deposit/flow
                    :funding-modal-vm.deposit/amount
                    :funding-modal-vm.deposit/summary
                    :funding-modal-vm.deposit/lifecycle
                    :funding-modal-vm.deposit/actions])
   #(exact-keys? % required-deposit-keys)))

(s/def :funding-modal-vm.send-asset/token (s/nilable string?))
(s/def :funding-modal-vm.send-asset/symbol (s/nilable string?))
(s/def :funding-modal-vm.send-asset/prefix-label (s/nilable string?))
(s/def :funding-modal-vm/send-asset
  (s/and
   (s/keys :req-un [:funding-modal-vm.send-asset/token
                    :funding-modal-vm.send-asset/symbol
                    :funding-modal-vm.send-asset/prefix-label])
   #(exact-keys? % required-send-asset-keys)))

(s/def :funding-modal-vm.destination/value string?)
(s/def :funding-modal-vm/destination
  (s/and
   (s/keys :req-un [:funding-modal-vm.destination/value])
   #(exact-keys? % required-destination-keys)))

(s/def :funding-modal-vm.send-amount/value string?)
(s/def :funding-modal-vm.send-amount/max-display string?)
(s/def :funding-modal-vm.send-amount/symbol (s/nilable string?))
(s/def :funding-modal-vm/send-amount
  (s/and
   (s/keys :req-un [:funding-modal-vm.send-amount/value
                    :funding-modal-vm.send-amount/max-display
                    :funding-modal-vm.send-amount/symbol])
   #(exact-keys? % required-send-amount-keys)))

(s/def :funding-modal-vm.send/asset :funding-modal-vm/send-asset)
(s/def :funding-modal-vm.send/destination :funding-modal-vm/destination)
(s/def :funding-modal-vm.send/amount :funding-modal-vm/send-amount)
(s/def :funding-modal-vm.send/actions :funding-modal-vm/actions)
(s/def :funding-modal-vm/send
  (s/and
   (s/keys :req-un [:funding-modal-vm.send/asset
                    :funding-modal-vm.send/destination
                    :funding-modal-vm.send/amount
                    :funding-modal-vm.send/actions])
   #(exact-keys? % required-send-keys)))

;; The rest of the transfer submap lives in funding-modal-transfer-contracts; this
;; alias must register after :funding-modal-vm/actions (cljs.spec resolves it eagerly).
(s/def :funding-modal-vm.transfer/actions :funding-modal-vm/actions)

(s/def :funding-modal-vm.last-operation/tx-id (s/nilable string?))
(s/def :funding-modal-vm.last-operation/explorer-url (s/nilable string?))
(s/def :funding-modal-vm/last-operation
  (s/and
   (s/keys :req-un [:funding-modal-vm.last-operation/tx-id
                    :funding-modal-vm.last-operation/explorer-url])
   #(exact-keys? % required-last-operation-keys)))

(s/def :funding-modal-vm.withdrawal-queue/state keyword?)
(s/def :funding-modal-vm.withdrawal-queue/length any?)
(s/def :funding-modal-vm.withdrawal-queue/last-operation :funding-modal-vm/last-operation)
(s/def :funding-modal-vm.withdrawal-queue/message (s/nilable string?))
(s/def :funding-modal-vm/withdrawal-queue
  (s/and
   (s/keys :req-un [:funding-modal-vm.withdrawal-queue/state
                    :funding-modal-vm.withdrawal-queue/length
                    :funding-modal-vm.withdrawal-queue/last-operation
                    :funding-modal-vm.withdrawal-queue/message])
   #(exact-keys? % required-withdrawal-queue-keys)))

(s/def :funding-modal-vm.withdraw-flow/kind keyword?)
(s/def :funding-modal-vm.withdraw-flow/protocol-address (s/nilable string?))
(s/def :funding-modal-vm.withdraw-flow/fee-estimate :funding-modal-vm/fee-estimate)
(s/def :funding-modal-vm.withdraw-flow/withdrawal-queue :funding-modal-vm/withdrawal-queue)
(s/def :funding-modal-vm/withdraw-flow
  (s/and
   (s/keys :req-un [:funding-modal-vm.withdraw-flow/kind
                    :funding-modal-vm.withdraw-flow/protocol-address
                    :funding-modal-vm.withdraw-flow/fee-estimate
                    :funding-modal-vm.withdraw-flow/withdrawal-queue])
   #(exact-keys? % required-withdraw-flow-keys)))

(s/def :funding-modal-vm.withdraw-amount/value string?)
(s/def :funding-modal-vm.withdraw-amount/max-display string?)
(s/def :funding-modal-vm.withdraw-amount/max-input string?)
(s/def :funding-modal-vm.withdraw-amount/available-label string?)
(s/def :funding-modal-vm.withdraw-amount/symbol string?)
(s/def :funding-modal-vm/withdraw-amount
  (s/and
   (s/keys :req-un [:funding-modal-vm.withdraw-amount/value
                    :funding-modal-vm.withdraw-amount/max-display
                    :funding-modal-vm.withdraw-amount/max-input
                    :funding-modal-vm.withdraw-amount/available-label
                    :funding-modal-vm.withdraw-amount/symbol])
   #(exact-keys? % required-withdraw-amount-keys)))

(s/def :funding-modal-vm.withdraw/step keyword?)
(s/def :funding-modal-vm.withdraw/search :funding-modal-vm/search)
(s/def :funding-modal-vm.withdraw/assets (s/coll-of :funding-modal-vm/withdraw-asset :kind vector?))
(s/def :funding-modal-vm.withdraw/selected-asset (s/nilable :funding-modal-vm/withdraw-asset))
(s/def :funding-modal-vm.withdraw/destination :funding-modal-vm/destination)
(s/def :funding-modal-vm.withdraw/amount :funding-modal-vm/withdraw-amount)
(s/def :funding-modal-vm.withdraw/flow :funding-modal-vm/withdraw-flow)
(s/def :funding-modal-vm.withdraw/summary :funding-modal-vm/summary)
(s/def :funding-modal-vm.withdraw/lifecycle (s/nilable lifecycle-panel-shape?))
(s/def :funding-modal-vm.withdraw/actions :funding-modal-vm/actions)
(s/def :funding-modal-vm/withdraw
  (s/and
   (s/keys :req-un [:funding-modal-vm.withdraw/step
                    :funding-modal-vm.withdraw/search
                    :funding-modal-vm.withdraw/assets
                    :funding-modal-vm.withdraw/selected-asset
                    :funding-modal-vm.withdraw/destination
                    :funding-modal-vm.withdraw/amount
                    :funding-modal-vm.withdraw/flow
                    :funding-modal-vm.withdraw/summary
                    :funding-modal-vm.withdraw/lifecycle
                    :funding-modal-vm.withdraw/actions])
   #(exact-keys? % required-withdraw-keys)))

(s/def :funding-modal-vm.legacy/kind keyword?)
(s/def :funding-modal-vm.legacy/message string?)
(s/def :funding-modal-vm/legacy
  (s/and
   (s/keys :req-un [:funding-modal-vm.legacy/kind
                    :funding-modal-vm.legacy/message])
   #(exact-keys? % required-legacy-keys)))

(s/def ::funding-modal-vm
  (s/and
   (s/keys :req-un [:funding-modal-vm/open?
                    :funding-modal-vm/mode
                    :funding-modal-vm/legacy-kind
                    :funding-modal-vm/anchor
                    :funding-modal-vm/title
                    :funding-modal-vm/deposit-step
                    :funding-modal-vm/deposit-search-input
                    :funding-modal-vm/withdraw-step
                    :funding-modal-vm/withdraw-search-input
                    :funding-modal-vm/deposit-assets
                    :funding-modal-vm/deposit-selected-asset
                    :funding-modal-vm/deposit-flow-kind
                    :funding-modal-vm/deposit-flow-supported?
                    :funding-modal-vm/deposit-generated-address
                    :funding-modal-vm/deposit-generated-signatures
                    :funding-modal-vm/withdraw-assets
                    :funding-modal-vm/withdraw-all-assets
                    :funding-modal-vm/withdraw-selected-asset
                    :funding-modal-vm/withdraw-selected-asset-key
                    :funding-modal-vm/withdraw-flow-kind
                    :funding-modal-vm/withdraw-generated-address
                    :funding-modal-vm/amount-input
                    :funding-modal-vm/to-perp?
                    :funding-modal-vm/destination-input
                    :funding-modal-vm/hyperunit-lifecycle
                    :funding-modal-vm/hyperunit-lifecycle-terminal?
                    :funding-modal-vm/hyperunit-lifecycle-outcome
                    :funding-modal-vm/hyperunit-lifecycle-outcome-label
                    :funding-modal-vm/hyperunit-lifecycle-recovery-hint
                    :funding-modal-vm/hyperunit-lifecycle-destination-explorer-url
                    :funding-modal-vm/hyperunit-withdrawal-queue
                    :funding-modal-vm/max-display
                    :funding-modal-vm/max-input
                    :funding-modal-vm/max-symbol
                    :funding-modal-vm/submitting?
                    :funding-modal-vm/submit-disabled?
                    :funding-modal-vm/preview-ok?
                    :funding-modal-vm/status-message
                    :funding-modal-vm/deposit-submit-label
                    :funding-modal-vm/deposit-quick-amounts
                    :funding-modal-vm/deposit-min-usdc
                    :funding-modal-vm/deposit-min-amount
                    :funding-modal-vm/deposit-estimated-time
                    :funding-modal-vm/deposit-network-fee
                    :funding-modal-vm/withdraw-estimated-time
                    :funding-modal-vm/withdraw-network-fee
                    :funding-modal-vm/withdraw-queue-length
                    :funding-modal-vm/withdraw-queue-last-operation-tx-id
                    :funding-modal-vm/withdraw-queue-last-operation-explorer-url
                    :funding-modal-vm/hyperunit-fee-estimate-loading?
                    :funding-modal-vm/hyperunit-fee-estimate-error
                    :funding-modal-vm/hyperunit-withdrawal-queue-loading?
                    :funding-modal-vm/hyperunit-withdrawal-queue-error
                    :funding-modal-vm/submit-label
                    :funding-modal-vm/min-withdraw-usdc
                    :funding-modal-vm/min-withdraw-amount
                    :funding-modal-vm/min-withdraw-symbol
                    :funding-modal-vm/modal
                    :funding-modal-vm/content
                    :funding-modal-vm/feedback
                    :funding-modal-vm/deposit
                    :funding-modal-vm/send
                    :funding-modal-vm/transfer
                    :funding-modal-vm/withdraw
                    :funding-modal-vm/legacy])
   #(exact-keys? % required-top-level-keys)))

(defn funding-modal-vm-valid?
  [view-model]
  (s/valid? ::funding-modal-vm view-model))

(defn assert-funding-modal-vm!
  [view-model context]
  (when-not (funding-modal-vm-valid? view-model)
    (throw (js/Error.
            (str "funding modal VM schema validation failed. "
                 "context=" (pr-str context)
                 " problems=" (pr-str (s/explain-data ::funding-modal-vm view-model))))))
  view-model)
