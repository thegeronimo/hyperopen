(ns hyperopen.views.trade.vol-range.replace-dialog
  "The \"Update ladder\" dialog and the ticket's update banner (HO-SPEC-014
  v2.1 sections 10.2, 11.4). Renders `replace-model` values only: old versus
  proposed settings, the cancel step, why the replacement is blocked, and the
  unfilled remainder once the old ladder is canceled. Nothing here places an
  order: the replacement is placed only from the ordinary review. Escape
  closes the dialog and Enter never cancels or places anything."
  (:require [hyperopen.views.trade.vol-range.commands :as commands]
            [hyperopen.views.ui.dialog-focus :as dialog-focus]))

(def ^:private title-id "vol-range-replace-title")

(def ^:private dialog-focus-on-render
  (dialog-focus/dialog-focus-on-render
   {:restore-selector "[data-role='vol-range-replace-open']"}))

(def ^:private secondary-button
  ["min-h-[36px]" "rounded-lg" "border" "border-ho-border" "px-3" "text-sm" "text-ho-text"
   "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-text"
   "disabled:cursor-not-allowed" "disabled:opacity-50"])

(def ^:private primary-button
  ["min-h-[36px]" "rounded-lg" "bg-primary" "px-3" "text-sm" "font-semibold" "text-primary-content"
   "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-text"
   "disabled:cursor-not-allowed" "disabled:opacity-50"])

(defn- button
  [classes data-role actions {:keys [label enabled? busy? aria-label]}]
  [:button {:type "button"
            :class classes
            :disabled (not enabled?)
            :aria-busy (boolean busy?)
            :aria-label aria-label
            :data-role data-role
            :on {:click actions}}
   label])

(defn- steps [items]
  [:ol {:class ["flex" "flex-wrap" "gap-x-3" "gap-y-1" "text-xs"] :data-role "vol-range-replace-steps"
        :aria-label "Update steps"}
   (for [{:keys [label current?]} items]
     ^{:key label}
     [:li {:class (if current? ["font-semibold" "text-ho-text"] ["text-ho-text-muted"])
           :aria-current (when current? "step")}
      label
      (when current? [:span {:class ["ml-1" "font-normal" "text-ho-text-secondary"]} "(current step)"])])])

(defn- compare-table [rows]
  [:div {:class ["overflow-x-auto"]}
   [:table {:class ["w-full" "text-xs" "num"] :data-role "vol-range-replace-compare"}
    [:thead
     [:tr {:class ["text-ho-text-secondary"]}
      (for [[idx label] (map-indexed vector ["Setting" "Current ladder" "Proposed"])]
        ^{:key idx}
        [:th {:scope "col" :class ["py-1" "pr-2" "text-left" "font-medium"]} label])]]
    [:tbody
     (for [{:keys [field label current proposed]} rows]
       ^{:key field}
       [:tr {:class ["border-t" "border-ho-border" "align-top"]
             :data-role "vol-range-replace-compare-row"
             :data-field (name field)}
        [:th {:scope "row" :class ["py-1" "pr-2" "text-left" "font-normal" "text-ho-text-secondary"]} label]
        [:td {:class ["py-1" "pr-2" "text-ho-text"]} (or current "—")]
        [:td {:class ["py-1" "text-ho-text"]} (or proposed "—")]])]]])

(defn- rejected-section [rows]
  [:section {:class ["flex" "flex-col" "gap-1" "rounded-md" "border" "border-ho-border" "p-2"]
             :data-role "vol-range-replace-rejected"}
   [:h3 {:class ["text-xs" "font-semibold" "text-ho-text-secondary"]} "Orders that were never placed"]
   [:p {:class ["text-xs" "text-ho-text-muted"]} "These are not included unless you select them."]
   (for [{:keys [logical-id label selected? enabled?]} rows
         :let [id (str "vol-range-replace-rejected-" logical-id)]]
     ^{:key logical-id}
     [:div {:class ["flex" "min-h-[24px]" "items-center" "gap-2"]}
      [:input {:id id
               :type "checkbox"
               :checked (boolean selected?)
               :disabled (not enabled?)
               :class ["h-4" "w-4" "cursor-pointer"]
               :data-role "vol-range-replace-rejected-select"
               :data-logical-id logical-id
               :on {:change (commands/toggle-update-rejected-actions logical-id)}}]
      [:label {:for id :class ["text-xs" "text-ho-text"]} label]])])

(defn- muted [data-role text]
  (when text [:p {:class ["text-xs" "text-ho-text-secondary"] :data-role data-role} text]))

(defn- body
  [{:keys [stale-text explainer compare settings-note quantity-note phase-text blocking message remaining-text
           filled-text included-text rejected]}]
  [:div {:class ["flex" "flex-col" "gap-3"]}
   (when stale-text [:p {:class ["text-sm" "text-ho-warn"] :data-role "vol-range-replace-stale"} stale-text])
   (when explainer [:p {:class ["text-sm" "text-ho-text"] :data-role "vol-range-replace-explainer"} explainer])
   (when (seq compare) (compare-table compare))
   (when settings-note
     [:p {:class ["text-xs" "text-ho-warn"] :data-role "vol-range-replace-settings-note"}
      [:span {:aria-hidden true} "⚠ "] settings-note])
   (muted "vol-range-replace-quantity-note" quantity-note)
   (when phase-text
     [:p {:class ["text-sm" "text-ho-text"] :aria-busy true :data-role "vol-range-replace-phase"} phase-text])
   (when (seq blocking)
     [:div {:class ["flex" "flex-col" "gap-0.5" "rounded-md" "border" "border-ho-warn" "p-2"]
            :data-role "vol-range-replace-blocking"}
      (for [[idx text] (map-indexed vector blocking)]
        ^{:key idx}
        [:p {:class ["text-xs" "text-ho-warn"]} [:span {:aria-hidden true} "⚠ "] text])])
   (when message
     [:p {:class ["text-sm" "font-medium" "text-ho-text"] :data-role "vol-range-replace-message"} message])
   (muted "vol-range-replace-remaining" remaining-text)
   (muted "vol-range-replace-filled" filled-text)
   (when (seq rejected) (rejected-section rejected))
   (muted "vol-range-replace-included" included-text)])

(defn- footer [{:keys [cancel-old edit cancel-remaining check use-quantities abandon]}]
  [:footer {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-2" "border-t" "border-ho-border"
                    "pt-2"]}
   (button secondary-button "vol-range-replace-abandon" (commands/stop-update-actions) abandon)
   [:div {:class ["flex" "flex-wrap" "items-center" "justify-end" "gap-2"]}
    (when edit (button secondary-button "vol-range-replace-edit" (commands/close-update-actions) edit))
    (when check (button secondary-button "vol-range-replace-check" (commands/check-update-actions) check))
    (when cancel-remaining
      (button primary-button "vol-range-replace-cancel-remaining" (commands/cancel-for-update-actions)
              cancel-remaining))
    (when cancel-old
      (button primary-button "vol-range-replace-cancel-old" (commands/cancel-for-update-actions) cancel-old))
    (when use-quantities
      (button primary-button "vol-range-replace-use-quantities" (commands/review-replacement-actions)
              use-quantities))]])

(defn replace-dialog
  "Dialog for an open `replace-model/replace-dialog-model`; nil when closed."
  [{:keys [title identity-line actions] :as model}]
  (when model
    [:div {:class ["fixed" "inset-0" "z-[300]" "flex" "items-stretch" "justify-center" "bg-black/60"
                   "sm:items-center" "sm:p-4"]
           ;; The focus hook sits on the node that mounts and unmounts, so
           ;; closing always returns focus to the control that opened it.
           :replicant/on-render dialog-focus-on-render
           :data-role "vol-range-replace-backdrop"}
     [:div {:role "dialog"
            :aria-modal true
            :aria-labelledby title-id
            :tabindex "-1"
            :data-role "vol-range-replace-dialog"
            :class ["flex" "h-full" "w-full" "flex-col" "gap-3" "overflow-y-auto" "bg-base-100" "p-4"
                    "text-ho-text" "sm:h-auto" "sm:max-h-[90vh]" "sm:max-w-[640px]" "sm:rounded-lg"
                    "sm:border" "sm:border-ho-border"]
            :on {:keydown (commands/update-keydown-actions)}}
      [:header {:class ["flex" "items-start" "justify-between" "gap-3"]}
       [:div {:class ["flex" "min-w-0" "flex-col" "gap-1"]}
        [:h2 {:id title-id :class ["text-base" "font-semibold"]} title]
        [:p {:class ["text-xs" "text-ho-text-secondary"]} identity-line]
        (steps (:steps model))]
       [:button {:type "button"
                 :aria-label "Close update"
                 :class ["h-8" "w-8" "shrink-0" "rounded-md" "text-ho-text-secondary" "hover:text-ho-text"]
                 :data-role "vol-range-replace-dismiss"
                 :on {:click (commands/close-update-actions)}}
        "×"]]
      (body model)
      (footer actions)]]))

;; ---------------------------------------------------------------------------
;; Ticket banner
;; ---------------------------------------------------------------------------

(defn replace-banner
  "The ticket's update banner for a `replace-model/banner-model`; nil
  without an update."
  [{:keys [title message lineage open-update? refresh-balances busy?] :as banner}]
  (when banner
    [:section {:class ["flex" "flex-col" "gap-1.5" "rounded-md" "border" "border-ho-warn" "p-2"]
               :role "status"
               :aria-busy (boolean busy?)
               :data-role "vol-range-replace-banner"}
     [:p {:class ["text-xs" "font-semibold" "text-ho-text"]} title]
     (when message [:p {:class ["text-xs" "text-ho-text"] :data-role "vol-range-replace-banner-message"} message])
     (when lineage [:p {:class ["text-xs" "text-ho-text-secondary"] :data-role "vol-range-replace-lineage"} lineage])
     [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
      (when open-update?
        (button primary-button "vol-range-replace-open" (commands/open-update-actions)
                {:label "Open update" :enabled? true}))
      (when refresh-balances
        (button secondary-button "vol-range-replace-refresh-balances" (commands/check-update-actions)
                (assoc refresh-balances :label "Refresh balances")))
      (button secondary-button "vol-range-replace-banner-stop" (commands/stop-update-actions)
              {:label "Stop updating" :enabled? true})]]))
