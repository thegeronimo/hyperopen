(ns hyperopen.runtime.effect-adapters.vol-range-report-test
  "The report adapter's store writes (HO-SPEC-014 v2.1 sections 11.3, 12):
  a load writes the shown account's report (and the trading account's
  campaigns for the dock), then backfills and reads the hourly closes the
  selected window needs; a read-only load reads no account data from the
  venue; `:marks` merges completed closes and is not announced; a result for an account no longer shown is dropped; Start
  tracking reloads the campaigns it changed. Fakes only: no request reaches
  an exchange and nothing is signed or sent."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.api.trading.action-coordinator :as coord]
            [hyperopen.platform.locks :as locks]
            [hyperopen.runtime.effect-adapters.vol-range-report :as adapter]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.infrastructure.ledger-store :as store]
            [hyperopen.vol-range.infrastructure.ledger-store-test :as store-test]
            [hyperopen.vol-range.report-fixtures :as fx]))

(def account "0x1234567890abcdef1234567890abcdef12345678")
(def scope (assoc (fx/scope) :account-address account))
(def campaign (fx/campaign-record {:scope* scope}))

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

(defn- world []
  (let [calls (atom [])
        journal (atom {(:record-id campaign) campaign
                       "grid:grid-vrt" (jr/grid-record (fx/grid {:scope* scope}) (+ fx/t0 500))})
        io (store-test/memory-io)
        ids (atom 0)
        clock (atom (+ fx/t0 (* 6 fx/hour)))]
    {:calls calls :journal journal :io io
     :deps {:post-info! (fn [body _opts]
                          (swap! calls conj (get body "type"))
                          (js/Promise.resolve
                           (case (get body "type")
                             "spotClearinghouseState" {:balances [{:coin "VRT" :token 9001 :total "150" :hold "0"}]}
                             "frontendOpenOrders" []
                             "l2Book" {:levels [[{:px "89.99"}] [{:px "90.01"}]]}
                             "userFillsByTime" []
                             "userNonFundingLedgerUpdates" []
                             "candleSnapshot" (let [{:strs [startTime endTime]} (get body "req")]
                                                (filterv #(<= startTime (:t %) endTime) fx/candles))
                             nil)))
            :journal {:load-all! (fn [] (js/Promise.resolve {:records (vec (vals @journal)) :unreadable []}))
                      :commit! (fn [records]
                                 (swap! journal into (map (juxt :record-id identity)) records)
                                 (js/Promise.resolve true))}
            :ledger {:put-records! (partial store/put-records! io)
                     :load-account! (partial store/load-account! io)
                     :merge-coverage! (partial store/merge-coverage! io)
                     :put-balance-check! (partial store/put-balance-check! io)}
            :spot-meta! (constantly fx/spot-meta)
            :admission! (fn [opts body!]
                          (coord/with-account-admission! (assoc opts :allow-in-tab-coordination? true) body!))
            :now-ms! (fn [] (swap! clock inc))
            :random-id! (fn [p] (str p "-" (swap! ids inc)))
            :network fx/network}}))

(defn- with-deps [w f]
  (let [orig adapter/report-deps]
    (locks/reset-in-tab-state!)
    (set! adapter/report-deps (fn [_] (:deps w)))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (f)))
        (.finally (fn [] (set! adapter/report-deps orig))))))

(def ^:private h0 (* fx/hour (quot fx/t0 fx/hour)))
(def ^:private h1 (+ h0 fx/hour))
(def ^:private h2 (+ h0 (* 2 fx/hour)))
(def ^:private h3 (+ h0 (* 3 fx/hour)))

(defn- store-state [] (atom {:wallet {:address account} :spot {:meta fx/spot-meta}}))

(deftest a-load-writes-the-report-then-backfills-test
  (async done
    (let [w (world)
          s (store-state)]
      (-> (with-deps w #(adapter/vol-range-report nil s :load {:account-address account :backfill? true}))
          (.then (fn [_]
                   (let [report (get-in @s [:vol-range :report])]
                     (is (= account (:account-address report)))
                     (is (= ["cmp-vrt"] (keys (:campaigns report))))
                     (is (= {} (:busy report)) "not busy afterwards")
                     (is (= {:op :load :status :ok} (select-keys (:last report) [:op :status])))
                     (is (number? (get-in report [:backfill-at-ms account])))
                     (is (= ["9001"] (keys (get-in report [:ledger :coverage :fills]))) "reloaded after the backfill")
                     (is (= ["cmp-vrt"] (keys (get-in @s [:vol-range :campaigns])))
                         "the trading account's campaigns reach the dock too")
                     (is (some #{"userFillsByTime"} @(:calls w)))
                     (is (some #{"userNonFundingLedgerUpdates"} @(:calls w)))
                     (is (= {h1 "80" h2 "70" h3 "100"} (get-in report [:marks "@9001" :closes]))
                         "the selected window's hourly closes are read after the backfill"))
                   (done)))
          (.catch (fail done))))))

(deftest marks-merge-completed-closes-quietly-test
  (async done
    (let [w (world)
          s (store-state)]
      (-> (with-deps w #(adapter/vol-range-report nil s :marks {:requests [{:coin "@9001" :from-ms h0
                                                                             :to-ms (+ h0 (* 4 fx/hour))}]}))
          (.then (fn [_]
                   (let [report (get-in @s [:vol-range :report])
                         entry (get-in report [:marks "@9001"])]
                     (is (= {h1 "80" h2 "70" h3 "100"} (:closes entry)))
                     (is (= [[h0 (+ h0 (* 4 fx/hour))]] (:covered entry)) "a complete read proves its hours")
                     (is (nil? (:error entry)))
                     (is (number? (:marks-at-ms report)))
                     (is (= {} (:busy report)))
                     (is (nil? (:last report)) "a successful marks read is not announced")
                     (is (= ["candleSnapshot"] @(:calls w)))
                     (is (nil? (:account-address report)) "marks need no account and load none"))
                   (done)))
          (.catch (fail done))))))

(deftest a-failed-marks-read-is-announced-test
  (async done
    (let [w (assoc-in (world) [:deps :post-info!] (fn [_ _] (js/Promise.reject (js/Error. "offline"))))
          s (store-state)]
      (-> (with-deps w #(adapter/vol-range-report nil s :marks {:requests [{:coin "@9001" :from-ms h0 :to-ms h3}]}))
          (.then (fn [_]
                   (let [report (get-in @s [:vol-range :report])]
                     (is (= "offline" (get-in report [:marks "@9001" :error])))
                     (is (= [] (get-in report [:marks "@9001" :covered])) "nothing proven")
                     (is (= {:op :marks :status :error :error "offline"} (select-keys (:last report) [:op :status :error]))))
                   (done)))
          (.catch (fail done))))))

(deftest a-read-only-load-reads-nothing-from-the-venue-test
  (async done
    (let [w (world)
          s (atom {:wallet {:address "0x9999999999999999999999999999999999999999"}
                   :account-context {:spectate-mode {:active? true :address account}}})]
      (-> (with-deps w #(adapter/vol-range-report nil s :load {:account-address account :backfill? false}))
          (.then (fn [_]
                   (is (every? #{"candleSnapshot"} @(:calls w))
                       "no account read for a spectated account: only public hourly closes")
                   (is (= ["cmp-vrt"] (keys (get-in @s [:vol-range :report :campaigns]))))
                   (is (nil? (get-in @s [:vol-range :campaigns])) "the dock's campaigns are the trading account's only")
                   (done)))
          (.catch (fail done))))))

(deftest a-result-for-an-account-no-longer-shown-is-dropped-test
  (let [s {:wallet {:address "0x9999999999999999999999999999999999999999"}}
        loaded {:account-address account :journal-ok? true :campaigns {"cmp-vrt" campaign} :closures {}}]
    (is (= s (adapter/write-loaded s loaded 1)))
    (is (= {"cmp-vrt" campaign}
           (get-in (adapter/write-loaded {:wallet {:address account}} loaded 1) [:vol-range :campaigns])))
    (is (nil? (get-in (adapter/write-loaded {:wallet {:address account}} (assoc loaded :journal-ok? false) 1)
                      [:vol-range :campaigns]))
        "an unreadable journal never empties the dock's campaigns")))

(deftest start-tracking-reloads-the-campaigns-it-changed-test
  (async done
    (let [w (world)
          s (store-state)]
      (-> (with-deps w #(adapter/vol-range-report nil s :start-tracking
                                                  {:campaign-key (:campaign-key campaign) :account-address account}))
          (.then (fn [_]
                   (let [report (get-in @s [:vol-range :report])
                         successor (first (remove #(= "cmp-vrt" (:campaign-id %)) (vals (:campaigns report))))]
                     (is (= {:op :start-tracking :status :ok} (select-keys (:last report) [:op :status])))
                     (is (= :user-restart (:started-by successor)))
                     (is (= "90" (get-in successor [:baseline :p0])))
                     (is (= (:campaign-id successor) (get-in report [:closures "cmp-vrt" :successor-campaign-id])))
                     (is (contains? (get-in @s [:vol-range :campaign-closures]) "cmp-vrt")
                         "the dock's tracking line follows the restart")
                     (is (not-any? #{"userFillsByTime"} @(:calls w)) "a restart is not a backfill"))
                   (done)))
          (.catch (fail done))))))
