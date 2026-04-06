(ns hyperopen.views.header-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.platform :as platform]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.header-view :as header-view]
            [hyperopen.wallet.core :as wallet]))

(defn- find-node [pred node]
  (cond
    (vector? node)
    (or (when (pred node) node)
        (some #(find-node pred %) (rest node)))
    (seq? node)
    (some #(find-node pred %) node)
    :else nil))
(defn- find-node-by-role [node role]
  (find-node #(and (vector? %)
                   (= role (get-in % [1 :data-role])))
             node))
(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (rest node))
    (seq? node) (mapcat collect-strings node)
    :else []))
(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))
(defn- class-token-set [node]
  (set (class-values (get-in node [1 :class]))))
(defn- ordered-settings-sections
  [node]
  (->> (tree-seq coll? seq node)
       (filter vector?)
       (keep #(get-in % [1 :data-role]))
       (filter #(#{"trading-settings-session-section"
                   "trading-settings-confirmations-section"
                   "trading-settings-alerts-section"
                   "trading-settings-display-section"} %))
       distinct
       vec))
(def connected-address
  "0x1234567890abcdef1234567890abcdef12345678")

(deftest connected-header-shows-address-dropdown-and-hides-legacy-controls-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address}})
        trigger (find-node-by-role view "wallet-menu-trigger")
        all-text (set (collect-strings view))]
    (is (some? trigger))
    (is (contains? (set (collect-strings trigger)) (wallet/short-addr connected-address)))
    (is (not (contains? all-text "Deposit")))
    (is (not (contains? all-text "Withdraw")))
    (is (not (contains? all-text "Connected")))
    (is (not (contains? all-text "Connect Wallet")))))

(deftest disconnected-header-shows-connect-wallet-button-test
  (let [view (header-view/header-view {:wallet {:connected? false
                                                 :connecting? false}})
        connect-btn (find-node-by-role view "wallet-connect-button")
        connect-text (set (collect-strings connect-btn))
        trigger (find-node-by-role view "wallet-menu-trigger")]
    (is (some? connect-btn))
    (is (contains? connect-text "Connect"))
    (is (contains? connect-text "Connect Wallet"))
    (is (nil? trigger))))

(deftest header-does-not-render-parity-attr-maps-as-visible-text-test
  (let [view (header-view/header-view {:wallet {:connected? false}})
        all-text (collect-strings view)]
    (is (not-any? #(str/includes? % ":data-parity-id") all-text))))

(deftest header-renders-spectate-mode-trigger-button-test
  (let [view (header-view/header-view {:wallet {:connected? false}})
        spectate-mode-button (find-node-by-role view "spectate-mode-open-button")
        spectate-mode-tooltip (find-node-by-role view "spectate-mode-open-tooltip")]
    (is (some? spectate-mode-button))
    (is (= [] (collect-strings spectate-mode-button)))
    (is (= "Open Spectate Mode" (get-in spectate-mode-button [1 :aria-label])))
    (is (= "spectate-mode-open-tooltip" (get-in spectate-mode-button [1 :aria-describedby])))
    (is (contains? (set (collect-strings spectate-mode-tooltip))
                   "Inspect another wallet in read-only mode. Click to open Spectate Mode and choose an address."))
    (is (= [[:actions/open-spectate-mode-modal :event.currentTarget/bounds]]
           (get-in spectate-mode-button [1 :on :click])))))

(deftest header-renders-mobile-menu-trigger-and-settings-button-test
  (let [view (header-view/header-view {:wallet {:connected? false}
                                       :router {:path "/trade"}})
        menu-trigger (find-node-by-role view "mobile-header-menu-trigger")
        mobile-brand (find-node-by-role view "mobile-brand")
        menu-panel (find-node-by-role view "mobile-header-menu-panel")
        settings-button (find-node-by-role view "header-settings-button")]
    (is (some? menu-trigger))
    (is (= [[:actions/open-mobile-header-menu]]
           (get-in menu-trigger [1 :on :click])))
    (is (some? mobile-brand))
    (is (= [[:actions/navigate "/trade"]]
           (get-in mobile-brand [1 :on :click])))
    (is (nil? menu-panel))
    (is (some? settings-button))))

(deftest header-renders-settings-trigger-at-tablet-breakpoint-with-open-dispatch-test
  (let [view (header-view/header-view {:wallet {:connected? false}
                                       :router {:path "/trade"}})
        settings-button (find-node-by-role view "header-settings-button")
        settings-toolbar (find-node-by-role view "header-settings-toolbar")]
    (is (some? settings-button))
    (is (= [[:actions/open-header-settings]]
           (get-in settings-button [1 :on :click])))
    (is (some? settings-toolbar))
    (is (not (contains? (class-token-set settings-toolbar) "md:hidden")))))

(deftest header-renders-trading-settings-shell-when-open-test
  (let [view (header-view/header-view {:wallet {:connected? false
                                                :agent {:storage-mode :local}}
                                       :router {:path "/trade"}
                                       :header-ui {:settings-open? true
                                                   :settings-confirmation nil}
                                       :trading-settings {:fill-alerts-enabled? true}})
        panel (find-node-by-role view "trading-settings-panel")
        sheet (find-node-by-role view "trading-settings-sheet")
        title (find-node-by-role view "trading-settings-title")
        confirmations-section (find-node-by-role view "trading-settings-confirmations-section")
        session-section (find-node-by-role view "trading-settings-session-section")
        alerts-section (find-node-by-role view "trading-settings-alerts-section")
        display-section (find-node-by-role view "trading-settings-display-section")
        confirm-open-orders-row (find-node-by-role view "trading-settings-confirm-open-orders-row")
        confirm-close-position-row (find-node-by-role view "trading-settings-confirm-close-position-row")
        confirm-open-orders-icon (find-node-by-role view "trading-settings-confirm-open-orders-row-icon")
        confirm-close-position-icon (find-node-by-role view "trading-settings-confirm-close-position-row-icon")
        storage-icon (find-node-by-role view "trading-settings-storage-mode-row-icon")
        storage-row (find-node-by-role view "trading-settings-storage-mode-row")
        fill-alerts-row (find-node-by-role view "trading-settings-fill-alerts-row")
        footer-note (find-node-by-role view "trading-settings-footer-note")
        panel-classes (class-token-set panel)
        sheet-classes (class-token-set sheet)
        session-section-classes (class-token-set session-section)
        panel-style (get-in panel [1 :style])
        panel-mounting (get-in panel [1 :replicant/mounting :style])
        sheet-mounting (get-in sheet [1 :replicant/mounting :style])
        all-text (set (collect-strings view))]
    (is (some? panel))
    (is (some? sheet))
    (is (some? confirmations-section))
    (is (some? session-section))
    (is (some? alerts-section))
    (is (some? display-section))
    (is (= ["trading-settings-session-section"
            "trading-settings-confirmations-section"
            "trading-settings-alerts-section"
            "trading-settings-display-section"]
           (ordered-settings-sections view)))
    (is (= "dialog" (get-in panel [1 :role])))
    (is (= true (get-in panel [1 :aria-modal])))
    (is (= "Trading settings" (get-in panel [1 :aria-label])))
    (is (= "Trading settings" (get-in sheet [1 :aria-label])))
    (is (contains? panel-classes "w-[328px]"))
    (is (contains? panel-classes "rounded-[15px]"))
    (is (contains? panel-classes "bg-[#132026]"))
    (is (contains? sheet-classes "bg-[#132026]"))
    (is (contains? session-section-classes "border-t"))
    (is (contains? session-section-classes "first:border-t-0"))
    (is (= "top right" (:transform-origin panel-style)))
    (is (= "translateY(-8px) scale(0.97)" (:transform panel-mounting)))
    (is (= 0 (:opacity panel-mounting)))
    (is (= "translateY(18px)" (:transform sheet-mounting)))
    (is (= 0 (:opacity sheet-mounting)))
    (is (contains? (set (collect-strings title)) "Trading settings"))
    (is (some? confirm-open-orders-row))
    (is (some? confirm-close-position-row))
    (is (nil? confirm-open-orders-icon))
    (is (nil? confirm-close-position-icon))
    (is (some? storage-icon))
    (is (some? storage-row))
    (is (some? fill-alerts-row))
    (is (some? footer-note))
    (is (some? (find-node #(contains? (class-token-set %) "bg-[#62ded0]") session-section)))
    (is (nil? (find-node #(contains? (class-token-set %) "hover:bg-[#20262b]") storage-row)))
    (is (nil? (find-node #(contains? (class-token-set %) "rounded-[9px]") storage-row)))
    (is (contains? all-text "Confirm open orders"))
    (is (contains? all-text "Confirm close position"))
    (is (contains? all-text "Remember session"))
    (is (contains? all-text "Fill alerts"))
    (is (contains? all-text "Ask before sending a new order from the trade form."))
    (is (contains? all-text "Ask before submitting from the close-position popover."))
    (is (contains? all-text "Keep trading enabled across browser restarts on this device."))
    (is (contains? all-text "Show fill alerts while Hyperopen is open."))
    (is (contains? all-text "Applies only to this browser on this device."))
    (is (not (contains? all-text "This device")))
    (is (not (contains? all-text "Requires re-enable")))
    (is (not (contains? all-text "In app")))
    (is (not (contains? all-text "Disable Unified Account Mode")))
    (is (not (contains? all-text "Disable HIP-3 Dex Abstraction")))
    (is (not (contains? all-text "Disable Transaction Delay Protection")))))

(deftest header-renders-order-confirmation-settings-when-open-test
  (let [view (header-view/header-view {:wallet {:connected? false
                                                :agent {:storage-mode :local}}
                                       :router {:path "/trade"}
                                       :header-ui {:settings-open? true
                                                   :settings-confirmation nil}
                                       :trading-settings {:fill-alerts-enabled? true
                                                          :confirm-open-orders? true
                                                          :confirm-close-position? false}})
        confirmations-section (find-node-by-role view "trading-settings-confirmations-section")
        all-text (set (collect-strings view))]
    (is (some? confirmations-section))
    (is (contains? all-text "Confirmations"))
    (is (contains? all-text "Confirm open orders"))
    (is (contains? all-text "Confirm close position"))
    (is (contains? all-text "Ask before sending a new order from the trade form."))
    (is (contains? all-text "Ask before submitting from the close-position popover."))))

(deftest header-renders-phase-1-5-display-settings-when-open-test
  (let [view (header-view/header-view {:wallet {:connected? false
                                                :agent {:storage-mode :local}}
                                       :router {:path "/trade"}
                                       :header-ui {:settings-open? true
                                                   :settings-confirmation nil}
                                       :trading-settings {:fill-alerts-enabled? true
                                                          :animate-orderbook? true
                                                          :show-fill-markers? false}})
        display-section (find-node-by-role view "trading-settings-display-section")
        all-text (set (collect-strings view))]
    (is (some? display-section))
    (is (contains? all-text "Display"))
    (is (contains? all-text "Animate order book"))
    (is (contains? all-text "Fill markers"))
    (is (contains? all-text "Smooth bid and ask depth changes as the book updates."))
    (is (contains? all-text "Show buy and sell markers for the active asset on the chart."))
    (is (not (contains? all-text "Order Book")))
    (is (not (contains? all-text "Motion")))
    (is (not (contains? all-text "Chart")))
    (is (not (contains? all-text "Active Asset")))))

(deftest header-renders-session-default-when-storage-mode-is-missing-test
  (let [view (header-view/header-view {:wallet {:connected? false}
                                       :router {:path "/trade"}
                                       :header-ui {:settings-open? true
                                                   :settings-confirmation nil}
                                       :trading-settings {:fill-alerts-enabled? true}})
        all-text (set (collect-strings view))]
    (is (contains? all-text "Remember session"))
    (is (contains? all-text "Fill alerts"))
    (is (not (contains? all-text "This session")))
    (is (not (contains? all-text "This device")))))

(deftest header-renders-storage-mode-confirmation-warning-when-open-test
  (let [view (header-view/header-view {:wallet {:connected? false
                                                :agent {:storage-mode :session}}
                                       :router {:path "/trade"}
                                       :header-ui {:settings-open? true
                                                   :settings-confirmation {:kind :agent-storage-mode
                                                                           :next-mode :local}}
                                       :trading-settings {:fill-alerts-enabled? true}})
        all-text (set (collect-strings view))]
    (is (contains? all-text "Remember session on this device?"))
    (is (contains? all-text "Changes trading persistence on this device and will require Enable Trading again."))
    (is (contains? all-text "Cancel"))
    (is (contains? all-text "Change"))))

(deftest header-settings-trigger-focus-return-hook-focuses-trigger-when-flagged-test
  (let [focus-calls (atom 0)
        view (header-view/header-view {:wallet {:connected? false}
                                       :router {:path "/trade"}
                                       :header-ui {:settings-return-focus? true}})
        settings-button (find-node-by-role view "header-settings-button")
        on-render (get-in settings-button [1 :replicant/on-render])
        original-get-computed-style (.-getComputedStyle js/globalThis)]
    (is (fn? on-render))
    (set! (.-getComputedStyle js/globalThis)
          (fn [_node]
            #js {:display "inline-flex"}))
    (try
      (with-redefs [platform/queue-microtask! (fn [f] (f))]
        (on-render #js {:isConnected true
                        :focus (fn []
                                 (swap! focus-calls inc))}))
      (finally
        (set! (.-getComputedStyle js/globalThis) original-get-computed-style)))
    (is (= 1 @focus-calls))))

(deftest header-renders-mobile-menu-drawer-when-open-test
  (let [view (header-view/header-view {:wallet {:connected? false}
                                       :router {:path "/trade"}
                                       :header-ui {:mobile-menu-open? true}})
        layer (find-node-by-role view "mobile-header-menu-layer")
        backdrop (find-node-by-role view "mobile-header-menu-backdrop")
        menu-panel (find-node-by-role view "mobile-header-menu-panel")
        menu-close (find-node-by-role view "mobile-header-menu-close")
        menu-brand-mark (find-node-by-role view "mobile-header-menu-brand-mark")
        trade-link (find-node-by-role view "mobile-header-menu-link-trade")
        portfolio-link (find-node-by-role view "mobile-header-menu-link-portfolio")
        funding-link (find-node-by-role view "mobile-header-menu-link-funding")
        vaults-link (find-node-by-role view "mobile-header-menu-link-vaults")
        staking-link (find-node-by-role view "mobile-header-menu-link-staking")
        leaderboard-link (find-node-by-role view "mobile-header-menu-link-leaderboard")
        spectate-link (find-node-by-role view "mobile-header-menu-spectate")
        layer-classes (set (class-values (get-in layer [1 :class])))
        panel-classes (set (class-values (get-in menu-panel [1 :class])))
        backdrop-style (get-in backdrop [1 :style])
        backdrop-mounting (get-in backdrop [1 :replicant/mounting :style])
        backdrop-unmounting (get-in backdrop [1 :replicant/unmounting :style])
        panel-style (get-in menu-panel [1 :style])
        panel-mounting (get-in menu-panel [1 :replicant/mounting :style])
        panel-unmounting (get-in menu-panel [1 :replicant/unmounting :style])
        trade-classes (set (class-values (get-in trade-link [1 :class])))]
    (is (some? layer))
    (is (some? backdrop))
    (is (some? menu-panel))
    (is (some? menu-close))
    (is (some? menu-brand-mark))
    (is (= [[:actions/close-mobile-header-menu]]
           (get-in backdrop [1 :on :click])))
    (is (= [[:actions/close-mobile-header-menu]]
           (get-in menu-close [1 :on :click])))
    (is (= [[:actions/navigate-mobile-header-menu "/trade"]]
           (get-in trade-link [1 :on :click])))
    (is (= [[:actions/navigate-mobile-header-menu "/portfolio"]]
           (get-in portfolio-link [1 :on :click])))
    (is (= [[:actions/navigate-mobile-header-menu "/funding-comparison"]]
           (get-in funding-link [1 :on :click])))
    (is (= [[:actions/navigate-mobile-header-menu "/vaults"]]
           (get-in vaults-link [1 :on :click])))
    (is (= [[:actions/navigate-mobile-header-menu "/staking"]]
           (get-in staking-link [1 :on :click])))
    (is (= [[:actions/navigate-mobile-header-menu "/leaderboard"]]
           (get-in leaderboard-link [1 :on :click])))
    (is (= [[:actions/open-spectate-mode-mobile-header-menu
             :event.currentTarget/bounds]]
           (get-in spectate-link [1 :on :click])))
    (is (contains? layer-classes "fixed"))
    (is (contains? layer-classes "inset-0"))
    (is (contains? panel-classes "left-0"))
    (is (contains? panel-classes "border-r"))
    (is (= "opacity 0.14s ease-out" (:transition backdrop-style)))
    (is (= 1 (:opacity backdrop-style)))
    (is (= 0 (:opacity backdrop-mounting)))
    (is (= 0 (:opacity backdrop-unmounting)))
    (is (= "transform 0.16s ease-out, opacity 0.16s ease-out" (:transition panel-style)))
    (is (= "translateX(0)" (:transform panel-style)))
    (is (= 1 (:opacity panel-style)))
    (is (= "translateX(-18px)" (:transform panel-mounting)))
    (is (= 0 (:opacity panel-mounting)))
    (is (= "translateX(-18px)" (:transform panel-unmounting)))
    (is (= 0 (:opacity panel-unmounting)))
    (is (contains? trade-classes "text-white"))
    (is (nil? (find-node-by-role view "mobile-header-menu-link-earn")))
    (is (nil? (find-node-by-role view "mobile-header-menu-link-referrals")))))

(deftest wallet-menu-renders-copy-and-disconnect-controls-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address
                                                 :agent {:status :not-ready}}})
        details-node (find-node-by-role view "wallet-menu-details")
        menu-panel (find-node-by-role view "wallet-menu-panel")
        copy-button (find-node-by-role view "wallet-menu-copy")
        status-row (find-node-by-role view "wallet-agent-status")
        spectate-button (find-node-by-role view "wallet-menu-open-spectate-mode")
        enable-button (find-node-by-role view "wallet-enable-trading")
        disconnect-button (find-node-by-role view "wallet-menu-disconnect")]
    (is (some? details-node))
    (is (some? menu-panel))
    (is (some? copy-button))
    (is (nil? status-row))
    (is (nil? spectate-button))
    (is (some? enable-button))
    (is (some? disconnect-button))
    (is (= "Disconnect" (last disconnect-button)))))

(deftest wallet-menu-copy-and-disconnect-dispatch-actions-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address
                                                 :agent {:status :not-ready}}})
        copy-button (find-node-by-role view "wallet-menu-copy")
        enable-button (find-node-by-role view "wallet-enable-trading")
        disconnect-button (find-node-by-role view "wallet-menu-disconnect")]
    (is (= [[:actions/copy-wallet-address]]
           (get-in copy-button [1 :on :click])))
    (is (= [[:actions/enable-agent-trading]]
           (get-in enable-button [1 :on :click])))
    (is (= [[:actions/disconnect-wallet]]
           (get-in disconnect-button [1 :on :click])))))

(deftest wallet-menu-hides-enable-button-when-trading-ready-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address
                                                 :agent {:status :ready}}})
        status-row (find-node-by-role view "wallet-agent-status")
        enable-button (find-node-by-role view "wallet-enable-trading")]
    (is (nil? status-row))
    (is (nil? enable-button))))

(deftest wallet-menu-copy-feedback-renders-success-message-and-icon-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address
                                                 :copy-feedback {:kind :success
                                                                 :message "Address copied to clipboard"}}})
        feedback-row (find-node-by-role view "wallet-copy-feedback")
        success-icon (find-node-by-role view "wallet-copy-feedback-success-icon")
        text (set (collect-strings feedback-row))]
    (is (some? feedback-row))
    (is (some? success-icon))
    (is (contains? text "Address copied to clipboard"))))

(deftest wallet-menu-omits-spectate-controls-when-spectating-test
  (let [spectate-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        spectate-state {:spectate-mode {:active? true
                                  :address spectate-address
                                  :started-at-ms 1}
                     :spectate-ui {:modal-open? false
                                :search spectate-address
                                :last-search spectate-address
                                :search-error nil}
                     :watchlist [spectate-address]
                     :watchlist-loaded? true}
        view (header-view/header-view {:wallet {:connected? true
                                                :address connected-address
                                                :agent {:status :ready}}
                                       :account-context spectate-state})
        spectate-mode-button (find-node-by-role view "spectate-mode-open-button")
        spectate-mode-tooltip (find-node-by-role view "spectate-mode-open-tooltip")
        menu-open-spectate (find-node-by-role view "wallet-menu-open-spectate-mode")
        spectate-active-row (find-node-by-role view "wallet-menu-spectate-active-address")]
    (is (true? (account-context/spectate-mode-active?
                {:account-context spectate-state})))
    (is (= "Manage Spectate Mode" (get-in spectate-mode-button [1 :aria-label])))
    (is (contains? (set (collect-strings spectate-mode-tooltip))
                   "Spectate Mode is active. Click to manage the address you are viewing or stop spectating."))
    (is (nil? menu-open-spectate))
    (is (nil? spectate-active-row))))

(deftest wallet-menu-class-attributes-are-tokenized-collections-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address}})
        details-node (find-node-by-role view "wallet-menu-details")
        trigger (find-node-by-role view "wallet-menu-trigger")
        chevron (find-node-by-role view "wallet-menu-chevron")
        panel (find-node-by-role view "wallet-menu-panel")
        panel-classes (set (class-values (get-in panel [1 :class])))]
    (is (sequential? (get-in details-node [1 :class])))
    (is (sequential? (get-in trigger [1 :class])))
    (is (sequential? (get-in chevron [1 :class])))
    (is (sequential? (get-in panel [1 :class])))
    (is (contains? panel-classes "ui-dropdown-panel"))
    (is (= "true" (get-in panel [1 :data-ui-native-details-panel])))
    (is (contains? (set (class-values (get-in chevron [1 :class])))
                   "group-open:rotate-180"))))

(deftest header-highlights-portfolio-link-when-portfolio-route-is-active-test
  (let [view (header-view/header-view {:wallet {}
                                       :router {:path "/portfolio"}})
        portfolio-link (find-node (fn [candidate]
                                    (and (= :button (first candidate))
                                         (= "link" (get-in candidate [1 :role]))
                                         (some #{"Portfolio"} (collect-strings candidate))))
                                  view)
        trade-link (find-node (fn [candidate]
                                (and (= :button (first candidate))
                                     (= "link" (get-in candidate [1 :role]))
                                     (some #{"Trade"} (collect-strings candidate))))
                              view)
        portfolio-classes (set (class-values (get-in portfolio-link [1 :class])))
        trade-classes (set (class-values (get-in trade-link [1 :class])))]
    (is (contains? portfolio-classes "header-nav-link-active"))
    (is (not (contains? trade-classes "header-nav-link-active")))))

(deftest header-desktop-nav-links-render-link-role-with-hrefs-and-navigate-actions-test
  (let [view (header-view/header-view {:wallet {}
                                       :router {:path "/trade"}})
        vaults-link (find-node (fn [candidate]
                                 (and (= :button (first candidate))
                                      (= "link" (get-in candidate [1 :role]))
                                      (some #{"Vaults"} (collect-strings candidate))))
                               view)]
    (is (= :button (first vaults-link)))
    (is (= "link" (get-in vaults-link [1 :role])))
    (is (= "/vaults" (get-in vaults-link [1 :href])))
    (is (= [[:actions/navigate "/vaults"]]
           (get-in vaults-link [1 :on :click])))))

(deftest header-highlights-vaults-link-when-vault-route-is-active-test
  (let [view (header-view/header-view {:wallet {}
                                       :router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}})
        vaults-link (find-node (fn [candidate]
                                 (and (= :button (first candidate))
                                      (= "link" (get-in candidate [1 :role]))
                                      (some #{"Vaults"} (collect-strings candidate))))
                               view)
        portfolio-link (find-node (fn [candidate]
                                    (and (= :button (first candidate))
                                         (= "link" (get-in candidate [1 :role]))
                                         (some #{"Portfolio"} (collect-strings candidate))))
                                  view)
        vaults-classes (set (class-values (get-in vaults-link [1 :class])))
        portfolio-classes (set (class-values (get-in portfolio-link [1 :class])))]
    (is (contains? vaults-classes "header-nav-link-active"))
    (is (not (contains? portfolio-classes "header-nav-link-active")))))

(deftest header-highlights-funding-link-for-funding-comparison-routes-test
  (let [view (header-view/header-view {:wallet {}
                                       :router {:path "/fundingComparison"}})
        funding-link (find-node (fn [candidate]
                                  (and (= :button (first candidate))
                                       (= "link" (get-in candidate [1 :role]))
                                       (some #{"Funding"} (collect-strings candidate))))
                                view)
        trade-link (find-node (fn [candidate]
                                (and (= :button (first candidate))
                                     (= "link" (get-in candidate [1 :role]))
                                     (some #{"Trade"} (collect-strings candidate))))
                              view)
        funding-classes (set (class-values (get-in funding-link [1 :class])))
        trade-classes (set (class-values (get-in trade-link [1 :class])))]
    (is (contains? funding-classes "header-nav-link-active"))
    (is (not (contains? trade-classes "header-nav-link-active")))))

(deftest header-more-menu-renders-api-link-and-highlights-api-route-test
  (let [view (header-view/header-view {:wallet {}
                                       :router {:path "/API"}})
        details-node (find-node-by-role view "header-more-menu")
        trigger (find-node-by-role view "header-more-trigger")
        chevron (find-node-by-role view "header-more-chevron")
        panel (find-node-by-role view "header-more-menu-panel")
        api-link (find-node-by-role view "header-more-link-api")
        trigger-classes (set (class-values (get-in trigger [1 :class])))
        chevron-classes (set (class-values (get-in chevron [1 :class])))
        panel-classes (set (class-values (get-in panel [1 :class])))
        api-classes (set (class-values (get-in api-link [1 :class])))]
    (is (= "header-more-menu:/API"
           (get-in details-node [1 :replicant/key])))
    (is (some? trigger))
    (is (some? chevron))
    (is (some? panel))
    (is (some? api-link))
    (is (contains? trigger-classes "header-nav-link-active"))
    (is (contains? chevron-classes "group-open:rotate-180"))
    (is (contains? panel-classes "ui-dropdown-panel"))
    (is (= "true" (get-in panel [1 :data-ui-native-details-panel])))
    (is (contains? api-classes "bg-[#123a36]"))
    (is (= "/api" (get-in api-link [1 :href])))
    (is (= [[:actions/navigate "/api"]]
           (get-in api-link [1 :on :click])))))

(deftest header-view-uses-app-shell-gutter-test
  (let [view (header-view/header-view {:wallet {}})]
    (is (hiccup/contains-class? view "app-shell-gutter"))))

(deftest header-navigation-links-remain-left-aligned-test
  (let [view (header-view/header-view {:wallet {}})
        nav-node (hiccup/find-first-node view
                                         (fn [candidate]
                                           (and (vector? candidate)
                                                (keyword? (first candidate))
                                                (str/starts-with? (name (first candidate)) "nav."))))]
    (is (= :nav.hidden.md:flex.flex-1.items-center.justify-start.space-x-8.ml-8
           (first nav-node)))))

(deftest header-navigation-links-use-hyperliquid-typography-classes-test
  (let [view (header-view/header-view {:wallet {}})
        trade-link (hiccup/find-first-node view
                                           (fn [candidate]
                                             (and (= :button (first candidate))
                                                  (= "link" (get-in candidate [1 :role]))
                                                  (some #{"Trade"} (hiccup/collect-strings candidate)))))
        vaults-link (hiccup/find-first-node view
                                            (fn [candidate]
                                              (and (= :button (first candidate))
                                                   (= "link" (get-in candidate [1 :role]))
                                                   (some #{"Vaults"} (hiccup/collect-strings candidate)))))
        trade-classes (set (class-values (get-in trade-link [1 :class])))
        vaults-classes (set (class-values (get-in vaults-link [1 :class])))]
    (is (contains? trade-classes "header-nav-link"))
    (is (contains? trade-classes "header-nav-link-active"))
    (is (contains? vaults-classes "header-nav-link"))
    (is (not (contains? vaults-classes "header-nav-link-active")))
    (is (= [[:actions/navigate "/trade"]]
           (get-in trade-link [1 :on :click])))
    (is (= [[:actions/navigate "/vaults"]]
           (get-in vaults-link [1 :on :click])))))
