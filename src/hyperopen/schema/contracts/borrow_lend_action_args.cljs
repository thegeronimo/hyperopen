(ns hyperopen.schema.contracts.borrow-lend-action-args
  (:require [cljs.spec.alpha :as s]
            [hyperopen.schema.contracts.common :as common]))

;; Earn (HyperCore borrow/lend) action payload contracts, merged into
;; hyperopen.schema.contracts.action-args/action-args-spec-by-id.

(s/def ::modal-args
  (s/tuple (s/or :keyword keyword? :string string?)
           ::common/intish))

(s/def ::fraction-args (s/tuple number?))

(def borrow-lend-action-args-spec-by-id
  {:actions/load-borrow-lend-route ::common/path-args
   :actions/load-borrow-lend ::common/no-args
   :actions/set-borrow-lend-tab ::common/keyword-or-string-args
   :actions/open-borrow-lend-modal ::modal-args
   :actions/close-borrow-lend-modal ::common/no-args
   :actions/set-borrow-lend-modal-kind ::common/keyword-or-string-args
   :actions/set-borrow-lend-amount ::common/single-input-args
   :actions/set-borrow-lend-amount-fraction ::fraction-args
   :actions/submit-borrow-lend ::common/no-args})
