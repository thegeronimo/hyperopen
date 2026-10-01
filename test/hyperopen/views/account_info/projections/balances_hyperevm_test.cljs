(ns hyperopen.views.account-info.projections.balances-hyperevm-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.views.account-info.projections.balances-hyperevm :as balances-hyperevm]))

(def ^:private state
  "The owner holds 12.5 HYPE, 1,240 USDC and 50 PURR on HyperEVM."
  (fixture/state))

(defn- row-by-key
  [rows key*]
  (some #(when (= key* (:key %)) %) rows))

(deftest hyperevm-rows-project-what-the-shown-account-holds-test
  (let [rows (balances-hyperevm/hyperevm-rows state [])
        hype (row-by-key rows "hyperevm-150")
        usdc (row-by-key rows "hyperevm-0")
        purr (row-by-key rows "hyperevm-1")]
    (is (= ["hyperevm-150" "hyperevm-0" "hyperevm-1"] (mapv :key rows))
        "native HYPE first, then by token index")
    (is (every? #(= :hyperevm (:location %)) rows))
    (testing "native HYPE keeps the Transfer modal's gas reserve back"
      (is (= "12.5" (:total-text hype)))
      (is (= "12.499" (:available-text hype)))
      (is (= 12.499 (:available-balance hype)))
      (is (= "0.001" (:gas-reserve-text hype)))
      (is (true? (:evm-native? hype)))
      (is (nil? (:evm-contract hype)))
      (is (= "@107" (:market-coin hype)) "the coin opens the HYPE spot market"))
    (testing "USD values come from the shared token pricing"
      (is (= (* 12.5 44.68) (:usdc-value hype)))
      (is (= 1240 (:usdc-value usdc)))
      (is (= 10 (:usdc-value purr))))
    (testing "ERC-20 rows carry their contract and its HyperEVM explorer page"
      (is (= "0xb88339cb7199b77e23db6e890353e22632ba630f" (:evm-contract usdc)))
      (is (= "https://hyperevmscan.io/token/0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
             (:evm-contract-url purr)))
      (is (= "50" (:available-text purr)) "only HYPE keeps a gas reserve")
      (is (nil? (:gas-reserve-text purr))))
    (testing "rows show amounts like the token's HyperCore Spot row (weiDecimals)"
      (is (= 8 (:amount-decimals hype)))
      (is (= 8 (:amount-decimals usdc))
          "USDC holds 6 decimals on HyperEVM but reads like the Spot USDC row")
      (is (= 5 (:amount-decimals purr))))
    (is (every? #(nil? (:contract-id %)) rows)
        "the HyperCore explorer link never gets an ERC-20 address")))

(deftest hyperevm-rows-price-a-token-held-on-core-like-its-core-row-test
  (let [core-rows [{:key "spot-1" :coin "PURR" :selection-coin "PURR"
                    :total-balance 100 :usdc-value 25}]
        purr (row-by-key (balances-hyperevm/hyperevm-rows state core-rows) "hyperevm-1")]
    (is (= 12.5 (:usdc-value purr)) "0.25 per PURR from the Core row, over the market's 0.2")))

(deftest hyperevm-rows-skip-unknown-and-empty-tokens-test
  (let [entry (assoc fixture/evm-entry
                     :token-units {fixture/usdc-index "1240000000"
                                   fixture/purr-index "0"}
                     :unread-token-indexes [fixture/joff-index])
        rows (balances-hyperevm/hyperevm-rows (fixture/with-evm-entry state entry) [])]
    (is (= ["hyperevm-150" "hyperevm-0"] (mapv :key rows))
        "a zero balance has no row, and JOFF (never read) is unknown, not zero")))

(deftest hyperevm-status-and-rows-before-and-after-reads-test
  (let [no-entry (assoc-in state [:hyperevm :balances :by-address] {})
        loading (fixture/with-evm-entry state {:status :loading :requested-at-ms 1})
        failed (fixture/with-evm-entry state {:status :error :stale? false
                                              :error "rate limited" :error-kind :rate-limited})
        stale (fixture/with-evm-entry state (assoc fixture/evm-entry
                                                   :status :error :stale? true))
        refreshing (fixture/with-evm-entry state (assoc fixture/evm-entry :status :loading))]
    (is (= :loading (balances-hyperevm/hyperevm-status no-entry)))
    (is (= [] (balances-hyperevm/hyperevm-rows no-entry [])))
    (is (= :loading (balances-hyperevm/hyperevm-status loading)))
    (is (= [] (balances-hyperevm/hyperevm-rows loading [])))
    (is (= :unavailable (balances-hyperevm/hyperevm-status failed)))
    (is (= [] (balances-hyperevm/hyperevm-rows failed [])))
    (testing "a refresh that fails or is still loading keeps the last read"
      (is (= :ready (balances-hyperevm/hyperevm-status stale)))
      (is (= 3 (count (balances-hyperevm/hyperevm-rows stale []))))
      (is (= :ready (balances-hyperevm/hyperevm-status refreshing)))
      (is (= 3 (count (balances-hyperevm/hyperevm-rows refreshing [])))))))

(deftest hyperevm-rows-follow-the-shown-account-test
  (let [spectated "0x5555555555555555555555555555555555555555"
        spectating (assoc state :account-context {:spectate-mode {:active? true
                                                                  :address spectated}})]
    (is (= [] (balances-hyperevm/hyperevm-rows spectating []))
        "the owner's balances never show as the spectated account's")
    (is (= :loading (balances-hyperevm/hyperevm-status spectating)))
    (let [read (-> spectating
                   (hyperevm-balances/apply-loading {:addresses [spectated] :requested-at-ms 5})
                   (hyperevm-balances/apply-success spectated 5
                                                    {:native-wei "2000000000000000000"
                                                     :token-units {}
                                                     :gas-price-wei fixture/gas-price-wei}
                                                    #{0 1}
                                                    6))]
      (is (= ["hyperevm-150"] (mapv :key (balances-hyperevm/hyperevm-rows read []))))
      (is (= 1 (balances-hyperevm/hyperevm-row-count read))))))

(deftest no-account-shown-has-nothing-to-wait-for-test
  (let [no-account (dissoc state :wallet)]
    (is (= :ready (balances-hyperevm/hyperevm-status no-account)))
    (is (= [] (balances-hyperevm/hyperevm-rows no-account [])))))

(deftest hyperevm-row-count-matches-the-rows-test
  (is (= 3 (balances-hyperevm/hyperevm-row-count state)))
  (is (= 0 (balances-hyperevm/hyperevm-row-count (dissoc state :wallet)))))

(deftest location-helpers-test
  (let [rows [{:key "perps-usdc" :coin "USDC (Perps)"}
              {:key "perps-usdc-xyz" :coin "USDC (Perps) xyz"}
              {:key "spot-1" :coin "PURR"}
              {:key "hyperevm-1" :coin "PURR" :location :hyperevm}]]
    (is (= [:perps :perps :spot :hyperevm] (mapv balances-hyperevm/row-location rows)))
    (is (= rows (balances-hyperevm/filter-rows-by-location rows :all)))
    (is (= ["perps-usdc" "perps-usdc-xyz" "spot-1"]
           (mapv :key (balances-hyperevm/filter-rows-by-location rows :hypercore))))
    (is (= ["hyperevm-1"] (mapv :key (balances-hyperevm/filter-rows-by-location rows :hyperevm))))
    (is (= rows (balances-hyperevm/filter-rows-by-location rows :nonsense)))
    (is (= :all (balances-hyperevm/normalize-location-filter nil)))
    (is (= :hyperevm (balances-hyperevm/normalize-location-filter :hyperevm)))))
