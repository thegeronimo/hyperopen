(ns hyperopen.api.gateway.account
  (:require [hyperopen.api.endpoints.account :as account-endpoints]
            [hyperopen.api.fetch-compat :as fetch-compat]))

(defn normalize-user-abstraction-mode
  [abstraction]
  (account-endpoints/normalize-user-abstraction-mode abstraction))

(defn fetch-user-funding-history!
  [{:keys [post-info!
           normalize-funding-history-filters
           normalize-info-funding-rows
           sort-funding-history-rows]}
   _store
   address
   opts]
  (if-not address
    (js/Promise.resolve [])
    (let [{:keys [start-time-ms end-time-ms]} (normalize-funding-history-filters opts)]
      (account-endpoints/request-user-funding-history! post-info!
                                                       normalize-info-funding-rows
                                                       sort-funding-history-rows
                                                       address
                                                       start-time-ms
                                                       end-time-ms
                                                       opts))))

(defn request-user-funding-history!
  [{:keys [fetch-user-funding-history!]}
   address
   opts]
  (fetch-user-funding-history! nil address opts))

(defn request-spot-clearinghouse-state!
  [{:keys [post-info!]}
   address
   opts]
  (account-endpoints/request-spot-clearinghouse-state! post-info! address opts))

(defn fetch-spot-clearinghouse-state!
  [{:keys [log-fn
           request-spot-clearinghouse-state!
           begin-spot-balances-load
           apply-spot-balances-success
           apply-spot-balances-error]}
   store
   address
   opts]
  (fetch-compat/fetch-spot-clearinghouse-state!
   {:log-fn log-fn
    :request-spot-clearinghouse-state! request-spot-clearinghouse-state!
    :begin-spot-balances-load begin-spot-balances-load
    :apply-spot-balances-success apply-spot-balances-success
    :apply-spot-balances-error apply-spot-balances-error}
   store
   address
   opts))

(defn request-user-abstraction!
  [{:keys [post-info!]}
   address
   opts]
  (account-endpoints/request-user-abstraction! post-info! address opts))

(defn fetch-user-abstraction!
  [{:keys [log-fn
           request-user-abstraction!
           normalize-user-abstraction-mode
           apply-user-abstraction-snapshot]}
   store
   address
   opts]
  (fetch-compat/fetch-user-abstraction!
   {:log-fn log-fn
    :request-user-abstraction! request-user-abstraction!
    :normalize-user-abstraction-mode normalize-user-abstraction-mode
    :apply-user-abstraction-snapshot apply-user-abstraction-snapshot}
   store
   address
   opts))

(defn request-clearinghouse-state!
  [{:keys [post-info!]}
   address
   dex
   opts]
  (account-endpoints/request-clearinghouse-state! post-info! address dex opts))

(defn fetch-clearinghouse-state!
  [{:keys [log-fn
           request-clearinghouse-state!
           apply-perp-dex-clearinghouse-success
           apply-perp-dex-clearinghouse-error]}
   store
   address
   dex
   opts]
  (fetch-compat/fetch-clearinghouse-state!
   {:log-fn log-fn
    :request-clearinghouse-state! request-clearinghouse-state!
    :apply-perp-dex-clearinghouse-success apply-perp-dex-clearinghouse-success
    :apply-perp-dex-clearinghouse-error apply-perp-dex-clearinghouse-error}
   store
   address
   dex
   opts))

(defn fetch-perp-dex-clearinghouse-states!
  [{:keys [fetch-clearinghouse-state!]}
   store
   address
   dex-names
   opts]
  (fetch-compat/fetch-perp-dex-clearinghouse-states!
   {:fetch-clearinghouse-state! fetch-clearinghouse-state!}
   store
   address
   dex-names
   opts))
