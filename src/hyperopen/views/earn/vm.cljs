(ns hyperopen.views.earn.vm
  "View model for the Earn page: turns borrow/lend state into display rows.
  All maths comes from `hyperopen.borrow-lend.model`; this namespace only
  selects, labels and formats."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.borrow-lend.account :as bl-account]
            [hyperopen.borrow-lend.model :as model]
            [hyperopen.borrow-lend.normalize :as normalize]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.utils.parse :as parse-utils]))

(def ^:private dash "--")

(defn- finite? [x] (and (number? x) (js/isFinite x)))

(defn format-pct
  ([x] (format-pct x 2))
  ([x digits]
   (if (finite? x) (str (fmt/format-fixed-number (* 100 x) digits) "%") dash)))

(defn format-usd
  [x]
  (if (finite? x) (or (fmt/format-currency x) dash) dash))

(defn format-compact-usd
  [x]
  (cond (not (finite? x)) dash
        (>= (js/Math.abs x) 1e9) (str "$" (fmt/format-fixed-number (/ x 1e9) 2) "B")
        (>= (js/Math.abs x) 1e6) (str "$" (fmt/format-fixed-number (/ x 1e6) 1) "M")
        (>= (js/Math.abs x) 1e4) (str "$" (fmt/format-fixed-number (/ x 1e3) 1) "K")
        :else (format-usd x)))

(defn format-amount
  [x]
  (if (finite? x) (fmt/format-fixed-number x (if (< (js/Math.abs x) 1) 6 2)) dash))

(defn format-price
  [x]
  (if (finite? x) (str "$" (fmt/format-fixed-number x (if (< x 10) 4 2))) dash))

(defn health-label
  [status]
  (case status
    :safe "Safe"
    :caution "Caution"
    :risk "At risk"
    "No borrows"))

(defn- pool-row
  [state {:keys [token supply-apy borrow-apy utilization total-supplied total-borrowed available oracle-px]}]
  (let [px (or oracle-px 1)]
    {:token token
     :symbol (bl-account/token-symbol state token)
     :supply-apy (format-pct supply-apy)
     :borrow-apy (format-pct borrow-apy)
     :utilization utilization
     :utilization-label (format-pct utilization 1)
     :above-kink? (model/above-kink? utilization)
     :supplied (format-compact-usd (* total-supplied px))
     :borrowed (format-compact-usd (* total-borrowed px))
     :available (format-compact-usd (* available px))}))

(defn- collateral-row
  [state positions-by-token {:keys [token oracle-px ltv total-supplied]}]
  (let [mine (get-in positions-by-token [token :supplied])
        symbol (bl-account/token-symbol state token)]
    {:token token
     :symbol symbol
     :oracle-px (format-price oracle-px)
     :ltv (format-pct ltv 0)
     :threshold (format-pct (model/liquidation-threshold ltv) 1)
     :total-supplied (str (format-amount total-supplied) " " symbol)
     :total-supplied-usd (format-compact-usd (when (finite? oracle-px) (* total-supplied oracle-px)))
     :mine (if (and (finite? mine) (pos? mine)) (str (format-amount mine) " " symbol) dash)
     :has-position? (and (finite? mine) (pos? mine))}))

(defn- position-rows
  [state reserves-by-token interest-totals]
  (->> (bl-account/positions state)
       (mapcat (fn [{:keys [token supplied borrowed]}]
                 (let [{:keys [oracle-px ltv supply-apy borrow-apy]} (get reserves-by-token token)
                       symbol (bl-account/token-symbol state token)
                       collateral? (and (finite? ltv) (pos? ltv))
                       interest (get interest-totals symbol)]
                   (cond-> []
                     (pos? supplied)
                     (conj {:key (str token "-supply")
                            :token token
                            :symbol symbol
                            :kind (if collateral? :collateral :supplied)
                            :amount (format-amount supplied)
                            :value (format-usd (when (finite? oracle-px) (* supplied oracle-px)))
                            :apy (if collateral? dash (format-pct supply-apy))
                            :interest (if (and (not collateral?) (finite? (:supply interest)))
                                        (str "+" (format-amount (:supply interest)) " " symbol)
                                        dash)})
                     (pos? borrowed)
                     (conj {:key (str token "-borrow")
                            :token token
                            :symbol symbol
                            :kind :borrowed
                            :amount (format-amount borrowed)
                            :value (format-usd (when (finite? oracle-px) (* borrowed oracle-px)))
                            :apy (format-pct borrow-apy)
                            :interest (if (finite? (:borrow interest))
                                        (str "-" (format-amount (:borrow interest)) " " symbol)
                                        dash)})))))
       vec))

(def curve-geometry
  {:x0 32 :x1 292 :y0 132 :y1 12})

(defn- curve-xy
  [u rate]
  (let [{:keys [x0 x1 y0 y1]} curve-geometry
        rate* (min 1 (max 0 rate))]
    [(+ x0 (* u (- x1 x0))) (- y0 (* rate* (- y0 y1)))]))

(defn- polyline
  [f]
  (->> (concat (range 0 0.8 0.1) [0.8] (range 0.82 1.0 0.02) [1.0])
       (map (fn [u] (let [[x y] (curve-xy u (f u))]
                      (str (.toFixed x 1) "," (.toFixed y 1)))))
       (str/join " ")))

(defn rate-curve
  [state reserve]
  (when reserve
    (let [u (:utilization reserve)
          [bx by] (curve-xy u (:borrow-apy reserve))
          [sx sy] (curve-xy u (:supply-apy reserve))
          [kx _] (curve-xy model/kink-utilization 0)]
      {:symbol (bl-account/token-symbol state (:token reserve))
       :utilization-label (format-pct u 1)
       :borrow-apy (format-pct (:borrow-apy reserve))
       :supply-apy (format-pct (:supply-apy reserve))
       :borrow-points (polyline model/borrow-rate)
       :supply-points (polyline model/supply-rate)
       :borrow-marker [bx by]
       :supply-marker [sx sy]
       :kink-x kx})))

(defn- summary
  [state reserves-by-token health]
  (let [ps (bl-account/positions state)
        legs (fn [k apy-k pred]
               (keep (fn [{:keys [token] :as p}]
                       (let [{:keys [oracle-px ltv] :as r} (get reserves-by-token token)
                             amount (get p k)]
                         (when (and (finite? amount) (pos? amount) (finite? oracle-px) (pred ltv))
                           {:value-usd (* amount oracle-px) :apy (get r apy-k)})))
                     ps))
        supply-legs (legs :supplied :supply-apy #(not (pos? %)))
        borrow-legs (legs :borrowed :borrow-apy any?)
        supplied-usd (reduce + 0 (map :value-usd supply-legs))
        status (model/health-status (:health-factor health))
        primary (first (sort-by (comp - :value-usd) (:collateral health)))
        primary-prices (get (:liquidation-prices health) (:token primary))]
    {:supplied-usd (format-usd supplied-usd)
     :supply-apy (format-pct (when (pos? supplied-usd)
                               (/ (reduce + 0 (map #(* (:value-usd %) (:apy %)) supply-legs))
                                  supplied-usd)))
     :collateral-usd (format-usd (reduce + 0 (map :value-usd (:collateral health))))
     :borrow-limit-usd (format-usd (:borrow-limit-usd health))
     :borrowed-usd (format-usd (:borrowed-usd health))
     :borrow-apy (format-pct (let [b (reduce + 0 (map :value-usd borrow-legs))]
                               (when (pos? b)
                                 (/ (reduce + 0 (map #(* (:value-usd %) (:apy %)) borrow-legs)) b))))
     :available-to-borrow-usd (format-usd (:available-to-borrow-usd health))
     :limit-used (:limit-used health)
     :limit-used-label (format-pct (:limit-used health) 1)
     :health-factor (if (finite? (:health-factor health)) (format-pct (:health-factor health) 0) dash)
     :health-status status
     :health-label (health-label status)
     :net-apy (format-pct (model/net-apy supply-legs borrow-legs))
     :primary-collateral-symbol (when primary (bl-account/token-symbol state (:token primary)))
     :primary-collateral-px (when primary (format-price (:oracle-px primary)))
     :borrow-stops-px (format-price (:borrow-stops-px primary-prices))
     :liquidation-px (format-price (:liquidation-px primary-prices))
     :has-positions? (boolean (seq ps))
     :has-debt? (pos? (or (:borrowed-usd health) 0))}))

(defn- interest-current?
  [state]
  (let [address (account-context/normalize-address (bl-account/account-address state))]
    (and (some? address)
         (= address (get-in state [:borrow-lend :interest-address])))))

(defn- interest-history-rows
  [state]
  (if (interest-current? state)
    (->> (get-in state [:borrow-lend :interest])
         (filter #(or (pos? (:supply %)) (pos? (:borrow %))))
         reverse
         (take 50)
         (mapv (fn [{:keys [time-ms token supply borrow]}]
                 {:key (str time-ms "-" token)
                  :time (fmt/format-local-date-time time-ms)
                  :token token
                  :supply (if (pos? supply) (str "+" (format-amount supply)) dash)
                  :borrow (if (pos? borrow) (str "-" (format-amount borrow)) dash)})))
    []))

(defn- preview-row
  [label before after tone]
  {:label label :before before :after after :tone tone})

(defn modal-vm
  [state reserves-by-token health]
  (let [{:keys [open? kind token amount max? submitting? error]} (get-in state [:borrow-lend-ui :modal])]
    (when (and open? kind (some? token))
      (let [reserve (get reserves-by-token token)
            symbol (bl-account/token-symbol state token)
            pm? (bl-account/portfolio-margin? state)
            parsed (or (parse-utils/parse-localized-decimal amount (get-in state [:ui :locale])) 0)
            max-amount (bl-account/modal-max-amount state kind token)
            effective (if max? (or max-amount 0) parsed)
            px (or (:oracle-px reserve) 1)
            pool-kind? (contains? #{:supply :withdraw} kind)
            collateral? (pos? (or (:ltv reserve) 0))
            over? (and (finite? max-amount) (not max?) (> parsed (* max-amount 1.000001)))
            preview (if pool-kind?
                      (when-not collateral?
                        (let [after (model/supply-preview reserve kind effective)]
                          [(preview-row "Supply APY" (format-pct (:supply-apy reserve)) (format-pct (:supply-apy after)) :buy)
                           (preview-row "Borrow APY" (format-pct (:borrow-apy reserve)) (format-pct (:borrow-apy after)) :neutral)
                           (preview-row "Utilization" (format-pct (:utilization reserve) 1) (format-pct (:utilization after) 1) :neutral)]))
                      (let [delta (* effective px (if (= :borrow kind) 1 -1))
                            after (model/health-after (:collateral health) (:borrowed-usd health) delta)
                            primary (first (sort-by (comp - :value-usd) (:collateral health)))
                            prices (get (:liquidation-prices after) (:token primary))]
                        [(preview-row "Health factor"
                                      (if (finite? (:health-factor health)) (format-pct (:health-factor health) 0) dash)
                                      (if (finite? (:health-factor after)) (format-pct (:health-factor after) 0) "No debt")
                                      (model/health-status (:health-factor after)))
                         (preview-row "Total borrowed" (format-usd (:borrowed-usd health)) (format-usd (:borrowed-usd after)) :neutral)
                         (preview-row (str "Liquidation if " (or (some->> primary :token (bl-account/token-symbol state)) "collateral") " ≤")
                                      (format-price (get-in health [:liquidation-prices (:token primary) :liquidation-px]))
                                      (format-price (:liquidation-px prices))
                                      :sell)]))
            kinds (if (#{:supply :withdraw} kind) [:supply :withdraw] [:borrow :repay])
            label {:supply "Supply" :withdraw "Withdraw" :borrow "Borrow" :repay "Repay"}]
        {:kind kind
         :token token
         :symbol symbol
         :title (str (label kind) " " symbol)
         :tabs (mapv (fn [k] {:kind k
                              :label (label k)
                              :active? (= k kind)
                              :disabled? (and pm? (= :borrow k))})
                     kinds)
         :amount amount
         :max-label (case kind :supply "Available" :withdraw "Supplied" :borrow "Can borrow" :repay "Owed")
         :max-value (if (finite? max-amount) (str (format-amount max-amount) " " symbol) dash)
         :max? max?
         :preview-title (if pool-kind? "Pool after this change" "Borrow health after this change")
         :preview preview
         :rate-note (when (and pool-kind? (not collateral?))
                      "Rates are shared by the whole pool and move as others supply and borrow. When utilization is near 100%, a withdrawal can fill only partly until borrowers repay.")
         :collateral-note (when (and pool-kind? collateral?)
                            (str symbol " is collateral: it earns no interest and raises your borrow limit."))
         :borrow-apy (when-not pool-kind? (format-pct (:borrow-apy reserve)))
         :above-kink? (model/above-kink? (:utilization reserve))
         :pm-note (when pm? "Portfolio margin accounts borrow automatically, so manual borrow is off. You can still repay.")
         :error (or error (when over? "That is more than the maximum for this action."))
         :submitting? (true? submitting?)
         :submit-disabled? (or (true? submitting?) over? (and (not max?) (not (pos? parsed))))
         :cta (cond submitting? "Submitting…"
                    max? (str (label kind) " all " symbol)
                    (pos? parsed) (str (label kind) " " (format-amount parsed) " " symbol)
                    :else "Enter an amount")}))))

(defn earn-vm
  [state]
  (let [reserves (bl-account/reserves state)
        by-token (into {} (map (juxt :token identity)) reserves)
        positions-by-token (into {} (map (juxt :token identity)) (bl-account/positions state))
        health (bl-account/health state)
        interest-totals (when (interest-current? state)
                          (normalize/interest-totals (get-in state [:borrow-lend :interest])))
        pools (->> reserves (remove #(pos? (:ltv %))) (mapv #(pool-row state %)))
        collateral (->> reserves (filter #(pos? (:ltv %))) (mapv #(collateral-row state positions-by-token %)))
        tab (get-in state [:borrow-lend-ui :active-tab] :markets)
        mode (bl-account/account-mode state)]
    {:connected? (true? (get-in state [:wallet :connected?]))
     :account-address (bl-account/account-address state)
     :mode mode
     :mode-label (case mode :portfolio-margin "Portfolio margin" :unified "Unified account" "Standard account")
     :portfolio-margin? (= :portfolio-margin mode)
     :read-only? (boolean (seq (account-context/mutations-blocked-message state)))
     :loading? (and (empty? reserves) (true? (get-in state [:borrow-lend :loading :reserves])))
     :error (get-in state [:borrow-lend :errors :reserves])
     :tab tab
     :pools pools
     :collateral collateral
     :summary (summary state by-token health)
     :positions (position-rows state by-token interest-totals)
     :history (interest-history-rows state)
     :curve (rate-curve state (or (get by-token 0) (first (remove #(pos? (:ltv %)) reserves))))
     :modal (modal-vm state by-token health)}))
