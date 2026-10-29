(ns hyperopen.views.trade.vol-range.repair-dialog
  "The \"Review rejected orders\" dialog (HO-SPEC-014 v2.1 sections 8.6, 11.4).
  Renders a `repair-model/repair-model` value read-only: the frozen repair
  prices and sizes, which rejected orders are included, why others can't be
  repaired, the inventory bounds and why a send is refused. Escape closes it,
  Enter never submits, and focus returns to the dock's repair button."
  (:require [hyperopen.views.trade.vol-range.commands :as commands]
            [hyperopen.views.trade.vol-range.primitives :as p]
            [hyperopen.views.ui.dialog-focus :as dialog-focus]))

(def ^:private title-id "vol-ladder-repair-title")

(def ^:private dialog-focus-on-render
  (dialog-focus/dialog-focus-on-render
   {:restore-selector "[data-role='vol-ladder-repair']"}))

(def ^:private secondary-button
  ["h-9" "rounded-lg" "border" "border-ho-border" "px-3" "text-sm" "text-ho-text"
   "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-text"
   "disabled:cursor-not-allowed" "disabled:opacity-50"])

(defn- side-class [side] (if (= :buy side) "text-ho-buy" "text-ho-sell"))

(defn- leg-row
  [{:keys [logical-id side side-label selected? select-label rejected-price price size notional distance
           note reason]}]
  (let [checkbox-id (str "vol-ladder-repair-select-" logical-id)]
    [:tr {:class ["border-t" "border-ho-border" "align-top"]
          :data-role "vol-ladder-repair-leg"
          :data-logical-id logical-id}
     [:td {:class ["py-1.5" "pr-2"]}
      [:input {:id checkbox-id
               :type "checkbox"
               :checked (boolean selected?)
               :aria-label select-label
               :class ["h-4" "w-4" "cursor-pointer"]
               :data-role "vol-ladder-repair-leg-select"
               :on {:change (commands/toggle-repair-leg-actions logical-id)}}]]
     [:td {:class ["py-1.5" (side-class side)]} side-label]
     [:td {:class ["py-1.5" "text-right" "text-ho-text-secondary"]} rejected-price]
     [:td {:class ["py-1.5" "text-right" "font-medium" "text-ho-text"] :data-role "vol-ladder-repair-price"} price]
     [:td {:class ["py-1.5" "text-right" "text-ho-text"]} size]
     [:td {:class ["py-1.5" "text-right" "text-ho-text"]} notional]
     [:td {:class ["py-1.5" "text-right" "text-ho-text-secondary"]} distance]
     [:td {:class ["py-1.5" "pl-2" "min-w-[12rem]"]}
      [:p {:class ["text-ho-text-secondary"] :data-role "vol-ladder-repair-note"} note]
      (when reason
        [:p {:class ["text-ho-text-muted"] :data-role "vol-ladder-repair-reason"} reason])]]))

(defn- legs-table [legs base-symbol]
  [:div {:class ["overflow-x-auto"] :data-role "vol-ladder-repair-legs"}
   [:table {:class ["w-full" "text-xs" "num"]}
    [:thead
     [:tr {:class ["text-ho-text-secondary"]}
      (for [[idx [label right?]] (map-indexed vector [["Include"] ["Side"] ["Rejected at" true] ["New price" true]
                                                      [(str "Size (" (or base-symbol "base") ")") true]
                                                      ["Notional (USDC)" true] ["Distance" true] ["Why"]])]
        ^{:key idx}
        [:th {:scope "col" :class (into ["py-1" "font-medium"] (if right? ["text-right"] ["text-left"]))} label])]]
    [:tbody
     (for [leg legs] ^{:key (:logical-id leg)} (leg-row leg))]]])

(defn- bounds-panel [bounds limits-warning]
  [:section {:class ["flex" "flex-col" "gap-1" "rounded-md" "border" "border-ho-border" "p-2"]
             :data-role "vol-ladder-repair-bounds"}
   [:h3 {:class ["text-xs" "font-semibold" "text-ho-text-secondary"]} "Inventory bounds with the selected orders"]
   (for [[idx {:keys [label value]}] (map-indexed vector bounds)]
     ^{:key idx} (p/stat-row label value))
   (when limits-warning
     [:p {:class ["text-xs" "text-ho-warn"] :data-role "vol-ladder-repair-limits-warning"}
      [:span {:aria-hidden true} "⚠ "] limits-warning])])

(defn- body [{:keys [status loading-text legs unrepairable bounds limits-warning base-symbol]}]
  [:div {:class ["flex" "flex-col" "gap-3"]}
   (when (= :loading status)
     [:p {:class ["text-sm" "text-ho-text-secondary"] :role "status" :data-role "vol-ladder-repair-loading"}
      loading-text])
   (when (seq legs) (legs-table legs base-symbol))
   (when (seq unrepairable)
     [:ul {:class ["flex" "flex-col" "gap-0.5"]}
      (for [{:keys [logical-id text]} unrepairable]
        ^{:key logical-id}
        [:li {:class ["text-xs" "text-ho-warn"] :data-role "vol-ladder-repair-unrepairable"
              :data-logical-id logical-id}
         text])])
   (when (seq bounds) (bounds-panel bounds limits-warning))])

(defn repair-dialog
  "Dialog for an open `repair-model`; nil when no review is open."
  [{:keys [title identity-line intro errors as-of refresh submit] :as model}]
  (when model
    [:div {:class ["fixed" "inset-0" "z-[300]" "flex" "items-stretch" "justify-center" "bg-black/60"
                   "sm:items-center" "sm:p-4"]
           ;; The focus hook sits on the node that mounts and unmounts (the
           ;; backdrop holds only the dialog), so closing always runs the
           ;; unmount that returns focus to the repair button.
           :replicant/on-render dialog-focus-on-render
           :data-role "vol-ladder-repair-backdrop"}
     [:div {:role "dialog"
            :aria-modal true
            :aria-labelledby title-id
            :tabindex "-1"
            :data-role "vol-ladder-repair-dialog"
            :class ["flex" "h-full" "w-full" "flex-col" "gap-3" "overflow-y-auto" "bg-base-100" "p-4"
                    "text-ho-text" "sm:h-auto" "sm:max-h-[90vh]" "sm:max-w-[720px]" "sm:rounded-lg"
                    "sm:border" "sm:border-ho-border"]
            :on {:keydown (commands/repair-keydown-actions)}}
      [:header {:class ["flex" "items-start" "justify-between" "gap-3"]}
       [:div {:class ["flex" "flex-col" "gap-0.5"]}
        [:h2 {:id title-id :class ["text-base" "font-semibold"]} title]
        [:p {:class ["text-xs" "text-ho-text-secondary"]} identity-line]
        [:p {:class ["text-xs" "text-ho-text-secondary"] :data-role "vol-ladder-repair-intro"} intro]]
       [:button {:type "button"
                 :aria-label "Close rejected orders review"
                 :class ["h-8" "w-8" "rounded-md" "text-ho-text-secondary" "hover:text-ho-text"]
                 :data-role "vol-ladder-repair-dismiss"
                 :on {:click (commands/close-repair-actions)}}
        "×"]]
      (body model)
      (when (seq errors)
        [:div {:role "alert" :class ["flex" "flex-col" "gap-0.5"] :data-role "vol-ladder-repair-error"}
         (for [[idx text] (map-indexed vector errors)]
           ^{:key idx} [:p {:class ["text-xs" "text-error"]} text])])
      [:footer {:class ["flex" "flex-col" "gap-2" "border-t" "border-ho-border" "pt-2"]}
       [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-2"]}
        [:p {:class ["text-xs" "text-ho-text-muted" "num"] :data-role "vol-ladder-repair-as-of"} as-of]
        [:button {:type "button"
                  :class secondary-button
                  :disabled (not (:enabled? refresh))
                  :aria-busy (boolean (:busy? refresh))
                  :data-role "vol-ladder-repair-refresh"
                  :on {:click (commands/refresh-repair-actions)}}
         "Refresh prices"]]
       [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
        [:button {:type "button"
                  :class secondary-button
                  :data-role "vol-ladder-repair-close"
                  :on {:click (commands/close-repair-actions)}}
         "Back"]
        [:button {:type "button"
                  :disabled (not (:enabled? submit))
                  :aria-busy (boolean (:busy? submit))
                  :class ["h-9" "min-w-[6rem]" "rounded-lg" "bg-primary" "px-3" "text-sm" "font-semibold"
                          "text-primary-content" "focus:outline-none" "focus-visible:ring-2"
                          "focus-visible:ring-ho-text" "disabled:cursor-not-allowed" "disabled:opacity-50"]
                  :data-role "vol-ladder-repair-submit"
                  :on {:click (commands/submit-repair-actions)}}
         (:label submit)]]
       (when-let [note (:note submit)]
         [:p {:class ["text-right" "text-xs" "text-ho-text-muted"] :data-role "vol-ladder-repair-submit-note"}
          note])]]]))
