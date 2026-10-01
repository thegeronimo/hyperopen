(ns hyperopen.funding.test-support.hyperevm-transfer
  "App states for the HyperEVM Transfer tests. Token rows and bridge values
   come from the live HyperEVM fixtures; balances are round, readable
   amounts."
  (:require [hyperopen.funding.application.modal-state :as modal-state]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.hyperevm.test-support.bridge-fixtures :as bridge-fixtures]))

(def owner "0x1234567890abcdef1234567890abcdef12345678")
(def subaccount "0xbce774ef2382a4eb9376ea6f20408b318b10b63e")

(def now-ms 1760000000000)

(def hype-index 150)
(def usdc-index 0)
(def purr-index 1)
(def six-index 6)
(def hope-index 122)
(def joff-index 296)

(def gas-price-wei
  "0.1 gwei: the next small-block base fee floor."
  "100000000")

(def evm-entry
  "The owner's HyperEVM balances: 12.5 HYPE, 1,240 USDC and 50 PURR."
  {:status :ready
   :stale? false
   :requested-at-ms (- now-ms 2000)
   :loaded-at-ms (- now-ms 1000)
   :native-wei "12500000000000000000"
   :token-units {usdc-index "1240000000"
                 purr-index "50000000000000000000"}
   :token-indexes #{0 1 6 122 197 296 478}
   :unread-token-indexes []
   :gas-price-wei gas-price-wei
   :error nil
   :error-kind nil})

(def spot-balances
  [{:coin "USDC" :token usdc-index :total "2105.4" :hold "0.0"}
   {:coin "HYPE" :token hype-index :total "412.08" :hold "0.0"}
   {:coin "PURR" :token purr-index :total "9800" :hold "0.0"}
   {:coin "SIX" :token six-index :total "10" :hold "0.0"}
   {:coin "HOPE" :token hope-index :total "7" :hold "0.0"}
   {:coin "JOFF" :token joff-index :total "5" :hold "0.0"}])

(def healthy
  {:decimals-ok? true :balance-of-ok? true})

(def bridge
  {:evm-system-units bridge-fixtures/live-system-units
   ;; Read by the poll requested 2 s before `now-ms`, like the health stamp.
   :evm-system-read-at-ms (zipmap (keys bridge-fixtures/live-system-units) (repeat (- now-ms 2000)))
   :core->evm-sent-at-ms {}
   :core-system-balances {hype-index {:amount "51277474.282994248" :loaded-at-ms now-ms}
                          purr-index {:amount "91403084.7764399946" :loaded-at-ms now-ms}}
   :token-health {purr-index healthy
                  six-index healthy
                  hope-index {:decimals-ok? false :balance-of-ok? true}
                  197 healthy
                  joff-index {:balance-of-ok? false}
                  478 healthy}
   :health-requested-at-ms (- now-ms 2000)})

(defn modal
  [overrides]
  (merge (modal-state/default-funding-modal-state)
         {:open? true
          :mode :transfer
          :transfer-destination-address owner
          :destination-input owner}
         overrides))

(defn state
  "A connected master account with Spot, Perps and HyperEVM balances and a
   Transfer modal built from `modal-overrides`."
  ([] (state {}))
  ([modal-overrides]
   {:wallet {:address owner :connected? true :chain-id "0xa4b1"}
    :spot {:meta bridge-fixtures/bridge-spot-meta
           :clearinghouse-state {:balances spot-balances}}
    :webdata2 {:clearinghouseState {:withdrawable "500.25"
                                    :marginSummary {:accountValue "800"
                                                    :totalMarginUsed "0"}}}
    :asset-selector {:market-by-key {"spot:HYPE" {:market-type :spot :coin "@107"
                                                  :base "HYPE" :quote "USDC" :mark "44.68"}
                                     "spot:PURR" {:market-type :spot :coin "PURR/USDC"
                                                  :base "PURR" :quote "USDC" :mark "0.2"}}}
    :hyperevm (assoc (hyperevm-balances/default-state)
                     :balances {:by-address {owner evm-entry}}
                     :bridge bridge
                     :core-account {owner :active})
    :funding-ui {:modal (modal modal-overrides)}}))

(defn with-evm-entry
  [state entry]
  (assoc-in state [:hyperevm :balances :by-address owner] entry))

(defn saved-modal
  "The modal a command's effects save, or nil."
  [effects]
  (some (fn [[effect-id path value]]
          (when (and (= :effects/save effect-id) (= [:funding-ui :modal] path))
            value))
        effects))
