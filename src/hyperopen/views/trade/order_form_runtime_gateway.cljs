(ns hyperopen.views.trade.order-form-runtime-gateway
  (:require [hyperopen.schema.order-form-command-catalog :as command-catalog]
            [hyperopen.schema.order-form-command-contracts :as contracts]
            [hyperopen.views.trade.order-form-placeholders :as placeholders]))

(defprotocol OrderFormRuntimeGateway
  (command->runtime-actions [gateway command]))

(defn supported-command-ids []
  (command-catalog/supported-command-ids))

(defn- resolve-command-arg [arg]
  (placeholders/resolve-placeholder-token arg))

(defrecord DefaultOrderFormRuntimeGateway []
  OrderFormRuntimeGateway
  (command->runtime-actions [_ {:keys [command-id args] :as command}]
    (let [supported-command-id-set (command-catalog/supported-command-ids)]
      (contracts/assert-order-form-command! command
                                            {:boundary :order-form/runtime-gateway}
                                            supported-command-id-set)
      (let [action-id (command-catalog/action-id-for-command command-id)]
        (when-not action-id
          (throw (js/Error.
                  (str "Unknown order-form command id: "
                       (pr-str command-id)
                       " command="
                       (pr-str command)))))
        (contracts/assert-runtime-actions!
         [(into [action-id] (map resolve-command-arg args))]
         {:boundary :order-form/runtime-gateway
          :command-id command-id})))))

(defn default-gateway []
  (->DefaultOrderFormRuntimeGateway))
