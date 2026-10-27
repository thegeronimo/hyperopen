(ns hyperopen.vol-range.application.dock-model
  "Pure view models for Vol Range placement and the Vol ladders dock
  (HO-SPEC-014 v2.1 sections 8.5, 11.2).

  Reads `[:vol-range :execution]`: {:enabled? :max-ladder-usdc :grids :busy
  :last :selected-grid-id}. Counts always come with the aggregate label, and
  an unknown outcome is never shown as reassuring (spec 8.5)."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]))

(def ^:private zero (precision/parse-decimal "0"))
(defn- d [x] (or (some-> x str precision/parse-decimal) zero))
(defn- ds [x] (precision/decimal->string x))
(defn- execution [state] (get-in state [:vol-range :execution]))
(defn- busy? [state k] (true? (get-in state [:vol-range :execution :busy k])))

(def status-labels
  {:submitting "Submitting"
   :live "Live"
   :partially-deployed "Partially deployed"
   :checking "Checking status"
   :canceling "Canceling"
   :closed "Closed"
   :not-placed "Not placed"})

(defn- plural [n word] (str n " " word (when (not= 1 n) "s")))

;; ---------------------------------------------------------------------------
;; Review commit button
;; ---------------------------------------------------------------------------

(defn review-commit
  "The review dialog's commit control. Hidden unless placement is enabled in
  this browser; disabled unless the frozen plan is :ready and within the
  local ladder cap."
  [state]
  (let [{:keys [enabled? max-ladder-usdc]} (execution state)
        plan (get-in state [:vol-range :frozen-review])
        n (count (:legs plan))
        ;; Same arithmetic as the executor's cap check: price x size.
        total (reduce precision/add zero (map #(precision/mul (d (:price %)) (d (:size %))) (:legs plan)))
        over-cap? (and max-ladder-usdc (pos? (precision/cmp total (d max-ladder-usdc))))
        placing? (busy? state [:place])]
    {:visible? (boolean enabled?)
     :enabled? (boolean (and enabled? (= :ready (:status plan)) (pos? n) (not over-cap?) (not placing?)))
     :placing? placing?
     :label (if placing? "Placing orders…" (str "Place " (plural n "order")))
     :note (cond
             over-cap? (str "This ladder is " (ds total) " USDC; the local test cap is " max-ladder-usdc " USDC.")
             :else (str "Post-only orders. Local test cap " max-ladder-usdc " USDC per ladder."))}))

;; ---------------------------------------------------------------------------
;; Dock
;; ---------------------------------------------------------------------------

(defn- side-fills
  "Execution detail per side (not P&L): filled base, fill VWAP, fees by token."
  [grid side]
  (let [fills (filter #(= side (:side (get-in grid [:legs (:cloid %)]))) (vals (:fills grid)))
        qty (reduce precision/add zero (map #(d (:sz %)) fills))
        gross (reduce precision/add zero (map #(precision/mul (d (:px %)) (d (:sz %))) fills))
        fees (reduce (fn [m f] (update m (or (:fee-token f) "?") #(precision/add (or % zero) (d (:fee f)))))
                     {} fills)]
    {:qty (ds qty)
     :vwap (when (precision/pos-dec? qty) (ds (precision/divide gross qty 8 :half-up)))
     :fees (into (sorted-map) (map (fn [[k v]] [k (ds v)])) fees)}))

(defn- leg-status-label [leg]
  (cond
    (= :unknown (:placement-state leg)) "Checking"
    (= :pending (:placement-state leg)) "Sending"
    (= :not-sent (:placement-state leg)) "Not sent"
    (= :rejected (:placement-state leg)) "Rejected"
    (contains? #{:requested :acknowledged :not-resting :unknown} (:cancel-state leg)) "Canceling"
    (= :unconfirmed (:order-state leg)) "Checking"
    (= :filled (:terminal-reason leg)) "Filled"
    (= :canceled (:terminal-reason leg)) "Canceled"
    (= :resting (:order-state leg)) "Live"
    :else "Checking"))

(defn- leg-rows [grid]
  (->> (vals (:legs grid))
       (sort-by (juxt #(if (= :buy (:side %)) 0 1) :rung-index :cloid))
       (mapv (fn [leg]
               (let [q (lifecycle/leg-quantities grid leg)
                     current? (= (:cloid leg) (lifecycle/current-cloid (get-in grid [:members (:logical-id leg)])))]
                 {:cloid (:cloid leg)
                  :oid (:oid leg)
                  :side (:side leg)
                  :rung-index (:rung-index leg)
                  :price (:price leg)
                  :size (:original-base leg)
                  :filled (:filled q)
                  :remaining (:remaining q)
                  :status-label (leg-status-label leg)
                  :reason (some-> (:reason leg) str)
                  :historical? (not current?)})))))

(defn- partial-message [{:keys [rejected live]}]
  (when (and (pos? rejected) (pos? live))
    (str (plural rejected "order") " " (if (= 1 rejected) "was" "were") " not placed. "
         live " other order" (when (not= 1 live) "s") " remain" (when (= 1 live) "s") " active.")))

(defn grid-row [state grid]
  (let [status (lifecycle/status grid)
        counts (lifecycle/counts grid)
        {:keys [symbol quote-symbol account-address]} (:scope grid)
        id (:grid-id grid)]
    {:grid-id id
     :market (str (or symbol (get-in grid [:scope :coin])) (when quote-symbol (str "/" quote-symbol)))
     :account (some-> account-address str)
     :recovered? (boolean (:recovered? grid))
     :status status
     :status-label (if (:recovered? grid)
                     "Recovered Vol Range orders — original settings unavailable"
                     (get status-labels status "Checking status"))
     :attention? (or (= :checking status) (boolean (:recovered? grid)))
     :counts counts
     :partial-message (partial-message counts)
     :filled {:buy (side-fills grid :buy) :sell (side-fills grid :sell)}
     :model (:model grid)
     :created-at-ms (:created-at-ms grid)
     :can-cancel? (boolean (seq (lifecycle/cancel-targets grid)))
     :canceling? (busy? state [:cancel id])
     :refreshing? (busy? state [:refresh id])
     :selected? (= id (get-in state [:vol-range :execution :selected-grid-id]))
     :legs (leg-rows grid)}))

(def ^:private ladder-result-text
  {:busy "An order placement for this ladder is still in flight. Cancel again in a moment."
   :refused "This ladder belongs to another account or your trading session isn't ready."
   :error "Something went wrong."})

(def ^:private result-text
  {:busy "Another placement for this account is in progress. Try again in a moment."
   :blocked "A ladder is already active or still being checked in this market."
   :refused "Placement is not allowed right now."
   :admission-failed "The market or your balances changed since review. Review the ladder again."
   :journal-unavailable "Placement needs browser storage, which is unavailable."
   :coordination-unavailable "Another tab could be trading this account; placement is paused."
   :reconciliation-required "Checking an earlier placement before placing again."
   :error "Something went wrong."})

(defn status-message
  "The polite live-region text for the latest operation (spec 11.4)."
  [state]
  (let [{:keys [op status delivery errors error]} (get-in state [:vol-range :execution :last])
        texts (if (contains? #{:cancel :refresh} op) ladder-result-text result-text)]
    (when op
      (cond
        (= :not-sent delivery) (str "Nothing was sent: " (or (some->> errors (map name) (str/join ", ")) error))
        (contains? texts status) (str (get texts status)
                                      (when (and (= :error status) error) (str " " error))
                                            (when (seq errors) (str " (" (str/join ", " (map name errors)) ")")))
        (keyword? status) (str "Ladder " (str/lower-case (get status-labels status (name status))) ".")
        :else nil))))

(defn dock
  "The Vol ladders tab model."
  [state]
  (let [account (some-> (account-context/active-trading-account-address state) str str/lower-case)
        grids (filter #(= account (some-> (get-in % [:scope :account-address]) str str/lower-case))
                      (vals (get-in state [:vol-range :execution :grids])))]
    {:enabled? (boolean (get-in state [:vol-range :execution :enabled?]))
     :syncing? (busy? state [:sync])
     :rows (mapv #(grid-row state %) (sort-by (comp - (fnil identity 0) :created-at-ms) grids))
     :message (status-message state)}))
