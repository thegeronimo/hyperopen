(ns hyperopen.views.asset-selector.annotation-selector-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.settings :as settings]
            [hyperopen.views.asset-selector.controls :as controls]
            [hyperopen.views.asset-selector.processing :as processing]
            [hyperopen.views.asset-selector.rows :as rows]
            [hyperopen.views.asset-selector.test-support :as support]))

(def ^:private hip3-markets
  [{:key "perp:xyz:SPCX" :symbol "SPCX-USDC" :coin "xyz:SPCX" :base "SPCX" :dex "xyz"
    :market-type :perp :category :tradfi :hip3? true :volume24h 30}
   {:key "perp:vntl:SPACEX" :symbol "SPACEX-USDH" :coin "vntl:SPACEX" :base "SPACEX" :dex "vntl"
    :market-type :perp :category :tradfi :hip3? true :volume24h 20}
   {:key "perp:io:OAI" :symbol "OAI-USDC" :coin "io:OAI" :base "OAI" :dex "io"
    :market-type :perp :category :tradfi :hip3? true :volume24h 10}
   {:key "perp:BTC" :symbol "BTC-USDC" :coin "BTC" :base "BTC"
    :market-type :perp :category :crypto :hip3? false :volume24h 40}])

(def ^:private perp-annotations
  {:concise-status :loaded
   :by-coin {"xyz:SPCX" {:category :stocks :display-name "SPCX" :keywords ["spacex" "ai"]}
             "vntl:SPACEX" {:category :preipo :keywords ["elon" "preipo"]}
             "io:OAI" {:category :preipo :display-name "OPENAI" :keywords ["openai" "oai"]}}
   :detail-by-coin {}})

(defn- annotated []
  (processing/reset-processed-assets-cache!)
  (:markets (processing/annotated-selector-markets hip3-markets nil perp-annotations)))

(deftest keyword-search-finds-markets-by-company-test
  (let [markets (annotated)
        search (fn [term strict?]
                 (set (map :key (processing/filter-and-sort-assets
                                 markets term :volume :desc #{} false strict? :all))))]
    (is (= #{"perp:xyz:SPCX" "perp:vntl:SPACEX"} (search "spacex" false)))
    (is (= #{"perp:io:OAI"} (search "openai" false)))
    (is (= #{"perp:io:OAI"} (search "open" true)) "strict mode prefix-matches display names")
    (is (= #{"perp:vntl:SPACEX"} (search "elon" false)))))

(deftest tradfi-asset-class-subtabs-filter-by-deployer-category-test
  (let [markets (annotated)
        tab (fn [active-tab]
              (set (map :key (processing/filter-and-sort-assets
                              markets "" :volume :desc #{} false false active-tab))))]
    (is (= #{"perp:xyz:SPCX"} (tab :tradfi-stocks)))
    (is (= #{"perp:vntl:SPACEX" "perp:io:OAI"} (tab :tradfi-preipo)))
    (is (= #{} (tab :tradfi-rates)))
    (is (= 3 (count (tab :tradfi))))
    (is (every? settings/valid-tabs (keys settings/tradfi-tab->asset-class))
        "persisted sub-tab selections survive a reload")))

(deftest annotated-selector-markets-is-memoized-and-rekeys-test
  (processing/reset-processed-assets-cache!)
  (reset! processing/annotated-markets-cache nil)
  (let [first-result (processing/annotated-selector-markets hip3-markets {} perp-annotations)
        second-result (processing/annotated-selector-markets hip3-markets {} perp-annotations)
        unannotated (processing/annotated-selector-markets hip3-markets {:sentinel true} nil)]
    (is (identical? first-result second-result))
    (is (= :stocks (get-in first-result [:market-by-key "perp:xyz:SPCX" :asset-class])))
    (is (identical? hip3-markets (:markets unannotated)))
    (is (= {:sentinel true} (:market-by-key unannotated))))
  (testing "the processing signature changes when annotations arrive, so cached ordering is rebuilt"
    (is (not= (processing/processed-assets-market-signature hip3-markets)
              (processing/processed-assets-market-signature (annotated))))))

(deftest row-renders-descriptor-after-chips-test
  (let [oai (some #(when (= "io:OAI" (:coin %)) %) (annotated))
        desktop (rows/asset-list-item oai false false #{} #{} #{})
        mobile (rows/mobile-asset-list-item oai false false #{})]
    (is (= ["OPENAI"] (support/collect-strings
                       (support/find-node-by-role desktop "asset-selector-row-descriptor"))))
    (is (= ["OPENAI"] (support/collect-strings
                       (support/find-node-by-role mobile "asset-selector-row-descriptor"))))
    (is (nil? (support/find-node-by-role
               (rows/asset-list-item (last hip3-markets) false false #{} #{} #{})
               "asset-selector-row-descriptor")))))

(deftest tradfi-subtab-row-shows-only-under-tradfi-test
  (is (nil? (controls/tradfi-subtab-row :all)))
  (let [row (controls/tradfi-subtab-row :tradfi-preipo)
        labels (support/collect-strings row)]
    (is (= ["All" "Stocks" "Indices" "Commodities" "Pre-IPO" "FX" "Rates"] labels))
    (is (some? (controls/tradfi-subtab-row :tradfi)))))
