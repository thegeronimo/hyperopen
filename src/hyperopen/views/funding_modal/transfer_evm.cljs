(ns hyperopen.views.funding-modal.transfer-evm
  "The Transfer views while a move that touches HyperEVM runs and after it
   ends: progress (the wallet steps), pending (sent, receipt still out),
   failed, and success (arriving -> arrived).

   While a transaction may still be confirming these views never offer a
   way back to a submittable form: progress and pending only close. A
   failure whose outcome is unknown offers no one-click \"Try again\", only
   \"Back to edit\", which shows the balances as they are now. Every view
   focuses its heading when it first shows, so a screen reader hears the new
   state; returning to the form focuses its amount."
  (:require [clojure.string :as str]
            [hyperopen.views.funding-modal.shared :as shared]
            [hyperopen.views.funding-modal.transfer-parts :as parts]
            [hyperopen.views.ui.location-chip :as location-chip]))

(def close-note
  "If you close this, the transfer keeps going in your wallet.")

(def history-actions
  "Where a HyperEVM move is listed: the Portfolio's Account Activity tab, on
   its Account Transfers sub-tab (the ledger labels both bridge legs as
   account transfers)."
  [[:actions/close-funding-modal]
   [:actions/set-portfolio-account-info-tab :deposits-withdrawals]
   [:actions/set-portfolio-account-activity-sub-tab :account-transfers]
   [:actions/navigate "/portfolio"]])

(def ^:private history-label
  "View in Account Activity")

(defn- hidden-unless
  [visible? classes]
  (cond-> (vec classes)
    (not visible?) (conj "hidden")))

(defn- amount-text
  "`250 HYPE` from the run's result, thousands grouped, or \"\"."
  [{:keys [amount symbol]}]
  (str/trim (str (shared/format-grouped-amount-input (or amount "")) " " (or symbol ""))))

(defn- heading
  "A run view's heading. Its tabindex key is the DOM attribute's own
   spelling: Replicant writes attribute names as given, so a hyphenated key
   would leave the heading unfocusable and its focus hook a no-op."
  [view-id classes & children]
  (into [:h3 {:tabindex "-1"
              :class (into ["m-0" "outline-none"] classes)
              :data-role (str "funding-transfer-" (name view-id) "-heading")
              :replicant/on-render (parts/focus-when-shown view-id)}]
        children))

;; --- steps ----------------------------------------------------------------------

(defn- step-marker
  [index status]
  (case status
    (:done :skipped)
    [:span {:class ["grid" "h-[22px]" "w-[22px]" "shrink-0" "place-items-center" "rounded-full"
                    (if (= :done status) "bg-ho-accent" "bg-ho-surface-raised")
                    (if (= :done status) "text-ho-bg-deep" "text-ho-text-secondary")]}
     (parts/check-icon ["h-3" "w-3"])]

    :active
    [:span {:class ["h-[22px]" "w-[22px]" "shrink-0" "animate-spin" "rounded-full" "border-2"
                    "border-ho-accent" "border-r-transparent"]
            :aria-hidden "true"}]

    :failed
    [:span {:class ["grid" "h-[22px]" "w-[22px]" "shrink-0" "place-items-center" "rounded-full"
                    "border-2" "border-ho-sell" "text-xs" "font-bold" "text-ho-sell"]
            :aria-hidden "true"}
     "!"]

    [:span {:class ["grid" "h-[22px]" "w-[22px]" "shrink-0" "place-items-center" "rounded-full"
                    "border" "border-ho-border" "text-xs" "text-ho-text-secondary"]
            :aria-hidden "true"}
     (str index)]))

(def ^:private status-words
  {:done "Done"
   :skipped "Skipped"
   :active "In progress"
   :failed "Failed"
   :pending "Not started"})

(defn- step-row
  [index {:keys [id label status detail]}]
  [:li {:class (into ["flex" "items-start" "gap-3" "px-3.5" "py-3"]
                     (case status
                       :active ["bg-ho-accent-soft/60"]
                       :failed ["bg-ho-sell/10"]
                       []))
        :data-role (str "funding-transfer-step-" (name id))
        :data-step-status (name status)}
   (step-marker index status)
   [:div {:class ["flex" "min-w-0" "flex-col" "gap-0.5"]}
    [:span {:class (into ["text-sm" "font-semibold"]
                         (if (= :pending status) ["text-ho-text-secondary"] ["text-ho-text"]))}
     label
     [:span {:class ["sr-only"]} (str " — " (get status-words status ""))]]
    [:span {:class (hidden-unless (seq detail)
                                  (into ["text-xs"]
                                        (if (= :active status)
                                          ["text-ho-accent-bright"]
                                          ["text-ho-text-secondary"])))}
     (or detail "")]]])

(defn- steps-list
  "The run's steps. Not a live region: re-reading every step on each change
   would bury the one that moved; `step-announcement` speaks that one."
  [steps]
  [:div {:class ["overflow-hidden" "rounded-xl" "border" "border-ho-border-accent"]
         :data-role "funding-transfer-steps"}
   (into [:ol {:class ["m-0" "list-none" "divide-y" "divide-ho-border-accent-muted" "p-0"]}]
         (map-indexed (fn [i step] (step-row (inc i) step)))
         steps)])

(defn- active-step-text
  "`Step 2 of 3: Approve 1,000 USDC. Confirming on HyperEVM…`, for the step
   the run waits on, or \"\"."
  [{:keys [steps step-index step-count]}]
  (if-let [{:keys [label detail]} (get steps (dec (or step-index 0)))]
    (str "Step " step-index " of " step-count ": " label "."
         (when (seq detail) (str " " detail)))
    ""))

(defn- step-announcement
  "A screen-reader-only live line with the active step alone, so each step
   event reads one short sentence instead of the whole list."
  [evm]
  [:p {:role "status"
       :aria-live "polite"
       :class ["sr-only"]
       :data-role "funding-transfer-step-announcement"}
   (active-step-text evm)])

(defn- tx-link
  [tx-url]
  [:p {:class (hidden-unless (seq tx-url) ["m-0" "text-center" "text-sm"])
       :data-role "funding-transfer-explorer-link-slot"}
   (if (seq tx-url)
     (parts/explorer-link tx-url "View on explorer" "funding-transfer-explorer-link")
     "")])

;; --- progress -------------------------------------------------------------------

(defn progress-content
  "A run waiting on the wallet or the chain."
  [{:keys [evm balances summary destination actions] :as transfer}]
  (let [result (:result evm)]
    [:div {:class ["space-y-4"]
           :data-role "funding-transfer-progress"
           :replicant/on-render (parts/view-root-hook :progress)}
     (parts/route-header transfer)
     (heading :progress
              ["flex" "items-baseline" "justify-between" "gap-3" "rounded-lg" "border"
               "border-ho-border-accent" "bg-ho-bg-deep" "p-3" "font-normal"]
              [:span {:class ["text-xs" "text-ho-text-secondary"]} "Moving"]
              [:span {:class ["truncate" "text-xl" "font-semibold" "tabular-nums" "text-ho-text"]}
               (amount-text result)
               [:span {:class ["sr-only"]}
                (str " to " (parts/location-label (:to result)))]])
     (parts/balance-cards (-> balances
                              (assoc-in [:from :delta] nil)
                              (assoc-in [:to :delta] nil)))
     (steps-list (:steps evm))
     (step-announcement evm)
     (parts/summary-list destination summary)
     (parts/sticky-footer
      [:button {:type "button"
                :disabled true
                :class (parts/primary-button-classes true)
                :data-role "funding-transfer-submit"}
       (:submit-label actions)]
      [:p {:class ["m-0" "text-center" "text-xs" "text-ho-text-secondary"]
           :data-role "funding-transfer-close-note"}
       close-note])]))

;; --- pending --------------------------------------------------------------------

(defn pending-content
  "Sent, but the receipt did not arrive in time. The app keeps checking it
   in the background, but a transaction the wallet replaced or dropped
   never confirms, so the copy promises nothing and says how to get out;
   the modal only closes."
  [{:keys [evm]}]
  [:div {:class ["space-y-4"]
         :data-role "funding-transfer-pending"
         :replicant/on-render (parts/view-root-hook :pending)}
   [:div {:class ["space-y-1.5"]}
    (heading :pending ["text-base" "font-semibold" "text-ho-text"]
             "Submitted — confirmation pending")
    [:p {:class ["m-0" "text-xs" "leading-relaxed" "text-ho-text-secondary"]
         :data-role "funding-transfer-pending-detail"}
     (str (amount-text (:result evm)) " was sent and hasn't confirmed on HyperEVM yet. "
          "Check the transaction in your wallet or on the explorer. Transfers from HyperEVM "
          "stay on hold until it confirms; if your wallet shows it was replaced or cancelled, "
          "reload the page to start another.")]]
   (steps-list (:steps evm))
   (tx-link (:tx-url evm))
   (parts/sticky-footer
    [:button {:type "button"
              :class parts/secondary-button-classes
              :data-role "funding-transfer-done"
              :on {:click [[:actions/close-funding-modal]]}}
     "Close"])])

;; --- failed ---------------------------------------------------------------------

(defn failed-content
  "A run that stopped. \"Try again\" is the view-model's `:retry-action`
   (nil when it must not be offered: an outcome that may have moved funds,
   a block, or a draft that no longer submits); \"Back to edit\" is always
   there and shows why."
  [{:keys [evm]}]
  (let [retry (:retry-action evm)
        ;; The network retry re-enables the form; it does not resubmit.
        retry-label (if (= :actions/retry-funding-transfer-capability (first retry))
                      "Try switching again"
                      "Try again")]
    [:div {:class ["space-y-4"]
           :data-role "funding-transfer-failed"
           :replicant/on-render (parts/view-root-hook :failed)}
     (heading :failed ["text-base" "font-semibold" "text-ho-text"] "Transfer didn't finish")
     [:div {:role "alert"
            :class ["rounded-lg" "border" "border-ho-border-sell" "bg-ho-sell-soft/55" "px-3" "py-2.5"
                    "text-xs" "leading-relaxed" "text-ho-sell-tint"]
            :data-role "funding-transfer-error"}
      (or (:error evm) "The transfer didn't go through.")]
     (steps-list (:steps evm))
     (tx-link (:tx-url evm))
     (parts/sticky-footer
      [:button (cond-> {:type "button"
                        :class (hidden-unless retry (parts/primary-button-classes false))
                        :data-role "funding-transfer-try-again"}
                 retry (assoc :on {:click [retry]}))
       retry-label]
      [:button {:type "button"
                :class parts/secondary-button-classes
                :data-role "funding-transfer-back-to-edit"
                :on {:click [[:actions/reset-funding-transfer-evm]]}}
       "Back to edit"])]))

;; --- success --------------------------------------------------------------------

(defn- plural-seconds
  [n]
  (str n (if (= 1 n) " second" " seconds")))

(defn- arrival-copy
  "Heading and sub-line for a finished move, by arrival state."
  [{:keys [arrival result]} destination]
  (let [amount (amount-text result)
        place (parts/location-label (:to result))
        ;; From when the move went through, not from the wallet prompt: the
        ;; line reads as the bridge's time, not the user's signing time.
        started (or (:sent-at-ms result) (:started-at-ms result))
        arrived-at (:arrived-at-ms result)
        wallet (parts/lower-first (:display destination))]
    (case arrival
      :arrived
      [(str amount " is on " place)
       (str (if (and (number? started) (number? arrived-at))
              (str "Arrived in " (plural-seconds (max 1 (js/Math.round (/ (- arrived-at started) 1000)))))
              "Arrived")
            (when (seq wallet) (str " at " wallet)))]

      :slow
      [(str "Sent " amount " to " place)
       "Taking longer than usual — check Account Activity."]

      [(str "Sent " amount " to " place)
       (str "Arriving on " place "… usually a few seconds.")])))

(defn- result-card
  [location label value side highlight?]
  [:div {:class (into ["flex" "min-w-0" "flex-col" "gap-1.5" "rounded-lg" "bg-ho-surface" "p-3"]
                      (when highlight? ["border" "border-ho-border-accent"]))
         :data-role (str "funding-transfer-result-" (name side))}
   [:span {:class ["flex" "items-center" "gap-1.5"]}
    (or (location-chip/location-chip location) [:span])
    [:span {:class ["truncate" "text-xs" "text-ho-text-secondary"]
            :data-role (str "funding-transfer-result-" (name side) "-label")}
     label]]
   [:span {:class ["truncate" "text-base" "font-semibold" "tabular-nums" "text-ho-text"]}
    (or value "--")]])

(defn- result-cards
  "Both sides of a finished move. Until it has arrived the cards show the
   move itself (\"Sent −100 PURR\", \"Arriving +100 PURR\"): the live
   balances lag it on one side or the other, and a destination still at its
   old balance under \"Sent 100 PURR\" reads as funds gone missing. Once
   arrived they show each side's balance now."
  [{:keys [arrival result]} balances]
  (let [arrived? (= :arrived arrival)
        moved (amount-text result)]
    [:div {:class ["grid" "grid-cols-2" "gap-2"]}
     (if arrived?
       (result-card (:from result) (str (parts/location-label (:from result)) " now")
                    (get-in balances [:from :before]) :from false)
       (result-card (:from result) "Sent" (str "−" moved) :from false))
     (if arrived?
       (result-card (:to result) (str (parts/location-label (:to result)) " now")
                    (get-in balances [:to :before]) :to true)
       (result-card (:to result) "Arriving" (str "+" moved) :to true))]))

(defn- add-to-wallet-box
  [{:keys [add-to-wallet? result]}]
  [:div {:class (hidden-unless add-to-wallet?
                               ["flex" "items-center" "justify-between" "gap-3" "rounded-xl" "border"
                                "border-ho-border-accent" "bg-ho-bg-deep" "p-3.5"])
         :data-role "funding-transfer-add-to-wallet-box"}
   [:div {:class ["flex" "min-w-0" "flex-col" "gap-0.5"]}
    [:span {:class ["text-sm" "font-semibold" "text-ho-text"]} "Don't see it in your wallet?"]
    [:span {:class ["text-xs" "text-ho-text-secondary"]}
     (str "Add the HyperEVM network so your wallet shows " (or (:symbol result) "it") " there.")]]
   [:button (cond-> {:type "button"
                     ;; Disabled while its slot is hidden, so it never counts
                     ;; as focusable.
                     :disabled (not add-to-wallet?)
                     :class ["h-9" "shrink-0" "rounded-lg" "border" "border-ho-border" "bg-ho-surface" "px-3"
                             "text-xs" "font-semibold" "text-ho-text-hi" "transition-colors"
                             "hover:border-ho-accent/60"]
                     :data-role "funding-transfer-add-to-wallet"}
              add-to-wallet? (assoc :on {:click [[:actions/add-funding-transfer-token-to-wallet]]}))
    "Add to wallet"]])

(defn success-content
  "The move went through: arriving, then arrived (or slow)."
  [{:keys [evm balances destination]}]
  (let [[title sub] (arrival-copy evm destination)]
    [:div {:class ["space-y-5" "pt-1"]
           :data-role "funding-transfer-success"
           :data-arrival (name (:arrival evm))
           :replicant/on-render (parts/view-root-hook :success)}
     [:div {:class ["flex" "flex-col" "items-center" "gap-3" "text-center"]}
      [:span {:class ["grid" "h-14" "w-14" "place-items-center" "rounded-full" "border"
                      "border-ho-accent-soft-hi" "bg-ho-accent-soft" "text-ho-accent"]}
       (parts/check-icon ["h-[26px]" "w-[26px]"])]
      (heading :success ["text-xl" "font-semibold" "text-ho-text"] title)
      [:p {:role "status"
           :aria-live "polite"
           :class (into ["m-0" "text-sm"]
                        (if (= :slow (:arrival evm)) ["text-ho-warn"] ["text-ho-text-secondary"]))
           :data-role "funding-transfer-arrival"}
       sub]]
     (result-cards evm balances)
     (add-to-wallet-box evm)
     [:div {:class ["flex" "flex-wrap" "justify-center" "gap-x-5" "gap-y-2" "text-sm"]}
      [:button {:type "button"
                :class (into ["bg-transparent" "p-0"] parts/link-classes)
                :data-role "funding-transfer-view-history"
                :on {:click history-actions}}
       history-label]
      [:span {:class (hidden-unless (seq (:tx-url evm)) [])}
       (if (seq (:tx-url evm))
         (parts/explorer-link (:tx-url evm) "View on explorer" "funding-transfer-explorer-link")
         "")]]
     (parts/sticky-footer
      [:button {:type "button"
                :class (parts/primary-button-classes false)
                :data-role "funding-transfer-done"
                :on {:click [[:actions/close-funding-modal]]}}
       "Done"])]))
