(ns hyperopen.funding.effects.hyperevm-runtime-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.effects.hyperevm-runtime :as hyperevm-runtime]
            [hyperopen.funding.infrastructure.wallet-rpc :as wallet-rpc]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.wallet.core :as wallet]))

(deftest the-wallet-sees-hyperevm-with-hype-and-a-verified-switch-test
  (is (= "0x3e7" (:chain-id hyperevm-runtime/wallet-chain-config)))
  (is (true? (:verify-switch? hyperevm-runtime/wallet-chain-config)))
  (is (= {:name "HYPE" :symbol "HYPE" :decimals 18}
         (:native-currency hyperevm-runtime/wallet-chain-config)))
  (is (= (:rpc-url chain/mainnet) (:rpc-url hyperevm-runtime/wallet-chain-config))))

(deftest submit-deps-bind-the-real-wallet-and-rpc-test
  (let [deps (hyperevm-runtime/submit-deps (atom {:x 1}))]
    (is (identical? wallet/provider (:wallet-provider-fn deps)))
    (is (identical? wallet-rpc/ensure-wallet-chain! (:ensure-wallet-chain! deps)))
    (is (identical? wallet-rpc/request-chain-id! (:request-chain-id! deps)))
    (is (identical? wallet-rpc/send-transaction! (:send-transaction! deps)))
    (is (identical? hyperevm-runtime/wallet-chain-config (:chain-config deps)))
    (is (= {:x 1} ((:state-fn deps))))
    (is (every? fn? ((juxt :gas-price! :estimate-gas! :read-allowance! :wait-for-receipt!
                           :record-in-flight! :now-ms-fn) deps)))))

(deftest record-in-flight-writes-hashes-and-changes-for-the-flow-test
  (let [owner "0x1234567890abcdef1234567890abcdef12345678"
        store (atom {:hyperevm {:in-flight {owner {:flow-id "f" :status :running :hashes []}}}})
        record! (:record-in-flight! (hyperevm-runtime/submit-deps store))]
    (record! owner "f" {:hash "0xa" :step :send :submitted-at-ms 3})
    (is (= {:flow-id "f" :status :running :hashes ["0xa"] :step :send :waiting-receipt? true
            :submitted-at-ms 3}
           (get-in @store [:hyperevm :in-flight owner])))
    (record! owner "f" {:waiting-receipt? false})
    (is (false? (get-in @store [:hyperevm :in-flight owner :waiting-receipt?])))
    (record! owner "other" {:waiting-receipt? true})
    (is (false? (get-in @store [:hyperevm :in-flight owner :waiting-receipt?])))))

(deftest read-allowance-decodes-the-rpc-answer-test
  (async done
    (let [bodies (atom [])
          fetch-fn (fn [_ init]
                     (swap! bodies conj (js->clj (js/JSON.parse (.-body init)) :keywordize-keys true))
                     (js/Promise.resolve
                      #js {:ok true :status 200
                           :json (fn [] (js/Promise.resolve
                                         (clj->js {:jsonrpc "2.0" :id 1
                                                   :result (str "0x" (apply str (repeat 56 "0"))
                                                                "3b9aca00")})))}))]
      (-> (hyperevm-runtime/read-allowance!
           {:fetch-fn fetch-fn :set-timeout-fn (fn [_ _] :t) :clear-timeout-fn (fn [_] nil)
            :make-abort-controller (fn [] nil)}
           "0xb88339cb7199b77e23db6e890353e22632ba630f"
           "0x1234567890abcdef1234567890abcdef12345678"
           "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24")
          (.then (fn [allowance]
                   (is (= "1000000000" allowance))
                   (is (= "eth_call" (:method (first @bodies))))
                   (is (= "0xb88339cb7199b77e23db6e890353e22632ba630f"
                          (get-in (first @bodies) [:params 0 :to])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(def ^:private owner "0x1234567890abcdef1234567890abcdef12345678")
(def ^:private other "0x9999999999999999999999999999999999999999")

(deftest the-spot-refresh-applies-only-while-the-owner-is-shown-test
  (async done
    (let [requests (atom [])
          request-fn (fn [address _opts]
                       (js/Promise. (fn [resolve _] (swap! requests conj [address resolve]))))
          fresh {:balances [{:coin "HYPE" :token 150 :total "422.08" :hold "0"}]}
          shown-spot {:balances [{:coin "HYPE" :token 150 :total "7" :hold "0"}]}
          own (atom {:wallet {:address owner} :spot {:clearinghouse-state {:balances []}}})
          switched (atom {:wallet {:address owner} :spot {:clearinghouse-state {:balances []}}})
          spectating (atom {:wallet {:address owner}
                            :account-context {:spectate-mode {:active? true :address other}}
                            :spot {:clearinghouse-state shown-spot}})
          own-read (hyperevm-runtime/refresh-spot-clearinghouse! own owner {} request-fn)
          switched-read (hyperevm-runtime/refresh-spot-clearinghouse! switched owner {} request-fn)]
      (hyperevm-runtime/refresh-spot-clearinghouse! spectating owner {} request-fn)
      (is (= [owner owner] (mapv first @requests)) "no read while another account is already shown")
      ;; The user starts spectating while the second read is out.
      (swap! switched assoc
             :account-context {:spectate-mode {:active? true :address other}}
             :spot {:clearinghouse-state shown-spot})
      (doseq [[_ resolve] @requests] (resolve fresh))
      (-> (js/Promise.all #js [own-read switched-read])
          (.then (fn [_]
                   (is (= fresh (get-in @own [:spot :clearinghouse-state])))
                   (is (= shown-spot (get-in @switched [:spot :clearinghouse-state]))
                       "a reply for an account no longer shown is dropped")
                   (is (= shown-spot (get-in @spectating [:spot :clearinghouse-state])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
