(ns hyperopen.views.trade.outcome-ticket
  "Order ticket for outcome markets: pick a side, enter dollars, read what it
   pays. Buying a side is the only entry; selling is Cash out on held shares."
  (:require [clojure.string :as str]
            [hyperopen.system :as app-system]
            [hyperopen.views.trade.order-form-commands :as cmd]
            [hyperopen.views.trade.order-form-footer :as footer]
            [hyperopen.views.trade.order-form-intent-adapter :as intent-adapter]
            [nexus.registry :as nxr]))

(defn- command-actions
  [& commands]
  (into [] (mapcat intent-adapter/command->actions) commands))

(defn- segmented
  [{:keys [role label items]}]
  (into [:div {:class ["grid" "gap-1" "rounded-lg" "bg-base-200/60" "p-1"]
               :style {:grid-template-columns (str "repeat(" (count items) ", minmax(0, 1fr))")}
               :role "tablist"
               :aria-label label
               :data-role role}]
        (map (fn [{:keys [text active? disabled? actions title]}]
               [:button {:type "button"
                         :role "tab"
                         :aria-selected (boolean active?)
                         :disabled (boolean disabled?)
                         :title title
                         :class (into ["h-8" "rounded-md" "text-xs" "font-medium" "transition-colors"
                                       "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
                                      (cond
                                        disabled? ["cursor-not-allowed" "text-ho-text-muted"]
                                        active? ["bg-base-100" "text-ho-text-hi" "shadow-sm"]
                                        :else ["text-ho-text-secondary" "hover:text-ho-text"]))
                         :on (when-not disabled? {:click actions})}
                text]))
        items))

(defn- side-tone-classes
  [tone selected?]
  (if-not selected?
    ["border-base-300" "bg-base-100" "hover:border-ho-border-accent"]
    (case tone
      :yes ["border-ho-buy" "bg-ho-accent-soft"]
      :no ["border-ho-border-sell" "bg-ho-sell-soft"]
      ["border-ho-border-accent" "bg-ho-accent-soft"])))

(defn- side-label-classes
  [tone]
  (case tone
    :yes ["text-ho-buy"]
    :no ["text-ho-sell-hi"]
    ["text-ho-text-hi"]))

(defn- pick-side-actions
  "One action only: the side switch re-selects the side's market and resets the
   form, and a second form write in the same dispatch would read the stale form
   and undo it."
  [handlers index]
  (vec ((get-in handlers [:outcome :on-select-outcome-side]) index)))

(defn- side-button
  [handlers {:keys [index label sub-label price-text chance-text selected? tone]}]
  [:button {:type "button"
            :class (into ["flex" "min-h-[3.5rem]" "min-w-0" "flex-col" "items-start" "justify-center" "gap-0.5"
                          "rounded-lg" "border" "px-3" "py-2" "text-left" "transition-colors"
                          "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
                         (side-tone-classes tone selected?))
            :aria-pressed (boolean selected?)
            :data-role "outcome-ticket-side"
            :on {:click (pick-side-actions handlers index)}}
   [:span {:class ["flex" "w-full" "items-baseline" "justify-between" "gap-2"]}
    [:span {:class (into ["truncate" "text-sm" "font-semibold"] (side-label-classes tone))} label]
    [:span {:class ["num" "text-sm" "font-semibold" "text-ho-text-hi"]} price-text]]
   [:span {:class ["flex" "w-full" "items-baseline" "justify-between" "gap-2" "text-xs" "text-ho-text-secondary"]}
    [:span {:class ["truncate"]} (or sub-label "")]
    [:span {:class ["num" "shrink-0"]} (str chance-text " chance")]]])

(defn- select-coin-actions
  [coin]
  [[:actions/select-asset coin]])

(defn- option-sub-line
  [{:keys [venue change-text direction distance-text]}]
  (let [parts (keep identity [(when distance-text
                                (str (case direction :up "↑ " :down "↓ " "") distance-text))
                              (when change-text (str change-text " 24h"))
                              venue])]
    (when (seq parts)
      [:div {:class ["truncate" "text-ho-text-muted"]} (str/join " · " parts)])))

(defn- option-row
  [{:keys [label selected? chance-text yes-text no-text yes-coin no-coin kink-note] :as row}]
  [:div {:class (into ["grid" "items-center" "gap-2" "rounded-md" "px-2" "py-1.5" "text-xs"]
                      (if selected? ["bg-ho-accent-soft"] ["hover:bg-base-200/60"]))
         :style {:grid-template-columns "minmax(0,1fr) 2.75rem 3.25rem 3.25rem"}
         :data-role "outcome-ticket-option"}
   [:div {:class ["min-w-0"]}
    [:div {:class ["truncate" "font-medium" "text-ho-text-hi"]} label]
    (option-sub-line row)
    (when kink-note
      [:div {:class ["truncate" "text-ho-warn"] :data-role "outcome-ticket-rung-kink"} kink-note])]
   [:div {:class ["num" "text-right" "font-semibold" "text-ho-text-hi"]} chance-text]
   [:button {:type "button"
             :class ["h-8" "rounded-md" "border" "border-base-300" "num" "text-ho-buy" "hover:border-ho-buy"
                     "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
             :aria-label (str "Buy Yes on " label " at " yes-text)
             :data-role "outcome-ticket-option-yes"
             :on {:click (select-coin-actions yes-coin)}}
    yes-text]
   [:button {:type "button"
             :class ["h-8" "rounded-md" "border" "border-base-300" "num" "text-ho-sell-hi" "hover:border-ho-border-sell"
                     "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
             :aria-label (str "Buy No on " label " at " no-text)
             :data-role "outcome-ticket-option-no"
             :on {:click (select-coin-actions no-coin)}}
    no-text]])

(defn- chance-bar
  [rows]
  (let [traded (filter (comp number? :chance) rows)
        total (reduce + 0 (map :chance traded))]
    (when (pos? total)
      (into [:div {:class ["flex" "h-1.5" "overflow-hidden" "rounded-full" "bg-base-300"]
                   :aria-hidden "true"
                   :data-role "outcome-ticket-chance-bar"}]
            (map-indexed (fn [idx {:keys [chance]}]
                           [:div {:class [(if (even? idx) "bg-ho-accent" "bg-ho-info")]
                                  :style {:width (str (* 100 (/ chance (max 1 total))) "%")
                                          :opacity (max 0.35 (- 1 (* 0.15 idx)))}}])
                         (sort-by (comp - :chance) traded))))))

(defn- options-section
  [{:keys [rows exclusive? listed-sum-text stale? other ladder? spot-text]}]
  [:div {:class ["space-y-1.5"] :data-role "outcome-ticket-options"}
   [:div {:class ["flex" "items-center" "justify-between" "text-xs" "text-ho-text-secondary"]}
    [:span (if ladder? "Each rung is its own Yes/No" "Pick an answer")]
    (cond
      exclusive? [:span {:class ["num"]} (str "Listed " listed-sum-text)]
      spot-text [:span {:class ["num"]} spot-text])]
   (when exclusive? (chance-bar rows))
   (into [:div {:class ["max-h-56" "space-y-0.5" "overflow-y-auto" "pr-1"]}]
         (map option-row rows))
   (when other
     [:div {:class ["flex" "items-center" "justify-between" "px-2" "text-xs" "text-ho-text-muted"]
            :data-role "outcome-ticket-other"}
      [:span (str (:label other) " · any answer not listed")]
      [:span {:class ["num"]} (:chance-text other)]])
   (when stale?
     [:div {:class ["rounded-md" "border" "border-ho-warn/40" "bg-ho-warn/10" "px-2.5" "py-2" "text-xs" "text-ho-text"]
            :role "alert"
            :data-role "outcome-ticket-stale-warning"}
      [:div {:class ["font-semibold" "text-ho-warn"]} (str "Listed answers add up to " listed-sum-text)]
      [:div "Only one can win, so some of these prices are old last trades. Check the result before you trade."]])])

(defn- input-shell
  [{:keys [prefix suffix input]}]
  [:div {:class ["flex" "h-10" "items-center" "gap-1" "rounded-lg" "border" "border-base-300" "bg-base-100" "px-3"
                 "focus-within:border-ho-border-accent"]}
   (when prefix [:span {:class ["text-sm" "text-ho-text-secondary"]} prefix])
   input
   (when suffix [:span {:class ["text-xs" "text-ho-text-secondary"]} suffix])])

(def ^:private input-classes
  ["min-w-0" "flex-1" "bg-transparent" "text-right" "text-sm" "num" "text-ho-text-hi" "outline-none"
   "focus:outline-none" "focus:ring-0" "border-0" "p-0"])

(defn- cents-input-handler
  "Typed cents become the order's dollar price. Half-typed numbers (\"68.\")
   wait for the next keystroke so the field never fights the typist."
  [event]
  (let [text (.. event -target -value)
        complete? (or (= "" text) (re-matches #"\d+(\.\d+)?" text))]
    (when (and complete? app-system/store)
      (let [cents (js/parseFloat text)
            price (if (js/isNaN cents) "" (str (/ (js/Math.round (* cents 1000)) 100000)))]
        (nxr/dispatch app-system/store nil (command-actions (cmd/update-order-form [:price] price)))))))

(defn- limit-section
  [{:keys [limit-cents-text limit-chance-text limit-note quote-fills]}]
  [:div {:class ["space-y-1.5"] :data-role "outcome-ticket-limit"}
   [:div {:class ["flex" "items-center" "justify-between" "text-xs" "text-ho-text-secondary"]}
    [:label {:for "outcome-ticket-limit-price"} "Limit price"]
    (into [:div {:class ["flex" "gap-1"]}]
          (map (fn [[label price]]
                 [:button {:type "button"
                           :class ["rounded" "px-1.5" "py-0.5" "text-xs" "text-ho-text-secondary" "hover:text-ho-text"
                                   "hover:bg-base-200" "focus:outline-none" "focus:ring-0"]
                           :on {:click (command-actions (cmd/update-order-form [:price] (str price)))}}
                  label]))
          quote-fills)]
   (input-shell {:suffix "¢"
                 :input [:input {:id "outcome-ticket-limit-price"
                                 :type "text"
                                 :inputmode "decimal"
                                 :autocomplete "off"
                                 :placeholder "0.0"
                                 :class input-classes
                                 :value (or limit-cents-text "")
                                 :on {:input cents-input-handler}}]})
   (when limit-chance-text
     [:div {:class ["text-xs" "text-ho-text-muted"]} limit-chance-text])
   (when limit-note
     [:div {:class ["text-xs" "text-ho-text-secondary"]} limit-note])])

(defn- usd-presets
  [available]
  (cond-> [["$10" "10"] ["$50" "50"] ["$100" "100"]]
    (and (number? available) (pos? available))
    (conj ["Max" (str (/ (js/Math.floor (* available 100)) 100))])))

(defn- amount-section
  [{:keys [amount-text available-usdc]}]
  [:div {:class ["space-y-1.5"] :data-role "outcome-ticket-amount"}
   [:div {:class ["flex" "items-center" "justify-between" "text-xs" "text-ho-text-secondary"]}
    [:label {:for "outcome-ticket-amount"} "Amount"]
    (when (number? available-usdc)
      [:span {:class ["num"]} (str "Available " (.toFixed available-usdc 2) " USDC")])]
   (input-shell {:prefix "$"
                 :suffix "USDC"
                 :input [:input {:id "outcome-ticket-amount"
                                 :type "text"
                                 :inputmode "decimal"
                                 :autocomplete "off"
                                 :placeholder "0"
                                 :class input-classes
                                 :value (or amount-text "")
                                 :on {:input (command-actions (cmd/set-order-size-input-mode :quote)
                                                              (cmd/set-order-size-display-input))}}]})
   (into [:div {:class ["grid" "grid-cols-4" "gap-1"]}]
         (map (fn [[label value]]
                [:button {:type "button"
                          :class ["h-8" "rounded-md" "border" "border-base-300" "text-xs" "text-ho-text-secondary"
                                  "hover:text-ho-text" "hover:border-ho-border-accent" "focus:outline-none" "focus:ring-0"]
                          :on {:click (command-actions (cmd/set-order-size-input-mode :quote)
                                                       (cmd/set-order-size-display value))}}
                 label]))
         (usd-presets available-usdc))])

(defn- shares-section
  [{:keys [shares-text held]}]
  [:div {:class ["space-y-1.5"] :data-role "outcome-ticket-shares"}
   [:div {:class ["flex" "items-center" "justify-between" "text-xs" "text-ho-text-secondary"]}
    [:label {:for "outcome-ticket-shares"} "Shares to sell"]
    [:span {:class ["num"]} (str "You hold " (js/Math.floor held))]]
   (input-shell {:suffix "shares"
                 :input [:input {:id "outcome-ticket-shares"
                                 :type "text"
                                 :inputmode "numeric"
                                 :autocomplete "off"
                                 :placeholder "0"
                                 :class input-classes
                                 :value (or shares-text "")
                                 :on {:input (command-actions (cmd/set-order-size-input-mode :base)
                                                              (cmd/set-order-size-display-input))}}]})
   (into [:div {:class ["grid" "grid-cols-3" "gap-1"]}]
         (map (fn [[label fraction]]
                [:button {:type "button"
                          :class ["h-8" "rounded-md" "border" "border-base-300" "text-xs" "text-ho-text-secondary"
                                  "hover:text-ho-text" "hover:border-ho-border-accent" "focus:outline-none" "focus:ring-0"]
                          :on {:click (command-actions (cmd/set-order-size-input-mode :base)
                                                       (cmd/set-order-size-display
                                                        (str (js/Math.floor (* held fraction)))))}}
                 label]))
         [["25%" 0.25] ["50%" 0.5] [(str "All · " (js/Math.floor held)) 1]])])

(defn- summary-section
  [rows fees]
  (into [:div {:class ["space-y-1" "rounded-lg" "bg-base-200/50" "px-3" "py-2" "text-xs"]
               :data-role "outcome-ticket-summary"}]
        (concat (map (fn [[label value]]
                       [:div {:class ["flex" "items-baseline" "justify-between" "gap-3"]}
                        [:span {:class ["text-ho-text-secondary"]} label]
                        [:span {:class ["num" "font-medium" "text-ho-text-hi"]} value]])
                     rows)
                [[:div {:class ["flex" "items-baseline" "justify-between" "gap-3"]}
                  [:span {:class ["text-ho-text-secondary"]
                          :title (:tooltip (footer/fee-row-copy fees))}
                   "Fee (taker / maker)"]
                  [:span {:class ["num" "text-ho-text-secondary"]} (or (:effective fees) "—")]]])))

(defn- disclosure
  [title body]
  [:details {:class ["group" "rounded-lg" "border" "border-base-300" "px-3" "py-2"]}
   [:summary {:class ["cursor-pointer" "list-none" "text-xs" "font-medium" "text-ho-text"]} title]
   [:div {:class ["pt-2" "text-xs" "leading-5" "text-ho-text-secondary"]} body]])

(defn- about-section
  [{:keys [rules timeline details]}]
  [:div {:class ["space-y-1.5"] :data-role "outcome-ticket-about"}
   (when (seq rules) (disclosure "Rules" rules))
   (when (seq timeline)
     (disclosure "Timeline"
                 (into [:div {:class ["space-y-1"]}]
                       (map (fn [[label value]]
                              [:div {:class ["flex" "justify-between" "gap-3"]}
                               [:span label]
                               [:span {:class ["num" "text-ho-text"]} (or value "—")]])
                            timeline))))
   (disclosure "Market details" details)])

(defn- mode-actions
  [mode held]
  (if (= :cash-out mode)
    (command-actions (cmd/set-order-side :sell)
                     (cmd/set-order-size-input-mode :base)
                     (cmd/set-order-size-display (str (js/Math.floor held))))
    (command-actions (cmd/set-order-side :buy)
                     (cmd/set-order-size-input-mode :quote)
                     (cmd/set-order-size-display ""))))

(defn ticket
  "`model` from `outcome-ticket-vm/ticket-model`; `vm` is the order-form vm."
  [model vm handlers]
  (let [{:keys [mode market-order? sides options no-explainer market-blocked? wide-note no-market-note two-sided?
                held held-text holds-any? summary-rows submit-label submit-blocked? fees]} model
        entry (:entry-mode handlers)
        submit (:submit vm)
        disabled? (boolean (or (:disabled? submit) submit-blocked?))]
    [:div {:class ["flex" "flex-col" "gap-2.5"] :data-role "outcome-ticket"}
     (when holds-any?
       (segmented {:role "outcome-ticket-mode"
                   :label "Buy or cash out"
                   :items [{:text "Buy" :active? (= :buy mode) :actions (mode-actions :buy held)}
                           {:text "Cash out"
                            :active? (= :cash-out mode)
                            :disabled? (not (pos? held))
                            :title (when-not (pos? held) "You hold no shares of this side")
                            :actions (mode-actions :cash-out held)}]}))
     (segmented {:role "outcome-ticket-order-type"
                 :label "Order type"
                 :items [{:text "Market"
                          :active? market-order?
                          :disabled? (boolean no-market-note)
                          :title (when no-market-note "No two-sided quotes: use a limit price")
                          :actions (:on-select-entry-market entry)}
                         {:text "Limit" :active? (not market-order?) :actions (:on-select-entry-limit entry)}]})
     (when options (options-section options))
     [:div {:class ["space-y-1.5"]}
      [:div {:class ["text-xs" "text-ho-text-secondary"]} (if (= :cash-out mode) "Your side" "Pick a side")]
      (into [:div {:class ["grid" "grid-cols-2" "gap-2"]}]
            (map (partial side-button handlers) sides))
      (when no-explainer
        [:div {:class ["text-xs" "text-ho-text-secondary"]} no-explainer])]
     (when (and no-market-note (or market-blocked? (not market-order?)))
       [:div {:class ["text-xs" "text-ho-warn"] :data-role "outcome-ticket-no-market"} no-market-note])
     (when wide-note
       [:div {:class ["text-xs" "text-ho-warn"]} wide-note])
     (when-not market-order? (limit-section model))
     (if (= :cash-out mode)
       (shares-section model)
       (amount-section model))
     (when (seq summary-rows) (summary-section summary-rows fees))
     [:div {:class (cond-> ["space-y-1.5"]
                     (:stale? options) (conj "outcome-stale-guard"))}
      (when (:stale? options)
        [:label {:class ["flex" "items-center" "gap-2" "text-xs" "text-ho-text"]}
         [:input {:type "checkbox"
                  :class ["h-4" "w-4" "rounded" "border-base-300"]
                  :data-role "outcome-stale-confirm"}]
         "I understand these prices may be stale"])
      (footer/submit-row {:submitting? (:submitting? vm)
                          :submit-disabled? disabled?
                          :submit-tooltip (:tooltip submit)
                          :submit-label submit-label
                          :submitting-label "Submitting..."
                          :on-submit (get-in handlers [:submit :on-submit])})]
     [:div {:class ["text-xs" "text-ho-text-secondary"] :data-role "outcome-ticket-holdings"}
      (str "You hold " held-text)]
     (about-section model)]))
