(ns hyperopen.funding.domain.assets
  (:require [clojure.string :as str]))

(def withdraw-min-usdc
  5)

(def deposit-min-usdc
  5)

(def deposit-chain-id-mainnet
  "0xa4b1")

(def deposit-chain-id-testnet
  "0x66eee")

(def deposit-quick-amounts
  [5 1000 10000 100000])

(def ^:private deposit-assets-base
  [{:key :usdc
    :symbol "USDC"
    :name "USDC"
    :network "Arbitrum"
    :flow-kind :bridge2
    :minimum deposit-min-usdc}
   {:key :usdt
    :symbol "USDT"
    :name "Tether"
    :network "Arbitrum"
    :flow-kind :route
    :route-key "lifi"}
   {:key :btc
    :symbol "BTC"
    :name "Bitcoin"
    :network "Bitcoin"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "bitcoin"}
   {:key :eth
    :symbol "ETH"
    :name "Ethereum"
    :network "Ethereum"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "ethereum"}
   {:key :sol
    :symbol "SOL"
    :name "Solana"
    :network "Solana"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "solana"}
   {:key :2z
    :symbol "2Z"
    :name "ZZ"
    :network "Solana"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "solana"}
   {:key :bonk
    :symbol "BONK"
    :name "Bonk"
    :network "Solana"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "solana"}
   {:key :ena
    :symbol "ENA"
    :name "Ethena"
    :network "Ethereum"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "ethereum"}
   {:key :fart
    :symbol "FARTCOIN"
    :name "Fartcoin"
    :network "Solana"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "solana"}
   {:key :mon
    :symbol "MON"
    :name "Monad"
    :network "Monad"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "monad"}
   {:key :pump
    :symbol "PUMP"
    :name "Pump"
    :network "Solana"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "solana"}
   {:key :spxs
    :symbol "SPX"
    :name "SPX"
    :network "Solana"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "solana"}
   {:key :xpl
    :symbol "XPL"
    :name "Plasma"
    :network "Plasma"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "plasma"}
   {:key :usdh
    :symbol "USDH"
    :name "USDH"
    :network "Arbitrum"
    :flow-kind :route
    :route-key "arbitrum_across"
    :maximum 1000000}])

(def ^:private deposit-asset-keys
  (set (map :key deposit-assets-base)))

(def ^:private deposit-implemented-asset-keys
  #{:usdc :usdt :btc :eth :sol :2z :bonk :ena :fart :mon :pump :spxs :xpl :usdh})

(def withdraw-default-asset-key
  :usdc)

(def ^:private withdraw-supported-asset-keys
  (->> deposit-assets-base
       (filter (fn [{:keys [key flow-kind]}]
                 (or (= key :usdc)
                     (= flow-kind :hyperunit-address))))
       (map :key)
       set))

(def ^:private hyperunit-withdraw-minimum-by-asset-key
  {:btc 0.0003
   :eth 0.007
   :sol 0.12
   :2z 150
   :bonk 1800000
   :ena 120
   :fart 55
   :mon 450
   :pump 5500
   :spxs 32
   :xpl 60})

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- normalize-chain-id
  [value]
  (let [raw (some-> value str str/trim)]
    (when (seq raw)
      (let [hex? (str/starts-with? raw "0x")
            source (if hex? (subs raw 2) raw)
            base (if hex? 16 10)
            parsed (js/parseInt source base)]
        (when (and (number? parsed)
                   (not (js/isNaN parsed)))
          (str "0x" (.toString (js/Math.floor parsed) 16)))))))

(defn normalize-deposit-asset-key
  [value]
  (let [asset-key (cond
                    (keyword? value) value
                    (string? value) (some-> value str/trim str/lower-case keyword)
                    :else nil)]
    (when (contains? deposit-asset-keys asset-key)
      asset-key)))

(defn normalize-withdraw-asset-key
  [value]
  (let [asset-key (cond
                    (keyword? value) value
                    (string? value) (some-> value str/trim str/lower-case keyword)
                    :else nil)]
    (when (contains? withdraw-supported-asset-keys asset-key)
      asset-key)))

(defn- resolve-deposit-network
  [state]
  (let [wallet-chain-id (normalize-chain-id (get-in state [:wallet :chain-id]))]
    (if (= wallet-chain-id deposit-chain-id-testnet)
      {:chain-id deposit-chain-id-testnet
       :chain-label "Arbitrum Sepolia"}
      {:chain-id deposit-chain-id-mainnet
       :chain-label "Arbitrum"})))

(defn deposit-assets
  [state]
  (let [{:keys [chain-id chain-label]} (resolve-deposit-network state)]
    (mapv (fn [asset]
            (if (= :usdc (:key asset))
              (assoc asset
                     :network chain-label
                     :chain-id chain-id)
              asset))
          deposit-assets-base)))

(defn deposit-asset
  [state modal]
  (let [selected-key (normalize-deposit-asset-key (:deposit-selected-asset-key modal))]
    (some (fn [asset]
            (when (= selected-key (:key asset))
              asset))
          (deposit-assets state))))

(defn deposit-asset-implemented?
  [asset]
  (contains? deposit-implemented-asset-keys (:key asset)))

(defn deposit-assets-filtered
  [state modal]
  (let [search-term (-> (or (:deposit-search-input modal) "")
                        str
                        str/trim
                        str/lower-case)
        assets (deposit-assets state)]
    (if-not (seq search-term)
      assets
      (filterv (fn [{:keys [symbol name network]}]
                 (let [symbol* (str/lower-case (or symbol ""))
                       name* (str/lower-case (or name ""))
                       network* (str/lower-case (or network ""))]
                   (or (str/includes? symbol* search-term)
                       (str/includes? name* search-term)
                       (str/includes? network* search-term))))
               assets))))

(defn withdraw-assets
  [state]
  (->> (deposit-assets state)
       (filterv (fn [asset]
                  (contains? withdraw-supported-asset-keys (:key asset))))))

(defn withdraw-minimum-amount
  [asset]
  (let [asset-key (:key asset)]
    (cond
      (= asset-key :usdc)
      withdraw-min-usdc

      (contains? hyperunit-withdraw-minimum-by-asset-key asset-key)
      (get hyperunit-withdraw-minimum-by-asset-key asset-key)

      :else 0)))

(defn hyperunit-source-chain
  [asset]
  (some-> (:hyperunit-source-chain asset)
          non-blank-text
          str/lower-case))
