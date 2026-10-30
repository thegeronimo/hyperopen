(ns hyperopen.views.trade.vol-range.review
  "Vol Range review dialog (spec section 4). It lists the exact planned orders
  and the cash and inventory bounds. A commit button renders only when the
  `:commit` model (dock-model/review-commit) is visible; otherwise the
  placement-unavailable text stays and there is only Back to edit."
  (:require [hyperopen.views.trade.vol-range.commands :as commands]
            [hyperopen.views.trade.vol-range.primitives :as p]
            [hyperopen.views.ui.dialog-focus :as dialog-focus]))

(def ^:private title-id "vol-range-review-title")

(def ^:private dialog-focus-on-render
  "Shared dialog focus behavior: focus moves in on open, Tab is trapped inside,
  and on close focus returns to the element that opened the review (falling
  back to the Review button)."
  (dialog-focus/dialog-focus-on-render
   {:restore-selector "[data-role='vol-range-review-open']"}))

(defn- legs-table
  [legs base-label]
  [:div {:class ["flex" "flex-col" "gap-1"] :data-role "vol-range-review-legs"}
   [:h3 {:class ["text-xs" "font-semibold" "text-ho-text-secondary"]} "Exact orders to sign"]
   [:div {:class ["overflow-x-auto"]}
    [:table {:class ["w-full" "text-xs" "num"]}
     [:thead
      [:tr {:class ["text-ho-text-secondary"]}
       (for [[idx [label right?]] (map-indexed vector [["Side"] ["Price" true] [(str "Size (" base-label ")") true]
                                                       ["Notional (USDC)" true]
                                                       ["Distance after rounding" true]])]
         ^{:key idx}
         [:th {:scope "col" :class (into ["py-1" "font-medium"] (if right? ["text-right"] ["text-left"]))}
          label])]]
     [:tbody
      (for [{:keys [leg-id side side-label price size notional distance]} legs]
        ^{:key leg-id}
        [:tr {:class ["border-t" "border-ho-border"] :data-leg-id leg-id}
         [:td {:class ["py-1" (if (= side :buy) "text-ho-buy" "text-ho-sell")]} side-label]
         [:td {:class ["py-1" "text-right" "text-ho-text"]} price]
         [:td {:class ["py-1" "text-right" "text-ho-text"]} size]
         [:td {:class ["py-1" "text-right" "text-ho-text"]} notional]
         [:td {:class ["py-1" "text-right" "text-ho-text-secondary"]} distance]])]]]])

(defn- panel
  [title rows data-role]
  [:section {:class ["flex" "flex-col" "gap-1" "rounded-md" "border" "border-ho-border" "p-2"]
             :data-role data-role}
   [:h3 {:class ["text-xs" "font-semibold" "text-ho-text-secondary"]} title]
   (for [[idx {:keys [label value]}] (map-indexed vector rows)]
     ^{:key idx} (p/stat-row label value))])

(defn- lineage-section
  "A replacement's lineage (spec 10.2): which ladder it replaces, the unfilled
  remainder it defaults to, fills during the cancel, and whether rejected
  orders of the old ladder are included."
  [{:keys [lineage-text remaining-text filled-text rejected-text edited-text]}]
  [:section {:class ["flex" "flex-col" "gap-0.5" "rounded-md" "border" "border-ho-border" "p-2" "text-xs"]
             :data-role "vol-range-review-lineage"}
   [:p {:class ["text-ho-text"]} lineage-text " " remaining-text (when filled-text (str " " filled-text))]
   [:p {:class ["text-ho-text-secondary"] :data-role "vol-range-review-rejected"} rejected-text]
   (when edited-text
     [:p {:class ["text-ho-warn"] :data-role "vol-range-review-edited"} edited-text])])

(defn- commit-control
  [{:keys [label enabled? placing? note error]}]
  [:div {:class ["flex" "flex-col" "items-end" "gap-1"]}
   (when error
     [:p {:role "alert" :class ["text-right" "text-xs" "text-error"] :data-role "vol-range-submit-error"}
      error])
   [:button {:type "button"
             :disabled (not enabled?)
             :aria-busy (boolean placing?)
             :class ["h-9" "min-w-[6rem]" "rounded-lg" "bg-primary" "px-3" "text-sm" "font-semibold"
                     "text-primary-content" "focus:outline-none" "focus-visible:ring-2"
                     "focus-visible:ring-ho-text" "disabled:cursor-not-allowed" "disabled:opacity-50"]
             :data-role "vol-range-submit"
             :on {:click (commands/place-orders-actions)}}
    label]
   (when note
     [:p {:class ["text-xs" "text-ho-text-muted"] :data-role "vol-range-submit-note"} note])])

(defn review-dialog
  "Dialog for an open review model; nil when closed or unavailable."
  [{:keys [open? title identity-line plan-as-of base-symbol tiles legs cash inventory details note footer commit
           replacement]}]
  (when open?
   (let [base-label (or base-symbol "base")]
    [:div {:class ["fixed" "inset-0" "z-[300]" "flex" "items-stretch" "justify-center" "bg-black/60"
                   "sm:items-center" "sm:p-4"]
           :data-role "vol-range-review-backdrop"}
     [:div {:role "dialog"
            :aria-modal true
            :aria-labelledby title-id
            :tabindex "-1"
            :replicant/on-render dialog-focus-on-render
            :data-role "vol-range-review"
            :class ["flex" "h-full" "w-full" "flex-col" "gap-3" "overflow-y-auto" "bg-base-100" "p-4"
                    "text-ho-text" "sm:h-auto" "sm:max-h-[90vh]" "sm:max-w-[640px]" "sm:rounded-lg"
                    "sm:border" "sm:border-ho-border"]
            :on {:keydown (p/actions (commands/handle-review-keydown))}}
      [:header {:class ["flex" "items-start" "justify-between" "gap-3"]}
       [:div {:class ["flex" "flex-col" "gap-0.5"]}
        [:h2 {:id title-id :class ["text-base" "font-semibold"]} title]
        [:p {:class ["text-xs" "text-ho-text-secondary"]} identity-line]
        [:p {:class ["text-xs" "text-ho-text-muted"]} plan-as-of]]
       [:button {:type "button"
                 :aria-label "Close review"
                 :class ["h-8" "w-8" "rounded-md" "text-ho-text-secondary" "hover:text-ho-text"]
                 :data-role "vol-range-review-close"
                 :on {:click (p/actions (commands/close-review))}}
        "×"]]
      [:div {:class ["grid" "grid-cols-2" "gap-2"] :data-role "vol-range-review-tiles"}
       (for [{:keys [id label value caption]} tiles]
         ^{:key id} (p/tile {:label label :value value :caption caption}))]
      (when replacement (lineage-section replacement))
      (legs-table legs base-label)
      [:div {:class ["grid" "grid-cols-1" "gap-2" "sm:grid-cols-2"]}
       (panel "Cash and fees" cash "vol-range-review-cash")
       (panel (str "Inventory bounds (" base-label ")") inventory "vol-range-review-inventory")]
      [:details {:class ["text-xs" "text-ho-text-secondary"] :data-role "vol-range-review-details"}
       [:summary {:class ["cursor-pointer"]} "Plan details · sources"]
       [:div {:class ["mt-1" "flex" "flex-col" "gap-0.5"]}
        (for [[idx {:keys [label value]}] (map-indexed vector details)]
          ^{:key idx} (p/stat-row label value))]]
      [:p {:class ["text-xs" "text-ho-text-muted"] :data-role "vol-range-review-note"}
       "Orders fill independently. Prices stay fixed until you update them."
       (when note [:span " " note])]
      [:footer {:class ["flex" "flex-col" "gap-2" "border-t" "border-ho-border" "pt-2"]
                :data-role "vol-range-review-footer"}
       [:p {:class ["text-xs" "text-ho-text-muted"]} (:hint footer)]
       [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
        [:button {:type "button"
                  :autofocus true
                  :class ["h-9" "rounded-lg" "border" "border-ho-border" "px-3" "text-sm" "text-ho-text"]
                  :data-role "vol-range-review-back"
                  :on {:click (p/actions (commands/close-review))}}
         (:back-label footer)]
        (if (:visible? commit)
          (commit-control commit)
          [:span {:class ["text-sm" "text-ho-text-secondary"]
                  :data-role "vol-range-placement-unavailable"}
           (:text footer)])]]]])))
