(ns hyperopen.portfolio.optimizer.actions.vault-funding
  "Actions of the Fund-this-allocation sheet. Pure: each returns effect
  vectors. Sending happens in one effect, `:effects/run-portfolio-optimizer-
  vault-funding`, which walks the run's rows in order."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.optimizer.actions.execution :as execution-actions]
            [hyperopen.portfolio.optimizer.application.vault-funding.model :as model]
            [hyperopen.portfolio.optimizer.application.vault-funding.run :as run]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(def ^:private path contracts/ui-vault-funding-path)

(defn- save
  [k v]
  [:effects/save (conj path k) v])

(defn- result
  [state]
  (get-in state contracts/last-successful-run-result-path))

(defn- sanitize-amount
  [value]
  ;; Digits, both decimal marks and the space-like group marks some locales use.
  (let [text (str/replace (str (or value "")) #"[^0-9.,\s\u00A0\u202F']" "")]
    (subs text 0 (min 24 (count text)))))

(defn- fingerprint
  "What the user reviewed: each step's kind, vault and exact micros."
  [rows]
  (mapv (juxt :kind :address :usd-micros) rows))

(defn set-portfolio-optimizer-vault-funding-field
  "`field` with its value:

    :amount-input       the typed amount
    :amount-format      the typed amount, reformatted in the user's locale
                        (on blur, so the caret never jumps while typing)
    :amount-fraction    0..1 of the spendable USDC (25%, 50%, Max)
    :include-existing?  :withdraw-over?   booleans
    :closed-mode        :spread | :cash"
  [state field value]
  (let [field* (coercion/normalize-keyword-like field)]
    (cond
      (model/running? state)
      []

      (= field* :amount-input)
      [(save :amount-input (sanitize-amount value)) (save :error nil)]

      (= field* :amount-format)
      (let [locale (get-in state [:ui :locale])
            amount (model/parse-amount (sanitize-amount value) locale)]
        (if (number? amount)
          [(save :amount-input (model/format-amount-input amount locale))]
          []))

      (= field* :amount-fraction)
      (let [available (:available (model/funding-model state (result state) 0))
            fraction (coercion/parse-float-number value)]
        (if (and (number? available) (number? fraction) (<= 0 fraction 1))
          [(save :amount-input (model/format-amount-input (* available fraction) (get-in state [:ui :locale])))
           (save :error nil)]
          []))

      (contains? #{:include-existing? :withdraw-over?} field*)
      [(save field* (true? value)) (save :error nil)]

      (= field* :closed-mode)
      (let [mode (coercion/normalize-keyword-like value)]
        (if (contains? #{:spread :cash} mode)
          [(save :closed-mode mode)]
          []))

      :else
      [])))

(defn- holdings-fetch
  [state]
  (if-let [address (account-context/effective-account-address state)]
    [[:effects/api-fetch-user-vault-equities address]]
    []))

(defn- current-run?
  "The stored run belongs to this wallet and this optimizer result."
  [state]
  (run/matches? (model/run-state state) (model/owner state) (model/source-id state)))

(defn- open-effects
  [state]
  (let [current (model/run-state state)
        status (:status current)]
    (into (cond
            (= :running status) [(save :screen :progress)]
            ;; A finished run from another wallet or another result is history,
            ;; not something to resume: start a fresh plan.
            (and (contains? run/terminal-statuses status) (current-run? state)) [(save :screen :progress)]
            :else [(save :screen :plan) (save :run nil) (save :reviewed nil)])
          (into [(save :open? true) (save :error nil)]
                (holdings-fetch state)))))

(defn- agent-gate
  "nil when trading is ready; otherwise the effects that handle it, with the
  confirm replayed after a passkey unlock."
  [state replay]
  (let [agent-status (get-in state [:wallet :agent :status])]
    (cond
      (= :ready agent-status) nil
      (= :locked agent-status) [(save :error nil)
                                [:effects/save-many [[[:wallet :agent :status] :unlocking]
                                                     [[:wallet :agent :error] nil]]]
                                [:effects/unlock-agent-trading
                                 {:after-success-actions [[:actions/portfolio-optimizer-vault-funding-command replay]]}]]
      (= :unlocking agent-status) [(save :error "Awaiting passkey before sending.")]
      :else [[:effects/save [:wallet :agent :recovery-modal-open?] true]
             (save :error "Enable trading to send vault transfers.")])))

(defn- send-gate
  "Effects that stop a send (with the reason saved), or nil to go ahead."
  [state replay]
  (cond
    (model/running? state) []
    (model/account-block state) [(save :error (model/account-block state))]
    (execution-actions/stale-recommendation? state)
    [(save :error "The setup changed since this run. Rerun the optimizer before funding.")]
    :else (agent-gate state replay)))

(defn- review-effects
  [state now-ms]
  (let [funding (model/funding-model state (result state) now-ms)]
    (if (:ready? funding)
      [(save :screen :review)
       (save :error nil)
       (save :reviewed (fingerprint (run/rows-from-plan (:plan funding))))]
      [(save :error (:block funding))])))

(defn- confirm-effects
  [state now-ms]
  (let [funding (model/funding-model state (result state) now-ms)
        rows (run/rows-from-plan (:plan funding))
        reviewed (:reviewed (model/funding-state state))]
    (or (send-gate state :confirm)
        (cond
          (not (:ready? funding)) [(save :error (:block funding))]
          (empty? rows) [(save :error "Nothing to send.")]
          ;; Send exactly what was reviewed. Holdings or balances that moved in
          ;; between (or a passkey unlock that replays this confirm later)
          ;; change the plan; show the new one instead of sending it unseen.
          (not= reviewed (fingerprint rows))
          [(save :screen :review)
           (save :reviewed (fingerprint rows))
           (save :error "The plan changed since you reviewed it. Check the amounts below and confirm again.")]
          :else [[:effects/save path (assoc (model/funding-state state)
                                            :screen :progress
                                            :error nil
                                            :run (run/new-run rows (str "vfund_" now-ms) now-ms
                                                              {:owner (model/owner state)
                                                               :source (model/source-id state)}))]
                 [:effects/run-portfolio-optimizer-vault-funding]]))))

(defn- retry-effects
  [state now-ms]
  (let [current (model/run-state state)]
    (or (send-gate state :retry)
        (when-not (current-run? state)
          [(save :error "This run was planned for another wallet or another optimizer result. Plan again.")])
        (if (and (contains? run/terminal-statuses (:status current))
                 (seq (run/retryable-rows current)))
          [(save :error nil)
           (save :run (run/retry current (str "vfund_" now-ms) now-ms))
           [:effects/run-portfolio-optimizer-vault-funding]]
          []))))

(defn portfolio-optimizer-vault-funding-command
  "Commands: :open :close :review :back :confirm :stop :retry :replan :done,
  and the key name \"Escape\" (closes; a running run keeps going)."
  ([state command]
   (portfolio-optimizer-vault-funding-command state command (.now js/Date)))
  ([state command now-ms]
   (case (coercion/normalize-keyword-like command)
     :open (if (model/vault-result? (result state)) (open-effects state) [])
     (:close :escape) [(save :open? false)]
     :review (review-effects state now-ms)
     :back (if (model/running? state) [] [(save :screen :plan) (save :error nil)])
     :confirm (confirm-effects state now-ms)
     ;; Only the flag: the runner owns the rows while a run is in flight.
     :stop (if (model/running? state)
             [[:effects/save (conj contracts/ui-vault-funding-run-path :abort-requested?) true]]
             [])
     :retry (retry-effects state now-ms)
     ;; Back to a fresh plan from re-read holdings, e.g. after a transfer
     ;; whose outcome is unknown.
     :replan (if (model/running? state)
               []
               (into [(save :run nil) (save :reviewed nil) (save :screen :plan) (save :error nil)]
                     (holdings-fetch state)))
     :done (if (model/running? state)
             [(save :open? false)]
             [[:effects/save path (assoc (model/funding-state state)
                                         :open? false :screen :plan :run nil :error nil
                                         :reviewed nil :amount-input "")]])
     [])))
