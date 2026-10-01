(ns hyperopen.views.funding-modal.transfer-qa-fixes-test
  "Regressions found by the Milestone 8 browser QA: text contrast in the run
   views' route header, design-system focus rings on the amount shortcuts,
   and the Balances tab header keeping the selected tab visible at 768 px."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.platform :as platform]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.funding-modal.transfer :as transfer]
            [hyperopen.views.funding-modal.transfer-parts :as parts]))

(defn- form
  [state]
  (with-redefs [platform/now-ms (constantly support/now-ms)]
    (transfer/render-content (:transfer (funding-actions/funding-modal-view-model state)))))

(defn- classes
  [node]
  (hiccup/node-class-set node))

(deftest route-header-cards-keep-full-opacity-test
  ;; At 75% opacity the cards' secondary text measured 3.64:1 against the
  ;; panel (WCAG AA needs 4.5:1 for 12 px text).
  (let [header (parts/route-header {:route {:from :hyperevm :to :spot}
                                    :balances {:from {:before "1,240.00 USDC"}
                                               :to {:before "2,105.40 USDC"}}})
        cards (filter #(contains? (classes %) "rounded-lg") (hiccup/node-children header))]
    (is (= 2 (count cards)))
    (doseq [card cards]
      (is (not-any? #(re-find #"^opacity-" %) (classes card))))))

(deftest amount-shortcuts-use-the-design-system-focus-ring-test
  (let [node (form (support/state {:transfer-from :spot :transfer-to :hyperevm
                                   :transfer-asset support/hype-index :amount-input "250"}))]
    (doseq [role ["funding-transfer-max" "funding-transfer-percent-25"
                  "funding-transfer-percent-50" "funding-transfer-percent-75"]]
      (testing role
        (let [cls (classes (hiccup/find-by-data-role node role))]
          (is (contains? cls "focus:outline-none"))
          (is (contains? cls "focus-visible:ring-2"))
          (is (contains? cls "focus-visible:ring-ho-accent/50")))))))

(defn- header-shell
  [nav]
  (hiccup/find-first-node nav #(and (vector? %)
                                    (contains? (classes %) "bg-base-200")
                                    (contains? (classes %) "flex-col"))))

(defn- strip-viewport
  [nav]
  (hiccup/find-first-node nav #(and (vector? %)
                                    (contains? (classes %) "overflow-x-auto")
                                    (contains? (classes %) "scrollbar-hide"))))

(deftest balances-actions-sit-under-the-tab-strip-until-lg-test
  ;; Beside the strip the Balances actions (about 530 px) left it about
  ;; 200 px at 768 px, which hid the selected tab.
  (let [balances (account-info-view/tab-navigation :balances {:balances 3} false {})
        positions (account-info-view/tab-navigation :positions {:positions 1} false {})]
    (testing "Balances stacks below lg"
      (let [shell (classes (header-shell balances))
            strip (classes (strip-viewport balances))]
        (is (contains? shell "lg:flex-row"))
        (is (contains? shell "lg:items-stretch"))
        (is (not (contains? shell "md:flex-row")))
        (is (contains? shell "md:min-h-12"))
        (is (contains? strip "lg:border-b-0"))
        (is (not (contains? strip "md:border-b-0")))))
    (testing "other tabs keep one row from md up"
      (let [shell (classes (header-shell positions))
            strip (classes (strip-viewport positions))]
        (is (contains? shell "md:flex-row"))
        (is (not (contains? shell "lg:flex-row")))
        (is (contains? strip "md:border-b-0"))))))
