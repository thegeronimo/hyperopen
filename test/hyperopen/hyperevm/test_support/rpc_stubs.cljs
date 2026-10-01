(ns hyperopen.hyperevm.test-support.rpc-stubs
  "Stubbed fetch functions and ABI answers for the HyperEVM RPC client tests."
  (:require [cljs.test :refer-macros [is]]))

(defn- word
  [hex]
  (str (apply str (repeat (- 64 (count hex)) "0")) hex))

(defn- uint-word
  [value]
  (word (.toString (js/BigInt value) 16)))

(defn- right-pad
  [hex]
  (let [remainder (mod (count hex) 64)]
    (if (zero? remainder)
      hex
      (str hex (apply str (repeat (- 64 remainder) "0"))))))

(defn encode-results
  "ABI-encode `(bool success, bytes returnData)[]`, the aggregate3 return type,
   for stubbed RPC answers. `rpc-test` pins it against the live probe
   response."
  [results]
  (let [tails (mapv (fn [[success? data]]
                      (let [body (subs data 2)]
                        (str (uint-word (if success? 1 0))
                             (uint-word 64)
                             (uint-word (quot (count body) 2))
                             (right-pad body))))
                    results)
        offsets (take (count tails)
                      (reductions (fn [offset tail] (+ offset (quot (count tail) 2)))
                                  (* 32 (count tails))
                                  tails))]
    (apply str "0x" (uint-word 32) (uint-word (count tails))
           (concat (map uint-word offsets) tails))))

(defn amount-data
  "`balanceOf` / `getEthBalance` return data for `units`."
  [units]
  (str "0x" (uint-word units)))

(defn quantity
  "JSON-RPC hex quantity for `units`, as `eth_getBalance` answers."
  [units]
  (str "0x" (.toString (js/BigInt units) 16)))

(defn json-response
  [status payload]
  #js {:ok (<= 200 status 299)
       :status status
       :json (fn [] (js/Promise.resolve (clj->js payload)))})

(defn request-body
  [init]
  (js->clj (js/JSON.parse (.-body init)) :keywordize-keys true))

(defn batch-fetch
  "fetch-fn answering every JSON-RPC batch entry with `(answer entry)`, and
   recording each request. An answer of `{:error {...}}` becomes that entry's
   JSON-RPC error."
  [calls answer]
  (fn [url init]
    (let [body (request-body init)]
      (swap! calls conj {:url url :init init :body body})
      (js/Promise.resolve
       (json-response 200 (mapv (fn [entry]
                                  (let [answer* (answer entry)]
                                    (if (and (map? answer*) (:error answer*))
                                      {:jsonrpc "2.0" :id (:id entry) :error (:error answer*)}
                                      {:jsonrpc "2.0" :id (:id entry) :result answer*})))
                                body))))))

(defn single-fetch
  "fetch-fn answering successive requests with successive thunks."
  [calls answers]
  (let [remaining (atom answers)]
    (fn [url init]
      (swap! calls conj {:url url :init init :body (request-body init)})
      (let [next-answer (first @remaining)]
        (swap! remaining rest)
        (next-answer)))))

(defn expect-rejection
  [done promise check]
  (-> promise
      (.then (fn [value]
               (is false (str "Expected a rejection, got " (pr-str value)))
               (done)))
      (.catch (fn [err]
                (check (ex-data err) err)
                (done)))))

(defn token-address
  "A distinct well-formed contract address for synthetic token `i`."
  [i]
  (str "0x" (apply str (repeat (- 40 (count (.toString (+ 4096 i) 16))) "0"))
       (.toString (+ 4096 i) 16)))
