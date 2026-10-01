(ns hyperopen.views.account-info.tabs.balances.hyperevm-view-test
  "The Balances tab with HyperEVM rows, rendered from app state through the
   real view-model: chips, the location filter, move actions, notes and the
   HyperEVM empty states."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-transfer-preview]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.account-info.tabs.balances.shared :as balances-shared]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]))

(def ^:private state (fixture/state))

(defn- panel
  ([] (panel state))
  ([state*]
   (account-info-view/account-info-panel state*)))

(defn- with-filter
  [state* location-filter]
  (assoc-in state* [:account-info :balances-location-filter] location-filter))

(defn- by-role
  [node role]
  (hiccup/find-by-data-role node role))

(defn- desktop-rows
  [node]
  (vec (hiccup/node-children (by-role node "account-tab-rows-viewport"))))

(defn- desktop-row
  "The desktop row holding a node with data-role `role`."
  [node role]
  (some #(when (by-role % role) %) (desktop-rows node)))

(defn- click-actions
  [node]
  (get-in node [1 :on :click]))

(defn- class-set
  [node]
  (hiccup/node-class-set node))

(deftest hyperevm-rows-render-with-their-chip-and-move-test
  (let [content (panel)
        hype-row (desktop-row content "balances-move-hyperevm-150-spot")
        to-spot (by-role content "balances-move-hyperevm-150-spot")]
    (is (some? hype-row))
    (is (some? (by-role hype-row "location-chip-hyperevm")) "the EVM chip sits in the coin cell")
    (is (= "Move HYPE from HyperEVM to Spot" (get-in to-spot [1 :aria-label])))
    (is (= "To Spot" (first (hiccup/collect-strings to-spot))))
    (is (= [[:actions/open-funding-transfer-modal
             :event.currentTarget/bounds
             "balances-move-hyperevm-150-spot"
             {:from :hyperevm :to :spot :asset 150}]]
           (click-actions to-spot)))
    (testing "HyperCore Send never acts on a HyperEVM balance"
      (is (nil? (hiccup/find-first-node hype-row #(and (= :button (first %))
                                                       (contains? (hiccup/direct-texts %) "Send"))))))
    (testing "the gas reserve is named under the available balance"
      (is (= "0.001 kept for gas"
             (first (hiccup/collect-strings (by-role hype-row "balance-row-gas-reserve-note"))))))
    (testing "the contract cell says Native for HYPE and links ERC-20s to hyperevmscan"
      (is (= ["Native"] (hiccup/collect-strings (by-role hype-row "balance-row-evm-contract"))))
      (let [purr-row (desktop-row content "balances-move-hyperevm-1-spot")
            link (hiccup/find-first-node purr-row #(= :a (first %)))]
        (is (= "https://hyperevmscan.io/token/0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
               (get-in link [1 :href])))))))

(deftest core-rows-offer-hyperevm-moves-beside-the-legacy-transfer-test
  (let [content (panel)
        to-perps (by-role content "balances-move-spot-0-perps")
        to-evm (by-role content "balances-move-spot-0-hyperevm")]
    (is (= [[:actions/open-funding-transfer-modal
             :event.currentTarget/bounds
             nil
             {:dex "" :to-perp? true}]]
           (click-actions to-perps))
        "the Perps <-> Spot move dispatches the legacy action unchanged")
    (is (= "To Perps" (first (hiccup/collect-strings to-perps))))
    (is (= [[:actions/open-funding-transfer-modal
             :event.currentTarget/bounds
             "balances-move-spot-0-hyperevm"
             {:from :spot :to :hyperevm :asset 0}]]
           (click-actions to-evm)))
    (is (nil? (by-role content "balances-move-perps-usdc-hyperevm"))
        "Perps -> HyperEVM goes through Spot")
    (is (nil? (by-role content "balances-move-spot-122-hyperevm"))
        "HOPE's broken link gets no move at all")))

(deftest a-disabled-move-names-its-reason-test
  (let [content (panel)
        to-evm (by-role content "balances-move-spot-6-hyperevm")
        reason-id "balances-move-spot-6-hyperevm-reason"
        reason-node (hiccup/find-first-node content #(= reason-id (get-in % [1 :id])))
        reason (:bridge-empty-evm evm-transfer-preview/messages)]
    (is (= "true" (get-in to-evm [1 :aria-disabled])))
    (is (= reason-id (get-in to-evm [1 :aria-describedby])))
    (is (nil? (click-actions to-evm)))
    (is (= [reason] (hiccup/collect-strings reason-node)))
    (testing "the reason is visible text on hover and while the action has keyboard focus"
      (is (= "tooltip" (get-in reason-node [1 :role])))
      (is (not (contains? (class-set reason-node) "sr-only")))
      (is (every? (class-set reason-node) ["group-hover:opacity-100" "group-focus-within:opacity-100"]))
      (is (nil? (get-in to-evm [1 :title])) "no second, native tooltip"))))

(deftest core-rows-show-their-place-only-beside-hyperevm-rows-test
  (let [content (panel)
        hype-core (desktop-row content "balances-move-spot-150-hyperevm")
        usdc-spot (desktop-row content "balances-move-spot-0-perps")
        usdc-perps (desktop-row content "balances-move-perps-usdc-spot")
        usdc-evm (desktop-row content "balances-move-hyperevm-0-spot")]
    (is (some? (by-role hype-core "location-chip-spot")))
    (testing "the three USDC rows, each labelled USDC, name their place"
      (is (some? (by-role usdc-spot "location-chip-spot")))
      (is (some? (by-role usdc-perps "location-chip-perps")))
      (is (some? (by-role usdc-evm "location-chip-hyperevm")))))
  (let [unified (-> state
                    (assoc :account {:mode :unified})
                    (assoc-in [:webdata2 :clearinghouseState :marginSummary :accountValue] "0"))
        pooled-usdc (desktop-row (panel unified) "balances-move-spot-0-hyperevm")]
    (is (some? pooled-usdc))
    (is (nil? (by-role pooled-usdc "location-chip-spot"))
        "a unified account's pooled USDC row is both places, so it gets no chip"))
  (let [no-evm (assoc-in state [:hyperevm :balances :by-address] {})
        hype-core (desktop-row (panel no-evm) "balances-move-spot-150-hyperevm")]
    (is (nil? (by-role hype-core "location-chip-spot"))
        "an account with nothing on HyperEVM keeps today's table"))
  (let [hype-core (desktop-row (panel (with-filter state :hypercore)) "balances-move-spot-150-hyperevm")]
    (is (nil? (by-role hype-core "location-chip-spot")) "a filtered table needs no place chips")))

(deftest location-filter-narrows-rows-and-shows-in-the-header-test
  (let [evm-only (panel (with-filter state :hyperevm))
        core-only (panel (with-filter state :hypercore))
        filter-group (by-role evm-only "balances-location-filter")
        pressed (fn [id] (get-in (by-role evm-only (str "balances-location-filter-" id))
                                 [1 :aria-pressed]))]
    (is (= 3 (count (desktop-rows evm-only))))
    (is (every? #(by-role % "location-chip-hyperevm") (desktop-rows evm-only)))
    (is (= 7 (count (desktop-rows core-only))))
    (is (not-any? #(by-role % "location-chip-hyperevm") (desktop-rows core-only)))
    (is (= "group" (get-in filter-group [1 :role])))
    (is (= ["false" "false" "true"] (mapv pressed ["all" "hypercore" "hyperevm"]))
        "strings, so the unpressed options keep aria-pressed in the DOM")
    (is (= [[:actions/set-balances-location-filter :hypercore]]
           (click-actions (by-role evm-only "balances-location-filter-hypercore"))))
    (testing "short labels below 640 px, long ones from 640 px"
      (let [option (by-role evm-only "balances-location-filter-hypercore")]
        (is (= ["HyperCore" "Core"] (hiccup/collect-strings option)))))))

(deftest hyperevm-filter-empty-states-test
  (let [message (fn [entry]
                  (let [state* (cond-> (with-filter state :hyperevm)
                                 true (assoc-in [:hyperevm :balances :by-address] {})
                                 entry (fixture/with-evm-entry entry))]
                    (hiccup/collect-strings (panel state*))))]
    (is (some #{"Checking HyperEVM balances…"} (message nil)))
    (is (some #{"HyperEVM balances are unavailable right now."}
              (message {:status :error :stale? false :error "down" :error-kind :network})))
    (is (some #{"No HyperEVM balances."}
              (message (assoc fixture/evm-entry :native-wei "0" :token-units {}))))
    (is (not (some #{"No data available"} (message nil)))
        "a HyperEVM empty state never claims there is no data")))

(deftest hyperevm-note-and-move-block-reason-test
  (let [note-classes (fn [content] (class-set (by-role content "balances-hyperevm-note")))
        blocked (fn [content] (by-role content "balances-hyperevm-moves-blocked"))]
    (testing "shown with HyperEVM rows, with no block for the connected master"
      (let [content (panel)]
        (is (contains? (note-classes content) "lg:block"))
        (is (contains? (class-set (blocked content)) "hidden"))))
    (testing "hidden for an account with nothing on HyperEVM"
      (let [content (panel (fixture/with-evm-entry state (assoc fixture/evm-entry
                                                                :native-wei "0"
                                                                :token-units {})))]
        (is (contains? (note-classes content) "hidden"))
        (is (not (contains? (note-classes content) "lg:block")))))
    (testing "spectate mode shows its rows read-only and says why nothing moves"
      (let [spectated "0x5555555555555555555555555555555555555555"
            spectating (-> state
                           (assoc :account-context {:spectate-mode {:active? true
                                                                    :address spectated}})
                           (hyperevm-balances/apply-loading {:addresses [spectated]
                                                             :requested-at-ms 5})
                           (hyperevm-balances/apply-success spectated 5
                                                            {:native-wei "2000000000000000000"
                                                             :token-units {}
                                                             :gas-price-wei fixture/gas-price-wei}
                                                            #{0 1}
                                                            6))
            content (panel spectating)]
        (is (some? (by-role content "location-chip-hyperevm")))
        (is (nil? (hiccup/find-first-node content
                                          #(str/starts-with? (str (get-in % [1 :data-role]))
                                                             "balances-move-")))
            "read-only tables have no move column")
        (is (= [account-context/spectate-mode-read-only-message]
               (hiccup/collect-strings (blocked content))))
        (is (not (contains? (class-set (blocked content)) "hidden")))))))

(deftest mobile-cards-carry-the-chip-and-their-own-move-roles-test
  (let [content (panel (assoc-in state [:account-info :mobile-expanded-card :balances] "hyperevm-150"))
        card (by-role content "mobile-balance-card-hyperevm-150")
        to-spot (by-role card "balances-move-mobile-hyperevm-150-spot")]
    (is (some? (by-role card "location-chip-hyperevm")))
    (is (= [[:actions/open-funding-transfer-modal
             :event.currentTarget/bounds
             "balances-move-mobile-hyperevm-150-spot"
             {:from :hyperevm :to :spot :asset 150}]]
           (click-actions to-spot)))
    (is (some? (by-role card "balance-row-gas-reserve-note")))
    (is (contains? (class-set (by-role content "balances-hyperevm-note-mobile")) "lg:hidden"))))

(deftest unpriced-hyperevm-token-shows-no-dollar-figure-test
  (let [unpriced (-> state
                     (update-in [:asset-selector :market-by-key] dissoc "spot:PURR")
                     (update-in [:spot :clearinghouse-state :balances]
                                (fn [rows] (vec (remove #(= "PURR" (:coin %)) rows)))))
        purr-row (desktop-row (panel unpriced) "balances-move-hyperevm-1-spot")
        usd-cell (nth (vec (hiccup/node-children purr-row)) 3)]
    (is (= ["--"] (hiccup/collect-strings usd-cell)))))

(deftest a-token-on-both-ledgers-lists-hypercore-first-on-ties-test
  (let [rows [{:key "hyperevm-150" :coin "HYPE" :location :hyperevm :usdc-value 10}
              {:key "spot-150" :coin "HYPE" :usdc-value 10}]]
    (is (= ["spot-150" "hyperevm-150"]
           (mapv :key (balances-shared/sort-balances-by-column rows "USDC Value" :asc))))
    (is (= ["spot-150" "hyperevm-150"]
           (mapv :key (balances-shared/sort-balances-by-column rows "USDC Value" :desc)))))
  (testing "the Coin sort keeps HyperEVM USDC after the HyperCore USDC rows"
    (let [rows [{:key "hyperevm-0" :coin "USDC" :location :hyperevm :usdc-value 1240}
                {:key "perps-usdc" :coin "USDC (Perps)" :usdc-value 800}
                {:key "spot-0" :coin "USDC (Spot)" :usdc-value 2105}
                {:key "spot-150" :coin "HYPE" :usdc-value 10}
                {:key "hyperevm-150" :coin "HYPE" :location :hyperevm :usdc-value 10}]
          sorted (fn [direction]
                   (mapv :key (balances-shared/sort-balances-by-column rows "Coin" direction)))]
      (is (= ["perps-usdc" "spot-0" "hyperevm-0" "spot-150" "hyperevm-150"] (sorted :asc)))
      (is (= ["spot-0" "perps-usdc" "hyperevm-0" "spot-150" "hyperevm-150"] (sorted :desc))
          "the HyperCore labels keep the sort's direction, as before"))))
