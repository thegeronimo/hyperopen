(ns hyperopen.views.app-shell-spacing-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.state.trading :as trading]
            [hyperopen.views.app-view :as app-view]
            [hyperopen.views.footer-view :as footer-view]
            [hyperopen.views.header-view :as header-view]
            [hyperopen.views.trade-view :as trade-view]))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- contains-class? [node class-name]
  (letfn [(walk [n]
            (cond
              (vector? n)
              (let [attrs (when (map? (second n)) (second n))
                    children (if attrs (drop 2 n) (drop 1 n))
                    class-set (set (class-values (:class attrs)))]
                (or (contains? class-set class-name)
                    (some walk children)))

              (seq? n)
              (some walk n)

              :else
              nil))]
    (boolean (walk node))))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- find-nodes [node pred]
  (letfn [(walk [n]
            (cond
              (vector? n)
              (let [attrs (when (map? (second n)) (second n))
                    children (if attrs (drop 2 n) (drop 1 n))
                    matches (if (pred n) [n] [])]
                (into matches (mapcat walk children)))

              (seq? n)
              (mapcat walk n)

              :else []))]
    (vec (walk node))))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings node)
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- root-class-set [node]
  (let [attrs (when (and (vector? node) (map? (second node)))
                (second node))]
    (set (class-values (:class attrs)))))

(defn- node-class-set [node]
  (let [attrs (when (and (vector? node) (map? (second node)))
                (second node))]
    (set (class-values (:class attrs)))))

(def trade-view-test-state
  {:active-asset nil
   :active-market nil
   :orderbooks {}
   :webdata2 {}
   :orders {:open-orders []
            :open-orders-snapshot []
            :open-orders-snapshot-by-dex {}
            :fills []
            :fundings []
            :order-history []
            :ledger []}
   :spot {:meta nil
          :clearinghouse-state nil}
   :perp-dex-clearinghouse {}
   :order-form (trading/default-order-form)
   :asset-selector {:visible-dropdown nil
                    :search-term ""
                    :sort-by :volume
                    :sort-direction :desc
                    :markets []
                    :market-by-key {}
                    :loading? false
                    :phase :bootstrap
                    :favorites #{}
                    :missing-icons #{}
                    :favorites-only? false
                    :strict? false
                    :active-tab :all}
   :chart-options {:selected-timeframe :1d
                   :selected-chart-type :candlestick}
   :orderbook-ui {:size-unit :base
                  :size-unit-dropdown-visible? false
                  :price-aggregation-dropdown-visible? false
                  :price-aggregation-by-coin {}
                  :active-tab :orderbook}
   :account-info {:selected-tab :balances
                  :loading false
                  :error nil
                  :hide-small-balances? false
                  :balances-sort {:column nil :direction :asc}
                  :positions-sort {:column nil :direction :asc}
                  :open-orders-sort {:column "Time" :direction :desc}}})

(deftest header-view-uses-app-shell-gutter-test
  (let [view-node (header-view/header-view {:wallet {}})]
    (is (contains-class? view-node "app-shell-gutter"))))

(deftest header-navigation-links-remain-left-aligned-test
  (let [view-node (header-view/header-view {:wallet {}})
        nav-node (find-first-node view-node
                                  (fn [candidate]
                                    (and (vector? candidate)
                                         (keyword? (first candidate))
                                         (str/starts-with? (name (first candidate)) "nav."))))]
    (is (= :nav.hidden.md:flex.flex-1.items-center.justify-start.space-x-8.ml-8
           (first nav-node)))))

(deftest header-navigation-links-use-hyperliquid-typography-classes-test
  (let [view-node (header-view/header-view {:wallet {}})
        trade-link (find-first-node view-node
                                    (fn [candidate]
                                      (and (= :a (first candidate))
                                           (some #{"Trade"} (collect-strings candidate)))))
        vaults-link (find-first-node view-node
                                     (fn [candidate]
                                       (and (= :a (first candidate))
                                            (some #{"Vaults"} (collect-strings candidate)))))
        trade-classes (set (class-values (get-in trade-link [1 :class])))
        vaults-classes (set (class-values (get-in vaults-link [1 :class])))]
    (is (contains? trade-classes "header-nav-link"))
    (is (contains? trade-classes "header-nav-link-active"))
    (is (contains? vaults-classes "header-nav-link"))
    (is (not (contains? vaults-classes "header-nav-link-active")))))

(deftest trade-view-does-not-use-app-shell-gutter-test
  (let [view-node (trade-view/trade-view trade-view-test-state)]
    (is (not (contains-class? view-node "app-shell-gutter")))))

(deftest trade-view-root-and-right-column-layout-test
  (let [view-node (trade-view/trade-view trade-view-test-state)
        root-classes (root-class-set view-node)]
    (is (not (contains? root-classes "overflow-auto")))
    (is (contains? root-classes "min-h-0"))
    (is (contains-class? view-node "right-[280px]"))
    (is (contains-class? view-node "lg:grid-cols-[minmax(0,1fr)_280px]"))
    (is (contains-class? view-node "lg:grid-rows-[minmax(580px,1fr)_auto_auto]"))
    (is (contains-class? view-node "xl:grid-cols-[minmax(0,1fr)_280px_280px]"))
    (is (contains-class? view-node "xl:grid-rows-[minmax(580px,1fr)_auto]"))
    (is (not (contains-class? view-node "xl:grid-rows-[minmax(580px,auto)_auto]")))
    (is (contains-class? view-node "xl:row-span-2"))
    (is (not (contains-class? view-node "xl:row-start-2")))))

(deftest trade-view-account-info-cell-bounds-overflow-test
  (let [view-node (trade-view/trade-view trade-view-test-state)
        account-info-cell (find-first-node view-node
                                           (fn [candidate]
                                             (let [classes (node-class-set candidate)]
                                               (and (contains? classes "lg:col-span-2")
                                                    (contains? classes "xl:col-span-2")
                                                    (contains? classes "border-t")))))
        account-info-cell-classes (node-class-set account-info-cell)]
    (is (some? account-info-cell))
    (is (contains? account-info-cell-classes "flex"))
    (is (contains? account-info-cell-classes "flex-col"))
    (is (contains? account-info-cell-classes "min-h-0"))
    (is (contains? account-info-cell-classes "overflow-hidden"))))

(deftest trade-view-reads-runtime-health-snapshot-for-surface-freshness-cues-test
  (let [state (-> trade-view-test-state
                  (assoc :active-asset "BTC")
                  (assoc-in [:websocket :health]
                            {:generated-at-ms 5000
                             :streams {["l2Book" "BTC" nil nil nil]
                                       {:topic "l2Book"
                                        :status :live
                                        :subscribed? true
                                        :last-payload-at-ms 4900
                                        :stale-threshold-ms 5000}}})
                  (assoc-in [:websocket-ui :show-surface-freshness-cues?] true)
                  (assoc :orderbooks {"BTC" {:bids [{:px "99" :sz "2"}]
                                            :asks [{:px "101" :sz "1"}]}}))
        view-node (trade-view/trade-view state)
        cue-node (find-first-node view-node
                                  #(= "orderbook-freshness-cue"
                                      (get-in % [1 :data-role])))]
    (is (some? cue-node))
    (is (some #(str/includes? % "Updated")
              (collect-strings cue-node)))))

(deftest footer-view-uses-app-shell-gutter-test
  (let [view-node (footer-view/footer-view {:websocket {:status :connected}})]
    (is (contains-class? view-node "app-shell-gutter"))
    (is (contains-class? view-node "fixed"))
    (is (contains-class? view-node "inset-x-0"))
    (is (contains-class? view-node "bottom-0"))
    (is (contains-class? view-node "z-40"))
    (is (contains-class? view-node "bg-base-200"))
    (is (contains-class? view-node "isolate"))))

(deftest app-view-root-hides-scrollbar-with-trade-xl-scroll-lock-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/trade"}
                                            :wallet {}))
        root-classes (root-class-set view-node)]
    (is (contains? root-classes "overflow-y-auto"))
    (is (contains? root-classes "scrollbar-hide"))
    (is (contains? root-classes "xl:overflow-y-hidden"))))

(deftest app-view-root-keeps-non-trade-scroll-policy-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/vaults"}
                                            :wallet {}))
        root-classes (root-class-set view-node)]
    (is (contains? root-classes "overflow-y-auto"))
    (is (contains? root-classes "scrollbar-hide"))
    (is (not (contains? root-classes "xl:overflow-y-hidden")))))

(deftest app-view-renders-portfolio-route-with-portfolio-root-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/portfolio"}
                                            :wallet {}))
        portfolio-root (find-first-node view-node
                                        #(= "portfolio-root"
                                            (get-in % [1 :data-parity-id])))
        trade-root (find-first-node view-node
                                    #(= "trade-root"
                                        (get-in % [1 :data-parity-id])))]
    (is (some? portfolio-root))
    (is (nil? trade-root))))

(deftest app-view-renders-funding-comparison-route-with-funding-root-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/funding-comparison"}
                                            :wallet {}
                                            :funding-comparison-ui {:query ""
                                                                    :timeframe :8hour
                                                                    :sort {:column :coin
                                                                           :direction :asc}
                                                                    :loading? false}
                                            :funding-comparison {:predicted-fundings []
                                                                 :error nil
                                                                 :loaded-at-ms nil}
                                            :asset-selector {:favorites #{}
                                                             :market-by-key {}}))
        funding-root (find-first-node view-node
                                      #(= "funding-comparison-root"
                                          (get-in % [1 :data-parity-id])))]
    (is (some? funding-root))))

(deftest app-view-renders-vault-routes-with-vault-roots-test
  (let [list-view (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/vaults"}
                                            :wallet {}
                                            :vaults-ui {:search-query ""
                                                        :filter-leading? true
                                                        :filter-deposited? true
                                                        :filter-others? true
                                                        :filter-closed? false
                                                        :snapshot-range :month
                                                        :sort {:column :tvl
                                                               :direction :desc}}
                                            :vaults {:loading {:index? false
                                                               :summaries? false}
                                                     :errors {:index nil
                                                              :summaries nil}
                                                     :user-equity-by-address {}
                                                     :merged-index-rows []}))
        detail-view (app-view/app-view (assoc trade-view-test-state
                                              :router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}
                                              :wallet {}
                                              :vaults-ui {:detail-tab :about
                                                          :snapshot-range :month
                                                          :detail-loading? false}
                                              :vaults {:errors {:details-by-address {}
                                                                :webdata-by-vault {}}
                                                       :details-by-address {}
                                                       :webdata-by-vault {}
                                                       :user-equity-by-address {}
                                                       :merged-index-rows []}))
        list-root (find-first-node list-view #(= "vaults-root"
                                                 (get-in % [1 :data-parity-id])))
        detail-root (find-first-node detail-view #(= "vault-detail-root"
                                                     (get-in % [1 :data-parity-id])))]
    (is (some? list-root))
    (is (some? detail-root))))

(deftest app-view-renders-global-order-feedback-toast-when-present-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :ui {:toast {:kind :success
                                                         :message "Order submitted."}}))
        toast-node (find-first-node view-node
                                    #(= "global-toast" (get-in % [1 :data-role])))]
    (is (some? toast-node))
    (is (contains? (set (collect-strings toast-node))
                   "Order submitted."))))

(deftest app-view-renders-stacked-order-feedback-toasts-and-dismiss-actions-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :ui {:toast {:kind :success
                                                         :message "Sold 1.25 SOL"}
                                                 :toasts [{:id "toast-1"
                                                           :kind :success
                                                           :headline "Bought 6 HYPE"
                                                           :subline "At average price of $31.66667"
                                                           :message "Bought 6 HYPE"}
                                                          {:id "toast-2"
                                                           :kind :success
                                                           :headline "Sold 1.25 SOL"
                                                           :subline "At average price of $90.79"
                                                           :message "Sold 1.25 SOL"}]}))
        toast-nodes (find-nodes view-node #(= "global-toast" (get-in % [1 :data-role])))
        dismiss-nodes (find-nodes view-node #(= "global-toast-dismiss" (get-in % [1 :data-role])))
        rendered-strings (set (collect-strings view-node))]
    (is (= 2 (count toast-nodes)))
    (is (= 2 (count dismiss-nodes)))
    (is (contains? rendered-strings "Bought 6 HYPE"))
    (is (contains? rendered-strings "Sold 1.25 SOL"))
    (is (contains? rendered-strings "At average price of $31.66667"))
    (is (contains? rendered-strings "At average price of $90.79"))
    (is (= [[:actions/dismiss-order-feedback-toast "toast-1"]]
           (get-in (first dismiss-nodes) [1 :on :click])))
    (is (= [[:actions/dismiss-order-feedback-toast "toast-2"]]
           (get-in (second dismiss-nodes) [1 :on :click])))))

(deftest app-view-renders-ghost-mode-banner-when-active-test
  (let [address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :account-context {:ghost-mode {:active? true
                                                                           :address address}
                                                              :ghost-ui {:modal-open? false}
                                                              :watchlist []}))
        banner-node (find-first-node view-node
                                     #(= "ghost-mode-active-banner"
                                         (get-in % [1 :data-role])))
        manage-button (find-first-node view-node
                                       #(= "ghost-mode-banner-manage"
                                           (get-in % [1 :data-role])))
        stop-button (find-first-node view-node
                                     #(= "ghost-mode-banner-stop"
                                         (get-in % [1 :data-role])))]
    (is (some? banner-node))
    (is (contains? (set (collect-strings banner-node)) "Ghost Mode"))
    (is (contains? (set (collect-strings banner-node)) "Currently spectating"))
    (is (= [[:actions/open-ghost-mode-modal :event.currentTarget/bounds]]
           (get-in manage-button [1 :on :click])))
    (is (= [[:actions/stop-ghost-mode]]
           (get-in stop-button [1 :on :click])))))

(deftest app-view-renders-ghost-mode-modal-and-stop-control-when-open-and-active-test
  (let [address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        label "The Assistance Fund"
        view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/trade"}
                                            :wallet {:copy-feedback {:kind :success
                                                                     :message "Address copied to clipboard"}}
                                            :account-context {:ghost-mode {:active? true
                                                                           :address address}
                                                              :ghost-ui {:modal-open? true
                                                                         :search address
                                                                         :label ""
                                                                         :search-error nil}
                                                              :watchlist [{:address address
                                                                           :label label}]}))
        modal-root (find-first-node view-node
                                    #(= "ghost-mode-modal-root"
                                        (get-in % [1 :data-role])))
        watchlist-row (find-first-node view-node
                                       #(= "ghost-mode-watchlist-row"
                                           (get-in % [1 :data-role])))
        watchlist-label (find-first-node view-node
                                         #(= "ghost-mode-watchlist-label"
                                             (get-in % [1 :data-role])))
        close-button (find-first-node view-node
                                      #(= "ghost-mode-close"
                                          (get-in % [1 :data-role])))
        stop-button (find-first-node view-node
                                     #(= "ghost-mode-stop"
                                         (get-in % [1 :data-role])))
        copy-button (find-first-node view-node
                                     #(= "ghost-mode-watchlist-copy"
                                         (get-in % [1 :data-role])))
        edit-button (find-first-node view-node
                                     #(= "ghost-mode-watchlist-edit"
                                         (get-in % [1 :data-role])))
        link-placeholder-button (find-first-node view-node
                                                 #(= "ghost-mode-watchlist-link-placeholder"
                                                     (get-in % [1 :data-role])))
        copy-feedback-row (find-first-node view-node
                                           #(= "ghost-mode-copy-feedback"
                                               (get-in % [1 :data-role])))
        rendered-strings (set (collect-strings modal-root))]
    (is (some? modal-root))
    (is (some? watchlist-row))
    (is (some? watchlist-label))
    (is (some? close-button))
    (is (contains? (set (collect-strings modal-root)) "Ghost Mode"))
    (is (contains? (set (collect-strings modal-root)) "Currently spectating: "))
    (is (contains? rendered-strings address))
    (is (contains? rendered-strings label))
    (is (contains? rendered-strings "Address copied to clipboard"))
    (is (some? copy-feedback-row))
    (is (not-any? #(str/starts-with? % "[[:li")
                  rendered-strings))
    (is (= [[:actions/close-ghost-mode-modal]]
           (get-in close-button [1 :on :click])))
    (is (= [[:actions/stop-ghost-mode]]
           (get-in stop-button [1 :on :click])))
    (is (= [[:actions/copy-ghost-mode-watchlist-address address]]
           (get-in copy-button [1 :on :click])))
    (is (= [[:actions/edit-ghost-mode-watchlist-address address]]
           (get-in edit-button [1 :on :click])))
    (is (true? (get-in link-placeholder-button [1 :disabled])))))
