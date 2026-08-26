(ns hyperopen.portfolio.optimizer.universe-search-actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.actions :as actions]
            [hyperopen.portfolio.optimizer.application.view-model.universe-search :as universe-search]))

(def ^:private type-filter-path
  [:portfolio-ui :optimizer :universe-search-type-filter])

(def ^:private quote-filter-path
  [:portfolio-ui :optimizer :universe-search-quote-filter])

(def ^:private query-path
  [:portfolio-ui :optimizer :universe-search-query])

(def ^:private active-index-path
  [:portfolio-ui :optimizer :universe-search-active-index])

(defn- save-many-values
  [effects]
  (second (first effects)))

(deftest type-filter-action-normalizes-and-resets-the-cursor-test
  ;; The chips dispatch (name key), so the value always arrives as a string.
  (is (= [[:effects/save-many
           [[type-filter-path :perp]
            [active-index-path 0]]]]
         (actions/set-portfolio-optimizer-universe-search-type-filter {} "perp")))
  (testing "an unknown value falls back to :all rather than filtering everything out"
    (is (= [[:effects/save-many
             [[type-filter-path :all]
              [active-index-path 0]]]]
           (actions/set-portfolio-optimizer-universe-search-type-filter {} "nonsense")))))

(deftest quote-filter-action-upper-cases-the-token-test
  (is (= [[:effects/save-many
           [[quote-filter-path "USDC"]
            [active-index-path 0]]]]
         (actions/set-portfolio-optimizer-universe-search-quote-filter {} "usdc")))
  (is (= [[:effects/save-many
           [[quote-filter-path :all]
            [active-index-path 0]]]]
         (actions/set-portfolio-optimizer-universe-search-quote-filter {} "all"))))

(deftest typing-keeps-the-facets-but-clearing-the-field-resets-them-test
  (testing "narrowing a query under an active chip is the point of the chip"
    (is (= [[:effects/save-many
             [[query-path "pump"]
              [active-index-path 0]]]]
           (actions/set-portfolio-optimizer-universe-search-query {} "pump"))))
  (testing "an emptied field is a full reset, so no facet can sit invisibly"
    ;; The draft add-asset popover shares these paths and renders no chips.
    (is (= [[:effects/save-many
             [[query-path ""]
              [active-index-path 0]
              [type-filter-path :all]
              [quote-filter-path :all]]]]
           (actions/set-portfolio-optimizer-universe-search-query {} "")))))

(def ^:private catalog
  (into {}
        (map (fn [i]
               (let [coin (str "PUMP" i)]
                 [(str "perp:" coin)
                  {:key (str "perp:" coin)
                   :market-type :perp
                   :coin coin
                   :symbol (str coin "-USDC")
                   :base coin
                   :quote "USDC"
                   :shortable? true}])))
        (range 40)))

(defn- state-with-catalog
  []
  {:asset-selector {:market-by-key catalog}
   :portfolio {:optimizer {:draft {:universe []
                                   :constraints {:long-only? false}}}}})

(defn- added-universe
  [effects]
  (->> (save-many-values effects)
       (some (fn [[path value]]
               (when (= [:portfolio :optimizer :draft :universe] path)
                 value)))))

(deftest add-matches-adds-the-batch-and-clears-the-search-test
  (let [effects (actions/add-portfolio-optimizer-universe-matches
                 (state-with-catalog)
                 ["perp:PUMP0" "perp:PUMP1" "perp:PUMP2"])
        values (save-many-values effects)]
    (is (= ["perp:PUMP0" "perp:PUMP1" "perp:PUMP2"]
           (mapv :instrument-id (added-universe effects))))
    (testing "the batch records a custom universe source"
      (is (some #(= [[:portfolio :optimizer :draft :metadata :universe-source]
                     {:kind :custom}]
                    %)
                values)))
    (testing "the search resets as a unit after the batch lands"
      (is (some #(= [query-path ""] %) values))
      (is (some #(= [type-filter-path :all] %) values))
      (is (some #(= [quote-filter-path :all] %) values)))))

(deftest add-matches-is-capped-and-emits-a-single-heavy-effect-test
  ;; A two-character query matches hundreds of instruments and every one of them
  ;; joins the history request. The batch is capped, and it coalesces into ONE
  ;; prefetch effect so it satisfies the same :allow-duplicate-heavy-effects?
  ;; false policy as the single add.
  (let [keys* (mapv #(str "perp:PUMP" %) (range 40))
        effects (actions/add-portfolio-optimizer-universe-matches
                 (state-with-catalog)
                 keys*)
        heavy (filterv #(= :effects/load-portfolio-optimizer-history (first %))
                       effects)]
    (is (= universe-search/add-all-limit (count (added-universe effects))))
    (is (>= 1 (count heavy))
        "never more than one history effect, whatever the batch size")))

(deftest add-matches-is-a-no-op-when-nothing-resolves-test
  (is (= [] (actions/add-portfolio-optimizer-universe-matches
             (state-with-catalog)
             [])))
  (is (= [] (actions/add-portfolio-optimizer-universe-matches
             (state-with-catalog)
             ["perp:DOES-NOT-EXIST"])))
  (testing "instruments already in the universe are skipped"
    (let [state (assoc-in (state-with-catalog)
                          [:portfolio :optimizer :draft :universe]
                          [{:instrument-id "perp:PUMP0"
                            :market-type :perp
                            :coin "PUMP0"}])
          effects (actions/add-portfolio-optimizer-universe-matches
                   state
                   ["perp:PUMP0"])]
      (is (= [] effects)))))
