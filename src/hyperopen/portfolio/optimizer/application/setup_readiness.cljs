(ns hyperopen.portfolio.optimizer.application.setup-readiness
  (:require [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.orderbook-loader :as orderbook-loader]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]))

(defn- current-as-of-ms
  [state]
  (or (get-in state [:portfolio :optimizer :runtime :as-of-ms])
      (.now js/Date)))

(defn- build-request
  [state draft]
  (request-builder/build-engine-request
   {:draft draft
    :current-portfolio (current-portfolio/current-portfolio-snapshot state)
    :history-data (get-in state [:portfolio :optimizer :history-data])
    :market-cap-by-coin (get-in state [:portfolio :optimizer :market-cap-by-coin])
    :as-of-ms (current-as-of-ms state)
    :stale-after-ms (get-in state [:portfolio :optimizer :runtime :stale-after-ms])
    :funding-periods-per-year (get-in state
                                      [:portfolio :optimizer :runtime :funding-periods-per-year])}))

(defn- orderbook-cost-contexts
  [state request]
  (let [fallback-bps (get-in request [:execution-assumptions :fallback-slippage-bps])
        opts {:now-ms (:as-of-ms request)
              :fallback-bps fallback-bps
              :stale-after-ms (or (get-in state
                                          [:portfolio :optimizer :runtime :orderbook-stale-after-ms])
                                  orderbook-loader/default-stale-after-ms)}]
    (into {}
          (map (fn [{:keys [instrument-id coin]}]
                 [instrument-id
                  (dissoc (orderbook-loader/orderbook-cost-context state coin opts)
                          :coin)]))
          (:universe request))))

(defn- with-cost-contexts
  [state request]
  (let [generated-contexts (orderbook-cost-contexts state request)
        existing-contexts (get-in request [:execution-assumptions :cost-contexts-by-id])]
    (assoc-in request
              [:execution-assumptions :cost-contexts-by-id]
              (merge existing-contexts generated-contexts))))

(defn- instrument-ids
  [instruments]
  (set (keep :instrument-id instruments)))

(defn- incomplete-history?
  [requested-universe request]
  (not= (instrument-ids requested-universe)
        (instrument-ids (:universe request))))

(defn build-readiness
  [state]
  (let [draft (get-in state [:portfolio :optimizer :draft])
        requested-universe (vec (or (:universe draft) []))
        history-loading? (= :loading
                            (get-in state [:portfolio :optimizer :history-load-state :status]))]
    (if (empty? requested-universe)
      {:status :blocked
       :reason :missing-universe
       :runnable? false
       :request nil
       :warnings []}
      (let [request (with-cost-contexts state (build-request state draft))
            eligible? (boolean (seq (:universe request)))
            incomplete? (incomplete-history? requested-universe request)
            runnable? (and eligible?
                           (not incomplete?)
                           (not history-loading?))]
        {:status (if runnable? :ready :blocked)
         :reason (cond
                   history-loading? :history-loading
                   (not eligible?) :no-eligible-history
                   incomplete? :incomplete-history
                   :else nil)
         :runnable? (boolean runnable?)
         :request request
         :warnings (vec (:warnings request))}))))
