(ns hyperopen.views.spectate-mode-modal.watchlist
  (:require [hyperopen.wallet.core :as wallet]))

(defn- watchlist-action-icon-button
  [{:keys [aria-label title on-click data-role disabled? class]} icon]
  [:button {:type "button"
            :class (cond-> (into ["inline-flex"
                                  "h-7"
                                  "w-7"
                                  "items-center"
                                  "justify-center"
                                  "rounded-md"
                                  "border"
                                  "border-base-300"
                                  "bg-base-200/40"
                                  "text-gray-400"
                                  "transition-colors"
                                  "focus:outline-none"
                                  "focus:ring-0"
                                  "focus:ring-offset-0"
                                  "hover:bg-base-200"
                                  "hover:text-gray-100"]
                                 (or class []))
                     disabled? (into ["cursor-not-allowed"
                                      "opacity-45"
                                      "hover:bg-base-200/40"
                                      "hover:text-gray-400"]))
            :on (when-not disabled? {:click on-click})
            :aria-label aria-label
            :title title
            :disabled disabled?
            :data-role data-role}
   icon])

(defn- spectate-icon
  []
  [:svg {:viewBox "0 0 24 24"
         :class ["h-3.5" "w-3.5"]
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.8"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M3 12s3.5-5 9-5 9 5 9 5-3.5 5-9 5-9-5-9-5z"}]
   [:circle {:cx "12" :cy "12" :r "2.25"}]])

(defn- copy-icon
  []
  [:svg {:viewBox "0 0 20 20"
         :class ["h-3.5" "w-3.5"]
         :fill "currentColor"
         :aria-hidden "true"}
   [:path {:d "M4 4a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v1h-2V4H6v8h1v2H6a2 2 0 0 1-2-2V4z"}]
   [:path {:d "M8 7a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V7zm2 0h4v7h-4V7z"}]])

(defn- link-icon
  []
  [:svg {:viewBox "0 0 20 20"
         :class ["h-3.5" "w-3.5"]
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.7"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M8 12l4-4"}]
   [:path {:d "M6.4 14.6 4.9 16.1a2.8 2.8 0 0 1-4 0 2.8 2.8 0 0 1 0-4l1.5-1.5"}]
   [:path {:d "M13.6 5.4 15.1 3.9a2.8 2.8 0 0 1 4 0 2.8 2.8 0 0 1 0 4l-1.5 1.5"}]])

(defn- edit-icon
  []
  [:svg {:viewBox "0 0 20 20"
         :class ["h-3.5" "w-3.5"]
         :fill "currentColor"
         :aria-hidden "true"}
   [:path {:d "M4 13.5V16h2.5L14 8.5 11.5 6 4 13.5Z"}]
   [:path {:d "M10.5 7 13 9.5"}]])

(defn- remove-icon
  []
  [:svg {:viewBox "0 0 20 20"
         :class ["h-3.5" "w-3.5"]
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.8"
         :stroke-linecap "round"
         :aria-hidden "true"}
   [:path {:d "M5 5 15 15"}]
   [:path {:d "M15 5 5 15"}]])

(defn- watchlist-display-label
  [entry]
  (or (:label entry)
      ""))

(defn- watchlist-display-address
  [address]
  (or (wallet/short-addr address)
      address))

(defn- watchlist-row-classes
  [active? editing?]
  (cond-> ["grid"
           "grid-cols-[minmax(0,1.15fr)_minmax(0,0.95fr)_auto]"
           "items-center"
           "gap-1.5"
           "px-3"
           "py-2.5"]
    active? (into ["bg-base-200/80"])
    (not active?) (into ["bg-base-100"])
    editing? (into ["ring-1" "ring-[#4f8f87]/70"])))

(defn- active-watchlist-action-classes
  [active?]
  (when active?
    ["text-[#e8c25f]"
     "border-[#7f6a39]"
     "bg-[#2a2418]"
     "hover:border-[#9f854c]"
     "hover:bg-[#3a301f]"
     "hover:text-[#f2d981]"]))

(defn- watchlist-action-specs
  [address active?]
  [{:aria-label (if active? "Currently spectating this address" "Spectate this address")
    :title (if active? "Currently spectating" "Spectate this address")
    :on-click [[:actions/start-spectate-mode-watchlist-address address]]
    :class (active-watchlist-action-classes active?)
    :data-role "spectate-mode-watchlist-spectate"
    :icon (spectate-icon)}
   {:aria-label "Copy watchlist address"
    :title "Copy address"
    :on-click [[:actions/copy-spectate-mode-watchlist-address address]]
    :data-role "spectate-mode-watchlist-copy"
    :icon (copy-icon)}
   {:aria-label "Copy spectate link"
    :title "Link address"
    :on-click [[:actions/copy-spectate-mode-watchlist-link address]]
    :data-role "spectate-mode-watchlist-link"
    :icon (link-icon)}
   {:aria-label "Edit watchlist label"
    :title "Edit label"
    :on-click [[:actions/edit-spectate-mode-watchlist-address address]]
    :data-role "spectate-mode-watchlist-edit"
    :icon (edit-icon)}
   {:aria-label "Remove watchlist address"
    :title "Remove address"
    :on-click [[:actions/remove-spectate-mode-watchlist-address address]]
    :data-role "spectate-mode-watchlist-remove"
    :icon (remove-icon)}])

(defn- watchlist-action-button
  [{:keys [icon] :as spec}]
  (watchlist-action-icon-button (dissoc spec :icon) icon))

(defn watchlist-row
  [entry active? editing?]
  (let [address (:address entry)
        label (watchlist-display-label entry)]
    [:li {:class (watchlist-row-classes active? editing?)
          :data-role "spectate-mode-watchlist-row"}
     [:div {:class ["min-w-0"]
            :data-role "spectate-mode-watchlist-label"}
      [:span {:class ["text-m" "font-medium" "text-gray-100" "break-words"]}
       label]]
     [:div {:class ["min-w-0" "truncate"]
            :data-role "spectate-mode-watchlist-address"}
      [:span {:class ["num" "truncate" "text-sm" "text-gray-400"]}
       (watchlist-display-address address)]]
     (into
      [:div {:class ["flex" "items-center" "justify-end" "gap-1"]
             :data-role "spectate-mode-watchlist-actions"}]
      (map (fn [spec]
             ^{:key (:data-role spec)}
             (watchlist-action-button spec))
           (watchlist-action-specs address active?)))]))
