(ns hyperopen.views.funding-modal.transfer
  "The Transfer form: From and To places (Perps, Spot, HyperEVM), the asset
   list for routes that touch HyperEVM, the amount, before/after balances,
   the summary, the blocked card with its fix, and the submit button.

   Everything renders from the `:transfer` view-model; conditional parts are
   always-present slots hidden with a class, so the tree keeps its shape
   while the user edits."
  (:require [clojure.string :as str]
            [hyperopen.views.funding-modal.shared :as shared]
            [hyperopen.views.funding-modal.transfer-parts :as parts]
            [hyperopen.views.ui.location-chip :as location-chip]))

(def ^:private amount-input-id
  parts/amount-input-id)

(def ^:private message-id
  "funding-transfer-message")

(defn- hidden-unless
  [visible? classes]
  (cond-> (vec classes)
    (not visible?) (conj "hidden")))

;; --- places ---------------------------------------------------------------------

(defn- reason-id
  [reasons reason]
  (str "funding-transfer-place-reason-" (inc (.indexOf (to-array reasons) reason))))

(defn- place-button
  [reasons locked? {:keys [id label selected? disabled? reason action data-role]}]
  (let [blocked? (or disabled? locked?)]
    [:button (cond-> {:type "button"
                      :class (into ["h-11" "min-w-0" "flex-auto" "truncate" "rounded-md" "px-1.5"
                                    "text-xs" "font-medium" "transition-colors" "sm:h-8"
                                    "focus:outline-none" "focus-visible:ring-2"
                                    "focus-visible:ring-ho-accent/50"]
                                   (cond
                                     selected? ["bg-ho-surface-raised" "text-ho-text-hi"]
                                     disabled? ["cursor-not-allowed" "text-ho-text-dim"]
                                     :else ["text-ho-text-secondary" "hover:text-ho-text"]))
                      :aria-pressed (if selected? "true" "false")
                      :data-role data-role
                      :data-location (name id)}
               blocked? (assoc :aria-disabled "true")
               (and disabled? (seq reason)) (assoc :aria-describedby (reason-id reasons reason))
               (not blocked?) (assoc :on {:click [action]}))
     label]))

(defn- place-group
  [side options reasons locked? balance selected-location]
  [:div {:class ["flex" "min-w-0" "flex-1" "flex-col" "gap-1.5"]}
   [:span {:class parts/label-classes
           :id (str "funding-transfer-" (name side) "-label")}
    (if (= :from side) "From" "To")]
   (into [:div {:role "group"
                :aria-labelledby (str "funding-transfer-" (name side) "-label")
                :class ["flex" "gap-0.5" "rounded-lg" "border" "border-ho-border" "bg-ho-bg-deep" "p-0.5"]
                :data-role (str "funding-transfer-" (name side) "-group")}]
         (map #(place-button reasons locked? %))
         options)
   [:span {:class ["flex" "min-h-[18px]" "items-center" "gap-1.5" "text-xs" "tabular-nums"
                   "text-ho-text-secondary"]
           :data-role (str "funding-transfer-" (name side) "-balance")}
    (or (location-chip/location-chip selected-location) [:span])
    [:span {:class ["truncate"]} (or balance "")]]])

(defn- disabled-reasons
  "The distinct reasons of every place that can't be chosen, in order."
  [from-options to-options]
  (->> (concat from-options to-options)
       (keep (fn [{:keys [disabled? reason]}] (when (and disabled? (seq reason)) reason)))
       distinct
       vec))

(defn- places
  [{:keys [route from-options to-options swap-action balances]} locked?]
  (let [reasons (disabled-reasons from-options to-options)]
    [:div {:class ["space-y-1.5"]}
     [:div {:class ["flex" "flex-col" "gap-2" "sm:flex-row" "sm:items-start"]}
      (place-group :from from-options reasons locked?
                   (get-in balances [:from :before]) (:from route))
      [:button (cond-> {:type "button"
                        :aria-label "Swap direction"
                        :data-role "funding-transfer-swap"
                        :disabled locked?
                        :class ["grid" "h-11" "w-11" "shrink-0" "place-items-center" "self-center"
                                "rounded-full" "border" "border-ho-border" "bg-ho-bg" "text-ho-accent"
                                "transition-colors" "hover:border-ho-accent/60" "sm:mt-[22px]" "sm:h-9"
                                "sm:w-9" "disabled:cursor-not-allowed" "disabled:opacity-50"
                                "focus:outline-none" "focus-visible:ring-2"
                                "focus-visible:ring-ho-accent/50"]}
                 (not locked?) (assoc :on {:click [swap-action]}))
       (parts/swap-icon)]
      (place-group :to to-options reasons locked?
                   (get-in balances [:to :before]) (:to route))]
     (into [:div {:class (hidden-unless (seq reasons) ["space-y-0.5"])
                  :data-role "funding-transfer-place-reasons"}]
           (map (fn [reason]
                  [:p {:id (reason-id reasons reason)
                       :class ["text-xs" "text-ho-text-secondary"]}
                   reason]))
           reasons)]))

;; --- asset ----------------------------------------------------------------------

(defn- asset-row
  "One asset as a native radio row. A disabled row dims its radio and symbol
   only: its reason is the user's only explanation, so it keeps full
   secondary-text contrast."
  [locked? {:keys [index symbol balance-display selected? disabled? reason action]}]
  (let [reason-id (str "funding-transfer-asset-reason-" index)
        dim (when (and disabled? (not selected?)) ["opacity-70"])]
    [:label {:class (into ["flex" "cursor-pointer" "items-center" "gap-2.5" "px-3" "py-2"
                           "transition-colors"]
                          (cond
                            selected? ["bg-ho-surface"]
                            disabled? ["cursor-not-allowed"]
                            :else ["hover:bg-ho-surface/60"]))
             :data-role (str "funding-transfer-asset-option-" index)}
     [:input (cond-> {:type "radio"
                      :name "funding-transfer-asset"
                      :value (str index)
                      :checked (true? selected?)
                      :disabled (boolean (or disabled? locked?))
                      :class (into ["h-3.5" "w-3.5" "shrink-0" "border-ho-border" "bg-ho-bg-deep"
                                    "text-ho-accent" "focus:ring-ho-accent/40" "focus:ring-offset-0"]
                                   dim)}
               (seq reason) (assoc :aria-describedby reason-id)
               (not (or disabled? locked?)) (assoc :on {:change [action]}))]
     [:span {:class ["flex" "min-w-0" "flex-1" "flex-col"]}
      [:span {:class (into ["text-sm" "font-semibold" "text-ho-text"] dim)} symbol]
      [:span {:id reason-id
              :class (hidden-unless (seq reason) ["text-xs" "text-ho-text-secondary"])}
       (or reason "")]]
     [:span {:class ["shrink-0" "text-xs" "tabular-nums" "text-ho-text-secondary"]}
      balance-display]]))

(defn- asset-list
  [{:keys [visible? options empty-message]} locked?]
  [:fieldset {:class (hidden-unless visible? ["m-0" "min-w-0" "space-y-1.5" "border-0" "p-0"])
              :data-role "funding-transfer-asset-list"}
   [:legend {:class (into ["mb-1.5" "p-0"] parts/label-classes)} "Asset"]
   (into [:div {:class (hidden-unless (seq options)
                                      ["max-h-32" "overflow-y-auto" "rounded-lg" "border"
                                       "border-ho-border" "bg-ho-bg-deep"
                                       "divide-y" "divide-ho-border/60"])}]
         (map #(asset-row locked? %))
         options)
   [:p {:class (hidden-unless (and visible? (empty? options) (seq empty-message))
                              ["rounded-lg" "bg-ho-surface" "px-3" "py-2.5" "text-xs"
                               "text-ho-text-secondary"])
        :data-role "funding-transfer-asset-empty"}
    (or empty-message "")]])

;; --- amount ---------------------------------------------------------------------

(defn- percent-chip
  [locked? {:keys [label action]}]
  [:button (cond-> {:type "button"
                    :disabled locked?
                    :aria-label (str label " of the maximum")
                    :data-role (str "funding-transfer-percent-" (str/replace label "%" ""))
                    :class ["h-7" "rounded-full" "border" "border-ho-border" "px-3" "text-xs"
                            "text-ho-text-secondary" "transition-colors" "hover:border-ho-accent/60"
                            "hover:text-ho-text" "disabled:cursor-not-allowed" "disabled:opacity-50"
                            "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-accent/50"]}
             (not locked?) (assoc :on {:click [action]}))
   label])

(def ^:private notice-id
  "funding-transfer-amount-notice")

(defn- described-by
  "The ids the amount input points `aria-describedby` at: the form's
   message and the amount notice (rounding, the gas MAX keeps), when shown."
  [message notice]
  (some->> [(when (seq message) message-id) (when (seq notice) notice-id)]
           (remove nil?)
           seq
           (str/join " ")))

(defn- max-label
  "The MAX button's accessible name; an unknown maximum is not read out."
  [max-display symbol]
  (if (or (str/blank? max-display) (= "--" max-display))
    "Use the maximum"
    (str/trim (str "Use the maximum: " max-display " " symbol))))

(defn- amount-field
  [{:keys [amount percent-actions usd-estimate balances message]} locked?]
  (let [{:keys [value symbol notice]} amount
        describedby (described-by message notice)]
    [:div {:class ["space-y-1.5"]}
     [:label {:for amount-input-id :class (into ["block"] parts/label-classes)} "Amount"]
     [:div {:class ["flex" "h-[52px]" "items-center" "gap-2.5" "rounded-lg" "border" "border-ho-border"
                    "bg-ho-bg-deep" "px-3" "transition-colors" "focus-within:border-ho-accent"]}
      [:input (cond-> {:type "text"
                       :id amount-input-id
                       :inputmode "decimal"
                       :autocomplete "off"
                       :placeholder "0.00"
                       :disabled locked?
                       :value (shared/format-grouped-amount-input value)
                       :class ["min-w-0" "flex-1" "border-0" "bg-transparent" "p-0" "text-xl"
                               "font-semibold" "tabular-nums" "text-ho-text-hi" "outline-none" "ring-0"
                               "focus:border-0" "focus:ring-0" "disabled:cursor-not-allowed"
                               "disabled:opacity-70"]
                       :data-role "funding-transfer-amount-input"
                       :on {:input [[:actions/enter-funding-transfer-amount [:event.target/value]]]}}
                (seq message) (assoc :aria-invalid "true")
                describedby (assoc :aria-describedby describedby))]
      [:span {:class ["shrink-0" "text-sm" "text-ho-text-secondary"]
              :data-role "funding-transfer-amount-symbol"}
       (or symbol "")]
      [:button (cond-> {:type "button"
                        :disabled locked?
                        :aria-label (max-label (:max-display amount) symbol)
                        :data-role "funding-transfer-max"
                        :class ["h-[26px]" "shrink-0" "rounded-md" "border" "border-ho-border-accent"
                                "bg-ho-accent-soft" "px-2" "text-xs" "font-bold" "text-ho-accent-bright"
                                "transition-colors" "hover:bg-ho-accent-soft-hi"
                                "disabled:cursor-not-allowed" "disabled:opacity-50"
                                "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-accent/50"]}
                 (not locked?) (assoc :on {:click [[:actions/set-funding-amount-to-max]]}))
       "MAX"]]
     (into [:div {:class (hidden-unless (seq percent-actions) ["flex" "flex-wrap" "gap-2"])}]
           (map #(percent-chip locked? %))
           percent-actions)
     [:div {:class ["flex" "justify-between" "gap-3" "text-xs" "tabular-nums" "text-ho-text-secondary"]}
      [:span {:data-role "funding-transfer-usd-estimate"} (or usd-estimate "")]
      [:span {:class ["truncate" "text-right"]
              :data-role "funding-transfer-available"}
       (if-let [available (get-in balances [:from :available])]
         (str "Available " available)
         "")]]
     [:p {:id notice-id
          :class (hidden-unless (seq notice) ["text-xs" "text-ho-text-secondary"])
          :data-role "funding-transfer-amount-notice"}
      (or notice "")]]))

;; --- blocked card ---------------------------------------------------------------

(defn- fix-data-role
  [code]
  (if (= :no-chain-switch code)
    "funding-transfer-capability-retry"
    "funding-transfer-gas-fix"))

(defn- fix-block
  "The fix under a blocked card. It is never natively disabled: a busy fix
   (a top-up being sent or arriving) or one with a reason is
   `aria-disabled` with no click handler, so a keyboard user who pressed it
   keeps focus on it instead of losing it to the dialog's Close button."
  [code {:keys [label action status status-message disabled? reason]}]
  (let [reason-id "funding-transfer-fix-reason"]
    [:div {:class ["space-y-2"]}
     [:button (cond-> {:type "button"
                       :aria-disabled (when disabled? "true")
                       :aria-busy (when (contains? #{:submitting :sent} status) "true")
                       :data-role (fix-data-role code)
                       :data-fix-status (name (or status :idle))
                       :class (if disabled?
                                ["h-10" "w-full" "rounded-lg" "border" "border-ho-warn/30" "bg-transparent"
                                 "px-3" "text-sm" "font-semibold" "text-ho-warn/60" "cursor-not-allowed"
                                 "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-warn/50"]
                                ["h-10" "w-full" "rounded-lg" "border" "border-ho-warn" "bg-transparent"
                                 "px-3" "text-sm" "font-semibold" "text-ho-warn" "transition-colors"
                                 "hover:bg-ho-warn/10" "focus:outline-none" "focus-visible:ring-2"
                                 "focus-visible:ring-ho-warn/50"])}
              (seq reason) (assoc :aria-describedby reason-id)
              (not disabled?) (assoc :on {:click [action]}))
      label]
     [:p {:id reason-id
          :class (hidden-unless (seq reason) ["text-center" "text-xs" "text-ho-warn"])
          :data-role "funding-transfer-fix-reason"}
      (or reason "")]
     ;; Its own live region: the top-up's progress is read out, the fix
     ;; button's label is not.
     [:p {:role "status"
          :aria-live "polite"
          :class (hidden-unless (seq status-message)
                                (into ["text-center" "text-xs" "tabular-nums"]
                                      (if (= :failed status) ["text-ho-sell"] ["text-ho-warn/70"])))
          :data-role "funding-transfer-fix-status"}
      (or status-message "")]]))

(defn- blocked-card
  "Why the route can't move right now. Every slot is always present. The
   live region (`funding-transfer-blocked-status`) holds only the title and
   message, so a change reads out that text and never the fix button's
   label; the explorer link and the fix sit outside it. A block that only
   waits on a read (`:checking?`) is a quiet line, not a warning card, and
   leaves the form's details in place."
  [{:keys [code checking? title message explorer-url fix] :as blocked}]
  (let [card? (boolean (and blocked (not checking?)))]
    [:div {:data-role "funding-transfer-blocked-region"
           :replicant/on-render (parts/fix-focus-hook (some? fix))}
     [:div (cond-> {:class (if card?
                             ["flex" "flex-col" "gap-3" "rounded-xl" "border" "border-ho-warn/40"
                              "bg-ho-warn/10" "p-3.5"]
                             ["flex" "flex-col"])}
             blocked (assoc :data-role "funding-transfer-blocked"
                            :data-blocked-code (name code)
                            :data-checking (if checking? "true" "false")))
      ;; A titled card (missing gas) announces its title only: its message
      ;; carries the gas estimate, which changes with every gas-price poll
      ;; and would be read out again each time. Untitled blocks announce
      ;; their message, which is all they say.
      (let [titled? (boolean (and card? (seq title)))
            message-classes (if card?
                              ["text-xs" "leading-relaxed" "text-ho-text"]
                              ["text-xs" "text-ho-text-secondary"])]
        [:div {:class ["flex" "items-start" "gap-2.5"]}
         (if card? (parts/warn-icon) [:span {:class ["hidden"]}])
         [:div {:class ["flex" "min-w-0" "flex-col" "gap-1"]}
          [:div {:role "status"
                 :aria-live "polite"
                 :data-role "funding-transfer-blocked-status"}
           [:span {:class (hidden-unless titled? ["text-sm" "font-semibold" "text-ho-warn"])}
            (or title "")]
           [:span {:class (hidden-unless (not titled?) message-classes)}
            (if titled? "" (or message ""))]]
          [:span {:class (hidden-unless titled? message-classes)
                  :data-role "funding-transfer-blocked-detail"}
           (if titled? (or message "") "")]]])
      [:span {:class (hidden-unless (seq explorer-url) ["-mt-2" "pl-7" "text-xs"])}
       (if (seq explorer-url)
         (parts/explorer-link explorer-url "View transaction" "funding-transfer-blocked-explorer")
         "")]
      (if fix
        (fix-block code fix)
        [:span {:class ["hidden"]}])]]))

;; --- notice ---------------------------------------------------------------------

(defn- verification-notice
  "The unverified-contract notice. Its explorer link renders only with the
   notice: a link left in the hidden slot would still count as the dialog's
   last focusable element and let Tab escape the dialog."
  [{:keys [notice explorer-url]}]
  [:div {:class (hidden-unless (seq notice) ["flex" "items-start" "gap-2.5" "rounded-xl" "bg-ho-surface"
                                             "px-3.5" "py-3"])
         :data-role "funding-transfer-verification-notice"}
   (parts/info-icon)
   [:p {:class ["m-0" "text-xs" "leading-normal" "text-ho-text-secondary"]}
    (or notice "")
    (if (and (seq notice) (seq explorer-url))
      [:span " " (parts/explorer-link explorer-url "View on explorer" "funding-transfer-token-explorer")]
      [:span])]])

(defn- form-footer
  "The sticky footer: the draft's message (why the submit is disabled, or
   the last submit error) right above the submit, so it stays in view with
   it while the panel scrolls. The live region is always present, so a new
   message is read out; the input and the submit point at it."
  [message submit-label submit-disabled?]
  (parts/sticky-footer
   [:div
    [:div {:role "status"
           :aria-live "polite"
           :data-role "funding-transfer-message-region"}
     [:p {:id message-id
          :class (hidden-unless (seq message)
                                ["m-0" "mb-2" "rounded-md" "border" "border-ho-border-sell"
                                 "bg-ho-sell-soft/55" "px-3" "py-2" "text-xs" "leading-relaxed"
                                 "text-ho-sell-tint"])
          :data-role "funding-transfer-message"}
      (or message "")]]
    [:button (cond-> {:type "button"
                      :disabled (boolean submit-disabled?)
                      :class (parts/primary-button-classes (boolean submit-disabled?))
                      :data-role "funding-transfer-submit"}
               (seq message) (assoc :aria-describedby message-id)
               (not submit-disabled?) (assoc :on {:click [[:actions/submit-funding-transfer]]}))
     (or submit-label "Transfer")]]))

;; --- form -----------------------------------------------------------------------

(defn render-content
  "The Transfer form for the `:transfer` view-model. The balances and
   summary give way to a blocked card the user can act on (missing gas, an
   unmovable token, …); a block that only waits on a read keeps them."
  [{:keys [asset balances destination summary blocked message actions] :as transfer}]
  (let [{:keys [submit-label submit-disabled? submitting?]} actions
        locked? (true? submitting?)
        show-details? (or (nil? blocked) (true? (:checking? blocked)))]
    [:div {:class ["space-y-3"]
           :data-role "funding-transfer-form"
           :replicant/on-render (parts/view-root-hook :form)}
     (places transfer locked?)
     (asset-list asset locked?)
     (amount-field transfer locked?)
     (blocked-card blocked)
     (verification-notice (:selected asset))
     [:div {:class (hidden-unless show-details? ["space-y-4"])
            :data-role "funding-transfer-details"}
      (parts/balance-cards balances)
      (parts/summary-list destination summary)]
     (form-footer message submit-label submit-disabled?)]))
