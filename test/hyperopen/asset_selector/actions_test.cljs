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
                                 [[:asset-selector :last-render-limit-increase-ms] nil]]]
            [:effects/fetch-asset-selector-markets]]
           open-effects))
    (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]]]]
           close-effects))))

(deftest close-asset-dropdown-resets-visible-state-test
  (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                               [[:asset-selector :scroll-top] 0]
                               [[:asset-selector :render-limit]
                                actions/asset-selector-default-render-limit]
                               [[:asset-selector :last-render-limit-increase-ms] nil]]]]
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
              [:effects/subscribe-trades "BTC"]]
             (subvec effects 1)))
      (is (= market (path-value effects [:active-market])))
      (is (nil? (path-value effects [:order-form])))
      (is (nil? (path-value effects [:order-form-ui])))))

  (testing "invalid market input still emits deterministic save-many envelope"
    (let [effects (actions/select-asset {:active-asset nil} :unsupported)]
      (is (= [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                                   [[:asset-selector :scroll-top] 0]
                                   [[:asset-selector :render-limit]
                                    actions/asset-selector-default-render-limit]
                                   [[:asset-selector :last-render-limit-increase-ms] nil]
                                   [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                   [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                   [[:active-market] nil]]]
              [:effects/subscribe-active-asset nil]
              [:effects/subscribe-orderbook nil]
              [:effects/subscribe-trades nil]]
             effects)))))

(deftest update-search-sort-strict-favorites-and-tab-actions-test
  (is (= [[:effects/save-many [[[:asset-selector :search-term] ""]
                               [[:asset-selector :scroll-top] 0]
                               [[:asset-selector :render-limit]
                                actions/asset-selector-default-render-limit]
                               [[:asset-selector :last-render-limit-increase-ms] nil]]]]
         (actions/update-asset-search {} nil)))

  (is (= [[:effects/save-many [[[:asset-selector :sort-by] :volume]
                               [[:asset-selector :sort-direction] :desc]
                               [[:asset-selector :scroll-top] 0]
                               [[:asset-selector :render-limit]
                                actions/asset-selector-default-render-limit]
                               [[:asset-selector :last-render-limit-increase-ms] nil]]]
          [:effects/local-storage-set "asset-selector-sort-by" "volume"]
          [:effects/local-storage-set "asset-selector-sort-direction" "desc"]]
         (actions/update-asset-selector-sort
           {:asset-selector {:sort-by :volume
                             :sort-direction :asc}}
           :volume)))

  (is (= [[:effects/save-many [[[:asset-selector :sort-by] :volume]
                               [[:asset-selector :sort-direction] :asc]
                               [[:asset-selector :scroll-top] 0]
                               [[:asset-selector :render-limit]
                                actions/asset-selector-default-render-limit]
                               [[:asset-selector :last-render-limit-increase-ms] nil]]]
          [:effects/local-storage-set "asset-selector-sort-by" "volume"]
          [:effects/local-storage-set "asset-selector-sort-direction" "asc"]]
         (actions/update-asset-selector-sort
           {:asset-selector {:sort-by :volume
                             :sort-direction :desc}}
           :volume)))

  (is (= [[:effects/save-many [[[:asset-selector :strict?] true]
                               [[:asset-selector :scroll-top] 0]
                               [[:asset-selector :render-limit]
                                actions/asset-selector-default-render-limit]
                               [[:asset-selector :last-render-limit-increase-ms] nil]]]
          [:effects/local-storage-set "asset-selector-strict" "true"]]
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
                               [[:asset-selector :last-render-limit-increase-ms] nil]]]]
         (actions/set-asset-selector-favorites-only {} nil)))

  (is (= [[:effects/save-many [[[:asset-selector :active-tab] :hip3]
                               [[:asset-selector :scroll-top] 0]
                               [[:asset-selector :render-limit]
                                actions/asset-selector-default-render-limit]
                               [[:asset-selector :last-render-limit-increase-ms] nil]]]
          [:effects/local-storage-set "asset-selector-active-tab" "hip3"]]
         (actions/set-asset-selector-tab {} :hip3))))

(deftest scroll-and-render-limit-actions-cover-noop-and-growth-branches-test
  (is (= []
         (actions/set-asset-selector-scroll-top
           {:asset-selector {:scroll-top 48}}
           "63.9")))
  (is (= [[:effects/save [:asset-selector :scroll-top] 0]]
         (actions/set-asset-selector-scroll-top
           {:asset-selector {:scroll-top 10}}
           "-5")))

  (is (= [[:effects/save [:asset-selector :render-limit] 10]]
         (actions/increase-asset-selector-render-limit
           {:asset-selector {:markets (vec (repeat 10 {:key "perp:T"}))
                             :render-limit 2}})))
  (is (= []
         (actions/increase-asset-selector-render-limit
           {:asset-selector {:markets []
                             :render-limit 2}})))

  (is (= [[:effects/save [:asset-selector :render-limit] 10]]
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
  (is (= [[:effects/save [:asset-selector :render-limit] 200]]
         (actions/maybe-increase-asset-selector-render-limit
           {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                             :render-limit 120}}
           "5100"))))

(deftest maybe-increase-asset-selector-render-limit-throttles-when-event-time-is-too-close-test
  (is (= [[:effects/save-many [[[:asset-selector :render-limit] 200]
                               [[:asset-selector :last-render-limit-increase-ms] 1000]
                               [[:asset-selector :scroll-top] 5088]]]]
         (actions/maybe-increase-asset-selector-render-limit
           {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                             :render-limit 120}}
           5100
           1000)))

  (is (= [[:effects/save [:asset-selector :scroll-top] 5088]]
         (actions/maybe-increase-asset-selector-render-limit
           {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                             :render-limit 120
                             :last-render-limit-increase-ms 1000}}
           5100
           1050)))

  (is (= [[:effects/save-many [[[:asset-selector :render-limit] 200]
                               [[:asset-selector :last-render-limit-increase-ms] 1095]
                               [[:asset-selector :scroll-top] 5088]]]]
         (actions/maybe-increase-asset-selector-render-limit
           {:asset-selector {:markets (vec (repeat 400 {:key "perp:T"}))
                             :render-limit 120
                             :last-render-limit-increase-ms 1000}}
           5100
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
