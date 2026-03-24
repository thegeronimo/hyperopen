(ns hyperopen.views.funding-modal.withdraw
  (:require [hyperopen.views.funding-modal.shared :as shared]))

(defn- withdraw-asset-button
  [asset selected?]
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
            :on {:click [[:actions/select-funding-withdraw-asset
                          (:key asset)]]}}
   [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
    [:div {:class ["flex" "items-center" "gap-2.5"]}
     (shared/funding-asset-icon (:symbol asset) (shared/asset-icon-src asset))
     [:div {:class ["flex" "min-w-0" "flex-col"]}
      [:span {:class ["text-sm" "font-semibold" "text-[#e6eef2]"]} (:symbol asset)]
      [:span {:class ["text-xs" "text-[#7e95a0]"]} (:network asset)]]]
    [:span {:class ["shrink-0" "text-sm" "font-medium" "text-[#dce9ee]"]}
     (:available-display asset)]]])

(defn withdraw-select-content
  [{:keys [search assets selected-asset]}]
  [:div {:class ["space-y-3"] :data-role "funding-withdraw-select-step"}
   [:p {:class ["text-sm" "leading-6" "text-[#8fa7ae]"]}
    "Withdraw funds from your Hyperliquid account. You can deposit at any time."]
   [:input {:type "text"
            :placeholder (:placeholder search)
            :value (:value search)
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
            :on {:input [[:actions/search-funding-withdraw-assets
                          [:event.target/value]]]}}]
   (if (seq assets)
     (into
      [:div {:class ["max-h-[264px]"
                     "overflow-y-auto"
                     "rounded-xl"
                     "border"
                     "border-[#1c3948]"
                     "bg-[#0c1e29]"
                     "p-2"]
             :data-role "funding-withdraw-asset-list"}]
      (for [asset assets]
        ^{:key (name (:key asset))}
        (withdraw-asset-button asset
                               (= (:key asset)
                                  (:key selected-asset)))))
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

(def render-select-content withdraw-select-content)

(defn- withdrawal-queue-copy
  [{:keys [state length]}]
  (case state
    :loading "Loading..."
    :ready (str length)
    :error "Unavailable"
    "N/A"))

(defn- hyperunit-address-flow?
  [flow]
  (= (:kind flow) :hyperunit-address))

(defn- withdraw-asset-selector
  [selected-asset]
  [:div {:class ["space-y-2"]}
   [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
    "Asset"]
   (shared/deposit-asset-card selected-asset)])

(defn- withdraw-destination-label
  [selected-asset flow]
  (if (hyperunit-address-flow? flow)
    (str "Destination Address (" (:network selected-asset) ")")
    "Destination Address"))

(defn- withdraw-destination-placeholder
  [selected-asset flow]
  (if (hyperunit-address-flow? flow)
    (str "Enter " (:symbol selected-asset) " destination")
    "0x..."))

(defn- withdraw-destination-field
  [selected-asset flow destination submitting?]
  [:div {:class ["space-y-2"]}
   [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
    (withdraw-destination-label selected-asset flow)]
   [:input {:type "text"
            :placeholder (withdraw-destination-placeholder selected-asset flow)
            :disabled submitting?
            :value (:value destination)
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
            :on {:input [[:actions/enter-funding-withdraw-destination
                          [:event.target/value]]]}}]])

(defn- queue-operation-link-or-text
  [{:keys [tx-id explorer-url]}]
  (if (seq explorer-url)
    [:a {:href explorer-url
         :target "_blank"
         :rel "noreferrer noopener"
         :class ["max-w-[220px]"
                 "truncate"
                 "font-mono"
                 "text-xs"
                 "text-[#70e9e1]"
                 "underline"
                 "decoration-[#3d8f8a]"
                 "hover:text-[#9df5ef]"]}
     tx-id]
    [:span {:class ["max-w-[220px]"
                    "truncate"
                    "font-mono"
                    "text-xs"
                    "text-[#dce9ee]"]}
     tx-id]))

(defn- withdraw-queue-row
  [flow]
  (when (hyperunit-address-flow? flow)
    [:div {:class ["flex" "items-center" "justify-between"]}
     [:span {:class ["text-[#7d94a0]"]} "Withdrawal queue"]
     [:span {:class ["text-[#dce9ee]"]}
      (withdrawal-queue-copy (get-in flow [:withdrawal-queue]))]]))

(defn- withdraw-last-queue-tx-row
  [flow]
  (when-let [tx-id (when (hyperunit-address-flow? flow)
                     (get-in flow [:withdrawal-queue :last-operation :tx-id]))]
    [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
     [:span {:class ["text-[#7d94a0]"]} "Last queue tx"]
     (queue-operation-link-or-text (assoc (get-in flow [:withdrawal-queue :last-operation])
                                          :tx-id tx-id))]))

(defn- withdraw-queue-error-message
  [flow]
  (when (and (hyperunit-address-flow? flow)
             (= :error (get-in flow [:withdrawal-queue :state]))
             (seq (get-in flow [:withdrawal-queue :message])))
    [:p {:class ["text-xs" "text-[#9db2ba]"]}
     (str "Live queue status unavailable: "
          (get-in flow [:withdrawal-queue :message]))]))

(defn- withdraw-fee-estimate-error-message
  [flow]
  (when (and (hyperunit-address-flow? flow)
             (= :error (get-in flow [:fee-estimate :state]))
             (seq (get-in flow [:fee-estimate :message])))
    [:p {:class ["text-xs" "text-[#9db2ba]"]}
     (str "Live HyperUnit estimates unavailable: "
          (get-in flow [:fee-estimate :message]))]))

(defn- withdraw-summary-section
  [summary flow]
  (into
   [:div {:class ["space-y-1.5" "pt-1" "text-sm"]}]
   (concat
    (for [row (:rows summary)]
      ^{:key (:label row)}
      (shared/summary-row row))
    (remove nil?
            [(withdraw-queue-row flow)
             (withdraw-last-queue-tx-row flow)
             (withdraw-queue-error-message flow)
             (withdraw-fee-estimate-error-message flow)]))))

(defn- withdraw-protocol-address-panel
  [flow]
  (when (and (hyperunit-address-flow? flow)
             (seq (:protocol-address flow)))
    [:div {:class ["rounded-lg"
                   "border"
                   "border-[#24485b]"
                   "bg-[#0f2433]"
                   "px-3"
                   "py-2.5"
                   "space-y-1.5"]}
     [:p {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-[#7c93a0]"]}
      "HyperUnit Protocol Address"]
     [:p {:class ["break-all" "font-mono" "text-xs" "text-[#d6e8ee]"]}
      (:protocol-address flow)]]))

(defn withdraw-detail-content
  [{:keys [selected-asset destination amount flow summary lifecycle actions]}]
  (let [submitting? (get-in actions [:submitting?])]
    [:div {:class ["space-y-3"] :data-role "funding-withdraw-detail-step"}
     [:p {:class ["text-sm" "text-[#8ea4ab]"]}
      (str "To the " (:network selected-asset) " network")]
     (withdraw-asset-selector selected-asset)
     (withdraw-destination-field selected-asset flow destination submitting?)
     (shared/withdraw-amount-input-field {:value (:value amount)
                                          :placeholder "Enter amount"
                                          :disabled? submitting?
                                          :input-action :actions/enter-funding-withdraw-amount
                                          :max-action :actions/set-funding-amount-to-max
                                          :suffix (:symbol amount)
                                          :data-role "funding-withdraw-amount-input"})
     [:div {:class ["flex" "justify-end"]}
      [:p {:class ["text-xs" "text-[#7f97a0]"]}
       (:available-label amount)]]
     (withdraw-summary-section summary flow)
     (withdraw-protocol-address-panel flow)
     (shared/lifecycle-panel lifecycle "funding-withdraw-lifecycle")
     (shared/action-row {:back-action :actions/return-to-funding-withdraw-asset-select
                         :submit-action :actions/submit-funding-withdraw
                         :submit-label (get-in actions [:submit-label])
                         :submit-disabled? (get-in actions [:submit-disabled?])})]))

(def render-detail-content withdraw-detail-content)
