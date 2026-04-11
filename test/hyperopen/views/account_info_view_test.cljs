(ns hyperopen.views.account-info-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info-view :as view]))

(def ^:private spectate-address
  "0x4444444444444444444444444444444444444444")

(defn- spectate-account-info-state
  [selected-tab]
  (-> fixtures/sample-account-info-state
      (assoc-in [:account-info :selected-tab] selected-tab)
      (assoc :account {:mode :classic})
      (assoc :account-context {:spectate-mode {:active? true
                                               :address spectate-address}})))

(defn- spectate-balances-state []
  (-> (spectate-account-info-state :balances)
      (assoc :asset-selector {:market-by-key {"spot:MEOW/USDC" {:coin "MEOW/USDC"
                                                                :mark 0.02}}})
      (assoc :spot {:meta {:tokens [{:index 0 :name "USDC" :weiDecimals 6}
                                    {:index 1 :name "MEOW" :weiDecimals 6}]
                           :universe [{:name "MEOW/USDC"
                                       :tokens [1 0]
                                       :index 0}]}
                    :clearinghouse-state {:balances [{:coin "MEOW"
                                                      :token 1
                                                      :hold "0.0"
                                                      :total "2.0"
                                                      :entryNtl "0.03"}]}})))

(defn- spectate-positions-state []
  (-> (spectate-account-info-state :positions)
      (assoc :webdata2 {:clearinghouseState {:assetPositions [fixtures/sample-position-data]}})))

(defn- spectate-open-orders-state []
  (-> (spectate-account-info-state :open-orders)
      (assoc-in [:orders :open-orders]
                [{:coin "BTC"
                  :oid 101
                  :side "B"
                  :sz "1.0"
                  :origSz "1.0"
                  :limitPx "100.0"
                  :orderType "Limit"
                  :timestamp 1700000000000
                  :reduceOnly false
                  :isTrigger false
                  :isPositionTpsl false}])
      (assoc :asset-selector {:market-by-key {"perp:BTC" {:coin "BTC"
                                                          :symbol "BTC"}}})))

(defn- spectate-twap-state []
  (-> (spectate-account-info-state :twap)
      (assoc :orders {:open-orders []
                      :open-orders-snapshot []
                      :open-orders-snapshot-by-dex {}
                      :order-history []
                      :twap-states [[17 {:coin "xyz:CL"
                                         :side "B"
                                         :sz "1.0"
                                         :executedSz "0.4"
                                         :executedNtl "40.0"
                                         :minutes 30
                                         :timestamp 1700000000000
                                         :reduceOnly false}]]})))

(defn- disconnected-cleared-account-info-state
  [selected-tab]
  (-> fixtures/sample-account-info-state
      (assoc-in [:account-info :selected-tab] selected-tab)
      (assoc :wallet {:connected? false
                      :address nil}
             :webdata2 nil
             :spot {:meta nil
                    :clearinghouse-state nil}
             :account {:mode :classic
                       :abstraction-raw nil}
             :perp-dex-clearinghouse {}
             :orders {:open-orders []
                      :open-orders-hydrated? false
                      :open-orders-snapshot []
                      :open-orders-snapshot-by-dex {}
                      :fills []
                      :fundings-raw []
                      :fundings []
                      :order-history []
                      :ledger []
                      :twap-states []
                      :twap-history []
                      :twap-slice-fills []
                      :pending-cancel-oids nil})))

(defn- button-with-text
  [node text]
  (hiccup/find-first-node node #(and (= :button (first %))
                                     (contains? (hiccup/direct-texts %) text))))

(deftest account-info-panel-keeps-a-stable-default-height-across-standard-tabs-test
  (let [balances-panel (view/account-info-panel fixtures/sample-account-info-state)
        positions-panel (view/account-info-panel (assoc-in fixtures/sample-account-info-state
                                                          [:account-info :selected-tab]
                                                          :positions))
        balances-classes (hiccup/node-class-set balances-panel)
        positions-classes (hiccup/node-class-set positions-panel)
        content-node (second (vec (hiccup/node-children balances-panel)))
        content-classes (hiccup/node-class-set content-node)]
    (is (contains? balances-classes "h-96"))
    (is (contains? balances-classes "lg:h-[29rem]"))
    (is (contains? positions-classes "h-96"))
    (is (contains? positions-classes "lg:h-[29rem]"))
    (is (contains? balances-classes "flex"))
    (is (contains? balances-classes "flex-col"))
    (is (contains? balances-classes "min-h-0"))
    (is (contains? balances-classes "overflow-hidden"))
    (is (contains? content-classes "flex-1"))
    (is (contains? content-classes "min-h-0"))
    (is (contains? content-classes "overflow-hidden"))))

(deftest account-info-panel-renders-twap-tab-content-instead-of-placeholder-test
  (let [state {:account-info {:selected-tab :twap}
               :orders {:twap-states [[17 {:coin "BTC"
                                           :side "B"
                                           :sz "1.0"
                                           :executedSz "0.4"
                                           :executedNtl "40.0"
                                           :minutes 30
                                           :timestamp 1700000000000
                                           :reduceOnly false}]]}
               :spot {:meta nil
                      :clearinghouse-state nil}
               :account {:mode :classic}
               :perp-dex-clearinghouse {}}
        panel (view/account-info-panel state)
        strings (set (hiccup/collect-strings panel))]
    (is (contains? strings "Terminate"))
    (is (contains? strings "Active (1)"))
    (is (not (contains? strings "TWAP coming soon")))))

(deftest format-pnl-percentage-renders-signed-and-neutral-states-test
  (let [positive (view/format-pnl-percentage "1.234")
        negative (view/format-pnl-percentage "-0.5")
        rounded-zero (view/format-pnl-percentage "-0.004")
        invalid (view/format-pnl-percentage "oops")
        na-value (view/format-pnl-percentage "N/A")
        nil-value (view/format-pnl-percentage nil)]
    (is (= "text-success" (get-in positive [1 :class])))
    (is (= "+1.23%" (nth positive 2)))
    (is (= "text-error" (get-in negative [1 :class])))
    (is (= "-0.50%" (nth negative 2)))
    (is (= "text-base-content" (get-in rounded-zero [1 :class])))
    (is (= "0.00%" (nth rounded-zero 2)))
    (is (= "text-base-content" (get-in invalid [1 :class])))
    (is (= "0.00%" (nth invalid 2)))
    (is (= "text-base-content" (get-in na-value [1 :class])))
    (is (= "0.00%" (nth na-value 2)))
    (is (= "text-base-content" (get-in nil-value [1 :class])))
    (is (= "0.00%" (nth nil-value 2)))))

(deftest format-pnl-percentage-handles-borderline-rounding-boundaries-test
  (let [positive-boundary (view/format-pnl-percentage "1.005")
        negative-boundary (view/format-pnl-percentage "-0.005")]
    (is (= "text-success" (get-in positive-boundary [1 :class])))
    (is (= "+1.00%" (nth positive-boundary 2)))
    (is (= "text-base-content" (get-in negative-boundary [1 :class])))
    (is (= "0.00%" (nth negative-boundary 2)))))

(deftest account-info-panel-applies-selected-extra-tab-panel-sizing-overrides-test
  (let [panel (view/account-info-panel
               {:account-info {:selected-tab :performance-metrics}}
               {:extra-tabs [{:id :performance-metrics
                              :label "Performance Metrics"
                              :panel-classes ["min-h-96"]
                              :panel-style {:max-height "min(44rem, calc(100dvh - 22rem))"}
                              :content [:div "Metrics"]}]})
        panel-classes (hiccup/node-class-set panel)
        panel-style (get-in panel [1 :style])]
    (is (contains? panel-classes "min-h-96"))
    (is (not (contains? panel-classes "h-96")))
    (is (= "min(44rem, calc(100dvh - 22rem))"
           (:max-height panel-style)))))

(deftest account-info-panel-allows-default-panel-class-overrides-test
  (let [panel (view/account-info-panel fixtures/sample-account-info-state
                                       {:default-panel-classes ["h-full"]})
        panel-classes (hiccup/node-class-set panel)]
    (is (contains? panel-classes "h-full"))
    (is (not (contains? panel-classes "h-96")))
    (is (not (contains? panel-classes "lg:h-[29rem]")))))

(deftest account-info-panel-composes-spectate-read-only-state-into-shared-tab-content-test
  (doseq [{:keys [label panel required-text forbidden-texts forbidden-buttons forbidden-aria-labels]}
          [{:label "balances"
            :panel (view/account-info-panel (spectate-balances-state))
            :required-text "MEOW"
            :forbidden-texts ["Send" "Transfer" "Repay"]}
           {:label "positions"
            :panel (view/account-info-panel (spectate-positions-state))
            :required-text "HYPE"
            :forbidden-texts ["Close All"]
            :forbidden-buttons ["Reduce"]
            :forbidden-aria-labels ["Edit Margin" "Edit TP/SL"]}
           {:label "open orders"
            :panel (view/account-info-panel (spectate-open-orders-state))
            :required-text "BTC"
            :forbidden-texts ["Cancel All"]
            :forbidden-buttons ["Cancel"]}
           {:label "twap"
            :panel (view/account-info-panel (spectate-twap-state))
            :required-text "Active (1)"
            :forbidden-texts ["Terminate"]}]]
    (let [strings (set (hiccup/collect-strings panel))]
      (is (contains? strings required-text) label)
      (doseq [forbidden-text forbidden-texts]
        (is (not (contains? strings forbidden-text))
            (str label " omits " forbidden-text)))
      (doseq [forbidden-button forbidden-buttons]
        (is (nil? (button-with-text panel forbidden-button))
            (str label " omits button " forbidden-button)))
      (doseq [aria-label forbidden-aria-labels]
        (is (nil? (hiccup/find-first-node panel #(= aria-label (get-in % [1 :aria-label]))))
            (str label " omits aria-label " aria-label))))))

(deftest account-info-panel-renders-empty-disconnected-surfaces-after-account-reset-test
  (doseq [{:keys [label selected-tab expected-text]} [{:label "balances"
                                                       :selected-tab :balances
                                                       :expected-text "No balance data available"}
                                                      {:label "positions"
                                                       :selected-tab :positions
                                                       :expected-text "No active positions"}
                                                      {:label "open orders"
                                                       :selected-tab :open-orders
                                                       :expected-text "No open orders"}]]
    (let [panel (view/account-info-panel (disconnected-cleared-account-info-state selected-tab))
          strings (set (hiccup/collect-strings panel))]
      (is (contains? strings expected-text) label))))
