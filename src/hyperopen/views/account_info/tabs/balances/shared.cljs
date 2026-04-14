(ns hyperopen.views.account-info.tabs.balances.shared
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]))

(def ^:private unified-available-balance-tooltip-suffix
  " is available to withdraw or transfer. Some perps may have a larger available to trade amount, which can be seen in the order form for that asset.")

(def ^:private balance-contract-explorer-token-base-url
  "https://app.hyperliquid.xyz/explorer/token/")

(def ^:private external-link-button-classes
  ["inline-flex"
   "h-6"
   "w-6"
   "shrink-0"
   "items-center"
   "justify-center"
   "rounded"
   "transition-opacity"
   "hover:opacity-80"
   "focus:outline-none"
   "focus:ring-0"
   "focus:ring-offset-0"
   "focus-visible:outline-none"
   "focus-visible:ring-2"
   "focus-visible:ring-trading-green/70"
   "focus-visible:ring-offset-1"
   "focus-visible:ring-offset-base-100"])

(def ^:private balance-row-action-button-classes
  ["inline-flex"
   "min-h-6"
   "w-full"
   "justify-start"
   "appearance-none"
   "border-0"
   "bg-transparent"
   "p-0"
   "font-medium"
   "text-trading-text"
   "transition-colors"
   "hover:bg-transparent"
   "hover:text-[#7fffe4]"
   "focus:outline-none"
   "focus:ring-0"
   "focus:ring-offset-0"
   "focus:shadow-none"
   "focus-visible:outline-none"
   "focus-visible:ring-0"
   "focus-visible:ring-offset-0"
   "focus-visible:text-[#7fffe4]"
   "focus-visible:underline"
   "underline-offset-2"
   "whitespace-nowrap"])

(defn normalize-balances-options
  [options]
  (cond
    (and (map? options)
         (or (contains? options :mobile-expanded-card)
             (contains? options :read-only?)
             (contains? options :read-only-message)))
    (merge {:mobile-expanded-card {}}
           options)

    (map? options)
    {:mobile-expanded-card options}

    :else
    {:mobile-expanded-card {}}))

(defn- unified-available-balance-tooltip-text [coin available-balance amount-decimals]
  (str (shared/format-balance-amount available-balance amount-decimals)
       " "
       (or coin "USDC")
       unified-available-balance-tooltip-suffix))

(defn available-balance-value-node
  [{:keys [coin
           unit-label
           available-balance
           amount-decimals
           transfer-disabled?
           tooltip-position]}]
  (let [display-unit-label (or unit-label coin "USDC")
        value-text (str (shared/format-balance-amount available-balance amount-decimals)
                        " "
                        display-unit-label)]
    (if transfer-disabled?
      (let [position (or tooltip-position :top)
            panel-position-classes (case position
                                     :bottom ["top-full" "mt-2"]
                                     ["bottom-full" "mb-2"])
            caret-position-classes (case position
                                     :bottom ["bottom-full" "border-b-gray-800"]
                                     ["top-full" "border-t-gray-800"])]
        [:div {:class ["group" "relative" "inline-flex" "min-h-6" "items-center" "justify-start"]}
         [:span {:class ["cursor-help" "rounded" "underline" "decoration-dashed" "underline-offset-2" "focus-visible:outline-none" "focus-visible:ring-2" "focus-visible:ring-trading-green/70" "focus-visible:ring-offset-1" "focus-visible:ring-offset-base-100"]
                 :tab-index 0}
          value-text]
         [:div {:class (into ["pointer-events-none" "absolute" "left-1/2" "-translate-x-1/2" "z-[120]" "opacity-0" "transition-opacity" "duration-200" "group-hover:opacity-100" "group-focus-within:opacity-100"]
                             panel-position-classes)}
          [:div {:class ["relative" "w-[520px]" "max-w-[calc(100vw-2rem)]" "min-w-[320px]" "rounded-md" "bg-gray-800" "px-3" "py-1.5" "text-xs" "leading-tight" "text-left" "text-gray-100" "spectate-lg" "whitespace-normal"]}
           (unified-available-balance-tooltip-text display-unit-label available-balance amount-decimals)
           [:div {:class (into ["absolute" "left-1/2" "-translate-x-1/2" "h-0" "w-0" "border-4" "border-transparent"]
                              caret-position-classes)}]]]])
      value-text)))

(defn normalize-balance-contract-id [contract-id]
  (projections/normalize-balance-contract-id contract-id))

(defn- abbreviate-contract-id [contract-id]
  (when-let [contract-id* (normalize-balance-contract-id contract-id)]
    (if (> (count contract-id*) 10)
      (let [prefix-len (if (str/starts-with? contract-id* "0x") 6 4)
            safe-prefix-len (min prefix-len (count contract-id*))]
        (str (subs contract-id* 0 safe-prefix-len)
             "..."
             (subs contract-id* (- (count contract-id*) 4))))
      contract-id*)))

(defn- balance-contract-explorer-url [contract-id]
  (when-let [contract-id* (normalize-balance-contract-id contract-id)]
    (str balance-contract-explorer-token-base-url contract-id*)))

(defn- external-link-button [href aria-label tone-classes]
  [:a {:href href
       :target "_blank"
       :rel "noopener noreferrer"
       :aria-label aria-label
       :title aria-label
       :class (into external-link-button-classes tone-classes)}
   (shared/external-link-icon ["h-3.5" "w-3.5" "shrink-0"] {:stroke-width 2})])

(defn balance-contract-node [contract-id]
  (let [display-contract-id (abbreviate-contract-id contract-id)]
    (when-let [explorer-url (balance-contract-explorer-url contract-id)]
      [:span {:class ["inline-flex"
                      "min-w-0"
                      "min-h-6"
                      "items-center"
                      "gap-1"
                      "whitespace-nowrap"
                      "text-trading-text"]}
       [:span {:class ["truncate"]} display-contract-id]
       (external-link-button explorer-url
                             (str "Open contract " display-contract-id " in Hyperliquid Explorer")
                             ["text-trading-green"])])))

(defn balance-pnl-node
  [{:keys [coin selection-coin pnl-value pnl-pct contract-id]}]
  (if-let [pnl-text (shared/format-pnl-text pnl-value pnl-pct)]
    (let [tone-class (shared/pnl-tone-class pnl-value)
          explorer-url (balance-contract-explorer-url contract-id)
          asset-label (or selection-coin coin "asset")]
      [:span {:class ["inline-flex"
                      "min-h-6"
                      "items-center"
                      "justify-start"
                      "gap-1"
                      "whitespace-nowrap"
                      tone-class]}
       [:span {:class ["num"]} pnl-text]
       (when explorer-url
         (external-link-button explorer-url
                               (str "Open " asset-label " in Hyperliquid Explorer")
                               [tone-class]))])
    [:span {:class ["text-trading-text"]} "--"]))

(defn balance-coin-display [{:keys [coin selection-coin]}]
  (let [{:keys [base-label prefix-label]}
        (shared/resolve-coin-display (or selection-coin coin) {})]
    {:base-label (or base-label coin "Asset")
     :prefix-label prefix-label}))

(defn balance-coin-node [{:keys [base-label prefix-label]}]
  [:span {:class ["flex" "min-w-0" "items-center" "gap-1"]}
   [:span {:class ["truncate"]} base-label]
   (when prefix-label
     [:span {:class shared/position-chip-classes}
      prefix-label])])

(defn balance-amount-cell [amount amount-decimals unit-label]
  [:div {:class ["text-left" "font-semibold" "num" "whitespace-nowrap"]}
   [:span {:class ["num"]}
    (shared/format-balance-amount amount amount-decimals)]
   " "
   [:span unit-label]])

(defn usdc-balance-row? [row]
  (str/starts-with? (or (:coin row) "") "USDC"))

(defn- balance-sort-value [column row]
  (case column
    "Coin" (or (:coin row) "")
    "Total Balance" (shared/parse-num (:total-balance row))
    "Available Balance" (shared/parse-num (:available-balance row))
    "USDC Value" (shared/parse-num (:usdc-value row))
    "PNL (ROE %)" (shared/parse-num (:pnl-value row))
    0))

(defn- compare-balance-rows [column direction row-a row-b]
  (let [value-a (balance-sort-value column row-a)
        value-b (balance-sort-value column row-b)
        primary-cmp (if (= direction :desc)
                      (compare value-b value-a)
                      (compare value-a value-b))]
    (if (zero? primary-cmp)
      (let [coin-cmp (compare (or (:coin row-a) "")
                              (or (:coin row-b) ""))]
        (if (zero? coin-cmp)
          (compare (or (:key row-a) "")
                   (or (:key row-b) ""))
          coin-cmp))
      primary-cmp)))

(defn sort-balances-by-column [rows column direction]
  (let [[usdc-rows non-usdc-rows]
        (reduce (fn [[usdc* non-usdc*] row]
                  (if (usdc-balance-row? row)
                    [(conj usdc* row) non-usdc*]
                    [usdc* (conj non-usdc* row)]))
                [[] []]
                rows)
        compare-rows (partial compare-balance-rows column direction)]
    (->> (concat (sort compare-rows usdc-rows)
                 (sort compare-rows non-usdc-rows))
         vec)))

(defn- balance-matches-coin-search?
  [row query]
  (let [coin (:coin row)
        selection-coin (:selection-coin row)
        base-selection-coin (some-> selection-coin
                                    shared/parse-coin-namespace
                                    :base)]
    (or (shared/coin-matches-search? coin query)
        (shared/coin-matches-search? selection-coin query)
        (shared/coin-matches-search? base-selection-coin query))))

(defn filter-balances-by-coin-search
  [rows coin-search]
  (let [rows* (or rows [])
        query (shared/normalize-coin-search-query coin-search)]
    (if (str/blank? query)
      (vec rows*)
      (->> rows*
           (filterv #(balance-matches-coin-search? % query))))))

(defn send-enabled?
  [{:keys [key selection-coin coin available-balance]}]
  (let [row-key (some-> key str str/trim)]
    (and (seq (shared/non-blank-text (or selection-coin coin)))
         (number? (shared/parse-num available-balance))
         (pos? (shared/parse-num available-balance))
         (not (#{"perps-usdc" "unified-usdc-fallback"} row-key)))))

(defn send-action-context
  [{:keys [coin selection-coin available-balance amount-decimals]}]
  (let [{:keys [base-label prefix-label]}
        (shared/resolve-coin-display (or selection-coin coin) {})
        max-display (shared/format-balance-amount available-balance amount-decimals)]
    {:token (or selection-coin coin)
     :symbol (or base-label coin "Asset")
     :prefix-label prefix-label
     :max-amount available-balance
     :max-display max-display
     :max-input max-display}))

(defn balance-row-action-button
  ([label]
   (balance-row-action-button label nil))
  ([label action]
   [:button {:class balance-row-action-button-classes
             :type "button"
             :on (when action {:click [action]})}
    label]))

(defn balance-row-disabled-action [label]
  [:span {:class ["inline-flex"
                  "min-h-6"
                  "items-center"
                  "text-xs"
                  "text-trading-text-secondary"]}
   label])
