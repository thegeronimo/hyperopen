(ns hyperopen.portfolio.optimizer.application.current-portfolio
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.asset-selector.markets :as markets]))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- parse-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [text (str/trim value)
          parsed (js/parseFloat text)]
      (when (and (seq text)
                 (js/isFinite parsed))
        parsed))

    :else
    nil))

(defn- abs-number
  [value]
  (js/Math.abs value))

(defn- positive-number?
  [value]
  (and (number? value)
       (pos? value)))

(defn- zeroish?
  [value]
  (or (nil? value)
      (zero? value)))

(defn- market-mark-price
  [market]
  (some (fn [k]
          (let [value (parse-number (get market k))]
            (when (positive-number? value)
              value)))
        [:mark :markRaw :markPx :midPx :oraclePx]))

(defn- normalize-coin
  [coin]
  (non-blank-text coin))

(defn- normalize-dex
  [dex]
  (non-blank-text dex))

(defn- margin-summary
  [clearinghouse-state]
  (or (:marginSummary clearinghouse-state)
      (:crossMarginSummary clearinghouse-state)
      (get-in clearinghouse-state [:clearinghouseState :marginSummary])
      (get-in clearinghouse-state [:clearinghouseState :crossMarginSummary])))

(defn- clearinghouse-state
  [state]
  (or (get-in state [:webdata2 :clearinghouseState])
      (get-in state [:webdata2 :clearinghouseState :clearinghouseState])))

(defn- perp-account-value-usdc
  [state]
  (some-> state
          clearinghouse-state
          margin-summary
          :accountValue
          parse-number))

(defn- total-margin-used-usdc
  [state]
  (some-> state
          clearinghouse-state
          margin-summary
          :totalMarginUsed
          parse-number))

(defn- spot-balances
  [state]
  (or (get-in state [:spot :clearinghouse-state :balances])
      (get-in state [:spot :clearinghouseState :balances])
      (get-in state [:spot :balances])
      []))

(defn- position-rows
  [state]
  (let [base-rows (or (get-in state [:webdata2 :clearinghouseState :assetPositions])
                      [])
        dex-rows (->> (or (:perp-dex-clearinghouse state) {})
                      (mapcat (fn [[dex dex-state]]
                                (map #(assoc % :dex dex)
                                     (or (:assetPositions dex-state) [])))))]
    (vec (concat base-rows dex-rows))))

(defn- perps-instrument-id
  [dex coin]
  (let [coin* (normalize-coin coin)
        dex* (normalize-dex dex)]
    (when (seq coin*)
      (if (seq dex*)
        (str "perp:" dex* ":" coin*)
        (str "perp:" coin*)))))

(defn- spot-instrument-id
  [market coin]
  (or (non-blank-text (:key market))
      (when-let [coin* (normalize-coin coin)]
        (str "spot:" coin*))))

(defn- position-mark-price
  [market-by-key position-row]
  (let [position (or (:position position-row) {})
        coin (normalize-coin (:coin position))
        market (markets/resolve-market-by-coin market-by-key coin)]
    (or (parse-number (:markPx position))
        (parse-number (:markPrice position))
        (parse-number (:markPx position-row))
        (parse-number (:markPrice position-row))
        (market-mark-price market))))

(defn- leverage-mode
  [position]
  (let [leverage (:leverage position)
        leverage-type (or (:type leverage)
                          (:marginMode position)
                          (:margin-mode position))
        token (some-> leverage-type str str/trim str/lower-case)]
    (cond
      (= token "isolated") :isolated
      (= token "cross") :cross
      :else nil)))

(defn- position-side
  [signed-size]
  (cond
    (pos? signed-size) :long
    (neg? signed-size) :short
    :else :flat))

(defn- position-notional
  [position signed-size mark-price]
  (or (let [position-value (parse-number (:positionValue position))]
        (when (number? position-value)
          (* (if (neg? signed-size) -1 1)
             (abs-number position-value))))
      (when (and (number? mark-price)
                 (not (zeroish? signed-size)))
        (* signed-size mark-price))))

(defn- build-perp-exposures
  [state]
  (let [market-by-key (get-in state [:asset-selector :market-by-key])]
    (reduce (fn [{:keys [exposures warnings] :as acc} position-row]
              (let [position (or (:position position-row) {})
                    coin (normalize-coin (:coin position))
                    dex (normalize-dex (:dex position-row))
                    signed-size (parse-number (:szi position))
                    mark-price (position-mark-price market-by-key position-row)
                    signed-notional (when (number? signed-size)
                                      (position-notional position signed-size mark-price))
                    instrument-id (perps-instrument-id dex coin)]
                (cond
                  (or (not (seq instrument-id))
                      (zeroish? signed-size))
                  acc

                  (not (number? signed-notional))
                  (assoc acc :warnings
                         (conj warnings
                               {:code :missing-perp-notional
                                :instrument-id instrument-id
                                :coin coin
                                :dex dex}))

                  :else
                  (assoc acc :exposures
                         (conj exposures
                               {:instrument-id instrument-id
                                :market-type :perp
                                :coin coin
                                :dex dex
                                :signed-size signed-size
                                :mark-price mark-price
                                :signed-notional-usdc signed-notional
                                :abs-notional-usdc (abs-number signed-notional)
                                :side (position-side signed-size)
                                :margin-mode (leverage-mode position)
                                :leverage (parse-number (get-in position [:leverage :value]))
                                :source (if (seq dex)
                                          :perp-dex-clearinghouse
                                          :clearinghouse)})))))
            {:exposures []
             :warnings []}
            (position-rows state))))

(defn- usdc-coin?
  [coin]
  (= "USDC" (some-> coin str str/trim str/upper-case)))

(defn- spot-price
  [market-by-key coin]
  (if (usdc-coin? coin)
    1
    (market-mark-price (markets/resolve-market-by-coin market-by-key coin))))

(defn- build-spot-exposures
  [state]
  (let [market-by-key (get-in state [:asset-selector :market-by-key])]
    (reduce (fn [{:keys [exposures warnings cash-usdc spot-noncash-usdc] :as acc} balance]
              (let [coin (normalize-coin (:coin balance))
                    total (parse-number (:total balance))
                    hold (or (parse-number (:hold balance)) 0)
                    available (when (number? total)
                                (- total hold))
                    market (markets/resolve-market-by-coin market-by-key coin)
                    price (spot-price market-by-key coin)
                    instrument-id (spot-instrument-id market coin)]
                (cond
                  (or (not (seq coin))
                      (zeroish? total))
                  acc

                  (usdc-coin? coin)
                  (assoc acc :cash-usdc (+ cash-usdc total))

                  (not (number? price))
                  (assoc acc :warnings
                         (conj warnings
                               {:code :missing-spot-price
                                :instrument-id instrument-id
                                :coin coin}))

                  :else
                  (let [signed-notional (* total price)]
                    (-> acc
                        (update :spot-noncash-usdc + signed-notional)
                        (update :exposures conj
                                {:instrument-id instrument-id
                                 :market-type :spot
                                 :coin coin
                                 :signed-size total
                                 :available-size available
                                 :hold-size hold
                                 :mark-price price
                                 :signed-notional-usdc signed-notional
                                 :abs-notional-usdc (abs-number signed-notional)
                                 :side :long
                                 :source :spot-clearinghouse}))))))
            {:exposures []
             :warnings []
             :cash-usdc 0
             :spot-noncash-usdc 0}
            (spot-balances state))))

(defn- with-weights
  [nav-usdc exposures]
  (mapv (fn [exposure]
          (if (positive-number? nav-usdc)
            (assoc exposure :weight (/ (:signed-notional-usdc exposure) nav-usdc))
            (assoc exposure :weight nil)))
        exposures))

(defn- snapshot-signature
  [state address]
  {:address address
   :webdata2-loaded? (some? (clearinghouse-state state))
   :spot-loaded? (boolean (seq (spot-balances state)))
   :perp-dex-count (count (or (:perp-dex-clearinghouse state) {}))
   :market-count (count (or (get-in state [:asset-selector :market-by-key]) {}))})

(defn current-portfolio-snapshot
  [state]
  (let [{perp-exposures :exposures
         perp-warnings :warnings} (build-perp-exposures state)
        {spot-exposures :exposures
         spot-warnings :warnings
         cash-usdc :cash-usdc
         spot-noncash-usdc :spot-noncash-usdc} (build-spot-exposures state)
        perp-account-value (perp-account-value-usdc state)
        total-margin-used (total-margin-used-usdc state)
        nav-usdc (if (number? perp-account-value)
                   (+ perp-account-value spot-noncash-usdc)
                   (+ cash-usdc spot-noncash-usdc))
        raw-exposures (vec (concat perp-exposures spot-exposures))
        exposures (with-weights nav-usdc raw-exposures)
        gross-exposure (reduce + (map :abs-notional-usdc exposures))
        net-exposure (reduce + (map :signed-notional-usdc exposures))
        address (account-context/effective-account-address state)
        read-only? (account-context/inspected-account-read-only? state)
        read-only-message (account-context/mutations-blocked-message state)
        snapshot-loaded? (boolean
                          (or (some? perp-account-value)
                              (seq exposures)
                              (pos? cash-usdc)))
        capital-ready? (positive-number? nav-usdc)
        execution-ready? (and capital-ready?
                              (not read-only?))]
    {:address address
     :loaded? snapshot-loaded?
     :snapshot-loaded? snapshot-loaded?
     :capital-ready? capital-ready?
     :execution-ready? execution-ready?
     :account {:mode (or (get-in state [:account :mode]) :classic)
               :read-only? read-only?
               :read-only-message read-only-message}
     :capital {:nav-usdc nav-usdc
               :cash-usdc cash-usdc
               :perp-account-value-usdc perp-account-value
               :total-margin-used-usdc total-margin-used
               :spot-noncash-usdc spot-noncash-usdc
               :gross-exposure-usdc gross-exposure
               :net-exposure-usdc net-exposure}
     :exposures exposures
     :by-instrument (into {}
                          (map (juxt :instrument-id identity))
                          exposures)
     :warnings (vec (concat perp-warnings spot-warnings))
     :signature (snapshot-signature state address)}))
