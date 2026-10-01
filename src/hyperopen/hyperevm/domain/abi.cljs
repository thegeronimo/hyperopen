(ns hyperopen.hyperevm.domain.abi
  "Hand-rolled Solidity ABI encoding for the few HyperEVM calls the app makes.

   Every selector is a constant (keccak-256 of the signature, first 4 bytes),
   so no hashing library is needed. The values were checked with foundry's
   `cast sig` on 2026-09-30. uint256 values are `js/BigInt` because 18-decimal
   amounts exceed a JS number's precision.

   Every public function returns nil on invalid input and never throws: an
   invalid address or amount must surface as a missing calldata the caller
   can explain, not as a TypeError thrown outside a promise chain."
  (:require [clojure.string :as str]
            [hyperopen.hyperevm.domain.units :as units]))

(def selectors
  {:balance-of "0x70a08231"
   :transfer "0xa9059cbb"
   :approve "0x095ea7b3"
   :allowance "0xdd62ed3e"
   :core-deposit "0x2b2dfd2c"
   :get-eth-balance "0x4d2301cc"
   :aggregate3 "0x82ad56cb"
   ;; ERC-20 `decimals()`, added in Milestone 2. The live bridge-health probe
   ;; on 2026-09-30 got the expected decimals back through it for PURR, SIX,
   ;; UBTC and FUNT.
   :decimals "0x313ce567"})

(def ^:private word-hex-length 64)

(def ^:private uint256-limit
  "2^256, the first value that does not fit a uint256 word."
  (loop [acc (js/BigInt 1)
         i 0]
    (if (< i 256)
      (recur (* acc (js/BigInt 2)) (inc i))
      acc)))

(def ^:private uint32-limit
  (js/BigInt 4294967296))

(defn normalize-address
  "Lowercase `0x` + 40-hex address, or nil when `value` is not one."
  [value]
  (when (string? value)
    (let [text (str/lower-case (str/trim value))]
      (when (re-matches #"^0x[0-9a-f]{40}$" text)
        text))))

(defn- left-pad-hex
  [hex width]
  (str (apply str (repeat (- width (count hex)) "0")) hex))

(defn- right-pad-hex
  [hex]
  (let [remainder (js-mod (count hex) word-hex-length)]
    (if (= 0 remainder)
      hex
      (str hex (apply str (repeat (- word-hex-length remainder) "0"))))))

(defn- uint-word
  "64-hex word for a non-negative integer below `limit`, or nil."
  [value limit]
  (when-let [value* (units/to-bigint value)]
    (when (< value* limit)
      (left-pad-hex (.toString value* 16) word-hex-length))))

(defn- uint256-word
  [value]
  (uint-word value uint256-limit))

(defn- address-word
  [address]
  (when-let [address* (normalize-address address)]
    (left-pad-hex (subs address* 2) word-hex-length)))

(defn- bool-word
  [value]
  (left-pad-hex (if value "1" "0") word-hex-length))

(defn- hex-body
  "The hex digits of `0x`-prefixed, even-length hex data (lowercased), or nil.
   `\"0x\"` is valid empty data."
  [value]
  (when (string? value)
    (let [text (str/lower-case (str/trim value))]
      (when (and (re-matches #"^0x[0-9a-f]*$" text)
                 (even? (count text)))
        (subs text 2)))))

(defn- call-data
  [selector & words]
  (when (every? some? words)
    (apply str selector words)))

(defn encode-balance-of
  "Calldata for ERC-20 `balanceOf(owner)`."
  [owner]
  (call-data (:balance-of selectors) (address-word owner)))

(defn encode-transfer
  "Calldata for ERC-20 `transfer(to, units)`."
  [to amount-units]
  (call-data (:transfer selectors) (address-word to) (uint256-word amount-units)))

(defn encode-approve
  "Calldata for ERC-20 `approve(spender, units)`."
  [spender amount-units]
  (call-data (:approve selectors) (address-word spender) (uint256-word amount-units)))

(defn encode-allowance
  "Calldata for ERC-20 `allowance(owner, spender)`."
  [owner spender]
  (call-data (:allowance selectors) (address-word owner) (address-word spender)))

(defn encode-core-deposit
  "Calldata for Circle's CoreDepositWallet `deposit(uint256 amount,
   uint32 destinationDex)`. `destination-dex` is 0 for perps and 4294967295
   for spot (see `hyperopen.hyperevm.domain.chain`)."
  [amount-units destination-dex]
  (call-data (:core-deposit selectors)
             (uint256-word amount-units)
             (uint-word destination-dex uint32-limit)))

(defn encode-decimals
  "Calldata for ERC-20 `decimals()`, which takes no arguments."
  []
  (:decimals selectors))

(defn encode-get-eth-balance
  "Calldata for Multicall3 `getEthBalance(owner)`: native HYPE, in wei."
  [owner]
  (call-data (:get-eth-balance selectors) (address-word owner)))

(defn- encode-call3
  "Tail encoding of one `(address target, bool allowFailure, bytes callData)`
   tuple. The bytes offset is 0x60: three head words precede the bytes."
  [call]
  (let [{:keys [target allow-failure? call-data]} (when (map? call) call)
        target-word (address-word target)
        body (hex-body call-data)]
    (when (and target-word body)
      (str target-word
           (bool-word allow-failure?)
           (uint256-word 96)
           (uint256-word (quot (count body) 2))
           (right-pad-hex body)))))

(defn encode-aggregate3
  "Calldata for Multicall3 `aggregate3((address,bool,bytes)[] calls)`.

   `calls` is a sequence of `{:target :allow-failure? :call-data}`. A dynamic
   array of dynamic tuples encodes as: offset to the array (0x20), its length,
   one offset per tuple (relative to the first offset word), then the tuple
   tails. Returns nil when any call is invalid."
  [calls]
  (when (sequential? calls)
    (let [tails (mapv encode-call3 calls)]
      (when (every? some? tails)
        (let [head-bytes (* 32 (count tails))
              offsets (->> tails
                           (reductions (fn [offset tail]
                                         (+ offset (quot (count tail) 2)))
                                       head-bytes)
                           (take (count tails)))]
          (apply str
                 (:aggregate3 selectors)
                 (uint256-word 32)
                 (uint256-word (count tails))
                 (concat (map uint256-word offsets) tails)))))))

;; --- decoding --------------------------------------------------------------

(defn- word-at
  "The 64-hex word starting at byte `offset` of `body`, or nil past the end."
  [body offset]
  (when (and (number? offset)
             (not (neg? offset)))
    (let [start (* 2 offset)
          end (+ start word-hex-length)]
      (when (<= end (count body))
        (subs body start end)))))

(defn- word-number
  "A word read as a JS number, or nil when it exceeds `bound` (used for
   offsets and lengths, which must point inside the data)."
  [word bound]
  (when word
    (let [value (js/BigInt (str "0x" word))]
      (when (<= value (js/BigInt bound))
        (js/Number value)))))

(defn- decode-bool
  [word]
  (case word
    "0000000000000000000000000000000000000000000000000000000000000000" false
    "0000000000000000000000000000000000000000000000000000000000000001" true
    nil))

(defn- decode-result-tuple
  "Decode one `(bool success, bytes returnData)` tuple starting at byte
   `start` of `body`."
  [body start]
  (let [total (quot (count body) 2)
        success? (decode-bool (word-at body start))
        bytes-offset (word-number (word-at body (+ start 32)) total)
        bytes-start (when bytes-offset (+ start bytes-offset))
        bytes-length (when bytes-start
                       (word-number (word-at body bytes-start) total))
        data-start (when bytes-length (+ bytes-start 32))]
    (when (and (some? success?)
               data-start
               (<= (+ data-start bytes-length) total))
      {:success? success?
       :return-data (str "0x" (subs body
                                    (* 2 data-start)
                                    (* 2 (+ data-start bytes-length))))})))

(defn- decode-aggregate3*
  [body]
  (let [total (quot (count body) 2)
        array-offset (word-number (word-at body 0) total)
        length (when array-offset
                 (word-number (word-at body array-offset) total))
        heads-start (when length (+ array-offset 32))]
    (when (and heads-start
               (<= (+ heads-start (* 32 length)) total))
      (let [results (mapv (fn [i]
                            (when-let [offset (word-number
                                               (word-at body (+ heads-start (* 32 i)))
                                               total)]
                              (decode-result-tuple body (+ heads-start offset))))
                          (range length))]
        (when (every? some? results)
          results)))))

(defn decode-aggregate3
  "Decode Multicall3 `aggregate3` return data, `(bool success, bytes
   returnData)[]`, into `[{:success? bool :return-data \"0x…\"}]` in call
   order. Returns nil for malformed data: every offset and length is
   bounds-checked against the payload before it is followed."
  [hex]
  (when-let [body (hex-body hex)]
    (try
      (decode-aggregate3* body)
      (catch :default _
        nil))))

(defn decode-uint256
  "The first 32-byte word of `hex` return data as a BigInt, or nil when the
   data is shorter than one word (a failed call returns `0x`)."
  [hex]
  (when-let [body (hex-body hex)]
    (when-let [word (word-at body 0)]
      (js/BigInt (str "0x" word)))))

;; --- JSON-RPC quantities -----------------------------------------------------

(defn quantity-hex
  "JSON-RPC quantity encoding (`0x` + minimal lowercase hex, `0x0` for zero)
   for a non-negative integer, or nil."
  [value]
  (when-let [value* (units/to-bigint value)]
    (str "0x" (.toString value* 16))))

(defn parse-quantity
  "BigInt for a JSON-RPC hex quantity such as `\"0x666f5c3\"`, or nil."
  [value]
  (when (and (string? value)
             (re-matches #"^0x[0-9a-fA-F]+$" (str/trim value)))
    (units/to-bigint value)))
