(ns hyperopen.views.pnl-share.card-data
  "Turns a Positions-table row view-model into the flat, already-formatted map
   both share-card templates render from.

   Everything here is pure so the card is unit-testable in the node suite, and
   every optional field resolves to nil rather than an empty string when it
   cannot be derived. A share card is the most public thing this app renders:
   docs/agent-guides/trading-ui-policy.md forbids showing a fake zero as real
   data, so a field with no honest value is simply absent from the card."
  (:require [clojure.string :as str]
            [hyperopen.pnl-share.naming :as naming]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info.shared :as shared]))

(def default-options
  {:show-prices? true
   :show-funding? true
   :show-handle? true})

(def default-template
  :neon-arrow)

(def templates
  [{:id :neon-arrow :label "Neon arrow"}
   {:id :number-hero :label "Number as hero"}])

(def template-ids
  (into #{} (map :id) templates))

(def ^:private flat-position-epsilon
  1e-12)

(defn- num
  [value]
  (shared/parse-optional-num value))

(defn- flat-start-position?
  [value]
  (let [n (num value)]
    (and (number? n)
         (< (js/Math.abs n) flat-position-epsilon))))

(defn opened-at-ms
  "Epoch millis at which the position's current episode opened.

   Walks this coin's fills oldest-first and keeps the time of the most recent
   fill that started from a flat position. Returns nil when no such fill is in
   the loaded window -- a position opened before the window starts has no
   honest open time, and the card omits Held rather than inventing one."
  [coin fills]
  (when-let [coin* (shared/non-blank-text coin)]
    (->> (or fills [])
         (filter (fn [fill] (= coin* (shared/non-blank-text (:coin fill)))))
         (sort-by (fn [fill] (or (num (:time fill)) 0)))
         (reduce (fn [acc fill]
                   (if (flat-start-position? (:startPosition fill))
                     (num (:time fill))
                     acc))
                 nil))))

(defn format-held
  "Duration in the card's own compact form: 3d 04h, 11h 42m, 7m."
  [ms]
  (when (and (number? ms)
             (pos? ms))
    (let [total-minutes (js/Math.floor (/ ms 60000))
          days (js/Math.floor (/ total-minutes 1440))
          hours (js/Math.floor (/ (mod total-minutes 1440) 60))
          minutes (mod total-minutes 60)]
      (cond
        (pos? days) (str days "d " (fmt/pad2 hours) "h")
        (pos? hours) (str hours "h " (fmt/pad2 minutes) "m")
        :else (str minutes "m")))))

(defn format-utc-timestamp
  "YYYY-MM-DD HH:MM UTC. The card always states its timezone, per the trading
   UI policy on freshness context."
  [ms]
  (when (number? ms)
    (let [date (js/Date. ms)]
      (str (.getUTCFullYear date)
           "-" (fmt/pad2 (inc (.getUTCMonth date)))
           "-" (fmt/pad2 (.getUTCDate date))
           " " (fmt/pad2 (.getUTCHours date))
           ":" (fmt/pad2 (.getUTCMinutes date))
           " UTC"))))

(defn- sign-prefix
  [value]
  (cond
    (and (number? value) (pos? value)) "+"
    (and (number? value) (neg? value)) "-"
    :else ""))

(defn format-roe
  "Signed percentage to one decimal, matching the table cell it came from."
  [percent]
  (when (number? percent)
    (str (sign-prefix percent)
         (.toFixed (js/Math.abs percent) 1)
         "%")))

(defn format-pnl
  "Signed currency to two decimals, matching the table cell it came from."
  [value]
  (when (number? value)
    (str (sign-prefix value)
         "$"
         (shared/format-currency (js/Math.abs value)))))

(defn short-address
  [address]
  (let [address* (shared/non-blank-text address)]
    (when (and address*
               (< 12 (count address*)))
      (str (subs address* 0 6) "…" (subs address* (- (count address*) 4))))))

(defn- site-host
  [origin]
  (or (some-> (shared/non-blank-text origin)
              (str/replace #"^[a-zA-Z][a-zA-Z0-9+.-]*://" "")
              (str/replace #"^www\." "")
              (str/replace #"/+$" "")
              shared/non-blank-text)
      "hyperopen.xyz"))

(defn- site-origin-url
  [origin]
  (or (shared/non-blank-text origin)
      (str "https://" (site-host origin))))

(defn- leverage-value
  [row-vm]
  (num (get-in row-vm [:position :leverage :value])))

(defn- side-word
  [side]
  (case side
    :long "LONG"
    :short "SHORT"
    nil))

(defn- leverage-label
  [row-vm]
  (let [word (side-word (:side row-vm))
        leverage (leverage-value row-vm)]
    (cond
      (and word (number? leverage)) (str word " " (fmt/format-number leverage 0) "X")
      word word
      :else nil)))

(defn- monogram
  [coin-label]
  (some-> (shared/non-blank-text coin-label)
          (subs 0 1)
          str/upper-case))

(defn- normalize-options
  [options]
  (merge default-options (select-keys (or options {}) (keys default-options))))

(defn normalize-template
  [template]
  (if (contains? template-ids template)
    template
    default-template))

(defn- summary-line
  "A factual one-liner for the templates that carry one.

   The card reports an OPEN position, so it says so: the figures are marked
   against the live mark price and are not a settled result."
  [coin-label leverage-label]
  (let [lead (cond
               (and coin-label leverage-label)
               (str (str/capitalize (str/lower-case leverage-label)) " " coin-label)

               coin-label coin-label
               :else nil)]
    (when lead
      (str lead " · open position, marked live"))))

(defn default-caption
  "The caption the modal pre-fills, in the same spirit as the design's mock.
   Kept factual: it restates what the card already shows."
  [{:keys [coin-label roe-text leverage-label]}]
  (let [parts (cond-> []
                roe-text (conj (str "Closed " roe-text))
                coin-label (conj (str "on $" coin-label))
                leverage-label (conj (str "(" (str/lower-case leverage-label) ")")))]
    (when (seq parts)
      (str (str/join " " parts) " — traded on hyperopen."))))

(defn position-card-data
  "Builds the render map for one position.

   `row-vm` is the map produced by
   hyperopen.views.account-info.positions-vm/position-row-vm. `ctx` carries the
   things the row cannot know: who owns the account, whether a referral code has
   arrived, where the app is served from, the current time, the loaded fills,
   the chosen template and the field toggles."
  [row-vm {:keys [owner-address referral-code site-origin now-ms fills template options
                  icon-data-uri]}]
  (let [options* (normalize-options options)
        pnl-num (:pnl-num row-vm)
        pnl-percent (:pnl-percent row-vm)
        winning? (if (number? pnl-num)
                   (not (neg? pnl-num))
                   (not (and (number? pnl-percent) (neg? pnl-percent))))
        coin-label (shared/non-blank-text (:coin-label row-vm))
        raw-coin (get-in row-vm [:position :coin])
        opened-ms (opened-at-ms raw-coin fills)
        held-ms (when (and (number? opened-ms) (number? now-ms))
                  (- now-ms opened-ms))
        code (shared/non-blank-text referral-code)
        origin (site-origin-url site-origin)
        host (site-host site-origin)
        roe-text (format-roe pnl-percent)
        lev-label (leverage-label row-vm)]
    {:template (normalize-template template)
     :winning? winning?
     :side (:side row-vm)
     :coin-label (or coin-label "--")
     :dex-label (shared/non-blank-text (:dex-label row-vm))
     :monogram (or (monogram coin-label) "?")
     :monogram-key (or coin-label "")
     :icon-data-uri (shared/non-blank-text icon-data-uri)
     :leverage-label lev-label
     :roe-text roe-text
     :roe-label (if winning? "UNREALIZED P&L" "UNREALIZED LOSS")
     :roe-label-compact "UNREALIZED"
     :summary-line (summary-line coin-label lev-label)
     :pnl-text (format-pnl pnl-num)
     :entry-price-text (when (:show-prices? options*)
                         (shared/format-trade-price (:entry-price row-vm)))
     :mark-price-text (when (:show-prices? options*)
                        (:mark-price-display row-vm))
     :size-text (shared/non-blank-text (:size-display row-vm))
     :held-text (format-held held-ms)
     :funding-text (when (:show-funding? options*)
                     (shared/non-blank-text (:funding-display-text row-vm)))
     :handle-text (when (:show-handle? options*)
                    (short-address owner-address))
     :timestamp-text (format-utc-timestamp now-ms)
     :site-label host
     :join-link (if code (str origin "/join/" code) origin)
     :join-label (if code (str host "/join/" code) host)
     :has-referral-code? (some? code)
     :loss-pill (when-not winning? "CERTIFIED L")
     :file-name (naming/card-file-name coin-label (:side row-vm) now-ms)
     :alt-text (str/join " "
                         (remove nil?
                                 ["Share card:"
                                  (or coin-label "position")
                                  lev-label
                                  roe-text]))}))
