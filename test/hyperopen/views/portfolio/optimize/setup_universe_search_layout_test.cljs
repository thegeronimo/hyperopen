(ns hyperopen.views.portfolio.optimize.setup-universe-search-layout-test
  "The design-1a search: every match reachable, symbols legible, facets honest."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.optimize.setup-layout-fixtures
             :refer [collect-strings node-by-role node-text click-actions]]))

(defn- perp
  [coin volume]
  {:key (str "perp:" coin) :market-type :perp :coin coin
   :symbol (str coin "-USDC") :base coin :quote "USDC" :volume24h volume})

(defn- spot
  [base quote volume]
  {:key (str "spot:" base "/" quote) :market-type :spot
   :coin (str base "/" quote) :symbol (str base "/" quote)
   :base base :quote quote :volume24h volume})

;; The scenario from the design brief: 13 "pump" markets, of which the old panel
;; showed six, with the perp below the cut and the three spot quotes clipped to
;; an identical "PUMP-US…".
(def ^:private pump-markets
  [(perp "PUMP" 412600000)
   (perp "HPUMP" 18200000)
   (spot "PUMP" "USDC" 96400000)
   (spot "PUMP" "USDE" 31800000)
   (spot "PUMP" "USDH" 7200000)
   (spot "UPUMP" "USDC" 12900000)
   (spot "UPUMP" "USDE" 3400000)
   (spot "HPUMP" "USDC" 5100000)
   (spot "HPUMP" "USDE" 1200000)
   (spot "PUMPX" "USDC" 600000)])

(defn- view
  ([] (view {}))
  ([{:keys [type-filter quote-filter]}]
   (portfolio-view/portfolio-view
    {:router {:path "/portfolio/optimize/new"}
     ;; Seed the REAL defaults (both facets are stored as the keyword :all), not an
     ;; absent key — a nil read took a different normalization path and hid a bug
     ;; that emptied the list in the browser.
     :portfolio-ui {:optimizer {:universe-search-query "pump"
                                :universe-search-type-filter (or type-filter :all)
                                :universe-search-quote-filter (or quote-filter :all)}}
     :portfolio {:optimizer {:draft {:universe []
                                     :constraints {:long-only? false}}}}
     :asset-selector {:markets pump-markets
                      :market-by-key (into {} (map (juxt :key identity)) pump-markets)}
     :vaults {:merged-index-rows [{:name "Pump Hunt"
                                   :vault-address "0xabc"
                                   :relationship {:type :normal}
                                   :tvl 8400000}]}})))

(defn- rows-present
  [node keys*]
  (mapv #(some? (node-by-role node (str "portfolio-optimizer-universe-candidate-row-" %)))
        keys*))

(deftest every-match-is-reachable-not-truncated-to-six-test
  (let [node (view)]
    (testing "all eleven matches render, including the ones past the old cut"
      (is (= [true true true true true true true true true true true]
             (rows-present node
                           ["perp:PUMP" "perp:HPUMP"
                            "spot:PUMP/USDC" "spot:PUMP/USDE" "spot:PUMP/USDH"
                            "spot:UPUMP/USDC" "spot:UPUMP/USDE"
                            "spot:HPUMP/USDC" "spot:HPUMP/USDE"
                            "spot:PUMPX/USDC" "vault:0xabc"]))))
    (testing "the count is stated rather than the overflow being silent"
      (is (= "11 hits"
             (node-text (node-by-role
                         node
                         "portfolio-optimizer-universe-search-match-count"))))
      (is (= "11 matches"
             (node-text (node-by-role
                         node
                         "portfolio-optimizer-universe-search-footer-count")))))
    (testing "the results live in a scroll container so the rail cannot grow unbounded"
      (let [scroll (node-by-role node "portfolio-optimizer-universe-search-results")]
        (is (contains? (set (get-in scroll [1 :class]))
                       "optimizer-universe-results-scroll"))
        (is (contains? (set (get-in scroll [1 :class])) "overflow-y-auto"))))))

(deftest quote-pairs-are-distinguishable-instead-of-clipping-to-the-same-row-test
  (let [node (view)
        text (node-text node)]
    ;; The whole reason for the redesign: these three used to render as
    ;; "PUMP-US…" and read as the same market.
    (is (str/includes? text "PUMP/USDC"))
    (is (str/includes? text "PUMP/USDE"))
    (is (str/includes? text "PUMP/USDH"))
    (testing "the perp keeps its own separator"
      (is (str/includes? text "PUMP-USDC")))))

(deftest results-are-grouped-by-type-so-the-perp-is-not-buried-test
  (let [node (view)]
    (is (some? (node-by-role node "portfolio-optimizer-universe-candidate-group-header-perp")))
    (is (some? (node-by-role node "portfolio-optimizer-universe-candidate-group-header-spot")))
    (is (some? (node-by-role node "portfolio-optimizer-universe-candidate-group-header-vault")))
    (testing "group headers carry their own counts"
      (is (str/includes?
           (node-text (node-by-role
                       node
                       "portfolio-optimizer-universe-candidate-group-header-perp"))
           "2 markets")))
    (testing "the group header role is disjoint from the row role prefix"
      ;; Two browser specs select rows with
      ;; [data-role^="portfolio-optimizer-universe-candidate-row-"].
      (is (not (str/starts-with?
                "portfolio-optimizer-universe-candidate-group-header-perp"
                "portfolio-optimizer-universe-candidate-row-"))))))

(deftest facets-render-with-counts-and-dispatch-filter-actions-test
  (let [node (view)
        all-chip (node-by-role node "portfolio-optimizer-universe-search-type-chip-all")
        perp-chip (node-by-role node "portfolio-optimizer-universe-search-type-chip-perp")
        usde-chip (node-by-role node "portfolio-optimizer-universe-search-quote-chip-USDE")]
    (is (str/includes? (node-text all-chip) "11"))
    (is (str/includes? (node-text perp-chip) "2"))
    (is (= [[:actions/set-portfolio-optimizer-universe-search-type-filter "perp"]]
           (click-actions perp-chip)))
    (testing "the quote facet is derived from the data, so USDE and USDH appear"
      (is (some? usde-chip))
      (is (some? (node-by-role
                  node
                  "portfolio-optimizer-universe-search-quote-chip-USDH")))
      (is (= [[:actions/set-portfolio-optimizer-universe-search-quote-filter "USDE"]]
             (click-actions usde-chip))))))

(deftest an-active-facet-narrows-the-list-and-the-footer-says-so-test
  (let [node (view {:type-filter :perp})]
    (is (= [true true] (rows-present node ["perp:PUMP" "perp:HPUMP"])))
    (is (= [false false] (rows-present node ["spot:PUMP/USDC" "vault:0xabc"])))
    (testing "the footer states shown-of-total rather than pretending nothing is hidden"
      (is (= "2 of 11 matches shown"
             (node-text (node-by-role
                         node
                         "portfolio-optimizer-universe-search-footer-count")))))
    (testing "a reset affordance appears once a facet is active"
      (is (some? (node-by-role
                  node
                  "portfolio-optimizer-universe-search-reset-filters"))))))

(deftest keyboard-market-keys-follow-the-grouped-render-order-test
  ;; The rendered row order, each row's DOM id index and the market-keys vector
  ;; handed to the keydown action must agree, or ArrowDown+Enter adds a different
  ;; asset than the one highlighted.
  (let [node (view {:type-filter :spot :quote-filter "USDE"})
        input (node-by-role node "portfolio-optimizer-universe-search-input")
        [[_action _key market-keys]] (get-in input [1 :on :keydown])]
    (is (= ["spot:PUMP/USDE" "spot:UPUMP/USDE" "spot:HPUMP/USDE"] market-keys))
    (testing "the filtered-out rows are absent from the keys, not merely hidden"
      (is (not (some #{"spot:PUMP/USDC"} market-keys))))))

(deftest add-all-dispatches-the-capped-batch-with-the-rendered-keys-test
  (let [node (view)
        add-all (node-by-role node "portfolio-optimizer-universe-search-add-all")
        [[action keys*]] (click-actions add-all)]
    (is (= "+ add all 11" (node-text add-all)))
    (is (= :actions/add-portfolio-optimizer-universe-matches action))
    (is (= 11 (count keys*)))))

(deftest keyboard-hints-are-present-test
  (let [strings (set (collect-strings (view)))]
    (is (contains? strings "↑↓ move"))
    (is (contains? strings "↵ add"))
    (is (contains? strings "esc clear"))))

(deftest an-over-filtered-set-explains-itself-test
  (let [node (view {:quote-filter "NOPE"})]
    (is (some? (node-by-role
                node
                "portfolio-optimizer-universe-search-results-empty")))
    (is (str/includes?
         (node-text (node-by-role
                     node
                     "portfolio-optimizer-universe-search-results-empty"))
         "try clearing the quote filter"))))
