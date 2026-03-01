(ns hyperopen.api.instance
  (:require [hyperopen.api.compat :as api-compat]
            [hyperopen.api.gateway.account :as account-gateway]
            [hyperopen.api.gateway.market :as market-gateway]
            [hyperopen.api.gateway.orders :as order-gateway]
            [hyperopen.api.gateway.vaults :as vault-gateway]
            [hyperopen.api.info-client :as info-client]
            [hyperopen.api.service :as api-service]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.telemetry :as telemetry]))

(def info-url (:info-url info-client/default-config))

(def default-info-client-config
  (merge info-client/default-config
         {:info-url info-url}))

(defn make-default-api-service
  []
  (api-service/make-service
   {:info-client-config default-info-client-config
    :log-fn telemetry/log!}))

(defn- make-instance-post-info!
  [service]
  (fn post-info
    ([body]
     (post-info body {}))
    ([body opts]
     (api-service/request-info! service body opts))
    ([body opts attempt]
     (api-service/request-info! service body opts attempt))))

(defn- make-instance-normalize-funding-history-filters
  [now-ms-fn]
  (fn [filters]
    (funding-history/normalize-funding-history-filters
     filters
     (now-ms-fn)
     funding-history/default-window-ms)))

(defn- make-instance-market-request-ops
  [post-info! now-ms-fn]
  (letfn [(request-asset-contexts!
            ([] (request-asset-contexts! {}))
            ([opts]
             (market-gateway/request-asset-contexts!
              {:post-info! post-info!}
              opts)))
          (request-meta-and-asset-ctxs!
            ([dex]
             (request-meta-and-asset-ctxs! dex {}))
            ([dex opts]
             (market-gateway/request-meta-and-asset-ctxs!
              {:post-info! post-info!}
              dex
              opts)))
          (request-perp-dexs!
            ([] (request-perp-dexs! {}))
            ([opts]
             (market-gateway/request-perp-dexs!
              {:post-info! post-info!}
              opts)))
          (request-candle-snapshot!
            [coin & {:keys [interval bars priority]
                     :or {interval :1d bars 330 priority :high}}]
            (market-gateway/request-candle-snapshot!
             {:post-info! post-info!
              :now-ms-fn now-ms-fn}
             coin
             {:interval interval
              :bars bars
              :priority priority}))
          (request-spot-meta!
            ([] (request-spot-meta! {}))
            ([opts]
             (market-gateway/request-spot-meta!
              {:post-info! post-info!}
              opts)))
          (request-public-webdata2!
            ([] (request-public-webdata2! {}))
            ([opts]
             (market-gateway/request-public-webdata2!
              {:post-info! post-info!}
              opts)))
          (request-predicted-fundings!
            ([] (request-predicted-fundings! {}))
            ([opts]
             (market-gateway/request-predicted-fundings!
              {:post-info! post-info!}
              opts)))]
    {:request-asset-contexts! request-asset-contexts!
     :request-meta-and-asset-ctxs! request-meta-and-asset-ctxs!
     :request-perp-dexs! request-perp-dexs!
     :request-candle-snapshot! request-candle-snapshot!
     :request-spot-meta! request-spot-meta!
     :request-public-webdata2! request-public-webdata2!
     :request-predicted-fundings! request-predicted-fundings!}))

(defn- make-instance-market-state-ops
  [service now-ms-fn log-fn market-ops]
  (let [{:keys [request-perp-dexs!
                request-spot-meta!
                request-public-webdata2!
                request-meta-and-asset-ctxs!]} market-ops]
    (letfn [(ensure-perp-dexs-data!
              ([store]
               (ensure-perp-dexs-data! store {}))
              ([store opts]
               (api-service/ensure-perp-dexs-data! service store request-perp-dexs! opts)))
            (ensure-spot-meta-data!
              ([store]
               (ensure-spot-meta-data! store {}))
              ([store opts]
               (api-service/ensure-spot-meta-data! service store request-spot-meta! opts)))
            (ensure-public-webdata2!
              ([]
               (ensure-public-webdata2! {}))
              ([opts]
               (api-service/ensure-public-webdata2! service request-public-webdata2! opts)))
            (request-asset-selector-markets!
              ([store]
               (request-asset-selector-markets! store {:phase :full}))
              ([store opts]
               (market-gateway/request-asset-selector-markets!
                {:opts opts
                 :active-asset (:active-asset @store)
                 :ensure-perp-dexs-data! (fn [request-opts]
                                           (ensure-perp-dexs-data! store request-opts))
                 :ensure-spot-meta-data! (fn [request-opts]
                                           (ensure-spot-meta-data! store request-opts))
                 :ensure-public-webdata2! ensure-public-webdata2!
                 :request-meta-and-asset-ctxs! request-meta-and-asset-ctxs!
                 :build-market-state (fn [active-asset phase dexs spot-meta spot-asset-ctxs perp-results]
                                       (market-gateway/build-market-state now-ms-fn
                                                                          active-asset
                                                                          phase
                                                                          dexs
                                                                          spot-meta
                                                                          spot-asset-ctxs
                                                                          perp-results))
                 :log-fn log-fn})))]
      {:ensure-perp-dexs-data! ensure-perp-dexs-data!
       :ensure-spot-meta-data! ensure-spot-meta-data!
       :ensure-public-webdata2! ensure-public-webdata2!
       :request-asset-selector-markets! request-asset-selector-markets!})))

(defn- make-instance-order-ops
  [post-info! log-fn]
  (letfn [(request-frontend-open-orders!
            ([address]
             (request-frontend-open-orders! address {}))
            ([address opts]
             (order-gateway/request-frontend-open-orders!
              {:post-info! post-info!}
              address
              opts))
            ([address dex opts]
             (request-frontend-open-orders! address
                                            (cond-> (or opts {})
                                              (and dex (not= dex "")) (assoc :dex dex)))))
          (request-user-fills!
            ([address]
             (request-user-fills! address {}))
            ([address opts]
             (order-gateway/request-user-fills!
              {:post-info! post-info!}
              address
              opts)))
          (request-historical-orders-data! [address opts]
            (api-compat/fetch-historical-orders!
             {:log-fn log-fn
              :post-info! post-info!}
             address
             opts))
          (request-historical-orders!
            ([address]
             (request-historical-orders! address {}))
            ([address opts]
             (order-gateway/request-historical-orders!
              {:request-historical-orders-data! request-historical-orders-data!}
              address
              opts)))]
    {:request-frontend-open-orders! request-frontend-open-orders!
     :request-user-fills! request-user-fills!
     :request-historical-orders! request-historical-orders!}))

(defn- make-instance-account-ops
  [post-info! normalize-funding-history-filters-fn]
  (letfn [(request-user-funding-history-data! [address opts]
            (account-gateway/request-user-funding-history-data!
             {:post-info! post-info!
              :normalize-funding-history-filters normalize-funding-history-filters-fn
              :normalize-info-funding-rows funding-history/normalize-info-funding-rows
              :sort-funding-history-rows funding-history/sort-funding-history-rows}
             address
             opts))
          (request-user-funding-history!
            ([address]
             (request-user-funding-history! address {}))
            ([address opts]
             (account-gateway/request-user-funding-history!
              {:request-user-funding-history-data! request-user-funding-history-data!}
              address
              opts)))
          (request-spot-clearinghouse-state!
            ([address]
             (request-spot-clearinghouse-state! address {}))
            ([address opts]
             (account-gateway/request-spot-clearinghouse-state!
              {:post-info! post-info!}
              address
              opts)))
          (request-user-abstraction!
            ([address]
             (request-user-abstraction! address {}))
            ([address opts]
             (account-gateway/request-user-abstraction!
              {:post-info! post-info!}
              address
              opts)))
          (request-portfolio!
            ([address]
             (request-portfolio! address {}))
            ([address opts]
             (account-gateway/request-portfolio!
              {:post-info! post-info!}
              address
              opts)))
          (request-user-fees!
            ([address]
             (request-user-fees! address {}))
            ([address opts]
             (account-gateway/request-user-fees!
              {:post-info! post-info!}
              address
              opts)))
          (request-user-non-funding-ledger-updates!
            ([address start-time-ms]
             (request-user-non-funding-ledger-updates! address start-time-ms nil {}))
            ([address start-time-ms end-time-ms]
             (request-user-non-funding-ledger-updates! address start-time-ms end-time-ms {}))
            ([address start-time-ms end-time-ms opts]
             (account-gateway/request-user-non-funding-ledger-updates!
              {:post-info! post-info!}
              address
              start-time-ms
              end-time-ms
              opts)))
          (request-clearinghouse-state!
            ([address dex]
             (request-clearinghouse-state! address dex {}))
            ([address dex opts]
             (account-gateway/request-clearinghouse-state!
              {:post-info! post-info!}
              address
              dex
              opts)))]
    {:request-user-funding-history! request-user-funding-history!
     :request-spot-clearinghouse-state! request-spot-clearinghouse-state!
     :request-user-abstraction! request-user-abstraction!
     :request-portfolio! request-portfolio!
     :request-user-fees! request-user-fees!
     :request-user-non-funding-ledger-updates! request-user-non-funding-ledger-updates!
     :request-clearinghouse-state! request-clearinghouse-state!}))

(defn- make-instance-vault-ops
  [post-info!]
  (letfn [(request-vault-index!
            ([] (request-vault-index! {}))
            ([opts]
             (vault-gateway/request-vault-index! {:fetch-fn js/fetch}
                                                 opts)))
          (request-vault-summaries!
            ([] (request-vault-summaries! {}))
            ([opts]
             (vault-gateway/request-vault-summaries! {:post-info! post-info!}
                                                     opts)))
          (request-merged-vault-index!
            ([] (request-merged-vault-index! {}))
            ([opts]
             (vault-gateway/request-merged-vault-index! {:request-vault-index! request-vault-index!
                                                         :request-vault-summaries! request-vault-summaries!}
                                                        opts)))
          (request-user-vault-equities!
            ([address]
             (request-user-vault-equities! address {}))
            ([address opts]
             (vault-gateway/request-user-vault-equities! {:post-info! post-info!}
                                                         address
                                                         opts)))
          (request-vault-details!
            ([vault-address]
             (request-vault-details! vault-address {}))
            ([vault-address opts]
             (vault-gateway/request-vault-details! {:post-info! post-info!}
                                                   vault-address
                                                   opts)))
          (request-vault-webdata2!
            ([vault-address]
             (request-vault-webdata2! vault-address {}))
            ([vault-address opts]
             (vault-gateway/request-vault-webdata2! {:post-info! post-info!}
                                                    vault-address
                                                    opts)))]
    {:request-vault-index! request-vault-index!
     :request-vault-summaries! request-vault-summaries!
     :request-merged-vault-index! request-merged-vault-index!
     :request-user-vault-equities! request-user-vault-equities!
     :request-vault-details! request-vault-details!
     :request-vault-webdata2! request-vault-webdata2!}))

(defn make-api
  [{:keys [service now-ms-fn log-fn]}]
  (let [service* (or service (make-default-api-service))
        now-ms* (or now-ms-fn (fn [] (api-service/now-ms service*)))
        log-fn* (or log-fn (api-service/log-fn service*))
        post-info! (make-instance-post-info! service*)
        normalize-filters* (make-instance-normalize-funding-history-filters now-ms*)
        market-request-ops (make-instance-market-request-ops post-info! now-ms*)
        market-state-ops (make-instance-market-state-ops service* now-ms* log-fn* market-request-ops)
        order-ops (make-instance-order-ops post-info! log-fn*)
        account-ops (make-instance-account-ops post-info! normalize-filters*)
        vault-ops (make-instance-vault-ops post-info!)]
    (merge
     {:service service*
      :log-fn log-fn*
      :now-ms now-ms*
      :funding-position-side funding-history/funding-position-side
      :funding-history-row-id funding-history/funding-history-row-id
      :normalize-info-funding-row funding-history/normalize-info-funding-row
      :normalize-info-funding-rows funding-history/normalize-info-funding-rows
      :normalize-ws-funding-row funding-history/normalize-ws-funding-row
      :normalize-ws-funding-rows funding-history/normalize-ws-funding-rows
      :sort-funding-history-rows funding-history/sort-funding-history-rows
      :merge-funding-history-rows funding-history/merge-funding-history-rows
      :normalize-funding-history-filters normalize-filters*
      :filter-funding-history-rows (fn [rows filters]
                                     (funding-history/filter-funding-history-rows
                                      rows
                                      (normalize-filters* filters)))
      :get-request-stats (fn []
                           (api-service/get-request-stats service*))
      :reset-request-runtime! (fn []
                                (api-service/reset-service! service*))
      :request-info! post-info!}
     market-request-ops
     market-state-ops
     order-ops
     account-ops
     vault-ops)))
