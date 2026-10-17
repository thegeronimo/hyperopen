(ns hyperopen.portfolio.optimizer.application.tear-sheet-model-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.actions.tear-sheet :as tear-sheet-actions]
            [hyperopen.portfolio.optimizer.application.tear-sheet.model :as model]
            [hyperopen.portfolio.optimizer.application.tear-sheet.sources :as sources]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.routes :as routes]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.portfolio.optimize.tear-sheet-card :as card]))

(def ^:private day-ms (* 24 60 60 1000))

(def ^:private now-ms (.getTime (js/Date. "2026-10-16T00:00:00.000Z")))

(def ^:private address (str "0x" (apply str (repeat 40 "a"))))

(def ^:private vault-id (str "vault:" address))

(defn- summary
  "`n` samples `gap-days` apart ending at now, account value wobbling upward."
  [n gap-days]
  (let [times (mapv #(- now-ms (* day-ms gap-days (- n 1 %))) (range n))
        values (mapv #(* 100000 (+ 1 (* 0.002 %) (* 0.01 (js/Math.sin %)))) (range n))]
    {:accountValueHistory (mapv (fn [t v] [t (str v)]) times values)
     :pnlHistory (mapv (fn [t v] [t (str (- v 100000))]) times values)}))

(defn- btc-points
  []
  (mapv (fn [d] {:time-ms (- now-ms (* d day-ms)) :close (+ 60000 (* 50 (js/Math.cos d)))})
        (range 400 -1 -1)))

(defn- state
  []
  (-> {}
      (assoc-in contracts/draft-universe-path
                [{:instrument-id vault-id :market-type :vault :name "Alpha vault" :vault-address address}
                 {:instrument-id "perp:BTC" :market-type :perp :coin "BTC"}])
      (assoc-in contracts/history-data-path
                {:vault-details-by-address {address {:portfolio {:all-time (summary 60 6)
                                                                 :month (summary 45 0.66)}
                                                     :leader-commission 0.1}}
                 :api-v2-history {:series-by-instrument {"perp:BTC" {:points (btc-points)}}}})))

(def ^:private result
  {:status :solved :instrument-ids [vault-id "perp:BTC"] :target-weights [0.6 0.4]})

(deftest vault-run-test
  (is (true? (model/vault-run? result)))
  (is (false? (model/vault-run? (assoc result :instrument-ids ["perp:BTC" "perp:ETH"]))) "no vault")
  (is (false? (model/vault-run? (assoc result :status :infeasible))) "not solved"))

(deftest options-test
  (is (= model/defaults (model/options {})))
  (let [opts (model/options (assoc-in {} contracts/ui-tear-sheet-path
                                      {:range :all-time :rebalance :bogus :profit-share? nil}))]
    (is (= :all-time (:range opts)))
    (is (= :daily (:rebalance opts)) "unknown values fall back")
    (is (false? (:profit-share? opts)))))

(deftest stitched-vault-rows-test
  (let [details {:portfolio {:all-time (summary 60 6) :month (summary 45 0.66)}}
        rows (sources/stitched-vault-rows details)
        month-start (- now-ms (* day-ms 0.66 44))]
    (is (< 60 (count rows)) "dense month samples replace the sparse tail")
    (is (apply < (map :time-ms rows)) "time ordered")
    (is (every? #(< (:time-ms %) month-start) (take 50 rows)))
    (is (= 0.1 (sources/fee-rate {:leader-commission 0.1})))
    (is (= 0.1 (sources/fee-rate {:leader-commission 10})) "a percent is read as a fraction")
    (is (= 0 (sources/fee-rate {})))))

(deftest backtest-model-test
  (let [m (model/backtest-model (state) result model/defaults)]
    (is (seq (get-in m [:run :rows])))
    (is (= ["Alpha vault" "BTC"] (mapv :label (:legs m))) "heaviest first, universe labels")
    (is (= :strategy (:id (first (:raw-series m)))))
    (is (= ["equal-weight"] (keep :coin (rest (:raw-series m))))
        "HLP is selected but not loaded, so only equal weight is drawn")
    (is (false? (:available? (some #(when (= (sources/hlp-value) (:value %)) %) (:benchmarks m))))
        "HLP stays a chip, marked loading")
    (testing "the vault is sampled every few days over 1Y, so path metrics are estimated"
      (is (true? (:sparse? m)))
      (let [rows (mapcat :rows (get-in m [:metrics :groups]))
            by-key (into {} (map (juxt :key identity)) rows)]
        (is (= :interpolated-samples (get-in by-key [:sharpe :portfolio-reason])))
        (is (not= :interpolated-samples (get-in by-key [:cumulative-return :portfolio-reason])))))
    (is (number? (:cumulative-return (model/hero-stats m))))))

(deftest drawdown-mode-test
  (let [m (model/backtest-model (state) result (assoc model/defaults :mode :drawdown))]
    (is (every? #(<= (:value %) 1e-9) (:raw-points (first (:raw-series m))))
        "drawdown never rises above zero")))

(deftest tear-sheet-route-test
  (is (= {:kind :optimize-scenario :path "/portfolio/optimize/draft/tear-sheet" :scenario-id "draft" :view :tear-sheet}
         (routes/parse-portfolio-route "/portfolio/optimize/draft/tear-sheet")))
  (is (nil? (:view (routes/parse-portfolio-route "/portfolio/optimize/draft"))))
  (is (= "/portfolio/optimize/scn-1/tear-sheet" (routes/portfolio-optimize-tear-sheet-path "scn-1")))
  (is (routes/portfolio-optimize-route? "/portfolio/optimize/scn-1/tear-sheet")))

(defn- save
  [k v]
  [:effects/save (conj contracts/ui-tear-sheet-path k) v])

(deftest set-option-action-test
  (is (= [(save :range :two-year)]
         (tear-sheet-actions/set-portfolio-optimizer-tear-sheet-option {} :range "two-year")))
  (testing "adding any market or vault saves it, clears the search and fetches its history"
    (is (= [(save :compare [(sources/hlp-value) "ETH"])
            (save :benchmark-search "")
            (save :benchmark-open? false)
            [:effects/fetch-candle-snapshot :coin "ETH" :interval :1d :bars sources/benchmark-candle-bars]]
           (tear-sheet-actions/set-portfolio-optimizer-tear-sheet-option {} :add-benchmark "ETH")))
    (is (= [(save :benchmark-search "")]
           (tear-sheet-actions/set-portfolio-optimizer-tear-sheet-option {} :add-benchmark (sources/hlp-value)))
        "already picked"))
  (is (= [(save :compare [])]
         (tear-sheet-actions/set-portfolio-optimizer-tear-sheet-option {} :remove-benchmark (sources/hlp-value))))
  (is (= [(save :benchmark-search "so") (save :benchmark-open? true)]
         (tear-sheet-actions/set-portfolio-optimizer-tear-sheet-option {} :benchmark-search "so")))
  (is (= [(save :benchmark-open? false)]
         (tear-sheet-actions/set-portfolio-optimizer-tear-sheet-option {} :benchmark-key "Escape")))
  (is (= [(save :show-all-weights? true)]
         (tear-sheet-actions/set-portfolio-optimizer-tear-sheet-option {} :show-all-weights? true)))
  (is (= [[:effects/save (conj contracts/ui-tear-sheet-path :profit-share?) false]]
         (tear-sheet-actions/set-portfolio-optimizer-tear-sheet-option {} :profit-share? false)))
  (is (= [] (tear-sheet-actions/set-portfolio-optimizer-tear-sheet-option {} :range :decade)))
  (is (= [] (tear-sheet-actions/set-portfolio-optimizer-tear-sheet-option {} :bogus :daily))))

(deftest benchmark-fetch-effects-test
  (is (= [[:effects/api-fetch-vault-benchmark-details sources/hlp-address]
          [:effects/fetch-candle-snapshot :coin "BTC" :interval :1d :bars sources/benchmark-candle-bars]]
         (sources/benchmark-fetch-effects {} [(sources/hlp-value) "BTC" "BTC"])))
  (is (= [] (sources/benchmark-fetch-effects
             {:candles {"BTC" {:1d [{:t 1 :c 2}]}}
              :vaults {:benchmark-details-by-address
                       {sources/hlp-address {:portfolio {:all-time (summary 40 5)}}}}}
             [(sources/hlp-value) "BTC"]))))

(deftest any-market-benchmark-test
  (let [st (-> (state)
               (assoc-in contracts/ui-tear-sheet-path {:compare ["ETH"]})
               (assoc-in [:candles "ETH" :1d]
                         (mapv (fn [d] {:t (- now-ms (* d day-ms)) :c (str (+ 3000 d))}) (range 400 -1 -1)))
               (assoc-in [:asset-selector :markets] [{:coin "ETH" :symbol "ETH-USDC" :market-type :perp}]))
        m (model/backtest-model st result (model/options st))]
    (is (= ["equal-weight" "ETH"] (keep :coin (rest (:raw-series m)))))
    (is (true? (:available? (some #(when (= "ETH" (:value %)) %) (:benchmarks m)))))
    (testing "search offers markets not yet picked"
      (let [search (model/benchmark-search-model
                    (assoc-in st [:asset-selector :markets] [{:coin "ETH" :symbol "ETH-USDC"} {:coin "SOL" :symbol "SOL-USDC"}])
                    (assoc (model/options st) :benchmark-search "so"))]
        (is (= ["SOL"] (mapv :value (:candidates search))))
        (is (= "SOL" (:top-value search)))))))

(deftest entry-points-only-for-vault-runs-test
  (let [model {:scenario-id "draft" :result result}
        link (card/header-link model)
        card* (card/card (state) model)]
    (is (= [[:actions/navigate "/portfolio/optimize/draft/tear-sheet"]]
           (get-in link [1 :on :click])))
    (is (some? (hiccup/find-by-data-role card* "portfolio-optimizer-tear-sheet-card-return")))
    (is (nil? (card/header-link (assoc-in model [:result :instrument-ids] ["perp:BTC" "perp:ETH"]))))
    (is (nil? (card/card (state) (assoc-in model [:result :instrument-ids] ["perp:BTC" "perp:ETH"]))))))
