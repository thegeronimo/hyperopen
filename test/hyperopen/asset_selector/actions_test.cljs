(ns hyperopen.asset-selector.actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.actions :as actions]))

(defn- save-many-path-values
  [effects]
  (-> effects first second))

(defn- path-value
  [effects target-path]
  (some (fn [[path value]]
          (when (= path target-path)
            value))
        (save-many-path-values effects)))

(deftest toggle-asset-dropdown-covers-open-and-close-branches-test
  (let [open-effects (actions/toggle-asset-dropdown
                       {:asset-selector {:visible-dropdown nil
                                         :markets []
                                         :phase :bootstrap}}
                       :asset-selector)
        close-effects (actions/toggle-asset-dropdown
                        {:asset-selector {:visible-dropdown :asset-selector
                                          :markets [{:key "perp:BTC"}]
                                          :phase :full}}
                        :asset-selector)]
    (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] :asset-selector]
                                 [[:asset-selector :scroll-top] 0]
                                 [[:asset-selector :render-limit]
                                  actions/asset-selector-default-render-limit]
                                 [[:asset-selector :last-render-limit-increase-ms] nil]
                                 [[:asset-selector :highlighted-market-key] nil]]]
            [:effects/sync-asset-selector-active-ctx-subscriptions]
            [:effects/fetch-asset-selector-markets]]
           open-effects))
    (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]]]
            [:effects/sync-asset-selector-active-ctx-subscriptions]]
           close-effects))))

(deftest close-asset-dropdown-resets-visible-state-test
  (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                               [[:asset-selector :scroll-top] 0]
                               [[:asset-selector :render-limit]
                                actions/asset-selector-default-render-limit]
                               [[:asset-selector :last-render-limit-increase-ms] nil]
                               [[:asset-selector :highlighted-market-key] nil]]]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/close-asset-dropdown {}))))

(deftest select-asset-covers-fallback-map-and-invalid-input-branches-test
  (testing "unresolved map falls back to provided market and keeps order form when asset unchanged"
    (let [market {:key "perp:BTC" :coin "BTC"}
          effects (actions/select-asset
                    {:active-asset "BTC"
                     :asset-selector {:market-by-key {}}
                     :order-form {:price "123"}}
                    market)]
      (is (= [[:effects/unsubscribe-active-asset "BTC"]
              [:effects/unsubscribe-orderbook "BTC"]
              [:effects/unsubscribe-trades "BTC"]
              [:effects/subscribe-active-asset "BTC"]
              [:effects/subscribe-orderbook "BTC"]
              [:effects/subscribe-trades "BTC"]
              [:effects/sync-active-asset-funding-predictability "BTC"]]
             (subvec effects 2)))
      (is (= [:effects/sync-asset-selector-active-ctx-subscriptions]
             (nth effects 1)))
      (is (= market (path-value effects [:active-market])))
      (is (nil? (path-value effects [:order-form])))
      (is (nil? (path-value effects [:order-form-ui])))))

  (testing "invalid market input still emits deterministic save-many envelope"
    (let [effects (actions/select-asset {:active-asset nil} :unsupported)]
      (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                                   [[:asset-selector :search-term] ""]
                                   [[:asset-selector :scroll-top] 0]
                                   [[:asset-selector :render-limit]
                                    actions/asset-selector-default-render-limit]
                                   [[:asset-selector :last-render-limit-increase-ms] nil]
                                   [[:asset-selector :highlighted-market-key] nil]
                                   [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                   [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                   [[:active-market] nil]]]
              [:effects/sync-asset-selector-active-ctx-subscriptions]
              [:effects/subscribe-active-asset nil]
              [:effects/subscribe-orderbook nil]
              [:effects/subscribe-trades nil]]
             effects)))))

(deftest update-search-sort-strict-favorites-and-tab-actions-test
  (is (= [[:effects/save-many [[[:asset-selector :search-term] ""]
                               [[:asset-selector :scroll-top] 0]
                               [[:asset-selector :render-limit]
                                actions/asset-selector-default-render-limit]
                               [[:asset-selector :last-render-limit-increase-ms] nil]
                               [[:asset-selector :highlighted-market-key] nil]]]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/update-asset-search {} nil)))

  (is (= [[:effects/save-many [[[:asset-selector :sort-by] :volume]
                               [[:asset-selector :sort-direction] :desc]
                               [[:asset-selector :scroll-top] 0]
                               [[:asset-selector :render-limit]
                                actions/asset-selector-default-render-limit]
                               [[:asset-selector :last-render-limit-increase-ms] nil]
                               [[:asset-selector :highlighted-market-key] nil]]]
          [:effects/local-storage-set "asset-selector-sort-by" "volume"]
          [:effects/local-storage-set "asset-selector-sort-direction" "desc"]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/update-asset-selector-sort
           {:asset-selector {:sort-by :volume
                             :sort-direction :asc}}
           :volume)))

  (is (= [[:effects/save-many [[[:asset-selector :sort-by] :volume]
                               [[:asset-selector :sort-direction] :asc]
                               [[:asset-selector :scroll-top] 0]
                               [[:asset-selector :render-limit]
                                actions/asset-selector-default-render-limit]
                               [[:asset-selector :last-render-limit-increase-ms] nil]
                               [[:asset-selector :highlighted-market-key] nil]]]
          [:effects/local-storage-set "asset-selector-sort-by" "volume"]
          [:effects/local-storage-set "asset-selector-sort-direction" "asc"]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/update-asset-selector-sort
           {:asset-selector {:sort-by :volume
                             :sort-direction :desc}}
           :volume)))

  (is (= [[:effects/save-many [[[:asset-selector :strict?] true]
                               [[:asset-selector :scroll-top] 0]
                               [[:asset-selector :render-limit]
                                actions/asset-selector-default-render-limit]
                               [[:asset-selector :last-render-limit-increase-ms] nil]
                               [[:asset-selector :highlighted-market-key] nil]]]
          [:effects/local-storage-set "asset-selector-strict" "true"]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/toggle-asset-selector-strict
           {:asset-selector {:strict? false}})))

  (let [favorite-effects (actions/toggle-asset-favorite
                           {:asset-selector {:favorites #{"perp:BTC"}}}
                           "perp:ETH")]
    (is (= #{"perp:BTC" "perp:ETH"}
           (path-value favorite-effects [:asset-selector :favorites])))
    (is (= #{"perp:BTC" "perp:ETH"}
           (set (nth (second favorite-effects) 2)))))

  (let [remove-effects (actions/toggle-asset-favorite
                         {:asset-selector {:favorites #{"perp:BTC" "perp:ETH"}}}
                         "perp:ETH")]
    (is (= #{"perp:BTC"}
           (path-value remove-effects [:asset-selector :favorites])))
    (is (= #{"perp:BTC"}
           (set (nth (second remove-effects) 2)))))

  (is (= [[:effects/save-many [[[:asset-selector :favorites-only?] false]
                               [[:asset-selector :scroll-top] 0]
                               [[:asset-selector :render-limit]
                                actions/asset-selector-default-render-limit]
                               [[:asset-selector :last-render-limit-increase-ms] nil]
                               [[:asset-selector :highlighted-market-key] nil]]]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/set-asset-selector-favorites-only {} nil)))

  (is (= [[:effects/save-many [[[:asset-selector :active-tab] :hip3]
                               [[:asset-selector :scroll-top] 0]
                               [[:asset-selector :render-limit]
                                actions/asset-selector-default-render-limit]
                               [[:asset-selector :last-render-limit-increase-ms] nil]
                               [[:asset-selector :highlighted-market-key] nil]]]
          [:effects/local-storage-set "asset-selector-active-tab" "hip3"]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/set-asset-selector-tab {} :hip3))))

(deftest handle-asset-selector-shortcut-covers-open-navigation-select-favorite-and-close-test
  (testing "cmd/ctrl+k toggles selector open even when currently hidden"
    (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] :asset-selector]
                                 [[:asset-selector :scroll-top] 0]
                                 [[:asset-selector :render-limit]
                                  actions/asset-selector-default-render-limit]
                                 [[:asset-selector :last-render-limit-increase-ms] nil]
                                 [[:asset-selector :highlighted-market-key] nil]]]
            [:effects/sync-asset-selector-active-ctx-subscriptions]
            [:effects/fetch-asset-selector-markets]]
           (actions/handle-asset-selector-shortcut
             {:asset-selector {:visible-dropdown nil
                               :markets []
                               :phase :bootstrap}}
             "k"
             true
             false
             []))))

  (testing "cmd/ctrl+k is a no-op when selector is already visible"
    (is (= []
           (actions/handle-asset-selector-shortcut
             {:asset-selector {:visible-dropdown :asset-selector}}
             "k"
             true
             false
             []))))

  (testing "non-shortcut keys are ignored when selector is hidden"
    (is (= []
           (actions/handle-asset-selector-shortcut
             {:asset-selector {:visible-dropdown nil}}
             "ArrowDown"
             false
             false
             ["perp:BTC"]))))

  (testing "arrow keys move highlighted market in filtered order"
    (is (= [[:effects/save [:asset-selector :highlighted-market-key] "perp:ETH"]]
           (actions/handle-asset-selector-shortcut
             {:asset-selector {:visible-dropdown :asset-selector
                               :highlighted-market-key nil}
              :active-market {:key "perp:BTC"}}
             "ArrowDown"
             false
             false
             ["perp:BTC" "perp:ETH" "perp:SOL"]))))

  (testing "enter selects highlighted market and preserves deterministic immediate-ui envelope"
    (let [market {:key "perp:ETH" :coin "ETH" :symbol "ETH-USDC"}
          effects (actions/handle-asset-selector-shortcut
                    {:active-asset nil
                     :asset-selector {:visible-dropdown :asset-selector
                                      :highlighted-market-key "perp:ETH"
                                      :market-by-key {"perp:ETH" market}}}
                    "Enter"
                    false
                    false
                    ["perp:BTC" "perp:ETH"])]
      (is (= market (path-value effects [:active-market])))
      (is (= [[:effects/subscribe-active-asset "ETH"]
              [:effects/subscribe-orderbook "ETH"]
              [:effects/subscribe-trades "ETH"]
              [:effects/sync-active-asset-funding-predictability "ETH"]]
             (subvec effects 2)))
      (is (= [:effects/sync-asset-selector-active-ctx-subscriptions]
             (nth effects 1)))))

  (testing "cmd/ctrl+s toggles favorite on highlighted market"
    (let [effects (actions/handle-asset-selector-shortcut
                    {:asset-selector {:visible-dropdown :asset-selector
                                      :highlighted-market-key "perp:ETH"
                                      :favorites #{}}}
                    "s"
                    true
                    false
                    ["perp:BTC" "perp:ETH"])]
      (is (= #{"perp:ETH"}
             (path-value effects [:asset-selector :favorites])))))

  (testing "escape closes selector and resets keyboard highlight"
    (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                                 [[:asset-selector :scroll-top] 0]
                                 [[:asset-selector :render-limit]
                                  actions/asset-selector-default-render-limit]
                                 [[:asset-selector :last-render-limit-increase-ms] nil]
                                 [[:asset-selector :highlighted-market-key] nil]]]
            [:effects/sync-asset-selector-active-ctx-subscriptions]]
           (actions/handle-asset-selector-shortcut
             {:asset-selector {:visible-dropdown :asset-selector}}
             "Escape"
             false
             false
             ["perp:BTC"])))))

(deftest scroll-and-render-limit-actions-cover-noop-and-growth-branches-test
  (is (= []
         (actions/set-asset-selector-scroll-top
           {:asset-selector {:scroll-top 48}}
           "63.9")))
  (is (= [[:effects/save [:asset-selector :scroll-top] 0]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/set-asset-selector-scroll-top
           {:asset-selector {:scroll-top 10}}
           "-5")))

  (is (= [[:effects/save [:asset-selector :render-limit] 10]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/increase-asset-selector-render-limit
           {:asset-selector {:markets (vec (repeat 10 {:key "perp:T"}))
                             :render-limit 2}})))
  (is (= []
         (actions/increase-asset-selector-render-limit
           {:asset-selector {:markets []
                             :render-limit 2}})))

  (is (= [[:effects/save [:asset-selector :render-limit] 10]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/show-all-asset-selector-markets
           {:asset-selector {:markets (vec (repeat 10 {:key "perp:T"}))
                             :render-limit 2}})))
  (is (= []
         (actions/show-all-asset-selector-markets
           {:asset-selector {:markets (vec (repeat 10 {:key "perp:T"}))
                             :render-limit 10}})))

  (is (= []
         (actions/maybe-increase-asset-selector-render-limit
           {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                             :render-limit 120}}
           0)))
  (is (= [[:effects/save-many [[[:asset-selector :render-limit] 200]
                               [[:asset-selector :scroll-top] 2304]]]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/maybe-increase-asset-selector-render-limit
           {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                             :render-limit 120}}
           "2304"))))

(deftest maybe-increase-asset-selector-render-limit-throttles-when-event-time-is-too-close-test
  (is (= [[:effects/save-many [[[:asset-selector :render-limit] 200]
                               [[:asset-selector :last-render-limit-increase-ms] 1000]
                               [[:asset-selector :scroll-top] 2304]]]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/maybe-increase-asset-selector-render-limit
           {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                             :render-limit 120}}
           2304
           1000)))

  (is (= [[:effects/save [:asset-selector :scroll-top] 2304]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/maybe-increase-asset-selector-render-limit
           {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                             :render-limit 120
                             :last-render-limit-increase-ms 1000}}
           2304
           1050)))

  (is (= [[:effects/save-many [[[:asset-selector :render-limit] 200]
                               [[:asset-selector :last-render-limit-increase-ms] 1095]
                               [[:asset-selector :scroll-top] 2304]]]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (actions/maybe-increase-asset-selector-render-limit
           {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                             :render-limit 120
                             :last-render-limit-increase-ms 1000}}
           2304
           1095))))

(deftest icon-status-actions-cover-unknown-and-idempotent-branches-test
  (let [status (actions/apply-asset-icon-status-updates
                 {:asset-selector {:loaded-icons #{"perp:BTC"}
                                   :missing-icons #{"perp:ETH"}}}
                 {"perp:BTC" :missing
                  "perp:ETH" :loaded
                  "" :loaded
                  "perp:X" :unknown})]
    (is (= #{"perp:ETH"} (:loaded-icons status)))
    (is (= #{"perp:BTC"} (:missing-icons status)))
    (is (true? (:changed? status))))

  (is (= {:loaded-icons #{"perp:BTC"}
          :missing-icons #{}
          :changed? false}
         (actions/apply-asset-icon-status-updates
           {:asset-selector {:loaded-icons #{"perp:BTC"}
                             :missing-icons #{}}}
           nil)))

  (is (= []
         (actions/mark-loaded-asset-icon
           {:asset-selector {:loaded-icons #{"perp:BTC"}
                             :missing-icons #{}}}
           "perp:BTC")))
  (is (= [[:effects/queue-asset-icon-status {:market-key "perp:BTC"
                                             :status :loaded}]]
         (actions/mark-loaded-asset-icon
           {:asset-selector {:loaded-icons #{}
                             :missing-icons #{"perp:BTC"}}}
           "perp:BTC")))
  (is (= []
         (actions/mark-missing-asset-icon
           {:asset-selector {:loaded-icons #{}
                             :missing-icons #{"perp:BTC"}}}
           "perp:BTC")))
  (is (= [[:effects/queue-asset-icon-status {:market-key "perp:BTC"
                                             :status :missing}]]
         (actions/mark-missing-asset-icon
           {:asset-selector {:loaded-icons #{"perp:BTC"}
                             :missing-icons #{}}}
           "perp:BTC")))
  (is (= []
         (actions/mark-missing-asset-icon {} nil))))

(deftest funding-hypothetical-actions-sync-size-and-value-test
  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "0.02"
                   :value-input "1000.00"}}]]
         (actions/set-funding-hypothetical-size {} "btc" 50000 "0.02")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "oops"
                   :value-input "1000.00"}}]]
         (actions/set-funding-hypothetical-size {} "btc" 50000 "oops")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "-0.0300"
                   :value-input "1500"}}]]
         (actions/set-funding-hypothetical-value
          {:funding-ui {:hypothetical-position-by-coin {"BTC" {:size-input "-0.0200"
                                                                :value-input "1000.00"}}}}
          "btc"
          50000
          "1500")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "0.0250"
                   :value-input "1250"}}]]
         (actions/set-funding-hypothetical-value {} "BTC" 50000 "1250")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "-0.0250"
                   :value-input "-1250"}}]]
         (actions/set-funding-hypothetical-value {} "BTC" 50000 "-1250")))

  (is (= [[:effects/save [:funding-ui :hypothetical-position-by-coin]
           {"BTC" {:size-input "0.0200"
                   :value-input ""}}]]
         (actions/set-funding-hypothetical-value {} "BTC" 50000 ""))))
