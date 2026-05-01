(ns hyperopen.portfolio.optimizer.application.history-loader.request-plan
  (:require [hyperopen.portfolio.optimizer.application.history-loader.instruments :as instruments]))

(def default-interval
  :1d)

(def default-bars
  365)

(def default-priority
  :high)

(defn build-history-request-plan
  [universe opts]
  (let [opts* (or opts {})
        interval (or (:interval opts*) default-interval)
        bars (or (:bars opts*) default-bars)
        priority (or (:priority opts*) default-priority)
        now-ms (:now-ms opts*)
        funding-window-ms (:funding-window-ms opts*)
        funding-start-ms (or (:funding-start-ms opts*)
                             (when (and (number? now-ms)
                                        (number? funding-window-ms))
                               (- now-ms funding-window-ms)))
        funding-end-ms (or (:funding-end-ms opts*)
                           now-ms)
        market-universe (vec (remove instruments/vault-instrument? (or universe [])))
        all-groups (instruments/sorted-coin-groups market-universe (constantly true))
        perp-groups (instruments/sorted-coin-groups market-universe instruments/perp-instrument?)
        vault-groups (instruments/sorted-vault-groups universe)
        candle-request (fn [[coin instruments*]]
                         {:coin coin
                          :instrument-ids (mapv instruments/normalize-instrument-id instruments*)
                          :opts {:interval interval
                                 :bars bars
                                 :priority priority
                                 :cache-key [:portfolio-optimizer :candles coin interval bars]
                                 :dedupe-key [:portfolio-optimizer :candles coin interval bars]}})
        funding-request (fn [[coin instruments*]]
                          {:coin coin
                           :instrument-ids (mapv instruments/normalize-instrument-id instruments*)
                           :opts {:priority priority
                                  :start-time-ms funding-start-ms
                                  :end-time-ms funding-end-ms
                                  :cache-key [:portfolio-optimizer :funding coin funding-start-ms funding-end-ms]
                                  :dedupe-key [:portfolio-optimizer :funding coin funding-start-ms funding-end-ms]}})
        vault-detail-request (fn [[address instruments*]]
                               {:vault-address address
                                :instrument-ids (mapv instruments/normalize-instrument-id instruments*)
                                :opts {:priority priority
                                       :cache-key [:portfolio-optimizer :vault-details address]
                                       :dedupe-key [:portfolio-optimizer :vault-details address]}})]
    {:candle-requests (mapv candle-request (:groups all-groups))
     :funding-requests (mapv funding-request (:groups perp-groups))
     :vault-detail-requests (mapv vault-detail-request (:groups vault-groups))
     :warnings (vec (concat (:warnings all-groups)
                            (:warnings vault-groups)))}))
