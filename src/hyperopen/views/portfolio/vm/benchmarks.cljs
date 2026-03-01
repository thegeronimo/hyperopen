(ns hyperopen.views.portfolio.vm.benchmarks
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.vm.constants :as constants]))

(defn vault-benchmark-value
  [vault-address]
  (str constants/vault-benchmark-prefix vault-address))

(defn vault-benchmark-address
  [benchmark]
  (when (and (string? benchmark)
             (str/starts-with? benchmark constants/vault-benchmark-prefix))
    (subs benchmark (count constants/vault-benchmark-prefix))))

(defn benchmark-vault-row?
  [row]
  (boolean (and (:isVault row)
                (:tvl row)
                (pos? (:tvl row)))))

(defn benchmark-vault-tvl
  [row]
  (js/Number. (or (:tvl row) 0)))

(defn benchmark-vault-name
  [row]
  (or (:name row) "Unknown Vault"))

(defn benchmark-vault-option-rank
  [row]
  (- (benchmark-vault-tvl row)))

(defn eligible-vault-benchmark-rows
  [all-vault-rows]
  (->> (or all-vault-rows [])
       (filter benchmark-vault-row?)
       (sort-by benchmark-vault-option-rank)
       (take constants/max-vault-benchmark-options)
       vec))

(defn build-vault-benchmark-selector-options
  [eligible-vault-rows]
  (mapv (fn [row]
          {:value (vault-benchmark-value (:vaultAddress row))
           :label (benchmark-vault-name row)
           :group "Vaults"
           :tvl (benchmark-vault-tvl row)})
        eligible-vault-rows))

(defn vault-benchmark-row-by-address
  [all-vault-rows vault-address]
  (first (filter #(= (:vaultAddress %) vault-address) all-vault-rows)))

(defn market-type-token
  [value]
  (when (string? value)
    (case value
      "SPOT" "Spot"
      "PERP" "Perp"
      value)))

(defn benchmark-open-interest
  [market]
  (let [oi (:openInterest market)]
    (if (string? oi)
      (js/Number. oi)
      0)))

(defn benchmark-option-label
  [market]
  (let [coin (:coin market)
        type-token (market-type-token (:type market))]
    (if type-token
      (str coin " " type-token)
      coin)))

(defn benchmark-option-rank
  [market]
  (let [coin (:coin market)
        type* (:type market)
        is-btc? (= coin "BTC")
        is-eth? (= coin "ETH")
        is-spot? (= type* "SPOT")
        oi (benchmark-open-interest market)]
    (cond
      (and is-btc? (not is-spot?)) -1000000000000
      (and is-eth? (not is-spot?)) -100000000000
      (and is-btc? is-spot?) -10000000000
      (and is-eth? is-spot?) -1000000000
      :else (- oi))))

(defn build-benchmark-selector-options
  [active-markets eligible-vault-rows]
  (let [market-options (->> (or active-markets [])
                            (map (fn [market]
                                   {:value (:coin market)
                                    :label (benchmark-option-label market)
                                    :group "Markets"
                                    :rank (benchmark-option-rank market)}))
                            (sort-by :rank)
                            (mapv #(dissoc % :rank)))
        vault-options (build-vault-benchmark-selector-options eligible-vault-rows)]
    (vec (concat market-options vault-options))))

(defn mix-benchmark-markets-hash
  [hash-value market]
  (let [coin (:coin market)
        oi (str (:openInterest market))]
    (+ (* hash-value 31)
       (hash coin)
       (hash oi))))

(defn benchmark-market-signature
  [market]
  (str (:coin market) "-" (:openInterest market)))

(defn benchmark-markets-signature
  [markets]
  (if (seq markets)
    (let [n (min 10 (count markets))
          top-markets (take n (sort-by benchmark-option-rank markets))]
      (str/join "|" (map benchmark-market-signature top-markets)))
    constants/empty-benchmark-markets-signature))

(defonce benchmark-selector-options-cache
  (atom {:markets-signature constants/empty-benchmark-markets-signature
         :vaults-signature ""
         :options []}))

(defonce eligible-vault-benchmark-rows-cache
  (atom {:signature ""
         :rows []}))

(defn memoized-eligible-vault-benchmark-rows
  [all-vault-rows]
  (let [current-sig (str (count all-vault-rows) "-" (hash all-vault-rows))
        cached @eligible-vault-benchmark-rows-cache]
    (if (= current-sig (:signature cached))
      (:rows cached)
      (let [computed (eligible-vault-benchmark-rows all-vault-rows)]
        (reset! eligible-vault-benchmark-rows-cache {:signature current-sig
                                                     :rows computed})
        computed))))

(def ^:dynamic *build-benchmark-selector-options* build-benchmark-selector-options)

(defn memoized-benchmark-selector-options
  [active-markets all-vault-rows]
  (let [markets-sig (benchmark-markets-signature active-markets)
        vaults-sig (:signature @eligible-vault-benchmark-rows-cache)
        cached @benchmark-selector-options-cache]
    (if (and (= markets-sig (:markets-signature cached))
             (= vaults-sig (:vaults-signature cached)))
      (:options cached)
      (let [eligible-vaults (memoized-eligible-vault-benchmark-rows all-vault-rows)
            computed (*build-benchmark-selector-options* active-markets eligible-vaults)]
        (reset! benchmark-selector-options-cache {:markets-signature markets-sig
                                                  :vaults-signature vaults-sig
                                                  :options computed})
        computed))))

(defn benchmark-selector-options
  [state]
  (let [active-markets (get-in state [:market-data :active-markets])
        all-vault-rows (get-in state [:portfolio :summaries :all :vaults])]
    (memoized-benchmark-selector-options active-markets all-vault-rows)))

(defn normalize-benchmark-search-query
  [value]
  (when value
    (str/lower-case (str/trim value))))

(defn benchmark-option-matches-search?
  [option search-query]
  (if-let [query (normalize-benchmark-search-query search-query)]
    (str/includes? (str/lower-case (:label option)) query)
    true))

(defn selected-returns-benchmark-coins
  [state]
  (let [saved (get-in state [:ui :preferences :portfolio-returns-benchmarks])]
    (if (and saved (vector? saved) (seq saved))
      (vec (take 4 saved))
      ["BTC" "ETH"])))

(defn selected-benchmark-options
  [options selected-coins]
  (let [options-by-value (into {} (map (juxt :value identity) options))]
    (->> selected-coins
         (keep #(get options-by-value %))
         vec)))

(defn returns-benchmark-selector-model
  [state]
  (let [selected-coins (selected-returns-benchmark-coins state)
        options (benchmark-selector-options state)
        selected-options (selected-benchmark-options options selected-coins)
        search-query (get-in state [:ui :local-state :portfolio-returns-benchmark-search])
        available-options (if (>= (count selected-coins) 4)
                            []
                            (->> options
                                 (remove (fn [opt] (some #(= (:value opt) (:value %)) selected-options)))
                                 (filter #(benchmark-option-matches-search? % search-query))
                                 (take 20)
                                 vec))]
    {:selected-options selected-options
     :available-options available-options
     :search-query (or search-query "")
     :max-reached? (>= (count selected-coins) 4)}))

(defn reset-portfolio-vm-cache!
  []
  (reset! eligible-vault-benchmark-rows-cache {:signature "" :rows []})
  (reset! benchmark-selector-options-cache {:markets-signature constants/empty-benchmark-markets-signature
                                            :vaults-signature ""
                                            :options []}))