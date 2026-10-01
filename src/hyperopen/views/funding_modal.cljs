(ns hyperopen.views.funding-modal
  (:require [hyperopen.funding.actions :as funding-actions]
            [hyperopen.views.funding-modal.deposit :as deposit]
            [hyperopen.views.funding-modal.send :as send]
            [hyperopen.views.funding-modal.transfer :as transfer]
            [hyperopen.views.funding-modal.transfer-evm :as transfer-evm]
            [hyperopen.views.funding-modal.withdraw :as withdraw]
            [hyperopen.views.ui.dialog-focus :as dialog-focus]
            [hyperopen.views.ui.funding-modal-positioning
             :as funding-modal-positioning]))

(def ^:private funding-modal-title-id
  "funding-modal-title")

(def ^:private funding-modal-layer-z
  "Above the app's fixed footer (z-170, whose mobile nav would otherwise
   cover a sheet's submit button) and the trade panes that paint at z 100-260,
   below toasts (z-280)."
  "z-[262]")

(def ^:private funding-modal-panel-z
  "z-[263]")

(def ^:private portfolio-header-deposit-action-data-role
  "portfolio-action-deposit")

(def ^:private portfolio-header-transfer-action-data-role
  "portfolio-action-perps-spot")

(def ^:private portfolio-header-withdraw-action-data-role
  "portfolio-action-withdraw")

(def ^:private portfolio-card-deposit-action-data-role
  "portfolio-funding-action-deposit")

(def ^:private portfolio-card-transfer-action-data-role
  "portfolio-funding-action-transfer")

(def ^:private portfolio-card-withdraw-action-data-role
  "portfolio-funding-action-withdraw")

(defn- data-role-selector
  [data-role]
  (str "[data-role=\"" data-role "\"]"))

(defn- data-role-prefix-selector
  [prefix]
  (str "[data-role^=\"" prefix "\"]"))

(defn- combined-restore-selector
  ([data-roles]
   (combined-restore-selector data-roles []))
  ([data-roles data-role-prefixes]
   (->> (concat (map data-role-selector data-roles)
                (map data-role-prefix-selector data-role-prefixes))
        (interpose ", ")
        (apply str))))

;; Transfer openers beyond the header and trade buttons: the Portfolio funds
;; strip connectors, the /trade HyperEVM line's Move link, and every Balances
;; row move action (`balances-move-<row>-<to>`).
(def ^:private transfer-opener-data-roles
  ["portfolio-funds-connector-perps-spot"
   "portfolio-funds-connector-spot-evm"
   "account-equity-hyperevm-move"])

(def ^:private transfer-opener-data-role-prefixes
  ["balances-move-"])

(def ^:private funding-modal-focus-on-render-default
  (dialog-focus/dialog-focus-on-render))

(def ^:private funding-modal-focus-on-render-by-mode
  {:deposit (dialog-focus/dialog-focus-on-render
             {:restore-selector (combined-restore-selector
                                 [funding-modal-positioning/deposit-action-data-role
                                  portfolio-header-deposit-action-data-role
                                  portfolio-card-deposit-action-data-role])})
   :transfer (dialog-focus/dialog-focus-on-render
              {:restore-selector (combined-restore-selector
                                  (into [funding-modal-positioning/transfer-action-data-role
                                         portfolio-header-transfer-action-data-role
                                         portfolio-card-transfer-action-data-role]
                                        transfer-opener-data-roles)
                                  transfer-opener-data-role-prefixes)})
   :withdraw (dialog-focus/dialog-focus-on-render
              {:restore-selector (combined-restore-selector
                                  [funding-modal-positioning/withdraw-action-data-role
                                   portfolio-header-withdraw-action-data-role
                                   portfolio-card-withdraw-action-data-role])})})

(defn- funding-modal-focus-on-render
  [mode]
  (or (get funding-modal-focus-on-render-by-mode mode)
      funding-modal-focus-on-render-default))

(defn- legacy-content
  [{:keys [message]}]
  [:div {:class ["space-y-3"]}
   [:p {:class ["text-sm" "text-[#b9cbd0]"]}
    message]
   [:div {:class ["flex" "justify-end"]}
    [:button {:type "button"
              :class ["rounded-lg"
                      "border"
                      "border-[#2f625a]"
                      "bg-ho-accent-soft"
                      "px-3.5"
                      "py-2"
                      "text-sm"
                      "font-medium"
                      "text-[#daf3ef]"
                      "hover:border-[#3f7f75]"
                      "hover:bg-ho-accent-soft-hi"]
              :on {:click [[:actions/close-funding-modal]]}}
     "Close"]]])

(defn- unknown-content
  [{:keys [kind]}]
  [:div {:class ["space-y-3"] :data-role "funding-unknown-content"}
   [:div {:class ["rounded-lg"
                  "border"
                  "border-ho-border-sell"
                  "bg-ho-sell-soft/55"
                  "px-3"
                  "py-3"
                  "space-y-1.5"]}
    [:p {:class ["text-sm" "text-ho-sell-tint"]}
     "This funding modal state is not supported yet."]
    [:p {:class ["text-xs" "text-[#d7b8c0]"]}
     (str "Unhandled content kind: " (pr-str kind))]]
   [:div {:class ["flex" "justify-end"]}
    [:button {:type "button"
              :class ["rounded-lg"
                      "border"
                      "border-[#2f625a]"
                      "bg-ho-accent-soft"
                      "px-3.5"
                      "py-2"
                      "text-sm"
                      "font-medium"
                      "text-[#daf3ef]"
                      "hover:border-[#3f7f75]"
                      "hover:bg-ho-accent-soft-hi"]
              :on {:click [[:actions/close-funding-modal]]}}
     "Close"]]])

(defn- render-content
  [{:keys [content deposit send transfer withdraw legacy]}]
  (case (:kind content)
    :deposit/select (deposit/deposit-select-content deposit)
    :deposit/address (deposit/deposit-address-content deposit)
    :deposit/amount (deposit/deposit-amount-content deposit)
    :deposit/unavailable (deposit/deposit-unavailable-content deposit)
    :deposit/missing-asset (deposit/deposit-missing-asset-content deposit)
    :send/form (send/render-content send)
    :transfer/form (transfer/render-content transfer)
    :transfer/progress (transfer-evm/progress-content transfer)
    :transfer/pending (transfer-evm/pending-content transfer)
    :transfer/failed (transfer-evm/failed-content transfer)
    :transfer/success (transfer-evm/success-content transfer)
    :withdraw/select (withdraw/withdraw-select-content withdraw)
    :withdraw/detail (withdraw/withdraw-detail-content withdraw)
    :unsupported/workflow (legacy-content legacy)
    (unknown-content content)))

(def ^:private transfer-scroll-classes
  "Transfer keeps its actions in a sticky footer (about 70-110px). Scroll
   padding under it keeps a control reached with Tab from scrolling into
   place behind the footer."
  ["scroll-pb-28"])

(defn- render-mobile-shell
  [sheet-style focus-on-render extra-panel-classes panel-children]
  [:div {:class ["fixed" "inset-0" funding-modal-layer-z]
         :data-role "funding-mobile-sheet-layer"}
   [:button {:type "button"
             :class ["absolute" "inset-0" "bg-black/55" "backdrop-blur-[1px]"]
             :style {:transition "opacity 0.14s ease-out"
                     :opacity 1}
             :replicant/mounting {:style {:opacity 0}}
             :replicant/unmounting {:style {:opacity 0}}
             :aria-label "Close funding dialog"
             :data-role "funding-mobile-sheet-backdrop"
             :on {:click [[:actions/close-funding-modal]]}}]
   (into [:div {:class (into ["absolute"
                              "inset-x-0"
                              "bottom-0"
                              "w-full"
                              "overflow-y-auto"
                              "rounded-t-[22px]"
                              "border"
                              "border-ho-border-accent-muted"
                              "bg-ho-bg-deep"
                              "px-4"
                              "pt-4"
                              "text-sm"
                              "shadow-[0_-24px_60px_rgba(0,0,0,0.45)]"
                              "space-y-3"]
                             extra-panel-classes)
                :style sheet-style
                :replicant/mounting {:style {:transform "translateY(18px)"
                                             :opacity 0}}
                :replicant/unmounting {:style {:transform "translateY(18px)"
                                               :opacity 0}}
                :role "dialog"
                :aria-modal true
                :aria-labelledby funding-modal-title-id
                :tabindex "-1"
                :data-role "funding-modal"
                :data-parity-id "funding-modal-mobile"
                :data-funding-mobile-sheet-surface "true"
                :replicant/on-render focus-on-render
                :on {:keydown [[:actions/handle-funding-modal-keydown
                                [:event/key]]]}}]
         (keep identity panel-children))])

(def ^:private popover-bottom-reserve
  "Room kept free under an anchored popover, matching the popover's own
   viewport margin."
  "12px")

(defn- popover-panel-style
  "An anchored popover's placement, capped so the panel never runs past the
   viewport: it scrolls inside instead, and the Transfer actions stay in view
   in its sticky footer."
  [popover-style]
  (when (map? popover-style)
    (cond-> popover-style
      (string? (:top popover-style))
      (assoc :max-height (str "calc(100vh - " (:top popover-style) " - "
                              popover-bottom-reserve ")")))))

(defn- render-desktop-shell
  [anchored-popover? popover-style focus-on-render extra-panel-classes panel-children]
  (let [layer-classes (into ["fixed" "inset-0" funding-modal-layer-z]
                            (if anchored-popover?
                              ["pointer-events-none"]
                              ["flex" "items-center" "justify-center" "p-4"]))
        backdrop-classes (into ["absolute" "inset-0"]
                               (if anchored-popover?
                                 ["pointer-events-auto" "bg-transparent"]
                                 ["bg-black/65"]))
        panel-classes (into ["relative"
                             funding-modal-panel-z
                             "space-y-3"
                             "border"
                             "border-ho-border-accent"
                             "bg-ho-bg-deep"
                             "shadow-2xl"
                             "pointer-events-auto"
                             "max-h-[calc(100vh-24px)]"
                             "overflow-y-auto"]
                            (concat (if anchored-popover?
                                      ["rounded-2xl" "p-4"]
                                      ["rounded-2xl" "p-4" "w-full" "max-w-md"])
                                    extra-panel-classes))]
    [:div {:class layer-classes
           :data-role "funding-modal-layer"}
     [:button {:type "button"
               :class backdrop-classes
               :aria-label "Close funding dialog"
               :on {:click [[:actions/close-funding-modal]]}}]
     (into [:div {:class panel-classes
                  :style (popover-panel-style popover-style)
                  :role "dialog"
                  :aria-modal true
                  :aria-labelledby funding-modal-title-id
                  :tabindex "-1"
                  :data-role "funding-modal"
                  :data-parity-id "funding-modal-desktop"
                  :replicant/on-render focus-on-render
                  :on {:keydown [[:actions/handle-funding-modal-keydown
                                  [:event/key]]]}}]
           (keep identity panel-children))]))

(defn render-funding-modal
  [{:keys [modal feedback] :as view-model}]
  (let [open? (:open? modal)]
    (when open?
      (let [{:keys [mobile-sheet?
                    anchored-popover?
                    popover-style
                    sheet-style]}
            (funding-modal-positioning/resolve-modal-layout modal)
            focus-on-render (funding-modal-focus-on-render (:mode modal))
            extra-panel-classes (if (= :transfer (:mode modal)) transfer-scroll-classes [])
            panel-children
            [[:div {:class ["flex" "items-center" "justify-between"]}
              [:h2 {:id funding-modal-title-id
                    :class ["text-lg" "font-semibold" "text-[#e5eef1]"]}
               (:title modal)]
              [:button {:type "button"
                        :aria-label "Close funding dialog"
                        :data-role "funding-modal-close"
                        :class (into ["h-8"
                                      "w-8"
                                      "leading-none"
                                      "text-xl"
                                      "transition-colors"
                                      "focus:outline-none"
                                      "focus:ring-1"
                                      "focus:ring-ho-accent-hi/40"
                                      "focus:ring-offset-0"
                                      "focus:shadow-none"]
                                     (if mobile-sheet?
                                       ["inline-flex"
                                        "items-center"
                                        "justify-center"
                                        "rounded-lg"
                                        "border"
                                        "border-ho-border-accent-muted"
                                        "bg-ho-bg"
                                        "text-gray-300"
                                        "hover:bg-[#102229]"
                                        "hover:text-gray-100"]
                                       ["rounded-md"
                                        "text-[#7f98a0]"
                                        "hover:bg-[#0f2834]"
                                        "hover:text-[#dce9ee]"]))
                        :on {:click [[:actions/close-funding-modal]]}}
               "×"]]
             (render-content view-model)
             ;; Transfer shows its message in its own form (next to the
             ;; sticky submit); presentation never makes this visible there.
             (when (:visible? feedback)
               [:div {:class ["rounded-md"
                              "border"
                              "border-ho-border-sell"
                              "bg-ho-sell-soft/55"
                              "px-3"
                              "py-2"
                              "text-sm"
                              "text-ho-sell-tint"]
                        :data-role "funding-status"}
                (:message feedback)])]]
        (if mobile-sheet?
          (render-mobile-shell sheet-style focus-on-render extra-panel-classes panel-children)
          (render-desktop-shell anchored-popover?
                                popover-style
                                focus-on-render
                                extra-panel-classes
                                panel-children))))))

(defn funding-modal-view
  [state]
  (render-funding-modal (funding-actions/funding-modal-view-model state)))
