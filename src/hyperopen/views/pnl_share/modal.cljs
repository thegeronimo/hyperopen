(ns hyperopen.views.pnl-share.modal
  "The share modal: a live card on the left, the controls on the right.

   Reproduces the share-modal direction from the design project, minus the
   aspect-ratio picker -- see the ExecPlan's What This Plan Does Not Do."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.pnl-share.actions :as pnl-share-actions]
            [hyperopen.views.account-info.positions-vm :as positions-vm]
            [hyperopen.views.pnl-share.card-data :as card-data]
            [hyperopen.views.pnl-share.card-view :as card-view]
            [hyperopen.views.ui.dialog-focus :as dialog-focus]))

(def ^:private title-id
  "pnl-share-modal-title")

(def ^:private toggle-controls
  [{:key :show-prices? :label "Entry / mark price"}
   {:key :show-size? :label "Position size"}
   {:key :show-funding? :label "Funding paid"}
   {:key :show-handle? :label "My wallet"}])

(defn- environment
  []
  {:now-ms (if (exists? js/Date) (js/Date.now) 0)
   :site-origin (some-> js/globalThis .-location .-origin)})

(defn- referral-code
  [state]
  (let [referrer-state (or (get-in state [:referrals :raw :referrerState])
                           (get-in state [:referrals :raw :referrer-state]))
        data (or (:data referrer-state) (get referrer-state "data"))]
    (or (:code data) (get data "code"))))

(defn- resolved-icon-data-uri
  "The stored icon, but only when it was resolved for the coin now on screen.
   Without the key check, opening SOL after BTC would paint BTC's icon until the
   new fetch landed."
  [state position]
  (let [stored (get-in state [:pnl-share :icon])]
    (when (= (:key stored) (pnl-share-actions/position-icon-key position))
      (:data-uri stored))))

(defn card-for-state
  "The card data map for the currently open position, or nil when closed."
  [state {:keys [now-ms site-origin]}]
  (when-let [position (get-in state [:pnl-share :position])]
    (card-data/position-card-data
     (positions-vm/position-row-vm position)
     {:owner-address (account-context/effective-account-address state)
      :referral-code (referral-code state)
      :site-origin site-origin
      :now-ms now-ms
      :fills (get-in state [:orders :fills])
      :template (get-in state [:pnl-share :template])
      :options (get-in state [:pnl-share :options])
      :icon-data-uri (resolved-icon-data-uri state position)})))

(defn- section-label
  [text]
  [:div {:class ["text-xs" "uppercase" "tracking-[0.14em]" "text-ho-text-dim"]} text])

(defn- template-picker
  [active]
  [:div {:class ["flex" "flex-col" "gap-2"]}
   (section-label "Card")
   (into [:div {:class ["grid" "grid-cols-2" "gap-2"]}]
         (map (fn [{:keys [id label]}]
                (let [selected? (= id active)]
                  [:button {:type "button"
                            :data-role "pnl-share-template-option"
                            :data-template (name id)
                            :aria-pressed (if selected? "true" "false")
                            :class (into ["rounded-lg" "border" "px-3" "py-2" "text-xs"
                                          "transition-colors" "focus:outline-none"]
                                         (if selected?
                                           ["border-ho-accent" "bg-ho-accent-soft" "text-ho-accent-bright"]
                                           ["border-ho-border" "bg-ho-bg-deep" "text-ho-text-secondary"
                                            "hover:border-ho-border-accent"]))
                            :on {:click [[:actions/set-pnl-share-option :template id]]}}
                   label])))
         card-data/templates)])

(defn- field-toggles
  [options]
  [:div {:class ["flex" "flex-col" "gap-2"]}
   (section-label "Show on card")
   (into [:div {:class ["flex" "flex-col" "gap-1"]}]
         (map (fn [{:keys [key label]}]
                ;; Merged against the defaults rather than treated as on-unless-
                ;; false: size and wallet default OFF, so a missing key must read
                ;; as off, not on.
                (let [on? (true? (get (merge (pnl-share-actions/default-options)
                                             (or options {}))
                                      key))]
                  [:button {:type "button"
                            :data-role "pnl-share-field-toggle"
                            :data-field (name key)
                            :aria-pressed (if on? "true" "false")
                            :class ["flex" "items-center" "gap-2" "bg-transparent" "px-0"
                                    "py-1" "text-left" "focus:outline-none"]
                            :on {:click [[:actions/set-pnl-share-option key (not on?)]]}}
                   [:span {:class (into ["flex" "h-4" "w-4" "flex-none" "items-center"
                                         "justify-center" "rounded" "border" "text-xs"]
                                        (if on?
                                          ["border-ho-accent" "bg-ho-accent" "text-ho-bg-deep"]
                                          ["border-ho-border" "text-transparent"]))}
                    (if on? "✓" "")]
                   [:span {:class ["text-xs" "text-ho-text"]} label]])))
         toggle-controls)])

(defn- caption-field
  [caption]
  [:div {:class ["flex" "flex-col" "gap-2"]}
   [:div {:class ["flex" "items-baseline" "justify-between"]}
    (section-label "Post text")
    [:div {:class ["text-xs" "text-ho-text-dim" "num"]
           :data-role "pnl-share-caption-count"}
     (str (count caption) "/" pnl-share-actions/caption-limit)]]
   [:p {:class ["text-xs" "leading-relaxed" "text-ho-text-dim"]
        :data-role "pnl-share-caption-hint"}
    "Goes in the X post alongside the image. It is not drawn on the card."]
   [:textarea {:rows 3
               :data-role "pnl-share-caption"
               :aria-label "Caption to post with the card"
               :value caption
               :class ["w-full" "resize-none" "rounded-lg" "border" "border-ho-border"
                       "bg-ho-bg-deep" "px-3" "py-2" "text-xs" "leading-relaxed"
                       "text-ho-text" "focus:border-ho-accent" "focus:outline-none"]
               :on {:input [[:actions/set-pnl-share-option :caption [:event.target/value]]]}}]])

(defn post-url
  "An X compose URL. Opening it only pre-fills a post; nothing is published
   until the person presses Post in X's own window."
  [caption join-link]
  (str "https://x.com/intent/post?text="
       (js/encodeURIComponent (or caption ""))
       "&url="
       (js/encodeURIComponent (or join-link ""))))

(defn- actions-block
  [{:keys [caption join-link]}]
  [:div {:class ["mt-auto" "flex" "flex-col" "gap-2"]}
   [:div {:class ["grid" "grid-cols-2" "gap-2"]}
    [:button {:type "button"
              :data-role "pnl-share-save-png"
              :class ["rounded-lg" "border" "border-ho-border" "bg-ho-bg-deep" "px-3"
                      "py-2" "text-xs" "text-ho-text" "transition-colors"
                      "hover:border-ho-border-accent" "focus:outline-none"]
              :on {:click [[:actions/save-pnl-share-card-image]]}}
     "Save PNG"]
    [:button {:type "button"
              :data-role "pnl-share-copy-link"
              :class ["rounded-lg" "border" "border-ho-border" "bg-ho-bg-deep" "px-3"
                      "py-2" "text-xs" "text-ho-text" "transition-colors"
                      "hover:border-ho-border-accent" "focus:outline-none"]
              :on {:click [[:actions/copy-pnl-share-link]]}}
     "Copy link"]]
   [:a {:href (post-url caption join-link)
        :target "_blank"
        :rel "noopener noreferrer"
        :data-role "pnl-share-post-x"
        :class ["rounded-lg" "bg-ho-accent" "px-3" "py-3" "text-center" "text-xs"
                "font-medium" "text-ho-bg-deep" "transition-[filter]"
                "hover:brightness-110" "focus:outline-none"]}
    "Post it on X"]
   [:p {:class ["text-center" "text-xs" "leading-relaxed" "text-ho-text-dim"]}
    "The image is built in your browser. Nothing about your wallet leaves this page."]])

(defn- preview
  "The card sits directly on the panel. An enclosing well would have to be taller
   than a 16:9 card to hold the controls' height, and the dead bands above and
   below read as broken rather than as framing."
  [card]
  [:div {:class ["w-full"] :data-role "pnl-share-preview"}
   (-> (card-view/card-svg card)
       (update 1 assoc :style {:width "100%" :height "auto" :display "block"}))])

(defn- panel
  [state card]
  (let [caption (or (get-in state [:pnl-share :caption]) "")
        options (get-in state [:pnl-share :options])
        template (card-data/normalize-template (get-in state [:pnl-share :template]))]
    [:div {:class ["relative" "z-[301]" "flex" "max-h-[92vh]" "w-full" "max-w-5xl"
                   "flex-col" "gap-4" "overflow-y-auto" "rounded-2xl" "border"
                   "border-ho-border-accent" "bg-ho-bg" "p-4" "shadow-2xl"
                   "pointer-events-auto" "lg:grid" "lg:grid-cols-[minmax(0,1fr)_320px]"]
           :role "dialog"
           :aria-modal true
           :aria-labelledby title-id
           :tab-index 0
           :data-role "pnl-share-modal"
           :data-parity-id "pnl-share-modal"
           :replicant/on-render (dialog-focus/dialog-focus-on-render
                                 {:restore-selector "[data-role='pnl-share-trigger']"})
           :on {:keydown [[:actions/handle-pnl-share-card-keydown [:event/key]]]}}
     [:div {:class ["flex" "min-w-0" "flex-col" "justify-center" "gap-3"]}
      [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
       [:h2 {:id title-id :class ["text-sm" "font-semibold" "text-ho-text-hi"]}
        (str "Share " (:coin-label card) " " (or (:leverage-label card) ""))]
       [:button {:type "button"
                 :data-role "pnl-share-close"
                 :aria-label "Close share card"
                 :class ["rounded" "px-2" "py-1" "text-xs" "text-ho-text-secondary"
                         "hover:text-ho-text-hi" "focus:outline-none"]
                 :on {:click [[:actions/close-pnl-share-card]]}}
        "Close"]]
      (preview card)]
     [:div {:class ["flex" "flex-col" "gap-4" "rounded-xl" "border" "border-ho-border"
                    "bg-ho-bg-deep" "p-4"]}
      (template-picker template)
      (field-toggles options)
      (caption-field caption)
      (when-not (:has-referral-code? card)
        [:p {:class ["text-xs" "leading-relaxed" "text-ho-text-dim"]
             :data-role "pnl-share-no-referral-note"}
         "You have no referral code yet, so the card links to the site itself."])
      (actions-block {:caption (if (str/blank? caption)
                                 (card-data/default-caption card)
                                 caption)
                      :join-link (:join-link card)})]]))

(defn pnl-share-modal-view
  ([state] (pnl-share-modal-view state (environment)))
  ([state env]
   (when (pnl-share-actions/open? state)
     (when-let [card (card-for-state state env)]
       [:div {:class ["fixed" "inset-0" "z-[300]" "flex" "items-center" "justify-center" "p-4"]
              :data-role "pnl-share-layer"}
        [:button {:type "button"
                  :class ["absolute" "inset-0" "bg-black/70"]
                  :aria-label "Close share card"
                  :on {:click [[:actions/close-pnl-share-card]]}}]
        (panel state card)]))))
