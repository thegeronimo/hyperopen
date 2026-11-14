(ns hyperopen.runtime.effect-adapters.vault-transfer-attribution-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.runtime.effect-adapters.vaults :as vault-adapters]
            [hyperopen.vaults.effects :as vault-effects]))

(defn- store-with-slot
  []
  (atom {:portfolio {:vault-attribution {:status :ready :stale? false}}}))

(deftest successful-vault-transfer-marks-vault-attribution-stale-test
  (async done
    (let [store (store-with-slot)]
      (with-redefs [vault-effects/api-submit-vault-transfer!
                    (fn [_] (js/Promise.resolve {:status "ok"}))]
        (-> (vault-adapters/api-submit-vault-transfer-effect nil store {:vault-address "0xv"})
            (.then (fn [resp]
                     (is (= {:status "ok"} resp))
                     (is (true? (get-in @store [:portfolio :vault-attribution :stale?])))
                     (done))))))))

(deftest failed-vault-transfer-leaves-vault-attribution-fresh-test
  (async done
    (let [store (store-with-slot)]
      (with-redefs [vault-effects/api-submit-vault-transfer!
                    (fn [_] (js/Promise.resolve {:status "err"}))]
        (-> (vault-adapters/api-submit-vault-transfer-effect nil store {:vault-address "0xv"})
            (.then (fn [_]
                     (is (false? (get-in @store [:portfolio :vault-attribution :stale?])))
                     (done))))))))

(deftest blocked-vault-transfer-result-passes-through-test
  (let [store (store-with-slot)]
    (with-redefs [vault-effects/api-submit-vault-transfer! (constantly :blocked)]
      (is (= :blocked (vault-adapters/api-submit-vault-transfer-effect nil store {})))
      (is (false? (get-in @store [:portfolio :vault-attribution :stale?]))))))
