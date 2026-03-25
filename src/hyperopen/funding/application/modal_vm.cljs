(ns hyperopen.funding.application.modal-vm
  (:require [hyperopen.funding.application.modal-vm.amounts :as amounts]
            [hyperopen.funding.application.modal-vm.async :as async]
            [hyperopen.funding.application.modal-vm.context :as context]
            [hyperopen.funding.application.modal-vm.lifecycle :as lifecycle]
            [hyperopen.funding.application.modal-vm.models :as models]
            [hyperopen.funding.application.modal-vm.presentation :as presentation]))

(defn funding-modal-view-model
  [deps
   state]
  (-> (context/base-context deps state)
      (context/with-asset-context deps)
      (context/with-generated-address-context deps)
      (context/with-preview-context deps)
      (async/with-async-context deps)
      (lifecycle/with-lifecycle-context deps)
      (amounts/with-amount-context deps)
      presentation/with-presentation-context
      models/build-view-model))
