(ns hyperopen.schema.contracts.hyperevm-action-args
  (:require [cljs.spec.alpha :as s]))

;; HyperEVM action payload contracts. Merged into
;; hyperopen.schema.contracts.action-args/action-args-spec-by-id.

(s/def ::now-ms number?)
(s/def ::force? boolean?)
(s/def ::fast-poll-ms (s/and number? pos?))
(s/def ::refresh-hyperevm-balances-opts
  (s/keys :req-un [::now-ms]
          :opt-un [::force? ::fast-poll-ms]))
(s/def ::refresh-hyperevm-balances-args
  (s/tuple ::refresh-hyperevm-balances-opts))
(s/def ::token-index (s/and int? (complement neg?)))
(s/def ::refresh-hyperevm-bridge-capacity-args
  (s/tuple ::token-index))

(s/def ::check-hyperevm-in-flight-args empty?)

(s/def ::balances-location-filter #{:all :hypercore :hyperevm})
(s/def ::set-balances-location-filter-args
  (s/tuple ::balances-location-filter))

(def hyperevm-action-args-spec-by-id
  {:actions/refresh-hyperevm-balances ::refresh-hyperevm-balances-args
   :actions/refresh-hyperevm-bridge-capacity ::refresh-hyperevm-bridge-capacity-args
   :actions/check-hyperevm-in-flight ::check-hyperevm-in-flight-args
   :actions/set-balances-location-filter ::set-balances-location-filter-args})
