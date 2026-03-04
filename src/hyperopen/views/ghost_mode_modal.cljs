(ns hyperopen.views.ghost-mode-modal
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.wallet.core :as wallet]))

(defn- modal-button-classes
  [primary? disabled?]
  (cond
    disabled?
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

    primary?
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

    :else
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

(defn- watchlist-row
  [address active?]
  [:li {:class ["flex"
                "items-center"
                "justify-between"
                "gap-2"
                "rounded-lg"
                "border"
                "px-2.5"
                "py-2"]
        :data-role "ghost-mode-watchlist-row"}
   [:div {:class ["min-w-0" "flex" "flex-col" "gap-0.5"]}
    [:span {:class ["text-sm" "font-medium" "text-[#e5eef1]"]}
     (wallet/short-addr address)]
    [:span {:class ["num" "truncate" "text-xs" "text-[#94a9af]"]}
     address]]
   [:div {:class ["flex" "items-center" "gap-1.5"]}
    [:button {:type "button"
              :class (modal-button-classes true false)
              :on {:click [[:actions/spectate-ghost-mode-watchlist-address address]]}
              :data-role "ghost-mode-watchlist-spectate"}
     (if active? "Spectating" "Spectate")]
    [:button {:type "button"
              :class (modal-button-classes false false)
              :on {:click [[:actions/remove-ghost-mode-watchlist-address address]]}
              :data-role "ghost-mode-watchlist-remove"}
     "Remove"]]])

(defn ghost-mode-modal-view
  [state]
  (let [ui-state (get-in state [:account-context :ghost-ui] {})
        open? (true? (:modal-open? ui-state))
        search (or (:search ui-state) "")
        search-error (:search-error ui-state)
        watchlist (account-context/normalize-watchlist
                   (get-in state [:account-context :watchlist]))
        active? (account-context/ghost-mode-active? state)
        active-address (account-context/ghost-address state)
        valid-search? (some? (account-context/normalize-address search))
        start-disabled? (not valid-search?)
        add-disabled? (not valid-search?)]
    (when open?
      [:div {:class ["fixed" "inset-0" "z-[290]" "flex" "items-center" "justify-center" "p-4"]
             :data-role "ghost-mode-modal-root"}
       [:div {:class ["absolute" "inset-0" "bg-black/70"]
              :on {:click [[:actions/close-ghost-mode-modal]]}}]
       [:div {:class ["relative"
                      "z-[291]"
                      "w-full"
                      "max-w-lg"
                      "rounded-2xl"
                      "border"
                      "border-[#1f3b3c]"
                      "bg-[#081b24]"
                      "p-4"
                      "shadow-2xl"
                      "space-y-4"]
              :role "dialog"
              :aria-modal true
              :aria-label "Ghost Mode"
              :data-role "ghost-mode-modal"}
        [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
         [:div {:class ["flex" "min-w-0" "flex-col"]}
          [:h2 {:class ["text-lg" "font-semibold" "text-[#e5eef1]"]}
           "Ghost Mode"]
          [:p {:class ["text-sm" "text-[#97adb3]"]}
           "Spectate any public address in read-only mode."]]
         [:button {:type "button"
                   :class ["text-sm" "text-[#8ea4ab]" "hover:text-[#e5eef1]"]
                   :on {:click [[:actions/close-ghost-mode-modal]]}
                   :data-role "ghost-mode-close"}
          "Close"]]
        (when active?
          [:div {:class ["rounded-lg"
                         "border"
                         "border-[#1f4f4f]"
                         "bg-[#0a2f33]/60"
                         "px-3"
                         "py-2"
                         "text-sm"
                         "text-[#bdeee8]"]
                 :data-role "ghost-mode-active-summary"}
           [:span {:class ["font-medium"]}
            "Currently spectating: "]
           [:span {:class ["num"]} active-address]])
        [:div {:class ["space-y-2"]}
         [:label {:class ["block"
                          "text-xs"
                          "font-medium"
                          "uppercase"
                          "tracking-[0.08em]"
                          "text-[#8ea4ab]"]}
          "Public Address"]
         [:input {:type "text"
                  :value search
                  :placeholder "0x..."
                  :spell-check false
                  :auto-capitalize "off"
                  :auto-complete "off"
                  :class ["w-full"
                          "rounded-lg"
                          "border"
                          "border-[#28474b]"
                          "bg-[#0c2028]"
                          "px-3"
                          "py-2.5"
                          "text-sm"
                          "text-[#e6eff2]"
                          "outline-none"
                          "focus:border-[#4f8f87]"]
                  :on {:input [[:actions/set-ghost-mode-search [:event.target/value]]]}
                  :data-role "ghost-mode-search-input"}]
         (when (seq search-error)
           [:div {:class ["rounded-md"
                          "border"
                          "border-[#7b3340]"
                          "bg-[#3a1b22]/55"
                          "px-3"
                          "py-2"
                          "text-sm"
                          "text-[#f2b8c5]"]
                  :data-role "ghost-mode-search-error"}
            search-error])]
        [:div {:class ["flex" "flex-wrap" "justify-end" "gap-2"]}
         [:button {:type "button"
                   :class (modal-button-classes false false)
                   :on {:click [[:actions/close-ghost-mode-modal]]}
                   :data-role "ghost-mode-cancel"}
          "Cancel"]
         (when active?
           [:button {:type "button"
                     :class (modal-button-classes false false)
                     :on {:click [[:actions/stop-ghost-mode]]}
                     :data-role "ghost-mode-stop"}
            "Stop Ghost Mode"])
         [:button {:type "button"
                   :class (modal-button-classes false add-disabled?)
                   :disabled add-disabled?
                   :on {:click [[:actions/add-ghost-mode-watchlist-address]]}
                   :data-role "ghost-mode-add-watchlist"}
          "Add To Watchlist"]
         [:button {:type "button"
                   :class (modal-button-classes true start-disabled?)
                   :disabled start-disabled?
                   :on {:click [[:actions/start-ghost-mode]]}
                   :data-role "ghost-mode-start"}
          (if active? "Switch Spectating" "Start Spectating")]]
        [:div {:class ["space-y-2"]}
         [:div {:class ["flex" "items-center" "justify-between"]}
          [:h3 {:class ["text-sm" "font-semibold" "text-[#e5eef1]"]}
           "Watchlist"]]
         (if (seq watchlist)
           (into
            [:ul {:class ["max-h-56" "space-y-2" "overflow-y-auto"]
                  :data-role "ghost-mode-watchlist"}]
            (map (fn [address]
                   ^{:key address}
                   (watchlist-row address (= address active-address)))
                 watchlist))
           [:div {:class ["rounded-lg"
                          "border"
                          "border-dashed"
                          "border-[#2a4b4f]"
                          "bg-[#081f28]"
                          "px-3"
                          "py-3"
                          "text-sm"
                          "text-[#90a6ad]"]
                  :data-role "ghost-mode-watchlist-empty"}
            "No spectated addresses saved yet."])]]])))
