(ns hyperopen.schema.contracts.vault-screener-action-args
  (:require [cljs.spec.alpha :as s]
            [hyperopen.schema.contracts.common :as common]))

;; Vault screener action payload contracts. Merged into
;; hyperopen.schema.contracts.action-args/action-args-spec-by-id.

(s/def ::control-and-value-args
  (s/tuple ::common/keyword-or-string any?))

(def vault-screener-action-args-spec-by-id
  {:actions/set-vaults-view ::common/keyword-or-string-args
   :actions/set-vault-screener-control ::control-and-value-args
   :actions/add-vault-screener-criterion ::common/keyword-or-string-args
   :actions/remove-vault-screener-criterion ::common/keyword-or-string-args
   :actions/set-vault-screener-criterion-value ::control-and-value-args
   :actions/apply-vault-screener-preset ::common/keyword-or-string-args
   :actions/run-vault-screener ::common/no-args
   :actions/control-vault-screener-run ::common/keyword-or-string-args
   :actions/open-vault-save-view-dialog ::common/no-args
   :actions/set-vault-save-view-field ::control-and-value-args
   :actions/close-vault-save-view-dialog ::common/no-args
   :actions/save-vault-screener-view ::common/no-args
   :actions/select-vault-saved-view ::common/optional-string-args
   :actions/vault-saved-view-command ::common/keyword-or-string-args})
