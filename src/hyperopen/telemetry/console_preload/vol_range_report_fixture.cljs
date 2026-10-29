(ns hyperopen.telemetry.console-preload.vol-range-report-fixture
  "Debug fixture for the Vol Range Portfolio report (HO-SPEC-014 v2.1
  section 13.5; reporting stage R4). Debug/QA only, loaded by the dev
  console preload: `seedVolRangeFixture({report: \"<scenario>\"})` commits
  SYNTHETIC tracking campaigns, ladders and ledger rows for the fixture
  wallet through the same committed stores the app reads
  (`journal/commit!`, `ledger-store/put-records!`) and resolves once they
  are durable. It never builds, signs or sends an order.

  Scenarios (times relative to now; VRT/USDC is `@9001`, VRS/USDC `@9002`):

  - \"headline-buy\": VRT tracking began 3 h ago with q0 = 150 at P0 = 80;
    its ladder bought 7 at 76.4 half an hour later; fills and ledger
    coverage are proven through now. With hourly closes of 70 the report
    shows −1,544.80 / −1,500.00 / −44.80 for Since tracking began.
  - \"external-transfer\": the same plus a 5 VRT `spotTransfer` out an
    hour after tracking began: an attribution break.
  - \"two-markets\": VRT and VRS tracking that both began 8 days ago; the
    VRT ladder bought 7 at 76.4 two and a half hours ago; a VRS trade no
    ladder owns breaks VRS three hours ago."
  (:require [hyperopen.system :as app-system]
            [hyperopen.vol-range.application.campaign :as campaign]
            [hyperopen.vol-range.application.fill-records :as fill-records]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.application.report-coverage :as coverage]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.infrastructure.journal :as journal]
            [hyperopen.vol-range.infrastructure.ledger-store :as ledger-store]))

(def ^:private hour 3600000)
(def ^:private day (* 24 hour))

(def spot-meta
  {:universe [{:name "@9001" :index 9001 :tokens [9001 0]}
              {:name "@9002" :index 9002 :tokens [9002 0]}]
   :tokens [{:name "USDC" :index 0 :szDecimals 8}
            {:name "VRT" :index 9001 :szDecimals 2}
            {:name "VRS" :index 9002 :szDecimals 2}]})

(defn- scope [account coin idx symbol]
  {:network (contracts/current-network) :account-address account :coin coin :market-type :spot
   :spot-pair-index idx :base-token-id (str idx) :quote-token-id "0" :symbol symbol :quote-symbol "USDC"
   :sz-decimals 2 :asset-id (+ 10000 idx)})

(defn- barrier-read [t]
  {:barrier-id (str "rb-fixture-" t) :requested-at-ms t :received-at-ms (+ t 180) :complete? true
   :balances [{:coin "USDC" :token 0 :total "3000" :hold "0"}
              {:coin "VRT" :token 9001 :total "150" :hold "0"}
              {:coin "VRS" :token 9002 :total "40" :hold "0"}]})

(defn- campaign-record [{:keys [id sc t0 bid ask]}]
  (campaign/baseline-record {:campaign-id id :scope sc :read (barrier-read t0)
                             :bbo {:bid bid :ask ask :observed-at-ms (+ t0 120)}
                             :started-by :first-placement :operation-id (str "op-" id) :grid-id (str "grid-" id)
                             :now-ms (+ t0 200)}))

(def ^:private cloid-buy "0x484f565200000000000000000000fb01")
(def ^:private cloid-sell "0x484f565200000000000000000000fb02")

(defn- ladder
  "A VRT ladder admitted at `at`: buy rung 0 (oid 7901) and sell rung 0
  (oid 7902), both accepted."
  [sc at]
  (-> (lifecycle/new-grid {:grid-id "grid-fixture-vrt" :scope sc :scope-key "fixture-vrt"
                           :operation-id "op-fixture-vrt" :plan-id "fixture" :expires-after-ms (+ at 30000)
                           :now-ms at
                           :legs [{:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4" :size "7" :cloid cloid-buy}
                                  {:leg-id "sell-0" :side :sell :rung-index 0 :price "88.4" :size "30"
                                   :cloid cloid-sell}]})
      (lifecycle/mark-sending "op-fixture-vrt" "fixture-attempt" (+ at 10))
      (evidence/apply-placement-delivery
       "op-fixture-vrt"
       {:delivery :response
        :leg-results [{:cloid cloid-buy :oid "7901" :placement-state :accepted :order-state :resting}
                      {:cloid cloid-sell :oid "7902" :placement-state :accepted :order-state :resting}]}
       (+ at 400))))

(defn- fill-row [{:keys [coin side px sz time tid oid]}]
  {:coin coin :px px :sz sz :side side :time time :tid tid :oid oid :crossed false :fee "0" :feeToken "USDC"
   :hash (str "0xfixture" tid) :dir (if (= "B" side) "Buy" "Sell") :startPosition "150.0" :closedPnl "0.0"})

(defn- fill-record [account row now-ms]
  (ledger-store/fill-record
   (fill-records/normalize-fill {:network (contracts/current-network) :account-address account :row row
                                 :source :rest :received-at-ms (+ (:time row) 300)})
   now-ms))

(defn- coverage-records
  "Complete fills and ledger coverage of `[from to)` for `token`."
  [account token from to now-ms]
  (for [stream [:fills :ledger]]
    (coverage/merge-coverage nil {:stream stream :base-token-id token :complete [[from to]] :failure nil}
                             {:account-address account :now-ms now-ms})))

(defn- vrt-buy [t] (fill-row {:coin "@9001" :side "B" :px "76.4" :sz "7" :time t :tid 990101 :oid 7901}))

(defn- headline-buy [account now]
  (let [t0 (- now (* 3 hour))
        sc (scope account "@9001" 9001 "VRT")]
    {:journal [(campaign-record {:id "cmp-fixture-vrt" :sc sc :t0 t0 :bid "79.99" :ask "80.01"})
               (jr/grid-record (ladder sc (+ t0 200)) (+ t0 500))]
     :ledger (into [(fill-record account (vrt-buy (+ t0 (* 30 60000))) now)]
                   (coverage-records account "9001" (- t0 60000) (- now 10000) now))
     :t0 t0}))

(defn- external-transfer [account now]
  (let [{:keys [t0] :as base} (headline-buy account now)
        row {:time (+ t0 hour) :hash "0xfixturetransfer"
             :delta {:type "spotTransfer" :token "VRT" :amount "-5" :usdcValue "-400" :user account
                     :destination "0x00000000000000000000000000000000000000de" :fee "0"}}]
    (update base :ledger into (attribution/ledger-update-records
                               {:markets (attribution/spot-markets spot-meta) :tracked #{"9001"}
                                :account-address account :row row :now-ms now}))))

(defn- two-markets [account now]
  (let [t0 (- now (* 8 day))
        vrt (scope account "@9001" 9001 "VRT")
        vrs (scope account "@9002" 9002 "VRS")
        untracked (fill-row {:coin "@9002" :side "B" :px "10" :sz "1" :time (- now (* 3 hour)) :tid 990201
                             :oid 7999})]
    {:journal [(campaign-record {:id "cmp-fixture-vrt" :sc vrt :t0 t0 :bid "79.99" :ask "80.01"})
               (campaign-record {:id "cmp-fixture-vrs" :sc vrs :t0 t0 :bid "9.99" :ask "10.01"})
               (jr/grid-record (ladder vrt (+ t0 200)) (+ t0 500))]
     :ledger (-> [(fill-record account (vrt-buy (- now (* 150 60000))) now)
                  (fill-record account untracked now)]
                 (into (coverage-records account "9001" (- t0 60000) (- now 10000) now))
                 (into (coverage-records account "9002" (- t0 60000) (- now 10000) now)))
     :t0 t0}))

(def scenarios
  {"headline-buy" headline-buy "external-transfer" external-transfer "two-markets" two-markets})

(defn seed-report!
  "Commit `scenario`'s records for `account` at `now-ms` and put the
  scenario's spot metadata in the app store; resolves to a JS object {ok
  scenario t0 reused} (or {ok false error}). Seeding again in the same
  browser only refreshes the spot metadata (`reused` true): the journal
  already holds the scenario, and recommitting it at a later `now` would
  add a second copy of its fills."
  [scenario account now-ms]
  (if-let [build (get scenarios (str scenario))]
    (let [{:keys [journal ledger t0]} (build account now-ms)
          ids (set (map :record-id journal))]
      (swap! app-system/store assoc-in [:spot :meta] spot-meta)
      (-> (journal/load-all!)
          (.then (fn [{:keys [records]}]
                   (if (some #(contains? ids (:record-id %)) records)
                     (clj->js {:ok true :scenario scenario :reused true})
                     (-> (journal/commit! journal)
                         (.then (fn [_] (ledger-store/put-records! ledger)))
                         (.then (fn [_] (clj->js {:ok true :scenario scenario :t0 t0 :reused false})))))))
          (.catch (fn [e] (clj->js {:ok false :error (str e)})))))
    (js/Promise.resolve (clj->js {:ok false :error (str "Unknown report scenario " scenario)}))))
