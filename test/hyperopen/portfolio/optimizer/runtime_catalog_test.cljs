(ns hyperopen.portfolio.optimizer.runtime-catalog-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.set :as set]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.runtime-catalog :as optimizer-runtime-catalog]
            [hyperopen.schema.runtime-registration.portfolio :as portfolio-registration]))

(def ^:private fs (js/require "fs"))

(defn- source
  [path]
  (.readFileSync fs path "utf8"))

(deftest app-runtime-loads-optimizer-owned-catalog-through-portfolio-route-module-test
  (let [actions-source (source "src/hyperopen/app/actions.cljs")
        effects-source (source "src/hyperopen/app/effects.cljs")
        route-runtime-source (source "src/hyperopen/portfolio/route_runtime_module.cljs")
        shadow-source (source "shadow-cljs.edn")]
    (is (not (str/includes? actions-source
                            "hyperopen.portfolio.optimizer.runtime-catalog"))
        "app action deps should stop requiring the optimizer runtime catalog directly")
    (is (not (str/includes? effects-source
                            "hyperopen.portfolio.optimizer.runtime-catalog"))
        "app effect deps should stop requiring the optimizer runtime catalog directly")
    (is (str/includes? route-runtime-source
                       "hyperopen.portfolio.optimizer.runtime-catalog")
        "the portfolio route runtime module should own the optimizer runtime catalog import")
    (is (str/includes? shadow-source
                       "hyperopen.portfolio.route-runtime-module")
        "shadow-cljs should include the portfolio route runtime module in the portfolio route chunk")))

(defn- optimizer-handler-key?
  [handler-key]
  (str/includes? (name handler-key) "portfolio-optimizer"))

(deftest optimizer-catalog-exposes-switch-objective-and-run-test
  (is (contains? (set (keys (:portfolio-optimizer
                             (optimizer-runtime-catalog/action-deps))))
                 :switch-portfolio-optimizer-objective-and-run)))

(deftest optimizer-catalog-covers-registration-handler-keys-test
  (let [action-catalog-keys (set (keys (:portfolio-optimizer
                                        (optimizer-runtime-catalog/action-deps))))
        effect-catalog-keys (set (keys (:portfolio-optimizer
                                        (optimizer-runtime-catalog/effect-deps nil))))
        action-registration-keys (->> portfolio-registration/action-binding-rows
                                      (map second)
                                      (filter optimizer-handler-key?)
                                      set)
        effect-registration-keys (->> portfolio-registration/effect-binding-rows
                                      (map second)
                                      (filter optimizer-handler-key?)
                                      set)]
    (is (= action-registration-keys action-catalog-keys)
        (str "optimizer action catalog drifted from registration rows: missing="
             (pr-str (set/difference action-registration-keys action-catalog-keys))
             " extra="
             (pr-str (set/difference action-catalog-keys action-registration-keys))))
    (is (= effect-registration-keys effect-catalog-keys)
        (str "optimizer effect catalog drifted from registration rows: missing="
             (pr-str (set/difference effect-registration-keys effect-catalog-keys))
             " extra="
             (pr-str (set/difference effect-catalog-keys effect-registration-keys))))))
