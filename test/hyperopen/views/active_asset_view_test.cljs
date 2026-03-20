(ns hyperopen.views.active-asset-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.active-asset.test-support :as support]
            [hyperopen.views.active-asset-view :as view]
            [hyperopen.views.asset-selector-view :as asset-selector-view]))

(deftest active-asset-list-spot-id-market-resolution-fallback-test
  (let [full-state {:active-asset "@1"
                    :active-market nil
                    :asset-selector {:missing-icons #{}
                                     :market-by-key {"spot:@1" {:key "spot:@1"
                                                                 :coin "@1"
                                                                 :symbol "HYPE/USDC"
                                                                 :base "HYPE"
                                                                 :quote "USDC"
                                                                 :market-type :spot
                                                                 :mark 10.0
                                                                 :markRaw "10.0"
                                                                 :change24h 1.0
                                                                 :change24hPct 11.11
                                                                 :volume24h 100000.0}}}}
        view-node (view/active-asset-view full-state)
        strings (set (support/collect-strings view-node))]
    (is (contains? strings "HYPE/USDC"))
    (is (contains? strings "SPOT"))
    (is (not (contains? strings "Loading...")))))

(deftest active-asset-panel-passes-scroll-top-to-selector-wrapper-test
  (let [captured-props (atom nil)
        dropdown-state {:visible-dropdown :asset-selector
                        :search-term ""
                        :sort-by :volume
                        :sort-direction :desc
                        :favorites #{}
                        :favorites-only? false
                        :missing-icons #{}
                        :loaded-icons #{}
                        :scroll-top 144
                        :render-limit 120
                        :strict? false
                        :active-tab :all}
        full-state {:active-asset nil
                    :asset-selector {:markets [{:key "perp:BTC"
                                                :coin "BTC"
                                                :symbol "BTC-USDC"
                                                :market-type :perp}]}}]
    (with-redefs [asset-selector-view/asset-selector-wrapper
                  (fn [props]
                    (reset! captured-props props)
                    [:div])]
      (view/active-asset-panel (assoc full-state :asset-selector dropdown-state)))
    (is (= 144 (:scroll-top @captured-props)))))
