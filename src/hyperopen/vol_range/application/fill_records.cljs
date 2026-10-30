(ns hyperopen.vol-range.application.fill-records
  "The canonical Vol Range fill record (HO-SPEC-014 v2.1 sections 7.4, 9.2,
  13.5 row 4): one pure normalizer for a WebSocket `userFills` row or a REST
  `userFillsByTime` row, shared by every consumer that keeps fills outside a
  grid (Reporting's durable ledger embeds it under `:fill`).

  `:fill-key` is exactly `lifecycle-evidence/fill-key` for the row, so the
  WebSocket row and the REST row of the same fill give equal records apart
  from `:source` and `:received-at-ms`, and de-duplicate against the keys
  grids already hold. Money fields keep the venue's text (never re-rounded);
  `:fee` is the venue's signed fee, which already includes `builderFee`.

  Grid records keep their own `:fills` map and its private shape; nothing
  here changes the lifecycle. Pure: no clock, storage or I/O."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]))

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

(defn- text
  "The venue value as text, or nil when missing or blank."
  [x]
  (some-> x str str/trim not-empty))

(defn- integer-text
  "Integer text for a venue time or trade id (as the fill key renders it),
  or nil when the value is missing or not an integer."
  [x]
  (when (some? x)
    (let [t (evidence/int-text x)]
      (when (re-matches #"\d+" t) t))))

(defn- decimal-text
  "The venue's decimal text, or nil when missing or not a plain decimal."
  [x]
  (when-let [t (text x)]
    (when (precision/parse-decimal t) t)))

(def ^:private sides {"B" :buy "A" :sell})

(defn normalize-fill
  "The canonical fill record for a raw venue fill `row` of `account-address`
  on `network`, received from `source` (:websocket or :rest), or nil when the
  row cannot identify a fill: a missing network or account, a missing or
  blank coin, a time or trade id that is not an integer, a price or size that
  is not a plain decimal, a side other than \"B\"/\"A\", or neither a lossless
  oid nor a CLOID."
  [{:keys [network account-address row source received-at-ms]}]
  (let [account (lower account-address)
        coin (text (:coin row))
        time-text (integer-text (:time row))
        tid (integer-text (:tid row))
        px (decimal-text (:px row))
        sz (decimal-text (:sz row))
        side (get sides (:side row))
        oid (lifecycle/oid-string (:oid row))
        cloid (lower (:cloid row))]
    (when (and network account coin time-text tid px sz side (or oid cloid))
      {:fill-key (evidence/fill-key network account-address row)
       :network network
       :account-address account
       :coin (:coin row)
       :time (js/parseInt time-text 10)
       :tid tid
       :oid oid
       :cloid cloid
       :side side
       :px px
       :sz sz
       :fee (text (:fee row))
       :fee-token (text (:feeToken row))
       :builder-fee (text (:builderFee row))
       :crossed (when (boolean? (:crossed row)) (:crossed row))
       :hash (text (:hash row))
       :dir (text (:dir row))
       :start-position (text (:startPosition row))
       :source source
       :received-at-ms received-at-ms})))

(defn same-fill?
  "True when both records are the same venue fill (equal fill keys)."
  [a b]
  (boolean (and (:fill-key a) (= (:fill-key a) (:fill-key b)))))
