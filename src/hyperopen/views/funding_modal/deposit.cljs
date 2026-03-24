(ns hyperopen.views.funding-modal.deposit
  (:require [hyperopen.views.funding-modal.shared :as shared]))

(defn- deposit-asset-button
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
            :on {:click [[:actions/select-funding-deposit-asset
                          (:key asset)]]}}
   [:div {:class ["flex" "items-center" "gap-2.5"]}
    (shared/funding-asset-icon (:symbol asset) (shared/asset-icon-src asset))
    [:div {:class ["flex" "min-w-0" "flex-col"]}
     [:span {:class ["text-sm" "font-semibold" "text-[#e6eef2]"]} (:symbol asset)]
     [:span {:class ["text-xs" "text-[#7e95a0]"]} (:network asset)]]]])

(defn deposit-select-content
  [{:keys [search assets selected-asset]}]
  [:div {:class ["space-y-3"] :data-role "funding-deposit-select-step"}
   [:p {:class ["text-sm" "leading-6" "text-[#8fa7ae]"]}
    "Deposit funds to start trading immediately. You can withdraw at any time."]
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
            :on {:input [[:actions/search-funding-deposit-assets
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
             :data-role "funding-deposit-asset-list"}]
      (for [asset assets]
        ^{:key (name (:key asset))}
        (deposit-asset-button asset
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

(def render-select-content deposit-select-content)

(defn deposit-address-content
  [{:keys [selected-asset flow summary lifecycle actions]}]
  [:div {:class ["space-y-3"] :data-role "funding-deposit-address-step"}
   [:p {:class ["text-sm" "text-[#8ea4ab]"]}
    (str "From the " (:network selected-asset) " network")]
   (shared/deposit-asset-card selected-asset)
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
      [:span {:class ["font-semibold" "text-[#c9dce4]"]} (:symbol selected-asset)]
      " from "
      [:span {:class ["font-semibold" "text-[#c9dce4]"]} (:network selected-asset)]
      "."]
     (when (seq (:generated-address flow))
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
         (:generated-address flow)]
        (when (pos? (:generated-signature-count flow))
          [:p {:class ["text-xs" "text-[#7f97a0]"]}
           (str "Authorization signatures: " (:generated-signature-count flow))])])]
    [:div {:class ["space-y-1.5" "pt-1" "text-sm"]}
     (for [row (:rows summary)]
       ^{:key (:label row)}
       (shared/summary-row row))
     (when (and (= :error (get-in flow [:fee-estimate :state]))
                (seq (get-in flow [:fee-estimate :message])))
       [:p {:class ["text-xs" "text-[#9db2ba]"]}
        (str "Live HyperUnit estimates unavailable: "
             (get-in flow [:fee-estimate :message]))])]
    (shared/lifecycle-panel lifecycle "funding-deposit-lifecycle")
    (shared/action-row {:back-action :actions/return-to-funding-deposit-asset-select
                        :submit-action :actions/submit-funding-deposit
                        :submit-label (get-in actions [:submit-label])
                        :submit-disabled? (get-in actions [:submit-disabled?])})]])

(def render-address-content deposit-address-content)

(defn deposit-amount-content
  [{:keys [selected-asset amount summary actions]}]
  (let [submitting? (get-in actions [:submitting?])]
    [:div {:class ["space-y-3"] :data-role "funding-deposit-amount-step"}
     [:p {:class ["text-sm" "text-[#8ea4ab]"]}
      (str "From the " (:network selected-asset) " network")]
     (shared/deposit-asset-card selected-asset)
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
                 :disabled submitting?
                 :class (if submitting?
                          ["rounded-md"
                           "border"
                           "border-[#445565]"
                           "bg-[#1d2933]"
                           "px-2"
                           "py-0.5"
                           "text-xs"
                           "font-semibold"
                           "text-[#6f868c]"
                           "cursor-not-allowed"]
                          ["rounded-md"
                           "border"
                           "border-[#445565]"
                           "bg-[#26313d]"
                           "px-2"
                           "py-0.5"
                           "text-xs"
                           "font-semibold"
                           "text-[#e6eef2]"
                           "hover:border-[#607487]"])
                 :on {:click [[:actions/set-funding-deposit-amount-to-minimum]]}}
        "MIN"]
       [:input {:type "text"
                :inputmode "decimal"
                :placeholder (str (:minimum-value amount))
                :disabled submitting?
                :value (shared/format-grouped-amount-input (:value amount))
                :class ["flex-1"
                        "bg-transparent"
                        "border-0"
                        "ring-0"
                        "text-sm"
                        "text-[#e6eef2]"
                        "text-right"
                        "outline-none"
                        "focus:border-0"
                        "focus:ring-0"
                        "disabled:opacity-70"]
                :on {:input [[:actions/enter-funding-deposit-amount
                              [:event.target/value]]]}}]
       [:span {:class ["text-sm" "text-[#7e95a0]"]} (:symbol selected-asset)]]]
     [:div {:class ["flex" "gap-2"]}
      (for [quick-amount (:quick-amounts amount)]
        ^{:key (str "deposit-quick-" quick-amount)}
        [:button {:type "button"
                  :disabled submitting?
                  :class (if submitting?
                           ["rounded-md"
                            "border"
                            "border-[#31404c]"
                            "bg-[#111921]"
                            "px-3"
                            "py-1.5"
                            "text-xs"
                            "text-[#6f868c]"
                            "cursor-not-allowed"]
                           ["rounded-md"
                            "border"
                            "border-[#3a4d5d]"
                            "bg-[#111f29]"
                            "px-3"
                            "py-1.5"
                            "text-xs"
                            "text-[#e0ebef]"
                            "hover:border-[#537089]"
                            "hover:bg-[#162b37]"])
                  :on {:click [[:actions/enter-funding-deposit-amount
                                (str quick-amount)]]}}
         (if (>= quick-amount 1000)
           (str (/ quick-amount 1000) "k")
           (str quick-amount))])]
     [:div {:class ["space-y-1.5" "pt-2" "text-sm"]}
      (for [row (:rows summary)]
        ^{:key (:label row)}
        (shared/summary-row row))]
     (shared/action-row {:back-action :actions/return-to-funding-deposit-asset-select
                         :submit-action :actions/submit-funding-deposit
                         :submit-label (get-in actions [:submit-label])
                         :submit-disabled? (get-in actions [:submit-disabled?])})]))

(def render-amount-content deposit-amount-content)

(defn deposit-unavailable-content
  [{:keys [flow actions]}]
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
     (:unsupported-detail flow)]]
   (shared/action-row {:back-action :actions/return-to-funding-deposit-asset-select
                       :submit-action :actions/submit-funding-deposit
                       :submit-label (get-in actions [:submit-label])
                       :submit-disabled? true})])

(def render-unavailable-content deposit-unavailable-content)

(defn deposit-missing-asset-content
  [{:keys [actions]}]
  [:div {:class ["space-y-3"]}
   [:div {:class ["rounded-lg"
                  "border"
                  "border-[#7b3340]"
                  "bg-[#3a1b22]/55"
                  "px-3"
                  "py-3"
                  "text-sm"
                  "text-[#f2b8c5]"]}
    "Select an asset before continuing with the deposit flow."]
   (shared/action-row {:back-action :actions/return-to-funding-deposit-asset-select
                       :submit-action :actions/submit-funding-deposit
                       :submit-label (get-in actions [:submit-label])
                       :submit-disabled? true})])

(def render-missing-asset-content deposit-missing-asset-content)
