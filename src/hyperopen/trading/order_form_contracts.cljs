(ns hyperopen.trading.order-form-contracts
  (:require [hyperopen.schema.order-form-contracts :as schema-contracts]))

(defn order-form-vm-valid?
  [vm]
  (schema-contracts/order-form-vm-valid? vm))

(defn transition-valid?
  [transition]
  (schema-contracts/order-form-transition-valid? transition))

(defn assert-order-form-vm!
  [vm context]
  (schema-contracts/assert-order-form-vm! vm context))

(defn assert-transition!
  [transition context]
  (schema-contracts/assert-order-form-transition! transition context))
