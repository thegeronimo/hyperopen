(ns hyperopen.trading.order-form-transitions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.schema.order-form-contracts :as contracts]
            [hyperopen.trading.order-type-registry :as order-type-registry]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-transitions :as transitions]))

(defn- base-state
  ([] (base-state {}))
  ([order-form-overrides]
   (let [order-form (merge (trading/default-order-form) order-form-overrides)]
     {:active-asset "BTC"
      :active-market {:coin "BTC"
                      :quote "USDC"
                      :mark 100
                      :maxLeverage 40
                      :market-type :perp
                      :szDecimals 4}
      :asset-contexts {:BTC {:idx 0}}
      :orderbooks {"BTC" {:bids [{:px "99"}]
                          :asks [{:px "101"}]}}
      :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                      :totalMarginUsed "250"}}}
      :order-form order-form
      :order-form-ui (trading/default-order-form-ui)
      :order-form-runtime (trading/default-order-form-runtime)})))

(deftest transition-runtime-shape-invariant-test
  (let [state (base-state {:type :limit :size "1" :price "100"})]
    (doseq [transition [(transitions/select-entry-mode state :market)
                        (transitions/select-entry-mode state :limit)
                        (transitions/select-pro-order-type state :scale)
                        (transitions/set-order-ui-leverage state 25)
                        (transitions/set-order-size-percent state 45)
                        (transitions/set-order-size-display state "123")
                        (transitions/focus-order-price-input state)
                        (transitions/blur-order-price-input state)
                        (transitions/set-order-price-to-mid state)
                        (transitions/update-order-form state [:side] :sell)]]
      (is (map? transition))
      (is (contracts/order-form-transition-valid? transition))
      (when-let [runtime (:order-form-runtime transition)]
        (is (boolean? (:submitting? runtime)))
        (is (nil? (:error runtime)))))))

(deftest percent-application-property-test
  (let [state (base-state {:type :limit :price "100"})]
    (doseq [input [-100 -1 0 1 12 33 50 77 100 120 999]]
      (let [transition (transitions/set-order-size-percent state input)
            form (:order-form transition)
            pct (:size-percent form)]
        (is (number? pct))
        (is (<= 0 pct 100))
        (when (zero? pct)
          (is (= "" (:size form))))))))

(deftest submit-policy-disabled-reason-invariant-test
  (let [state (base-state)
        forms [(assoc (:order-form state) :type :limit :size "" :price "")
               (assoc (:order-form state) :type :market :size "1")
               (assoc (:order-form state) :type :stop-market :size "1" :trigger-px "")
               (assoc (:order-form state) :type :limit :size "1" :price "100")]
        modes [{:mode :view :submitting? false}
               {:mode :submit :agent-ready? true}]]
    (doseq [form forms
            opts modes]
      (let [policy (trading/submit-policy state form opts)]
        (is (= (boolean (:reason policy))
               (:disabled? policy)))))))

(deftest select-entry-mode-and-pro-type-transitions-preserve-order-type-invariants-test
  (let [state (base-state {:type :limit})
        pro-types (set (order-type-registry/pro-order-types))]
    (doseq [mode [:market :limit :pro]]
      (let [transition (transitions/select-entry-mode state mode)
            form (:order-form transition)
            order-type (:type form)]
        (is (contracts/order-form-transition-valid? transition))
        (is (= mode (:entry-mode form)))
        (cond
          (= mode :market) (is (= :market order-type))
          (= mode :limit) (is (= :limit order-type))
          :else (is (contains? pro-types order-type)))))
    (doseq [order-type (order-type-registry/pro-order-types)]
      (let [transition (transitions/select-pro-order-type state order-type)
            form (:order-form transition)]
        (is (contracts/order-form-transition-valid? transition))
        (is (= :pro (:entry-mode form)))
        (is (= order-type (:type form)))))))

(deftest update-order-form-rejects-ui-and-runtime-paths-test
  (let [state (base-state {:type :limit :size "1" :price "100"})
        transition (transitions/update-order-form state [:price-input-focused?] true)]
    (is (contracts/order-form-transition-valid? transition))
    (is (nil? (:order-form transition)))
    (is (nil? (:order-form-ui transition)))
    (is (= nil (get-in transition [:order-form-runtime :error])))))

(def ^:private ui-only-form-paths
  #{:pro-order-type-dropdown-open?
    :price-input-focused?
    :tpsl-panel-open?
    :submitting?
    :error})

(defn- next-seed [seed]
  (mod (+ (* 1103515245 seed) 12345) 2147483648))

(def ^:private simulation-intents
  [:select-entry-market
   :select-entry-limit
   :select-entry-pro
   :select-pro-order-type
   :set-ui-leverage
   :set-size-percent
   :set-size-display
   :focus-price
   :blur-price
   :set-mid-price
   :toggle-tpsl
   :set-side
   :set-price
   :set-tif
   :keydown-pro-dropdown])

(defn- run-intent [state intent seed]
  (let [pro-types (vec (order-type-registry/pro-order-types))]
    (case intent
      :select-entry-market
      (transitions/select-entry-mode state :market)

      :select-entry-limit
      (transitions/select-entry-mode state :limit)

      :select-entry-pro
      (transitions/select-entry-mode state :pro)

      :select-pro-order-type
      (transitions/select-pro-order-type state (nth pro-types (mod seed (count pro-types))))

      :set-ui-leverage
      (transitions/set-order-ui-leverage state (+ 1 (mod seed 80)))

      :set-size-percent
      (transitions/set-order-size-percent state (- (mod seed 180) 40))

      :set-size-display
      (transitions/set-order-size-display state (str (/ (mod seed 5000) 10)))

      :focus-price
      (transitions/focus-order-price-input state)

      :blur-price
      (transitions/blur-order-price-input state)

      :set-mid-price
      (transitions/set-order-price-to-mid state)

      :toggle-tpsl
      (transitions/toggle-order-tpsl-panel state)

      :set-side
      (transitions/update-order-form state [:side] (if (odd? seed) :buy :sell))

      :set-price
      (transitions/update-order-form state [:price] (str (/ (mod seed 3000) 10)))

      :set-tif
      (transitions/update-order-form state [:tif] (if (odd? seed) :gtc :ioc))

      :keydown-pro-dropdown
      (transitions/handle-pro-order-type-dropdown-keydown state (if (odd? seed) "Escape" "Enter"))

      nil)))

(defn- apply-transition [state transition]
  (if (map? transition)
    (merge state transition)
    state))

(deftest transition-state-machine-sequence-invariants-test
  (let [base (base-state {:entry-mode :limit
                          :type :limit
                          :price "100"
                          :size "1"
                          :size-percent 10})
        seeds [7 13 42 99 2026 4096]]
    (doseq [seed0 seeds]
      (loop [seed seed0
             step 0
             state base]
        (when (< step 80)
          (let [seed* (next-seed seed)
                intent (nth simulation-intents (mod seed* (count simulation-intents)))
                transition (run-intent state intent seed*)
                _ (when (map? transition)
                    (is (contracts/order-form-transition-valid? transition)
                        (str "invalid transition at step " step " intent " intent)))
                next-state (apply-transition state transition)
                form (:order-form next-state)
                normalized-form (trading/normalize-order-form next-state form)
                effective-ui (trading/order-form-ui-state next-state)
                size-percent (:size-percent normalized-form)]
            (is (map? form))
            (is (= (:entry-mode normalized-form) (:entry-mode form)))
            (is (= (:type normalized-form) (:type form)))
            (is (every? #(not (contains? form %)) ui-only-form-paths))
            (when (number? size-percent)
              (is (<= 0 size-percent 100)))
            (when (= :scale (:type normalized-form))
              (is (false? (:tpsl-panel-open? effective-ui))))
            (recur seed* (inc step) next-state)))))))
