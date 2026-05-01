(ns hyperopen.views.portfolio.vm.benchmarks
  (:require [hyperopen.views.portfolio.vm.benchmarks.computation :as computation]
            [hyperopen.views.portfolio.vm.benchmarks.selector :as selector]))

(def market-type-token selector/market-type-token)
(def benchmark-open-interest selector/benchmark-open-interest)
(def benchmark-option-label selector/benchmark-option-label)
(def benchmark-option-rank selector/benchmark-option-rank)
(def vault-benchmark-value selector/vault-benchmark-value)
(def vault-benchmark-address selector/vault-benchmark-address)
(def benchmark-vault-tvl selector/benchmark-vault-tvl)
(def benchmark-vault-name selector/benchmark-vault-name)
(def benchmark-vault-option-rank selector/benchmark-vault-option-rank)
(def benchmark-vault-row-signature selector/benchmark-vault-row-signature)
(def benchmark-vault-rows-signature selector/benchmark-vault-rows-signature)
(def benchmark-vault-row? selector/benchmark-vault-row?)
(def eligible-vault-benchmark-rows selector/eligible-vault-benchmark-rows)
(def build-vault-benchmark-selector-options selector/build-vault-benchmark-selector-options)
(def mix-benchmark-markets-hash selector/mix-benchmark-markets-hash)
(def benchmark-market-signature selector/benchmark-market-signature)
(def benchmark-markets-signature selector/benchmark-markets-signature)
(def build-benchmark-selector-options selector/build-benchmark-selector-options)
(def normalize-benchmark-search-query selector/normalize-benchmark-search-query)
(def benchmark-option-matches-search? selector/benchmark-option-matches-search?)
(def selected-returns-benchmark-coins selector/selected-returns-benchmark-coins)
(def selected-benchmark-options selector/selected-benchmark-options)
(def sampled-series-source-version-counter computation/sampled-series-source-version-counter)
(def benchmark-source-version-by-coin computation/benchmark-source-version-by-coin)
(def benchmark-cumulative-return-rows-by-coin computation/benchmark-cumulative-return-rows-by-coin)
(def benchmark-computation-context computation/benchmark-computation-context)

(def ^:dynamic *build-benchmark-selector-options*
  selector/build-benchmark-selector-options)

(def ^:dynamic *build-vault-benchmark-selector-options*
  selector/build-vault-benchmark-selector-options)

(defn- with-selector-builders
  [f]
  (binding [selector/*build-benchmark-selector-options*
            *build-benchmark-selector-options*
            selector/*build-vault-benchmark-selector-options*
            *build-vault-benchmark-selector-options*
            selector/*benchmark-option-matches-search?*
            benchmark-option-matches-search?]
    (f)))

(defn memoized-eligible-vault-benchmark-rows
  [rows]
  (selector/memoized-eligible-vault-benchmark-rows rows))

(defn memoized-benchmark-selector-options
  [markets]
  (with-selector-builders
    #(selector/memoized-benchmark-selector-options markets)))

(defn memoized-vault-benchmark-selector-options
  [rows]
  (with-selector-builders
    #(selector/memoized-vault-benchmark-selector-options rows)))

(defn reset-portfolio-vm-cache!
  []
  (selector/reset-portfolio-vm-cache!))

(defn benchmark-selector-options
  [state]
  (with-selector-builders
    #(selector/benchmark-selector-options state)))

(defn returns-benchmark-selector-model
  [state]
  (with-selector-builders
    #(selector/returns-benchmark-selector-model state)))
