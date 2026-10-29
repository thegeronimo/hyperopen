(ns hyperopen.vol-range.infrastructure.ledger-ingest-test
  "WebSocket fills reach the reporting ledger idempotently and never touch
  coverage (HO-SPEC-014 v2.1 sections 9.1, 9.2, 12.2). Synthetic rows and an
  in-memory store only."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.config :as config]
            [hyperopen.vol-range.infrastructure.ledger-ingest :as ingest]
            [hyperopen.vol-range.infrastructure.ledger-store :as store]
            [hyperopen.vol-range.infrastructure.ledger-store-test :as store-test]
            [hyperopen.vol-range.report-fixtures :as fx]))

(def account "0x1234567890abcdef1234567890abcdef12345678")

(defn- campaign []
  (-> (fx/campaign-record)
      (assoc-in [:scope :account-address] account)))

(defn- state []
  {:wallet {:address account}
   :spot {:meta fx/spot-meta}
   :vol-range {:campaigns {"cmp-vrt" (campaign)} :campaign-closures {}
               :report {:account-address account :ledger {:fills {}}}}})

(defn- envelope [rows & [acct]]
  {:kind :fills :scope {:network fx/network :account-address (or acct account)} :source :websocket
   :received-at-ms (+ fx/t0 (* 3 fx/hour)) :rows rows})

(def perp-row (fx/fill-row {:coin "BTC" :side "B" :px "60000" :sz "0.1" :time (+ fx/t0 fx/hour) :tid 900 :oid 9900}))
(def old-row (fx/fill-row {:side "B" :px "70" :sz "1" :time (- fx/t0 (* 2 fx/hour)) :tid 400 :oid 7001}))
(def vrs-row (fx/fill-row {:coin "@9002" :side "B" :px "10" :sz "1" :time (+ fx/t0 fx/hour) :tid 905 :oid 9905}))

(deftest relevant-fills-test
  (let [rows [fx/buy-7-row fx/other-pair-row perp-row old-row vrs-row]
        fills (ingest/relevant-fills (state) (envelope rows))]
    (is (= #{501 602} (set (map (comp js/parseInt :tid) fills)))
        "the campaign pair and another VRT pair; not perps, older rows or other tokens")
    (is (every? #(= :websocket (:source %)) fills))
    (is (= [] (ingest/relevant-fills (state) (envelope rows "0x9999999999999999999999999999999999999999")))
        "another account's stream")
    (is (= [] (ingest/relevant-fills (assoc-in (state) [:vol-range :campaign-closures "cmp-vrt"] {:campaign-id "cmp-vrt"})
                                     (envelope rows)))
        "no open campaign")
    (is (= [] (ingest/relevant-fills (assoc-in (state) [:spot :meta] nil) (envelope rows)))
        "no spot metadata: left to the backfill")
    (let [known (:fill-key (first (ingest/relevant-fills (state) (envelope [fx/buy-7-row]))))]
      (is (= [] (ingest/relevant-fills (assoc-in (state) [:vol-range :report :ledger :fills known] {})
                                       (envelope [fx/buy-7-row])))
          "already in the loaded ledger"))))

(deftest a-streamed-fill-is-stored-once-and-shown-test
  (async done
    (let [io (store-test/memory-io)
          s (atom (state))
          put! (partial store/put-records! io)
          env (envelope [fx/buy-7-row fx/buy-7-row perp-row])]
      (-> (ingest/on-fills-envelope! s env {:put-records! put! :now-ms! (constantly 1)})
          (.then (fn [n]
                   (is (= 1 n) "one record for a repeated row")
                   (is (= 1 (count (get-in @s [:vol-range :report :ledger :fills])))
                       "shown in the loaded report")
                   ;; The same frame again is not even queued.
                   (is (nil? (ingest/on-fills-envelope! s env {:put-records! put! :now-ms! (constantly 2)})))
                   (store/load-account! io account)))
          (.then (fn [{:keys [records]}]
                   (is (= [:ledger-fill] (mapv :kind records)))
                   (is (not-any? #(= :coverage (:kind %)) records) "the stream never writes coverage")
                   (done)))
          (.catch (fn [e] (is false (str e)) (done)))))))

(deftest a-failed-put-is-only-logged-test
  (async done
    (let [s (atom (state))]
      (-> (ingest/on-fills-envelope! s (envelope [fx/buy-7-row])
                                     {:put-records! (fn [_] (js/Promise.reject (js/Error. "QuotaExceededError")))
                                      :now-ms! (constantly 1)})
          (.then (fn [r]
                   (is (nil? r))
                   (is (empty? (get-in @s [:vol-range :report :ledger :fills])) "nothing shown that wasn't stored")
                   (done)))))))

(deftest reporting-off-ingests-nothing-test
  (with-redefs [config/config (assoc-in config/config [:vol-range :reporting-enabled?] false)]
    (is (nil? (ingest/on-fills-envelope! (atom (state)) (envelope [fx/buy-7-row]))))))
