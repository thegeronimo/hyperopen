(ns hyperopen.views.account-info.tabs.balances.review-fixes-test
  "Balances details the Milestone 9 review fixed: a partial HyperEVM read
   says so instead of \"No HyperEVM balances\", the location filter's short
   labels beside the tab strip keep their full accessible names, a disabled
   move's tooltip can be hovered, and an enabled mobile move shows focus by
   more than colour."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-transfer-preview]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.hyperevm.panel-slice :as panel-slice]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.account-info.projections.balances-hyperevm :as balances-hyperevm]
            [hyperopen.views.account-info.tabs.balances.location-filter :as location-filter]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]))

(def ^:private state (fixture/state))

(def ^:private partial-state
  "The owner's read left USDC's and PURR's chunk unanswered by every read."
  (fixture/with-evm-entry state (assoc fixture/evm-entry
                                       :token-units {}
                                       :unread-token-indexes [0 1]
                                       :never-read-token-indexes #{0 1})))

(defn- panel [state*] (account-info-view/account-info-panel state*))
(defn- by-role [node role] (hiccup/find-by-data-role node role))
(defn- class-set [node] (hiccup/node-class-set node))

(defn- shown-text
  [content role]
  (let [node (by-role content role)]
    (when (and node (not (contains? (class-set node) "hidden")))
      (apply str (hiccup/collect-strings node)))))

(def ^:private partial-message
  (:balances-partial evm-transfer-preview/messages))

(deftest a-partial-read-is-said-not-shown-as-empty-test
  (is (= :partial (balances-hyperevm/hyperevm-status partial-state)))
  (is (= :ready (balances-hyperevm/hyperevm-status state)))
  (is (= partial-message (location-filter/status-message :partial)))
  (testing "under All the note says so"
    (is (= partial-message (shown-text (panel partial-state) "balances-hyperevm-status"))))
  (testing "filtered to HyperEVM with nothing read, the empty state says so"
    (let [only-unread (fixture/with-evm-entry state (assoc fixture/evm-entry
                                                           :native-wei "0"
                                                           :token-units {}
                                                           :unread-token-indexes [0 1]
                                                           :never-read-token-indexes #{0 1}))
          content (panel (assoc-in only-unread [:account-info :balances-location-filter] :hyperevm))]
      (is (some #{partial-message} (hiccup/collect-strings content)))
      (is (not-any? #{"No HyperEVM balances."} (hiccup/collect-strings content)))))
  (testing "/trade's projected slice reads it the same way"
    (let [sliced (merge state (panel-slice/balances-panel-slice partial-state))]
      (is (= #{0 1} (get-in sliced [:hyperevm :balances :by-address fixture/owner
                                    :never-read-token-indexes])))
      (is (= :partial (balances-hyperevm/hyperevm-status sliced))))))

(deftest the-filter-keeps-full-names-behind-short-labels-test
  (let [control (location-filter/location-filter-control :all)
        options (filterv #(= :button (first %)) (hiccup/node-children control))
        by-id (into {} (map (fn [node] [(get-in node [1 :data-role]) node])) options)
        core (get by-id "balances-location-filter-hypercore")
        [long-span short-span] (filterv vector? (hiccup/node-children core))]
    (is (= "HyperCore" (get-in core [1 :aria-label]))
        "the accessible name is the long label, which contains the short one")
    (is (= "HyperEVM" (get-in (get by-id "balances-location-filter-hyperevm") [1 :aria-label])))
    (is (= #{"hidden" "sm:inline" "lg:hidden" "2xl:inline"} (class-set long-span))
        "long from 640 px until the header goes one row at lg, then again from 2xl")
    (is (= #{"sm:hidden" "lg:inline" "2xl:hidden"} (class-set short-span))
        "short beside the tab strip from lg to 2xl, where width is tabs")))

(deftest a-disabled-move-tooltip-can-be-hovered-test
  (let [content (panel state)
        reason (by-role content "balances-move-spot-6-hyperevm-reason")
        classes (class-set reason)]
    (is (= "tooltip" (get-in reason [1 :role])))
    (is (not (contains? classes "pointer-events-none"))
        "the pointer can move onto it without ending the hover (WCAG 1.4.13)")
    (is (every? classes ["invisible" "group-hover:visible" "group-focus-within:visible"])
        "closed, it catches no pointer over the rows around it")
    (is (some #(or (str/starts-with? % "pt-") (str/starts-with? % "pb-")) classes)
        "the gap to the action is the tooltip's own padding")
    (is (= [(:bridge-empty-evm evm-transfer-preview/messages)] (hiccup/collect-strings reason)))))

(deftest an-enabled-mobile-move-shows-focus-by-more-than-colour-test
  (let [content (panel (assoc-in state [:account-info :mobile-expanded-card] {:balances "spot-1"}))
        move (by-role content "balances-move-mobile-spot-1-hyperevm")]
    (is (some? move))
    (is (every? (class-set move) ["focus-visible:underline" "underline-offset-2"]))))
