(ns hyperopen.views.vaults.vm
  (:require [hyperopen.vaults.application.list-vm :as list-vm]
            [hyperopen.vaults.infrastructure.routes :as vault-routes]))

(def build-startup-preview-record
  list-vm/build-startup-preview-record)

(def vault-list-vm
  list-vm/vault-list-vm)
