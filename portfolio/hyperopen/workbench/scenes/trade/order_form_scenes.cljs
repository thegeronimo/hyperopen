(ns hyperopen.workbench.scenes.trade.order-form-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.fixtures :as fixtures]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.views.trade.order-form-view :as order-form-view]
            [hyperopen.views.trade.order-form-vm :as order-form-vm]
            [hyperopen.trading.order-form-transitions :as transitions]))

(portfolio/configure-scenes
  {:title "Order Form"
   :collection :trade})

(defn- event-value [event]
  (some-> event .-target .-value))

(defn- event-checked [event]
  (boolean (some-> event .-target .-checked)))

(defn- transition!
  [store transition & args]
  (swap! store #(apply transition % args)))

(defn- update-field!
  [store path value]
  (swap! store transitions/update-order-form path value))

(defn- select-entry-mode!
  [store mode]
  (transition! store transitions/select-entry-mode mode))

(defn- ui-props
  [state]
  {:margin-mode-dropdown-open? (boolean (get-in state [:order-form-ui :margin-mode-dropdown-open?]))
   :leverage-popover-open? (boolean (get-in state [:order-form-ui :leverage-popover-open?]))
   :leverage-draft (get-in state [:order-form-ui :leverage-draft])
   :size-unit-dropdown-open? (boolean (get-in state [:order-form-ui :size-unit-dropdown-open?]))
   :tif-dropdown-open? (boolean (get-in state [:order-form-ui :tif-dropdown-open?]))
   :max-leverage (or (get-in state [:active-market :maxLeverage]) 40)})

(defn- handlers
  [store]
  {:entry-mode
   {:on-close-dropdown #(transition! store transitions/close-pro-order-type-dropdown)
    :on-select-entry-market #(select-entry-mode! store :market)
    :on-select-entry-limit #(select-entry-mode! store :limit)
    :on-toggle-dropdown #(transition! store transitions/toggle-pro-order-type-dropdown)
    :on-dropdown-keydown #(transition! store transitions/handle-pro-order-type-dropdown-keydown (some-> % .-key))
    :on-select-pro-order-type (fn [order-type]
                                #(transition! store transitions/select-pro-order-type order-type))}

   :leverage
   {:on-toggle-margin-mode-dropdown #(transition! store transitions/toggle-margin-mode-dropdown)
    :on-close-margin-mode-dropdown #(transition! store transitions/close-margin-mode-dropdown)
    :on-margin-mode-dropdown-keydown #(transition! store transitions/handle-margin-mode-dropdown-keydown (some-> % .-key))
    :on-select-margin-mode (fn [mode]
                             #(transition! store transitions/set-order-margin-mode mode))
    :on-toggle-leverage-popover #(transition! store transitions/toggle-leverage-popover)
    :on-close-leverage-popover #(transition! store transitions/close-leverage-popover)
    :on-leverage-popover-keydown #(transition! store transitions/handle-leverage-popover-keydown (some-> % .-key))
    :on-set-leverage-draft #(transition! store transitions/set-order-ui-leverage-draft (event-value %))
    :on-confirm-leverage #(transition! store transitions/confirm-order-ui-leverage)}

   :side
   {:on-select-side (fn [side]
                      #(update-field! store [:side] side))}

   :price
   {:on-set-to-mid #(transition! store transitions/set-order-price-to-mid)
    :on-focus #(transition! store transitions/focus-order-price-input)
    :on-blur #(transition! store transitions/blur-order-price-input)
    :on-change #(transition! store transitions/update-order-form [:price] (event-value %))}

   :size
   {:on-change-display #(transition! store transitions/set-order-size-display (event-value %))
    :on-change-mode #(transition! store transitions/update-order-form [:size-input-mode] (event-value %))
    :on-toggle-dropdown #(transition! store transitions/toggle-size-unit-dropdown)
    :on-close-dropdown #(transition! store transitions/close-size-unit-dropdown)
    :on-dropdown-keydown #(transition! store transitions/handle-size-unit-dropdown-keydown (some-> % .-key))
    :on-select-mode (fn [mode]
                      #(transition! store transitions/set-order-size-input-mode mode))
    :on-change-percent #(transition! store transitions/set-order-size-percent (event-value %))}

   :order-type-sections
   {:on-set-trigger-price #(update-field! store [:trigger-px] (event-value %))
    :on-set-scale-start #(update-field! store [:scale :start] (event-value %))
    :on-set-scale-end #(update-field! store [:scale :end] (event-value %))
    :on-set-scale-count #(update-field! store [:scale :count] (event-value %))
    :on-set-scale-skew #(update-field! store [:scale :skew] (event-value %))
    :on-set-twap-minutes #(update-field! store [:twap :minutes] (event-value %))
    :on-toggle-twap-randomize #(update-field! store [:twap :randomize] (event-checked %))}

   :toggles
   {:on-toggle-reduce-only #(update-field! store [:reduce-only] (event-checked %))
    :on-toggle-post-only #(update-field! store [:post-only] (event-checked %))
    :on-toggle-tpsl-panel #(transition! store transitions/toggle-order-tpsl-panel)}

   :tif
   {:on-toggle-dropdown #(transition! store transitions/toggle-tif-dropdown)
    :on-close-dropdown #(transition! store transitions/close-tif-dropdown)
    :on-dropdown-keydown #(transition! store transitions/handle-tif-dropdown-keydown (some-> % .-key))
    :on-select-tif (fn [tif]
                     #(update-field! store [:tif] tif))}

   :tp-sl
   {:on-set-tp-trigger #(update-field! store [:tp :trigger] (event-value %))
    :on-set-tp-offset #(update-field! store [:tp :offset-input] (event-value %))
    :on-set-sl-trigger #(update-field! store [:sl :trigger] (event-value %))
    :on-set-sl-offset #(update-field! store [:sl :offset-input] (event-value %))
    :on-toggle-unit-dropdown #(transition! store transitions/toggle-tpsl-unit-dropdown)
    :on-close-unit-dropdown #(transition! store transitions/close-tpsl-unit-dropdown)
    :on-unit-dropdown-keydown #(transition! store transitions/handle-tpsl-unit-dropdown-keydown (some-> % .-key))
    :on-select-tpsl-unit (fn [unit]
                           #(update-field! store [:tpsl :unit] unit))}

   :submit
   {:on-submit #(swap! store assoc-in [:order-form-runtime :error] "Workbench submit is stubbed")}})

(defn- order-form-card
  [store]
  (layout/page-shell
   (layout/desktop-shell {:class ["max-w-[420px]"]}
    (order-form-view/render-order-form
     {:state @store
      :vm (order-form-vm/order-form-vm @store)
      :handlers (handlers store)
      :ui (ui-props @store)}))))

(defonce market-store
  (atom (fixtures/order-form-state {:type :market
                                    :size "2.5"
                                    :size-display "2.5"
                                    :size-percent 45})))

(defonce limit-store
  (atom (fixtures/order-form-state {:type :limit
                                    :price "100"
                                    :size "1"
                                    :size-display "1"
                                    :size-percent 20})))

(defonce leverage-store
  (atom (fixtures/order-form-state {:type :limit
                                    :price "100"
                                    :size "1"
                                    :size-display "1"}
                                   {:leverage-popover-open? true
                                    :leverage-draft 18})))

(defonce tif-store
  (atom (fixtures/order-form-state {:type :limit
                                    :price "100"
                                    :size "1"
                                    :size-display "1"}
                                   {:tif-dropdown-open? true})))

(defonce size-unit-store
  (atom (fixtures/order-form-state {:type :limit
                                    :price "100"
                                    :size "1"
                                    :size-display "100"
                                    :size-input-mode :quote}
                                   {:size-unit-dropdown-open? true})))

(defonce tpsl-store
  (atom (fixtures/order-form-state {:type :limit
                                    :price "100"
                                    :size "1"
                                    :size-display "1"
                                    :tp {:enabled? true
                                         :trigger "110"}
                                    :sl {:enabled? true
                                         :trigger "95"}}
                                   {:tpsl-panel-open? true
                                    :tpsl-unit-dropdown-open? true})))

(defonce scale-store
  (atom (fixtures/order-form-state {:entry-mode :pro
                                    :type :scale
                                    :price "100"
                                    :size "1"
                                    :size-display "1"
                                    :scale {:start "99"
                                            :end "103"
                                            :count "4"
                                            :skew "0"}})))

(defonce read-only-store
  (atom (assoc (fixtures/order-form-state {:type :limit
                                           :price "100"
                                           :size "1"
                                           :size-display "1"})
               :account-context {:spectate-mode {:active? true
                                                 :address "0x1234567890abcdef1234567890abcdef12345678"}})))

(defonce disabled-store
  (atom (fixtures/order-form-state {:type :limit
                                    :price ""
                                    :size ""
                                    :size-display ""})))

(defonce error-store
  (atom (assoc (fixtures/order-form-state {:type :limit
                                           :price "100"
                                           :size "1"
                                           :size-display "1"})
               :order-form-runtime {:error "Order rejected by stubbed workbench runtime"})))

(portfolio/defscene market
  :params market-store
  [store]
  (order-form-card store))

(portfolio/defscene limit
  :params limit-store
  [store]
  (order-form-card store))

(portfolio/defscene leverage-popover-open
  :params leverage-store
  [store]
  (order-form-card store))

(portfolio/defscene tif-open
  :params tif-store
  [store]
  (order-form-card store))

(portfolio/defscene size-unit-open
  :params size-unit-store
  [store]
  (order-form-card store))

(portfolio/defscene tpsl-open
  :params tpsl-store
  [store]
  (order-form-card store))

(portfolio/defscene scale
  :params scale-store
  [store]
  (order-form-card store))

(portfolio/defscene spectate-read-only
  :params read-only-store
  [store]
  (order-form-card store))

(portfolio/defscene disabled-submit
  :params disabled-store
  [store]
  (order-form-card store))

(portfolio/defscene error-state
  :params error-store
  [store]
  (order-form-card store))
