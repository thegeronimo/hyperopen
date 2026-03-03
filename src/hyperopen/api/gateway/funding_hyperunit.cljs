(ns hyperopen.api.gateway.funding-hyperunit
  (:require [clojure.string :as str]
            [hyperopen.api.endpoints.funding-hyperunit :as funding-hyperunit-endpoints]))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- resolve-base-url
  [deps opts]
  (or (:hyperunit-base-url opts)
      (:base-url opts)
      (:hyperunit-base-url deps)
      funding-hyperunit-endpoints/default-mainnet-base-url))

(defn- resolve-base-urls
  [deps opts]
  (let [resolved (keep non-blank-text
                       (concat [(resolve-base-url deps opts)]
                               (:base-urls opts)
                               [(:hyperunit-base-url deps)
                                funding-hyperunit-endpoints/default-mainnet-base-url]))]
    (vec (distinct resolved))))

(defn- request-opts
  [opts]
  (dissoc (or opts {})
          :hyperunit-base-url
          :base-url
          :base-urls))

(defn- with-base-url-fallbacks!
  [base-urls request-fn]
  (let [candidates (vec (distinct (keep non-blank-text base-urls)))]
    (letfn [(attempt! [remaining last-error]
              (if-let [candidate (first remaining)]
                (let [result (try
                               (request-fn candidate)
                               (catch :default err
                                 (js/Promise.reject err)))]
                  (if (fn? (some-> result .-then))
                    (-> result
                        (.catch (fn [err]
                                  (attempt! (rest remaining)
                                            (or err last-error)))))
                    result))
                (js/Promise.reject
                 (or last-error
                     (js/Error. "HyperUnit request failed.")))))]
      (attempt! candidates nil))))

(defn request-hyperunit-generate-address!
  [deps opts]
  (let [fetch-fn (or (:fetch-fn deps) js/fetch)
        request-opts* (request-opts opts)
        base-urls (resolve-base-urls deps opts)]
    (with-base-url-fallbacks!
     base-urls
     (fn [candidate-base-url]
       (funding-hyperunit-endpoints/request-generate-address!
        fetch-fn
        candidate-base-url
        request-opts*)))))

(defn request-hyperunit-operations!
  [deps opts]
  (let [fetch-fn (or (:fetch-fn deps) js/fetch)
        request-opts* (request-opts opts)
        base-urls (resolve-base-urls deps opts)]
    (with-base-url-fallbacks!
     base-urls
     (fn [candidate-base-url]
       (funding-hyperunit-endpoints/request-operations!
        fetch-fn
        candidate-base-url
        request-opts*)))))

(defn request-hyperunit-estimate-fees!
  [deps opts]
  (let [fetch-fn (or (:fetch-fn deps) js/fetch)
        request-opts* (request-opts opts)
        base-urls (resolve-base-urls deps opts)]
    (with-base-url-fallbacks!
     base-urls
     (fn [candidate-base-url]
       (funding-hyperunit-endpoints/request-estimate-fees!
        fetch-fn
        candidate-base-url
        request-opts*)))))

(defn request-hyperunit-withdrawal-queue!
  [deps opts]
  (let [fetch-fn (or (:fetch-fn deps) js/fetch)
        request-opts* (request-opts opts)
        base-urls (resolve-base-urls deps opts)]
    (with-base-url-fallbacks!
     base-urls
     (fn [candidate-base-url]
       (funding-hyperunit-endpoints/request-withdrawal-queue!
        fetch-fn
        candidate-base-url
        request-opts*)))))
