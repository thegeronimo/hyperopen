(ns hyperopen.views.portfolio.optimize.execution-strategy-band-test
  "The staged-phase execution-strategy surface: honest tiles (Passive maker is a
  first-class strategy, Limit is not a bulk posture), live per-strategy cost
  projections, the high-cost-crossing warning with its one-click passive fix, and the
  counted Arm CTA."
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.test-support.hiccup :as h]
            [hyperopen.views.portfolio.optimize.execution-tab :as execution-tab]))

(def ^:private labels
  {"perp:EWZ" "EWZ" "perp:NOW" "NOW" "perp:REZ" "REZ"})

(def ^:private plan
  {:status :ready
   :execution-disabled? false
   :summary {:ready-count 2 :blocked-count 0 :skipped-count 1
             :gross-ready-notional-usd 127
             :margin {:after-utilization 0.1 :after-gross-leverage 1.1
                      :before-gross-leverage 1.0 :free-margin-usd 300
                      :capital-usd 320 :warning :none}}
   :rows [
          {:row-id "perp:EWZ" :instrument-id "perp:EWZ" :instrument-type :perp
           :status :ready :side :buy :quantity 2.47 :order-type :market
           :delta-notional-usd 87
           :cost {:source :snapshot :slippage-bps 45.4 :estimated-slippage-usd 0.4
                  :fee-bps 4.5 :estimated-fee-usd 0.04
                  :maker-fee-bps 1.5 :maker-fee-usd 0.01}}
          {:row-id "perp:NOW" :instrument-id "perp:NOW" :instrument-type :perp
           :status :ready :side :buy :quantity 0.38 :order-type :market
           :delta-notional-usd 40
           :cost {:source :snapshot :slippage-bps 1 :estimated-slippage-usd 0.01
                  :fee-bps 4.5 :estimated-fee-usd 0.02
                  :maker-fee-bps 1.5 :maker-fee-usd 0.005}}
          {:row-id "perp:REZ" :instrument-id "perp:REZ" :instrument-type :perp
           :status :skipped :side :sell :reason :within-tolerance :tolerance 0.03
           :quantity 2680.5 :delta-notional-usd -8.4}]})

(defn- view
  [modal-overrides]
  (execution-tab/execution-tab
   {:portfolio {:optimizer
                {:last-successful-run {:result {:labels-by-instrument labels}}
                 :execution-modal (merge {:open? true :plan plan :phase :staged
                                          :default-order-type :recommended
                                          :overrides {} :params {}}
                                         modal-overrides)
                 :execution {:status :idle :history []}}}}))

(deftest strategy-tiles-are-recommended-market-passive-twap-test
  (let [node (view nil)
        band (h/find-by-data-role node "portfolio-optimizer-execution-control-band")]
    (is (some? (h/find-by-data-role band "portfolio-optimizer-execution-mode-recommended")))
    (is (some? (h/find-by-data-role band "portfolio-optimizer-execution-mode-market")))
    (is (some? (h/find-by-data-role band "portfolio-optimizer-execution-mode-passive")))
    (is (some? (h/find-by-data-role band "portfolio-optimizer-execution-mode-twap")))
    (is (nil? (h/find-by-data-role band "portfolio-optimizer-execution-mode-limit")))
    (is (str/includes? (h/node-text band) "Execution strategy"))
    (is (not (str/includes? (h/node-text band) "Default order type")))))

(deftest strategy-tiles-project-live-costs-test
  (let [node (view nil)
        consequence (fn [id]
                      (h/node-text
                       (h/find-by-data-role
                        node (str "portfolio-optimizer-execution-mode-" id "-consequence"))))]
    (is (str/includes? (consequence "market") "$0.47"))
    (is (str/includes? (consequence "passive") "$0.02"))
    (is (str/includes? (consequence "recommended") "$0.04"))
    (is (str/includes? (consequence "recommended") "1 market"))
    (is (str/includes? (consequence "recommended") "1 passive maker"))))

(deftest high-cost-warning-absent-under-recommended-test
  (is (nil? (h/find-by-data-role (view nil)
                                 "portfolio-optimizer-execution-high-cost-warning"))))

(deftest high-cost-warning-under-market-all-offers-passive-fix-test
  (let [node (view {:default-order-type :market})
        warning (h/find-by-data-role node "portfolio-optimizer-execution-high-cost-warning")
        fix (h/find-by-data-role node "portfolio-optimizer-execution-rest-high-cost")]
    (is (some? warning))
    (is (str/includes? (h/node-text warning) "EWZ 45.4 bp"))
    (is (str/includes? (h/node-text warning) "cuts est. all-in cost from $0.47 to $0.04"))
    (is (some? fix))
    (is (= [[:actions/set-portfolio-optimizer-execution-row-order-type "perp:EWZ" :passive]]
           (get-in fix [1 :on :click])))))

(deftest high-cost-warning-respects-existing-passive-override-test
  (is (nil? (h/find-by-data-role (view {:default-order-type :market
                                        :overrides {"perp:EWZ" :passive}})
                                 "portfolio-optimizer-execution-high-cost-warning"))))

(deftest arm-cta-counts-the-orders-it-arms-test
  (let [arm (h/find-by-data-role (view nil) "portfolio-optimizer-execution-arm")]
    (is (some? arm))
    (is (str/includes? (h/node-text arm) "Arm 2 orders"))))

(def ^:private read-only-plan
  (assoc plan
         :execution-disabled? true
         :disabled-reason :read-only
         :disabled-message "Spectate Mode is read-only."))

(deftest read-only-view-keeps-strategy-tiles-interactive-test
  (let [node (view {:plan read-only-plan})
        band (h/find-by-data-role node "portfolio-optimizer-execution-control-band")
        market (h/find-by-data-role band "portfolio-optimizer-execution-mode-market")
        notice (h/find-by-data-role band "portfolio-optimizer-execution-readonly")
        arm (h/find-by-data-role node "portfolio-optimizer-execution-arm")]
    (is (= [[:actions/set-portfolio-optimizer-execution-default-order-type :market]]
           (get-in market [1 :on :click]))
        "the tile dispatches a strategy change")
    (is (nil? (get-in market [1 :disabled]))
        "the tile is not disabled in a read-only view")
    (is (some? notice))
    (is (str/includes? (h/node-text notice) "Spectate Mode is read-only."))
    (is (str/includes? (h/node-text notice) "still model execution strategies"))
    (is (true? (get-in arm [1 :disabled]))
        "Arm stays blocked — nothing can be sent from a read-only view")))

(deftest read-only-view-keeps-high-cost-passive-fix-clickable-test
  (let [node (view {:plan read-only-plan :default-order-type :market})
        fix (h/find-by-data-role node "portfolio-optimizer-execution-rest-high-cost")]
    (is (some? fix))
    (is (= [[:actions/set-portfolio-optimizer-execution-row-order-type "perp:EWZ" :passive]]
           (get-in fix [1 :on :click])))))

(def ^:private depth-labels {"perp:BIG" "BIG"})

(def ^:private depth-plan
  {:status :ready
   :execution-disabled? false
   :summary {:ready-count 1 :blocked-count 0 :skipped-count 0
             :gross-ready-notional-usd 100000
             :margin {:after-utilization 0.1 :after-gross-leverage 1.1
                      :before-gross-leverage 1.0 :free-margin-usd 300
                      :capital-usd 320 :warning :none}}
   :rows [{:row-id "perp:BIG" :instrument-id "perp:BIG" :instrument-type :perp
           :status :ready :side :sell :quantity 1000 :order-type :market
           :delta-notional-usd -100000
           :cost {:source :snapshot :slippage-bps 102 :estimated-slippage-usd 1020
                  :spread-bps 2 :spread-usd 20 :impact-bps 100 :impact-usd 1000
                  :notional-usd 100000
                  :fee-bps 4 :estimated-fee-usd 40
                  :maker-fee-bps 1 :maker-fee-usd 10}}]})

(defn- depth-view
  [modal-overrides]
  (execution-tab/execution-tab
   {:portfolio {:optimizer
                {:last-successful-run {:result {:labels-by-instrument depth-labels}}
                 :execution-modal (merge {:open? true :plan depth-plan :phase :staged
                                          :default-order-type :recommended
                                          :overrides {} :params {}}
                                         modal-overrides)
                 :execution {:status :idle :history []}}}}))

(deftest twap-tile-projects-below-market-on-splittable-books-test
  (let [node (depth-view nil)
        consequence (fn [id]
                      (h/node-text
                       (h/find-by-data-role
                        node (str "portfolio-optimizer-execution-mode-" id "-consequence"))))]
    (is (str/includes? (consequence "market") "$1,060"))
    (is (str/includes? (consequence "twap") "$230.73"))
    (is (not (str/includes? (consequence "twap") "$1,060")))))

(deftest high-cost-warning-judges-twap-rows-on-the-sliced-cost-test
  (is (some? (h/find-by-data-role (depth-view {:default-order-type :market})
                                  "portfolio-optimizer-execution-high-cost-warning")))
  (is (nil? (h/find-by-data-role (depth-view {:default-order-type :twap})
                                 "portfolio-optimizer-execution-high-cost-warning"))))

(deftest floor-estimates-mark-tiles-as-lower-bounds-test
  (let [floor-plan (update-in depth-plan [:rows 0 :cost] merge
                              {:estimate-floor? true
                               :depth-status :insufficient-visible-depth
                               :depth-overrun 409
                               :depth-coverage 0.0024
                               :visible-notional-usd 244})
        node (depth-view {:plan floor-plan})
        consequence (fn [id]
                      (h/node-text
                       (h/find-by-data-role
                        node (str "portfolio-optimizer-execution-mode-" id "-consequence"))))]
    (is (str/includes? (consequence "market") "≥ $1,060"))
    (is (str/includes? (consequence "twap") "≥ $"))))

(deftest passive-tile-never-prints-a-confident-zero-for-an-unknown-fee-test
  (let [feeless (update-in depth-plan [:rows 0 :cost] dissoc :maker-fee-bps :maker-fee-usd)
        node (depth-view {:plan feeless})
        consequence (fn [id]
                      (h/node-text
                       (h/find-by-data-role
                        node (str "portfolio-optimizer-execution-mode-" id "-consequence"))))]
    (is (str/includes? (consequence "passive") "≥ $0"))
    (is (not= "est. all-in $0" (consequence "passive")))
    (is (str/includes? (consequence "market") "$1,060"))
    (is (not (str/includes? (consequence "market") "≥")))))

(deftest twap-tile-discloses-when-there-is-no-book-to-slice-test
  (let [flat (assoc-in depth-plan [:rows 0 :cost]
                       {:source :fallback-bps :slippage-bps 25 :estimated-slippage-usd 250
                        :notional-usd 100000 :fee-bps 4 :estimated-fee-usd 40
                        :maker-fee-bps 1 :maker-fee-usd 10})
        consequence (fn [node id]
                      (h/node-text
                       (h/find-by-data-role
                        node (str "portfolio-optimizer-execution-mode-" id "-consequence"))))
        flat-node (depth-view {:plan flat})]
    (is (str/includes? (consequence flat-node "twap") "same as Market — no live book to slice"))
    (is (= (str/replace (consequence flat-node "twap") " · same as Market — no live book to slice" "")
           (consequence flat-node "market"))
        "the disclosure is honest: the two projections really are the same number")
    (is (not (str/includes? (consequence (depth-view nil) "twap") "no live book to slice")))))
