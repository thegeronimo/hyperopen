(ns hyperopen.funding.domain.spot-tokens
  "Resolve Hyperliquid *wire token ids* for spot assets.

   A wire token id is the string Hyperliquid expects inside an exchange action
   that names a spot token, and it always has the form `NAME:0x<hash>` — for
   example `USDC:0x6d1e7cde53ba9467b783cb7c530ce054`. Two other identifiers look
   plausible and are both rejected by the exchange:

   - a bare symbol such as `USDC` (proven live on 2026-06-03; see
     `/hyperopen/docs/exec-plans/completed/2026-06-03-subaccount-unified-sendasset-routing.md`),
   - a bare token index such as `150`, which is what a
     `spotClearinghouseState` balance row carries in its own `:token` field
     (see `parse-token-index` in
     `hyperopen.portfolio.optimizer.application.spot-token-labels`).

   `spotMeta` is the only source that joins the three together. Its `:tokens`
   entries carry `:name`, `:index`, and `:tokenId` (a bare `0x` hash), and app
   state stores that payload verbatim at `[:spot :meta]`.

   Resolution returns `nil` rather than guessing when spot metadata has not
   loaded. Callers must surface that honestly instead of falling back to a bare
   symbol, because a bare symbol is a known-rejected identifier and signing one
   turns a visible gap into an opaque exchange error."
  (:require [clojure.string :as str]))

;; Full mainnet USDC spot token id expected by Hyperliquid signing. Used only as
;; a last-resort fallback for USDC when `spotMeta` is unavailable; every other
;; token must resolve through metadata or fail honestly.
(def mainnet-usdc-token
  "USDC:0x6d1e7cde53ba9467b783cb7c530ce054")

(defn- non-blank-text
  [value]
  (some-> value str str/trim not-empty))

(defn wire-token-id?
  "True when `value` already looks like a `NAME:0x<hash>` wire token id."
  [value]
  (boolean (some-> (non-blank-text value) (str/includes? ":"))))

(defn token-symbol
  "Human symbol for a token identifier: the part before the colon of a wire
   token id, or the identifier itself when it carries no colon. Returns nil for
   blank input and for a bare numeric index, which names nothing on its own."
  [value]
  (when-let [value* (non-blank-text value)]
    (let [symbol* (if (str/includes? value* ":")
                    (non-blank-text (first (str/split value* #":" 2)))
                    value*)]
      (when-not (re-matches #"^@?\d+$" (str symbol*))
        symbol*))))

(defn- token-index
  "Coerce a spot balance `:token` field (a number, \"113\", or \"@113\") to a
   token index. Mirrors `parse-token-index` in the optimizer's spot token
   labels namespace, which documents the same three shapes."
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      (js/Math.floor value))

    (string? value)
    (let [stripped (str/replace (str/trim value) #"^@" "")]
      (when (re-matches #"^\d+$" stripped)
        (js/Math.floor (js/Number stripped))))

    :else nil))

(defn- meta-tokens
  [state]
  (let [tokens (get-in state [:spot :meta :tokens])]
    (when (sequential? tokens) tokens)))

(defn- entry-wire-id
  "Wire token id for one `spotMeta` `:tokens` entry, or nil when the entry lacks
   either half of the identifier."
  [entry]
  (let [name* (non-blank-text (or (:name entry) (get entry "name")))
        token-id (non-blank-text (or (:tokenId entry)
                                     (:token-id entry)
                                     (get entry "tokenId")
                                     (get entry "token-id")))]
    (when (and name* token-id)
      (str name* ":" token-id))))

(defn- entry-index
  [entry]
  (token-index (or (:index entry) (get entry "index"))))

(defn- entry-name
  [entry]
  (some-> (or (:name entry) (get entry "name")) non-blank-text str/upper-case))

(defn resolver
  "Build a reusable wire-token-id resolver from app state's `[:spot :meta]`.

   Returns `{:by-index {index -> wire-id} :by-name {UPPER-NAME -> wire-id}}`.
   Both maps are empty when spot metadata has not loaded."
  [state]
  (reduce (fn [acc entry]
            (if-let [wire-id (entry-wire-id entry)]
              (cond-> acc
                (some? (entry-index entry)) (assoc-in [:by-index (entry-index entry)] wire-id)
                (some? (entry-name entry)) (assoc-in [:by-name (entry-name entry)] wire-id))
              acc))
          {:by-index {} :by-name {}}
          (meta-tokens state)))

(defn resolve-with
  "Wire token id for `{:coin :token}` using a prebuilt `resolver`, or nil.

   `token` is a spot balance's own `:token` field (a numeric index) or an
   already-resolved wire token id; `coin` is the balance's human coin name.
   An identifier that already carries a colon is trusted and returned as-is."
  [{:keys [by-index by-name]} {:keys [coin token]}]
  (or (when (wire-token-id? token)
        (non-blank-text token))
      (get by-index (token-index token))
      (get by-name (some-> (token-symbol token) str/upper-case))
      (get by-name (some-> (non-blank-text coin) str/upper-case))
      (when (= "USDC" (some-> (or (token-symbol token) (non-blank-text coin))
                              str/upper-case))
        mainnet-usdc-token)))

(defn wire-token-id
  "Wire token id for `{:coin :token}` read from app `state`, or nil.

   Prefer `resolver` + `resolve-with` when resolving a whole balance list; this
   arity rebuilds the lookup maps on every call."
  [state balance]
  (resolve-with (resolver state) balance))

(defn usdc-wire-token-id
  "Wire token id for USDC, falling back to the mainnet constant when spot
   metadata has not loaded. USDC is the one token that may fall back, because
   its mainnet id is fixed and already proven against the live exchange."
  [state]
  (or (resolve-with (resolver state) {:coin "USDC"})
      mainnet-usdc-token))
