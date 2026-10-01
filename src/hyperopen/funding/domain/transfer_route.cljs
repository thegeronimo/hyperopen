(ns hyperopen.funding.domain.transfer-route
  "Where a Transfer moves funds from and to, and what it can move.

   The Transfer modal has three places: Perps and Spot on HyperCore, and
   HyperEVM. A route is a `{:from :to}` pair of them. Its kind decides
   everything downstream:

   - `:core-internal` is today's Perps <-> Spot USDC move, handled by the
     legacy preview byte-for-byte.
   - `:core->evm` sends a HyperCore balance to the token's system address
     with `sendAsset`.
   - `:evm->core` is a HyperEVM transaction from the owner's wallet.

   The modal keeps the legacy `:to-perp?` flag. While `:transfer-from` and
   `:transfer-to` are both nil the route is derived from it, so every legacy
   opener keeps working unchanged. Any other partial or invalid pair is
   reported as invalid, never silently replaced by the legacy route."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.domain.token-pricing :as token-pricing]
            [hyperopen.funding.domain.availability :as availability]
            [hyperopen.funding.domain.transfer-balances :as transfer-balances]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.tokens :as tokens]))

(def locations
  [:perps :spot :hyperevm])

(def location-labels
  {:perps "Perps"
   :spot "Spot"
   :hyperevm "HyperEVM"})

(def unified-perps-reason
  "Unified accounts share one balance for Perps and Spot. Use Spot.")

(def evm-to-perps-reason
  "Move to Spot first, then Spot → Perps.")

(def perps-to-evm-reason
  "Move USDC to Spot first, then Spot → HyperEVM.")

(def pooled-perps-evm-reason
  "Perps and Spot share one USDC balance on this account. Move USDC to HyperEVM from Spot.")

(def unmovable-token-reason
  "This token's HyperEVM link can't be verified, so it can't be moved here.")

(def checking-token-reason
  "Checking this token's HyperEVM link…")

(def bridge-empty-evm-reason
  "This token's HyperEVM bridge is empty, so nothing can be moved to HyperEVM right now.")

(def bridge-empty-core-reason
  "This token's HyperCore bridge is empty, so nothing can be moved back to HyperCore right now.")

(defn normalize-location
  "`:perps`, `:spot` or `:hyperevm` from a keyword or string, else nil."
  [value]
  (let [location (cond
                   (keyword? value) value
                   (string? value) (some-> value str/trim str/lower-case not-empty keyword)
                   :else nil)]
    (when (some #{location} locations)
      location)))

(defn normalize-side
  [value]
  (let [side (cond
               (keyword? value) value
               (string? value) (some-> value str/trim str/lower-case not-empty keyword)
               :else nil)]
    (when (contains? #{:from :to} side)
      side)))

(defn normalize-asset-index
  "A spot token index from an integer or a digit string, else nil."
  [value]
  (cond
    (and (number? value) (js/Number.isInteger value) (not (neg? value))) value
    (and (string? value) (re-matches #"^\d+$" (str/trim value))) (js/parseInt (str/trim value) 10)
    :else nil))

(defn- supported-pair?
  "Whether `from` -> `to` is a move the app performs. Neither Perps <->
   HyperEVM direction is:

   - HyperEVM -> Perps: Circle's Perps deposit settles asynchronously
     through CoreWriter and cannot revert, so it goes HyperEVM -> Spot ->
     Perps.
   - Perps -> HyperEVM: a `sendAsset` that leaves the Perps dex
     (`sourceDex \"\"`) for USDC's system address has never been seen
     bridged live (only `sourceDex \"spot\"` has), and if HyperCore credited
     the system address's Spot without bridging it the USDC would be
     stranded. It goes Perps -> Spot -> HyperEVM until it is verified."
  [from to]
  (boolean (and from to
                (not= from to)
                (not (and (= :hyperevm from) (= :perps to)))
                (not (and (= :perps from) (= :hyperevm to))))))

(defn perps-evm-pair?
  "Whether `route` joins Perps and HyperEVM directly (unsupported both
   ways)."
  [{:keys [from to]}]
  (= #{:perps :hyperevm} (set [from to])))

(defn transfer-route
  "`{:from :to :valid? bool :legacy? bool}` for the modal.

   Both locations nil derives the legacy route from `:to-perp?`. Both set,
   distinct and supported is valid. Anything else is invalid and keeps what
   was set, so the preview can block with `:invalid-route`."
  [modal]
  (let [from (normalize-location (:transfer-from modal))
        to (normalize-location (:transfer-to modal))]
    (if (and (nil? from) (nil? to))
      (if (true? (:to-perp? modal))
        {:from :spot :to :perps :valid? true :legacy? true}
        {:from :perps :to :spot :valid? true :legacy? true})
      {:from from
       :to to
       :valid? (supported-pair? from to)
       :legacy? false})))

(defn route-kind
  "`:core-internal`, `:core->evm` or `:evm->core`, from whichever side names
   HyperEVM. An invalid route still gets a kind, so the EVM preview can
   explain it."
  [{:keys [from to]}]
  (cond
    (= :hyperevm from) :evm->core
    (= :hyperevm to) :core->evm
    :else :core-internal))

(defn evm-route?
  [route]
  (not= :core-internal (route-kind route)))

(defn perps-spot-pair?
  [{:keys [from to]}]
  (= #{:perps :spot} (set [from to])))

(defn with-location
  "The route after setting `side` to `location`, as `{:from :to}`.

   Choosing the location the other side already has swaps the two. The side
   just set wins over an unsupported Perps <-> HyperEVM pair: the other side
   becomes Spot."
  [{:keys [from to]} side location]
  (let [[from* to*] (case side
                      :from (if (= location to) [location from] [location to])
                      :to (if (= location from) [to location] [from location])
                      [from to])]
    (cond
      (perps-evm-pair? {:from from* :to to*})
      (if (= :from side) [from* :spot] [:spot to*])

      :else [from* to*])))

(defn swapped
  "The route with its sides swapped; an unsupported Perps <-> HyperEVM
   result keeps its new source and goes to (or from) Spot instead."
  [{:keys [from to]}]
  (cond
    (and (= :perps from) (= :hyperevm to)) [:hyperevm :spot]
    (and (= :hyperevm from) (= :perps to)) [:perps :spot]
    :else [to from]))

;; --- location options ------------------------------------------------------

(defn- pooled-perps-evm-pair?
  "Whether choosing `location` for `side` pairs Perps with HyperEVM on an
   account whose Perps USDC is pooled with Spot (unified, portfolio margin,
   or DEX abstraction). Such accounts move USDC to HyperEVM from Spot."
  [state {:keys [from to]} side location]
  (and (or (and (= :from side) (= :perps location) (= :hyperevm to))
           (and (= :to side) (= :hyperevm location) (= :perps from)))
       (availability/pooled-perps-collateral? state)))

(defn- location-reason
  [state {:keys [from] :as route} side location]
  (let [hyperevm-block (account-context/hyperevm-moves-blocked-message state)]
    (cond
      (and (= :hyperevm location) hyperevm-block)
      hyperevm-block

      (and (= :perps location) (availability/unified-account-mode? state))
      unified-perps-reason

      (and (= :to side) (= :perps location) (= :hyperevm from))
      evm-to-perps-reason

      (pooled-perps-evm-pair? state route side location)
      pooled-perps-evm-reason

      (and (= :to side) (= :hyperevm location) (= :perps from))
      perps-to-evm-reason

      :else nil)))

(defn location-options
  "`{:from [..] :to [..]}`, each option `{:id :label :selected? :disabled?
   :reason}`. `:reason` is set whenever the location is unavailable for that
   side, so the view can always show why. An option already on the route
   stays enabled (the legacy Perps route of a unified account still submits
   unchanged); `:disabled?` only stops choosing it anew."
  [state route]
  (into {}
        (map (fn [side]
               [side (mapv (fn [location]
                             (let [selected? (= location (get route side))
                                   reason (location-reason state route side location)]
                               {:id location
                                :label (get location-labels location)
                                :selected? selected?
                                :disabled? (boolean (and reason (not selected?)))
                                :reason reason}))
                           locations)]))
        [:from :to]))

;; --- asset options ---------------------------------------------------------

(defn linked-tokens
  [state]
  (tokens/linked-tokens (get-in state [:spot :meta])))

(defn route-token
  "The linked token the modal's `:transfer-asset` names, or nil."
  [state modal]
  (when-let [index (normalize-asset-index (:transfer-asset modal))]
    (tokens/token-by-index (get-in state [:spot :meta]) index)))

(defn- source-tokens
  [state route]
  (if (and (evm-route? route) (not (perps-evm-pair? route)))
    (linked-tokens state)
    []))

(defn- health-reason
  [health]
  (case health
    :bad unmovable-token-reason
    :unknown checking-token-reason
    nil))

(defn- empty-bridge?
  "Whether the bridge is known to deliver none of `token` on `route`: its
   last capacity reading is \"0\". Unknown capacity is not empty."
  [state route token]
  (= "0" (case (route-kind route)
           :core->evm (bridge/core->evm-capacity state token)
           :evm->core (bridge/evm->core-capacity state token)
           nil)))

(defn- asset-option
  "The option for `token` when the route's source holds some of it, else
   nil. Only held tokens are priced, so the catalog of every linked token is
   not looked up in the market list on each render.

   A token whose bridge is known to be empty is `:empty-bridge?`. Toward
   HyperEVM it is also disabled with the Balances table's reason: that
   reading comes with every balance poll, so it stays current. Toward
   HyperCore it stays selectable, because its HyperCore reading is fetched
   only for the selected token, so a disabled one could never be read
   again; the preview then shows the block."
  [state route owner token]
  (let [available (transfer-balances/location-available-text state (:from route) token owner)]
    (when (transfer-balances/positive-text? available)
      (let [price (token-pricing/market-token-price-usd state (:name token))
            amount (js/parseFloat available)
            health (bridge/token-health-status state token)
            empty? (and (not= :bad health) (empty-bridge? state route token))
            reason (or (health-reason health)
                       (when (and empty? (= :core->evm (route-kind route)))
                         bridge-empty-evm-reason))]
        {:index (:index token)
         :symbol (:name token)
         :token token
         :available available
         :usd-value (when (and (number? price) (js/isFinite amount))
                      (* price amount))
         :health health
         :empty-bridge? (boolean empty?)
         :disabled? (some? reason)
         :reason reason}))))

(defn- option-order
  [{:keys [usd-value symbol]}]
  [(if (number? usd-value) 0 1)
   (if (number? usd-value) (- usd-value) 0)
   (str symbol)])

(defn asset-options
  "The linked tokens the route's source holds, most valuable first (by USD
   where a price resolves, then by name). Each is `{:index :symbol :token
   :available :usd-value :health :disabled? :reason}`. A held token whose
   bridge health is bad stays listed, disabled, with the reason; one whose
   health is not known yet is disabled as \"Checking…\". Empty for the
   Perps <-> Spot route, which always moves USDC."
  [state route]
  (let [owner (account-context/owner-address state)]
    (if (and (:from route) (evm-route? route))
      (->> (source-tokens state route)
           (keep #(asset-option state route owner %))
           (sort-by option-order)
           vec)
      [])))

(defn eligible-asset-index
  "The asset to keep selected on `options`: `current` when it is listed and
   not known to be unmovable or to have an empty bridge, else the first
   option that is enabled and whose bridge is not known to be empty, else
   the first enabled one, else nil. So a preset opener never lands on a
   blocked asset while another could move."
  [options current]
  (let [current* (normalize-asset-index current)
        listed (some #(when (= current* (:index %)) %) options)
        pick (fn [ok?] (some #(when (ok? %) (:index %)) options))]
    (if (and listed (not= :bad (:health listed)) (not (:empty-bridge? listed)))
      current*
      (or (pick #(and (not (:disabled? %)) (not (:empty-bridge? %))))
          (pick (complement :disabled?))))))

(defn empty-assets-message
  "Copy for a source that holds no movable linked token."
  [{:keys [from]}]
  (case from
    :hyperevm "No linked tokens on HyperEVM yet."
    :perps "No USDC available in Perps."
    "No linked tokens on Spot."))
