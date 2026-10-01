(ns hyperopen.schema.contracts.hyperevm-args-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.contracts :as contracts]))

(def ^:private owner "0x1111111111111111111111111111111111111111")

(defn- action-valid?
  [action-id args]
  (try
    (contracts/assert-action-args! action-id args {:phase :test})
    true
    (catch :default _ false)))

(defn- effect-valid?
  [effect-id args]
  (try
    (contracts/assert-effect-args! effect-id args {:phase :test})
    true
    (catch :default _ false)))

(deftest refresh-hyperevm-balances-args-test
  (is (action-valid? :actions/refresh-hyperevm-balances [{:now-ms 1}]))
  (is (action-valid? :actions/refresh-hyperevm-balances
                     [{:now-ms 1 :force? true :fast-poll-ms 60000}]))
  (is (not (action-valid? :actions/refresh-hyperevm-balances [])))
  (is (not (action-valid? :actions/refresh-hyperevm-balances [{}]))
      "the caller must supply the clock")
  (is (not (action-valid? :actions/refresh-hyperevm-balances [{:now-ms 1 :force? "yes"}])))
  (is (not (action-valid? :actions/refresh-hyperevm-balances [{:now-ms 1 :fast-poll-ms 0}]))))

(deftest refresh-hyperevm-bridge-capacity-args-test
  (is (action-valid? :actions/refresh-hyperevm-bridge-capacity [150]))
  (is (not (action-valid? :actions/refresh-hyperevm-bridge-capacity [-1])))
  (is (not (action-valid? :actions/refresh-hyperevm-bridge-capacity ["150"])))
  (is (not (action-valid? :actions/refresh-hyperevm-bridge-capacity []))))

(deftest hyperevm-effect-args-test
  (is (effect-valid? :effects/fetch-hyperevm-balances
                     [{:addresses [owner] :requested-at-ms 1}]))
  (is (not (effect-valid? :effects/fetch-hyperevm-balances [{:addresses [] :requested-at-ms 1}])))
  (is (not (effect-valid? :effects/fetch-hyperevm-balances [{:addresses [owner]}])))
  (is (effect-valid? :effects/fetch-hyperevm-core-bridge-balance
                     [6 "0x2000000000000000000000000000000000000006"]))
  (is (not (effect-valid? :effects/fetch-hyperevm-core-bridge-balance [6 ""])))
  (is (effect-valid? :effects/fetch-hyperevm-core-account-status [owner]))
  (is (not (effect-valid? :effects/fetch-hyperevm-core-account-status []))))

(deftest set-balances-location-filter-args-test
  (is (action-valid? :actions/set-balances-location-filter [:all]))
  (is (action-valid? :actions/set-balances-location-filter [:hypercore]))
  (is (action-valid? :actions/set-balances-location-filter [:hyperevm]))
  (is (not (action-valid? :actions/set-balances-location-filter [:spot])))
  (is (not (action-valid? :actions/set-balances-location-filter ["hyperevm"])))
  (is (not (action-valid? :actions/set-balances-location-filter []))))

(deftest check-hyperevm-in-flight-args-test
  (is (action-valid? :actions/check-hyperevm-in-flight []))
  (is (not (action-valid? :actions/check-hyperevm-in-flight [owner]))))

(def ^:private tx-hash
  "0x1111111111111111111111111111111111111111111111111111111111111111")

(deftest hyperevm-transfer-follow-up-effect-args-test
  (is (effect-valid? :effects/fetch-hyperevm-in-flight-receipt [owner tx-hash]))
  (is (not (effect-valid? :effects/fetch-hyperevm-in-flight-receipt [owner "0x12"])))
  (is (not (effect-valid? :effects/fetch-hyperevm-in-flight-receipt ["me" tx-hash])))
  (is (effect-valid? :effects/wallet-watch-asset
                     [{:chain-id "0x3e7" :address "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
                       :symbol "PURR" :decimals 18}]))
  (is (effect-valid? :effects/wallet-watch-asset
                     [{:chain-id "0x3e7" :address nil :symbol "HYPE" :decimals 18}])
      "native HYPE has no contract address")
  (is (not (effect-valid? :effects/wallet-watch-asset
                          [{:chain-id "0xa4b1" :address nil :symbol "HYPE" :decimals 18}]))
      "HyperEVM only")
  (is (not (effect-valid? :effects/wallet-watch-asset
                          [{:chain-id "0x3e7" :address nil :symbol "HYPE" :decimals 18 :extra 1}])))
  (is (not (effect-valid? :effects/wallet-watch-asset
                          [{:chain-id "0x3e7" :address "0x12" :symbol "PURR" :decimals 18}]))))
