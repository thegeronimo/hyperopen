(ns hyperopen.funding.infrastructure.route-clients
  (:require [clojure.string :as str]))

(defn- normalize-address
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (string? text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn- maybe-value-field
  [value]
  (let [text (some-> value str str/trim)]
    (when (and (seq text)
               (not= text "0x")
               (not= text "0x0")
               (not= text "0"))
      text)))

(defn- lifi-quote-url
  [{:keys [from-address amount-units to-token-address from-chain-id to-chain-id from-token-address integrator]}]
  (let [encode js/encodeURIComponent
        integrator* (or (some-> integrator str str/trim)
                        "hyperopen")]
    (str "https://li.quest/v1/quote?"
         "fromChain=" from-chain-id
         "&toChain=" to-chain-id
         "&fromToken=" (encode from-token-address)
         "&toToken=" (encode to-token-address)
         "&fromAddress=" (encode from-address)
         "&fromAmount=" (encode (.toString amount-units))
         "&slippage=0.005"
         "&integrator=" (encode integrator*))))

(defn fetch-lifi-quote!
  [request]
  (let [url (lifi-quote-url request)]
    (-> (js/fetch url #js {:method "GET"})
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.json resp)
                   (.then (.text resp)
                          (fn [text]
                            (throw (js/Error.
                                    (str "LiFi quote request failed ("
                                         (.-status resp)
                                         "): "
                                         (or text "Unknown response"))))))))
        (.then (fn [payload]
                 (js->clj payload :keywordize-keys true)))))))

(defn- across-approval-url
  [{:keys [base-url from-address amount-units input-token-address origin-chain-id output-token-address destination-chain-id]}]
  (let [encode js/encodeURIComponent]
    (str base-url
         "?tradeType=minOutput"
         "&amount=" (encode (.toString amount-units))
         "&inputToken=" (encode input-token-address)
         "&originChainId=" (encode origin-chain-id)
         "&outputToken=" (encode output-token-address)
         "&destinationChainId=" (encode destination-chain-id)
         "&depositor=" (encode from-address))))

(defn fetch-across-approval!
  [request]
  (let [url (across-approval-url request)]
    (-> (js/fetch url #js {:method "GET"})
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.json resp)
                   (.then (.text resp)
                          (fn [text]
                            (throw (js/Error.
                                    (str "Across approval request failed ("
                                         (.-status resp)
                                         "): "
                                         (or text "Unknown response"))))))))
        (.then (fn [payload]
                 (js->clj payload :keywordize-keys true)))))))

(defn- normalize-hex-data
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (seq text)
               (re-matches #"^0x[0-9a-f]+$" text))
      text)))

(defn- normalize-hex-quantity
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (cond
      (not (seq text))
      nil

      (or (= text "0")
          (= text "0x")
          (= text "0x0"))
      "0x0"

      (re-matches #"^0x[0-9a-f]+$" text)
      text

      (re-matches #"^\d+$" text)
      (str "0x" (.toString (js/BigInt text) 16))

      :else
      nil)))

(defn- parse-across-transaction
  [tx]
  (let [to (normalize-address (:to tx))
        data (normalize-hex-data (:data tx))
        value (normalize-hex-quantity (:value tx))]
    (when (and to data)
      (cond-> {:to to
               :data data}
        (and (seq value)
             (not= value "0x0")) (assoc :value value)))))

(defn across-approval->swap-config
  [approval]
  (let [swap-tx (parse-across-transaction (:swapTx approval))
        approval-txs (->> (or (:approvalTxns approval) [])
                          (keep parse-across-transaction)
                          vec)]
    (when swap-tx
      {:swap-tx swap-tx
       :approval-txs approval-txs})))

(defn- parse-positive-bigint
  [value]
  (let [text (some-> value str str/trim)]
    (when (and (seq text)
               (re-matches #"^\d+$" text))
      (let [parsed (js/BigInt text)]
        (when (> parsed (js/BigInt "0"))
          parsed)))))

(defn lifi-quote->swap-config
  [{:keys [action estimate transactionRequest]}]
  (let [approval-address (normalize-address (:approvalAddress estimate))
        from-amount-units (parse-positive-bigint (:fromAmount estimate))
        swap-token-address (normalize-address (get-in action [:fromToken :address]))
        swap-to-address (normalize-address (:to transactionRequest))
        swap-data (some-> (:data transactionRequest) str str/trim)
        swap-value (maybe-value-field (:value transactionRequest))]
    (when (and approval-address
               from-amount-units
               swap-token-address
               swap-to-address
               (seq swap-data))
      {:approval-address approval-address
       :from-amount-units from-amount-units
       :swap-token-address swap-token-address
       :swap-to-address swap-to-address
       :swap-data swap-data
       :swap-value swap-value})))
