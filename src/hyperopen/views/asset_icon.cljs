(ns hyperopen.views.asset-icon
  (:require [clojure.string :as str]))

(def ^:private hyperliquid-coin-icon-base-url
  "https://app.hyperliquid.xyz/coins/")

;; Entropy (the `io` HIP-3 deployer) hosts logos keyed by URL-encoded coin,
;; including markets Hyperliquid's CDN lacks (io:PRL, io:DRAM, ...). Public:
;; CORS *, cross-origin resource policy, and a real 404 when absent.
(def ^:private entropy-logo-base-url
  "https://assets.entropy.trade/logos/")

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- spot-icon-key
  [coin]
  (let [coin* (non-blank-text coin)]
    (when (and coin*
               (str/includes? coin* "/"))
      (let [[base _quote] (str/split coin* #"/" 2)
            base* (non-blank-text base)]
        (when base*
          (str base* "_spot"))))))

(defn- spot-market?
  [coin symbol market-type]
  (or (= :spot market-type)
      (and (nil? market-type)
           (some-> coin non-blank-text (str/starts-with? "@")))
      (some-> coin non-blank-text (str/includes? "/"))
      (some-> symbol non-blank-text (str/includes? "/"))))

(defn- spot-candidate-icon-key
  [coin symbol base]
  (or (spot-icon-key coin)
      (spot-icon-key symbol)
      (some-> base non-blank-text (str "_spot"))))

(def ^:private icon-instrument-prefixes
  #{"hip3" "hl" "perp" "spot"})

(defn- strip-icon-instrument-prefixes
  [icon-key]
  (loop [icon-key* icon-key]
    (let [[prefix suffix] (str/split icon-key* #":" 2)]
      (if (and suffix
               (contains? icon-instrument-prefixes prefix))
        (recur suffix)
        icon-key*))))

(defn- normalize-icon-key
  [icon-key]
  (let [icon-key* (non-blank-text icon-key)]
    (when icon-key*
      (let [icon-key* (strip-icon-instrument-prefixes icon-key*)]
        (if (and (str/starts-with? icon-key* "k")
                 (not (str/starts-with? icon-key* "km:")))
          (subs icon-key* 1)
          icon-key*)))))

(def ^:private icon-key-aliases
  {"abcd:USA500" "cash:USA500"
   "cash:GOLD" "xyz:GOLD"
   "cash:INTC" "xyz:INTC"
   "cash:MSFT" "xyz:MSFT"
   "cash:SILVER" "xyz:SILVER"
   "flx:BTC" "BTC"
   "flx:GAS" "GAS"
   "flx:PALLADIUM" "xyz:PALLADIUM"
   "flx:PLATINUM" "xyz:PLATINUM"
   "hyna:ADA" "ADA"
   "hyna:BCH" "BCH"
   "hyna:BNB" "BNB"
   "hyna:DOGE" "DOGE"
   "hyna:ENA" "ENA"
   "hyna:FARTCOIN" "FARTCOIN"
   "hyna:IP" "IP"
   "hyna:LINK" "LINK"
   "hyna:LIT" "LIT"
   "hyna:LTC" "LTC"
   "hyna:PUMP" "PUMP"
   "hyna:SUI" "SUI"
   "hyna:XMR" "XMR"
   "hyna:XPL" "XPL"
   "km:AAPL" "xyz:AAPL"
   "km:EUR" "xyz:EUR"
   "km:GOLD" "xyz:GOLD"
   "km:GOOGL" "xyz:GOOGL"
   "km:MU" "xyz:MU"
   "km:NVDA" "xyz:NVDA"
   "km:SILVER" "xyz:SILVER"
   "xyz:COPPER" "flx:COPPER"})

(defn- alias-icon-key
  [icon-key]
  (or (get icon-key-aliases icon-key)
      icon-key))

(defn- outcome-candidate-icon-key
  [underlying-for-icon underlying base]
  (or (non-blank-text underlying-for-icon)
      (non-blank-text underlying)
      (non-blank-text base)))

(defn- dex-candidate-icon-key
  [dex base]
  (let [dex* (non-blank-text dex)
        base* (non-blank-text base)]
    (when (and dex* base*)
      (str dex* ":" base*))))

(defn market-icon-key
  [{:keys [coin symbol base market-type underlying underlying-for-icon dex]}]
  (let [coin* (non-blank-text coin)
        symbol* (non-blank-text symbol)
        base* (non-blank-text base)
        outcome? (= :outcome market-type)
        spot? (spot-market? coin* symbol* market-type)
        candidate (or (when outcome?
                        (outcome-candidate-icon-key underlying-for-icon underlying base*))
                      (when spot?
                        (spot-candidate-icon-key coin* symbol* base*))
                      (when-not outcome?
                        (dex-candidate-icon-key dex base*))
                      (when-not (or outcome?
                                    (str/starts-with? (or coin* "") "@"))
                        coin*)
                      base*)
        normalized (normalize-icon-key candidate)
        aliased (some-> normalized alias-icon-key)]
    (when (and aliased
               (not (str/starts-with? aliased "@")))
      aliased)))

(defn market-icon-url
  [market]
  (when-let [icon-key (market-icon-key market)]
    (str hyperliquid-coin-icon-base-url icon-key ".svg")))

(defn market-icon-fallback-url
  "Second-choice icon for a perp whose Hyperliquid icon is missing. Nil for
  spot and outcome markets, which Entropy does not host."
  [{:keys [coin symbol market-type] :as market}]
  (when-not (or (= :outcome market-type)
                (spot-market? (non-blank-text coin) (non-blank-text symbol) market-type))
    (when-let [icon-key (market-icon-key market)]
      (str entropy-logo-base-url (js/encodeURIComponent icon-key) ".svg"))))

(defn fallback-icon-status-key
  "Loaded/missing status key for the fallback icon, kept beside the primary
  key in the asset selector's icon sets."
  [market-key]
  (when (seq market-key)
    (str market-key "#entropy")))
