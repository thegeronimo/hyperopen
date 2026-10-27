(ns hyperopen.views.trade.vol-range.ticket
  "Vol Range ticket (spec section 4). Renders the `ticket-model` projection and
  emits commands only; it never computes prices, sizes, fees or admission and
  never parses numbers. VR-24: there is no placement control here."
  (:require [hyperopen.views.trade.vol-range.commands :as commands]
            [hyperopen.views.trade.vol-range.inventory-section :as inventory]
            [hyperopen.views.trade.vol-range.model-section :as model-section]
            [hyperopen.views.trade.vol-range.outcomes :as outcomes]
            [hyperopen.views.trade.vol-range.primitives :as p]
            [hyperopen.views.trade.vol-range.side-cards :as side-cards]))

(defn mode-control
  "Price range | Volatility range segmented control."
  [{:keys [scale-mode eligible? ineligible-reason on-select]}]
  (let [select (or on-select #(p/actions (commands/select-scale-mode %)))]
    [:div {:class ["flex" "flex-col" "gap-1"] :data-role "vol-range-mode-control"}
     [:div {:role "radiogroup" :aria-label "Scale mode" :data-role "vol-range-mode"
            :class ["grid" "grid-cols-2" "rounded-lg" "border" "border-ho-border" "overflow-hidden"]}
      (for [[id label] [[:price-range "Price range"] [:volatility-range "Volatility range"]]
            :let [disabled? (and (= id :volatility-range) (false? eligible?))
                  active? (= scale-mode id)]]
        ^{:key id}
        [:button {:type "button"
                  :role "radio"
                  :aria-checked (boolean active?)
                  :disabled (boolean disabled?)
                  :data-role (str "vol-range-mode-" (name id))
                  :class (into ["h-8" "text-xs" "font-medium" "disabled:cursor-not-allowed" "disabled:opacity-50"]
                               (if active? ["bg-base-200" "text-ho-text"] ["text-ho-text-secondary"]))
                  :on {:click (select id)}}
         label])]
     (when (and (false? eligible?) ineligible-reason)
       [:p {:class ["text-xs" "text-ho-warn"] :data-role "vol-range-ineligible"} ineligible-reason])]))

(defn- identity-strip
  [{:keys [market-label market-type-label account-label execution-label]}]
  [:div {:class ["flex" "flex-wrap" "items-center" "gap-x-2" "gap-y-1" "text-xs" "text-ho-text-secondary"]
         :data-role "vol-range-identity"}
   [:span {:class ["font-semibold" "text-ho-text"]} market-label]
   [:span (str "· " market-type-label)]
   [:span (str "· " account-label)]
   [:span {:class ["rounded" "border" "border-ho-border" "px-1.5" "py-0.5" "text-ho-text"]
           :data-role "vol-range-post-only-badge"}
    execution-label]])

(defn- notice
  [data-role text]
  [:p {:class ["rounded-md" "border" "border-ho-warn" "p-2" "text-xs" "text-ho-warn"]
       :role "status"
       :data-role data-role}
   text])

(defn vol-range-ticket
  "The ticket body for a visible ticket model; nil otherwise. On an ineligible
  market only the identity strip and the eligibility reason render."
  [{:keys [visible? eligible? eligibility-reason read-only? identity model inventory buy sell outcomes
           errors warnings footer ui]}]
  (when visible?
    (if (false? eligible?)
      [:div {:class ["flex" "flex-col" "gap-2.5"] :data-role "vol-range-ticket"}
       (identity-strip identity)
       (notice "vol-range-ticket-ineligible" eligibility-reason)]
      (let [base-symbol (:base-symbol inventory)
            fe (p/field-errors errors)]
        [:div {:class ["flex" "flex-col" "gap-2.5"] :data-role "vol-range-ticket"}
         (identity-strip identity)
         (when read-only?
           (notice "vol-range-read-only"
                   "Read-only: you can preview and review this plan, but this account can't place orders."))
         (model-section/model-section model ui fe)
         (inventory/inventory-section inventory fe)
         (side-cards/buy-card buy base-symbol fe)
         (side-cards/sell-card sell base-symbol fe)
         (outcomes/outcomes-section outcomes)
         (outcomes/messages errors warnings)
         (outcomes/footer footer)]))))
