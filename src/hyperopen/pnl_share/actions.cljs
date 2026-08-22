(ns hyperopen.pnl-share.actions
  "Pure actions for the PnL share card modal.

   Card state lives in its own top-level :pnl-share bucket rather than under
   :positions-ui. :positions-ui is inside account-info-view-base-state-keys in
   hyperopen.views.trade-view, so every keystroke in the caption field would
   bust the account panel's memo and re-render the whole Positions table. A
   bucket absent from every select-keys vector cannot do that, and the modal is
   mounted from app-view, which is not memoized."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.pnl-share.naming :as naming]
            [hyperopen.views.asset-icon :as asset-icon]))

(def surface-id
  :pnl-share-modal)

(def caption-limit
  "Matches the limit Hyperliquid's own share modal enforces."
  280)

(def valid-templates
  #{:neon-arrow :number-hero})

(def default-template
  :neon-arrow)

(def toggle-keys
  #{:show-prices? :show-funding? :show-handle?})

(defn default-options
  []
  {:show-prices? true
   :show-funding? true
   :show-handle? true})

(defn default-pnl-share-state
  []
  {:open? false
   :position nil
   :template default-template
   :options (default-options)
   :caption ""})

(defn open?
  [state]
  (true? (get-in state [:pnl-share :open?])))

(defn- normalize-template
  [template]
  (if (contains? valid-templates template)
    template
    default-template))

(defn- sticky-template
  [state]
  (normalize-template (get-in state [:pnl-share :template])))

(defn- sticky-options
  [state]
  (merge (default-options)
         (select-keys (or (get-in state [:pnl-share :options]) {}) toggle-keys)))

(defn position-icon-key
  "The coin-icon key for a position row, using the same derivation the tables
   and the frontier markers use so the card shows the icon the rest of the app
   shows -- venue prefixes, spot suffixes and the alias table included."
  [position-data]
  (let [position (or (:position position-data) {})
        coin (:coin position)
        base (naming/base-symbol coin)]
    (asset-icon/market-icon-key {:coin coin
                                 :base base
                                 :dex (:dex position-data)})))

(defn- referral-code
  [state]
  (let [referrer-state (or (get-in state [:referrals :raw :referrerState])
                           (get-in state [:referrals :raw :referrer-state]))
        data (or (:data referrer-state) (get referrer-state "data"))]
    (or (:code data) (get data "code"))))

(defn open-pnl-share-card
  "Opens the modal for one position row.

   The lazy surface module is requested first, exactly as the spectate modal
   does. When the wallet's referral code is not in state -- it is only loaded on
   the referrals route -- the fetch is kicked off here so the card can upgrade
   its bare site link into a /join/<code> link when the response lands."
  [state position-data]
  (let [address (account-context/effective-account-address state)
        icon-key (position-icon-key position-data)
        need-referral? (and (seq address)
                            (nil? (referral-code state)))]
    (cond-> [[:effects/load-surface-module surface-id]
             [:effects/save-many [[[:pnl-share :open?] true]
                                  [[:pnl-share :position] position-data]
                                  [[:pnl-share :template] (sticky-template state)]
                                  [[:pnl-share :options] (sticky-options state)]
                                  [[:pnl-share :caption] ""]]]]
      icon-key (conj [:effects/resolve-pnl-share-icon icon-key])
      need-referral? (conj [:effects/api-fetch-referral address]))))

(defn set-pnl-share-icon
  "Stores a resolved icon against the key it was resolved for, so a card opened
   on a different coin never paints the previous coin's icon."
  [_state icon-key data-uri]
  [[:effects/save [:pnl-share :icon] {:key icon-key :data-uri data-uri}]])

(defn close-pnl-share-card
  [_state]
  [[:effects/save-many [[[:pnl-share :open?] false]
                        [[:pnl-share :position] nil]]]])

(defn set-pnl-share-option
  "One contract for every control in the right rail. The alternative was four
   near-identical actions, each needing its own spec and binding row."
  [_state option-key value]
  (cond
    (= :template option-key)
    [[:effects/save [:pnl-share :template] (normalize-template value)]]

    (= :caption option-key)
    (let [text (str value)
          clipped (if (< caption-limit (count text))
                    (subs text 0 caption-limit)
                    text)]
      [[:effects/save [:pnl-share :caption] clipped]])

    (contains? toggle-keys option-key)
    [[:effects/save [:pnl-share :options option-key] (boolean value)]]

    :else
    []))

(defn- position-descriptor
  "What the adapter needs to name the downloaded file. The date comes from the
   adapter, not from here: an action that read the clock would not be pure."
  [state]
  (let [position (get-in state [:pnl-share :position])
        coin (get-in position [:position :coin])
        size-num (js/parseFloat (get-in position [:position :szi]))
        side (cond
               (and (js/isFinite size-num) (neg? size-num)) :short
               (js/isFinite size-num) :long
               :else :long)]
    {:coin (naming/base-symbol coin)
     :side side}))

(defn save-pnl-share-card-image
  [state]
  (when (open? state)
    [[:effects/export-pnl-share-card-png (position-descriptor state)]]))

(defn copy-pnl-share-link
  "Copies the sharer's referral link. The adapter builds the absolute URL from
   the live origin so this stays a pure function of state."
  [state]
  [[:effects/copy-pnl-share-link (referral-code state)]])

(defn handle-pnl-share-card-keydown
  [state key-name]
  (when (and (open? state)
             (= "Escape" key-name))
    (close-pnl-share-card state)))
