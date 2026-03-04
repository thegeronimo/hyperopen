(ns hyperopen.core-bootstrap.order-entry-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.core.compat :as core]
            [hyperopen.order.actions :as order-actions]
            [hyperopen.state.trading :as trading]
            [hyperopen.core-bootstrap.test-support.effect-extractors :as effect-extractors]))

(def extract-saved-order-form effect-extractors/extract-saved-order-form)
(def extract-saved-order-form-ui effect-extractors/extract-saved-order-form-ui)

(defn- extract-save-many-path-values
  [effects]
  (into {} (second (first effects))))

(deftest select-order-entry-mode-market-emits-single-batched-projection-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :type :stop-market)
               :order-form-ui {:entry-mode :pro
                               :pro-order-type-dropdown-open? true}}
        effects (core/select-order-entry-mode state :market)
        saved-form (extract-saved-order-form effects)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (map? saved-form))
    (is (= :market (:type saved-form)))
    (is (= :market (:entry-mode saved-ui)))
    (is (= false (:pro-order-type-dropdown-open? saved-ui)))))

(deftest select-order-entry-mode-limit-forces-limit-type-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :type :stop-limit)
               :order-form-ui {:entry-mode :pro
                               :pro-order-type-dropdown-open? true}}
        effects (core/select-order-entry-mode state :limit)
        saved-form (extract-saved-order-form effects)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (map? saved-form))
    (is (= :limit (:type saved-form)))
    (is (= :limit (:entry-mode saved-ui)))
    (is (= false (:pro-order-type-dropdown-open? saved-ui)))))

(deftest select-order-entry-mode-pro-sets-pro-entry-and-normalized-pro-type-test
  (let [state {:order-form (assoc (trading/default-order-form) :type :limit)}
        effects (core/select-order-entry-mode state :pro)
        saved-form (extract-saved-order-form effects)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (map? saved-form))
    (is (= :stop-market (:type saved-form)))
    (is (= :pro (:entry-mode saved-ui)))))

(deftest select-pro-order-type-closes-dropdown-and-persists-pro-selection-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :type :stop-market)
               :order-form-ui {:entry-mode :pro
                               :pro-order-type-dropdown-open? true}}
        effects (core/select-pro-order-type state :scale)
        saved-form (extract-saved-order-form effects)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= :pro (:entry-mode saved-ui)))
    (is (= :scale (:type saved-form)))
    (is (= false (:pro-order-type-dropdown-open? saved-ui)))))

(deftest toggle-pro-order-type-dropdown-flips-open-flag-test
  (let [closed-state {:order-form (trading/default-order-form)
                      :order-form-ui {:pro-order-type-dropdown-open? false}}
        open-state {:order-form (trading/default-order-form)
                    :order-form-ui {:pro-order-type-dropdown-open? true}}
        closed-effects (core/toggle-pro-order-type-dropdown closed-state)
        open-effects (core/toggle-pro-order-type-dropdown open-state)]
    (is (= [[:effects/save-many [[[:order-form-ui :pro-order-type-dropdown-open?] true]]]]
           closed-effects))
    (is (= [[:effects/save-many [[[:order-form-ui :pro-order-type-dropdown-open?] false]]]]
           open-effects))))

(deftest close-pro-order-type-dropdown-forces-open-flag-false-test
  (let [state {:order-form (trading/default-order-form)
               :order-form-ui {:pro-order-type-dropdown-open? true}}
        effects (core/close-pro-order-type-dropdown state)]
    (is (= [[:effects/save-many [[[:order-form-ui :pro-order-type-dropdown-open?] false]]]]
           effects))))

(deftest handle-pro-order-type-dropdown-keydown-closes-only-on-escape-test
  (let [state {:order-form (trading/default-order-form)
               :order-form-ui {:pro-order-type-dropdown-open? true}}
        escape-effects (core/handle-pro-order-type-dropdown-keydown state "Escape")
        enter-effects (core/handle-pro-order-type-dropdown-keydown state "Enter")]
    (is (= [[:effects/save-many [[[:order-form-ui :pro-order-type-dropdown-open?] false]]]]
           escape-effects))
    (is (= [] enter-effects))))

(deftest margin-mode-and-leverage-popover-actions-emit-projections-test
  (let [base-state {:active-market {:coin "BTC" :market-type :perp :maxLeverage 40}
                    :order-form (assoc (trading/default-order-form)
                                       :type :limit)
                    :order-form-ui (assoc (trading/default-order-form-ui)
                                          :margin-mode-dropdown-open? false
                                          :leverage-popover-open? false
                                          :leverage-draft 25)}
        toggled-margin (extract-save-many-path-values
                        (order-actions/toggle-margin-mode-dropdown base-state))
        closed-margin (extract-save-many-path-values
                       (order-actions/close-margin-mode-dropdown
                        (assoc-in base-state [:order-form-ui :margin-mode-dropdown-open?] true)))
        escape-margin (extract-save-many-path-values
                       (order-actions/handle-margin-mode-dropdown-keydown
                        (assoc-in base-state [:order-form-ui :margin-mode-dropdown-open?] true)
                        "Escape"))
        open-leverage-state (assoc base-state
                                   :order-form-ui
                                   (assoc (:order-form-ui base-state)
                                          :margin-mode-dropdown-open? true
                                          :leverage-popover-open? false
                                          :leverage-draft 27))
        toggled-leverage (extract-save-many-path-values
                          (order-actions/toggle-leverage-popover open-leverage-state))
        closed-leverage (extract-save-many-path-values
                         (order-actions/close-leverage-popover
                          (assoc-in open-leverage-state [:order-form-ui :leverage-popover-open?] true)))
        escape-leverage (extract-save-many-path-values
                         (order-actions/handle-leverage-popover-keydown
                          (assoc-in open-leverage-state [:order-form-ui :leverage-popover-open?] true)
                          "Escape"))
        set-draft (extract-save-many-path-values
                   (order-actions/set-order-ui-leverage-draft base-state 33))
        confirmed-ui (extract-saved-order-form-ui
                      (order-actions/confirm-order-ui-leverage
                       (assoc base-state
                              :order-form-ui
                              (assoc (:order-form-ui base-state)
                                     :leverage-popover-open? true
                                     :leverage-draft 33))))]
    (is (= true (get toggled-margin [:order-form-ui :margin-mode-dropdown-open?])))
    (is (= false (get toggled-margin [:order-form-ui :leverage-popover-open?])))
    (is (= 20 (get toggled-margin [:order-form-ui :leverage-draft])))
    (is (= false (get closed-margin [:order-form-ui :margin-mode-dropdown-open?])))
    (is (= false (get escape-margin [:order-form-ui :margin-mode-dropdown-open?])))
    (is (= [] (order-actions/handle-margin-mode-dropdown-keydown base-state "Enter")))
    (is (= false (get toggled-leverage [:order-form-ui :margin-mode-dropdown-open?])))
    (is (= true (get toggled-leverage [:order-form-ui :leverage-popover-open?])))
    (is (= 27 (get toggled-leverage [:order-form-ui :leverage-draft])))
    (is (= false (get closed-leverage [:order-form-ui :leverage-popover-open?])))
    (is (= 20 (get closed-leverage [:order-form-ui :leverage-draft])))
    (is (= false (get escape-leverage [:order-form-ui :leverage-popover-open?])))
    (is (= [] (order-actions/handle-leverage-popover-keydown base-state "Enter")))
    (is (= true (get set-draft [:order-form-ui :leverage-popover-open?])))
    (is (= 33 (get set-draft [:order-form-ui :leverage-draft])))
    (is (= false (:leverage-popover-open? confirmed-ui)))
    (is (= 33 (:leverage-draft confirmed-ui)))
    (is (= 33 (:ui-leverage confirmed-ui)))))

(deftest size-tpsl-and-tif-dropdown-actions-handle-open-close-and-escape-test
  (let [base-state {:order-form (assoc (trading/default-order-form)
                                       :type :limit)
                    :order-form-ui (assoc (trading/default-order-form-ui)
                                          :size-unit-dropdown-open? false
                                          :tpsl-panel-open? true
                                          :tpsl-unit-dropdown-open? false
                                          :tif-dropdown-open? false)}
        toggled-size (extract-save-many-path-values
                      (order-actions/toggle-size-unit-dropdown base-state))
        closed-size (extract-save-many-path-values
                     (order-actions/close-size-unit-dropdown
                      (assoc-in base-state [:order-form-ui :size-unit-dropdown-open?] true)))
        escape-size (extract-save-many-path-values
                     (order-actions/handle-size-unit-dropdown-keydown
                      (assoc-in base-state [:order-form-ui :size-unit-dropdown-open?] true)
                      "Escape"))
        tpsl-closed-state (assoc-in base-state [:order-form-ui :tpsl-panel-open?] false)
        toggled-tpsl-closed (extract-save-many-path-values
                             (order-actions/toggle-tpsl-unit-dropdown tpsl-closed-state))
        toggled-tpsl-open (extract-save-many-path-values
                           (order-actions/toggle-tpsl-unit-dropdown base-state))
        closed-tpsl (extract-save-many-path-values
                     (order-actions/close-tpsl-unit-dropdown
                      (assoc-in base-state [:order-form-ui :tpsl-unit-dropdown-open?] true)))
        escape-tpsl (extract-save-many-path-values
                     (order-actions/handle-tpsl-unit-dropdown-keydown
                      (assoc-in base-state [:order-form-ui :tpsl-unit-dropdown-open?] true)
                      "Escape"))
        toggled-tif (extract-save-many-path-values
                     (order-actions/toggle-tif-dropdown base-state))
        closed-tif (extract-save-many-path-values
                    (order-actions/close-tif-dropdown
                     (assoc-in base-state [:order-form-ui :tif-dropdown-open?] true)))
        escape-tif (extract-save-many-path-values
                    (order-actions/handle-tif-dropdown-keydown
                     (assoc-in base-state [:order-form-ui :tif-dropdown-open?] true)
                     "Escape"))]
    (is (= true (get toggled-size [:order-form-ui :size-unit-dropdown-open?])))
    (is (= false (get closed-size [:order-form-ui :size-unit-dropdown-open?])))
    (is (= false (get escape-size [:order-form-ui :size-unit-dropdown-open?])))
    (is (= [] (order-actions/handle-size-unit-dropdown-keydown base-state "Enter")))
    (is (= false (get toggled-tpsl-closed [:order-form-ui :tpsl-unit-dropdown-open?])))
    (is (= true (get toggled-tpsl-open [:order-form-ui :tpsl-unit-dropdown-open?])))
    (is (= false (get closed-tpsl [:order-form-ui :tpsl-unit-dropdown-open?])))
    (is (= false (get escape-tpsl [:order-form-ui :tpsl-unit-dropdown-open?])))
    (is (= [] (order-actions/handle-tpsl-unit-dropdown-keydown base-state "Enter")))
    (is (= true (get toggled-tif [:order-form-ui :tif-dropdown-open?])))
    (is (= false (get closed-tif [:order-form-ui :tif-dropdown-open?])))
    (is (= false (get escape-tif [:order-form-ui :tif-dropdown-open?])))
    (is (= [] (order-actions/handle-tif-dropdown-keydown base-state "Enter")))))

(deftest order-ui-setters-and-update-order-form-emit-single-batch-save-many-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :market-type :perp :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :side :buy
                                  :price "100"
                                  :size "1")
               :order-form-ui (assoc (trading/default-order-form-ui)
                                     :margin-mode :cross
                                     :size-input-mode :base)}
        leverage-ui (extract-saved-order-form-ui (order-actions/set-order-ui-leverage state 31))
        margin-ui (extract-saved-order-form-ui (order-actions/set-order-margin-mode state :isolated))
        input-mode-ui (extract-saved-order-form-ui (order-actions/set-order-size-input-mode state :quote))
        updated-form (extract-saved-order-form (order-actions/update-order-form state [:price] "101.5"))
        blocked-update-paths (->> (order-actions/update-order-form state [:margin-mode] :isolated)
                                  first
                                  second
                                  (map first)
                                  set)]
    (is (= 31 (:ui-leverage leverage-ui)))
    (is (= 31 (:leverage-draft leverage-ui)))
    (is (= :isolated (:margin-mode margin-ui)))
    (is (= :quote (:size-input-mode input-mode-ui)))
    (is (= "101.5" (:price updated-form)))
    (is (contains? blocked-update-paths [:order-form-runtime]))
    (is (not (contains? blocked-update-paths [:order-form])))))

(deftest toggle-order-tpsl-panel-noops-for-scale-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :type :scale)
               :order-form-ui {:entry-mode :pro
                               :tpsl-panel-open? false}}
        effects (core/toggle-order-tpsl-panel state)]
    (is (= [] effects))))

(deftest set-order-size-percent-emits-single-batched-projection-and-no-network-effects-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 100 :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                               :totalMarginUsed "250"}}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "100")}
        effects (core/set-order-size-percent state 25)
        saved-form (-> effects first second first second)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= 25 (:size-percent saved-form)))
    (is (not-any? #(= (first %) :effects/api-submit-order) effects))
    (is (not-any? #(= (first %) :effects/subscribe-orderbook) effects))))

(deftest set-order-size-display-preserves-user-entered-value-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 100 :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                               :totalMarginUsed "250"}}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "")}
        effects (core/set-order-size-display state "202")
        saved-form (-> effects first second first second)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= "202" (:size-display saved-ui)))
    (is (= "2" (:size saved-form)))
    (is (not-any? #(= (first %) :effects/api-submit-order) effects))))

(deftest set-order-size-display-truncates-canonical-size-to-market-decimals-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 70179 :maxLeverage 40 :szDecimals 5}
               :orderbooks {"BTC" {:bids [{:px "70150"}]
                                   :asks [{:px "70160"}]}}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                               :totalMarginUsed "250"}}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "70179")}
        effects (core/set-order-size-display state "2")
        saved-form (-> effects first second first second)
        saved-ui (extract-saved-order-form-ui effects)
        summary (trading/order-summary state saved-form)]
    (is (= "2" (:size-display saved-ui)))
    (is (= "0.00002" (:size saved-form)))
    (is (<= (js/Math.abs (- 1.4 (:order-value summary))) 0.01))))

(deftest focus-order-price-input-locks-price-and-captures-current-fallback-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 70000 :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "70120"} {:px "70150"} {:px "70090"}]
                                   :asks [{:px "70240"} {:px "70160"} {:px "70210"}]}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "")
               :order-form-ui {:price-input-focused? false}}
        effects (core/focus-order-price-input state)
        saved-form (extract-saved-order-form effects)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= true (:price-input-focused? saved-ui)))
    (is (= "70155" (:price saved-form)))))

(deftest focus-order-price-input-does-not-overwrite-manual-price-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 70000 :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "70120"}]
                                   :asks [{:px "70160"}]}}
               :order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :price "70133.5")
               :order-form-ui {:price-input-focused? false}}
        effects (core/focus-order-price-input state)
        saved-form (extract-saved-order-form effects)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= true (:price-input-focused? saved-ui)))
    (is (= "70133.5" (:price saved-form)))))

(deftest blur-order-price-input-releases-focus-lock-without-mutating-price-test
  (let [state {:order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :price "70155")
               :order-form-ui {:price-input-focused? true}}
        effects (core/blur-order-price-input state)
        saved-ui (extract-saved-order-form-ui effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= false (:price-input-focused? saved-ui)))))

(deftest set-order-price-to-mid-uses-best-bid-ask-midpoint-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :mark 70000 :maxLeverage 40 :szDecimals 4}
               :orderbooks {"BTC" {:bids [{:px "70120"} {:px "70150"} {:px "70090"}]
                                   :asks [{:px "70240"} {:px "70160"} {:px "70210"}]}}
               :order-form (assoc (trading/default-order-form) :type :limit :price "")}
        effects (core/set-order-price-to-mid state)
        saved-form (-> effects first second first second)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))
    (is (= "70155" (:price saved-form)))
    (is (not-any? #(= (first %) :effects/api-submit-order) effects))))

(deftest submit-order-emits-single-api-submit-order-effect-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :market-type :perp}
               :asset-contexts {:BTC {:idx 0}}
               :wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :ready
                                :storage-mode :session
                                :agent-address "0xagent"}}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :side :buy
                                  :size "1"
                                  :price "100")}
        effects (core/submit-order state)
        api-submit-effects (filter #(= (first %) :effects/api-submit-order) effects)
        request (second (first api-submit-effects))
        pre-action (first (:pre-actions request))]
    (is (= 1 (count api-submit-effects)))
    (is (= "updateLeverage" (:type pre-action)))
    (is (= 0 (:asset pre-action)))
    (is (= true (:isCross pre-action)))
    (is (= 20 (:leverage pre-action)))))

(deftest submit-order-limit-with-blank-price-uses-fallback-and-emits-single-submit-effect-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :market-type :perp}
               :asset-contexts {:BTC {:idx 0}}
               :wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :ready
                                :storage-mode :session
                                :agent-address "0xagent"}}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :side :buy
                                  :size "1"
                                  :price "")}
        effects (core/submit-order state)
        api-submit-effects (filter #(= (first %) :effects/api-submit-order) effects)
        saved-form (some (fn [effect]
                           (when (and (= :effects/save (first effect))
                                      (= [:order-form] (second effect)))
                             (nth effect 2)))
                         effects)]
    (is (= 1 (count api-submit-effects)))
    (is (seq (:price saved-form)))))

(deftest submit-order-includes-isolated-margin-mode-pre-submit-action-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :market-type :perp}
               :asset-contexts {:BTC {:idx 0}}
               :wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :ready
                                :storage-mode :session
                                :agent-address "0xagent"}}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :side :buy
                                  :size "1"
                                  :price "100")
               :order-form-ui (assoc (trading/default-order-form-ui)
                                     :margin-mode :isolated
                                     :ui-leverage 25)}
        effects (core/submit-order state)
        api-submit-effects (filter #(= (first %) :effects/api-submit-order) effects)
        request (second (first api-submit-effects))
        pre-action (first (:pre-actions request))]
    (is (= 1 (count api-submit-effects)))
    (is (= "updateLeverage" (:type pre-action)))
    (is (= false (:isCross pre-action)))
    (is (= 25 (:leverage pre-action)))))

(deftest submit-order-requires-agent-ready-session-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :market-type :perp}
               :asset-contexts {:BTC {:idx 0}}
               :wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :not-ready
                                :storage-mode :session}}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :side :buy
                                  :size "1"
                                  :price "100")}
        effects (core/submit-order state)]
    (is (not-any? #(= (first %) :effects/api-submit-order) effects))
    (is (= [[:effects/save [:order-form-runtime :error] "Enable trading before submitting orders."]]
           effects))))

(deftest submit-order-blocks-mutations-while-ghost-mode-active-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC" :market-type :perp}
               :asset-contexts {:BTC {:idx 0}}
               :wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :ready
                                :storage-mode :session}}
               :account-context {:ghost-mode {:active? true
                                              :address "0x1234567890abcdef1234567890abcdef12345678"}}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :order-form (assoc (trading/default-order-form)
                                  :type :limit
                                  :side :buy
                                  :size "1"
                                  :price "100")}
        effects (core/submit-order state)]
    (is (= [[:effects/save [:order-form-runtime :error]
             account-context/ghost-mode-read-only-message]]
           effects))))

(deftest cancel-order-requires-agent-ready-session-test
  (let [state {:wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :not-ready
                                :storage-mode :session}}
               :asset-contexts {:BTC {:idx 0}}}
        order {:coin "BTC"
               :oid 101}
        effects (core/cancel-order state order)]
    (is (= [[:effects/save [:orders :cancel-error] "Enable trading before cancelling orders."]]
           effects))))

(deftest cancel-order-blocks-mutations-while-ghost-mode-active-test
  (let [state {:wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :ready
                                :storage-mode :session}}
               :account-context {:ghost-mode {:active? true
                                              :address "0x1234567890abcdef1234567890abcdef12345678"}}
               :asset-contexts {:BTC {:idx 0}}}
        order {:coin "BTC"
               :oid 101}
        effects (core/cancel-order state order)]
    (is (= [[:effects/save [:orders :cancel-error]
             account-context/ghost-mode-read-only-message]]
           effects))))

(deftest cancel-order-ready-agent-emits-single-api-cancel-effect-test
  (let [state {:wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :ready
                                :storage-mode :session
                                :agent-address "0xagent"}}
               :asset-contexts {:BTC {:idx 0}}}
        order {:coin "BTC"
               :oid 202}
        effects (core/cancel-order state order)
        cancel-effects (filter #(= (first %) :effects/api-cancel-order) effects)]
    (is (= 1 (count cancel-effects)))))

(deftest cancel-order-falls-back-to-asset-selector-market-index-test
  (let [state {:wallet {:connected? true
                        :address "0xabc"
                        :agent {:status :ready
                                :storage-mode :session
                                :agent-address "0xagent"}}
               :asset-contexts {}
               :asset-selector {:market-by-key {"perp:SOL" {:coin "SOL"
                                                            :idx 12}}}}
        order {:coin "SOL"
               :oid "307891000622"}
        effects (core/cancel-order state order)]
    (is (= [[:effects/api-cancel-order {:action {:type "cancel"
                                                 :cancels [{:a 12 :o 307891000622}]}}]]
           effects))))

(deftest prune-canceled-open-orders-removes-canceled-oid-across-all-sources-test
  (let [state {:orders {:open-orders [{:order {:coin "BTC" :oid 101}}
                                      {:order {:coin "ETH" :oid 102}}]
                        :open-orders-snapshot {:orders [{:order {:coin "BTC" :oid 101}}
                                                        {:order {:coin "SOL" :oid 103}}]}
                        :open-orders-snapshot-by-dex {"dex-a" [{:order {:coin "BTC" :oid 101}}]
                                                      "dex-b" [{:order {:coin "XRP" :oid 104}}]}}}
        request {:action {:type "cancel"
                          :cancels [{:a 0 :o 101}]}}
        next-state (core/prune-canceled-open-orders state request)]
    (is (= #{102}
           (->> (get-in next-state [:orders :open-orders])
                (map #(get-in % [:order :oid]))
                set)))
    (is (= #{103}
           (->> (get-in next-state [:orders :open-orders-snapshot :orders])
                (map #(get-in % [:order :oid]))
                set)))
    (is (= []
           (get-in next-state [:orders :open-orders-snapshot-by-dex "dex-a"])))
    (is (= #{104}
           (->> (get-in next-state [:orders :open-orders-snapshot-by-dex "dex-b"])
                (map #(get-in % [:order :oid]))
                set)))))
