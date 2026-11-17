(ns hyperopen.wallet.agent-runtime-passkey-setup-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.wallet.agent-runtime :as agent-runtime]))

(deftest enable-agent-trading-explains-a-failed-passkey-setup-test
  (async done
    (let [store (atom {:wallet {:address "0x111"
                                :chain-id "0xa4b1"
                                :agent {:status :approving
                                        :storage-mode :local
                                        :local-protection-mode :passkey}}})]
      (agent-runtime/enable-agent-trading!
       {:store store
        :options {:storage-mode :local
                  :local-protection-mode :passkey}
        :create-agent-credentials! (fn []
                                     {:private-key "0xpriv"
                                      :agent-address "0x999"})
        :now-ms-fn (fn [] 1700000000001)
        :normalize-storage-mode identity
        :default-signature-chain-id-for-environment (fn [_] "0xdefault")
        :build-approve-agent-action (fn [agent-address nonce & _]
                                      {:agentAddress agent-address
                                       :nonce nonce})
        :approve-agent! (fn [& _]
                          (js/Promise.resolve #js {:json (fn []
                                                          (js/Promise.resolve #js {:status "ok"}))}))
        :passkey-lock-supported? (constantly true)
        :create-locked-session! (fn [_]
                                  (js/Promise.reject (js/Error. "The operation was not allowed.")))
        :runtime-error-message (fn [err] (str err))
        :exchange-response-error (fn [resp] (pr-str resp))})
      (js/setTimeout
       (fn []
         (try
           (is (= :error (get-in @store [:wallet :agent :status])))
           (is (= (str "The operation was not allowed. "
                       "Try again, or turn off passkey protection in Trading Settings to remember the key without it.")
                  (get-in @store [:wallet :agent :error])))
           (finally
             (done))))
       10))))
