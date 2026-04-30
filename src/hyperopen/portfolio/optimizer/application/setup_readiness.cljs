(ns hyperopen.portfolio.optimizer.application.setup-readiness
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.orderbook-loader :as orderbook-loader]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]))

(def ^:private vault-instrument-prefix
  "vault:")

(def ^:private history-blocking-warning-codes
  #{:missing-history-coin
    :missing-candle-history
    :insufficient-candle-history
    :missing-vault-address
    :missing-vault-history
    :insufficient-vault-history
    :insufficient-common-history})

(defn- current-as-of-ms
  [state]
  (or (get-in state [:portfolio :optimizer :runtime :as-of-ms])
      (.now js/Date)))

(defn- positive-number?
  [value]
  (and (number? value)
       (pos? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- vault-ref?
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (boolean
     (and (seq text)
          (str/starts-with? text vault-instrument-prefix)))))

(defn- with-manual-capital
  [snapshot draft]
  (let [manual-capital (get-in draft [:execution-assumptions :manual-capital-usdc])]
    (if (positive-number? manual-capital)
      (-> snapshot
          (assoc :capital-ready? true)
          (assoc-in [:capital :nav-usdc] manual-capital)
          (assoc-in [:capital :source] :manual)
          (assoc-in [:capital :manual-capital-usdc] manual-capital)
          (update :warnings
                  #(conj (vec %)
                         {:code :manual-capital-base
                          :message "Manual capital base is being used for preview sizing."})))
      snapshot)))

(defn- build-request
  [state draft]
  (request-builder/build-engine-request
   {:draft draft
    :current-portfolio (with-manual-capital
                         (current-portfolio/current-portfolio-snapshot state)
                         draft)
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

(defn- requested-instrument-by-id
  [request]
  (into {}
        (map (fn [instrument]
               [(:instrument-id instrument) instrument]))
        (:requested-universe request)))

(defn- warning-instrument
  [request warning]
  (get (requested-instrument-by-id request) (:instrument-id warning)))

(defn- warning-asset-label
  [request warning]
  (let [instrument (warning-instrument request warning)
        coin (non-blank-text (:coin instrument))]
    (or (non-blank-text (:name instrument))
        (non-blank-text (:symbol instrument))
        (when-not (vault-ref? coin) coin)
        (non-blank-text (:vault-address instrument))
        (non-blank-text (:vault-address warning))
        (non-blank-text (:instrument-id warning))
        "Selected asset")))

(defn- observation-count-message
  [warning noun]
  (if (and (some? (:observations warning))
           (some? (:required warning)))
    (str "only " (:observations warning) " usable " noun
         " observations; " (:required warning) " required.")
    (str "not enough usable " noun " observations.")))

(defn warning-display-message
  [request warning]
  (let [label (warning-asset-label request warning)]
    (case (:code warning)
      :missing-history-coin
      (str label ": missing coin metadata needed to fetch history.")

      :missing-candle-history
      (str label ": no candle history returned"
           (if-let [coin (non-blank-text (:coin warning))]
             (str " for " coin ".")
             "."))

      :insufficient-candle-history
      (str label ": " (observation-count-message warning "candle"))

      :missing-vault-address
      (str label ": missing vault address needed to fetch vault details.")

      :missing-vault-history
      (str label ": vault details returned no usable return history.")

      :insufficient-vault-history
      (str label ": " (observation-count-message warning "vault return"))

      :insufficient-common-history
      (observation-count-message warning "shared return")

      (or (some-> (:code warning) name)
          "Optimizer warning."))))

(defn- with-warning-messages
  [request warnings]
  (mapv (fn [warning]
          (assoc warning :message (warning-display-message request warning)))
        warnings))

(defn- blocking-history-warnings
  [requested-universe request]
  (let [missing-ids (set/difference (instrument-ids requested-universe)
                                    (instrument-ids (:universe request)))]
    (->> (:warnings request)
         (filter (fn [warning]
                   (or (contains? missing-ids (:instrument-id warning))
                       (contains? history-blocking-warning-codes
                                  (:code warning)))))
         (with-warning-messages request))))

(defn readiness-error-message
  [readiness]
  (let [details (seq (distinct (keep :message (:blocking-warnings readiness))))
        with-details (fn [fallback prefix]
                       (if details
                         (str prefix ": " (str/join " " details))
                         fallback))]
    (case (:reason readiness)
      :missing-universe "Select a universe before running."
      :history-loading "Optimizer history is already loading."
      :no-eligible-history
      (with-details "No eligible history was available for this universe."
                    "No eligible history was available")
      :incomplete-history
      (with-details "History is incomplete for this universe."
                    "History is incomplete")
      "Optimizer inputs are not ready to run.")))

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
                           (not history-loading?))
            blocking-warnings (if (or (not eligible?) incomplete?)
                                (blocking-history-warnings requested-universe
                                                          request)
                                [])]
        {:status (if runnable? :ready :blocked)
         :reason (cond
                   history-loading? :history-loading
                   (not eligible?) :no-eligible-history
                   incomplete? :incomplete-history
                   :else nil)
         :runnable? (boolean runnable?)
         :request request
         :blocking-warnings blocking-warnings
         :warnings (vec (:warnings request))}))))
