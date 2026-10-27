(ns hyperopen.views.trade.vol-range.side-cards
  "Accumulate buys and Distribute sells cards of the Vol Range ticket."
  (:require [hyperopen.views.trade.vol-range.commands :as commands]
            [hyperopen.views.trade.vol-range.inventory-section :as inventory]
            [hyperopen.views.trade.vol-range.primitives :as p]))

(def ^:private rung-counts (vec (range 1 13)))

(defn- current-count
  "Index into the fixed 1-12 rung list matching the display string; no parsing."
  [count-input]
  (some #(when (= (str %) (str count-input)) %) rung-counts))

(defn- stepper
  [side count-input]
  (let [n (current-count count-input)
        id (str "vol-range-" (name side) "-count")
        step (fn [label delta aria enabled?]
               [:button {:type "button"
                         :class ["h-9" "w-9" "rounded-md" "border" "border-ho-border" "text-ho-text"
                                 "disabled:opacity-40" "disabled:cursor-not-allowed"]
                         :aria-label aria
                         :disabled (not enabled?)
                         :on {:click (p/actions (commands/update-draft [side :count] (+ n delta)))}}
                label])]
    [:div {:class ["flex" "flex-col" "gap-1"]}
     [:label {:for id :class ["text-xs" "text-ho-text-secondary"]} "Rungs"]
     [:div {:class ["flex" "items-center" "gap-1"]}
      (step "−" -1 (str "Fewer " (name side) " rungs") (boolean (and n (> n 1))))
      [:input {:id id :type "text" :inputmode "numeric" :autocomplete "off"
               :class ["w-full" "h-9" "px-2" "bg-base-200" "border" "border-base-300" "rounded-lg"
                       "text-sm" "text-center" "text-ho-text" "num"]
               :value (or count-input "")
               :on {:input (p/actions (commands/update-draft-from-input [side :count]))}}]
      (step "+" 1 (str "More " (name side) " rungs") (boolean (and n (< n 12))))]]))

(defn- distance-fields
  [side {:keys [start-sigma-input end-sigma-input skew-input count-input]} fe]
  [:div {:class ["flex" "flex-col" "gap-2"]}
   [:div {:class ["grid" "grid-cols-2" "gap-2"]}
    (p/text-field {:id (str "vol-range-" (name side) "-start") :label "Start distance"
                   :value start-sigma-input :suffix "σ" :path [side :start-sigma] :field-errors fe})
    (p/text-field {:id (str "vol-range-" (name side) "-end") :label "End distance"
                   :value end-sigma-input :suffix "σ" :path [side :end-sigma] :field-errors fe})]
   [:div {:class ["grid" "grid-cols-2" "gap-2"]}
    (stepper side count-input)
    (p/text-field {:id (str "vol-range-" (name side) "-skew") :label "Size skew"
                   :value skew-input :path [side :skew] :field-errors fe})]])

(defn- amount-field
  "The side's size: a base quantity, or a USDC amount the plan is sized to."
  [side {:keys [amount-unit quantity-input quote-amount-input amount-note]} base-symbol fe]
  (let [quote? (= :quote amount-unit)
        id (str "vol-range-" (name side) (if quote? "-quote-amount" "-quantity"))]
    [:div {:class ["flex" "flex-col" "gap-1"]}
     (p/text-field (merge {:id id :field-errors fe
                           :label-accessory (p/unit-toggle {:path [side :amount-unit] :unit amount-unit
                                                            :base-symbol base-symbol
                                                            :data-role (str "vol-range-" (name side) "-unit")
                                                            :aria-label (str (if (= side :buy) "Buy" "Sell")
                                                                             " size unit")})}
                          (if quote?
                            {:label "Amount" :value quote-amount-input :suffix "USDC"
                             :path [side :quote-amount]}
                            {:label "Quantity" :value quantity-input :suffix base-symbol
                             :path [side :quantity]})))
     (when amount-note
       [:div {:class ["text-xs" "text-ho-text-muted"] :data-role (str "vol-range-" (name side) "-amount-note")}
        amount-note])]))

(defn- buy-totals
  [{:keys [quantity gross fee-reserve fee-reserve-token max-debit cap cap-headroom vwap]} base-symbol]
  [:div {:class ["flex" "flex-col" "gap-0.5"] :data-role "vol-range-buy-totals"}
   (p/stat-row "Total quantity" (when quantity (str quantity " " base-symbol)))
   (p/stat-row "Gross order value" gross)
   (p/stat-row "Fee reserve" (when fee-reserve (str fee-reserve " " fee-reserve-token)))
   (p/stat-row "Maximum debit" max-debit)
   (p/stat-row "Cap" (when cap (str cap (when cap-headroom (str " · headroom " cap-headroom)))))
   (p/stat-row "Quoted buy VWAP" vwap)])

(defn- sell-totals
  [{:keys [quantity base-committed fee-reserve fee-reserve-token gross est-net-proceeds vwap]} base-symbol]
  [:div {:class ["flex" "flex-col" "gap-0.5"] :data-role "vol-range-sell-totals"}
   (p/stat-row "Total quantity" (when quantity (str quantity " " base-symbol)))
   (p/stat-row "Base committed" (when base-committed (str base-committed " " base-symbol)))
   (p/stat-row "Gross proceeds" gross)
   (p/stat-row "Fee reserve" (when fee-reserve (str fee-reserve " " fee-reserve-token)))
   (p/stat-row "Est. net proceeds" est-net-proceeds)
   (p/stat-row "Quoted sell VWAP" vwap)])

(defn buy-card
  ([buy base-symbol] (buy-card buy base-symbol {}))
  ([{:keys [enabled? amount-unit max-debit-input rows totals use-plan-total-offer] :as buy}
    base-symbol fe]
  (p/section {:title "Accumulate buys" :data-role "vol-range-buy-card"
              :class ["border-l-2" "border-ho-buy" "pl-2"]}
             (p/switch {:id "vol-range-buy-enabled" :label "Accumulate buys" :checked? enabled?
                        :path [:buy :enabled?]})
             (when enabled?
               [:div {:class ["flex" "flex-col" "gap-2"]}
                (amount-field :buy buy base-symbol fe)
                ;; In USDC mode the amount is the debit ceiling.
                (when-not (= :quote amount-unit)
                  [:div {:class ["flex" "flex-col" "gap-1"]}
                   [:div {:class ["flex" "items-end" "gap-2"]}
                    [:div {:class ["flex-1"]}
                     (p/text-field {:id "vol-range-buy-max-debit" :label "Maximum USDC debit"
                                    :value max-debit-input :suffix "USDC" :path [:buy :max-usdc-debit]
                                    :field-errors fe})]
                    (when use-plan-total-offer
                      (p/action-button {:label (:label use-plan-total-offer)
                                        :command (commands/apply-remediation (:id use-plan-total-offer))
                                        :data-role "vol-range-use-plan-total"
                                        :tone :buy}))]
                   [:div {:class ["text-xs" "text-ho-text-muted"] :data-role "vol-range-max-debit-hint"}
                    "Ceiling on what these buys can spend, fee reserve included"]])
                (distance-fields :buy buy fe)
                (p/rung-rows rows base-symbol :buy)
                (buy-totals totals base-symbol)]))))

(defn- hint-link
  "The hint is a label for the Protected core input, so activating it moves
  focus there natively."
  [hint]
  [:label {:for inventory/protected-core-input-id
           :class ["cursor-pointer" "text-xs" "text-ho-warn" "underline"]
           :data-role "vol-range-nothing-to-sell"}
   hint])

(defn sell-card
  ([sell base-symbol] (sell-card sell base-symbol {}))
  ([{:keys [enabled? percent-options nothing-to-sell-hint rows totals note] :as sell}
    base-symbol fe]
  (p/section {:title "Distribute sells" :data-role "vol-range-sell-card"
              :class ["border-l-2" "border-ho-sell" "pl-2"]}
             (p/switch {:id "vol-range-sell-enabled" :label "Distribute sells" :checked? enabled?
                        :path [:sell :enabled?]})
             (when enabled?
               [:div {:class ["flex" "flex-col" "gap-2"]}
                (amount-field :sell sell base-symbol fe)
                (when nothing-to-sell-hint (hint-link nothing-to-sell-hint))
                [:div {:class ["flex" "items-center" "gap-1"] :role "group"
                       :aria-label "Share of sellable excess above protected core"}
                 (for [{:keys [pct label enabled?]} percent-options]
                   ^{:key pct}
                   (p/action-button {:label label
                                     :aria-label (str label " of sellable excess above core")
                                     :command (commands/set-sell-quantity-percent pct)
                                     :disabled? (not enabled?)
                                     :data-role (str "vol-range-sell-percent-" pct)
                                     :tone :sell}))
                 [:span {:class ["text-xs" "text-ho-text-muted"]} "of sellable excess above core"]]
                (distance-fields :sell sell fe)
                (p/rung-rows rows base-symbol :sell)
                (sell-totals totals base-symbol)
                (when note [:div {:class ["text-xs" "text-ho-text-muted"]
                                  :data-role "vol-range-sell-note"} note])]))))
