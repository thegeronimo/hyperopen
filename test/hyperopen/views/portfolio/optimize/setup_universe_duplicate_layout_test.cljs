(ns hyperopen.views.portfolio.optimize.setup-universe-duplicate-layout-test
  "The Universe panel tells the trader when a search result is another listing
  of a stock already in the mix, asks inline before adding it, and keeps twins
  visibly linked if both are kept."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.optimize.setup-layout-fixtures
             :refer [node-by-role node-text click-actions]]))

(defn- hip3-perp
  [dex base quote volume]
  {:key (str "perp:" dex ":" base) :market-type :perp :coin (str dex ":" base)
   :dex dex :symbol (str base "-" quote) :base base :quote quote :volume24h volume})

(def ^:private markets
  [(hip3-perp "xyz" "NVDA" "USDC" 900)
   (hip3-perp "flx" "NVDA" "USDH" 500)
   (hip3-perp "cash" "NVDA" "USDT0" 300)
   (hip3-perp "xyz" "NVDL" "USDC" 100)])

(def ^:private held-nvda
  {:instrument-id "perp:xyz:NVDA" :market-type :perp :coin "xyz:NVDA" :dex "xyz"
   :symbol "NVDA-USDC" :base "NVDA" :quote "USDC" :shortable? true
   :position-side :long})

(def ^:private held-sol
  {:instrument-id "perp:SOL" :market-type :perp :coin "SOL" :symbol "SOL-USDC"
   :base "SOL" :position-side :long})

(defn- view
  [{:keys [query universe prompt]}]
  (portfolio-view/portfolio-view
   {:router {:path "/portfolio/optimize/new"}
    :portfolio-ui {:optimizer {:universe-search-query (or query "")
                               :universe-search-type-filter :all
                               :universe-search-quote-filter :all
                               :universe-duplicate-prompt prompt}}
    :portfolio {:optimizer {:draft {:universe universe
                                    :constraints {:long-only? false}}}}
    :asset-selector {:markets markets
                     :market-by-key (into {} (map (juxt :key identity)) markets)}}))

(deftest search-flags-other-listings-of-a-held-stock-test
  (let [node (view {:query "nvd" :universe [held-sol held-nvda]})
        banner (node-by-role node "portfolio-optimizer-universe-duplicate-banner")]
    (testing "one plain-words banner names the stock already held"
      (is (some? banner))
      (is (str/includes? (node-text banner) "You already have NVDA"))
      (is (str/includes? (node-text banner) "NVDA-USDC · xyz is in your universe")))
    (testing "each other NVDA listing is tagged; the look-alike NVDL is not"
      (is (= "Same as NVDA-USDC · xyz"
             (node-text (node-by-role node "portfolio-optimizer-universe-same-as-perp:flx:NVDA"))))
      (is (some? (node-by-role node "portfolio-optimizer-universe-same-as-perp:cash:NVDA")))
      (is (nil? (node-by-role node "portfolio-optimizer-universe-same-as-perp:xyz:NVDL"))))
    (testing "the held row in the list below is outlined while the query matches"
      (let [row (node-by-role node "portfolio-optimizer-universe-selected-row-perp:xyz:NVDA")]
        (is (= "true" (get-in row [1 :data-search-match])))
        (is (nil? (get-in (node-by-role node "portfolio-optimizer-universe-selected-row-perp:SOL")
                          [1 :data-search-match])))))
    (testing "add-all leaves the same-stock rows out and says so"
      (let [add-all (node-by-role node "portfolio-optimizer-universe-search-add-all")]
        (is (= "+ add all 1 · skips 2 duplicates" (node-text add-all)))
        (is (= [[:actions/add-portfolio-optimizer-universe-matches ["perp:xyz:NVDL"]]]
               (click-actions add-all)))))))

(deftest no-banner-without-a-held-sibling-test
  (let [node (view {:query "nvd" :universe [held-sol]})]
    (is (nil? (node-by-role node "portfolio-optimizer-universe-duplicate-banner")))
    (is (nil? (node-by-role node "portfolio-optimizer-universe-same-as-perp:flx:NVDA")))))

(deftest confirm-opens-inline-beside-the-row-test
  (let [node (view {:query "nvd" :universe [held-sol held-nvda] :prompt "perp:flx:NVDA"})
        confirm (node-by-role node "portfolio-optimizer-universe-duplicate-confirm-perp:flx:NVDA")
        row (node-by-role node "portfolio-optimizer-universe-candidate-row-perp:flx:NVDA")]
    (is (some? confirm))
    (is (str/includes? (node-text confirm)
                       "NVDA-USDH · flx is the same asset as NVDA-USDC · xyz, already in your mix."))
    (testing "the confirm is a sibling of the row, so its buttons cannot bubble into a plain add"
      (is (nil? (node-by-role row "portfolio-optimizer-universe-duplicate-confirm-perp:flx:NVDA"))))
    (testing "the comparison names venue and quote"
      (let [compare (node-text (node-by-role node "portfolio-optimizer-universe-duplicate-compare-perp:flx:NVDA"))]
        (is (str/includes? compare "xyz"))
        (is (str/includes? compare "flx"))
        (is (str/includes? compare "USDH"))))
    (testing "each button answers the confirm"
      (is (= [[:actions/add-portfolio-optimizer-universe-instrument "perp:flx:NVDA" "swap"]]
             (click-actions (node-by-role node "portfolio-optimizer-universe-duplicate-swap-perp:flx:NVDA"))))
      (is (= [[:actions/add-portfolio-optimizer-universe-instrument "perp:flx:NVDA" "both"]]
             (click-actions (node-by-role node "portfolio-optimizer-universe-duplicate-both-perp:flx:NVDA"))))
      (is (= [[:actions/add-portfolio-optimizer-universe-instrument "perp:flx:NVDA" "keep"]]
             (click-actions (node-by-role node "portfolio-optimizer-universe-duplicate-keep-perp:flx:NVDA")))))))

(deftest kept-twins-render-as-a-linked-group-test
  (let [flx {:instrument-id "perp:flx:NVDA" :market-type :perp :coin "flx:NVDA"
             :dex "flx" :symbol "NVDA-USDH" :base "NVDA" :quote "USDH"
             :position-side :long}
        node (view {:universe [held-nvda held-sol flx]})
        header (node-by-role node "portfolio-optimizer-universe-twin-header-NVDA")]
    (is (some? header))
    (is (str/includes? (node-text header) "NVDA × 2 · same asset"))
    (is (= " · 1 duplicate"
           (node-text (node-by-role node "portfolio-optimizer-universe-twin-count"))))
    (is (= "NVDA"
           (get-in (node-by-role node "portfolio-optimizer-universe-selected-row-perp:flx:NVDA")
                   [1 :data-twin-of])))))
