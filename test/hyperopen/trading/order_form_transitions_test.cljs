(ns hyperopen.trading.order-form-transitions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop :include-macros true]
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

(deftest size-input-mode-toggle-converts-display-without-changing-canonical-size-test
  (let [state (base-state {:type :limit
                           :price "100"
                           :size "2"
                           :size-input-mode :quote
                           :size-display "200"})
        to-base (transitions/set-order-size-input-mode state :base)
        base-form (:order-form to-base)
        base-ui (:order-form-ui to-base)
        next-state (merge state to-base)
        to-quote (transitions/set-order-size-input-mode next-state :quote)
        quote-form (:order-form to-quote)
        quote-ui (:order-form-ui to-quote)]
    (is (= :base (:size-input-mode base-ui)))
    (is (= "2" (:size base-form)))
    (is (= "2" (:size-display base-ui)))
    (is (= :quote (:size-input-mode quote-ui)))
    (is (= "2" (:size quote-form)))
    (is (= "200" (:size-display quote-ui)))))

(deftest manual-size-behavior-on-price-change-respects-active-size-input-mode-test
  (let [quote-state (base-state {:type :limit
                                 :price "100"
                                 :size-input-mode :quote})
        quote-after-size (merge quote-state
                                (transitions/set-order-size-display quote-state "200"))
        quote-after-price (transitions/update-order-form quote-after-size [:price] "50")
        quote-form (:order-form quote-after-price)
        quote-ui (:order-form-ui quote-after-price)
        base-state* (base-state {:type :limit
                                 :price "100"
                                 :size-input-mode :base})
        base-after-size (merge base-state*
                               (transitions/set-order-size-display base-state* "2"))
        base-after-price (transitions/update-order-form base-after-size [:price] "50")
        base-form (:order-form base-after-price)
        base-ui (:order-form-ui base-after-price)]
    (is (= "200" (:size-display quote-ui)))
    (is (= "4" (:size quote-form)))
    (is (= :manual (:size-input-source quote-ui)))
    (is (= "2" (:size-display base-ui)))
    (is (= "2" (:size base-form)))
    (is (= :manual (:size-input-source base-ui)))))

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
            ui (:order-form-ui transition)
            order-type (:type form)]
        (is (contracts/order-form-transition-valid? transition))
        (is (= mode (:entry-mode ui)))
        (cond
          (= mode :market) (is (= :market order-type))
          (= mode :limit) (is (= :limit order-type))
          :else (is (contains? pro-types order-type)))))
    (doseq [order-type (order-type-registry/pro-order-types)]
      (let [transition (transitions/select-pro-order-type state order-type)
            form (:order-form transition)
            ui (:order-form-ui transition)]
        (is (contracts/order-form-transition-valid? transition))
        (is (= :pro (:entry-mode ui)))
        (is (= order-type (:type form)))))))

(deftest update-order-form-rejects-ui-and-runtime-paths-test
  (let [state (base-state {:type :limit :size "1" :price "100"})
        transition (transitions/update-order-form state [:price-input-focused?] true)]
    (is (contracts/order-form-transition-valid? transition))
    (is (nil? (:order-form transition)))
    (is (nil? (:order-form-ui transition)))
    (is (= nil (get-in transition [:order-form-runtime :error])))))

(def ^:private ui-only-form-paths
  #{:entry-mode
    :ui-leverage
    :size-input-mode
    :size-input-source
    :size-display
    :pro-order-type-dropdown-open?
    :price-input-focused?
    :tpsl-panel-open?
    :submitting?
    :error})

(def ^:private entry-mode-gen
  (gen/elements [:market :limit :pro]))

(def ^:private pro-order-type-gen
  (gen/elements (vec (order-type-registry/pro-order-types))))

(def ^:private side-gen
  (gen/elements [:buy :sell]))

(def ^:private tif-gen
  (gen/elements [:gtc :ioc :alo]))

(def ^:private keydown-gen
  (gen/elements ["Escape" "Enter"]))

(def ^:private intent-gen
  (gen/one-of
   [(gen/fmap (fn [mode] {:intent :select-entry-mode :mode mode}) entry-mode-gen)
    (gen/fmap (fn [order-type] {:intent :select-pro-order-type :order-type order-type}) pro-order-type-gen)
    (gen/fmap (fn [leverage] {:intent :set-ui-leverage :leverage leverage}) (gen/choose 1 120))
    (gen/fmap (fn [percent] {:intent :set-size-percent :percent percent}) (gen/choose -40 160))
    (gen/fmap (fn [size-display] {:intent :set-size-display :size-display size-display})
              (gen/fmap str (gen/choose 0 10000)))
    (gen/return {:intent :focus-price})
    (gen/return {:intent :blur-price})
    (gen/return {:intent :set-mid-price})
    (gen/return {:intent :toggle-tpsl})
    (gen/fmap (fn [side] {:intent :set-side :side side}) side-gen)
    (gen/fmap (fn [price] {:intent :set-price :price price})
              (gen/fmap str (gen/choose 1 300000)))
    (gen/fmap (fn [tif] {:intent :set-tif :tif tif}) tif-gen)
    (gen/fmap (fn [key] {:intent :keydown-pro-dropdown :key key}) keydown-gen)
    (gen/return {:intent :toggle-pro-dropdown})]))

(defn- run-intent [state {:keys [intent mode order-type leverage percent size-display side price tif key]}]
  (case intent
    :select-entry-mode (transitions/select-entry-mode state mode)
    :select-pro-order-type (transitions/select-pro-order-type state order-type)
    :set-ui-leverage (transitions/set-order-ui-leverage state leverage)
    :set-size-percent (transitions/set-order-size-percent state percent)
    :set-size-display (transitions/set-order-size-display state size-display)
    :focus-price (transitions/focus-order-price-input state)
    :blur-price (transitions/blur-order-price-input state)
    :set-mid-price (transitions/set-order-price-to-mid state)
    :toggle-tpsl (transitions/toggle-order-tpsl-panel state)
    :set-side (transitions/update-order-form state [:side] side)
    :set-price (transitions/update-order-form state [:price] price)
    :set-tif (transitions/update-order-form state [:tif] tif)
    :keydown-pro-dropdown (transitions/handle-pro-order-type-dropdown-keydown state key)
    :toggle-pro-dropdown (transitions/toggle-pro-order-type-dropdown state)
    nil))

(defn- apply-model [model {:keys [intent mode order-type key]}]
  (case intent
    :select-entry-mode
    (case mode
      :market (assoc model :entry-mode :market :type :market :pro-order-type-dropdown-open? false)
      :limit (assoc model :entry-mode :limit :type :limit :pro-order-type-dropdown-open? false)
      :pro (assoc model
                  :entry-mode :pro
                  :type (trading/normalize-pro-order-type (:type model))))

    :select-pro-order-type
    (assoc model :entry-mode :pro
           :type (trading/normalize-pro-order-type order-type)
           :pro-order-type-dropdown-open? false)

    :toggle-pro-dropdown
    (update model :pro-order-type-dropdown-open? not)

    :keydown-pro-dropdown
    (if (= key "Escape")
      (assoc model :pro-order-type-dropdown-open? false)
      model)

    :toggle-tpsl
    (if (= :scale (:type model))
      model
      (update model :tpsl-panel-open? not))

    model))

(defn- apply-transition [state transition]
  (if (map? transition)
    (merge state transition)
    state))

(defn- state-invariants-hold?
  [state model]
  (let [form (:order-form state)
        ui-state (:order-form-ui state)
        normalized-form (trading/order-form-draft state)
        effective-ui (trading/order-form-ui-state state)
        size-percent (:size-percent normalized-form)]
    (and (map? form)
         (map? ui-state)
         (every? #(not (contains? form %)) ui-only-form-paths)
         (= (:entry-mode normalized-form) (:entry-mode effective-ui))
         (= (:ui-leverage normalized-form) (:ui-leverage effective-ui))
         (= (:size-display normalized-form) (:size-display effective-ui))
         (= (:entry-mode model) (:entry-mode effective-ui))
         (= (:type model) (:type normalized-form))
         (= (:pro-order-type-dropdown-open? model)
            (boolean (:pro-order-type-dropdown-open? effective-ui)))
         (or (not (= :scale (:type normalized-form)))
             (false? (:tpsl-panel-open? effective-ui)))
         (if (number? size-percent)
           (<= 0 size-percent 100)
           true))))

(defn- evaluate-intents
  [initial-state initial-model intents]
  (loop [idx 0
         remaining intents
         state initial-state
         model initial-model]
    (if (empty? remaining)
      {:ok? true}
      (let [intent (first remaining)
            transition (run-intent state intent)
            transition-valid? (or (nil? transition)
                                  (contracts/order-form-transition-valid? transition))
            next-state (apply-transition state transition)
            next-model (apply-model model intent)
            invariants-ok? (state-invariants-hold? next-state next-model)]
        (if (and transition-valid? invariants-ok?)
          (recur (inc idx) (rest remaining) next-state next-model)
          {:ok? false
           :idx idx
           :intent intent
           :transition transition
           :transition-valid? transition-valid?
           :invariants-ok? invariants-ok?
           :state-snapshot {:order-form (:order-form next-state)
                            :order-form-ui (:order-form-ui next-state)}})))))

(defn- failure-diagnostics
  [base initial-model result]
  (let [shrunk-intents (or (get-in result [:shrunk :smallest])
                           (get-in result [:fail]))
        run (when (sequential? shrunk-intents)
              (evaluate-intents base initial-model shrunk-intents))]
    {:result (dissoc result :result)
     :shrunk-intents (when (sequential? shrunk-intents)
                       (vec (take 25 shrunk-intents)))
     :failure run}))

(deftest transition-state-machine-generative-model-invariants-test
  (let [base (base-state {:type :limit
                          :price "100"
                          :size "1"
                          :size-percent 10})
        initial-model {:entry-mode :limit
                       :type :limit
                       :pro-order-type-dropdown-open? false
                       :tpsl-panel-open? false}
        property (prop/for-all [intents (gen/vector intent-gen 1 120)]
                   (:ok? (evaluate-intents base initial-model intents)))]
    (let [result (tc/quick-check 120 property)]
      (is (:pass? result)
          (str "generative model check failed: "
               (pr-str (failure-diagnostics base initial-model result)))))))
