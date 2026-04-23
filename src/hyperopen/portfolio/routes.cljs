(ns hyperopen.portfolio.routes
  (:require [clojure.string :as str]
            [hyperopen.router :as router]))

(def canonical-route
  "/portfolio")

(def ^:private trader-route-prefix
  "/portfolio/trader/")

(def ^:private optimize-route-prefix
  "/portfolio/optimize")

(def ^:private optimize-scenario-route-prefix
  "/portfolio/optimize/")

(defn- portfolio-prefix-match?
  [path]
  (or (= path canonical-route)
      (str/starts-with? path (str canonical-route "/"))))

(defn- normalize-address
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (seq text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- optimize-scenario-id
  [path]
  (when (str/starts-with? path optimize-scenario-route-prefix)
    (let [scenario-id (subs path (count optimize-scenario-route-prefix))]
      (when-not (or (= "new" scenario-id)
                    (str/includes? scenario-id "/"))
        (non-blank-text scenario-id)))))

(defn parse-portfolio-route
  [path]
  (let [path* (router/normalize-path path)
        suffix (when (str/starts-with? path* trader-route-prefix)
                 (subs path* (count trader-route-prefix)))
        trader-address (normalize-address suffix)
        scenario-id (optimize-scenario-id path*)]
    (cond
      (= path* canonical-route)
      {:kind :page
       :path path*}

      (= path* optimize-route-prefix)
      {:kind :optimize-index
       :path path*}

      (= path* (str optimize-route-prefix "/new"))
      {:kind :optimize-new
       :path path*}

      scenario-id
      {:kind :optimize-scenario
       :path path*
       :scenario-id scenario-id}

      trader-address
      {:kind :trader
       :path path*
       :address trader-address}

      (portfolio-prefix-match? path*)
      {:kind :other
       :path path*}

      :else
      {:kind :non-portfolio
       :path path*})))

(defn portfolio-route?
  [path]
  (contains? #{:page :trader :other :optimize-index :optimize-new :optimize-scenario}
             (:kind (parse-portfolio-route path))))

(defn portfolio-optimize-route?
  [path]
  (contains? #{:optimize-index :optimize-new :optimize-scenario}
             (:kind (parse-portfolio-route path))))

(defn portfolio-optimize-scenario-id
  [path]
  (:scenario-id (parse-portfolio-route path)))

(defn portfolio-optimize-index-path
  []
  optimize-route-prefix)

(defn portfolio-optimize-new-path
  []
  (str optimize-route-prefix "/new"))

(defn portfolio-optimize-scenario-path
  [scenario-id]
  (when-let [scenario-id* (non-blank-text scenario-id)]
    (str optimize-scenario-route-prefix scenario-id*)))

(defn trader-portfolio-route?
  [path]
  (= :trader (:kind (parse-portfolio-route path))))

(defn trader-portfolio-address
  [path]
  (:address (parse-portfolio-route path)))

(defn trader-portfolio-path
  [address]
  (when-let [address* (normalize-address address)]
    (str trader-route-prefix address*)))
