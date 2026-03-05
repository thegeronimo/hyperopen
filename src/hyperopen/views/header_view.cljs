(ns hyperopen.views.header-view
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.wallet.core :as wallet]))

(def header-nav-link-base-classes
  ["header-nav-link"
   "transition-colors"
   "no-underline"])

(def header-nav-link-active-classes
  (into header-nav-link-base-classes
        ["header-nav-link-active"
         "hover:text-[#aefde8]"]))

(def header-nav-link-inactive-classes
  (into header-nav-link-base-classes
        ["text-white"
         "opacity-80"
         "hover:opacity-100"
         "hover:text-white"]))

(defn- nav-link [label route active?]
  [:a {:class (if active?
                header-nav-link-active-classes
                header-nav-link-inactive-classes)
       :href "#"
       :on {:click [[:actions/navigate route]]}}
   label])

(defn- route-active? [current-route target-route]
  (str/starts-with? (or current-route "") target-route))

(defn- funding-route-active?
  [current-route]
  (let [route (or current-route "")]
    (or (str/starts-with? route "/funding-comparison")
        (str/starts-with? route "/fundingComparison"))))

(defn- wallet-chevron-icon []
  [:svg {:viewBox "0 0 20 20"
         :fill "currentColor"
         :data-role "wallet-menu-chevron"
         :class ["h-4" "w-4" "text-gray-300" "transition-transform" "group-open:rotate-180"]}
   [:path {:fill-rule "evenodd"
           :clip-rule "evenodd"
           :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]])

(defn- wallet-copy-icon []
  [:svg {:viewBox "0 0 20 20"
         :fill "currentColor"
         :class ["h-4" "w-4" "text-gray-300"]
         :data-role "wallet-copy-icon-idle"}
   [:path {:d "M4 4a2 2 0 012-2h6a2 2 0 012 2v1h-2V4H6v8h1v2H6a2 2 0 01-2-2V4z"}]
   [:path {:d "M8 7a2 2 0 012-2h4a2 2 0 012 2v7a2 2 0 01-2 2h-4a2 2 0 01-2-2V7zm2 0h4v7h-4V7z"}]])

(defn- wallet-copy-feedback-row [copy-feedback]
  (let [kind (:kind copy-feedback)
        message (:message copy-feedback)
        text-classes (if (= :success kind)
                       ["text-success"]
                       ["text-error"])]
    [:div {:class (into ["flex"
                         "items-center"
                         "gap-1.5"
                         "px-3"
                         "pb-2"
                         "text-xs"
                         "font-medium"]
                        text-classes)
           :data-role "wallet-copy-feedback"}
     (if (= :success kind)
       [:svg {:viewBox "0 0 20 20"
              :fill "currentColor"
              :class ["h-3.5" "w-3.5"]
              :data-role "wallet-copy-feedback-success-icon"}
        [:path {:fill-rule "evenodd"
                :clip-rule "evenodd"
                :d "M16.707 5.293a1 1 0 010 1.414l-7.75 7.75a1 1 0 01-1.414 0l-3.25-3.25a1 1 0 011.414-1.414l2.543 2.543 7.043-7.043a1 1 0 011.414 0z"}]]
       [:svg {:viewBox "0 0 20 20"
              :fill "currentColor"
              :class ["h-3.5" "w-3.5"]
              :data-role "wallet-copy-feedback-error-icon"}
       [:path {:fill-rule "evenodd"
                :clip-rule "evenodd"
                :d "M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z"}]])
     [:span message]]))

(defn- trading-agent-status-row [agent-state]
  (let [status (:status agent-state)
        status-text (case status
                      :ready "Trading enabled"
                      :approving "Awaiting signature..."
                      :error "Trading setup failed"
                      "Trading not enabled")
        status-classes (case status
                         :ready ["text-success"]
                         :approving ["text-warning"]
                         :error ["text-error"]
                         ["text-gray-300"])]
    [:div {:class ["px-3" "pt-2" "pb-1.5"]}
     [:div {:class (into ["text-xs" "font-medium"] status-classes)
            :data-role "wallet-agent-status"}
      status-text]
     (when (seq (:error agent-state))
       [:div {:class ["mt-1" "text-xs" "text-error"]
              :data-role "wallet-agent-error"}
        (:error agent-state)])]))

(defn- agent-storage-mode-row [agent-state]
  (let [storage-mode (if (= :session (:storage-mode agent-state)) :session :local)
        persistent? (= :local storage-mode)
        next-mode (if persistent? :session :local)
        disabled? (= :approving (:status agent-state))]
    [:div {:class ["px-3" "pb-1.5" "pt-2"]
           :data-role "wallet-agent-storage-mode-row"}
     [:button {:type "button"
               :class ["flex"
                       "w-full"
                       "items-center"
                       "justify-between"
                       "gap-2"
                       "rounded-lg"
                       "border"
                       "border-base-300"
                       "bg-base-100"
                       "px-2.5"
                       "py-2"
                       "text-left"
                       "text-xs"
                       "text-gray-200"
                       "transition-colors"
                       "hover:bg-base-200"
                       "disabled:cursor-not-allowed"
                       "disabled:opacity-60"]
               :disabled disabled?
               :on {:click [[:actions/set-agent-storage-mode next-mode]]}
               :data-role "wallet-agent-storage-mode-toggle"}
      [:span "Persist trading key"]
      [:span {:class ["font-medium" "text-white"]
              :data-role "wallet-agent-storage-mode-value"}
       (if persistent? "Device" "Session")]]
     [:div {:class ["mt-1" "text-xs" "text-gray-400"]
            :data-role "wallet-agent-storage-mode-hint"}
      "Switching mode resets trading setup."]]))

(defn- enable-trading-button [agent-state]
  (let [status (:status agent-state)
        disabled? (= :approving status)]
    (when (not= :ready status)
      [:button {:type "button"
                :class ["mx-3"
                        "mb-2"
                        "mt-1"
                        "block"
                        "w-[calc(100%-1.5rem)]"
                        "rounded-lg"
                        "bg-teal-600"
                        "px-3"
                        "py-2"
                        "text-sm"
                        "font-medium"
                        "text-teal-100"
                        "transition-colors"
                        "hover:bg-teal-700"
                        "disabled:cursor-not-allowed"
                        "disabled:opacity-60"]
                :disabled disabled?
                :on {:click [[:actions/enable-agent-trading]]}
                :data-role "wallet-enable-trading"}
       (if disabled? "Awaiting signature..." "Enable Trading")])))

(defn- wallet-menu [wallet-address copy-feedback agent-state ghost-active? ghost-address]
  [:div {:class ["absolute"
                 "right-0"
                 "top-full"
                 "mt-2"
                 "w-48"
                 "overflow-hidden"
                 "rounded-xl"
                 "border"
                 "border-base-300"
                 "bg-trading-bg"
                 "opacity-100"
                 "isolate"
                 "shadow-2xl"
                 "z-[260]"]
         :data-role "wallet-menu-panel"}
   [:button {:type "button"
             :class ["flex"
                     "w-full"
                     "items-center"
                     "justify-between"
                     "gap-2"
                     "px-3"
                     "py-3"
                     "text-left"
                     "text-sm"
                     "text-white"
                     "transition-colors"
                     "hover:bg-base-200"
                     "focus:outline-none"]
             :on {:click [[:actions/copy-wallet-address]]}
             :title "Copy address"
             :aria-label "Copy address"
                     :data-role "wallet-menu-copy"}
    [:span {:class ["truncate" "num"]} (or (wallet/short-addr wallet-address) "Unavailable")]
    (wallet-copy-icon)]
   (when (and (map? copy-feedback)
              (seq (:message copy-feedback)))
     (wallet-copy-feedback-row copy-feedback))
   (agent-storage-mode-row agent-state)
   (trading-agent-status-row agent-state)
   (enable-trading-button agent-state)
   [:div {:class ["h-px" "w-full" "bg-base-300"]}]
   [:button {:type "button"
             :class ["block"
                     "w-full"
                     "px-3"
                     "py-3"
                     "text-left"
                     "text-sm"
                     "font-medium"
                     "text-[#96f8e0]"
                     "transition-colors"
                     "hover:bg-base-200"
                     "focus:outline-none"]
             :on {:click [[:actions/open-ghost-mode-modal :event.currentTarget/bounds]]}
             :data-ghost-mode-trigger "true"
             :data-role "wallet-menu-open-ghost-mode"}
    (if ghost-active? "Manage Ghost Mode" "Open Ghost Mode")]
   (when ghost-active?
     [:div {:class ["px-3"
                    "pb-2"
                    "text-xs"
                    "text-[#9fb4b9]"]
            :data-role "wallet-menu-ghost-active-address"}
      [:span {:class ["num"]}
       (or (wallet/short-addr ghost-address) ghost-address)]])
   [:div {:class ["h-px" "w-full" "bg-base-300"]}]
   [:button {:type "button"
             :class ["block"
                     "w-full"
                     "px-3"
                     "py-3"
                     "text-left"
                     "text-sm"
                     "font-medium"
                     "text-[#50f6d2]"
                     "transition-colors"
                     "hover:bg-base-200"
                     "focus:outline-none"]
             :on {:click [[:actions/disconnect-wallet]]}
             :data-role "wallet-menu-disconnect"}
    "Disconnect"]])

(defn- wallet-trigger [wallet-address]
  [:summary {:class ["relative"
                     "z-[170]"
                     "inline-flex"
                     "h-10"
                     "items-center"
                     "gap-2"
                     "rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100"
                     "px-3"
                     "text-sm"
                     "text-white"
                     "transition-colors"
                     "hover:bg-base-200"
                     "list-none"
                     "cursor-pointer"]
             :data-role "wallet-menu-trigger"
             :aria-haspopup "menu"}
   [:span {:class ["num"]} (or (wallet/short-addr wallet-address) "Connected")]
   (wallet-chevron-icon)])

(defn- connect-wallet-button [is-connecting]
  [:button {:class ["bg-teal-600"
                    "hover:bg-teal-700"
                    "text-teal-100"
                    "px-4"
                    "py-2"
                    "rounded-lg"
                    "font-medium"
                    "transition-colors"]
            :disabled (boolean is-connecting)
            :on {:click [[:actions/connect-wallet]]}
            :data-role "wallet-connect-button"}
   (if is-connecting "Connecting…" "Connect Wallet")])

(defn- ghost-mode-icon []
  [:svg {:viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.9"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :class ["h-5" "w-5"]
         :data-role "ghost-mode-trigger-icon"}
   [:path {:d "M9 10h.01"}]
   [:path {:d "M15 10h.01"}]
   [:path {:d "M12 2a7 7 0 0 0-7 7v10l2-2 2 2 2-2 2 2 2-2 2 2V9a7 7 0 0 0-7-7z"}]])

(defn- ghost-mode-trigger-button
  [active?]
  [:button {:type "button"
            :class (into ["inline-flex"
                          "h-10"
                          "items-center"
                          "gap-2"
                          "rounded-xl"
                          "border"
                          "px-3"
                          "text-sm"
                          "transition-colors"]
                         (if active?
                           ["border-[#2c5d5a]"
                            "bg-[#0d3a35]"
                            "text-[#daf3ef]"
                            "hover:bg-[#115046]"]
                           ["border-base-300"
                            "bg-base-100"
                            "text-white"
                            "hover:bg-base-200"]))
            :on {:click [[:actions/open-ghost-mode-modal :event.currentTarget/bounds]]}
            :data-ghost-mode-trigger "true"
            :data-role "ghost-mode-open-button"}
   (ghost-mode-icon)
   [:span (if active? "Spectating" "Ghost Mode")]])

(defn- wallet-control [wallet-state ghost-mode]
  (let [is-connected (boolean (:connected? wallet-state))
        wallet-address (:address wallet-state)
        copy-feedback (:copy-feedback wallet-state)
        agent-state (:agent wallet-state)
        is-connecting (boolean (:connecting? wallet-state))]
    (if is-connected
      [:details {:class ["relative" "group"] :data-role "wallet-menu-details"}
       (wallet-trigger wallet-address)
       (wallet-menu wallet-address
                    copy-feedback
                    agent-state
                    (true? (:active? ghost-mode))
                    (:address ghost-mode))]
      (connect-wallet-button is-connecting))))

(defn header-view [state]
  (let [wallet-state (get-in state [:wallet] {})
        route (get-in state [:router :path] "/trade")
        ghost-active? (account-context/ghost-mode-active? state)
        ghost-mode {:active? ghost-active?
                    :address (account-context/ghost-address state)}]
    [:header.bg-base-200.border-b.border-base-300.w-full
     {:data-parity-id "header"}
     [:div {:class ["w-full" "app-shell-gutter" "py-3"]}
      [:div.flex.items-center
       ;; Logo and Brand
       [:div.flex.items-center.space-x-3
        [:span.text-primary.text-3xl.font-bold.font-splash "HyperOpen"]]

       ;; Navigation Links
       [:nav.hidden.md:flex.flex-1.items-center.justify-start.space-x-8.ml-8
        {:data-parity-id "header-nav"}
        (nav-link "Trade" "/trade" (route-active? route "/trade"))
        (nav-link "Portfolio" "/portfolio" (route-active? route "/portfolio"))
        (nav-link "Funding" "/funding-comparison" (funding-route-active? route))
        (nav-link "Earn" "/earn" false)
        (nav-link "Vaults" "/vaults" (route-active? route "/vaults"))
        (nav-link "Staking" "/staking" false)
        (nav-link "Referrals" "/referrals" false)
        (nav-link "Leaderboard" "/leaderboard" false)
        [:div.relative.group
         [:a {:class (into header-nav-link-inactive-classes ["flex" "items-center" "space-x-1"])
              :href "#"
              :on {:click [[:actions/navigate "/more"]]}}
          [:span "More"]
          [:svg.w-4.h-4 {:viewBox "0 0 20 20" :fill "currentColor"}
           [:path {:fill-rule "evenodd" :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" :clip-rule "evenodd"}]]]
         ;; Dropdown menu would go here
         ]]

       ;; Right Section - Wallet Control and Icons
       [:div.flex.items-center.space-x-4
        {:data-parity-id "header-wallet-control"}
        (ghost-mode-trigger-button ghost-active?)
        (wallet-control wallet-state ghost-mode)

        ;; Utility Icons
        [:button.w-10.h-10.bg-base-300.hover:bg-base-400.rounded-lg.flex.items-center.justify-center.transition-colors
         {:title "Language/Region"}
         [:svg.w-5.h-5.text-white {:viewBox "0 0 20 20" :fill "currentColor"}
          [:path {:fill-rule "evenodd" :d "M10 18a8 8 0 100-16 8 8 0 000 16zM4.332 8.027a6.012 6.012 0 011.912-2.706C6.512 5.73 6.974 6 7.5 6A1.5 1.5 0 019 7.5V8a2 2 0 004 0 2 2 0 011.523-1.943A5.977 5.977 0 0116 10c0 .34-.028.675-.083 1H15a2 2 0 00-2 2v2.197A5.973 5.973 0 0110 16v-2a2 2 0 00-2-2 2 2 0 01-2-2 2 2 0 00-1.668-1.973z" :clip-rule "evenodd"}]]]

        [:button.w-10.h-10.bg-base-300.hover:bg-base-400.rounded-lg.flex.items-center.justify-center.transition-colors
         {:title "Settings"}
         [:svg.w-5.h-5.text-white {:viewBox "0 0 20 20" :fill "currentColor"}
          [:path {:fill-rule "evenodd" :d "M11.49 3.17c-.38-1.56-2.6-1.56-2.98 0a1.532 1.532 0 01-2.286.948c-1.372-.836-2.942.734-2.106 2.106.54.886.061 2.042-.947 2.287-1.561.379-1.561 2.6 0 2.978a1.532 1.532 0 01.947 2.287c-.836 1.372.734 2.942 2.106 2.106a1.532 1.532 0 012.287.947c.379 1.561 2.6 1.561 2.978 0a1.533 1.533 0 012.287-.947c1.372.836 2.942-.734 2.106-2.106a1.533 1.533 0 01.947-2.287c1.561-.379 1.561-2.6 0-2.978a1.532 1.532 0 01-.947-2.287c.836-1.372-.734-2.942-2.106-2.106a1.532 1.532 0 01-2.287-.947zM10 13a3 3 0 100-6 3 3 0 000 6z" :clip-rule "evenodd"}]]]

        ;; Mobile menu button
        [:button.md:hidden.w-10.h-10.bg-base-300.hover:bg-base-400.rounded-lg.flex.items-center.justify-center.transition-colors
         {:title "Menu"}
         [:svg.w-5.h-5.text-white {:viewBox "0 0 20 20" :fill "currentColor"}
          [:path {:fill-rule "evenodd" :d "M3 5a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM3 10a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM3 15a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1z" :clip-rule "evenodd"}]]]]]]]))
