(ns hyperopen.hyperevm.infrastructure.rpc
  "Page-side JSON-RPC client for HyperEVM reads.

   It talks to the public RPC directly rather than through the wallet, so it
   works without a connected wallet, in spectate mode, and whatever chain the
   wallet is on. The public endpoint's limits, verified live on 2026-09-30:

   - CORS answers `Access-Control-Allow-Origin: *`, so browser reads work.
     The release CSP must list the origin in `connect-src`
     (`tools/release-assets/security_headers.mjs`).
   - JSON-RPC batches are capped at 20 entries (error -32010).
   - 100 requests per minute per IP, shared by every tab on that IP.
   - Only the latest block is served.

   Balances therefore come from ONE JSON-RPC batch instead of one request per
   token: `eth_getBalance` for native HYPE, `eth_gasPrice`, and Multicall3
   `aggregate3` eth_calls carrying every token's `balanceOf`.

   Every result leaves this namespace as a decimal string, never a BigInt.
   Failures reject with `ex-info` whose data carries `:kind` (`:timeout`,
   `:network`, `:http`, `:rate-limited`, `:rpc`, `:invalid-response`,
   `:invalid-request`, `:reverted`, `:receipt-timeout`), `:code` (the
   JSON-RPC error code, when the RPC sent one) and `:http-status`."
  (:require [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.units :as units]
            [hyperopen.platform :as platform]))

(def default-timeout-ms 10000)

(def max-batch-size
  "The public RPC rejects JSON-RPC batches above 20 entries."
  20)

(def max-tokens-per-aggregate
  "Tokens per `aggregate3` call. One call covering native HYPE and all 172
   linked mainnet tokens took 0.65 s live (38.8 KB calldata); chunking keeps a
   single call bounded as the token list grows."
  150)

(def default-receipt-poll-ms 1000)

(def default-receipt-timeout-ms
  "Addresses that opted into big blocks only land a transaction about once a
   minute, so the receipt wait allows three big blocks."
  180000)

(defn- rpc-error
  [message data]
  (ex-info message (merge {:code nil :http-status nil} data)))

(defn- rpc-error?
  [err]
  (some? (:kind (ex-data err))))

(defn- method-label
  [method]
  (if (sequential? method)
    (apply str (interpose ", " (distinct method)))
    (str method)))

(def rate-limited-code
  "The public RPC rate-limits with HTTP 200 and this JSON-RPC error code,
   sent as ONE error object even in reply to a batch (seen live 2026-09-30)."
  -32005)

(defn- rpc-entry-error
  [method error]
  (if (= rate-limited-code (:code error))
    (rpc-error "HyperEVM RPC rate limit reached."
               {:kind :rate-limited :code rate-limited-code :method method})
    (rpc-error (str "HyperEVM RPC " method " failed"
                    (when-let [message (:message error)]
                      (str ": " message))
                    ".")
               {:kind :rpc
                :code (:code error)
                :method method})))

(defn- invalid-response
  [method]
  (rpc-error (str "HyperEVM RPC returned an invalid response for "
                  (method-label method)
                  ".")
             {:kind :invalid-response
              :method method}))

(defn- rpc-url
  [deps]
  (or (:rpc-url deps)
      (:rpc-url (:chain deps))
      (:rpc-url chain/mainnet)))

(defn- make-abort-controller
  [deps]
  (if-let [make! (:make-abort-controller deps)]
    (make!)
    (when (exists? js/AbortController)
      (js/AbortController.))))

(defn- with-timeout!
  "Race `run!` against the request deadline. `run!` receives an AbortSignal
   (or nil) so a timed-out fetch is actually cancelled, not just abandoned."
  [deps run!]
  (let [timeout-ms (or (:timeout-ms deps) default-timeout-ms)
        set-timeout-fn (or (:set-timeout-fn deps) platform/set-timeout!)
        clear-timeout-fn (or (:clear-timeout-fn deps) platform/clear-timeout!)
        controller (make-abort-controller deps)
        timer (atom nil)]
    (-> (js/Promise.race
         #js [(run! (some-> controller .-signal))
              (js/Promise.
               (fn [_resolve reject]
                 (reset! timer
                         (set-timeout-fn
                          (fn []
                            (some-> controller .abort)
                            (reject (rpc-error
                                     (str "HyperEVM RPC request timed out after "
                                          timeout-ms
                                          "ms.")
                                     {:kind :timeout})))
                          timeout-ms))))])
        (.finally (fn []
                    (when-let [id @timer]
                      (clear-timeout-fn id)))))))

(defn- response-json!
  "Parsed JSON body of `response`, or nil when it has none."
  [response]
  (if (and response (fn? (.-json response)))
    (-> (.json response)
        (.catch (fn [_] nil)))
    (js/Promise.resolve nil)))

(defn- http-error!
  [response method]
  (let [status (.-status response)]
    (-> (response-json! response)
        (.then (fn [payload]
                 (let [error-code (some-> payload
                                          (js->clj :keywordize-keys true)
                                          (as-> body (when (map? body) body))
                                          :error
                                          :code)]
                   (js/Promise.reject
                    (rpc-error (if (= 429 status)
                                 "HyperEVM RPC rate limit reached."
                                 (str "HyperEVM RPC request failed with HTTP " status "."))
                               {:kind (if (= 429 status) :rate-limited :http)
                                :code error-code
                                :http-status status
                                :method method}))))))))

(defn- post-json!
  "POST `body` as JSON-RPC and resolve with the keywordized response payload."
  [deps body method]
  (let [fetch-fn (or (:fetch-fn deps) js/fetch)]
    (with-timeout!
      deps
      (fn [signal]
        (let [init #js {:method "POST"
                        :headers #js {"content-type" "application/json"}
                        :body (js/JSON.stringify (clj->js body))}]
          (when signal
            (aset init "signal" signal))
          (-> (js/Promise.resolve nil)
              (.then (fn [_] (fetch-fn (rpc-url deps) init)))
              (.then (fn [response]
                       (cond
                         (nil? response)
                         (js/Promise.reject (invalid-response method))

                         (.-ok response)
                         (-> (.json response)
                             (.then (fn [payload]
                                      (js->clj payload :keywordize-keys true)))
                             (.catch (fn [_]
                                       (js/Promise.reject (invalid-response method)))))

                         :else
                         (http-error! response method))))
              (.catch (fn [err]
                        (js/Promise.reject
                         (if (rpc-error? err)
                           err
                           (rpc-error "HyperEVM RPC request failed."
                                      {:kind :network
                                       :method method
                                       :cause err})))))))))))

(defn rpc-request!
  "Send one JSON-RPC request and resolve with its `result` (keywordized)."
  [deps method params]
  (-> (post-json! deps
                  {:jsonrpc "2.0" :id 1 :method method :params (vec params)}
                  method)
      (.then (fn [payload]
               (cond
                 (not (map? payload)) (js/Promise.reject (invalid-response method))
                 (:error payload) (js/Promise.reject (rpc-entry-error method (:error payload)))
                 (contains? payload :result) (:result payload)
                 :else (js/Promise.reject (invalid-response method)))))))

(defn- entry-outcome
  "`{:result r}` for a batch response entry that succeeded, otherwise
   `{:error ex-info}`."
  [method entry]
  (cond
    (not (map? entry)) {:error (invalid-response method)}
    (:error entry) {:error (rpc-entry-error method (:error entry))}
    (contains? entry :result) {:result (:result entry)}
    :else {:error (invalid-response method)}))

(defn- batch-outcomes!
  "Send `requests` as one JSON-RPC batch and resolve with one `entry-outcome`
   per request, in request order (responses are matched by id, since a server
   may reorder them). Rejects only when the batch as a whole failed; a failed
   entry is left to the caller."
  [deps requests]
  (let [requests* (vec requests)
        methods (mapv first requests*)]
    (cond
      (empty? requests*)
      (js/Promise.resolve [])

      (> (count requests*) max-batch-size)
      (js/Promise.reject
       (rpc-error (str "HyperEVM RPC batches are limited to " max-batch-size " requests.")
                  {:kind :invalid-request :method methods}))

      :else
      (-> (post-json! deps
                      (vec (map-indexed (fn [i [method params]]
                                          {:jsonrpc "2.0"
                                           :id (inc i)
                                           :method method
                                           :params (vec params)})
                                        requests*))
                      methods)
          (.then
           (fn [payload]
             (cond
               (and (map? payload) (:error payload))
               (js/Promise.reject (rpc-entry-error (first methods) (:error payload)))

               (not (sequential? payload))
               (js/Promise.reject (invalid-response (first methods)))

               :else
               (let [by-id (into {} (map (juxt :id identity)) payload)]
                 (vec (map-indexed (fn [i method]
                                     (entry-outcome method (get by-id (inc i))))
                                   methods))))))))))

(defn rpc-batch!
  "Send `requests`, a sequence of `[method params]`, as one JSON-RPC batch of
   at most 20 entries. Resolves with the results in request order (responses
   are matched by id, since a server may reorder them) and rejects on the
   first entry that failed."
  [deps requests]
  (-> (batch-outcomes! deps requests)
      (.then (fn [outcomes]
               (if-let [error (some :error outcomes)]
                 (js/Promise.reject error)
                 (mapv :result outcomes))))))

(defn- quantity-text!
  "Decimal string for a hex quantity result, or a rejection."
  [method value]
  (if-let [quantity (abi/parse-quantity value)]
    (units/units-text quantity)
    (js/Promise.reject (invalid-response method))))

(defn gas-price!
  "`eth_gasPrice` (the next small block's base fee) as a decimal wei string."
  [deps]
  (-> (rpc-request! deps "eth_gasPrice" [])
      (.then #(quantity-text! "eth_gasPrice" %))))

(defn estimate-gas!
  "`eth_estimateGas` for `tx` (a map of JSON-RPC tx fields) as a decimal
   string."
  [deps tx]
  (-> (rpc-request! deps "eth_estimateGas" [tx])
      (.then #(quantity-text! "eth_estimateGas" %))))

(defn eth-call!
  "`eth_call` of `{:to :data}` at the latest block; resolves with the hex
   return data."
  [deps call]
  (rpc-request! deps "eth_call" [call "latest"]))

(defn get-transaction-receipt!
  "The keywordized receipt for `tx-hash`, or nil while it is pending."
  [deps tx-hash]
  (rpc-request! deps "eth_getTransactionReceipt" [tx-hash]))

(defn wait-for-receipt!
  "Poll for `tx-hash`'s receipt until it lands. Resolves with the receipt when
   its status is 0x1, rejects `:reverted` otherwise, and rejects
   `:receipt-timeout` after `:timeout-ms`.

   `deps` configure each poll request exactly as for `rpc-request!`: their
   `:timeout-ms` and `:set-timeout-fn` bound ONE request. `opts` configure the
   wait as a whole:

   - `:poll-ms` between polls (default 1000);
   - `:timeout-ms` for the whole wait (default 180000);
   - `:schedule-poll-fn`, `(fn [f ms])`, schedules the next poll (default
     `platform/set-timeout!`). It is deliberately not `:set-timeout-fn`, so a
     fake poll scheduler can never also fire every request's deadline;
   - `:now-ms-fn` (default `platform/now-ms`).

   A nil option falls back to its default, exactly like an absent one.

   A failed poll (rate limit, network) is retried until the deadline rather
   than failing the wait, because the transaction may well have succeeded and
   a false failure invites the user to send it twice. While the RPC rate
   limits, the gap doubles (2, 4, then 8 s at most) within the same deadline."
  [deps tx-hash opts]
  (let [poll-ms (or (:poll-ms opts) default-receipt-poll-ms)
        timeout-ms (or (:timeout-ms opts) default-receipt-timeout-ms)
        schedule-poll-fn (or (:schedule-poll-fn opts) platform/set-timeout!)
        now-ms-fn (or (:now-ms-fn opts) platform/now-ms)
        started-at-ms (now-ms-fn)
        strikes (atom 0)]
    (js/Promise.
     (fn [resolve reject]
       (letfn [(retry-or-timeout [last-error]
                 (swap! strikes #(if (= :rate-limited (:kind (ex-data last-error))) (inc %) 0))
                 (if (>= (- (now-ms-fn) started-at-ms) timeout-ms)
                   (reject (rpc-error "Timed out waiting for the HyperEVM transaction receipt."
                                      {:kind :receipt-timeout
                                       :tx-hash tx-hash
                                       :last-error last-error}))
                   (schedule-poll-fn poll (min (* 8 poll-ms) (* poll-ms (js/Math.pow 2 @strikes))))))
               (poll []
                 (-> (get-transaction-receipt! deps tx-hash)
                     (.then (fn [receipt]
                              (cond
                                (not (map? receipt))
                                (retry-or-timeout nil)

                                (= "0x1" (:status receipt))
                                (resolve receipt)

                                :else
                                (reject (rpc-error "The HyperEVM transaction reverted."
                                                   {:kind :reverted
                                                    :tx-hash tx-hash
                                                    :receipt receipt})))))
                     (.catch retry-or-timeout)))]
         (poll))))))

;; --- balances ----------------------------------------------------------------
;;
;; Native HYPE and the gas price come from the node itself (`eth_getBalance`,
;; `eth_gasPrice`). Only token balances go through Multicall3, because each of
;; those `balanceOf` calls runs a contract that some HIP-1 deployer controls.
;; `allowFailure` survives a revert, but a `balanceOf` that burns all its gas
;; or returns a huge payload fails the whole aggregate3 call. Such a failure
;; costs only that chunk's token balances, never native HYPE or the gas price,
;; which gas gating depends on.

(defn- balance-calls
  "aggregate3 `balanceOf(owner)` calls, one per token."
  [owner tokens]
  (mapv (fn [token]
          {:target (:erc20-address token)
           :allow-failure? true
           :call-data (abi/encode-balance-of owner)})
        tokens))

(defn- run-batches!
  "`batch-outcomes!` over `requests` split into batches of at most 20."
  [deps requests]
  (-> (js/Promise.all
       (into-array (map #(batch-outcomes! deps %)
                        (partition-all max-batch-size requests))))
      (.then (fn [outcomes]
               (into [] cat outcomes)))))

(defn- quantity-outcome
  "`outcome` with its hex-quantity result as a decimal string."
  [method {:keys [result error] :as outcome}]
  (if error
    outcome
    (if-let [quantity (abi/parse-quantity result)]
      {:result (units/units-text quantity)}
      {:error (invalid-response method)})))

(defn- chunk-balances
  "`{:token-units {index units} :failed [index …]}` for one aggregate3 chunk,
   omitting zero balances. A token whose own call failed or returned no word
   is `:failed` (unknown, never zero). When the aggregate3 call itself
   failed, or its results do not line up one-to-one with `tokens`, the chunk
   is `{:unread [index …]}` instead: a short or long result list must never
   shift one token's balance onto another."
  [tokens {:keys [result error]}]
  (let [decoded (when-not error
                  (abi/decode-aggregate3 result))]
    (if (and (vector? decoded)
             (= (count tokens) (count decoded)))
      (reduce (fn [acc [{:keys [index]} {:keys [success? return-data]}]]
                (let [amount (when success? (abi/decode-uint256 return-data))]
                  (cond
                    (nil? amount) (update acc :failed conj index)
                    (> amount units/zero) (assoc-in acc [:token-units index] (units/units-text amount))
                    :else acc)))
              {:token-units {} :failed []}
              (map vector tokens decoded))
      {:unread (mapv :index tokens)})))

(defn- collect-balances
  [chunks [native-outcome gas-price-outcome & chunk-outcomes]]
  (let [native (quantity-outcome "eth_getBalance" native-outcome)
        gas-price (quantity-outcome "eth_gasPrice" gas-price-outcome)]
    (if-let [error (or (:error native) (:error gas-price))]
      (js/Promise.reject error)
      (let [parts (map chunk-balances chunks chunk-outcomes)
            unread (into [] (mapcat :unread) parts)
            failed (into [] (mapcat :failed) parts)]
        (cond-> {:native-wei (:result native)
                 :token-units (into {} (map :token-units) parts)
                 :gas-price-wei (:result gas-price)}
          (seq unread) (assoc :unread-token-indexes unread)
          (seq failed) (assoc :failed-token-indexes failed))))))

(defn- extra-results
  "Outcomes of the caller's extra aggregate3 calls, keyed by each call's
   `:key`: `{:extra-results {key {:success? :return-data}} :extra-unread
   [key …]}`. A chunk whose aggregate3 call failed or came back malformed
   lands in `:extra-unread`, never as a failed call."
  [chunks outcomes]
  (reduce (fn [acc [calls {:keys [result error]}]]
            (let [decoded (when-not error (abi/decode-aggregate3 result))]
              (if (and (vector? decoded) (= (count calls) (count decoded)))
                (update acc :extra-results into (map vector (map :key calls) decoded))
                (update acc :extra-unread into (map :key calls)))))
          {:extra-results {} :extra-unread []}
          (map vector chunks outcomes)))

(defn read-balances!
  "Read `owner`'s HyperEVM balances and the current gas price in one JSON-RPC
   batch: `eth_getBalance` (native HYPE), `eth_gasPrice`, and one Multicall3
   `aggregate3` eth_call per 150 tokens.

   `tokens` are linked-token maps (`hyperopen.hyperevm.domain.tokens`); each
   one with an `:erc20-address` gets a `balanceOf` call with allowFailure.

   Resolves with decimal strings,

       {:native-wei \"…\" :token-units {index \"base units\"} :gas-price-wei \"…\"}

   - `:native-wei` is always present, \"0\" included, so an empty wallet is
     never mistaken for an unread one.
   - `:token-units` omits zero balances.
   - `:failed-token-indexes` and `:unread-token-indexes`, each present only
     when non-empty, list the tokens whose own `balanceOf` failed (JOFF
     reverts) and those whose whole aggregate3 call failed or came back
     malformed. Both are unknown, never zero.

   `:extra-calls`, optional, are more `{:key :target :call-data}` reads to
   ride the same batch (the bridge-health reads, for example), chunked 150
   per aggregate3 with allowFailure. When given, the result also carries
   `:extra-results` and `:extra-unread` (see `extra-results`).

   Rejects when native HYPE or the gas price cannot be read, or when the
   request as a whole fails (HTTP, rate limit, network, timeout)."
  [deps {:keys [owner tokens chain extra-calls]}]
  (let [chain* (or chain (:chain deps) chain/mainnet)
        deps* (assoc deps :chain chain*)
        owner* (abi/normalize-address owner)
        multicall-address (:multicall3-address chain*)
        erc20-tokens (filterv #(abi/normalize-address (:erc20-address %)) tokens)
        chunks (mapv vec (partition-all max-tokens-per-aggregate erc20-tokens))
        call-data (mapv #(abi/encode-aggregate3 (balance-calls owner* %)) chunks)
        extra-chunks (mapv vec (partition-all max-tokens-per-aggregate extra-calls))
        extra-data (mapv (fn [calls]
                           (abi/encode-aggregate3 (mapv #(assoc % :allow-failure? true) calls)))
                         extra-chunks)
        eth-call (fn [data] ["eth_call" [{:to multicall-address :data data} "latest"]])]
    (if (or (nil? owner*)
            (not-every? some? (concat call-data extra-data)))
      (js/Promise.reject
       (rpc-error "Cannot read HyperEVM balances for an invalid address."
                  {:kind :invalid-request :method "eth_getBalance"}))
      (-> (run-batches! deps*
                        (-> [["eth_getBalance" [owner* "latest"]]
                             ["eth_gasPrice" []]]
                            (into (map eth-call) call-data)
                            (into (map eth-call) extra-data)))
          (.then (fn [outcomes]
                   (let [base-count (+ 2 (count chunks))
                         balances (collect-balances chunks (subvec outcomes 0 base-count))]
                     (if (and (map? balances) (seq extra-chunks))
                       (merge balances
                              (extra-results extra-chunks (subvec outcomes base-count)))
                       balances))))))))
