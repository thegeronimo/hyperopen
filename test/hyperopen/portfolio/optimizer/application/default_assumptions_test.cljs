(ns hyperopen.portfolio.optimizer.application.default-assumptions-test
  "Pure-model coverage for backend-recommended history assumptions: block
  normalization, member servability splits, entry building, plan assembly,
  and the outcome note copy."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.default-assumptions :as default-assumptions]))

(def ^:private coin-row
  {:instrument-id "external:tiingo:COIN"
   :instrument-kind :external-proxy
   :display-symbol "COIN"
   :basket-member-only true
   :trading-calendar "us_equity"
   :history {:status :available}})

(def ^:private smh-row
  {:instrument-id "external:tiingo:SMH"
   :instrument-kind :external-proxy
   :display-symbol "SMH"
   :basket-member-only true
   :history {:status :missing}})

(def ^:private discovery
  {:backend-id-by-local-id {"perp:NEW" "hl:perp:NEW"
                            "perp:ETH" "hl:perp:ETH"}
   :instruments-by-backend-id
   {"hl:perp:NEW" {:instrument-id "hl:perp:NEW"
                   :aliases {:hyperopen-market-key "perp:NEW"}
                   :history {:status :available}
                   :default-assumption
                   {:approach "proxy"
                    :members [{:instrument-id "hl:perp:ETH" :role "anchor"}
                              {:instrument-id "external:tiingo:COIN"
                               :role "sector_peer"}
                              {:instrument-id "external:tiingo:SMH"
                               :role "sector_peer"}]
                    :relationship-strength "medium"
                    :rationale "ETH anchors; COIN adds listed-sector exposure."
                    :review-status "generated"}}
    "hl:perp:ETH" {:instrument-id "hl:perp:ETH"
                   :aliases {:hyperopen-market-key "perp:ETH"}
                   :display-symbol "ETH"
                   :history {:status :available}}
    "external:tiingo:COIN" coin-row
    "external:tiingo:SMH" smh-row}})

(deftest recommendation-normalizes-the-discovery-block-test
  (let [rec (default-assumptions/recommendation discovery "hl:perp:NEW")]
    (is (= :proxy (:approach rec)))
    (is (= ["hl:perp:ETH" "external:tiingo:COIN" "external:tiingo:SMH"]
           (mapv :instrument-id (:members rec))))
    (is (= :medium (:relationship-strength rec)))
    (is (= "ETH anchors; COIN adds listed-sector exposure." (:rationale rec)))
    (is (= :generated (:review-status rec))))
  (testing "absent, unknown, and unusable blocks read as no recommendation"
    (is (nil? (default-assumptions/recommendation discovery "hl:perp:ETH"))
        "A row without a default-assumption block carries no recommendation.")
    (is (nil? (default-assumptions/recommendation discovery "hl:perp:UNKNOWN")))
    (is (nil? (default-assumptions/recommendation
               {:instruments-by-backend-id
                {"x" {:default-assumption {:approach "wat"}}}}
               "x"))
        "An unknown approach is not a behavior the engine backs.")
    (is (nil? (default-assumptions/recommendation
               {:instruments-by-backend-id
                {"x" {:default-assumption {:approach "proxy" :members []}}}}
               "x"))
        "A proxy recommendation with no members is unusable."))
  (testing "conservative recommendations carry empty members by design"
    (is (= :conservative
           (:approach (default-assumptions/recommendation
                       {:instruments-by-backend-id
                        {"x" {:default-assumption {:approach "conservative"
                                                   :members []}}}}
                       "x"))))))

(deftest member-availability-splits-by-servability-test
  (let [members (:members (default-assumptions/recommendation discovery "hl:perp:NEW"))
        {:keys [usable held]} (default-assumptions/member-availability
                               discovery "perp:NEW" members)]
    (is (= ["perp:ETH" "external:tiingo:COIN"] (mapv :instrument-id usable))
        "Tradable members map to their Hyperopen market key; external members keep the backend id.")
    (is (= [false true] (mapv :external? usable)))
    (is (= ["ETH" "COIN"] (mapv :label usable)))
    (is (= ["external:tiingo:SMH"] (mapv :instrument-id held))
        "A member the backend cannot serve yet is held, never applied."))
  (testing "self-references and unknown members"
    (let [{:keys [usable held]} (default-assumptions/member-availability
                                 discovery "perp:ETH"
                                 [{:instrument-id "hl:perp:ETH"}
                                  {:instrument-id "hl:perp:GHOST"}])]
      (is (= [] usable) "An asset never proxies itself.")
      (is (= ["hl:perp:GHOST"] (mapv :instrument-id held))
          "A member with no discovery row cannot be trusted to serve."))))

(deftest external-reference-instrument-builds-the-universe-shape-test
  (let [instrument (default-assumptions/external-reference-instrument coin-row)]
    (is (= {:instrument-id "external:tiingo:COIN"
            :market-type :external
            :coin "COIN"
            :symbol "COIN"
            :shortable? false
            :position-side :long
            :optimizer-history/instrument-id "external:tiingo:COIN"
            :optimizer-history/display-symbol "COIN"
            :optimizer-history/instrument-kind :external-proxy
            :optimizer-history/history-status :available}
           instrument)))
  (is (nil? (default-assumptions/external-reference-instrument
             (get-in discovery [:instruments-by-backend-id "hl:perp:ETH"])))
      "A tradable row is never wrapped as an external reference.")
  (is (nil? (default-assumptions/external-reference-instrument nil))))

(deftest proxy-entry-carries-basket-strength-and-prior-test
  (let [rec {:relationship-strength :high :rationale "twin"}
        entry (default-assumptions/proxy-entry
               rec
               [{:instrument-id "perp:BTC" :weight 3}
                {:instrument-id "external:tiingo:COIN" :weight 1}])]
    (is (= :proxy (:behavior entry)))
    (is (= ["perp:BTC" "external:tiingo:COIN"]
           (get-in entry [:proxy :instrument-ids])))
    (is (= :high (get-in entry [:proxy :relationship-strength])))
    (is (= {"perp:BTC" 0.75 "external:tiingo:COIN" 0.25}
           (get-in entry [:proxy :prior-weights]))
        "Weights renormalize over the applied members.")
    (is (= {:source :backend-recommendation :acknowledged? true :rationale "twin"}
           (:metadata entry))))
  (testing "weights are all-or-nothing, mirroring the import channel"
    (is (nil? (get-in (default-assumptions/proxy-entry
                       {}
                       [{:instrument-id "a" :weight 0.6}
                        {:instrument-id "b" :weight nil}])
                      [:proxy :prior-weights]))))
  (testing "strength defaults to medium when the block omits it"
    (is (= :medium (get-in (default-assumptions/proxy-entry
                            {} [{:instrument-id "a"}])
                           [:proxy :relationship-strength])))))

(deftest conservative-entry-seeds-behavior-defaults-test
  (let [entry (default-assumptions/conservative-entry {:rationale "No basket."})]
    (is (= :conservative (:behavior entry)))
    (is (= 0.8 (:volatility entry)))
    (is (= 0.03 (:max-weight entry)))
    (is (= 0.75 (:correlation-floor entry)))
    (is (= {:source :backend-recommendation :acknowledged? true :rationale "No basket."}
           (:metadata entry)))))

(defn- plan
  [overrides]
  (default-assumptions/recommendation-plan
   (merge {:discovery discovery
           :assumptions {}
           :universe-ids #{"perp:NEW" "perp:ETH"}
           :resolve-member (fn [{:keys [instrument-id]}]
                             (when (= "external:tiingo:COIN" instrument-id)
                               (default-assumptions/external-reference-instrument
                                coin-row)))
           :targets [{:instrument-id "perp:NEW" :backend-id "hl:perp:NEW"}]}
          overrides)))

(deftest recommendation-plan-applies-a-proxy-recommendation-test
  (let [result (plan {})
        entry (get-in result [:assumptions "perp:NEW"])]
    (is (= ["perp:ETH" "external:tiingo:COIN"]
           (get-in entry [:proxy :instrument-ids]))
        "In-universe and resolvable external members apply; the data-less one is held.")
    (is (= [{:instrument-id "perp:NEW"}]
           (mapv #(select-keys % [:instrument-id]) (:applied result))))
    (is (= ["external:tiingo:SMH"]
           (mapv :instrument-id (:held (first (:applied result))))))
    (is (= ["external:tiingo:COIN"]
           (mapv :instrument-id (:new-reference-instruments result)))
        "Only the out-of-universe member becomes a reference instrument.")
    (is (= [] (:skipped result)))))

(deftest recommendation-plan-skip-reasons-test
  (testing "an existing entry always wins"
    (let [result (plan {:assumptions {"perp:NEW" {:behavior :conservative}}})]
      (is (= [{:instrument-id "perp:NEW" :reason :already-configured}]
             (:skipped result)))
      (is (= [] (:applied result)))))
  (testing "no recommendation on the row"
    (let [result (plan {:targets [{:instrument-id "perp:ETH"
                                   :backend-id "hl:perp:ETH"}]})]
      (is (= [{:instrument-id "perp:ETH" :reason :no-recommendation}]
             (:skipped result)))))
  (testing "every member unavailable -> nothing applies, nothing half-applies"
    (let [result (plan {:resolve-member (constantly nil)
                        :universe-ids #{"perp:NEW"}})]
      (is (= [] (:applied result)))
      (is (= {} (:assumptions result)))
      (is (= :members-unavailable (-> result :skipped first :reason)))
      (is (= ["external:tiingo:SMH" "perp:ETH" "external:tiingo:COIN"]
             (mapv :instrument-id (-> result :skipped first :held)))
          "Data-less members and unresolvable members are all disclosed."))))

(deftest recommendation-plan-applies-conservative-test
  (let [conservative-discovery {:instruments-by-backend-id
                                {"hl:perp:GRAM"
                                 {:instrument-id "hl:perp:GRAM"
                                  :default-assumption {:approach "conservative"
                                                       :members []
                                                       :rationale "No basket."}}}}
        result (default-assumptions/recommendation-plan
                {:discovery conservative-discovery
                 :assumptions {}
                 :universe-ids #{"perp:GRAM"}
                 :resolve-member (constantly nil)
                 :targets [{:instrument-id "perp:GRAM" :backend-id "hl:perp:GRAM"}]})]
    (is (= :conservative (get-in result [:assumptions "perp:GRAM" :behavior])))
    (is (= [] (:new-reference-instruments result)))))

(deftest apply-note-message-discloses-outcomes-test
  (is (= "Applied 1 recommended assumption"
         (default-assumptions/apply-note-message
          {:applied [{:instrument-id "a" :held []}] :skipped []})))
  (let [message (default-assumptions/apply-note-message
                 {:applied [{:instrument-id "a"
                             :held [{:instrument-id "x" :label "SMH"}]}
                            {:instrument-id "b" :held []}]
                  :skipped [{:instrument-id "c"
                             :reason :members-unavailable
                             :held [{:instrument-id "y" :label "MU"}]}]})]
    (is (str/includes? message "Applied 2 recommended assumptions"))
    (is (str/includes? message "1 asset left waiting on backend member history"))
    (is (str/includes? message "SMH, MU")))
  (let [message (default-assumptions/apply-note-message
                 {:applied []
                  :skipped [{:instrument-id "c"
                             :reason :members-unavailable
                             :held [{:instrument-id "y" :label "MU"}]}]})]
    (is (str/includes? message "No recommendations applied"))
    (is (str/includes? message "MU"))))
