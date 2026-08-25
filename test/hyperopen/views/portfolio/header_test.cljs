(ns hyperopen.views.portfolio.header-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.portfolio.header :as header]))

(def ^:private trader-route-address
  "0x3333333333333333333333333333333333333333")

(deftest header-actions-renders-implemented-portfolio-action-buttons-test
  (let [view (header/header-actions {})
        actions-row (hiccup/find-by-data-role view "portfolio-actions-row")
        action-buttons (hiccup/find-all-nodes actions-row #(= :button (first %)))
        optimize (hiccup/find-by-data-role view "portfolio-action-optimize")
        link-staking (hiccup/find-by-data-role view "portfolio-action-link-staking")
        perps-spot (hiccup/find-by-data-role view "portfolio-action-perps-spot")
        withdraw (hiccup/find-by-data-role view "portfolio-action-withdraw")
        deposit (hiccup/find-by-data-role view "portfolio-action-deposit")]
    (is (contains? (set (hiccup/collect-strings view)) "Portfolio"))
    (is (= 5 (count action-buttons)))
    (is (= [[:actions/navigate "/portfolio/optimize"]]
           (get-in optimize [1 :on :click])))
    (is (= [[:actions/navigate "/staking"]]
           (get-in link-staking [1 :on :click])))
    (is (= [[:actions/open-funding-transfer-modal
             :event.currentTarget/bounds
             "portfolio-action-perps-spot"]]
           (get-in perps-spot [1 :on :click])))
    (is (= [[:actions/open-funding-withdraw-modal
             :event.currentTarget/bounds
             "portfolio-action-withdraw"]]
           (get-in withdraw [1 :on :click])))
    (is (= [[:actions/open-funding-deposit-modal
             :event.currentTarget/bounds
             "portfolio-action-deposit"]]
           (get-in deposit [1 :on :click])))
    (doseq [unimplemented-role ["portfolio-action-swap-stablecoins"
                                "portfolio-action-evm-core"
                                "portfolio-action-portfolio-margin"
                                "portfolio-action-send"]]
      (is (nil? (hiccup/find-by-data-role view unimplemented-role))))))

(deftest header-actions-add-focus-return-hook-for-matching-funding-button-test
  (let [view (header/header-actions {:funding-ui {:modal {:focus-return-data-role "portfolio-action-deposit"
                                                          :focus-return-token 5}}})
        deposit (hiccup/find-by-data-role view "portfolio-action-deposit")
        perps-spot (hiccup/find-by-data-role view "portfolio-action-perps-spot")]
    (is (fn? (get-in deposit [1 :replicant/on-render])))
    (is (= "focus-return:portfolio-action-deposit:5:true"
           (get-in deposit [1 :replicant/key])))
    (is (nil? (get-in perps-spot [1 :replicant/on-render])))))

(deftest portfolio-inspection-header-renders-read-only-trader-contract-test
  (let [view (header/portfolio-inspection-header
              {:router {:path (str "/portfolio/trader/" trader-route-address)}
               :leaderboard {:rows [{:eth-address trader-route-address
                                     :display-name "Gamma"}]}})
        inspection-summary (hiccup/find-by-data-role view "portfolio-inspection-summary")
        inspection-address (hiccup/find-by-data-role view "portfolio-inspection-address")
        own-portfolio-button (hiccup/find-by-data-role view "portfolio-inspection-own-portfolio")
        explorer-link (hiccup/find-by-data-role view "portfolio-inspection-explorer-link")
        all-text (set (hiccup/collect-strings view))]
    (is (contains? all-text "Trader View"))
    (is (contains? all-text "Read Only"))
    (is (some #(re-find #"Gamma" %) (hiccup/collect-strings inspection-summary)))
    (is (contains? (set (hiccup/collect-strings inspection-address))
                   trader-route-address))
    (is (= [[:actions/navigate "/portfolio"]]
           (get-in own-portfolio-button [1 :on :click])))
    (is (= "https://app.hyperliquid.xyz/explorer/address/0x3333333333333333333333333333333333333333"
           (get-in explorer-link [1 :href])))))

(deftest background-status-banner-renders-items-only-when-visible-test
  (let [hidden-banner (header/background-status-banner {:visible? false})
        visible-banner (header/background-status-banner
                        {:visible? true
                         :title "Portfolio analytics are still syncing"
                         :detail "The chart is ready. The remaining analytics will fill in automatically."
                         :items [{:id :benchmark-history
                                  :label "Benchmark history"}
                                 {:id :performance-metrics
                                  :label "Performance metrics"}]})
        benchmark-item (hiccup/find-by-data-role visible-banner "portfolio-background-status-item-benchmark-history")
        metrics-item (hiccup/find-by-data-role visible-banner "portfolio-background-status-item-performance-metrics")]
    ;; The region is ALWAYS present: it is a fixed, out-of-flow live region, so
    ;; the page above and below it is laid out identically whether there is
    ;; anything to report or not. Returning nil here used to make the banner an
    ;; in-flow sibling that shifted the whole page ~86px on the way in and back
    ;; again on the way out, twice per timeframe switch.
    (is (some? (hiccup/find-by-data-role hidden-banner "portfolio-background-status-region"))
        "the live region exists before any content is inserted into it")
    (is (nil? (hiccup/find-by-data-role hidden-banner "portfolio-background-status"))
        "but it holds no card while there is nothing to report")
    (is (contains? (set (into ["fixed"] (get-in hidden-banner [1 :class]))) "fixed")
        "and it is out of normal flow, so toggling it cannot move anything")
    (is (some? (hiccup/find-by-data-role visible-banner "portfolio-background-status")))
    (is (contains? (set (hiccup/collect-strings visible-banner))
                   "Portfolio analytics are still syncing"))
    (is (= "Benchmark history" (first (hiccup/collect-strings benchmark-item))))
    (is (= "Performance metrics" (first (hiccup/collect-strings metrics-item))))))

(deftest background-status-banner-spinner-is-compositor-driven-test
  ;; DaisyUI's `loading loading-spinner` animates via SMIL inside a mask-image,
  ;; which has no compositor path and therefore freezes for exactly as long as
  ;; the main thread is blocked. `.ho-spinner` animates `transform` only.
  (let [visible-banner (header/background-status-banner
                        {:visible? true
                         :title "Syncing"
                         :detail "Detail"
                         :items []})
        classes (set (mapcat (fn [node]
                               (when (and (vector? node) (map? (second node)))
                                 (get-in node [1 :class])))
                             (tree-seq coll? seq visible-banner)))]
    (is (contains? classes "ho-spinner"))
    (is (not (contains? classes "loading-spinner"))
        "the SMIL mask spinner must not come back")))
