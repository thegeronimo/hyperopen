(ns hyperopen.route-query-state
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.query-state :as portfolio-optimizer-query-state]
            [hyperopen.portfolio.query-state :as portfolio-query-state]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.router :as router]
            [hyperopen.vaults.application.query-state :as vault-query-state]
            [hyperopen.vaults.infrastructure.routes :as vault-routes]))

(defn- normalize-search
  [search]
  (let [search* (some-> search str str/trim)]
    (if-not (seq search*)
      ""
      (let [without-fragment (or (first (str/split search* #"#" 2))
                                 "")
            query-index (.indexOf without-fragment "?")
            query-text (if (>= query-index 0)
                         (subs without-fragment query-index)
                         without-fragment)]
        (if (str/starts-with? query-text "?")
          query-text
          (str "?" query-text))))))

(defn- search-query-pairs
  [search]
  (let [params (js/URLSearchParams. (normalize-search search))
        pairs (atom [])]
    (.forEach params (fn [value key]
                       (swap! pairs conj [(str key) (str value)])))
    @pairs))

(defn- query-string
  [pairs]
  (let [params (js/URLSearchParams.)]
    (doseq [[key value] pairs]
      (.append params key (str (or value ""))))
    (.toString params)))

(defn- route-surface
  [path]
  (let [path* (router/normalize-path path)
        portfolio-kind (:kind (portfolio-routes/parse-portfolio-route path*))
        vault-kind (:kind (vault-routes/parse-vault-route path*))]
    (cond
      (contains? #{:optimize-index :optimize-new :optimize-scenario} portfolio-kind)
      :portfolio-optimizer

      (contains? #{:page :trader} portfolio-kind)
      :portfolio

      (= :list vault-kind)
      :vault-list

      (= :detail vault-kind)
      :vault-detail

      :else
      nil)))

(def ^:private shareable-route-query-keys
  (into #{}
        (concat portfolio-query-state/owned-query-keys
                portfolio-optimizer-query-state/owned-query-keys
                vault-query-state/list-owned-query-keys
                vault-query-state/detail-owned-query-keys)))

(defn- surface-query-params
  [surface state]
  (case surface
    :portfolio (portfolio-query-state/portfolio-query-params state)
    :portfolio-optimizer (portfolio-optimizer-query-state/optimizer-query-params state)
    :vault-list (vault-query-state/vault-list-query-params state)
    :vault-detail (vault-query-state/vault-detail-query-params state)
    []))

(defn shareable-route-browser-path
  [state pathname search]
  (let [path (router/normalize-path pathname)
        surface (route-surface path)]
    (when surface
      (let [preserved-pairs (remove (fn [[key _value]]
                                      (contains? shareable-route-query-keys key))
                                    (search-query-pairs search))
            route-pairs (surface-query-params surface state)
            query (query-string (concat preserved-pairs route-pairs))]
        (if (seq query)
          (str path "?" query)
          path)))))

(defn apply-route-query-state
  [state pathname search]
  (let [path (router/normalize-path pathname)
        surface (route-surface path)
        portfolio-route (portfolio-routes/parse-portfolio-route path)]
    (case surface
      :portfolio
      (portfolio-query-state/apply-portfolio-query-state
       state
       (portfolio-query-state/parse-portfolio-query search))

      :portfolio-optimizer
      (let [optimizer-query-state
            (portfolio-optimizer-query-state/parse-optimizer-query search)]
        (portfolio-optimizer-query-state/apply-optimizer-query-state
         state
         (cond-> optimizer-query-state
           (and (= :optimize-scenario (:kind portfolio-route))
                (not (contains? optimizer-query-state :results-tab)))
           (assoc :results-tab :recommendation))))

      :vault-list
      (vault-query-state/apply-vault-query-state
       state
       (vault-query-state/parse-vault-list-query search))

      :vault-detail
      (vault-query-state/apply-vault-query-state
       state
       (vault-query-state/parse-vault-detail-query search))

      state)))

(defn restore-route-query-state!
  [store pathname search]
  (swap! store apply-route-query-state pathname search))

(defn restore-current-route-query-state!
  [store]
  (let [location (some-> js/globalThis .-location)
        path (router/current-path)
        search (some-> location .-search)]
    (restore-route-query-state! store path search)))
