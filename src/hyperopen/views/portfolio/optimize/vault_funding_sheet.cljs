(ns hyperopen.views.portfolio.optimize.vault-funding-sheet
  "Fund-this-allocation sheet: plan (split, amount, rules, per-vault actions
  and lock-ups, sent straight from its button), then progress. Rendered over any optimizer route; returns nil while
  closed."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.vault-funding.model :as model]
            [hyperopen.portfolio.optimizer.application.vault-funding.run :as run]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.routes :as portfolio-routes]
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

(defn- date-time
  [ms]
  (when (number? ms)
    (.toLocaleString (js/Date. ms) "en-US" #js {:month "short" :day "numeric" :hour "numeric" :minute "2-digit"})))

(defn- days
  [n]
  (str n (if (= 1 n) " day" " days")))

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

(defn- lock-cell
  "When this vault's money can next be withdrawn, and why."
  [{:keys [status deposit held after lock-days locked? unlock-ms]}]
  (cond
    (= :withdraw status) ["Free now" "Withdrawn first" :free]
    (pos? (or deposit 0)) [(date-time unlock-ms)
                           (str (days (or lock-days 1)) " lock-up"
                                (when (pos? (or held 0)) (str " · restarts on all " (usd after))))
                           (if (or (pos? (or held 0)) (> (or lock-days 1) 1)) :warn :locked)]
    (and locked? (number? unlock-ms)) [(date unlock-ms) "Already locked" :warn]
    (pos? (or held 0)) ["Free now" "Unchanged" :free]
    :else ["—" "" :free]))

(def ^:private plan-grid
  "grid-cols-[minmax(160px,2fr)_64px_64px_100px_minmax(180px,1.8fr)_minmax(170px,1.5fr)]")

(defn- plan-row
  [{:keys [address label weight held after] :as row} after-total]
  (let [[action sub] (action-cell row)
        [lock lock-sub tone] (lock-cell row)
        share (if (pos? after-total) (/ after after-total) 0)]
    [:div {:class ["grid" "min-h-14" plan-grid "items-center" "gap-3" "border-b" "border-base-300/60" "px-5" "text-sm"]
           :data-role "portfolio-optimizer-vault-funding-row"
           :data-address address}
     [:span {:class ["truncate" "text-trading-text"]} label]
     [:span {:class ["num" "text-right"]} (pct weight)]
     [:span {:class (into ["num" "text-right"] (if (> (js/Math.abs (- share weight)) 0.01) ["text-[#e2b545]"] ["text-trading-text"]))}
      (pct share)]
     [:span {:class ["num" "text-right" "text-trading-muted"]} (if (pos? (or held 0)) (usd held) "—")]
     [:span {:class ["flex" "flex-col" "gap-0.5"]}
      action
      [:span {:class ["text-xs" "text-trading-muted"]} sub]]
     [:span {:class ["flex" "flex-col" "gap-0.5"] :data-role "portfolio-optimizer-vault-funding-lock"}
      [:span {:class ["num" (case tone :free "text-trading-muted" :warn "text-[#efe1b5]" "text-trading-text")]} lock]
      [:span {:class ["text-xs" (if (= tone :warn) "text-[#efe1b5]/80" "text-trading-muted")]} lock-sub]]]))

(defn- split-card
  [{:keys [split selected? title sub summary]}]
  (let [{:keys [weights withdrawals deposits drift-after]} summary]
    [:button {:type "button"
              :role "radio"
              :aria-checked (boolean selected?)
              :class (into ["flex" "min-h-11" "flex-col" "gap-1.5" "rounded-xl" "border" "px-4" "py-3" "text-left"]
                           (if selected?
                             ["border-ho-accent-bright/70" "bg-ho-accent-bright/10"]
                             ["border-base-300" "hover:bg-base-200/40"]))
              :data-role (str "portfolio-optimizer-vault-funding-split-" (name split))
              :on {:click (field :split split)}}
     [:span {:class ["flex" "items-center" "gap-2.5" "text-sm" "font-medium" "text-trading-text"]}
      [:span {:class (into ["h-3.5" "w-3.5" "shrink-0" "rounded-full" "border"]
                           (if selected? ["border-[4px]" "border-ho-accent-bright"] ["border-trading-muted"]))}]
      title]
     [:span {:class ["pl-6" "text-xs" "leading-5" "text-trading-muted"]} sub]
     (into [:span {:class ["ml-6" "flex" "h-1.5" "gap-0.5" "overflow-hidden" "rounded-full"] :aria-hidden true}]
           (map-indexed (fn [i w]
                          [:span {:class ["bg-ho-accent-bright"]
                                  :style {:flex-grow w :flex-basis 0 :opacity (- 1 (* 0.07 (mod i 8)))}}]))
           (filter pos? weights))
     [:span {:class ["num" "pl-6" "text-xs" "text-trading-muted"]}
      (str (when (pos? (or withdrawals 0)) (str withdrawals (if (= 1 withdrawals) " withdrawal · " " withdrawals · ")))
           deposits (if (= 1 deposits) " deposit · " " deposits · ")
           (pct drift-after) " off target after")]]))

(defn- tear-sheet-link
  "Compare both splits on the hypothetical tear sheet (it charts Equal weight
  against the target); hidden when the sheet is already open over it."
  [state]
  (let [{:keys [kind view scenario-id]} (portfolio-routes/parse-portfolio-route (get-in state [:router :path]))]
    (if (and (= :optimize-scenario kind) scenario-id (not= :tear-sheet view))
      [:button {:type "button"
                :class ["self-start" "min-h-8" "text-sm" "text-ho-accent-bright" "hover:underline"]
                :data-role "portfolio-optimizer-vault-funding-compare"
                :on {:click [[:actions/portfolio-optimizer-vault-funding-command :close]
                             [:actions/navigate (portfolio-routes/portfolio-optimize-tear-sheet-path scenario-id)]]}}
       "Compare both on the hypothetical tear sheet ›"]
      [:span {:class ["hidden"]}])))

(defn- split-picker
  [{:keys [options splits]} state]
  (let [n (get-in splits [:equal :vaults] 0)]
    [:section {:class ["flex" "flex-col" "gap-2" "border-b" "border-base-300" "px-5" "py-4"]}
     [:span {:id "vault-funding-split" :class ["text-sm" "text-trading-muted"]} "Split the money by"]
     [:div {:role "radiogroup" :aria-labelledby "vault-funding-split" :class ["grid" "gap-3" "md:grid-cols-2"]}
      (split-card {:split :target
                   :selected? (= :target (:split options))
                   :title "Optimizer target"
                   :sub "The weights from this run."
                   :summary (:target splits)})
      (split-card {:split :equal
                   :selected? (= :equal (:split options))
                   :title "Equal weight"
                   :sub (str "The same share in each of the " n " vaults the run picked ("
                             (pct (if (pos? n) (/ 1 n) 0)) " each).")
                   :summary (:equal splits)})]
     (tear-sheet-link state)]))

(defn- lock-note
  "How much unlocks when, and which top-ups restart a lock."
  [rows]
  (let [deposits (filter #(pos? (:deposit %)) rows)
        by-day (->> deposits
                    (group-by #(date (:unlock-ms %)))
                    (sort-by (fn [[_ rs]] (:unlock-ms (first rs))))
                    (map (fn [[d rs]] (str (usd (reduce + 0 (map :deposit rs))) " until " d))))
        top-ups (filter #(pos? (:held %)) deposits)]
    (if (seq deposits)
      (note :info "portfolio-optimizer-vault-funding-lockup"
            (str "Hyperliquid locks each deposit before it can be withdrawn: " (str/join ", " by-day) "."
                 (when (seq top-ups)
                   (str " Topping up " (str/join " and " (map :label top-ups))
                        " restarts the lock on what you already hold there."))))
      [:span {:class ["hidden"]}])))

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

(defn- send-label
  [withdraw deposit]
  (cond
    (and (pos? withdraw) (pos? deposit)) (str "Withdraw " (usd withdraw) " and deposit " (usd deposit))
    (pos? withdraw) (str "Withdraw " (usd withdraw) " (kept as cash)")
    (pos? deposit) (str "Deposit " (usd deposit))
    :else "Nothing to send"))

(defn- plan-screen
  [{:keys [plan options available account-mode block agent-ready?] :as funding} state]
  (let [rows (:rows plan)
        after-total (reduce + 0 (map :after rows))
        {:keys [withdraw deposit leftover]} (:totals plan)
        n-steps (count (:steps plan))]
    [:div {:class ["flex" "flex-col"]}
     (split-picker funding state)
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
      (into [:div {:class ["min-w-[900px]"] :data-role "portfolio-optimizer-vault-funding-rows"}
             [:div {:class ["grid" plan-grid "gap-3" "border-b"
                            "border-base-300" "px-5" "py-2.5" "text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.14em]"
                            "text-trading-muted"]}
              [:span "Vault"] [:span {:class ["text-right"]} "Target"] [:span {:class ["text-right"]} "After"]
              [:span {:class ["text-right"]} "You hold"] [:span "Action"] [:span "Locked until"]]]
            (map #(plan-row % after-total))
            rows)]
     (into [:div {:class ["flex" "flex-col" "gap-2" "px-5" "pt-4"]}] (conj (notes funding) (lock-note rows)))
     [:footer {:class ["mt-4" "flex" "flex-wrap" "items-center" "justify-between" "gap-4" "border-t" "border-base-300" "px-5" "py-4"]}
      [:div {:class ["flex" "flex-wrap" "gap-6"]}
       [:div [:div {:class ["text-xs" "text-trading-muted"]} "Withdraw"]
        [:div {:class ["num" "text-base" "text-[#e2b545]"] :data-role "portfolio-optimizer-vault-funding-total-withdraw"} (usd withdraw)]]
       [:div [:div {:class ["text-xs" "text-trading-muted"]} "Deposit"]
        [:div {:class ["num" "text-base" "text-ho-accent-bright"] :data-role "portfolio-optimizer-vault-funding-total-deposit"} (usd deposit)]]
       [:div [:div {:class ["text-xs" "text-trading-muted"]} "Stays as cash"]
        [:div {:class ["num" "text-base"]} (usd leftover)]]
       [:div [:div {:class ["text-xs" "text-trading-muted"]} "Off target"]
        [:div {:class ["num" "text-base"]} (str (pct (:drift-before plan)) " → " (pct (:drift-after plan)))]]
       [:div [:div {:class ["text-xs" "text-trading-muted"]} "All withdrawable by"]
        [:div {:class ["num" "text-base"] :data-role "portfolio-optimizer-vault-funding-free-by"}
         (or (date (:free-by-ms plan)) "Now")]]]
      [:div {:class ["flex" "flex-col" "items-end" "gap-1"]}
       [:button {:type "button"
                 :disabled (boolean block)
                 :class primary-button
                 :data-role "portfolio-optimizer-vault-funding-confirm"
                 :on {:click (command :confirm)}}
        (send-label withdraw deposit)]
       [:span {:class ["max-w-[420px]" "text-right" "text-xs" "text-trading-muted"]
               :data-role "portfolio-optimizer-vault-funding-block"}
        (or block
            (str n-steps (if (= 1 n-steps) " transfer" " transfers")
                 ", withdrawals first, signed with your trading key"
                 (if agent-ready? "." " once trading is enabled.")
                 " Sends right away; no further confirmation."))]]]]))

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
             (if (= :progress screen) "Funding vaults" "Plan")]
            [:h2 {:id "vault-funding-title" :class ["mt-1" "text-lg" "font-semibold" "text-trading-text"]}
             "Fund this allocation"]
            [:p {:class ["mt-1" "max-w-2xl" "text-sm" "text-trading-muted"]}
             "Withdraw from vaults above target, then deposit into vaults below it, so your vault holdings match the split you choose."]]
           [:button {:type "button" :aria-label "Close"
                     :class ["inline-flex" "h-10" "w-10" "items-center" "justify-center" "rounded-lg" "text-trading-muted" "hover:bg-base-200"]
                     :data-role "portfolio-optimizer-vault-funding-close"
                     :on {:click (command :close)}}
            [:svg {:viewBox "0 0 16 16" :class ["h-4" "w-4"] :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden true}
             [:path {:d "M4 4l8 8M12 4l-8 8"}]]]]
          (if (:error opts)
            [:div {:class ["px-5" "pt-4"]} (note :error "portfolio-optimizer-vault-funding-error" (:error opts))]
            [:span {:class ["hidden"]}])
          (if (= :progress screen)
            (progress-screen current)
            (plan-screen funding state))]]))))
