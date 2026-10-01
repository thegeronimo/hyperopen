(ns hyperopen.funding.application.submit-effects-transfer-guard-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.application.submit-effects :as effects]
            [hyperopen.funding.test-support.effects :as effects-support]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]))

(defn- run-transfer!
  [store request]
  (let [signed (atom [])
        toasts (atom [])
        signer (fn [kind]
                 (fn [_store _address action]
                   (swap! signed conj [kind action])
                   (js/Promise.resolve {:status "ok"})))]
    (effects/api-submit-funding-transfer!
     (merge (effects-support/base-submit-effect-deps)
            {:store store
             :request request
             :submit-send-asset! (signer :send-asset)
             :submit-usd-class-transfer! (signer :usd-class)
             :show-toast! (effects-support/capture-toast! toasts)}))
    {:signed @signed :toasts @toasts}))

(defn- store
  []
  (atom (assoc (support/state) :funding-ui {:modal (effects-support/seed-modal :transfer)})))

(deftest a-send-to-the-wrong-system-address-is-never-signed-test
  (let [store* (store)
        {:keys [signed toasts]}
        (run-transfer! store* {:action {:type "sendAsset"
                                        :destination "0x2222222222222222222222222222222222222222"
                                        :sourceDex "spot"
                                        :destinationDex "spot"
                                        :token "PURR:0xc1fb593aeffbeb02f85e0308e9956a90"
                                        :amount "1"
                                        :fromSubAccount ""}})]
    (is (= [] signed))
    (is (= "Refusing to sign: PURR must move to its own HyperEVM system address."
           (get-in @store* [:funding-ui :modal :error])))
    (is (= :error (ffirst toasts)))))

(deftest the-client-only-hyperevm-action-is-never-signed-as-a-hypercore-action-test
  ;; `hyperEvmToCore` goes to the HyperEVM wallet submitter; it is never
  ;; signed as a usdClassTransfer or posted as a sendAsset.
  (let [store* (store)
        submitted (atom [])
        signed (atom [])
        action {:type "hyperEvmToCore"
                :kind "native"
                :tokenIndex 150
                :symbol "HYPE"
                :tokenAddress nil
                :spender nil
                :recipient "0x2222222222222222222222222222222222222222"
                :amount "1"
                :units "1000000000000000000"
                :destinationDex 4294967295
                :chainId "0x3e7"}]
    (effects/api-submit-funding-transfer!
     (merge (effects-support/base-submit-effect-deps)
            {:store store*
             :request {:action action
                       :route :evm->core
                       :evm {:owner support/owner :symbol "HYPE" :amount "1" :token-index 150}}
             :submit-send-asset! (fn [& args] (swap! signed conj args) (js/Promise.resolve {:status "ok"}))
             :submit-usd-class-transfer! (fn [& args] (swap! signed conj args) (js/Promise.resolve {:status "ok"}))
             :submit-hyperevm-to-core! (fn [_store owner action* _opts]
                                         (swap! submitted conj [owner action*])
                                         (js/Promise.resolve {:status "ok" :txHash nil :hashes []}))
             :show-toast! (fn [& _] nil)}))
    (is (= [] @signed))
    (is (= [[support/owner action]] @submitted))))

(deftest an-unknown-transfer-type-is-refused-not-signed-test
  (let [store* (store)
        {:keys [signed]} (run-transfer! store* {:action {:type "mysteryMove" :amount "1"}})]
    (is (= [] signed))
    (is (= "This transfer type isn't supported."
           (get-in @store* [:funding-ui :modal :error])))))

(deftest a-valid-core-to-evm-send-still-signs-as-send-asset-test
  (let [action {:type "sendAsset"
                :destination "0x2000000000000000000000000000000000000001"
                :sourceDex "spot"
                :destinationDex "spot"
                :token "PURR:0xc1fb593aeffbeb02f85e0308e9956a90"
                :amount "100"
                :fromSubAccount ""}
        {:keys [signed]} (run-transfer! (store) {:action action :route :core->evm})]
    (is (= [[:send-asset action]] signed))))

(deftest send-mode-never-signs-a-token-to-the-wrong-system-address-test
  ;; Send takes any destination; HYPE's 0x2222… and another token's system
  ;; address swallow a mismatched token for good.
  (doseq [[token destination] [["PURR:0xc1fb593aeffbeb02f85e0308e9956a90"
                                "0x2222222222222222222222222222222222222222"]
                               ["HYPE:0x0d01dc56dcaaca66ad901c959b4011ec"
                                "0x2000000000000000000000000000000000000001"]]]
    (let [store* (atom (assoc (support/state) :funding-ui {:modal (effects-support/seed-modal :send)}))
          signed (atom [])]
      (effects/api-submit-funding-send!
       (merge (effects-support/base-submit-effect-deps)
              {:store store*
               :request {:action {:type "sendAsset"
                                  :destination destination
                                  :sourceDex "spot"
                                  :destinationDex "spot"
                                  :token token
                                  :amount "1"
                                  :fromSubAccount ""}}
               :submit-send-asset! (fn [_ _ action]
                                     (swap! signed conj action)
                                     (js/Promise.resolve {:status "ok"}))
               :show-toast! (fn [& _] nil)}))
      (is (= [] @signed) token)
      (is (re-find #"^Refusing to sign" (str (get-in @store* [:funding-ui :modal :error])))
          token))))
