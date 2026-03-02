(ns hyperopen.views.funding-modal
  (:require [clojure.string :as str]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.views.asset-icon :as asset-icon]))

(defn- base-button-classes
  [primary?]
  (if primary?
    ["rounded-lg"
     "border"
     "border-[#2f625a]"
     "bg-[#0d3a35]"
     "px-3.5"
     "py-2"
     "text-sm"
     "font-medium"
     "text-[#daf3ef]"
     "hover:border-[#3f7f75]"
     "hover:bg-[#115046]"]
    ["rounded-lg"
     "border"
     "border-[#2c4b50]"
     "bg-transparent"
     "px-3.5"
     "py-2"
     "text-sm"
     "text-[#b7c8cc]"
     "hover:border-[#3d666b]"
     "hover:text-[#e5eef1]"]))

(defn- submit-button-classes
  [disabled?]
  (if disabled?
    ["rounded-lg"
     "border"
     "border-[#2a4b4b]"
     "bg-[#08202a]/55"
     "px-3.5"
     "py-2"
     "text-sm"
     "font-medium"
     "text-[#6c8e93]"
     "cursor-not-allowed"]
    (base-button-classes true)))

(defn- deposit-asset-icon
  [symbol icon-src]
  [:div {:class ["h-8" "w-8" "shrink-0" "overflow-hidden" "rounded-full"]}
   (if (seq icon-src)
     [:img {:class ["block" "h-8" "w-8" "rounded-full" "object-cover"]
            :src icon-src
            :alt (str symbol " icon")}]
     [:div {:class ["h-8"
                    "w-8"
                    "rounded-full"
                    "bg-[#1e5a93]"
                    "text-xs"
                    "font-semibold"
                    "text-white"
                    "flex"
                    "items-center"
                    "justify-center"]}
      symbol])])

(defn- deposit-asset-button
  [asset selected?]
  (let [icon-src (asset-icon/market-icon-url {:coin (:symbol asset)
                                               :symbol (:symbol asset)})]
    [:button {:type "button"
              :class (into ["w-full"
                            "rounded-lg"
                            "border"
                            "px-3"
                            "py-2.5"
                            "text-left"
                            "transition-colors"
                            "duration-150"]
                           (if selected?
                             ["border-[#3a5b6a]" "bg-[#1a2a37]"]
                             ["border-transparent" "bg-transparent" "hover:bg-[#10222f]"]))
              :on {:click [[:actions/set-funding-modal-field
                            [:deposit-selected-asset-key]
                            (:key asset)]]}}
     [:div {:class ["flex" "items-center" "gap-2.5"]}
      (deposit-asset-icon (:symbol asset) icon-src)
      [:div {:class ["flex" "min-w-0" "flex-col"]}
       [:span {:class ["text-sm" "font-semibold" "text-[#e6eef2]"]} (:symbol asset)]
       [:span {:class ["text-xs" "text-[#7e95a0]"]} (:network asset)]]]]))

(defn- format-grouped-amount-input
  [value]
  (let [raw (-> (or value "")
                str
                (str/replace #"," "")
                (str/replace #"\s+" ""))]
    (if-not (re-matches #"^\d*(?:\.\d*)?$" raw)
      raw
      (let [[whole fraction] (str/split raw #"\." 2)
            has-decimal? (str/includes? raw ".")
            grouped-whole (if (seq whole)
                            (str/replace whole #"\B(?=(\d{3})+(?!\d))" ",")
                            "")]
        (cond
          (empty? raw)
          ""

          (and (empty? whole) has-decimal?)
          (str "0." (or fraction ""))

          has-decimal?
          (str (if (seq grouped-whole) grouped-whole "0")
               "."
               (or fraction ""))

          :else
          grouped-whole)))))

(defn- titleize-token
  [value fallback]
  (if-let [token (some-> value name (str/replace #"-" " "))]
    (let [words (->> (str/split token #"\s+")
                     (remove str/blank?)
                     (map str/capitalize))]
      (if (seq words)
        (str/join " " words)
        fallback))
    fallback))

(defn- next-check-label
  [next-at-ms now-ms]
  (when (number? next-at-ms)
    (let [remaining-ms (max 0 (- next-at-ms now-ms))
          remaining-seconds (js/Math.ceil (/ remaining-ms 1000))]
      (if (<= remaining-seconds 1)
        "Now"
        (str "In " remaining-seconds "s")))))

(defn funding-modal-view
  [state]
  (let [{:keys [open?
                mode
                legacy-kind
                title
                deposit-step
                deposit-search-input
                deposit-assets
                deposit-selected-asset
                deposit-flow-kind
                deposit-flow-supported?
                deposit-generated-address
                deposit-generated-signatures
                deposit-submit-label
                deposit-quick-amounts
                deposit-min-amount
                deposit-estimated-time
                deposit-network-fee
                withdraw-assets
                withdraw-selected-asset
                withdraw-flow-kind
                withdraw-generated-address
                amount-input
                to-perp?
                destination-input
                hyperunit-lifecycle
                max-display
                max-input
                max-symbol
                submitting?
                submit-disabled?
                status-message
                submit-label
                min-withdraw-amount
                min-withdraw-symbol
                withdraw-estimated-time
                withdraw-network-fee
                hyperunit-fee-estimate-loading?
                hyperunit-fee-estimate-error]} (funding-actions/funding-modal-view-model state)
        deposit? (= mode :deposit)
        deposit-amount-entry? (= deposit-step :amount-entry)
        transfer? (= mode :transfer)
        withdraw? (= mode :withdraw)
        legacy? (= mode :legacy)
        selected-deposit-asset* (or deposit-selected-asset
                                    {:symbol "USDC"
                                     :network "Arbitrum"})
        selected-deposit-icon-src (asset-icon/market-icon-url {:coin (:symbol selected-deposit-asset*)
                                                                :symbol (:symbol selected-deposit-asset*)})
        deposit-flow-kind* (or deposit-flow-kind :unknown)
        selected-deposit-asset-key (:key selected-deposit-asset*)
        selected-withdraw-asset* (or withdraw-selected-asset
                                     {:key :usdc
                                      :symbol "USDC"
                                      :network "Arbitrum"
                                      :flow-kind :bridge2})
        selected-withdraw-asset-key (:key selected-withdraw-asset*)
        selected-withdraw-icon-src (asset-icon/market-icon-url {:coin (:symbol selected-withdraw-asset*)
                                                                 :symbol (:symbol selected-withdraw-asset*)})
        withdraw-flow-kind* (or withdraw-flow-kind :unknown)
        generated-signature-count (if (sequential? deposit-generated-signatures)
                                    (count deposit-generated-signatures)
                                    0)
        lifecycle* (or hyperunit-lifecycle {})
        show-hyperunit-lifecycle? (or (and (= :deposit (:direction lifecycle*))
                                           (= selected-deposit-asset-key (:asset-key lifecycle*)))
                                      (and (= :withdraw (:direction lifecycle*))
                                           (= selected-withdraw-asset-key (:asset-key lifecycle*))))
        lifecycle-state-label (titleize-token (:state lifecycle*)
                                              (if (= :withdraw (:direction lifecycle*))
                                                "Awaiting Hyperliquid Send"
                                                "Awaiting Source Transfer"))
        lifecycle-status-label (titleize-token (:status lifecycle*)
                                               "Pending")
        lifecycle-next-check (next-check-label (:state-next-at lifecycle*)
                                               (js/Date.now))
        amount-input-display (format-grouped-amount-input amount-input)
        modal-title (if (and deposit?
                             deposit-amount-entry?
                             (string? (:symbol selected-deposit-asset*)))
                      (str "Deposit " (:symbol selected-deposit-asset*))
                      title)]
    (when open?
      [:div {:class ["fixed" "inset-0" "z-[80]" "flex" "items-center" "justify-center" "p-4"]}
       [:div {:class ["absolute" "inset-0" "bg-black/65"]
              :on {:click [[:actions/close-funding-modal]]}}]
       [:div {:class ["relative"
                      "z-[81]"
                      "w-full"
                      "max-w-md"
                      "rounded-2xl"
                      "border"
                      "border-[#1f3b3c]"
                      "bg-[#081b24]"
                      "p-4"
                      "shadow-2xl"
                      "space-y-3"]
              :role "dialog"
              :aria-modal true
              :aria-label modal-title
              :tab-index 0
              :data-role "funding-modal"
              :on {:keydown [[:actions/handle-funding-modal-keydown [:event/key]]]}}
        [:div {:class ["flex" "items-center" "justify-between"]}
         [:h2 {:class ["text-lg" "font-semibold" "text-[#e5eef1]"]}
          modal-title]
         [:button {:type "button"
                   :class ["h-8"
                           "w-8"
                           "rounded-md"
                           "text-xl"
                           "leading-none"
                           "text-[#7f98a0]"
                           "hover:bg-[#0f2834]"
                           "hover:text-[#dce9ee]"]
                   :on {:click [[:actions/close-funding-modal]]}}
          "×"]]

        (when (and deposit? (not deposit-amount-entry?))
          [:div {:class ["space-y-3"] :data-role "funding-deposit-select-step"}
           [:p {:class ["text-sm" "leading-6" "text-[#8fa7ae]"]}
            "Deposit funds to start trading immediately. You can withdraw at any time."]
           [:input {:type "text"
                    :placeholder "Search a supported asset"
                    :value deposit-search-input
                    :class ["w-full"
                            "rounded-lg"
                            "border"
                            "border-[#1f3f4f]"
                            "bg-[#0c202a]"
                            "px-3"
                            "py-2.5"
                            "text-sm"
                            "text-[#dce9ee]"
                            "outline-none"
                            "focus:border-[#30607a]"]
                    :on {:input [[:actions/set-funding-modal-field
                                  [:deposit-search-input]
                                  [:event.target/value]]]}}]
           (if (seq deposit-assets)
             (into
              [:div {:class ["max-h-[264px]"
                             "overflow-y-auto"
                             "rounded-xl"
                             "border"
                             "border-[#1c3948]"
                             "bg-[#0c1e29]"
                             "p-2"]
                     :data-role "funding-deposit-asset-list"}]
              (for [asset deposit-assets]
                ^{:key (name (:key asset))}
                (deposit-asset-button asset
                                      (= (:key asset)
                                         (:key selected-deposit-asset*)))))
             [:div {:class ["rounded-xl"
                            "border"
                            "border-[#1c3948]"
                            "bg-[#0c1e29]"
                            "px-3"
                            "py-8"
                            "text-center"
                            "text-sm"
                            "text-[#7b919b]"]}
              "No assets match your search."])])

        (when (and deposit? deposit-amount-entry?)
          [:div {:class ["space-y-3"] :data-role "funding-deposit-amount-step"}
           [:p {:class ["text-sm" "text-[#8ea4ab]"]}
            (str "From the " (:network selected-deposit-asset*) " network")]
           [:div {:class ["rounded-lg"
                          "border"
                          "border-[#1f3f4f]"
                          "bg-[#1a2633]"
                          "px-3"
                          "py-3"]}
            [:div {:class ["flex" "items-center" "gap-2.5"]}
             (deposit-asset-icon (:symbol selected-deposit-asset*) selected-deposit-icon-src)
             [:div {:class ["flex" "flex-col"]}
              [:span {:class ["text-sm" "font-semibold" "text-[#e6eef2]"]} (:symbol selected-deposit-asset*)]
              [:span {:class ["text-xs" "text-[#7e95a0]"]} (:network selected-deposit-asset*)]]]]
           (if deposit-flow-supported?
             (case deposit-flow-kind*
               :hyperunit-address
               [:div {:class ["space-y-3"]}
                [:div {:class ["rounded-lg"
                               "border"
                               "border-[#24485b]"
                               "bg-[#0f2433]"
                               "px-3"
                               "py-3"
                               "space-y-1.5"]}
                 [:p {:class ["text-sm" "text-[#d9e7ed]"]}
                  "Generate a deposit address and send funds on the selected network."]
                 [:p {:class ["text-xs" "text-[#7f97a0]"]}
                  "Only send "
                  [:span {:class ["font-semibold" "text-[#c9dce4]"]} (:symbol selected-deposit-asset*)]
                  " from "
                  [:span {:class ["font-semibold" "text-[#c9dce4]"]} (:network selected-deposit-asset*)]
                  "."]
                 (when (seq deposit-generated-address)
                   [:div {:class ["space-y-1.5" "pt-1"]}
                    [:p {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-[#7c93a0]"]}
                     "Deposit Address"]
                    [:div {:class ["rounded-md"
                                   "border"
                                   "border-[#2c5468]"
                                   "bg-[#0a1d29]"
                                   "px-2.5"
                                   "py-2"
                                   "text-xs"
                                   "font-mono"
                                   "break-all"
                                   "text-[#d6e8ee]"]
                     :data-role "funding-deposit-generated-address"}
                     deposit-generated-address]
                    (when (pos? generated-signature-count)
                      [:p {:class ["text-xs" "text-[#7f97a0]"]}
                       (str "Authorization signatures: " generated-signature-count)])])]
                [:div {:class ["space-y-1.5" "pt-1" "text-sm"]}
                 [:div {:class ["flex" "items-center" "justify-between"]}
                  [:span {:class ["text-[#7d94a0]"]} "Estimated time"]
                  [:span {:class ["text-[#dce9ee]"]} deposit-estimated-time]]
                 [:div {:class ["flex" "items-center" "justify-between"]}
                  [:span {:class ["text-[#7d94a0]"]} "Network fee"]
                  [:span {:class ["text-[#dce9ee]"]} deposit-network-fee]]
                 (when (and (not hyperunit-fee-estimate-loading?)
                            (seq hyperunit-fee-estimate-error))
                   [:p {:class ["text-xs" "text-[#9db2ba]"]}
                    (str "Live HyperUnit estimates unavailable: "
                         hyperunit-fee-estimate-error)])]
                (when show-hyperunit-lifecycle?
                  [:div {:class ["rounded-lg"
                                 "border"
                                 "border-[#24485b]"
                                 "bg-[#0c1f2c]"
                                 "px-3"
                                 "py-2.5"
                                 "space-y-1.5"]
                         :data-role "funding-deposit-lifecycle"}
                   [:div {:class ["flex" "items-center" "justify-between"]}
                    [:span {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-[#7d94a0]"]}
                     "Lifecycle Stage"]
                    [:span {:class ["text-xs" "font-medium" "text-[#dce9ee]"]}
                     lifecycle-state-label]]
                   [:div {:class ["flex" "items-center" "justify-between"]}
                    [:span {:class ["text-[#7d94a0]"]} "Status"]
                    [:span {:class ["text-[#dce9ee]"]} lifecycle-status-label]]
                   (when (number? (:source-tx-confirmations lifecycle*))
                     [:div {:class ["flex" "items-center" "justify-between"]}
                      [:span {:class ["text-[#7d94a0]"]} "Source confirmations"]
                      [:span {:class ["text-[#dce9ee]"]}
                       (str (:source-tx-confirmations lifecycle*))]])
                   (when (number? (:destination-tx-confirmations lifecycle*))
                     [:div {:class ["flex" "items-center" "justify-between"]}
                      [:span {:class ["text-[#7d94a0]"]} "Destination confirmations"]
                      [:span {:class ["text-[#dce9ee]"]}
                       (str (:destination-tx-confirmations lifecycle*))]])
                   (when (seq (:destination-tx-hash lifecycle*))
                     [:div {:class ["space-y-1"]}
                      [:p {:class ["text-[#7d94a0]"]} "Destination tx hash"]
                      [:p {:class ["font-mono" "text-xs" "text-[#dce9ee]" "break-all"]}
                       (:destination-tx-hash lifecycle*)]])
                   (when (seq lifecycle-next-check)
                     [:div {:class ["flex" "items-center" "justify-between"]}
                      [:span {:class ["text-[#7d94a0]"]} "Next check"]
                      [:span {:class ["text-[#dce9ee]"]} lifecycle-next-check]])
                   (when (seq (:error lifecycle*))
                     [:div {:class ["rounded-md"
                                    "border"
                                    "border-[#7b3340]"
                                    "bg-[#3a1b22]/55"
                                    "px-2.5"
                                    "py-1.5"
                                    "text-xs"
                                    "text-[#f2b8c5]"]}
                      (:error lifecycle*)])])
                [:div {:class ["grid" "grid-cols-[56px_1fr]" "gap-2" "pt-1"]}
                 [:button {:type "button"
                           :class ["h-[38px]"
                                   "rounded-lg"
                                   "border"
                                   "border-[#355061]"
                                   "bg-[#152633]"
                                   "text-lg"
                                   "text-[#cfe0e7]"
                                   "hover:border-[#4b6c82]"
                                   "hover:bg-[#1b3242]"]
                           :on {:click [[:actions/set-funding-modal-field [:deposit-step] :asset-select]]}}
                  "←"]
                 [:button {:type "button"
                           :disabled submit-disabled?
                           :class (submit-button-classes submit-disabled?)
                           :on {:click [[:actions/submit-funding-deposit]]}}
                  deposit-submit-label]]]

               [:div {:class ["space-y-3"]}
                [:div {:class ["space-y-1.5"]}
                 [:label {:class ["block" "text-xs" "text-[#7e94a0]"]}
                  "Amount"]
                 [:div {:class ["flex"
                                "items-center"
                                "rounded-lg"
                                "border"
                                "border-[#2a4658]"
                                "bg-[#0f2230]"
                                "px-2"
                                "py-1.5"
                                "gap-2"]}
                  [:button {:type "button"
                            :class ["rounded-md"
                                    "border"
                                    "border-[#445565]"
                                    "bg-[#26313d]"
                                    "px-2"
                                    "py-0.5"
                                    "text-xs"
                                    "font-semibold"
                                    "text-[#e6eef2]"
                                    "hover:border-[#607487]"]
                            :on {:click [[:actions/set-funding-modal-field [:amount-input] (str deposit-min-amount)]]}}
                   "MAX"]
                  [:input {:type "text"
                           :input-mode "decimal"
                           :placeholder (str deposit-min-amount)
                           :disabled submitting?
                           :value amount-input-display
                           :class ["flex-1"
                                   "bg-transparent"
                                   "border-0"
                                   "ring-0"
                                   "shadow-none"
                                   "text-sm"
                                   "text-[#e6eef2]"
                                   "text-right"
                                   "outline-none"
                                   "focus:border-0"
                                   "focus:ring-0"
                                   "disabled:opacity-70"]
                           :on {:input [[:actions/set-funding-modal-field [:amount-input] [:event.target/value]]]}}]
                  [:span {:class ["text-sm" "text-[#7e95a0]"]} (:symbol selected-deposit-asset*)]]]
                [:div {:class ["flex" "gap-2"]}
                 (for [quick-amount deposit-quick-amounts]
                   ^{:key (str "deposit-quick-" quick-amount)}
                   [:button {:type "button"
                             :class ["rounded-md"
                                     "border"
                                     "border-[#3a4d5d]"
                                     "bg-[#111f29]"
                                     "px-3"
                                     "py-1.5"
                                     "text-xs"
                                     "text-[#e0ebef]"
                                     "hover:border-[#537089]"
                                     "hover:bg-[#162b37]"]
                             :on {:click [[:actions/set-funding-modal-field [:amount-input] (str quick-amount)]]}}
                    (if (>= quick-amount 1000)
                      (str (/ quick-amount 1000) "k")
                      (str quick-amount))])]
                [:div {:class ["space-y-1.5" "pt-2" "text-sm"]}
                 [:div {:class ["flex" "items-center" "justify-between"]}
                  [:span {:class ["text-[#7d94a0]"]} "Minimum deposit"]
                  [:span {:class ["text-[#dce9ee]"]} (str deposit-min-amount " " (:symbol selected-deposit-asset*))]]
                 [:div {:class ["flex" "items-center" "justify-between"]}
                  [:span {:class ["text-[#7d94a0]"]} "Estimated time"]
                  [:span {:class ["text-[#dce9ee]"]} deposit-estimated-time]]
                 [:div {:class ["flex" "items-center" "justify-between"]}
                  [:span {:class ["text-[#7d94a0]"]} "Network fee"]
                  [:span {:class ["text-[#dce9ee]"]} deposit-network-fee]]]
                [:div {:class ["grid" "grid-cols-[56px_1fr]" "gap-2" "pt-1"]}
                 [:button {:type "button"
                           :class ["h-[38px]"
                                   "rounded-lg"
                                   "border"
                                   "border-[#355061]"
                                   "bg-[#152633]"
                                   "text-lg"
                                   "text-[#cfe0e7]"
                                   "hover:border-[#4b6c82]"
                                   "hover:bg-[#1b3242]"]
                           :on {:click [[:actions/set-funding-modal-field [:deposit-step] :asset-select]]}}
                  "←"]
                 [:button {:type "button"
                           :disabled submit-disabled?
                           :class (submit-button-classes submit-disabled?)
                           :on {:click [[:actions/submit-funding-deposit]]}}
                  deposit-submit-label]]])
             [:div {:class ["space-y-3"]}
              [:div {:class ["rounded-lg"
                             "border"
                             "border-[#264759]"
                             "bg-[#102535]"
                             "px-3"
                             "py-3"
                             "text-sm"
                             "text-[#9bb1b9]"
                             "space-y-1.5"]}
               [:p "This asset's deposit flow is not implemented in Hyperopen yet."]
               [:p {:class ["text-xs" "text-[#7f98a0]"]}
                (case deposit-flow-kind*
                  :route "Route-based bridge/swap flow will be implemented in the next milestone."
                  :hyperunit-address "Address-based deposit instructions will be implemented in the next milestone."
                  "Deposit flow details are unavailable.")]]
              [:div {:class ["grid" "grid-cols-[56px_1fr]" "gap-2" "pt-1"]}
               [:button {:type "button"
                         :class ["h-[38px]"
                                 "rounded-lg"
                                 "border"
                                 "border-[#355061]"
                                 "bg-[#152633]"
                                 "text-lg"
                                 "text-[#cfe0e7]"
                                 "hover:border-[#4b6c82]"
                                 "hover:bg-[#1b3242]"]
                         :on {:click [[:actions/set-funding-modal-field [:deposit-step] :asset-select]]}}
                "←"]
               [:button {:type "button"
                         :disabled true
                         :class (submit-button-classes true)}
                deposit-submit-label]]])])

        (when transfer?
          [:div {:class ["space-y-3"]}
           [:div {:class ["grid" "grid-cols-2" "gap-2"]}
            [:button {:type "button"
                      :class (if to-perp?
                               (base-button-classes true)
                               (base-button-classes false))
                      :disabled submitting?
                      :on {:click [[:actions/set-funding-transfer-direction true]]}}
             "Spot -> Perps"]
            [:button {:type "button"
                      :class (if to-perp?
                               (base-button-classes false)
                               (base-button-classes true))
                      :disabled submitting?
                      :on {:click [[:actions/set-funding-transfer-direction false]]}}
             "Perps -> Spot"]]
           [:div {:class ["space-y-2"]}
            [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
             [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
              "Amount (USDC)"]
             [:button {:type "button"
                       :disabled submitting?
                       :class (if submitting?
                                ["text-xs" "font-medium" "tracking-[0.03em]" "text-[#6f868c]" "cursor-not-allowed"]
                                ["text-xs" "font-medium" "tracking-[0.03em]" "text-[#5de6da]" "hover:text-[#8bf3ea]"])
                       :on {:click [[:actions/set-funding-modal-field [:amount-input] max-input]]}}
              (str "MAX: " max-display " USDC")]]
            [:input {:type "text"
                     :input-mode "decimal"
                     :placeholder "Enter amount"
                     :disabled submitting?
                     :value amount-input-display
                     :class ["w-full"
                             "rounded-lg"
                             "border"
                             "border-[#28474b]"
                             "bg-[#0c2028]"
                             "px-3"
                             "py-2"
                             "text-sm"
                             "text-[#e6eff2]"
                             "outline-none"
                             "focus:border-[#4f8f87]"
                             "disabled:cursor-not-allowed"
                             "disabled:opacity-70"]
                     :on {:input [[:actions/set-funding-modal-field [:amount-input] [:event.target/value]]]}}]]
           [:div {:class ["flex" "justify-end" "gap-2"]}
            [:button {:type "button"
                      :class (base-button-classes false)
                      :on {:click [[:actions/close-funding-modal]]}}
             "Cancel"]
            [:button {:type "button"
                      :disabled submit-disabled?
                      :class (submit-button-classes submit-disabled?)
                      :on {:click [[:actions/submit-funding-transfer]]}}
             submit-label]]])

        (when withdraw?
          [:div {:class ["space-y-3"]}
           [:div {:class ["space-y-2"]}
            [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
             "Asset"]
            [:div {:class ["rounded-lg"
                           "border"
                           "border-[#1f3f4f]"
                           "bg-[#152230]"
                           "px-3"
                           "py-2.5"]}
             [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
              [:div {:class ["flex" "items-center" "gap-2.5"]}
               (deposit-asset-icon (:symbol selected-withdraw-asset*) selected-withdraw-icon-src)
               [:div {:class ["flex" "flex-col"]}
                [:span {:class ["text-sm" "font-semibold" "text-[#e6eef2]"]} (:symbol selected-withdraw-asset*)]
                [:span {:class ["text-xs" "text-[#7e95a0]"]} (:network selected-withdraw-asset*)]]]
              [:select {:value (or (some-> selected-withdraw-asset-key name) "usdc")
                        :disabled submitting?
                        :class ["rounded-md"
                                "border"
                                "border-[#355061]"
                                "bg-[#0e1f2b]"
                                "px-2.5"
                                "py-1.5"
                                "text-xs"
                                "text-[#dce9ee]"
                                "outline-none"
                                "focus:border-[#4b6c82]"
                                "disabled:opacity-70"]
                        :on {:input [[:actions/set-funding-modal-field
                                      [:withdraw-selected-asset-key]
                                      [:event.target/value]]]}}
               (for [asset withdraw-assets]
                 ^{:key (str "withdraw-asset-" (name (:key asset)))}
                 [:option {:value (name (:key asset))}
                  (:symbol asset)])]]]]
           [:div {:class ["space-y-2"]}
            [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
             (if (= withdraw-flow-kind* :hyperunit-address)
               (str "Destination Address (" (:network selected-withdraw-asset*) ")")
               "Destination Address")]
            [:input {:type "text"
                     :placeholder (if (= withdraw-flow-kind* :hyperunit-address)
                                    (str "Enter " (:symbol selected-withdraw-asset*) " destination")
                                    "0x...")
                     :disabled submitting?
                     :value destination-input
                     :class ["w-full"
                             "rounded-lg"
                             "border"
                             "border-[#28474b]"
                             "bg-[#0c2028]"
                             "px-3"
                             "py-2"
                             "text-sm"
                             "text-[#e6eff2]"
                             "outline-none"
                             "focus:border-[#4f8f87]"
                             "disabled:cursor-not-allowed"
                             "disabled:opacity-70"]
                     :on {:input [[:actions/set-funding-modal-field
                                   [:destination-input]
                                   [:event.target/value]]]}}]]
           [:div {:class ["space-y-2"]}
            [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
             [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
              (str "Amount (" max-symbol ")")]
             [:button {:type "button"
                       :disabled submitting?
                       :class (if submitting?
                                ["text-xs" "font-medium" "tracking-[0.03em]" "text-[#6f868c]" "cursor-not-allowed"]
                                ["text-xs" "font-medium" "tracking-[0.03em]" "text-[#5de6da]" "hover:text-[#8bf3ea]"])
                       :on {:click [[:actions/set-funding-modal-field [:amount-input] max-input]]}}
              (str "MAX: " max-display " " max-symbol)]]
            [:input {:type "text"
                     :input-mode "decimal"
                     :placeholder "Enter amount"
                     :disabled submitting?
                     :value amount-input-display
                     :class ["w-full"
                             "rounded-lg"
                             "border"
                             "border-[#28474b]"
                             "bg-[#0c2028]"
                             "px-3"
                             "py-2"
                             "text-sm"
                             "text-[#e6eff2]"
                             "outline-none"
                             "focus:border-[#4f8f87]"
                             "disabled:cursor-not-allowed"
                             "disabled:opacity-70"]
                     :on {:input [[:actions/set-funding-modal-field [:amount-input] [:event.target/value]]]}}]]
           [:div {:class ["space-y-1.5" "pt-1" "text-sm"]}
            (when (pos? min-withdraw-amount)
              [:div {:class ["flex" "items-center" "justify-between"]}
               [:span {:class ["text-[#7d94a0]"]} "Minimum withdrawal"]
               [:span {:class ["text-[#dce9ee]"]}
                (str min-withdraw-amount " " min-withdraw-symbol)]])
            [:div {:class ["flex" "items-center" "justify-between"]}
             [:span {:class ["text-[#7d94a0]"]} "Estimated time"]
             [:span {:class ["text-[#dce9ee]"]} withdraw-estimated-time]]
            [:div {:class ["flex" "items-center" "justify-between"]}
             [:span {:class ["text-[#7d94a0]"]} "Network fee"]
             [:span {:class ["text-[#dce9ee]"]} withdraw-network-fee]]
            (when (and (= withdraw-flow-kind* :hyperunit-address)
                       (not hyperunit-fee-estimate-loading?)
                       (seq hyperunit-fee-estimate-error))
              [:p {:class ["text-xs" "text-[#9db2ba]"]}
               (str "Live HyperUnit estimates unavailable: "
                    hyperunit-fee-estimate-error)])]
           (when (and (= withdraw-flow-kind* :hyperunit-address)
                      (seq withdraw-generated-address))
             [:div {:class ["rounded-lg"
                            "border"
                            "border-[#24485b]"
                            "bg-[#0f2433]"
                            "px-3"
                            "py-2.5"
                            "space-y-1.5"]}
              [:p {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-[#7c93a0]"]}
               "HyperUnit Protocol Address"]
              [:p {:class ["font-mono" "text-xs" "break-all" "text-[#d6e8ee]"]}
               withdraw-generated-address]])
           (when (and show-hyperunit-lifecycle?
                      (= :withdraw (:direction lifecycle*)))
             [:div {:class ["rounded-lg"
                            "border"
                            "border-[#24485b]"
                            "bg-[#0c1f2c]"
                            "px-3"
                            "py-2.5"
                            "space-y-1.5"]
                    :data-role "funding-withdraw-lifecycle"}
              [:div {:class ["flex" "items-center" "justify-between"]}
               [:span {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-[#7d94a0]"]}
                "Lifecycle Stage"]
               [:span {:class ["text-xs" "font-medium" "text-[#dce9ee]"]}
                lifecycle-state-label]]
              [:div {:class ["flex" "items-center" "justify-between"]}
               [:span {:class ["text-[#7d94a0]"]} "Status"]
               [:span {:class ["text-[#dce9ee]"]} lifecycle-status-label]]
              (when (number? (:source-tx-confirmations lifecycle*))
                [:div {:class ["flex" "items-center" "justify-between"]}
                 [:span {:class ["text-[#7d94a0]"]} "Source confirmations"]
                 [:span {:class ["text-[#dce9ee]"]}
                  (str (:source-tx-confirmations lifecycle*))]])
              (when (number? (:destination-tx-confirmations lifecycle*))
                [:div {:class ["flex" "items-center" "justify-between"]}
                 [:span {:class ["text-[#7d94a0]"]} "Destination confirmations"]
                 [:span {:class ["text-[#dce9ee]"]}
                  (str (:destination-tx-confirmations lifecycle*))]])
              (when (number? (:position-in-withdraw-queue lifecycle*))
                [:div {:class ["flex" "items-center" "justify-between"]}
                 [:span {:class ["text-[#7d94a0]"]} "Queue position"]
                 [:span {:class ["text-[#dce9ee]"]}
                  (str (:position-in-withdraw-queue lifecycle*))]])
              (when (seq (:destination-tx-hash lifecycle*))
                [:div {:class ["space-y-1"]}
                 [:p {:class ["text-[#7d94a0]"]} "Destination tx hash"]
                 [:p {:class ["font-mono" "text-xs" "text-[#dce9ee]" "break-all"]}
                  (:destination-tx-hash lifecycle*)]])
              (when (seq lifecycle-next-check)
                [:div {:class ["flex" "items-center" "justify-between"]}
                 [:span {:class ["text-[#7d94a0]"]} "Next check"]
                 [:span {:class ["text-[#dce9ee]"]} lifecycle-next-check]])
              (when (seq (:error lifecycle*))
                [:div {:class ["rounded-md"
                               "border"
                               "border-[#7b3340]"
                               "bg-[#3a1b22]/55"
                               "px-2.5"
                               "py-1.5"
                               "text-xs"
                               "text-[#f2b8c5]"]}
                 (:error lifecycle*)])])
           [:div {:class ["flex" "justify-end" "gap-2"]}
            [:button {:type "button"
                      :class (base-button-classes false)
                      :on {:click [[:actions/close-funding-modal]]}}
             "Cancel"]
            [:button {:type "button"
                      :disabled submit-disabled?
                      :class (submit-button-classes submit-disabled?)
                      :on {:click [[:actions/submit-funding-withdraw]]}}
             submit-label]]])

        (when legacy?
          [:div {:class ["space-y-3"]}
           [:p {:class ["text-sm" "text-[#b9cbd0]"]}
            (str "The " (name legacy-kind) " funding workflow is not available yet.")]
           [:div {:class ["flex" "justify-end"]}
            [:button {:type "button"
                      :class (base-button-classes true)
                      :on {:click [[:actions/close-funding-modal]]}}
             "Close"]]])

        (when (and (seq status-message)
                   (not legacy?)
                   (not deposit?))
          [:div {:class ["rounded-md"
                         "border"
                         "border-[#7b3340]"
                         "bg-[#3a1b22]/55"
                         "px-3"
                         "py-2"
                         "text-sm"
                         "text-[#f2b8c5]"]
                 :data-role "funding-status"}
           status-message])]])))
