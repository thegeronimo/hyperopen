(ns hyperopen.views.header-view
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.platform :as platform]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.wallet.agent-session :as agent-session]
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

(def brand-mark-classes
  ["text-primary"
   "font-black"
   "tracking-[-0.12em]"
   "italic"
   "select-none"])

(def brand-wordmark-classes
  ["text-primary"
   "font-black"
   "tracking-[-0.06em]"
   "italic"
   "select-none"])

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

(defn- more-trigger-classes
  [active?]
  (if active?
    (into header-nav-link-active-classes
          ["group"
           "flex"
           "items-center"
           "space-x-1"
           "list-none"
           "cursor-pointer"])
    (into header-nav-link-inactive-classes
          ["group"
           "flex"
           "items-center"
           "space-x-1"
           "list-none"
           "cursor-pointer"])))

(defn- more-menu-link
  [label route active?]
  [:button {:type "button"
            :class (into ["flex"
                          "w-full"
                          "items-center"
                          "justify-between"
                          "gap-3"
                          "rounded-lg"
                          "px-3"
                          "py-2"
                          "text-left"
                          "text-sm"
                          "transition-colors"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                          (if active?
                            ["bg-[#123a36]" "text-[#97fce4]"]
                            ["text-white" "hover:bg-base-200"]))
            :data-role (str "header-more-link-" (str/lower-case label))
            :on {:click [[:actions/navigate route]]}}
     [:span label]
     (when active?
     [:span {:class ["text-xs"
                     "font-semibold"
                     "uppercase"
                     "tracking-[0.08em]"]}
      "Open"])])

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

(defn- wallet-menu [wallet-address copy-feedback agent-state]
  (let [enable-trading-cta (enable-trading-button agent-state)]
    [:div {:class ["ui-dropdown-panel"
                   "absolute"
                   "right-0"
                   "top-full"
                   "mt-2"
                   "w-48"
                   "overflow-hidden"
                   "rounded-xl"
                   "border"
                   "border-base-300"
                   "bg-trading-bg"
                   "isolate"
                   "shadow-2xl"
                   "z-[260]"]
           :data-ui-native-details-panel "true"
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
     (when enable-trading-cta
       enable-trading-cta)
     (when enable-trading-cta
       [:div {:class ["h-px" "w-full" "bg-base-300"]}])
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
      "Disconnect"]]))

(defn- wallet-trigger [wallet-address]
  [:summary {:class ["relative"
                     "z-[170]"
                     "inline-flex"
                     "h-9"
                     "sm:h-10"
                     "items-center"
                     "gap-2"
                     "rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100"
                     "px-2.5"
                     "sm:px-3"
                     "text-xs"
                     "sm:text-sm"
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
  (into
   [:button {:class ["bg-teal-600"
                     "hover:bg-teal-700"
                     "text-teal-100"
                     "inline-flex"
                     "h-9"
                     "items-center"
                     "justify-center"
                     "px-3"
                     "rounded-lg"
                     "text-xs"
                     "sm:text-sm"
                     "font-medium"
                     "transition-colors"]
             :disabled (boolean is-connecting)
             :on {:click [[:actions/connect-wallet]]}
             :data-role "wallet-connect-button"}]
   (if is-connecting
     [[:span {:class ["sm:hidden"]} "Connecting…"]
      [:span {:class ["hidden" "sm:inline"]} "Connecting…"]]
     [[:span {:class ["sm:hidden"]} "Connect"]
      [:span {:class ["hidden" "sm:inline"]} "Connect Wallet"]])))

(defn- spectate-mode-icon []
  [:svg {:viewBox "0 0 256 256"
         :fill "currentColor"
         :class ["h-5" "w-5" "shrink-0"]
         :data-role "spectate-mode-trigger-icon"}
   [:path {:d "M237.22,151.9l0-.1a1.42,1.42,0,0,0-.07-.22,48.46,48.46,0,0,0-2.31-5.3L193.27,51.8a8,8,0,0,0-1.67-2.44,32,32,0,0,0-45.26,0A8,8,0,0,0,144,55V80H112V55a8,8,0,0,0-2.34-5.66,32,32,0,0,0-45.26,0,8,8,0,0,0-1.67,2.44L21.2,146.28a48.46,48.46,0,0,0-2.31,5.3,1.72,1.72,0,0,0-.07.21s0,.08,0,.11a48,48,0,0,0,90.32,32.51,47.49,47.49,0,0,0,2.9-16.59V96h32v71.83a47.49,47.49,0,0,0,2.9,16.59,48,48,0,0,0,90.32-32.51Zm-143.15,27a32,32,0,0,1-60.2-21.71l1.81-4.13A32,32,0,0,1,96,167.88V168h0A32,32,0,0,1,94.07,178.94ZM203,198.07A32,32,0,0,1,160,168h0v-.11a32,32,0,0,1,60.32-14.78l1.81,4.13A32,32,0,0,1,203,198.07Z"}]])

(def ^:private spectate-mode-trigger-tooltip-id
  "spectate-mode-open-tooltip")

(defn- spectate-mode-trigger-tooltip-copy
  [active?]
  (if active?
    "Spectate Mode is active. Click to manage the address you are viewing or stop spectating."
    "Inspect another wallet in read-only mode. Click to open Spectate Mode and choose an address."))

(defn- spectate-mode-trigger-button
  [active?]
  (let [button-label (if active?
                       "Manage Spectate Mode"
                       "Open Spectate Mode")
        tooltip-copy (spectate-mode-trigger-tooltip-copy active?)]
    [:div {:class ["relative" "inline-flex" "group"]
           :data-role "spectate-mode-trigger"}
     [:button {:type "button"
               :class (into ["inline-flex"
                             "h-9"
                             "w-9"
                             "sm:h-10"
                             "sm:w-10"
                             "items-center"
                             "justify-center"
                             "rounded-xl"
                             "border"
                             "transition-colors"
                             "focus:outline-none"
                             "focus:ring-2"
                             "focus:ring-[#66e3c5]/50"
                             "focus:ring-offset-0"]
                            (if active?
                              ["border-[#2c5d5a]"
                               "bg-[#0d3a35]"
                               "text-[#daf3ef]"
                               "hover:bg-[#115046]"]
                              ["border-base-300"
                               "bg-base-100"
                               "text-white"
                               "hover:bg-base-200"]))
               :on {:click [[:actions/open-spectate-mode-modal :event.currentTarget/bounds]]}
               :aria-label button-label
               :aria-describedby spectate-mode-trigger-tooltip-id
               :data-spectate-mode-trigger "true"
               :data-role "spectate-mode-open-button"}
      (spectate-mode-icon)]
     [:div {:id spectate-mode-trigger-tooltip-id
            :role "tooltip"
            :class ["pointer-events-none"
                    "absolute"
                    "right-0"
                    "top-full"
                    "z-[260]"
                    "mt-2"
                    "w-64"
                    "translate-y-1"
                    "rounded-lg"
                    "border"
                    "border-[#264b4f]"
                    "bg-[#0b1619]"
                    "px-3"
                    "py-2.5"
                    "text-left"
                    "text-xs"
                    "leading-5"
                    "text-[#d2e8eb]"
                    "opacity-0"
                    "shadow-2xl"
                    "transition-all"
                    "duration-150"
                    "group-hover:translate-y-0"
                    "group-hover:opacity-100"
                    "group-focus-within:translate-y-0"
                    "group-focus-within:opacity-100"]
            :data-role "spectate-mode-open-tooltip"}
      [:div {:class ["absolute"
                     "-top-1.5"
                     "right-3"
                     "h-3"
                     "w-3"
                     "rotate-45"
                     "border-l"
                     "border-t"
                     "border-[#264b4f]"
                     "bg-[#0b1619]"]}]
      tooltip-copy]]))

(defn- mobile-menu-icon []
  [:svg {:viewBox "0 0 20 20"
         :fill "currentColor"
         :class ["h-5" "w-5" "text-white"]}
   [:path {:fill-rule "evenodd"
           :clip-rule "evenodd"
           :d "M3 5a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM3 10a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM3 15a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1z"}]])

(defn- globe-icon []
  [:svg {:viewBox "0 0 20 20"
         :fill "currentColor"
         :class ["h-[18px]" "w-[18px]" "text-white"]}
   [:path {:fill-rule "evenodd"
           :clip-rule "evenodd"
           :d "M10 18a8 8 0 100-16 8 8 0 000 16zM4.332 8.027a6.012 6.012 0 011.912-2.706C6.512 5.73 6.974 6 7.5 6A1.5 1.5 0 019 7.5V8a2 2 0 004 0 2 2 0 011.523-1.943A5.977 5.977 0 0116 10c0 .34-.028.675-.083 1H15a2 2 0 00-2 2v2.197A5.973 5.973 0 0110 16v-2a2 2 0 00-2-2 2 2 0 01-2-2 2 2 0 00-1.668-1.973z"}]])

(defn- settings-icon []
  [:svg {:viewBox "0 0 20 20"
         :fill "currentColor"
         :class ["h-[18px]" "w-[18px]" "text-white"]}
   [:path {:fill-rule "evenodd"
           :clip-rule "evenodd"
           :d "M11.49 3.17c-.38-1.56-2.6-1.56-2.98 0a1.532 1.532 0 01-2.286.948c-1.372-.836-2.942.734-2.106 2.106.54.886.061 2.042-.947 2.287-1.561.379-1.561 2.6 0 2.978a1.532 1.532 0 01.947 2.287c-.836 1.372.734 2.942 2.106 2.106a1.532 1.532 0 012.287.947c.379 1.561 2.6 1.561 2.978 0a1.533 1.533 0 012.287-.947c1.372.836 2.942-.734 2.106-2.106a1.533 1.533 0 01.947-2.287c1.561-.379 1.561-2.6 0-2.978a1.532 1.532 0 01-.947-2.287c.836-1.372-.734-2.942-2.106-2.106a1.532 1.532 0 01-2.287-.947zM10 13a3 3 0 100-6 3 3 0 000 6z"}]])

(defn- utility-icon-button
  ([title body data-role]
   (utility-icon-button title body data-role {}))
  ([title body data-role extra-attrs]
   [:button (merge
             {:type "button"
              :class ["inline-flex"
                      "h-9"
                      "w-9"
                      "items-center"
                      "justify-center"
                      "rounded-xl"
                      "border"
                      "border-base-300"
                      "bg-base-100"
                      "transition-colors"
                      "hover:bg-base-200"]
              :title title
              :aria-label title
              :data-role data-role}
             extra-attrs)
    body]))

(def ^:private trading-settings-footer-copy
  "Applies only to this browser on this device.")

(def ^:private trading-settings-close-actions
  [[:actions/close-header-settings]])

(defn- trading-settings-storage-mode
  [state]
  (if-some [storage-mode (get-in state [:wallet :agent :storage-mode])]
    (agent-session/normalize-storage-mode storage-mode)
    :session))

(defn- remember-trading-session?
  [state]
  (= :local (trading-settings-storage-mode state)))

(defn- focus-visible-settings-surface!
  [node]
  (when node
    (platform/queue-microtask!
     (fn []
       (when (and (.-isConnected node)
                  (not= "none"
                        (some-> (js/getComputedStyle node) .-display)))
         (.focus node))))))

(defn- focus-settings-trigger!
  [node]
  (when node
    (platform/queue-microtask!
     (fn []
       (when (and (.-isConnected node)
                  (not= "none"
                        (some-> (js/getComputedStyle node) .-display)))
         (.focus node))))))

(defn- agent-storage-confirmation-copy
  [confirmation]
  (case (some-> (:next-mode confirmation)
                agent-session/normalize-storage-mode)
    :local
    {:title "Remember session on this device?"
     :body "Changes trading persistence on this device and will require Enable Trading again."
     :confirm-label "Change"}

    :session
    {:title "Use session-only trading?"
     :body "Changes trading persistence for this browser session and will require Enable Trading again."
     :confirm-label "Change"}

    nil))

(defn- trading-settings-close-icon []
  [:svg {:viewBox "0 0 20 20"
         :fill "none"
         :stroke "currentColor"
         :class ["h-4.5" "w-4.5"]}
   [:path {:stroke-linecap "round"
           :stroke-linejoin "round"
           :stroke-width 1.8
           :d "M5 5l10 10M15 5L5 15"}]])

(defn- trading-settings-row-icon
  [kind active?]
  (let [icon-classes (into ["h-5" "w-5" "shrink-0"]
                           (if active?
                             ["text-[#9ba4ac]"]
                             ["text-[#7f8991]"]))]
    (case kind
      :session
      [:svg {:viewBox "0 0 20 20"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:circle {:cx "10" :cy "10" :r "6.5" :stroke-width "1.7"}]
       [:path {:d "M10 6.7v3.5l2.4 1.4"
               :stroke-width "1.7"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]]

      :alerts
      [:svg {:viewBox "0 0 20 20"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:path {:d "M10 4.4a3 3 0 0 0-3 3v1.1c0 .9-.24 1.8-.7 2.56L5.15 13h9.7l-1.15-1.94A4.94 4.94 0 0 1 13 8.5V7.4a3 3 0 0 0-3-3Z"
               :stroke-width "1.6"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]
       [:path {:d "M8.5 14.55a1.7 1.7 0 0 0 3 0"
               :stroke-width "1.6"
               :stroke-linecap "round"}]]

      :animate-orderbook
      [:svg {:viewBox "0 0 20 20"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:path {:d "M3.75 13.5h3.15l2.05-5.2 2.8 7 2.1-4.05h2.4"
               :stroke-width "1.75"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]
       [:path {:d "M14.9 5.2h1.9v1.9"
               :stroke-width "1.75"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]]

      :fill-markers
      [:svg {:viewBox "0 0 20 20"
             :fill "none"
             :stroke "currentColor"
             :class icon-classes}
       [:path {:d "M10 16.1s4.2-4.15 4.2-7.2A4.2 4.2 0 0 0 5.8 8.9c0 3.05 4.2 7.2 4.2 7.2Z"
               :stroke-width "1.65"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]
       [:circle {:cx "10" :cy "8.8" :r "1.35" :stroke-width "1.6"}]]

      nil)))

(defn- trading-settings-icon-shell
  [kind active?]
  [:div {:class ["mt-0.5"
                 "flex"
                 "h-5"
                 "w-5"
                 "shrink-0"
                 "items-center"
                 "justify-center"]}
   (trading-settings-row-icon kind active?)])

(defn- trading-settings-toggle
  [{:keys [aria-label checked? on-change]}]
  [:label {:class ["relative"
                   "mt-0.5"
                   "inline-flex"
                   "h-[22px]"
                   "w-[42px]"
                   "shrink-0"
                   "cursor-pointer"
                   "items-center"]}
   [:input {:type "checkbox"
            :checked (boolean checked?)
            :class ["peer"
                    "absolute"
                    "inset-0"
                    "h-full"
                    "w-full"
                    "cursor-pointer"
                    "opacity-0"]
            :aria-label aria-label
            :on {:change on-change}}]
   [:span {:aria-hidden true
           :class (into ["absolute"
                         "inset-0"
                         "rounded-full"
                         "border"
                         "shadow-[inset_0_1px_0_rgba(255,255,255,0.05),inset_0_-1px_1px_rgba(0,0,0,0.18)]"
                         "transition-all"
                         "duration-200"
                         "peer-focus-visible:ring-2"
                         "peer-focus-visible:ring-[#66e3c5]/45"
                         "peer-focus-visible:ring-offset-0"]
                        (if checked?
                          ["border-[#67e5ba]"
                           "bg-[#67e5ba]"]
                          ["border-[#434b52]"
                           "bg-[#2b3238]"]))}]
   [:span {:aria-hidden true
           :class (into ["absolute"
                         "left-[3px]"
                         "top-[3px]"
                         "h-4"
                         "w-4"
                         "rounded-full"
                         "shadow-[0_1px_4px_rgba(0,0,0,0.32)]"
                         "transition-transform"
                         "duration-200"]
                        (if checked?
                          ["translate-x-[18px]" "bg-[#1e262b]"]
                          ["translate-x-0" "bg-[#69727a]"]))}]])

(defn- trading-settings-section
  [{:keys [data-role title]} & children]
  [:section {:class ["overflow-hidden"
                     "rounded-[14px]"
                     "border"
                     "border-[#394047]"
                     "bg-[#262d33]"
                     "shadow-[inset_0_1px_0_rgba(255,255,255,0.03),0_8px_18px_rgba(0,0,0,0.22)]"]
             :data-role data-role}
   [:div {:class ["px-4"
                  "pt-3.5"
                  "text-[0.68rem]"
                  "font-semibold"
                  "uppercase"
                  "tracking-[0.14em]"
                  "text-[#8a939b]"]}
    title]
   children])

(defn- trading-settings-confirmation-strip
  [confirmation]
  (when-let [{:keys [title body confirm-label]}
             (agent-storage-confirmation-copy confirmation)]
    [:div {:class ["mt-3"
                   "flex"
                   "items-start"
                   "justify-between"
                   "gap-3"
                   "rounded-[12px]"
                   "border"
                   "border-[#394047]"
                   "bg-[#22282d]"
                   "shadow-[inset_0_1px_0_rgba(255,255,255,0.02),0_6px_14px_rgba(0,0,0,0.18)]"
                   "px-3"
                   "py-3"]
           :data-role "trading-settings-storage-mode-confirmation"}
     [:div {:class ["min-w-0" "space-y-1"]}
      [:div {:class ["text-[0.84rem]" "font-semibold" "text-white"]}
       title]
      [:p {:class ["text-[0.76rem]" "leading-5" "text-[#9ba1a6]"]}
       body]]
     [:div {:class ["flex" "shrink-0" "gap-2"]}
      [:button {:type "button"
                :class ["rounded-lg"
                        "border"
                        "border-[#32373d]"
                        "bg-transparent"
                        "px-3"
                        "py-1.5"
                        "text-[0.8rem]"
                        "font-medium"
                        "text-[#d9dfe4]"
                        "transition-colors"
                        "hover:bg-[#2c3338]"
                        "hover:text-white"]
                :on {:click [[:actions/cancel-agent-storage-mode-change]]}}
       "Cancel"]
      [:button {:type "button"
                :class ["rounded-lg"
                        "border"
                        "border-[#44ccb5]/35"
                        "bg-[#18342e]"
                        "shadow-[inset_0_1px_0_rgba(255,255,255,0.03)]"
                        "px-3"
                        "py-1.5"
                        "text-[0.8rem]"
                        "font-medium"
                        "text-[#a9f5e4]"
                        "transition-colors"
                        "hover:bg-[#21473f]"]
                :on {:click [[:actions/confirm-agent-storage-mode-change]]}}
       confirm-label]]]))

(defn- trading-settings-row
  [{:keys [aria-label
           checked?
           data-role
           helper-copy
           icon-kind
           on-change
           title]}
   body]
  [:div {:class ["px-3" "py-2"]
         :data-role data-role}
   [:div {:class ["flex"
                  "items-start"
                  "gap-3"
                  "rounded-[10px]"
                  "px-1"
                  "py-1.5"
                  "transition-colors"
                  "hover:bg-[#20262b]"]}
    (trading-settings-icon-shell icon-kind checked?)
    [:div {:class ["min-w-0" "flex-1" "space-y-1" "pt-0.5"]}
     [:div {:class ["text-[0.95rem]" "font-semibold" "leading-5" "text-white"]}
      title]
     [:p {:class ["text-[0.76rem]" "leading-5" "text-[#9ba1a6]"]}
      helper-copy]]
    [:div {:class ["flex" "shrink-0" "items-start" "pt-1"]}
     (trading-settings-toggle {:aria-label aria-label
                               :checked? checked?
                               :on-change on-change})]]
   body])

(defn- trading-settings-content
  [state surface-id]
  (let [remember? (remember-trading-session? state)
        fill-alerts-enabled? (trading-settings/fill-alerts-enabled? state)
        animate-orderbook? (trading-settings/animate-orderbook? state)
        show-fill-markers? (trading-settings/show-fill-markers? state)
        confirmation (get-in state [:header-ui :settings-confirmation])]
    [:div {:class (into ["flex" "max-h-full" "flex-col"]
                        (when (= surface-id :sheet)
                          ["pb-[max(0.5rem,env(safe-area-inset-bottom))]"]))}
     [:div {:class ["flex"
                    "items-center"
                    "justify-between"
                    "gap-4"
                    "px-4"
                    "pb-2"
                    "pt-4"]}
      [:h3 {:class ["text-[1rem]" "font-semibold" "tracking-[-0.01em]" "text-white"]
            :data-role "trading-settings-title"}
       "Trading settings"]
      [:button {:type "button"
                :class ["inline-flex"
                        "h-8.5"
                        "w-8.5"
                        "items-center"
                        "justify-center"
                        "rounded-[10px]"
                        "border"
                        "border-[#40484f]"
                        "bg-[#2a3137]"
                        "shadow-[inset_0_1px_0_rgba(255,255,255,0.03)]"
                        "text-[#aeb6bc]"
                        "transition-colors"
                        "hover:bg-[#333b42]"
                        "hover:text-white"]
                :aria-label "Close trading settings"
                :data-role "trading-settings-close"
                :on {:click trading-settings-close-actions}}
       (trading-settings-close-icon)]]
     [:div {:class ["overflow-y-auto" "px-3" "pb-3" "pt-1"]}
      [:div {:class ["space-y-3"]}
       (trading-settings-section
        {:title "Session"
         :data-role "trading-settings-session-section"}
        (trading-settings-row
         {:title "Remember session"
          :helper-copy "Keep trading enabled across browser restarts on this device."
          :checked? remember?
          :icon-kind :session
          :aria-label "Remember session"
          :data-role "trading-settings-storage-mode-row"
          :on-change [[:actions/request-agent-storage-mode-change :event.target/checked]]}
         (trading-settings-confirmation-strip confirmation)))
       (trading-settings-section
        {:title "Alerts"
         :data-role "trading-settings-alerts-section"}
        (trading-settings-row
         {:title "Fill alerts"
          :helper-copy "Show fill alerts while Hyperopen is open."
          :checked? fill-alerts-enabled?
          :icon-kind :alerts
          :aria-label "Fill alerts"
          :data-role "trading-settings-fill-alerts-row"
          :on-change [[:actions/set-fill-alerts-enabled :event.target/checked]]}
         nil))
       (trading-settings-section
        {:title "Display"
         :data-role "trading-settings-display-section"}
        (trading-settings-row
         {:title "Animate order book"
          :helper-copy "Smooth bid and ask depth changes as the book updates."
          :checked? animate-orderbook?
          :icon-kind :animate-orderbook
          :aria-label "Animate order book"
          :data-role "trading-settings-animate-orderbook-row"
          :on-change [[:actions/set-animate-orderbook-enabled :event.target/checked]]}
         nil)
        [:div {:class ["mx-4" "h-px" "bg-[#353b42]"]}]
        (trading-settings-row
         {:title "Fill markers"
          :helper-copy "Show buy and sell markers for the active asset on the chart."
          :checked? show-fill-markers?
          :icon-kind :fill-markers
          :aria-label "Fill markers"
          :data-role "trading-settings-fill-markers-row"
          :on-change [[:actions/set-fill-markers-enabled :event.target/checked]]}
         nil)
        [:div {:class ["mx-4" "h-px" "bg-[#353b42]"]}]
        [:div {:class ["px-4" "py-3" "text-[0.75rem]" "leading-5" "text-[#9ba1a6]"]
               :data-role "trading-settings-footer-note"}
         trading-settings-footer-copy])]]]))

(defn- trading-settings-shell
  [state]
  (list
   [:button {:type "button"
             :class ["fixed" "inset-0" "z-[275]" "bg-black/45"]
             :style {:transition "opacity 0.2s ease-out"
                     :opacity 1}
             :replicant/mounting {:style {:opacity 0}}
             :replicant/unmounting {:style {:opacity 0}}
             :aria-label "Dismiss trading settings"
             :data-role "trading-settings-backdrop"
             :on {:click trading-settings-close-actions}}]
   [:section {:class ["absolute"
                      "right-0"
                      "top-full"
                      "z-[285]"
                      "mt-2"
                      "hidden"
                      "w-[336px]"
                      "max-h-[70vh]"
                      "max-w-[calc(100vw-1.5rem)]"
                      "overflow-hidden"
                      "rounded-[16px]"
                      "border"
                      "border-[#394147]"
                      "bg-[#20272d]"
                      "shadow-[inset_0_1px_0_rgba(255,255,255,0.03),0_20px_40px_rgba(0,0,0,0.42)]"
                      "md:block"]
              :role "dialog"
              :aria-modal true
              :aria-label "Trading settings"
              :tab-index 0
              :data-role "trading-settings-panel"
              :style {:transition "transform 0.22s cubic-bezier(0.22,1,0.36,1), opacity 0.22s ease-out"
                      :transform "translateY(0) scale(1)"
                      :transform-origin "top right"
                      :opacity 1}
              :replicant/mounting {:style {:transform "translateY(-8px) scale(0.97)"
                                           :opacity 0}}
              :replicant/unmounting {:style {:transform "translateY(-8px) scale(0.97)"
                                             :opacity 0}}
              :on {:keydown [[:actions/handle-header-settings-keydown [:event/key]]]}
              :replicant/on-render focus-visible-settings-surface!}
    (trading-settings-content state :panel)]
   [:section {:class ["fixed"
                      "inset-x-3"
                      "bottom-3"
                      "z-[285]"
                      "max-h-[76vh]"
                      "overflow-hidden"
                      "rounded-[18px]"
                      "border"
                      "border-[#394147]"
                      "bg-[#20272d]"
                      "shadow-[inset_0_1px_0_rgba(255,255,255,0.03),0_20px_40px_rgba(0,0,0,0.42)]"
                      "md:hidden"]
              :role "dialog"
              :aria-modal true
              :aria-label "Trading settings"
              :tab-index 0
              :data-role "trading-settings-sheet"
              :style {:transition "transform 0.22s cubic-bezier(0.22,1,0.36,1), opacity 0.22s ease-out"
                      :transform "translateY(0)"
                      :opacity 1}
              :replicant/mounting {:style {:transform "translateY(18px)"
                                           :opacity 0}}
              :replicant/unmounting {:style {:transform "translateY(18px)"
                                             :opacity 0}}
              :on {:keydown [[:actions/handle-header-settings-keydown [:event/key]]]}
              :replicant/on-render focus-visible-settings-surface!}
    (trading-settings-content state :sheet)]))

(def ^:private mobile-primary-nav-items
  [{:label "Trade"
    :route "/trade"
    :data-role "mobile-header-menu-link-trade"
    :active-fn #(route-active? % "/trade")}
   {:label "Portfolio"
    :route "/portfolio"
    :data-role "mobile-header-menu-link-portfolio"
    :active-fn #(route-active? % "/portfolio")}
   {:label "Funding"
    :route "/funding-comparison"
    :data-role "mobile-header-menu-link-funding"
    :active-fn funding-route-active?}
   {:label "Vaults"
    :route "/vaults"
    :data-role "mobile-header-menu-link-vaults"
    :active-fn #(route-active? % "/vaults")}])

(def ^:private mobile-secondary-nav-items
  [{:label "Earn"
    :route "/earn"
    :data-role "mobile-header-menu-link-earn"
    :active-fn #(route-active? % "/earn")}
   {:label "Staking"
    :route "/staking"
    :data-role "mobile-header-menu-link-staking"
    :active-fn #(route-active? % "/staking")}
   {:label "Referrals"
    :route "/referrals"
    :data-role "mobile-header-menu-link-referrals"
    :active-fn #(route-active? % "/referrals")}
   {:label "Leaderboard"
    :route "/leaderboard"
    :data-role "mobile-header-menu-link-leaderboard"
    :active-fn #(route-active? % "/leaderboard")}])

(defn- mobile-menu-link
  [current-route {:keys [label route data-role active-fn]}]
  (let [active? (boolean (when (fn? active-fn)
                           (active-fn current-route)))]
    [:button {:type "button"
              :class (into ["flex"
                            "w-full"
                            "items-center"
                            "px-4"
                            "py-3.5"
                            "text-left"
                            "text-[1.35rem]"
                            "font-medium"
                            "leading-none"
                            "transition-colors"]
                           (if active?
                             ["text-white"]
                             ["text-[#d7e7e8]" "hover:bg-[#0d1d22]" "hover:text-white"]))
              :on {:click [[:actions/navigate-mobile-header-menu route]]}
              :data-role data-role}
     [:span label]]))

(defn- mobile-menu-close-icon []
  [:svg {:viewBox "0 0 20 20"
         :fill "none"
         :stroke "currentColor"
         :class ["h-5" "w-5"]}
   [:path {:stroke-linecap "round"
           :stroke-linejoin "round"
           :stroke-width 1.8
           :d "M5 5l10 10M15 5L5 15"}]])

(defn- mobile-menu-section
  [current-route items]
  [:div {:class ["border-b" "border-[#173038]" "py-2"]}
   (for [{:keys [route] :as item} items]
     ^{:key route}
     (mobile-menu-link current-route item))])

(defn- mobile-header-menu
  [route menu-open? spectate-active?]
  [:div {:class ["md:hidden"] :data-role "mobile-header-menu"}
   [:button {:type "button"
             :class ["flex"
                     "h-9"
                     "w-9"
                     "items-center"
                     "justify-center"
                     "rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100"
                     "transition-colors"
                     "hover:bg-base-200"
                     "focus:outline-none"
                     "focus:ring-2"
                     "focus:ring-[#66e3c5]/50"
                     "focus:ring-offset-0"]
             :on {:click [[:actions/open-mobile-header-menu]]}
             :aria-label "Open mobile menu"
             :data-role "mobile-header-menu-trigger"}
   (mobile-menu-icon)]
   (when menu-open?
     [:div {:class ["fixed" "inset-0" "z-[290]"] :data-role "mobile-header-menu-layer"}
      [:button {:type "button"
                :class ["absolute" "inset-0" "bg-black/55" "backdrop-blur-[1px]"]
                :style {:transition "opacity 0.14s ease-out"
                        :opacity 1}
                :replicant/mounting {:style {:opacity 0}}
                :replicant/unmounting {:style {:opacity 0}}
                :on {:click [[:actions/close-mobile-header-menu]]}
                :aria-label "Close mobile menu"
                :data-role "mobile-header-menu-backdrop"}]
      [:aside {:class ["absolute"
                       "inset-y-0"
                       "left-0"
                       "flex"
                       "w-[min(19rem,calc(100vw-2.75rem))]"
                       "max-w-full"
                       "flex-col"
                       "border-r"
                       "border-[#173038]"
                       "bg-[#071115]"
                       "shadow-2xl"]
               :style {:transition "transform 0.16s ease-out, opacity 0.16s ease-out"
                       :transform "translateX(0)"
                       :opacity 1}
               :replicant/mounting {:style {:transform "translateX(-18px)"
                                            :opacity 0}}
               :replicant/unmounting {:style {:transform "translateX(-18px)"
                                              :opacity 0}}
               :role "dialog"
               :aria-modal true
               :aria-label "Mobile navigation"
               :data-role "mobile-header-menu-panel"}
       [:div {:class ["flex"
                      "items-center"
                      "justify-between"
                      "border-b"
                      "border-[#173038]"
                      "px-4"
                      "py-4"]}
        [:div {:class ["flex" "items-center" "gap-3"]}
         [:span {:class (into ["text-[1.9rem]" "leading-none"]
                              brand-mark-classes)
                 :data-role "mobile-header-menu-brand-mark"}
          "HO"]
         [:div {:class ["text-xs" "font-semibold" "uppercase" "tracking-[0.18em]" "text-[#85a3a8]"]}
          "Menu"]]
        [:button {:type "button"
                  :class ["inline-flex"
                          "h-9"
                          "w-9"
                          "items-center"
                          "justify-center"
                          "rounded-xl"
                          "border"
                          "border-[#173038]"
                          "bg-[#0b181d]"
                          "text-[#d7e7e8]"
                          "transition-colors"
                          "hover:bg-[#102229]"
                          "focus:outline-none"
                          "focus:ring-2"
                          "focus:ring-[#66e3c5]/40"
                          "focus:ring-offset-0"]
                  :on {:click [[:actions/close-mobile-header-menu]]}
                  :aria-label "Close mobile menu"
                  :data-role "mobile-header-menu-close"}
         (mobile-menu-close-icon)]]
       [:div {:class ["flex-1" "overflow-y-auto" "pb-6"]}
        (mobile-menu-section route mobile-primary-nav-items)
        (mobile-menu-section route mobile-secondary-nav-items)
        [:div {:class ["py-2"]}
         [:button {:type "button"
                   :class ["flex"
                           "w-full"
                           "items-center"
                           "justify-between"
                           "px-4"
                           "py-3.5"
                           "text-left"
                           "text-[1.05rem]"
                           "font-medium"
                           "text-[#97f7e2]"
                           "transition-colors"
                           "hover:bg-[#0d1d22]"]
                   :on {:click [[:actions/open-spectate-mode-mobile-header-menu
                                 :event.currentTarget/bounds]]}
                   :data-role "mobile-header-menu-spectate"}
         [:span (if spectate-active?
                   "Manage Spectate Mode"
                   "Open Spectate Mode")]
          (spectate-mode-icon)]]]]])])

(defn- wallet-control [wallet-state]
  (let [is-connected (boolean (:connected? wallet-state))
        wallet-address (:address wallet-state)
        copy-feedback (:copy-feedback wallet-state)
        agent-state (:agent wallet-state)
        is-connecting (boolean (:connecting? wallet-state))]
    (if is-connected
      [:details {:class ["relative" "group"] :data-role "wallet-menu-details"}
       (wallet-trigger wallet-address)
       (wallet-menu wallet-address copy-feedback agent-state)]
      (connect-wallet-button is-connecting))))

(defn header-view [state]
  (let [wallet-state (get-in state [:wallet] {})
        route (get-in state [:router :path] "/trade")
        api-wallet-route? (api-wallets-actions/api-wallet-route? route)
        mobile-menu-open? (true? (get-in state [:header-ui :mobile-menu-open?]))
        settings-open? (true? (get-in state [:header-ui :settings-open?]))
        settings-return-focus? (true? (get-in state [:header-ui :settings-return-focus?]))
        api-wallet-route? (api-wallets-actions/api-wallet-route? route)
        spectate-active? (account-context/spectate-mode-active? state)]
    [:header.bg-base-200.border-b.border-base-300.w-full
     {:data-parity-id "header"}
     [:div {:class ["w-full" "app-shell-gutter" "py-2" "md:py-3"]}
      [:div {:class ["flex" "items-center" "gap-2" "md:gap-4"]}
       [:div {:class ["flex" "items-center" "gap-2.5" "md:gap-3" "min-w-0"]}
        (mobile-header-menu route mobile-menu-open? spectate-active?)
        [:button {:type "button"
                  :class ["md:hidden" "inline-flex" "items-center" "rounded-lg" "px-1" "py-0.5"]
                  :on {:click [[:actions/navigate "/trade"]]}
                  :data-role "mobile-brand"}
         [:span {:class (into ["text-lg" "leading-none"]
                              brand-mark-classes)}
          "HO"]]
        [:div {:class ["hidden" "md:flex" "items-center" "space-x-2" "sm:space-x-3"]}
         [:span {:class (into ["text-xl" "leading-none" "sm:text-3xl"]
                              brand-wordmark-classes)}
          "HyperOpen"]]]

       ;; Navigation Links
       [:nav.hidden.md:flex.flex-1.items-center.justify-start.space-x-8.ml-8
        {:data-parity-id "header-nav"}
        (nav-link "Trade" "/trade" (route-active? route "/trade"))
        (nav-link "Portfolio" "/portfolio" (route-active? route "/portfolio"))
        (nav-link "Funding" "/funding-comparison" (funding-route-active? route))
        (nav-link "Earn" "/earn" false)
        (nav-link "Vaults" "/vaults" (route-active? route "/vaults"))
        (nav-link "Staking" "/staking" (route-active? route "/staking"))
        (nav-link "Referrals" "/referrals" false)
        (nav-link "Leaderboard" "/leaderboard" false)
        [:details {:class ["relative" "group"]
                   :replicant/key (str "header-more-menu:" route)
                   :data-role "header-more-menu"}
         [:summary {:class (more-trigger-classes api-wallet-route?)
                    :data-role "header-more-trigger"}
          [:span "More"]
          [:svg {:class ["h-4"
                         "w-4"
                         "transition-transform"
                         "duration-150"
                         "ease-out"
                         "group-open:rotate-180"]
                 :viewBox "0 0 20 20"
                 :fill "currentColor"
                 :data-role "header-more-chevron"}
           [:path {:fill-rule "evenodd" :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" :clip-rule "evenodd"}]]]
         [:div {:class ["ui-dropdown-panel"
                        "absolute"
                        "left-0"
                        "top-full"
                        "z-[260]"
                        "mt-2"
                        "min-w-[220px]"
                        "rounded-xl"
                        "border"
                        "border-base-300"
                        "bg-trading-bg"
                        "p-2"
                        "shadow-2xl"]
                :style {:--ui-dropdown-origin "top left"}
                :data-ui-native-details-panel "true"
                :data-role "header-more-menu-panel"}
          (more-menu-link "API" api-wallets-actions/canonical-route api-wallet-route?)]]]

       ;; Right Section - Wallet Control and Icons
       [:div {:class ["ml-auto" "flex" "items-center" "gap-1.5" "sm:gap-2.5" "lg:gap-4"]
              :data-parity-id "header-wallet-control"}
        [:div {:class ["inline-flex" "md:hidden" "lg:inline-flex"]}
         (spectate-mode-trigger-button spectate-active?)]
        (wallet-control wallet-state)
        [:div {:class ["relative" "flex" "items-center" "gap-1.5" "sm:gap-2"]
               :data-role "header-settings-toolbar"}
         (utility-icon-button "Language/Region" (globe-icon) "header-language-button")
         (utility-icon-button
          "Settings"
          (settings-icon)
          "header-settings-button"
          {:on {:click [[:actions/open-header-settings]]}
           :replicant/key (str "header-settings-button:" settings-open? ":" settings-return-focus?)
           :replicant/on-render (when settings-return-focus?
                                  focus-settings-trigger!)
           :aria-haspopup "dialog"
           :aria-expanded settings-open?})
         (when settings-open?
           (trading-settings-shell state))]]]]]))
