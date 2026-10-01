(ns hyperopen.views.account-equity.hyperevm-line
  "The Account Equity panel's \"HyperEVM · Not margin\" line: what the shown
   account holds on HyperEVM, with a Move link that opens Transfer preset to
   HyperEVM -> Spot.

   HyperEVM funds cannot margin a position, so the line sits beside the
   Spot and Perps rows but is never part of Account Value or any other
   trading figure; the chip says so in words.

   The /trade view precomputes the model from the full app state
   (`hyperevm-line-model`, exported by the account-surfaces module) and
   passes it in the panel's opts: the panel itself renders from a memoized
   slice that has no identity, wallet or HyperEVM keys. The model holds only
   what the line shows, so a balance poll that changes nothing compares
   equal and the memoized panel does not repaint.

   The slot is always rendered and hidden by class while there is nothing
   to show: no complete read yet, or nothing on HyperEVM that is either
   worth at least a cent (the value rounds to $0.01) or unpriced. Tokens
   with no price still show the line, valued \"Unpriced\", so their Move
   link stays reachable. A conditional child would leave a nil hole in
   Replicant's child walk, and a spectated panel must never read \"$0.00\"
   or \"--\" for funds it has not read."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.views.account-equity.format :refer [display-currency]]
            [hyperopen.views.account-info.projections.hyperevm-funds :as hyperevm-funds]
            [hyperopen.views.ui.focus-return :as focus-return]))

(def move-data-role
  "account-equity-hyperevm-move")

(def ^:private move-reason-id
  "account-equity-hyperevm-move-reason")

(def move-action
  [:actions/open-funding-transfer-modal
   :event.currentTarget/bounds
   move-data-role
   {:from :hyperevm :to :spot}])

(def ^:private hidden-model
  {:visible? false
   :usd nil
   :value-text nil
   :move-action nil
   :blocked-reason nil
   :focus-token nil})

(def ^:private min-shown-usd
  "The smallest value the line shows: it rounds to $0.01. Below it (native
   HYPE dust left after gas) the line would read \"$0.00\" beside a Move
   link with nothing worth moving."
  0.005)

(defn- focus-token
  "The funding modal's focus-return token while the Move link opened it,
   else nil, so the model changes only when focus must come back here."
  [state]
  (when (= move-data-role (get-in state [:funding-ui :modal :focus-return-data-role]))
    (get-in state [:funding-ui :modal :focus-return-token])))

(defn hyperevm-line-model
  "`{:visible? :usd :value-text :move-action :blocked-reason :focus-token}`
   for the account the panel shows.

   - Visible once that account's HyperEVM holdings are read in full
     (`hyperevm-funds` `:ready`) and are worth at least a cent or include a
     token with no price; hidden while loading, after a failed first read,
     while some token was never answered, or when nothing there is worth a
     cent (dust included).
   - `:value-text` is the USD value, or \"Unpriced\" when only unpriced
     tokens (and dust) are held.
   - `:move-action` is nil on read-only views (spectate, trader routes, an
     unavailable subaccount): the link is not rendered there.
   - `:blocked-reason` disables the link with visible text when HyperEVM
     moves are unavailable (a selected subaccount, no wallet)."
  [state]
  (let [{:keys [status usd unpriced-count]} (hyperevm-funds/hyperevm-funds state)
        worth? (and (number? usd) (>= usd min-shown-usd))
        unpriced? (and (number? unpriced-count) (pos? unpriced-count))]
    (if (and (= :ready status) (or worth? unpriced?))
      (let [read-only? (account-context/inspected-account-read-only? state)]
        {:visible? true
         :usd usd
         :value-text (if worth? (display-currency usd) "Unpriced")
         :move-action (when-not read-only? move-action)
         :blocked-reason (when-not read-only?
                           (account-context/hyperevm-moves-blocked-message state))
         :focus-token (focus-token state)})
      hidden-model)))

(def ^:private chip-classes
  ["inline-flex"
   "shrink-0"
   "items-center"
   "rounded"
   "px-1.5"
   "py-0.5"
   "text-xs"
   "font-semibold"
   "leading-none"
   "uppercase"
   "tracking-wide"
   "bg-ho-info/15"
   "text-ho-info"])

(def ^:private move-base-classes
  ["inline-flex"
   "items-center"
   "appearance-none"
   "border-0"
   "bg-transparent"
   "p-0"
   "text-sm"
   "font-medium"
   "leading-none"
   "transition-colors"
   "focus:outline-none"
   "focus-visible:underline"
   "underline-offset-2"])

(defn- move-button
  [{:keys [move-action blocked-reason focus-token]} show?]
  (let [move? (and show? (some? move-action))
        disabled? (and move? (some? blocked-reason))]
    (cond
      (not move?)
      [:button {:type "button"
                :class (conj move-base-classes "hidden")
                :disabled true
                :tabindex "-1"
                :aria-hidden "true"}
       "Move"]

      disabled?
      [:button {:type "button"
                :class (into move-base-classes ["text-trading-text-secondary" "cursor-not-allowed"])
                :data-role move-data-role
                :aria-label "Move funds from HyperEVM to Spot"
                :aria-disabled "true"
                :aria-describedby move-reason-id}
       "Move"]

      :else
      [:button (merge {:type "button"
                       :class (into move-base-classes ["text-ho-accent" "hover:text-ho-accent-bright"])
                       :data-role move-data-role
                       :aria-label "Move funds from HyperEVM to Spot"
                       :on {:click [move-action]}}
                      (focus-return/data-role-return-focus-props move-data-role
                                                                 (when focus-token move-data-role)
                                                                 focus-token))
       "Move"])))

(defn hyperevm-line
  "The line for `model` (`hyperevm-line-model`), or its hidden slot when the
   model is nil or has nothing to show."
  [model]
  (let [{:keys [visible? value-text move-action blocked-reason] :as model*} (or model hidden-model)
        show? (and (true? visible?) (string? value-text))
        disabled? (and show? (some? move-action) (some? blocked-reason))]
    [:div {:class (cond-> ["space-y-1"]
                    (not show?) (conj "hidden"))
           :data-role (when show? "account-equity-hyperevm-line")}
     [:div {:class ["flex" "items-center" "justify-between" "gap-2" "text-sm"]}
      [:span {:class ["flex" "min-w-0" "items-center" "gap-1.5" "text-sm" "text-trading-text-secondary"]}
       [:span "HyperEVM"]
       [:span {:class chip-classes} "Not margin"]]
      [:span {:class ["flex" "items-center" "gap-2.5"]}
       (move-button model* show?)
       [:span {:class ["num" "text-trading-text"]
               :data-role (when show? "account-equity-hyperevm-value")}
        (if show? value-text "")]]]
     ;; The disabled link's reason, as visible text it points at: the panel
     ;; also renders on phones, where a hover tooltip never shows.
     [:p {:id move-reason-id
          :class (cond-> ["text-xs" "leading-4" "text-ho-text-muted"]
                   (not disabled?) (conj "hidden"))
          :data-role (when disabled? move-reason-id)}
      (if disabled? blocked-reason "")]]))
