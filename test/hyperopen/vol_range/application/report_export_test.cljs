(ns hyperopen.vol-range.application.report-export-test
  "Vol Range report export (HO-SPEC-014 v2.1 section 12.8): the JSON bundle
  carries the format and every version, the valuation method, campaigns
  with baselines, closures, coverage, breaks and window figures, the order
  mappings, fills with attribution and signed fees, marks with their candle
  times and fetch time, and never a key that names signing material; the
  CSV has human-readable columns and escapes its cells. Synthetic fixtures
  only."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.application.report-export :as export]
            [hyperopen.vol-range.application.report-headline-test :as h]
            [hyperopen.vol-range.report-fixtures :as fx]))

(defn- doc
  ([s] (doc s h/now))
  ([s now]
   (export/json-document {:report (get-in s [:vol-range :report]) :spot-meta (get-in s [:spot :meta])
                          :account-address h/account :now-ms now})))

(def scope (assoc (fx/scope) :account-address h/account))

(def manual
  "A VRT/USDC trade no ladder owns, inside the Since tracking began window."
  (assoc fx/manual-row :time (+ fx/t0 (* 2 fx/hour))))

(defn- all-keys [x]
  (cond (map? x) (concat (keys x) (mapcat all-keys (vals x)))
        (coll? x) (mapcat all-keys x)
        :else []))

(defn- with-transfer
  "A VRT transfer whose raw venue row also carries keys that must never be
  exported."
  [s]
  (let [rec {:schema-version 1 :record-id "ledger:x|1|0xt|spotTransfer|VRT" :kind :ledger-update
             :account-address h/account :time (+ fx/t0 (* 2 fx/hour) 1000) :hash "0xt" :delta-type "spotTransfer"
             :token "VRT" :base-token-id "9001" :amount "-5" :classification :base-transfer
             :raw-delta {:type "spotTransfer" :token "VRT" :amount "-5" :signature {:r "0x1" :s "0x2" :v 27}
                         :nested [{:privateKey "k" :agent_key "a" :nonce 7 :ok "kept"}]}
             :saved-at-ms fx/t0}]
    (assoc-in s [:vol-range :report :ledger :updates (:record-id rec)] rec)))

(deftest the-bundle-has-its-format-versions-and-valuation-test
  (let [d (doc (h/state {}))]
    (is (= "hyperopen.vol-range.report" (:format d)))
    (is (= 1 (:format-version d)))
    (is (= h/now (:exported-at-ms d)))
    (is (= h/account (:account-address d)))
    (is (= :hyperliquid-mainnet (:network d)))
    (is (= {:export-format 1 :accounting-model 1 :campaign-schema 1 :ledger-schema 1 :model 1 :precision 1
            :estimator "parkinson-v1"}
           (:versions d)))
    (is (= "(C_b - C_a) + (q_b - q_a) * P_b" (get-in d [:valuation :equations :contribution])))
    (is (= "unavailable" (get-in d [:valuation :cost-basis])))
    (is (= 5000 (get-in d [:policy :boundary-skew-ms])))
    (is (= #{:format :format-version :exported-at-ms :network :account-address :versions :valuation :policy
             :selection :storage :campaigns :orders :fills :ledger-updates :coverage-records :balance-checks :marks}
           (set (keys d))))))

(deftest campaigns-carry-baseline-coverage-and-windows-test
  (let [d (doc (h/state {}))
        c (first (:campaigns d))
        since (first (:windows c))]
    (is (= 1 (count (:campaigns d))))
    (is (= {:q0 "150" :p0 "80" :p0-source :book-midpoint :p0-bid "79.99" :p0-ask "80.01"
            :p0-observed-at-ms (+ fx/t0 120) :c0 "0"
            :boundary {:barrier-id (str "rb-" fx/t0) :requested-at-ms fx/t0 :received-at-ms (+ fx/t0 180)
                       :skew-ms 5000}}
           (:baseline c)))
    (is (= "VRT/USDC" (:market c)))
    (is (true? (:open? c)))
    (is (nil? (:closure c)))
    (is (= {:model 1 :precision 1 :estimator "parkinson-v1"} (:model-versions c)))
    (is (= :complete (get-in c [:coverage :state])))
    (is (seq (get-in c [:coverage :intervals])))
    (is (= [] (get-in c [:coverage :breaks])))
    (is (= [:since-start :7d :30d] (mapv :window (:windows c))))
    (testing "Since tracking began: the window boundaries, marks, boundary state and figures"
      (is (= {:window :since-start :a-ms fx/t0 :b-ms h/b :a-source :inception-midpoint :status :ok :reason nil}
             (select-keys since [:window :a-ms :b-ms :a-source :status :reason])))
      (is (= {:status :ok :price "80" :source :inception-midpoint :observed-at-ms (+ fx/t0 120)} (:a-mark since)))
      (is (= {:status :ok :price "70" :source :hourly-close :candle-open-ms (- h/b fx/hour)} (:b-mark since)))
      (is (= ["150" "0" "157" "-534.8"] ((juxt :q-a :c-a :q-b :c-b) since)))
      (is (= {:net-pnl "-1544.8" :hold-pnl "-1500" :contribution "-44.8"} (:figures since)))
      (is (= :complete (get-in since [:coverage :state]))))
    (testing "7D and 30D before tracking: unavailable, with no figures"
      (is (= [:unavailable :unavailable] (mapv :status (rest (:windows c)))))
      (is (= [nil nil] (mapv :figures (rest (:windows c))))))))

(deftest a-closed-campaign-keeps-its-closure-and-no-windows-test
  (let [old (fx/campaign-record {:campaign-id "cmp-old" :at (- fx/t0 (* 2 h/day)) :scope* scope})
        s (-> (h/state {:campaigns [h/vrt old]})
              (assoc-in [:vol-range :report :closures "cmp-old"]
                        {:campaign-id "cmp-old" :ended-at-ms fx/t0 :reason :user-restart
                         :successor-campaign-id "cmp-vrt" :saved-at-ms fx/t0 :schema-version 1}))
        c (some #(when (= "cmp-old" (:campaign-id %)) %) (:campaigns (doc s)))]
    (is (= {:ended-at-ms fx/t0 :reason :user-restart :successor-campaign-id "cmp-vrt" :saved-at-ms fx/t0}
           (:closure c)))
    (is (false? (:open? c)))
    (is (= [] (:windows c)))
    (is (= fx/t0 (get-in c [:coverage :to-ms])) "coverage ends at the closure")))

(deftest fills-carry-attribution-fees-and-deltas-test
  (let [s (h/state {:rows [fx/base-fee-row manual]})
        d (doc s)
        [tracked manual] (:fills d)]
    (is (= 2 (count (:fills d))))
    (testing "a tracked fill: ladder, operation, logical leg, CLOID and oid, signed fee in its token"
      (is (= {:tracked? true :campaign-id "cmp-vrt" :market "VRT/USDC" :side :buy :px "80" :sz "10"
              :fee "0.004" :fee-token "VRT" :oid "7001" :tid "503" :source :rest}
             (select-keys tracked [:tracked? :campaign-id :market :side :px :sz :fee :fee-token :oid :tid :source])))
      (is (= {:grid-id "grid-vrt" :operation-id "op-vrt" :logical-id "buy-0" :side :buy :rung-index 0
              :campaign-key (:campaign-key h/vrt) :recovered? false :sent-after-ms (+ fx/t0 200)
              :predecessor-grid-id nil}
             (:order tracked)))
      (is (= {:base-delta "10" :cash-delta "-800" :net-base-delta "9.996" :net-cash-delta "-800" :fee-kind :base}
             (:deltas tracked)))
      (is (= [] (:breaks tracked))))
    (testing "an untracked fill: no deltas, and the break it caused"
      (is (false? (:tracked? manual)))
      (is (nil? (:deltas manual)))
      (is (nil? (:order manual)))
      (is (= [{:campaign-id "cmp-vrt" :reason :break/untracked-same-base-trade :at-ms (:time manual)}]
             (:breaks manual))))
    (testing "the campaign lists its tracked fills and its fees by token"
      (let [c (first (:campaigns d))]
        (is (= [(:fill-key tracked)] (:fill-keys c)))
        (is (= {"VRT" "0.004"} (get-in c [:activity :fees-by-token])))
        (is (= :break/untracked-same-base-trade (get-in c [:coverage :breaks 0 :reason])))))))

(deftest orders-map-cloids-and-oids-to-ladders-and-predecessors-test
  (let [replacement (assoc (fx/grid {:grid-id "grid-new" :scope* scope}) :predecessor {:grid-id "grid-vrt"})
        s (assoc-in (h/state {}) [:vol-range :report :attribution] (attribution/attribution-index [replacement]))
        orders (:orders (doc s))]
    (is (= [{:cloid fx/cloid-buy :oid "7001" :grid-id "grid-new" :operation-id "op-vrt" :logical-id "buy-0"
             :side :buy :rung-index 0 :campaign-key (:campaign-key h/vrt) :recovered? false
             :sent-after-ms (+ fx/t0 200) :predecessor-grid-id "grid-vrt"}
            {:cloid fx/cloid-sell :oid "7002" :grid-id "grid-new" :operation-id "op-vrt" :logical-id "sell-0"
             :side :sell :rung-index 0 :campaign-key (:campaign-key h/vrt) :recovered? false
             :sent-after-ms (+ fx/t0 200) :predecessor-grid-id "grid-vrt"}]
           orders))))

(deftest marks-coverage-and-ledger-updates-are-included-test
  (let [d (doc (with-transfer (h/state {})))]
    (is (= {:coin "@9001" :source "candleSnapshot 1h close"
            :closes [{:candle-open-ms (- h/a7 fx/hour) :close "80"} {:candle-open-ms (+ h/h0 (* 2 fx/hour)) :close "70"}]
            :covered [[(- h/a7 h/day) (+ h/b h/day)]] :fetched-at-ms nil :error nil}
           (first (:marks d))))
    (is (= #{[:fills "9001"] [:fills "9002"] [:ledger "9001"] [:ledger "9002"]}
           (set (map (juxt :stream :base-token-id) (:coverage-records d)))))
    (is (= h/wide (:complete (first (:coverage-records d)))))
    (is (= [{:type "spotTransfer" :token "VRT" :amount "-5" :nested [{:ok "kept"}]}]
           (mapv :raw-delta (:ledger-updates d)))
        "the raw venue row is exported without signing keys")
    (is (= :break/base-transfer (get-in d [:campaigns 0 :coverage :breaks 0 :reason])))))

(deftest no-forbidden-key-at-any-depth-test
  (let [d (doc (with-transfer (h/state {:rows [fx/base-fee-row manual]})))
        text (export/json-text d)]
    (is (not-any? export/forbidden-key? (all-keys d)))
    (doseq [k ["signature" "privateKey" "agent_key" "\"nonce\"" "secret" "session"]]
      (is (not (str/includes? text k)) k))
    (testing "the forbidden-key rule"
      (doseq [k [:private-key :privateKey "private_key" :signature :agent-key :nonce :unlocked-session :secret
                 :api-key :mnemonic]]
        (is (export/forbidden-key? k) (str k)))
      (doseq [k [:fill-key :cloid :oid :grid-id :fee :hash :tid]]
        (is (not (export/forbidden-key? k)) (str k))))))

(deftest json-text-keeps-namespaced-keywords-test
  (let [parsed (js->clj (js/JSON.parse (export/json-text (doc (h/state {:rows [manual]})))))]
    (is (= 1 (get parsed "format-version")))
    (is (= "break/untracked-same-base-trade" (get-in parsed ["fills" 0 "breaks" 0 "reason"])))
    (is (= "book-midpoint" (get-in parsed ["campaigns" 0 "baseline" "p0-source"])))
    (is (= "since-start" (get-in parsed ["campaigns" 0 "windows" 0 "window"])))))

(deftest the-fills-csv-test
  (let [csv (export/fills-csv (doc (h/state {:rows [fx/base-fee-row manual]})))
        lines (str/split csv #"\r\n")]
    (is (= (str "Time (UTC),Market,Coin,Side,Price,Size,Fee,Fee token,Builder fee,Liquidity,Tracking,"
                "Tracking ID,Ladder,Operation,Order leg,Rung,Client order ID,Order ID,Trade ID,Transaction hash,"
                "Source,Outside activity")
           (first lines)))
    (is (not (re-find #"(?i)grid" (first lines))) "column names say Ladder")
    (is (str/ends-with? csv "\r\n"))
    (is (= 3 (count lines)))
    (is (= (str "2026-10-21T14:42:00.000Z,VRT/USDC,'@9001,Buy,80,10,0.004,VRT,,Maker,Tracked,cmp-vrt,grid-vrt,"
                "op-vrt,buy-0,0,,7001,503,0xhash503,rest,")
           (second lines)))
    (is (str/ends-with? (nth lines 2) ",Not tracked,,,,,,,8888,601,0xhash601,rest,break/untracked-same-base-trade"))))

(deftest csv-cells-are-quoted-and-formula-safe-test
  (is (= "plain" (export/csv-cell "plain")))
  (is (= "\"a,b\"" (export/csv-cell "a,b")))
  (is (= "\"say \"\"hi\"\"\"" (export/csv-cell "say \"hi\"")))
  (is (= "\"line\nbreak\"" (export/csv-cell "line\nbreak")))
  (is (= "-0.1" (export/csv-cell "-0.1")) "a negative decimal stays a number")
  (is (= "'=SUM(A1)" (export/csv-cell "=SUM(A1)")))
  (is (= "'@cmd" (export/csv-cell "@cmd")))
  (is (= "'-x" (export/csv-cell "-x")))
  (is (= "" (export/csv-cell nil)))
  (is (= "break/base-transfer" (export/csv-cell :break/base-transfer))))

(deftest filenames-test
  (is (= "hyperopen-vol-range-report-0x123456-20261021T1542Z.json"
         (export/filename {:kind :json :account-address h/account :now-ms (+ fx/t0 (* 2 fx/hour))})))
  (is (= "hyperopen-vol-range-fills-0x123456-20261021T1542Z.csv"
         (export/filename {:kind :csv :account-address (str/upper-case h/account) :now-ms (+ fx/t0 (* 2 fx/hour))}))))

(deftest the-export-is-deterministic-test
  (let [s (h/state {:rows [fx/base-fee-row manual]})]
    (is (= (export/json-text (doc s)) (export/json-text (doc s))))))
