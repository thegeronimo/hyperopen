(ns hyperopen.views.account-equity-vaults-test
  "Regression coverage for the classic Account Equity vault row and grouped total.

   Vault equity is a separately refreshed, account-owned balance. These tests
   deliberately exercise the response ownership marker and raw wire value so a
   stale or malformed vault response cannot turn into a confident zero on the
   trading surface."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.account-equity-fixtures :as fixtures]
            [hyperopen.views.account-equity-view :as view]
            [hyperopen.views.account-info.derived-cache :as derived-cache]))

(def ^:private account-a
  "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private account-b
  "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(defn- node-children
  [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- direct-texts
  [node]
  (->> (node-children node)
       (filter string?)
       set))

(defn- node-attrs
  [node]
  (when (and (vector? node) (map? (second node)))
    (second node)))

(defn- find-first-node
  [node pred]
  (cond
    (vector? node)
    (or (when (pred node) node)
        (some #(find-first-node % pred) (node-children node)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- class-set
  [node]
  (let [tag (first node)
        tag-classes (if (keyword? tag)
                      (rest (str/split (name tag) #"\."))
                      [])
        classes (:class (node-attrs node))]
    (set (cond
           (string? classes) (concat tag-classes (remove str/blank? (str/split classes #"\s+")))
           (sequential? classes) (concat tag-classes classes)
           :else tag-classes))))

(defn- classic-named-dex-state
  []
  (or (some (fn [{:keys [label state]}]
              (when (= "classic / empty base dex, whole book on a named dex" label)
                state))
            (fixtures/classic))
      (throw (js/Error. "classic named-dex fixture missing"))))

(defn- scoped-vault-state
  ([rows]
   (scoped-vault-state rows {}))
  ([rows vaults-overrides]
   (-> (classic-named-dex-state)
       (assoc :wallet {:address account-a})
       ;; Vault-focused cases establish a known HyperEVM zero explicitly.
       ;; The four-part total is otherwise truthfully unavailable before that
       ;; independently owned account read has completed.
       (assoc :account-equity/hyperevm-funds {:status :ready
                                               :address account-a
                                               :usd 0
                                               :unpriced-count 0})
       (assoc :vaults (merge {:user-equities rows
                               :user-equities-for-address account-a
                               :loading {:user-equities? false}
                               :errors {:user-equities nil}}
                              vaults-overrides)))))

(defn- metrics-for
  [state]
  (derived-cache/reset-derived-cache!)
  (view/reset-account-equity-metrics-cache!)
  (let [result (view/account-equity-metrics state)]
    (derived-cache/reset-derived-cache!)
    (view/reset-account-equity-metrics-cache!)
    result))

(defn- index-of
  [xs value]
  (first (keep-indexed (fn [idx item]
                         (when (= item value) idx))
                       xs)))

(deftest classic-vault-equity-reconciles-the-grouped-total-test
  (let [without-vault (metrics-for (classic-named-dex-state))
        metrics (metrics-for
                 (scoped-vault-state [{:vault-address "0xvault-a"
                                       :equity 12500.0
                                       :equity-raw "12500.0"}]))]
    ;; The legacy two-part figure remains a public presentation metric. The
    ;; grouped total adds current vault equity without quietly changing its
    ;; Spot + Perps semantics or the risk figures below it.
    (is (= "$156,001.46" (view/display-currency (:account-value-display metrics))))
    (is (= (:account-value-display without-vault)
           (:account-value-display metrics)))
    (is (= "$12,500.00" (view/display-currency (:vault-equity metrics))))
    (is (= "$168,501.46" (view/display-currency (:total-account-value-display metrics))))
    (is (= (+ (:account-value-display metrics) (:vault-equity metrics))
           (:total-account-value-display metrics)))
    (is (= (select-keys without-vault [:cross-margin-ratio
                                      :maintenance-margin
                                      :cross-account-leverage])
           (select-keys metrics [:cross-margin-ratio
                                 :maintenance-margin
                                 :cross-account-leverage])))))

(deftest classic-vault-equity-accepts-confirmed-empty-and-numeric-string-responses-test
  (testing "a successful empty response is a confirmed $0.00"
    (let [metrics (metrics-for (scoped-vault-state []))]
      (is (= 0 (:vault-equity metrics)))
      (is (= (:account-value-display metrics)
             (:total-account-value-display metrics)))))
  (testing "numeric strings retain their exact current equity"
    (let [metrics (metrics-for
                   (scoped-vault-state [{:vault-address "0xvault-a"
                                         :equity 42.25
                                         :equity-raw "42.25"}]))]
      (is (= 42.25 (:vault-equity metrics)))
      (is (= (+ (:account-value-display metrics) 42.25)
             (:total-account-value-display metrics))))))

(deftest classic-vault-equity-never-treats-unconfirmed-or-invalid-data-as-zero-test
  (let [valid-row {:vault-address "0xvault-a" :equity 500.0 :equity-raw "500.0"}
        cases [["legacy WebData2-only state"
                (assoc-in (classic-named-dex-state) [:webdata2 :totalVaultEquity] "500.0")]
               ["response belonging to a different effective account"
                (assoc (scoped-vault-state [valid-row]) :vaults
                       {:user-equities [valid-row]
                        :user-equities-for-address account-b
                        :loading {:user-equities? false}
                        :errors {:user-equities nil}})]
               ["initial load before any successful response"
                (scoped-vault-state [valid-row]
                                    {:user-equities-for-address nil
                                     :loading {:user-equities? true}})]
               ["malformed endpoint payload that has no successful row vector"
                (scoped-vault-state nil)]
               ["failed response"
                (scoped-vault-state [valid-row]
                                    {:errors {:user-equities "vault request failed"}
                                     :user-equities-error-for-address account-a})]
               ["malformed raw row which an older normalizer would coerce to zero"
                (scoped-vault-state [{:vault-address "0xvault-a"
                                      :equity 0
                                      :equity-raw "not-a-number"}])]
               ["blank raw row"
                (scoped-vault-state [{:vault-address "0xvault-a"
                                      :equity 0
                                      :equity-raw "   "}])]
               ["missing raw row"
                (scoped-vault-state [{:vault-address "0xvault-a"
                                      :equity 0}])]
               ["partially numeric raw row"
                (scoped-vault-state [{:vault-address "0xvault-a"
                                      :equity 12
                                      :equity-raw "12bad"}])]
               ["non-finite raw row"
                (scoped-vault-state [{:vault-address "0xvault-a"
                                      :equity js/Infinity
                                      :equity-raw "Infinity"}])]]]
    (doseq [[label state] cases]
      (testing label
        (let [metrics (metrics-for state)]
          (is (nil? (:vault-equity metrics)))
          (is (nil? (:total-account-value-display metrics))))))))

(deftest classic-total-requires-confirmed-spot-perps-and-vault-components-test
  (testing "an explicitly loaded empty Spot snapshot is a confirmed zero"
    (let [metrics (metrics-for
                   (-> (scoped-vault-state [{:vault-address "0xvault-a"
                                              :equity 20.0
                                              :equity-raw "20.0"}])
                       (assoc-in [:spot :clearinghouse-state :balances] [])))]
      (is (= 0 (:spot-equity metrics)))
      (is (= (:perps-value metrics) (:account-value-display metrics)))
      (is (= (+ (:perps-value metrics) (:vault-equity metrics))
             (:total-account-value-display metrics)))))
  (testing "an absent Spot snapshot is unknown, never an inferred zero"
    (let [metrics (metrics-for
                   (-> (scoped-vault-state [{:vault-address "0xvault-a"
                                              :equity 20.0
                                              :equity-raw "20.0"}])
                       (assoc-in [:spot :clearinghouse-state] nil)))]
      (is (nil? (:spot-equity metrics)))
      (is (nil? (:total-account-value-display metrics)))))
  (testing "a nonempty Spot snapshot with no USD price is unknown, not a zero balance"
    (let [metrics (metrics-for
                   (-> (scoped-vault-state [{:vault-address "0xvault-a"
                                              :equity 20.0
                                              :equity-raw "20.0"}])
                       (assoc-in [:spot :clearinghouse-state :balances]
                                 [{:coin "UNPRICED" :total "4.2" :hold "0"}])))]
      (is (nil? (:spot-equity metrics)))
      (is (nil? (:total-account-value-display metrics))))))

(deftest classic-vault-equity-keeps-the-last-confirmed-same-account-value-during-refresh-test
  (let [metrics (metrics-for
                 (scoped-vault-state [{:vault-address "0xvault-a"
                                       :equity 500.0
                                       :equity-raw "500.0"}]
                                     {:loading {:user-equities? true}}))]
    ;; A background refresh for the same marked account should not make a
    ;; confirmed value flicker to --. An address switch must clear the marker
    ;; instead, which the previous test covers.
    (is (= 500.0 (:vault-equity metrics)))
    (is (= (+ (:account-value-display metrics) 500.0)
           (:total-account-value-display metrics)))))

(deftest classic-vault-equity-invalidates-the-account-metrics-cache-on-response-change-test
  (let [first-state (scoped-vault-state [{:vault-address "0xvault-a"
                                          :equity 10.0
                                          :equity-raw "10.0"}])
        next-state (assoc-in first-state [:vaults :user-equities]
                             [{:vault-address "0xvault-a"
                               :equity 25.0
                               :equity-raw "25.0"}])]
    (derived-cache/reset-derived-cache!)
    (view/reset-account-equity-metrics-cache!)
    (let [first-metrics (view/account-equity-metrics first-state)
          next-metrics (view/account-equity-metrics next-state)]
      (is (= 10.0 (:vault-equity first-metrics)))
      (is (= 25.0 (:vault-equity next-metrics)))
      (is (= (+ (:account-value-display next-metrics) 25.0)
             (:total-account-value-display next-metrics))))
    (derived-cache/reset-derived-cache!)
    (view/reset-account-equity-metrics-cache!)))

(deftest classic-vault-equity-does-not-survive-an-account-reset-in-the-metrics-cache-test
  (let [known-state (scoped-vault-state [{:vault-address "0xvault-a"
                                          :equity 10.0
                                          :equity-raw "10.0"}])
        reset-state (assoc known-state
                           :wallet {:address nil}
                           :vaults {:user-equities []
                                    :user-equities-for-address nil
                                    :loading {:user-equities? false}
                                    :errors {:user-equities nil}})]
    (derived-cache/reset-derived-cache!)
    (view/reset-account-equity-metrics-cache!)
    (let [known (view/account-equity-metrics known-state)
          after-reset (view/account-equity-metrics reset-state)]
      (is (= 10.0 (:vault-equity known)))
      (is (nil? (:vault-equity after-reset)))
      (is (nil? (:total-account-value-display after-reset))))
    (derived-cache/reset-derived-cache!)
    (view/reset-account-equity-metrics-cache!)))

(deftest classic-account-equity-renders-the-prominent-grouped-vault-total-test
  (let [state (scoped-vault-state [{:vault-address "0xvault-a"
                                    :equity 12500.0
                                    :equity-raw "12500.0"}])
        view-node (view/account-equity-view state {:show-funding-actions? false})
        strings (vec (collect-strings view-node))
        total-label (find-first-node view-node #(contains? (direct-texts %) "Total Account Value"))
        divider (find-first-node view-node #(= "account-equity-total-divider"
                                               (:data-role (node-attrs %))))
        total-index (index-of strings "Total Account Value")
        spot-index (index-of strings "Spot")
        perps-index (index-of strings "Perps")
        vaults-index (index-of strings "Vaults")]
    (is (some? total-label))
    (is (contains? (class-set total-label) "text-trading-text"))
    (is (some? divider))
    (is (contains? (class-set divider) "border-base-300"))
    (is (every? number? [total-index spot-index perps-index vaults-index]))
    (when (every? number? [total-index spot-index perps-index vaults-index])
      (is (< total-index spot-index perps-index vaults-index)))
    (is (some #(= "$168,501.46" %) strings))))

(deftest classic-account-equity-renders-confirmed-zero-and-unknown-vault-states-test
  (let [confirmed-zero (view/account-equity-view (scoped-vault-state [])
                                                  {:show-funding-actions? false})
        unknown (view/account-equity-view
                 (scoped-vault-state [{:vault-address "0xvault-a"
                                       :equity 0
                                       :equity-raw "bad"}])
                 {:show-funding-actions? false})]
    (is (some? (find-first-node confirmed-zero #(contains? (direct-texts %) "Vaults"))))
    (is (some? (find-first-node confirmed-zero #(contains? (direct-texts %) "$0.00"))))
    (is (some? (find-first-node unknown #(contains? (direct-texts %) "Vaults"))))
    ;; The existing known Spot and Perps values remain readable while only the
    ;; untrusted Vaults row and its dependent total say --.
    (is (>= (count (filter #(= "--" %) (collect-strings unknown))) 2))))

(deftest unified-account-summary-does-not-adopt-the-classic-vault-total-test
  (let [state (-> (first (fixtures/unified))
                  :state
                  (assoc :wallet {:address account-a})
                  (assoc :vaults {:user-equities [{:vault-address "0xvault-a"
                                                    :equity 12500.0
                                                    :equity-raw "12500.0"}]
                                  :user-equities-for-address account-a
                                  :loading {:user-equities? false}
                                  :errors {:user-equities nil}}))
        baseline (metrics-for (-> state
                                  (dissoc :vaults)))
        metrics (metrics-for state)
        strings (collect-strings (view/account-equity-view state {:show-funding-actions? false}))]
    (is (= (select-keys baseline [:account-value-display
                                  :unified-account-ratio
                                  :maintenance-margin
                                  :unified-account-leverage
                                  :isolated-notional])
           (select-keys metrics [:account-value-display
                                 :unified-account-ratio
                                 :maintenance-margin
                                 :unified-account-leverage
                                 :isolated-notional])))
    (is (nil? (some #{"Total Account Value" "Vaults"} strings)))))
