(ns hyperopen.api.trading.action-coordinator
  "Account admission coordination (HO-SPEC-014 v2.1 section 8.2).

  Placements that spend the same account's free balances run one at a time per
  `[network account]` across tabs, so two plans cannot allocate the same free
  USDC before either venue hold appears. The body reads outstanding
  reservations, revalidates and COMMITS its own reservations before the
  section ends. Cancellation never takes this section.

  The section never waits without bound: a holder elsewhere yields `:busy`."
  (:require [clojure.string :as str]
            [hyperopen.platform.locks :as locks]))

(defn admission-lock-name [network account-address]
  (str "hyperopen:account-admission:" (name (or network :hyperliquid-mainnet)) ":"
       (some-> account-address str str/trim str/lower-case)))

(def default-timeout-ms
  "How long the caller waits for an admission body before getting :timeout."
  20000)

(defn with-account-admission!
  "Run `body!` (-> value or Promise) inside the admission section for the
  account. Resolves to one of:

  - {:status :ok :value v :coordination kind}
  - {:status :busy} when the section is held elsewhere (never waits);
  - {:status :timeout} when the body has not settled within `timeout-ms`.
    The SECTION STAYS HELD until the body really settles, so no other tab can
    allocate the same funds meanwhile (spec 8.2). The caller must treat this
    as reconciliation required: never place from that admission, and reload
    the journal before any new admission;
  - {:status :unsafe-coordination} when only in-tab coordination is available
    and `:allow-in-tab-coordination?` is not set (another tab could hold the
    same funds); the body is not run;
  - {:status :error :error e}.

  Never rejects."
  [{:keys [network account-address timeout-ms allow-in-tab-coordination?]} body!]
  (if-not (and (string? account-address) (seq (str/trim account-address)))
    (js/Promise.resolve {:status :error :error (js/Error. "Account address required for admission.")})
    (if (and (= :in-tab-only (locks/coordination-kind)) (not allow-in-tab-coordination?))
      (js/Promise.resolve {:status :unsafe-coordination})
      (js/Promise.
       (fn [resolve _]
         (-> (locks/with-exclusive-lock-if-available!
              (admission-lock-name network account-address)
              (fn []
                (let [timer (js/setTimeout #(resolve {:status :timeout})
                                           (or timeout-ms default-timeout-ms))]
                  ;; The lock callback returns the BODY's promise, so the lock is
                  ;; released only when the body settles, even after :timeout.
                  (-> (js/Promise.resolve nil)
                      (.then (fn [_] (body!)))
                      (.then (fn [v] {:status :ok :value v :coordination (locks/coordination-kind)})
                             (fn [e] {:status :error :error e}))
                      (.finally (fn [] (js/clearTimeout timer)))))))
             ;; Resolves after the lock is released, so the next admission is free.
             ;; A caller already told :timeout keeps that answer (resolve is once).
             (.then (fn [{:keys [acquired? value]}]
                      (resolve (if acquired? value {:status :busy}))))
             (.catch (fn [e] (resolve {:status :error :error e})))))))))
