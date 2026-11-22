(ns hyperopen.account-type.options
  (:require [clojure.string :as str]))

(def option-order
  [:unified-account :portfolio-margin :manual])

(def ^:private option-entries
  {:unified-account
   {:abstraction "unifiedAccount"
    :title "Unified Account"
    :recommended? true
    :description "The default account setting where each collateral asset has a separate balance. Perps can only use the settlement asset as collateral, and margining is only shared across cross margin assets with the same collateral asset."}

   :portfolio-margin
   {:abstraction "portfolioMargin"
    :title "Portfolio Margin"
    :description "For advanced users who want greater flexibility and capital efficiency. All trading is unified across spot and perps. Eligible collateral assets such as HYPE and BTC can be used directly as collateral for perp positions without converting to the settlement asset. Collateral assets earn or pay interest depending on whether they are at rest or actively borrowed to open positions."}

   :manual
   {:abstraction "disabled"
    :title "Manual"
    :description "Only recommended for automated traders. All DEXs have separate balances and cross margin applies separately within each DEX."}})

(defn options
  []
  option-order)

(defn valid-option?
  [option]
  (boolean (get option-entries option)))

(defn option->abstraction
  [option]
  (:abstraction (get option-entries option)))

(defn abstraction->option
  "Maps a raw `userAbstraction` payload to the modal option it belongs to.
   Every non-unified, non-portfolio-margin payload (\"default\", \"disabled\",
   \"dexAbstraction\", unknown, nil) is the Manual/standard account type."
  [raw-abstraction]
  (case (some-> raw-abstraction str str/trim)
    "unifiedAccount" :unified-account
    "portfolioMargin" :portfolio-margin
    :manual))

(defn title
  [option]
  (:title (get option-entries option)))

(defn description
  [option]
  (:description (get option-entries option)))

(defn recommended?
  [option]
  (boolean (:recommended? (get option-entries option))))

(defn chip-label
  "Short label for the order form account type chip, mirroring Hyperliquid."
  [option]
  (case option
    :unified-account "Unified"
    :portfolio-margin "PM"
    "Manual"))
