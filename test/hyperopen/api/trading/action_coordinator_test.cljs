(ns hyperopen.api.trading.action-coordinator-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading.action-coordinator :as ac]
            [hyperopen.platform.locks :as locks]))

(deftest admission-is-exclusive-per-account-and-never-waits-test
  (async done
    (locks/reset-in-tab-state!)
    (let [release (atom nil)
          first-run (ac/with-account-admission!
                     {:account-address "0xAcc" :allow-in-tab-coordination? true}
                     (fn [] (js/Promise. (fn [resolve _] (reset! release resolve)))))]
      (-> (ac/with-account-admission! {:account-address "0xacc" :allow-in-tab-coordination? true} (fn [] :second))
          (.then (fn [r] (is (= {:status :busy} r) "same account, any case: busy, not queued")))
          (.then (fn [_] (ac/with-account-admission! {:account-address "0xother" :allow-in-tab-coordination? true} (fn [] :other))))
          (.then (fn [r] (is (= {:status :ok :value :other :coordination :in-tab-only} r) "other accounts are independent")))
          (.then (fn [_] (@release :reserved) first-run))
          (.then (fn [r] (is (= :reserved (:value r)))))
          (.then (fn [_] (ac/with-account-admission! {:account-address "0xacc" :allow-in-tab-coordination? true} (fn [] :again))))
          (.then (fn [r] (is (= :again (:value r)))))
          (.catch (fn [e] (is false (str e))))
          (.finally done)))))

(deftest a-timed-out-body-keeps-the-section-until-it-settles-test
  ;; Second review B1: releasing on timeout would let another tab allocate the
  ;; same funds while the late body still commits its reservations.
  (async done
    (locks/reset-in-tab-state!)
    (let [release (atom nil)
          opts {:account-address "0xh" :timeout-ms 10 :allow-in-tab-coordination? true}]
      (-> (ac/with-account-admission! opts (fn [] (js/Promise. (fn [resolve _] (reset! release resolve)))))
          (.then (fn [r] (is (= {:status :timeout} r) "the caller is told at once")))
          (.then (fn [_] (ac/with-account-admission! opts (fn [] :next))))
          (.then (fn [r] (is (= {:status :busy} r) "but the section stays held")))
          (.then (fn [_] (@release :late) (js/Promise. (fn [res _] (js/setTimeout res 5)))))
          (.then (fn [_] (ac/with-account-admission! opts (fn [] :after))))
          (.then (fn [r] (is (= :after (:value r)) "free once the body settled")))
          (.then (fn [_] (ac/with-account-admission! {:account-address nil} (fn [] :x))))
          (.then (fn [r] (is (= :error (:status r)))))
          (.then (fn [_] (ac/with-account-admission! {:account-address "0xz"} (fn [] :x))))
          (.then (fn [r] (is (= {:status :unsafe-coordination} r)
                             "in-tab-only coordination is refused unless explicitly allowed")))
          (.catch (fn [e] (is false (str e))))
          (.finally done)))))
