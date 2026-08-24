(ns hyperopen.portfolio.optimizer.instrument-keyed-codec-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.instrument-keyed-codec :as codec]))

(deftest normalize-worker-boundary-keywordizes-wire-enums-test
  (let [normalized (codec/normalize-worker-boundary
                    {:type "optimizer-result"
                     :payload {:status "solved"
                               :diagnostics {:reason "binding-constraint"}}
                     :history {:funding-by-instrument
                               {(keyword "perp:BTC")
                                {:source "market-funding-history"}}}})]
    (is (= :optimizer-result (:type normalized)))
    (is (= :solved (get-in normalized [:payload :status])))
    (is (= :binding-constraint
           (get-in normalized [:payload :diagnostics :reason])))
    (is (= :market-funding-history
           (get-in normalized
                   [:history :funding-by-instrument "perp:BTC" :source])))))

(deftest normalize-worker-boundary-stringifies-instrument-keyed-maps-by-key-test
  (let [btc (keyword "perp:BTC")
        purr (keyword "spot:PURR/USDC")
        normalized (codec/normalize-worker-boundary
                    {:current-portfolio {:by-instrument {btc {:weight 0.4}}}
                     :payload {:future-result
                               {:weights-by-instrument {btc 0.4
                                                       purr 0.6}
                                :labels-by-instrument {btc "BTC"
                                                       purr "PURR"}
                                :nested [{:target-weights-by-instrument
                                          {btc 0.5}}]}}
                     :diagnostics {:custom
                                   {:weight-sensitivity-by-instrument
                                    {purr {:max-delta 0.01}}}}})]
    (is (= {"perp:BTC" {:weight 0.4}}
           (get-in normalized [:current-portfolio :by-instrument])))
    (is (= {"perp:BTC" 0.4
            "spot:PURR/USDC" 0.6}
           (get-in normalized [:payload :future-result :weights-by-instrument])))
    (is (= {"perp:BTC" "BTC"
            "spot:PURR/USDC" "PURR"}
           (get-in normalized [:payload :future-result :labels-by-instrument])))
    (is (= {"perp:BTC" 0.5}
           (get-in normalized
                   [:payload :future-result :nested 0 :target-weights-by-instrument])))
    (is (= {"spot:PURR/USDC" {:max-delta 0.01}}
           (get-in normalized
                   [:diagnostics :custom :weight-sensitivity-by-instrument])))))

(deftest normalize-worker-boundary-stringifies-history-assumptions-and-keywordizes-enums-test
  (let [new-perp (keyword "perp:NEW")
        normalized (codec/normalize-worker-boundary
                    {:history-assumptions
                     {new-perp {:behavior "conservative"
                                :volatility 0.9
                                :max-weight 0.03
                                :correlation-floor 0.75}}})
        entry (get-in normalized [:history-assumptions "perp:NEW"])]
    (is (= {"perp:NEW" {:behavior :conservative
                        :volatility 0.9
                        :max-weight 0.03
                        :correlation-floor 0.75}}
           (:history-assumptions normalized))
        "instrument-id key is stringified and the :behavior value keywordized")
    (is (= :conservative (:behavior entry)))))

(deftest normalize-worker-boundary-handles-proxy-exposure-diagnostics-test
  ;; The proxy-assumption diagnostics ride the solved payload across the worker
  ;; boundary: exposure maps are proxy-instrument-keyed and the prior source is
  ;; an enum keyword.
  (let [eth (keyword "perp:ETH")
        btc (keyword "perp:BTC")
        normalized (codec/normalize-worker-boundary
                    {:payload {:risk-estimation
                               {:history-assumptions
                                {(keyword "perp:NEW")
                                 {:behavior "proxy"
                                  :prior-source "equal"
                                  :prior-weights {eth 0.5 btc 0.5}
                                  :regression-beta {eth 0.68 btc 0.32}
                                  :regression-beta-raw {eth 0.71 btc 0.29}
                                  :final-beta {eth 0.6 btc 0.4}}}}}})
        diag (get-in normalized
                     [:payload :risk-estimation :history-assumptions "perp:NEW"])]
    (is (= :proxy (:behavior diag)))
    (is (= :equal (:prior-source diag)))
    (is (= {"perp:ETH" 0.5 "perp:BTC" 0.5} (:prior-weights diag)))
    (is (= {"perp:ETH" 0.71 "perp:BTC" 0.29} (:regression-beta-raw diag)))
    (is (= {"perp:ETH" 0.6 "perp:BTC" 0.4} (:final-beta diag)))))

(deftest normalize-worker-boundary-preserves-unrelated-nested-map-keys-test
  (let [btc (keyword "perp:BTC")
        raw-weights {btc 1
                     :cash-buffer 0.05}
        normalized (codec/normalize-worker-boundary
                    {:payload {:metadata {:weights raw-weights}
                               :custom-by-id raw-weights}})]
    (is (= raw-weights
           (get-in normalized [:payload :metadata :weights])))
    (is (= raw-weights
           (get-in normalized [:payload :custom-by-id])))))

(deftest normalize-worker-boundary-covers-legacy-instrument-keyed-paths-test
  (let [btc (keyword "perp:BTC")]
    (doseq [path codec/instrument-keyed-map-paths]
      (let [normalized (codec/normalize-worker-boundary
                        (assoc-in {} path {btc {:weight 0.5}}))]
        (is (= {"perp:BTC" {:weight 0.5}}
               (get-in normalized path))
            (str "legacy path should still normalize: " (pr-str path)))))))

(deftest normalize-worker-boundary-stringifies-black-litterman-view-weights-test
  (let [btc (keyword "perp:BTC")
        normalized (codec/normalize-worker-boundary
                    {:return-model {:kind "black-litterman"
                                    :views [{:id "view-1"
                                             :kind "absolute"
                                             :instrument-id "perp:BTC"
                                             :weights {btc 1}}]}})]
    (is (= :black-litterman
           (get-in normalized [:return-model :kind])))
    (is (= {"perp:BTC" 1}
           (get-in normalized [:return-model :views 0 :weights])))))

(deftest worker-boundary-normalization-is-total-over-malformed-views-test
  ;; normalize-worker-boundary runs on EVERY message arriving from the
  ;; optimizer worker, inside the message listener. The Black-Litterman step
  ;; mapv's over [:return-model :views] without checking it is a sequence, so a
  ;; malformed payload took the listener down rather than producing a result --
  ;; and a string, being seqable in ClojureScript, was silently shredded into a
  ;; vector of characters instead.
  (is (= {:return-model {:views 0.4}}
         (codec/normalize-worker-boundary {:return-model {:views 0.4}}))
      "a scalar :views must pass through untouched rather than throw")
  (is (= {:return-model {:views "abc"}}
         (codec/normalize-worker-boundary {:return-model {:views "abc"}}))
      "a string :views must pass through untouched rather than become [\\a \\b \\c]")
  (is (= {:return-model {:views {:not "a sequence"}}}
         (codec/normalize-worker-boundary {:return-model {:views {:not "a sequence"}}}))
      "a map :views must pass through untouched")
  (is (= {:return-model {:views true}}
         (codec/normalize-worker-boundary {:return-model {:views true}}))
      "a boolean :views must pass through untouched"))

(deftest worker-boundary-normalization-still-rewrites-well-formed-views-test
  ;; The totality fix must not stop the step doing its job on real payloads.
  (is (= {:return-model {:views [{:weights {"perp:BTC" 0.5 "perp:ETH" -0.5}}]}}
         (codec/normalize-worker-boundary
          {:return-model {:views [{:weights {:perp:BTC 0.5 :perp:ETH -0.5}}]}})))
  (is (= {:return-model {:views []}}
         (codec/normalize-worker-boundary {:return-model {:views []}})))
  (is (= {:return-model {:views [{:confidence 0.3}]}}
         (codec/normalize-worker-boundary {:return-model {:views [{:confidence 0.3}]}}))
      "a view without :weights is left alone"))
