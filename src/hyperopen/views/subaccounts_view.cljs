(ns hyperopen.views.subaccounts-view
  (:require ["lucide/dist/esm/icons/copy.js" :default lucide-copy-node]
            [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.domain.availability :as funding-availability]
            [hyperopen.funding.domain.spot-tokens :as spot-tokens]
            [hyperopen.subaccounts.actions :as subaccounts-actions]
            [hyperopen.views.subaccounts-view.management :as management]))

(def ^:private usd-formatter
  (js/Intl.NumberFormat.
   "en-US"
   #js {:style "currency"
        :currency "USD"
        :minimumFractionDigits 2
        :maximumFractionDigits 2}))

(def ^:private usdc-amount-formatter
  (js/Intl.NumberFormat.
   "en-US"
   #js {:minimumFractionDigits 0
        :maximumFractionDigits 6}))

(defn- normalize-address
  [value]
  (account-context/normalize-address value))

(defn- row-address
  [row]
  (normalize-address
   (or (:sub-account-user row)
       (:subAccountUser row)
       (get row "subAccountUser")
       (get row "sub-account-user"))))

(defn- row-name
  [row]
  (let [name* (some-> (or (:name row)
                          (get row "name"))
                      str
                      str/trim)]
    (if (seq name*) name* "Unnamed")))

(defn- nested-value
  [m paths]
  (some (fn [path]
          (let [value (get-in m path)]
            (when (some? value) value)))
        paths))

(defn- clearinghouse-state
  [row]
  (or (:clearinghouse-state row)
      (:clearinghouseState row)
      (get row "clearinghouseState")
      (get row "clearinghouse-state")))

(defn- spot-state
  [row]
  (or (:spot-state row)
      (:spotState row)
      (get row "spotState")
      (get row "spot-state")))

(defn- parse-number
  [value]
  (let [parsed (cond
                 (number? value) value
                 (string? value) (js/Number value)
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed))
               (js/isFinite parsed))
      parsed)))

(defn- account-value
  [row]
  (parse-number
   (nested-value (clearinghouse-state row)
                 [[:marginSummary :accountValue]
                  [:margin-summary :account-value]
                  ["marginSummary" "accountValue"]
                  ["margin-summary" "account-value"]])))

(defn- spot-account-value
  [row]
  (let [balances (or (:balances (spot-state row))
                     (get (spot-state row) "balances"))]
    (some (fn [balance]
            (let [coin (or (:coin balance) (get balance "coin"))
                  total (or (:total balance) (get balance "total"))]
              (when (= "USDC" (some-> coin str str/upper-case))
                (parse-number total))))
          balances)))

(defn- format-usd
  [value]
  (if-let [value* (parse-number value)]
    (.format usd-formatter value*)
    "--"))

(defn- format-usdc-amount
  [value]
  (if-let [value* (parse-number value)]
    (.format usdc-amount-formatter value*)
    "--"))

(defn- balance-coin
  [balance]
  (some-> (or (:coin balance) (get balance "coin")) str str/trim))

(defn- balance-available
  [balance]
  (let [total (parse-number (or (:total balance)
                                (:totalBalance balance)
                                (get balance "total")
                                (get balance "totalBalance")))
        hold (or (parse-number (or (:hold balance)
                                   (get balance "hold")))
                 0)]
    (when total
      (max 0 (- total hold)))))

(defn- transfer-asset-row
  "One selectable asset. `:token` is the `NAME:0x<hash>` wire token id
   Hyperliquid needs, never the numeric index the balance row carries; a row
   that spot metadata cannot name is still listed with its balance but marked
   `:unresolved?` so the dialog can disable it instead of signing a rejected
   identifier.

   Returns nil for a row that is not a spot token at all: one that carries no
   token identifier *and* that metadata cannot name. Hyperliquid returns outcome
   (prediction-market) positions inside `spotClearinghouseState` alongside real
   spot balances — coins like `o458`, with no `token` field and no `spotMeta`
   entry. They can never travel the spot `sendAsset` path, so offering them
   disabled with a \"refresh balances\" hint would promise a recovery that cannot
   happen.

   Both halves of that test matter. A row carrying an identifier that cannot be
   resolved right now stays listed and disabled, because that failure really is
   transient. And a row missing its index but still nameable — USDC, which has a
   known mainnet id — stays listed too."
  [resolver balance]
  (when-let [coin (balance-coin balance)]
    (let [raw-token (or (:token balance) (get balance "token"))
          token (spot-tokens/resolve-with resolver
                                          {:coin coin
                                           :token raw-token})]
      (when (or (some? raw-token) (some? token))
        (let [available (or (balance-available balance) 0)]
          {:symbol coin
           :token (or token coin)
           :unresolved? (nil? token)
           :available available
           :available-display (format-usdc-amount available)})))))

(defn- spot-transfer-assets
  [state spot-state]
  (let [balances (or (:balances spot-state)
                     (get spot-state "balances"))
        resolver (spot-tokens/resolver state)]
    (or (seq (keep #(transfer-asset-row resolver %) balances))
        [{:symbol "USDC"
          :token (spot-tokens/usdc-wire-token-id state)
          :available 0
          :available-display "0"}])))

(defn- transfer-assets
  "Assets the Send Tokens dialog can offer.

   A unified (portfolio-margin) master pools spot and perps collateral and
   moves funds through the spot `sendAsset` path, so it is spot-kind and gets
   the full per-token balance list. Only a classic master's `:trading` kind is
   USDC-only, because perps collateral genuinely is USDC."
  [{:keys [state subaccounts active-transfer owner-spot-state deposit-max-value withdraw-max-value deposit-max withdraw-max unified-account?]}]
  (let [direction (or (:transfer-direction subaccounts) :deposit)
        account-kind (if unified-account?
                       :spot
                       (or (:transfer-account subaccounts) :trading))
        withdrawing? (= :withdraw direction)]
    (if (= :spot account-kind)
      (spot-transfer-assets
       state
       (if withdrawing?
         (spot-state active-transfer)
         owner-spot-state))
      [{:symbol "USDC"
        :token "USDC"
        :available (if withdrawing? withdraw-max-value deposit-max-value)
        :available-display (if withdrawing? withdraw-max deposit-max)}])))

(defn- unified-account-mode?
  [state]
  (account-context/subaccounts-owner-unified? state))

(defn- owner-snapshot
  [state owner-address]
  (let [snapshot (get-in state [:account-context :subaccounts :owner-snapshot])]
    (when (and (map? snapshot)
               (= owner-address (normalize-address (:owner snapshot))))
      snapshot)))

(defn- account-source-state
  [{:keys [mode clearinghouse-state spot-state]}]
  {:account {:mode mode}
   :spot {:clearinghouse-state spot-state}
   :webdata2 {:clearinghouseState clearinghouse-state}})

(defn- source-trading-transfer-max
  [{:keys [unified-account? clearinghouse-state spot-state]}]
  (let [source-state (account-source-state
                      {:mode (if unified-account? :unified :classic)
                       :clearinghouse-state clearinghouse-state
                       :spot-state spot-state})]
    (if unified-account?
      (or (funding-availability/spot-usdc-available source-state) 0)
      (funding-availability/perps-withdrawable source-state))))

(defn- lucide-node->hiccup
  [node]
  (let [tag-name (aget node 0)
        attrs (js->clj (aget node 1) :keywordize-keys true)]
    [(keyword tag-name) attrs]))

(defn- copy-icon
  []
  (into [:svg {:class ["h-3.5" "w-3.5"]
               :viewBox "0 0 24 24"
               :fill "none"
               :stroke "currentColor"
               :stroke-width 1.8
               :stroke-linecap "round"
               :stroke-linejoin "round"
               :aria-hidden true}]
        (map lucide-node->hiccup
             (array-seq lucide-copy-node))))

(defn- action-button
  [{:keys [data-role label on-click active? disabled?]}]
  [:button {:type "button"
            :data-role data-role
            :disabled (boolean disabled?)
            :class (into ["inline-flex"
                          "h-8"
                          "items-center"
                          "justify-center"
                          "rounded-md"
                          "border"
                          "px-2.5"
                          "text-xs"
                          "font-medium"
                          "leading-none"
                          "transition-colors"]
                         (cond
                           active?
                           ["border-[#2dceb3]" "bg-[#0f3a35]" "text-ho-accent-bright"]

                           disabled?
                           ["cursor-not-allowed" "border-base-300" "bg-base-200/30" "text-trading-text-secondary"]

                           :else
                           ["border-base-300" "bg-[#121d20]" "text-white" "hover:border-[#2dceb3]/60" "hover:bg-[#172528]"]))
            :on {:click on-click}}
   label])

(defn- table-header
  [labels]
  [:thead {:class ["text-left" "text-xs" "text-[#8b999d]"]}
   [:tr
    (for [label labels]
      ^{:key label}
      [:th {:class ["px-3" "py-2.5" "font-medium"]} label])]])

(defn- section-heading
  [title]
  [:h2 {:class ["px-3" "pb-1" "pt-3" "text-sm" "font-semibold" "text-white"]}
   title])

(defn- address-cell
  [address role]
  [:div {:class ["flex" "min-w-0" "items-center" "gap-2" "whitespace-nowrap"]}
   [:span {:class ["num"
                   "block"
                   "max-w-[16rem]"
                   "truncate"
                   "font-medium"
                   "text-white"
                   "md:max-w-none"]
           :title address}
    address]
   (when (seq address)
     [:button {:type "button"
               :data-role role
               :title "Copy address"
               :aria-label "Copy address"
               :class ["inline-flex" "h-5" "w-5" "items-center" "justify-center" "text-[#48dbc8]" "hover:text-white"]
               :on {:click [[:actions/copy-subaccount-address address]]}}
      (copy-icon)])])

(defn- empty-row
  [colspan message]
  [:tr
   [:td {:class ["px-3" "py-8" "text-center" "text-sm" "text-trading-text-secondary"]
         :colSpan colspan}
    message]])

(defn- master-section
  [{:keys [owner-address selected-master? connected? perps-value spot-value read-only?]}]
  [:div
   (section-heading "Master Account")
   [:div {:class ["overflow-x-auto"]}
    [:table {:class ["min-w-[760px]" "w-full" "text-sm"]}
     (table-header ["Name" "Address" "Perps Account Equity" "Spot Account Equity" "Action"])
     [:tbody
      [:tr {:data-role "subaccounts-master-row"
            :class ["transition-colors" "hover:bg-white/[0.02]"]}
       [:td {:class ["px-3" "py-2.5" "font-semibold" "text-white"]} "Master Account"]
       [:td {:class ["px-3" "py-2.5"]}
        (if connected?
          (address-cell owner-address "subaccounts-copy-master")
          [:span {:class ["text-[#8b999d]"]} "Not connected"])]
       [:td {:class ["px-3" "py-4" "num" "font-medium" "text-white"]} (format-usd perps-value)]
       [:td {:class ["px-3" "py-4" "num" "font-medium" "text-white"]} (format-usd spot-value)]
       [:td {:class ["px-3" "py-4" "text-right"]}
	        (management/trade-button {:data-role "subaccounts-select-master"
	                                  :label "Trade"
	                                  :active? selected-master?
	                                  :disabled? (or read-only? (not connected?))
	                                  :on-click [[:actions/select-master-account]]})]]]]]])

(defn- subaccount-row
  [{:keys [row selected-address subaccounts master-transfer-max read-only?]}]
  (let [address (row-address row)
        name (row-name row)
        perps-value (account-value row)
        spot-value (spot-account-value row)
        selected? (= address selected-address)]
    [:tr {:data-role (str "subaccounts-row-" address)
          :class (into ["border-t" "border-base-300" "transition-colors" "hover:bg-white/[0.025]"]
                       (when selected?
                         ["bg-ho-accent-soft/35"]))}
     [:td {:class ["px-3" "py-4" "align-top" "font-semibold" "text-white"]} name]
     [:td {:class ["px-3" "py-4" "align-top"]}
      (address-cell address (str "subaccounts-copy-" address))]
     [:td {:class ["px-3" "py-4" "align-top" "num" "font-medium" "text-white"]}
      (format-usd perps-value)]
     [:td {:class ["px-3" "py-4" "align-top" "num" "font-medium" "text-white"]}
      (format-usd spot-value)]
     [:td {:class ["px-3" "py-4" "align-top" "text-right"]}
      [:div {:class ["flex" "justify-end" "gap-2"]}
	       (management/trade-button {:data-role (str "subaccounts-select-" address)
	                                 :label "Trade"
	                                 :active? selected?
	                                 :disabled? read-only?
	                                 :on-click [[:actions/select-subaccount address]]})
	       (management/row-controls {:address address
	                                 :subaccounts subaccounts
	                                 :read-only? read-only?})]]]))

(defn- active-transfer-row
  [rows active-address]
  (when (seq active-address)
    (some (fn [row]
            (when (= active-address (row-address row))
              row))
          rows)))

(defn- subaccounts-section
  [{:keys [rows selected-address status error subaccounts master-transfer-max read-only?]}]
  [:div
   (section-heading "Sub-Accounts")
   (when (seq error)
     [:div {:class ["mx-3" "mb-2" "rounded-md" "border" "border-red-500/30" "bg-red-500/10" "px-3" "py-2" "text-sm" "text-red-200"]}
      error])
   [:div {:class ["overflow-x-auto"]}
    [:table {:class ["min-w-[960px]" "w-full" "text-sm"]}
     (table-header ["Name" "Address" "Perps Account Equity" "Spot Account Equity" "Action"])
     (cond
       (and (= :loading status) (empty? rows))
       [:tbody (empty-row 5 "Loading subaccounts...")]

       (empty? rows)
       [:tbody (empty-row 5 "No subaccounts found for this master account.")]

       :else
       (into [:tbody]
             (map (fn [row]
                    ^{:key (row-address row)}
	                    (subaccount-row {:row row
	                                     :selected-address selected-address
	                                     :master-transfer-max master-transfer-max
	                                     :subaccounts subaccounts
	                                     :read-only? read-only?}))
	                  rows)))]]])

(defn subaccounts-view
  [state]
  (let [owner-address (subaccounts-actions/viewed-master-address state)
        connected? (seq owner-address)
        read-only-message (account-context/mutations-blocked-message state)
        read-only? (some? read-only-message)
        subaccounts (get-in state [:account-context :subaccounts])
        rows (vec (or (:rows subaccounts) []))
        selected-address (account-context/selected-subaccount-address state)
        selected-master? (nil? selected-address)
        status (:status subaccounts)
        refreshing? (true? (:refreshing? subaccounts))
        error (:error subaccounts)
        active-transfer (when-not read-only?
                          (active-transfer-row rows (:transferring-address subaccounts)))
        unified-account? (unified-account-mode? state)
        owner-snapshot* (owner-snapshot state owner-address)
        owner-clearinghouse-state (:clearinghouse-state owner-snapshot*)
        owner-spot-state (:spot-state owner-snapshot*)
        master-row {:clearinghouse-state owner-clearinghouse-state
                    :spot-state owner-spot-state}
        master-perps-value (account-value master-row)
        master-spot-value (spot-account-value master-row)
        master-transfer-max-value (source-trading-transfer-max
                                   {:unified-account? unified-account?
                                    :clearinghouse-state owner-clearinghouse-state
                                    :spot-state owner-spot-state})]
    [:div {:class ["app-shell-gutter" "flex" "min-h-[calc(100vh-4rem)]" "w-full" "flex-col" "gap-5" "pt-8" "pb-16"]
           :style {:background-color "#061b20"
                   :background-image "radial-gradient(120% 80% at 15% -10%, rgba(0, 148, 111, 0.24), rgba(6, 30, 34, 0.04) 55%, transparent 70%), radial-gradient(circle at 15% 0%, rgba(0, 212, 170, 0.10), transparent 35%), radial-gradient(circle at 85% 100%, rgba(0, 212, 170, 0.08), transparent 40%)"
                   :background-repeat "no-repeat"}
           :data-parity-id "subaccounts-root"}
     [:div {:class ["mx-auto" "flex" "w-full" "max-w-[82rem]" "flex-col" "gap-5"]}
      [:section {:class ["flex" "flex-col" "gap-4" "md:flex-row" "md:items-center" "md:justify-between"]}
       [:h1 {:class ["text-[2rem]" "font-semibold" "leading-tight" "text-white" "sm:text-[2.5rem]"]}
        "Sub-Accounts"]
       [:div {:class ["flex" "w-full" "justify-end" "gap-2" "sm:w-auto"]}
        (action-button {:data-role "subaccounts-refresh"
                        :label (if (or (= :loading status) refreshing?) "Refreshing..." "Refresh")
                        :disabled? (or (not connected?) refreshing?)
                        :on-click [[:actions/refresh-subaccounts]]})
       (management/create-panel {:subaccounts subaccounts
                                  :connected? (boolean connected?)
                                  :read-only? read-only?})]]
      (when read-only-message
        [:div {:class ["rounded-md"
                       "border"
                       "border-[#294145]"
                       "bg-[#0b171b]"
                       "px-3"
                       "py-2"
                       "text-sm"
                       "text-[#d6dddf]"]
               :data-role "subaccounts-read-only-message"}
         read-only-message])
      [:section {:class ["overflow-hidden" "rounded-lg" "bg-[#0d171a]" "shadow-[0_18px_60px_rgba(0,0,0,0.18)]"]
                 :data-role "subaccounts-console"}
       (master-section {:owner-address owner-address
                        :selected-master? selected-master?
                        :connected? connected?
                        :perps-value master-perps-value
                        :spot-value master-spot-value
                        :read-only? read-only?})
       (subaccounts-section {:rows rows
                             :selected-address selected-address
                             :status status
                             :error error
                             :master-transfer-max master-transfer-max-value
                             :subaccounts subaccounts
                             :read-only? read-only?})]
      (when active-transfer
        (let [address (row-address active-transfer)
              subaccount-transfer-max-value
              (source-trading-transfer-max
               {:unified-account? unified-account?
                :clearinghouse-state (clearinghouse-state active-transfer)
                :spot-state (spot-state active-transfer)})
              deposit-max (format-usdc-amount master-transfer-max-value)
              withdraw-max (format-usdc-amount subaccount-transfer-max-value)]
          (management/transfer-popover-layer
           {:address address
            :subaccount-name (row-name active-transfer)
            :deposit-max deposit-max
            :withdraw-max withdraw-max
            :transfer-assets (transfer-assets {:state state
                                               :subaccounts subaccounts
                                               :active-transfer active-transfer
                                               :owner-spot-state owner-spot-state
                                               :deposit-max-value master-transfer-max-value
                                               :withdraw-max-value subaccount-transfer-max-value
                                               :deposit-max deposit-max
                                               :withdraw-max withdraw-max
                                               :unified-account? unified-account?})
            :unified-account? unified-account?
            :subaccounts subaccounts})))]]))

(defn ^:export route-view
  [state]
  (subaccounts-view state))

(goog/exportSymbol "hyperopen.views.subaccounts_view.route_view" route-view)
