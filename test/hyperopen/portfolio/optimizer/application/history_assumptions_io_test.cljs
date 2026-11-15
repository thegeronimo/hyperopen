(ns hyperopen.portfolio.optimizer.application.history-assumptions-io-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.history-assumptions-io :as io]))

(def ^:private now-ms 1751900000000)

(def ^:private universe
  [{:instrument-id "perp:WLFI" :coin "WLFI" :market-type :perp}
   {:instrument-id "perp:PUMP" :coin "PUMP" :market-type :perp}
   {:instrument-id "perp:BTC" :coin "BTC" :market-type :perp}])

(def ^:private catalog
  [{:key "perp:SOL" :coin "SOL" :market-type :perp}
   {:key "perp:DOGE" :coin "DOGE" :market-type :perp}
   {:key "perp:AVAX" :coin "AVAX" :market-type :perp}
   {:key "perp:NEAR" :coin "NEAR" :market-type :perp}
   {:key "perp:APT" :coin "APT" :market-type :perp}])

(def ^:private configured-entry
  {:behavior :proxy
   :expected-return 0.1
   :volatility 0.8
   :max-weight 0.05
   :proxy {:instrument-ids ["perp:BTC" "perp:SOL"]
           :relationship-strength :high
           :prior-weights {"perp:BTC" 0.6 "perp:SOL" 0.4}}
   :metadata {:source :agent-import :acknowledged? true :rationale "Sector basket"}})

(def ^:private export-inputs
  {:rows [{:instrument-id "perp:WLFI" :symbol "WLFI" :name "World Liberty"
           :kind "perp" :native-days 12 :status "needs-assumption"
           :verified? false :entry nil}
          {:instrument-id "perp:PUMP" :symbol "PUMP" :name "Pump"
           :kind "perp" :native-days 40 :status "configured"
           :verified? false :entry configured-entry}]
   :objective-kind :minimum-variance
   :now-ms now-ms
   :symbol-by-instrument-id {"perp:BTC" "BTC" "perp:ETH" "ETH" "perp:SOL" "SOL"}})

(deftest export-payload-envelope-test
  (let [{:keys [filename count document]} (io/export-payload export-inputs)]
    (is (= (str "history-assumptions-workflow-" now-ms ".json") filename))
    (is (= "proxy-workflow" (:export-scope document)))
    (is (= 2 count))
    (is (= io/export-document-type (:type document)))
    (is (= io/export-document-version (:version document)))
    (is (= now-ms (:exported-at document)))
    (is (vector? (:instructions document)))
    (is (seq (:instructions document)))
    (is (= {:minimum-history-days 360
            :preferred-history-days 720
            :candle-interval "1d"}
           (:history-policy document)))
    (is (= "minimum-variance" (:optimization-objective document)))
    (is (false? (:objective-uses-returns document)))))

(deftest export-payload-carries-no-candidate-menu-in-either-scope-test
  (is (not (contains? (get (io/export-payload export-inputs) :document)
                      :proxy-candidates)))
  (let [{:keys [filename document]} (io/export-payload
                                     (assoc export-inputs :scope :universe))]
    (is (= (str "history-assumptions-universe-" now-ms ".json") filename))
    (is (= "universe" (:export-scope document)))
    (is (not (contains? document :proxy-candidates)))
    (is (= ["unverified" "unverified"]
           (mapv :history-status (:assets document)))))
  (is (= "proxy-workflow"
         (get-in (io/export-payload (assoc export-inputs :scope :everything))
                 [:document :export-scope]))))

(deftest export-payload-asset-rows-test
  (let [assets (get-in (io/export-payload export-inputs) [:document :assets])
        [unconfigured configured] assets]
    (is (= {:instrument-id "perp:WLFI" :approach nil :proxies []
            :relationship-strength nil :expected-return-percent nil :rationale nil}
           (select-keys unconfigured [:instrument-id :approach :proxies
                                      :relationship-strength
                                      :expected-return-percent :rationale])))
    (is (= 12 (:native-days unconfigured)))
    (is (= "unverified" (:history-status unconfigured)))
    (is (= "proxy" (:approach configured)))
    (is (= [{:instrument-id "perp:BTC" :symbol "BTC" :weight 0.6}
            {:instrument-id "perp:SOL" :symbol "SOL" :weight 0.4}]
           (:proxies configured)))
    (is (= "high" (:relationship-strength configured)))
    (is (= 10 (:expected-return-percent configured)))
    (is (= "Sector basket" (:rationale configured)))))

(deftest export-uses-returns-flag-follows-objective-test
  (is (true? (get-in (io/export-payload (assoc export-inputs :objective-kind :max-sharpe))
                     [:document :objective-uses-returns]))))

(deftest strip-code-fences-test
  (is (= "{\"a\": 1}" (io/strip-code-fences "{\"a\": 1}")))
  (is (= "{\"a\": 1}" (io/strip-code-fences "```json\n{\"a\": 1}\n```")))
  (is (= "{\"a\": 1}" (io/strip-code-fences "```\n{\"a\": 1}")))
  (is (= "" (io/strip-code-fences nil))))

(defn- plan
  [data & [assumptions]]
  (io/import-plan {:assumptions (or assumptions {})
                   :universe universe
                   :proxy-pool catalog
                   :data data}))

(deftest import-rejects-unusable-data-test
  (is (= {:status :error :reason :invalid} (plan nil)))
  (is (= {:status :error :reason :invalid} (plan "text")))
  (is (= {:status :error :reason :invalid} (plan {"unrelated" 1})))
  (is (= {:status :error :reason :empty} (plan {"assets" []}))))

(deftest import-null-approach-is-untouched-test
  (let [result (plan {"assets" [{"instrument-id" "perp:WLFI" "approach" nil}]})]
    (is (= 0 (:applied result)))
    (is (= 1 (:unchanged result)))
    (is (= {} (:assumptions result)))))

(deftest import-unknown-asset-and-bad-approach-are-counted-test
  (let [result (plan {"assets" [{"symbol" "NOPE" "approach" "proxy"}
                                {"instrument-id" "perp:WLFI" "approach" "hedge"}]})]
    (is (= 1 (:unknown result)))
    (is (= 1 (:invalid result)))
    (is (= 0 (:applied result)))))

(deftest import-authors-proxy-entry-with-normalized-weights-test
  (let [result (plan {"assets" [{"symbol" "wlfi"
                                 "approach" "proxy"
                                 "proxies" [{"symbol" "BTC" "weight" 3}
                                            {"symbol" "SOL" "weight" 1}]
                                 "relationship-strength" "high"
                                 "expected-return-percent" "12.5"
                                 "rationale" "Anchor + ecosystem"}]})
        entry (get-in result [:assumptions "perp:WLFI"])]
    (is (= 1 (:applied result)))
    (is (= ["perp:WLFI"] (:applied-instrument-ids result)))
    (is (= :proxy (:behavior entry)))
    (is (= ["perp:BTC" "perp:SOL"] (get-in entry [:proxy :instrument-ids])))
    (is (= {"perp:BTC" 0.75 "perp:SOL" 0.25}
           (get-in entry [:proxy :prior-weights])))
    (is (= :high (get-in entry [:proxy :relationship-strength])))
    (is (= 0.125 (:expected-return entry)))
    (is (= 0.8 (:volatility entry)))
    (is (= 0.05 (:max-weight entry)))
    (is (= {:source :agent-import :acknowledged? true :rationale "Anchor + ecosystem"}
           (:metadata entry)))))

(deftest import-partial-weights-fall-back-to-equal-test
  (let [result (plan {"assets" [{"instrument-id" "perp:WLFI"
                                 "approach" "proxy"
                                 "proxies" [{"symbol" "BTC" "weight" 2}
                                            {"symbol" "SOL"}]}]})]
    (is (nil? (get-in result [:assumptions "perp:WLFI" :proxy :prior-weights])))))

(deftest import-resolves-agent-written-id-forms-test
  (let [result (plan {"assets" [{"instrument-id" "perp:WLFI"
                                 "approach" "proxy"
                                 "proxies" [{"symbol" "perp:DOGE" "weight" 0.5}
                                            {"symbol" "PERP:AVAX" "weight" 0.25}
                                            {"instrument-id" "NEAR" "weight" 0.125}
                                            {"symbol" "spot:SOL" "weight" 0.125}]}]})
        entry (get-in result [:assumptions "perp:WLFI"])]
    (is (= 1 (:applied result)))
    (is (= 0 (:skipped-proxies result)))
    (is (= ["perp:DOGE" "perp:AVAX" "perp:NEAR" "perp:SOL"]
           (get-in entry [:proxy :instrument-ids])))
    (is (= {"perp:DOGE" 0.5 "perp:AVAX" 0.25 "perp:NEAR" 0.125 "perp:SOL" 0.125}
           (get-in entry [:proxy :prior-weights])))))

(deftest import-drops-self-unknown-and-duplicate-proxies-test
  (let [result (plan {"assets" [{"instrument-id" "perp:WLFI"
                                 "approach" "proxy"
                                 "proxies" ["WLFI" "BTC" "BTC" "NOPE" "SOL"]}]})
        entry (get-in result [:assumptions "perp:WLFI"])]
    (is (= ["perp:BTC" "perp:SOL"] (get-in entry [:proxy :instrument-ids])))
    (is (= 3 (:skipped-proxies result)))))

(deftest import-clamps-basket-to-four-members-test
  (let [result (plan {"assets" [{"instrument-id" "perp:WLFI"
                                 "approach" "proxy"
                                 "proxies" ["BTC" "SOL" "DOGE" "AVAX" "NEAR"]}]})
        entry (get-in result [:assumptions "perp:WLFI"])]
    (is (= 4 (count (get-in entry [:proxy :instrument-ids]))))
    (is (= 1 (:skipped-proxies result)))))

(deftest import-empty-post-drop-basket-is-invalid-test
  (let [result (plan {"assets" [{"instrument-id" "perp:WLFI"
                                 "approach" "proxy"
                                 "proxies" ["NOPE" "WLFI"]}]})]
    (is (= 1 (:invalid result)))
    (is (= 2 (:skipped-proxies result)))
    (is (= {} (:assumptions result)))))

(deftest import-authors-conservative-entry-test
  (let [result (plan {"assets" [{"instrument-id" "perp:PUMP"
                                 "approach" "conservative"
                                 "expected-return-percent" 5
                                 "rationale" "No liquid peer co-moves."}]})
        entry (get-in result [:assumptions "perp:PUMP"])]
    (is (= 1 (:applied result)))
    (is (= :conservative (:behavior entry)))
    (is (= 0.05 (:expected-return entry)))
    (is (= 0.75 (:correlation-floor entry)))
    (is (= 0.03 (:max-weight entry)))
    (is (= "No liquid peer co-moves." (get-in entry [:metadata :rationale])))))

(deftest import-matching-behavior-preserves-guardrail-edits-test
  (let [existing (assoc configured-entry :volatility 1.2 :max-weight 0.04)
        result (plan {"assets" [{"instrument-id" "perp:PUMP"
                                 "approach" "proxy"
                                 "proxies" [{"symbol" "DOGE"}]
                                 "rationale" "Meme beta"}]}
                     {"perp:PUMP" existing})
        entry (get-in result [:assumptions "perp:PUMP"])]
    (is (= 1.2 (:volatility entry)))
    (is (= 0.04 (:max-weight entry)))
    (is (= ["perp:DOGE"] (get-in entry [:proxy :instrument-ids])))))

(deftest import-unedited-export-round-trip-is-a-no-op-test
  (let [document (get-in (io/export-payload export-inputs) [:document])
        data (js->clj (js/JSON.parse (js/JSON.stringify (clj->js document))))
        result (io/import-plan {:assumptions {"perp:PUMP" configured-entry}
                                :universe universe
                                :proxy-pool catalog
                                :data data})]
    (is (= :ok (:status result)))
    (is (= 0 (:applied result)))
    (is (= 2 (:unchanged result)))
    (is (= 0 (:unknown result)))
    (is (= {"perp:PUMP" configured-entry} (:assumptions result)))))

(deftest import-rationale-only-change-still-applies-test
  (let [result (plan {"assets" [{"instrument-id" "perp:PUMP"
                                 "approach" "proxy"
                                 "proxies" [{"symbol" "BTC" "weight" 0.6}
                                            {"symbol" "SOL" "weight" 0.4}]
                                 "relationship-strength" "high"
                                 "expected-return-percent" 10
                                 "rationale" "A NEW reason"}]}
                     {"perp:PUMP" configured-entry})]
    (is (= 1 (:applied result)))
    (is (= "A NEW reason"
           (get-in result [:assumptions "perp:PUMP" :metadata :rationale])))))

(deftest import-message-formats-test
  (testing "nothing applied says why, including dropped proxies"
    (is (= "No assumptions applied · 2 unknown assets skipped"
           (io/import-success-message {:applied 0 :unknown 2})))
    (is (= (str "No assumptions applied"
                " · 3 entries skipped — invalid approach or no resolvable proxies"
                " · 12 unrecognized proxies dropped")
           (io/import-success-message {:applied 0 :invalid 3 :skipped-proxies 12}))))
  (testing "applied with counts"
    (is (= "Configured 2 assets · 1 left as-is · 1 unknown asset skipped · 3 unknown proxies dropped — history validation continues on the cards below."
           (io/import-success-message {:applied 2 :unchanged 1 :unknown 1
                                       :invalid 0 :skipped-proxies 3}))))
  (testing "errors"
    (is (= "Import failed: no assets found in the file."
           (io/import-error-message :empty)))
    (is (= "Import failed: the file is not valid history-assumptions JSON."
           (io/import-error-message :invalid)))))
