(ns hyperopen.account.lifecycle-invariants
  (:require [hyperopen.account.context :as account-context]))

(defn- empty-coll-like?
  [value]
  (or (nil? value)
      (and (coll? value)
           (empty? value))))

(defn- empty-map-like?
  [value]
  (or (nil? value)
      (and (map? value)
           (empty? value))))

(defn- false-or-nil?
  [value]
  (or (nil? value)
      (false? value)))

(defn- classic-or-nil?
  [value]
  (or (nil? value)
      (= :classic value)))

(defn- mismatch
  [path expected actual]
  {:path path
   :expected expected
   :actual actual})

(defn- check-path
  [issues state path predicate expected]
  (let [value (get-in state path)]
    (if (predicate value)
      issues
      (conj issues (mismatch path expected value)))))

(defn no-effective-account-surface-failures
  [state]
  (if (some? (account-context/effective-account-address state))
    []
    (-> []
        (check-path state [:webdata2] empty-map-like? "nil or empty map")
        (check-path state [:orders :open-orders-hydrated?] false-or-nil? "false or nil")
        (check-path state [:orders :open-orders] empty-coll-like? "empty or nil")
        (check-path state [:orders :open-orders-snapshot] empty-coll-like? "empty or nil")
        (check-path state [:orders :open-orders-snapshot-by-dex] empty-map-like? "empty map or nil")
        (check-path state [:orders :open-error] nil? "nil")
        (check-path state [:orders :open-error-category] nil? "nil")
        (check-path state [:orders :fills] empty-coll-like? "empty or nil")
        (check-path state [:orders :fills-error] nil? "nil")
        (check-path state [:orders :fills-error-category] nil? "nil")
        (check-path state [:orders :fundings-raw] empty-coll-like? "empty or nil")
        (check-path state [:orders :fundings] empty-coll-like? "empty or nil")
        (check-path state [:orders :order-history] empty-coll-like? "empty or nil")
        (check-path state [:orders :ledger] empty-coll-like? "empty or nil")
        (check-path state [:orders :twap-states] empty-coll-like? "empty or nil")
        (check-path state [:orders :twap-history] empty-coll-like? "empty or nil")
        (check-path state [:orders :twap-slice-fills] empty-coll-like? "empty or nil")
        (check-path state [:orders :pending-cancel-oids] empty-coll-like? "empty or nil")
        (check-path state [:account-info :funding-history :loading?] false-or-nil? "false or nil")
        (check-path state [:account-info :funding-history :error] nil? "nil")
        (check-path state [:account-info :order-history :loading?] false-or-nil? "false or nil")
        (check-path state [:account-info :order-history :error] nil? "nil")
        (check-path state [:account-info :order-history :loaded-at-ms] nil? "nil")
        (check-path state [:account-info :order-history :loaded-for-address] nil? "nil")
        (check-path state [:spot :clearinghouse-state] empty-map-like? "nil or empty map")
        (check-path state [:spot :loading-balances?] false-or-nil? "false or nil")
        (check-path state [:spot :error] nil? "nil")
        (check-path state [:spot :error-category] nil? "nil")
        (check-path state [:perp-dex-clearinghouse] empty-map-like? "empty map or nil")
        (check-path state [:perp-dex-clearinghouse-error] nil? "nil")
        (check-path state [:perp-dex-clearinghouse-error-category] nil? "nil")
        (check-path state [:portfolio :summary-by-key] empty-map-like? "empty map or nil")
        (check-path state [:portfolio :user-fees] empty-coll-like? "empty or nil")
        (check-path state [:portfolio :ledger-updates] empty-coll-like? "empty or nil")
        (check-path state [:portfolio :loading?] false-or-nil? "false or nil")
        (check-path state [:portfolio :user-fees-loading?] false-or-nil? "false or nil")
        (check-path state [:portfolio :error] nil? "nil")
        (check-path state [:portfolio :user-fees-error] nil? "nil")
        (check-path state [:portfolio :ledger-error] nil? "nil")
        (check-path state [:portfolio :loaded-at-ms] nil? "nil")
        (check-path state [:portfolio :user-fees-loaded-at-ms] nil? "nil")
        (check-path state [:portfolio :ledger-loaded-at-ms] nil? "nil")
        (check-path state [:account :mode] classic-or-nil? ":classic or nil")
        (check-path state [:account :abstraction-raw] nil? "nil"))))

(defn disconnected-account-surface-violations
  [state]
  (no-effective-account-surface-failures state))

(defn no-effective-account-surfaces-cleared?
  [state]
  (empty? (no-effective-account-surface-failures state)))

(defn disconnected-account-surface-valid?
  [state]
  (no-effective-account-surfaces-cleared? state))

(defn assert-account-lifecycle-invariants!
  ([state]
   (assert-account-lifecycle-invariants! state {}))
  ([state context]
   (when-let [failures (seq (disconnected-account-surface-violations state))]
     (throw
      (js/Error.
       (str "account lifecycle invariant failed. "
            "No effective account must not retain account-derived surfaces. "
            "context=" (pr-str context) " "
            "failures=" (pr-str failures)))))))
