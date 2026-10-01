(ns hyperopen.views.account-info.tabs.balances.moves
  "A Balances row's move actions (\"To Perps\", \"To Spot\", \"To HyperEVM\")
   in the desktop Transfer column and the mobile card footer, rendered from
   the `:move-targets` the view-model puts on each row
   (`projections.balances-moves`).

   Each action has its own data-role, `balances-move-<row-key>-<to>` on
   desktop and `balances-move-mobile-<row-key>-<to>` on a mobile card, and
   passes it to the Transfer modal as the opener so focus returns to it. A
   disabled action stays focusable (`aria-disabled`) and points
   `aria-describedby` at its reason, which is visible text: on desktop a
   tooltip that shows on hover and while the action has keyboard focus
   (Escape dismisses it, `hyperopen.views.ui.dismissible-tooltip`), on a
   mobile card a line under the card's actions (a phone has no hover)."
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.tabs.balances.shared :as balances-shared]
            [hyperopen.views.ui.dismissible-tooltip :as dismissible-tooltip]))

(defn target-data-role
  [surface {:keys [row-key to]}]
  (str "balances-move-"
       (when (= :mobile surface) "mobile-")
       row-key
       "-"
       (name to)))

(defn target-action
  "The action a target dispatches: the row's legacy Perps <-> Spot transfer,
   unchanged, or the Transfer modal preset to the target's route and asset."
  [row target data-role]
  (if (:legacy? target)
    (balances-shared/balance-row-transfer-action row)
    [:actions/open-funding-transfer-modal
     :event.currentTarget/bounds
     data-role
     (:context target)]))

(def ^:private desktop-enabled-classes
  ["inline-flex"
   "min-h-6"
   "items-center"
   "appearance-none"
   "border-0"
   "bg-transparent"
   "p-0"
   "font-medium"
   "text-ho-accent"
   "transition-colors"
   "whitespace-nowrap"
   "hover:text-ho-accent-bright"
   "focus:outline-none"
   "focus-visible:text-ho-accent-bright"
   "focus-visible:underline"
   "underline-offset-2"])

(def ^:private desktop-disabled-classes
  ["inline-flex"
   "min-h-6"
   "items-center"
   "appearance-none"
   "border-0"
   "bg-transparent"
   "p-0"
   "text-xs"
   "text-trading-text-secondary"
   "cursor-not-allowed"
   "whitespace-nowrap"
   "focus:outline-none"
   "focus-visible:underline"
   "underline-offset-2"])

(def ^:private mobile-enabled-classes
  ["inline-flex"
   "items-center"
   "justify-start"
   "bg-transparent"
   "p-0"
   "text-sm"
   "font-medium"
   "leading-none"
   "transition-colors"
   "whitespace-nowrap"
   "text-trading-green"
   "hover:text-ho-accent-bright"
   "focus:outline-none"
   "focus:ring-0"
   "focus:ring-offset-0"
   "focus-visible:text-ho-accent-bright"
   ;; Colour alone is too weak a focus cue (WCAG 2.4.7, 1.4.11).
   "focus-visible:underline"
   "underline-offset-2"])

(def ^:private mobile-disabled-classes
  ["inline-flex"
   "items-center"
   "justify-start"
   "bg-transparent"
   "p-0"
   "text-sm"
   "font-medium"
   "leading-none"
   "whitespace-nowrap"
   "cursor-not-allowed"
   "text-trading-text-secondary"
   "focus:outline-none"
   "focus-visible:underline"])

(defn- reason-id
  [data-role]
  (str data-role "-reason"))

(def ^:private desktop-reason-classes
  ;; Anchored at the action's right edge and opening leftward, over the
  ;; row's own cells, so it never widens the scrolling rows viewport. The
  ;; gap to the action is the tooltip's own padding, so the pointer can
  ;; cross onto it without ending the hover (WCAG 1.4.13), and `invisible`
  ;; (not just transparent) keeps a closed tooltip from catching the
  ;; pointer over the rows around it.
  ["absolute"
   "right-0"
   "z-[120]"
   "w-72"
   "invisible"
   "opacity-0"
   "transition-opacity"
   "duration-150"
   "group-hover:visible"
   "group-hover:opacity-100"
   "group-focus-within:visible"
   "group-focus-within:opacity-100"
   dismissible-tooltip/hidden-when-dismissed-class])

(def ^:private desktop-reason-bubble-classes
  ["block"
   "whitespace-normal"
   "rounded-md"
   "border"
   "border-ho-border"
   "bg-ho-surface-raised"
   "px-2.5"
   "py-1.5"
   "text-left"
   "text-xs"
   "font-normal"
   "leading-4"
   "text-ho-text"])

(defn- desktop-reason-position-classes
  "Below the action on the table's first rows (above them the rows
   viewport would clip it), above it everywhere else."
  [position]
  (if (= :bottom position)
    ["top-full" "pt-1"]
    ["bottom-full" "pb-1"]))

(defn- target-node
  [row target surface]
  (let [data-role (target-data-role surface target)
        mobile? (= :mobile surface)
        base-attrs {:type "button"
                    :data-role data-role
                    :aria-label (:aria-label target)}]
    (cond
      (and (:disabled? target) mobile?)
      ;; The reason is a visible line under the card's actions
      ;; (`mobile-reasons-node`).
      ^{:key (name (:to target))}
      [:span {:class ["inline-flex" "items-center"]}
       [:button (assoc base-attrs
                       :class mobile-disabled-classes
                       :aria-disabled "true"
                       :aria-describedby (reason-id data-role))
        (:label target)]]

      (:disabled? target)
      ^{:key (name (:to target))}
      [:span (merge {:class ["group" "relative" "inline-flex" "items-center"]}
                    (dismissible-tooltip/group-attrs))
       [:button (assoc base-attrs
                       :class desktop-disabled-classes
                       :aria-disabled "true"
                       :aria-describedby (reason-id data-role))
        (:label target)]
       [:span {:id (reason-id data-role)
               :role "tooltip"
               :data-role (reason-id data-role)
               :class (into desktop-reason-classes
                            (desktop-reason-position-classes
                             (:move-reason-position row)))}
        [:span {:class desktop-reason-bubble-classes} (:reason target)]]]

      :else
      ^{:key (name (:to target))}
      [:span {:class ["inline-flex" "items-center"]}
       [:button (assoc base-attrs
                       :class (if mobile? mobile-enabled-classes desktop-enabled-classes)
                       :on {:click [(target-action row target data-role)]})
        (:label target)]])))

(defn move-nodes
  "One node per move target of `row`, for `surface` (`:desktop` or `:mobile`)."
  [row surface]
  (mapv #(target-node row % surface) (:move-targets row)))

(defn desktop-move-cell
  "The desktop Transfer column of an annotated row: its move actions, or,
   with none, nothing on a HyperEVM row, the unified row's \"Unified\"
   label or a muted \"Transfer\". The actions wrap onto a second line where
   the column is narrow (the /trade account panel at 1280 px) rather than
   widening the column past the panel's edge."
  [row]
  (let [nodes (move-nodes row :desktop)]
    (cond
      (seq nodes)
      (into [:div {:class ["flex" "flex-wrap" "items-center" "gap-x-3" "gap-y-0.5"]
                   :data-role (str "balances-moves-" (:key row))}]
            nodes)

      (= :hyperevm (:location row))
      [:span]

      (:transfer-disabled? row)
      [:span {:class ["text-xs" "text-trading-text-secondary"]} "Unified"]

      :else
      (balances-shared/balance-row-disabled-action "Transfer"))))

(defn mobile-reasons-node
  "Under a mobile card's actions: the reason of each disabled move, as the
   visible line its action's `aria-describedby` names. Always rendered, and
   hidden by class when no move is disabled."
  [row]
  (let [row-id (some-> (:key row) str str/trim)
        disabled (filterv :disabled? (:move-targets row))]
    (into [:div {:class (cond-> ["mt-2" "space-y-1" "text-xs" "leading-4" "text-ho-text-muted"]
                          (empty? disabled) (conj "hidden"))
                 :data-role (str "balances-moves-mobile-reasons-" row-id)}]
          (for [target disabled
                :let [data-role (target-data-role :mobile target)]]
            ^{:key (name (:to target))}
            [:p {:id (reason-id data-role)
                 :data-role (reason-id data-role)}
             (:reason target)]))))
