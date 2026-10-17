(ns hyperopen.views.account-info.tabs.balances.location-filter
  "The Balances tab's HyperCore / HyperEVM parts: the All / HyperCore /
   HyperEVM filter in the tab header, the place chips on rows, the HyperEVM
   empty states and the note under the table."
  (:require [hyperopen.funding.domain.evm-transfer-preview :as evm-transfer-preview]
            [hyperopen.views.account-info.projections.balances-hyperevm :as balances-hyperevm]))

(def ^:private filter-labels
  ;; [long label, short label]. The long one shows from 640 px until the
  ;; header goes one row at lg, and again from 2xl; the short one below
  ;; 640 px and from lg to 2xl, where the control sits beside the tab strip
  ;; and every pixel it takes is a tab hidden behind the strip's sideways
  ;; scroll. The accessible name is always the long label (it contains the
  ;; short one, so a voice user can say either).
  {:all ["All" "All"]
   :hypercore ["HyperCore" "Core"]
   :hyperevm ["HyperEVM" "EVM"]})

(def ^:private group-classes
  ["inline-flex" "shrink-0" "items-center" "gap-0.5" "rounded-lg" "bg-ho-surface" "p-0.5"])

(def ^:private option-base-classes
  ["inline-flex" "h-6" "items-center" "rounded-md" "px-2.5" "text-xs" "font-medium"
   "whitespace-nowrap" "transition-colors" "focus:outline-none" "focus-visible:ring-2"
   "focus-visible:ring-ho-accent/60"])

(defn location-filter-control
  "Three pressed-state buttons filtering the Balances rows by where they sit."
  [location-filter]
  (let [selected (balances-hyperevm/normalize-location-filter location-filter)]
    (into [:div {:role "group"
                 :aria-label "Filter balances by location"
                 :class group-classes
                 :data-role "balances-location-filter"}]
          (for [id balances-hyperevm/location-filters
                :let [[long-label short-label] (get filter-labels id)
                      pressed? (= id selected)]]
            ^{:key (name id)}
            [:button {:type "button"
                      ;; A string: Replicant drops a false attribute, which
                      ;; would leave the unpressed options without their
                      ;; toggle state.
                      :aria-pressed (if pressed? "true" "false")
                      :aria-label long-label
                      :data-role (str "balances-location-filter-" (name id))
                      :class (into option-base-classes
                                   (if pressed?
                                     ["bg-ho-surface-raised" "text-ho-text-hi"]
                                     ["bg-transparent" "text-trading-text-secondary"
                                      "hover:text-trading-text"]))
                      :on {:click [[:actions/set-balances-location-filter id]]}}
             [:span {:class ["hidden" "sm:inline" "lg:hidden" "2xl:inline"]} long-label]
             [:span {:class ["sm:hidden" "lg:inline" "2xl:hidden"]} short-label]]))))

(defn chip-location
  "The place chip a row shows in its coin cell, or nil. A HyperEVM row
   always shows its chip. HyperCore rows show theirs only when `core-chips?`
   (the filter is All and the account holds something on HyperEVM), so the
   Perps, Spot and HyperEVM USDC rows (each labelled \"USDC\") stay apart,
   except a unified account's pooled USDC row, which is both."
  [row core-chips?]
  (let [location (balances-hyperevm/row-location row)]
    (cond
      (= :hyperevm location) :hyperevm
      (not core-chips?) nil
      (:transfer-disabled? row) nil
      :else location)))

(def hidden-small-message
  "All HyperEVM balances are hidden by Hide Small Balances.")

(def no-match-message
  "No HyperEVM balances match your search.")

(defn status-message
  "What the note says about a first HyperEVM read that is still pending
   (`:loading`, or `:stalled` once the poller has retried it) or failed
   (`:unavailable`), or a read that left some token never answered
   (`:partial`), else nil."
  [hyperevm-status]
  (case hyperevm-status
    :loading (:checking-balances evm-transfer-preview/messages)
    :stalled (:checking-balances-stalled evm-transfer-preview/messages)
    :unavailable (:balances-unavailable evm-transfer-preview/messages)
    :partial (:balances-partial evm-transfer-preview/messages)
    nil))

(defn empty-message
  "The empty state's headline when the HyperEVM filter shows nothing: the
   read's status (`:loading`, `:unavailable`, `:partial`, else nothing
   held), or
   `:hidden-small` / `:no-match` when Hide Small Balances or the search
   hid every HyperEVM row."
  [cause]
  (case cause
    :hidden-small hidden-small-message
    :no-match no-match-message
    (or (status-message cause) "No HyperEVM balances.")))

(defn checking-status?
  "Whether `hyperevm-status` is a first read still pending, which the note
   animates so it reads as working rather than stuck."
  [hyperevm-status]
  (contains? #{:loading :stalled} hyperevm-status))

(defn- note-line
  [data-role text checking?]
  [:span {:class (cond-> ["mr-1"]
                   (nil? text) (conj "hidden")
                   checking? (conj "ho-checking-shimmer"))
          :data-role data-role}
   (or text "")])

(def ^:private retry-button-classes
  ["mr-1" "bg-transparent" "p-0" "font-medium" "text-ho-accent-bright"
   "hover:underline" "focus:outline-none" "focus-visible:underline"
   "underline-offset-2"])

(defn- retry-button
  [data-role retry?]
  [:button {:type "button"
            :class (cond-> retry-button-classes
                     (not retry?) (conj "hidden"))
            :data-role data-role
            :on {:click [[:actions/retry-hyperevm-balances]]}}
   "Retry"])

(defn hyperevm-note
  "The note under the table: `surface` `:desktop` (under the rows, 1024 px
   and up), `:mobile` (above the cards, below 1024 px) or `:any`. Always
   rendered, hidden by class unless `visible?` and it has something to say:
   `:status-line` says a first HyperEVM read is pending or failed
   (`:checking?` animates it while pending, and `:retry?` offers Retry once
   it has stalled); `:blocked-message` says why HyperEVM moves are
   unavailable for this account, if they are."
  [surface visible? {:keys [blocked-message status-line checking? retry?]}]
  (let [mobile? (= :mobile surface)
        status-line (when visible? status-line)
        checking? (and (some? status-line) (true? checking?))
        retry? (and (some? status-line) (true? retry?))
        blocked-message (when visible? blocked-message)
        shown? (boolean (or status-line blocked-message))]
    [:div {:class (into ["shrink-0" "border-ho-border-accent-muted" "px-4" "text-xs"
                         "leading-4" "text-ho-text-muted"]
                        (cond
                          (not shown?) ["hidden"]
                          mobile? ["lg:hidden" "border-b" "py-2"]
                          (= :desktop surface) ["hidden" "lg:block" "border-t" "py-2.5"]
                          :else ["border-t" "py-2.5"]))
           :data-role (if mobile? "balances-hyperevm-note-mobile" "balances-hyperevm-note")}
     (note-line (if mobile? "balances-hyperevm-status-mobile" "balances-hyperevm-status")
                status-line
                checking?)
     (retry-button (if mobile? "balances-hyperevm-retry-mobile" "balances-hyperevm-retry")
                   retry?)
     [:span {:class (into ["text-ho-warn"]
                          (when-not blocked-message ["hidden"]))
             :data-role (if mobile?
                          "balances-hyperevm-moves-blocked-mobile"
                          "balances-hyperevm-moves-blocked")}
      (or blocked-message "")]]))
