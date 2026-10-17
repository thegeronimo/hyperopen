(ns hyperopen.views.portfolio.optimize.vault-funding-sheet
  "Fund-this-allocation sheet: plan (amount, rules, per-vault actions),
  review, then progress. Rendered over any optimizer route; returns nil while
  closed."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.vault-funding.model :as model]
            [hyperopen.portfolio.optimizer.application.vault-funding.run :as run]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.views.ui.dialog-focus :as dialog-focus]))

(def ^:private focus-on-render
  ;; Focus moves into the sheet on open, Tab stays inside it, and focus goes
  ;; back to the button that opened it on close.
  (dialog-focus/dialog-focus-on-render
   {:restore-selector "[data-role='portfolio-optimizer-vault-funding-cta'], [data-role='portfolio-optimizer-tear-sheet-card-fund']"}))

(defn- usd
  [x]
  (str "$" (.toLocaleString (js/Number. (or x 0)) "en-US"
                            #js {:minimumFractionDigits 2 :maximumFractionDigits 2})))

(defn- pct
  [x]
  (str (.toFixed (* 100 (or x 0)) 1) "%"))

(defn- date
  [ms]
  (when (number? ms)
    (.toLocaleDateString (js/Date. ms) "en-US" #js {:month "short" :day "numeric"})))

(defn- command
  [c]
  [[:actions/portfolio-optimizer-vault-funding-command c]])

(defn- field
  [f v]
  [[:actions/set-portfolio-optimizer-vault-funding-field f v]])

(def ^:private primary-button
  ["min-h-11" "rounded-lg" "bg-primary" "px-4" "text-sm" "font-semibold" "text-primary-content"
   "hover:bg-primary/90" "disabled:cursor-not-allowed" "disabled:opacity-40"])

(def ^:private secondary-button
  ["min-h-11" "rounded-lg" "border" "border-base-300" "px-4" "text-sm" "text-trading-text" "hover:bg-base-200/40"])

(defn- note
  [tone data-role text]
  [:div {:role "note"
         :class (into ["rounded-lg" "border" "px-4" "py-3" "text-sm" "leading-6"]
                      (case tone
                        :warn ["border-[#e2b545]/40" "bg-[#e2b545]/10" "text-[#efe1b5]"]
                        :error ["border-trading-red/40" "bg-trading-red/10" "text-trading-text"]
                        ["border-base-300" "bg-base-200/30" "text-trading-muted"]))
         :data-role data-role}
   text])

(defn- action-cell
  [{:keys [status withdraw deposit over locked-until-ms held]}]
  (case status
    :withdraw [[:span {:class ["font-medium" "text-[#e2b545]"]} (str "Withdraw " (usd withdraw))]
               "Above target · unlocked"]
    :over-locked [[:span {:class ["text-[#efe1b5]"]} "No new cash"]
                  (str "Above target by " (usd over) " · locked until " (date locked-until-ms))]
    :over-kept [[:span {:class ["text-[#efe1b5]"]} "No new cash"]
                (str "Above target by " (usd over))]
    :deposit [[:span {:class ["font-medium" "text-ho-accent-bright"]} (str "Deposit " (usd deposit))]
              (if (pos? (or held 0)) "Tops up toward target" "New position")]
    :closed [[:span {:class ["text-trading-muted"]} "Closed to deposits"] "Its leader has turned deposits off"]
    :too-small [[:span {:class ["text-trading-muted"]} "Skipped"] "Its share is under $1"]
    [[:span {:class ["text-trading-muted"]} "On target"] ""]))

(defn- plan-row
  [{:keys [address label weight held target-usd after] :as row} after-total]
  (let [[action sub] (action-cell row)
        share (if (pos? after-total) (/ after after-total) 0)]
    [:div {:class ["grid" "min-h-12" "grid-cols-[minmax(160px,2fr)_70px_100px_100px_minmax(200px,2fr)_70px]"
                   "items-center" "gap-3" "border-b" "border-base-300/60" "px-5" "text-sm"]
           :data-role "portfolio-optimizer-vault-funding-row"
           :data-address address}
     [:span {:class ["truncate" "text-trading-text"]} label]
     [:span {:class ["num" "text-right"]} (pct weight)]
     [:span {:class ["num" "text-right" "text-trading-muted"]} (if (pos? (or held 0)) (usd held) "—")]
     [:span {:class ["num" "text-right" "text-trading-muted"]} (usd target-usd)]
     [:span {:class ["flex" "flex-col" "gap-0.5"]}
      action
      [:span {:class ["text-xs" "text-trading-muted"]} sub]]
     [:span {:class (into ["num" "text-right"] (if (> (js/Math.abs (- share weight)) 0.01) ["text-[#e2b545]"] ["text-trading-text"]))}
      (pct share)]]))

(defn- checkbox
  [{:keys [checked? disabled? on title sub data-role]}]
  [:label {:class (into ["flex" "min-h-11" "cursor-pointer" "items-start" "gap-3"] (when disabled? ["opacity-50"]))}
   ;; The sheet mounts outside the optimizer page's scope, so the shared
   ;; .optimizer-checkbox style needs its own scope here.
   [:span {:class ["portfolio-optimizer" "flex" "flex-none" "pt-0.5"]}
    [:input {:type "checkbox"
             :checked (boolean checked?)
             :disabled (boolean disabled?)
             :class ["optimizer-checkbox"]
             :data-role data-role
             :on {:change on}}]]
   [:span [:span {:class ["block" "text-sm" "text-trading-text"]} title]
    [:span {:class ["mt-0.5" "block" "text-xs" "leading-5" "text-trading-muted"]} sub]]])

(defn- notes
  [{:keys [plan options closed spot-shortfall mixed? held-total held-count other-held]}]
  (let [rows (:rows plan)
        over-locked (filter #(= :over-locked (:status %)) rows)
        over-kept (filter #(= :over-kept (:status %)) rows)
        scaled? (and (:include-existing? options)
                     (some #(and (= :deposit (:status %)) (< (+ (:held %) (:deposit %)) (- (:target-usd %) 1))) rows)
                     (< (get-in plan [:totals :leftover]) 1))]
    (cond-> []
      mixed? (conj (note :info "portfolio-optimizer-vault-funding-mixed"
                         "This funds the vault part of the run only; its perp and spot legs are traded from Review trades."))
      (and (:include-existing? options) (pos? held-count))
      (conj (note :info "portfolio-optimizer-vault-funding-held"
                  (str "You hold " (usd held-total) " across " held-count " of these vaults"
                       "; it counts toward the target."
                       (when (pos? other-held) (str " " (usd other-held) " in other vaults is left alone.")))))
      (seq over-locked)
      (conj (note :warn "portfolio-optimizer-vault-funding-over-locked"
                  (str (str/join " " (map #(str (:label %) " is " (usd (:over %)) " above target and locked until "
                                                (date (:locked-until-ms %)) ". Consider withdrawing " (usd (:over %))
                                                " once it unlocks to bring it in line.")
                                          over-locked)))))
      (seq over-kept)
      (conj (note :warn "portfolio-optimizer-vault-funding-over-kept"
                  (str (str/join ", " (map :label over-kept))
                       (if (= 1 (count over-kept)) " is" " are")
                       " above target and gets no new cash. Turn on “Withdraw from vaults above target first” to bring "
                       (if (= 1 (count over-kept)) "it" "them") " in line.")))
      scaled? (conj (note :warn "portfolio-optimizer-vault-funding-scaled"
                          "There is not enough cash to fill every vault, so each shortfall closes by the same fraction."))
      (seq closed)
      (conj [:div {:class ["rounded-lg" "border" "border-base-300" "px-4" "py-3" "text-sm"]
                   :data-role "portfolio-optimizer-vault-funding-closed"}
             [:p {:class ["text-trading-text"]}
              (str (count closed) (if (= 1 (count closed)) " vault is" " vaults are") " closed to deposits. Its share:")]
             [:div {:role "radiogroup" :aria-label "Closed vault's share" :class ["mt-2" "flex" "flex-wrap" "gap-2"]}
              (for [[mode label] [[:spread "Spread over the open vaults"] [:cash "Keep it as cash"]]]
                ^{:key (str "closed-" (name mode))}
                [:button {:type "button"
                          :role "radio"
                          :aria-checked (= mode (:closed-mode options))
                          :class (into ["min-h-9" "rounded-md" "border" "px-3" "text-sm"]
                                       (if (= mode (:closed-mode options))
                                         ["border-primary/60" "bg-primary/10" "text-trading-text"]
                                         ["border-base-300" "text-trading-muted"]))
                          :on {:click (field :closed-mode mode)}}
                 label])]])
      spot-shortfall
      (conj [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3" "rounded-lg" "border" "border-[#e2b545]/40"
                           "bg-[#e2b545]/10" "px-4" "py-3" "text-sm" "text-[#efe1b5]"]
                   :data-role "portfolio-optimizer-vault-funding-spot"}
             [:span (str "Vault deposits use perps USDC on this account. Move "
                         (usd (:needed spot-shortfall)) " from spot to perps first (your wallet will ask you to sign).")]
             [:button {:type "button"
                       :class secondary-button
                       :data-role "portfolio-optimizer-vault-funding-transfer"
                       :on {:click [[:actions/open-funding-transfer-modal [:event.currentTarget/bounds]
                                     "portfolio-optimizer-vault-funding-transfer" {:from :spot :to :perps}]]}}
              "Move USDC to perps"]]))))

(defn- plan-screen
  [{:keys [plan options available account-mode block] :as funding}]
  (let [rows (:rows plan)
        after-total (reduce + 0 (map :after rows))
        {:keys [withdraw deposit leftover]} (:totals plan)
        n-w (count (filter #(pos? (:withdraw %)) rows))
        n-d (count (filter #(pos? (:deposit %)) rows))]
    [:div {:class ["flex" "flex-col"]}
     [:section {:class ["grid" "gap-6" "border-b" "border-base-300" "px-5" "py-5" "md:grid-cols-2"]}
      [:div {:class ["flex" "flex-col" "gap-2"]}
       [:label {:for "vault-funding-amount" :class ["text-sm" "text-trading-muted"]} "New cash to add"]
       [:div {:class ["flex" "min-h-12" "items-center" "gap-2" "rounded-lg" "border" "border-base-300" "bg-base-100" "px-3"]}
        [:span {:class ["num" "text-lg" "text-trading-muted"]} "$"]
        [:input {:id "vault-funding-amount"
                 :type "text"
                 :inputmode "decimal"
                 :autocomplete "off"
                 :placeholder "0.00"
                 :value (:amount-input options)
                 :class ["num" "min-w-0" "flex-1" "border-0" "bg-transparent" "text-lg" "text-trading-text" "focus:outline-none"]
                 :data-role "portfolio-optimizer-vault-funding-amount"
                 :on {:input [[:actions/set-portfolio-optimizer-vault-funding-field :amount-input [:event.target/value]]]
                      :blur [[:actions/set-portfolio-optimizer-vault-funding-field :amount-format [:event.target/value]]]}}]
        [:span {:class ["text-xs" "text-trading-muted"]} "USDC"]]
       [:div {:class ["flex" "flex-wrap" "gap-1.5"]}
        (for [[label f] [["25%" 0.25] ["50%" 0.5] ["Max" 1]]]
          ^{:key (str "fraction-" label)}
          [:button {:type "button"
                    :class ["min-h-9" "rounded-md" "border" "border-base-300" "px-3" "text-xs" "text-trading-text" "hover:bg-base-200/40"]
                    :data-role (str "portfolio-optimizer-vault-funding-fraction-" label)
                    :on {:click (field :amount-fraction f)}}
           label])]
       [:span {:class ["text-xs" "text-trading-muted"]}
        "Available: " [:span {:class ["num" "text-trading-text"]} (if (number? available) (usd available) "—")]
        (if (= :unified account-mode) " spot USDC · unified account" " perps USDC · classic account")]]
      [:div {:class ["flex" "flex-col" "gap-1"]}
       (checkbox {:checked? (:include-existing? options)
                  :on (field :include-existing? (not (:include-existing? options)))
                  :title "Count my existing vault deposits"
                  :sub "Targets are set on holdings plus new cash; vaults already above target get no new cash."
                  :data-role "portfolio-optimizer-vault-funding-include-existing"})
       (checkbox {:checked? (and (:include-existing? options) (:withdraw-over? options))
                  :disabled? (not (:include-existing? options))
                  :on (field :withdraw-over? (not (:withdraw-over? options)))
                  :title "Withdraw from vaults above target first"
                  :sub "Only unlocked positions. The USDC freed goes into the vaults below target."
                  :data-role "portfolio-optimizer-vault-funding-withdraw-over"})]]
     [:div {:class ["overflow-x-auto"]}
      (into [:div {:class ["min-w-[760px]"] :data-role "portfolio-optimizer-vault-funding-rows"}
             [:div {:class ["grid" "grid-cols-[minmax(160px,2fr)_70px_100px_100px_minmax(200px,2fr)_70px]" "gap-3" "border-b"
                            "border-base-300" "px-5" "py-2.5" "text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.14em]"
                            "text-trading-muted"]}
              [:span "Vault"] [:span {:class ["text-right"]} "Target"] [:span {:class ["text-right"]} "You hold"]
              [:span {:class ["text-right"]} "Target $"] [:span "Action"] [:span {:class ["text-right"]} "After"]]]
            (map #(plan-row % after-total))
            rows)]
     (into [:div {:class ["flex" "flex-col" "gap-2" "px-5" "pt-4"]}] (notes funding))
     [:footer {:class ["mt-4" "flex" "flex-wrap" "items-center" "justify-between" "gap-4" "border-t" "border-base-300" "px-5" "py-4"]}
      [:div {:class ["flex" "flex-wrap" "gap-6"]}
       [:div [:div {:class ["text-xs" "text-trading-muted"]} "Withdraw"]
        [:div {:class ["num" "text-base" "text-[#e2b545]"] :data-role "portfolio-optimizer-vault-funding-total-withdraw"} (usd withdraw)]]
       [:div [:div {:class ["text-xs" "text-trading-muted"]} "Deposit"]
        [:div {:class ["num" "text-base" "text-ho-accent-bright"] :data-role "portfolio-optimizer-vault-funding-total-deposit"} (usd deposit)]]
       [:div [:div {:class ["text-xs" "text-trading-muted"]} "Stays as cash"]
        [:div {:class ["num" "text-base"]} (usd leftover)]]
       [:div [:div {:class ["text-xs" "text-trading-muted"]} "Off target"]
        [:div {:class ["num" "text-base"]} (str (pct (:drift-before plan)) " → " (pct (:drift-after plan)))]]]
      [:div {:class ["flex" "flex-col" "items-end" "gap-1"]}
       [:button {:type "button"
                 :disabled (boolean block)
                 :class primary-button
                 :data-role "portfolio-optimizer-vault-funding-review"
                 :on {:click (command :review)}}
        (str "Review " (when (pos? n-w) (str n-w (if (= 1 n-w) " withdrawal + " " withdrawals + ")))
             n-d (if (= 1 n-d) " deposit" " deposits"))]
       [:span {:class ["max-w-[360px]" "text-right" "text-xs" "text-trading-muted"]
               :data-role "portfolio-optimizer-vault-funding-block"}
        (or block "")]]]]))

(defn- step-list
  [title tone steps]
  [:section {:class ["px-5" "py-4"]}
   [:h3 {:class ["text-sm" "font-medium" "text-trading-text"]} title]
   (into [:div {:class ["mt-2" "rounded-lg" "border" "border-base-300"]}]
         (map (fn [{:keys [label kind] amount :usd}]
                [:div {:class ["flex" "min-h-10" "items-center" "justify-between" "gap-3" "border-b" "border-base-300/60" "px-3" "text-sm"]}
                 [:span {:class ["truncate"]} label]
                 [:span {:class ["num" tone]} (str (if (= :withdraw kind) "− " "+ ") (usd amount))]]))
         steps)])

(defn- send-label
  [withdraw deposit]
  (cond
    (and (pos? withdraw) (pos? deposit)) (str "Withdraw " (usd withdraw) " and deposit " (usd deposit))
    (pos? withdraw) (str "Withdraw " (usd withdraw) " (kept as cash)")
    :else (str "Deposit " (usd deposit))))

(defn- review-screen
  [{:keys [plan agent-ready?]}]
  (let [steps (:steps plan)
        withdrawals (filter #(= :withdraw (:kind %)) steps)
        deposits (filter #(= :deposit (:kind %)) steps)
        {:keys [withdraw deposit]} (:totals plan)]
    [:div {:class ["flex" "flex-col"] :data-role "portfolio-optimizer-vault-funding-review-screen"}
     [:p {:class ["px-5" "pt-4" "text-sm" "leading-6" "text-trading-muted"]}
      (str (count steps) (if (= 1 (count steps)) " transfer, " " transfers, ")
           (if agent-ready?
             "signed with your trading key — no wallet pop-ups."
             "signed with your trading key once trading is enabled or unlocked (Confirm asks for that first).")
           " They run one at a time; if a withdrawal fails, the deposits wait so you can retry.")]
     (if (seq withdrawals)
       (step-list (if (seq deposits) "1 · Withdraw from vaults above target" "Withdraw from vaults above target")
                  "text-[#e2b545]" withdrawals)
       [:span {:class ["hidden"]}])
     (if (seq deposits)
       (step-list (if (seq withdrawals) "2 · Deposit into vaults below target" "Deposit into vaults below target")
                  "text-ho-accent-bright" deposits)
       [:span {:class ["hidden"]}])
     [:div {:class ["px-5"]}
      (if (seq deposits)
        (note :info "portfolio-optimizer-vault-funding-lockup"
              "New deposits are locked before you can withdraw them again (1 day for most vaults, 4 days for HLP); a new deposit restarts that vault's lock.")
        [:span {:class ["hidden"]}])]
     [:footer {:class ["mt-4" "flex" "flex-wrap" "justify-end" "gap-2" "border-t" "border-base-300" "px-5" "py-4"]}
      [:button {:type "button" :class secondary-button :on {:click (command :back)}} "Edit plan"]
      [:button {:type "button"
                :class primary-button
                :data-role "portfolio-optimizer-vault-funding-confirm"
                :on {:click (command :confirm)}}
       (send-label withdraw deposit)]]]))

(def ^:private status-glyph
  {:queued ["○" "text-trading-muted" "Queued"]
   :sending ["◐" "text-trading-text" "Sending…"]
   :done ["✓" "text-ho-accent-bright" "Done"]
   :failed ["✕" "text-trading-red" "Failed"]
   :unknown ["?" "text-[#e2b545]" "Unconfirmed"]
   :skipped ["–" "text-trading-muted" "Skipped"]})

(defn- progress-row
  [{:keys [row-id label kind status error reason] amount :usd}]
  (let [[glyph tone text] (get status-glyph status (status-glyph :queued))]
    [:div {:class ["grid" "min-h-12" "grid-cols-[24px_minmax(0,1fr)_110px_90px]" "items-center" "gap-3" "border-b"
                   "border-base-300/60" "px-5" "text-sm"]
           :data-role "portfolio-optimizer-vault-funding-progress-row"
           :data-status (name status)
           :data-row-id row-id}
     [:span {:class [tone] :aria-hidden true} glyph]
     [:span {:class ["flex" "min-w-0" "flex-col"]}
      [:span {:class ["truncate"]} label]
      [:span {:class ["text-xs" (if (= :failed status) "text-trading-red" "text-trading-muted")]}
       (cond
         error error
         (= :halted reason) "Not sent: a withdrawal before it failed"
         (= :aborted reason) "Stopped before sending"
         :else (if (= :withdraw kind) "Withdraw" "Deposit"))]]
     [:span {:class ["num" "text-right" (if (= :withdraw kind) "text-[#e2b545]" "text-ho-accent-bright")]}
      (str (if (= :withdraw kind) "− " "+ ") (usd amount))]
     [:span {:class ["text-right" "text-xs" tone]} text]]))

(defn- progress-screen
  [current]
  (let [{:keys [total done failed sending unknown]} (run/summary current)
        status (:status current)
        running? (= :running status)
        retryable (count (run/retryable-rows current))]
    [:div {:class ["flex" "flex-col"] :data-role "portfolio-optimizer-vault-funding-progress"
           :data-run-status (name (or status :idle))}
     [:div {:class ["px-5" "pt-4"]}
      [:p {:class ["text-base" "font-medium" "text-trading-text"]}
       (case status
         :running (str "Sending " (min total (+ done failed sending)) " of " total "…")
         :done (str "All " total (if (= 1 total) " transfer" " transfers") " done")
         :partial (str done " of " total " transfers done")
         (str "Not sent" (when (:error current) (str " — " (:error current)))))]
      [:div {:role "progressbar" :aria-label "Funding progress" :aria-valuemin 0 :aria-valuemax total :aria-valuenow done
             :class ["mt-3" "h-2" "overflow-hidden" "rounded-full" "bg-base-300"]}
       [:div {:class ["h-2" "bg-primary"] :style {:width (str (if (pos? total) (* 100 (/ done total)) 0) "%")}}]]
      [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
       (if running?
         "Keep this tab open until it finishes; you can close the sheet."
         "Your vault holdings refresh automatically.")]
      (if (and (not running?) (pos? unknown))
        [:div {:class ["mt-3"]}
         (note :warn "portfolio-optimizer-vault-funding-unknown"
               (str unknown (if (= 1 unknown) " transfer" " transfers")
                    " could not be confirmed and may have gone through. They are never resent automatically: "
                    "use Plan again to work from your refreshed vault balances."))]
        [:span {:class ["hidden"]}])]
     (into [:div {:class ["mt-3"]}] (map progress-row) (:rows current))
     [:footer {:class ["mt-4" "flex" "flex-wrap" "justify-end" "gap-2" "border-t" "border-base-300" "px-5" "py-4"]}
      (if running?
        [:button {:type "button" :class secondary-button
                  :data-role "portfolio-optimizer-vault-funding-stop"
                  :on {:click (command :stop)}}
         "Stop after this transfer"]
        [:div {:class ["flex" "gap-2"]}
         [:button {:type "button" :class secondary-button
                   :data-role "portfolio-optimizer-vault-funding-replan"
                   :on {:click (command :replan)}}
          "Plan again"]
         [:button {:type "button" :class secondary-button
                   :data-role "portfolio-optimizer-vault-funding-done"
                   :on {:click (command :done)}}
          "Done"]])
      (if (and (not running?) (pos? retryable))
        [:button {:type "button" :class primary-button
                  :data-role "portfolio-optimizer-vault-funding-retry"
                  :on {:click (command :retry)}}
         (str "Retry " retryable (if (= 1 retryable) " transfer" " transfers"))]
        [:span {:class ["hidden"]}])]]))

(defn vault-funding-sheet
  [state]
  (let [opts (model/options state)]
    (when (:open? opts)
      (let [result (get-in state contracts/last-successful-run-result-path)
            funding (model/funding-model state result (.now js/Date))
            current (model/run-state state)
            screen (if (and current (= :progress (:screen opts))) :progress (:screen opts))]
        ;; Above the app's fixed footer (z-170) like the funding modal, with
        ;; bottom room so the footer never covers the sheet's buttons.
        [:div {:class ["fixed" "inset-0" "z-[262]" "flex" "items-start" "justify-center" "overflow-y-auto" "bg-black/60"
                       "px-4" "pt-8" "pb-[calc(5rem+env(safe-area-inset-bottom))]"]
               :data-role "portfolio-optimizer-vault-funding-sheet"}
         [:div {:role "dialog"
                :aria-modal true
                :aria-labelledby "vault-funding-title"
                :tabindex -1
                :replicant/on-render focus-on-render
                :on {:keydown [[:actions/portfolio-optimizer-vault-funding-command [:event/key]]]}
                :class ["w-full" "max-w-[1100px]" "overflow-hidden" "rounded-2xl" "border" "border-base-300" "bg-base-100" "shadow-2xl"]}
          [:header {:class ["flex" "items-start" "justify-between" "gap-3" "border-b" "border-base-300" "px-5" "py-4"]}
           [:div
            [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
             (case screen :review "Review" :progress "Funding vaults" "Plan")]
            [:h2 {:id "vault-funding-title" :class ["mt-1" "text-lg" "font-semibold" "text-trading-text"]}
             "Fund this allocation"]
            [:p {:class ["mt-1" "max-w-2xl" "text-sm" "text-trading-muted"]}
             "Withdraw from vaults above target, then deposit into vaults below it, so your vault holdings match the optimizer's weights."]]
           [:button {:type "button" :aria-label "Close"
                     :class ["inline-flex" "h-10" "w-10" "items-center" "justify-center" "rounded-lg" "text-trading-muted" "hover:bg-base-200"]
                     :data-role "portfolio-optimizer-vault-funding-close"
                     :on {:click (command :close)}}
            [:svg {:viewBox "0 0 16 16" :class ["h-4" "w-4"] :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden true}
             [:path {:d "M4 4l8 8M12 4l-8 8"}]]]]
          (if (:error opts)
            [:div {:class ["px-5" "pt-4"]} (note :error "portfolio-optimizer-vault-funding-error" (:error opts))]
            [:span {:class ["hidden"]}])
          (case screen
            :review (review-screen funding)
            :progress (progress-screen current)
            (plan-screen funding))]]))))
