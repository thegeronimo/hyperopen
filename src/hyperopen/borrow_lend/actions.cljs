(ns hyperopen.borrow-lend.actions
  "Earn (HyperCore borrow/lend) actions. Each action is a pure function of app
  state that returns effect vectors; validation for a submit happens here, in
  one place, before any signing effect is emitted."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.borrow-lend.account :as bl-account]
            [hyperopen.utils.parse :as parse-utils]))

(def earn-route "/earn")

(def ^:private valid-tabs #{:markets :positions :history})

(def ^:private valid-modal-kinds #{:supply :withdraw :borrow :repay})

(defn- normalize-route-path
  [path]
  (let [path* (-> (if (string? path) path (str (or path "")))
                  (str/split #"[?#]" 2)
                  first
                  (or "")
                  str/trim
                  str/lower-case)]
    (loop [p path*]
      (if (and (> (count p) 1) (str/ends-with? p "/"))
        (recur (subs p 0 (dec (count p))))
        p))))

(defn earn-route?
  [path]
  (= earn-route (normalize-route-path path)))

(defn- keywordish
  [value]
  (cond (keyword? value) value
        (string? value) (keyword (str/lower-case (str/trim value)))
        :else nil))

(defn load-borrow-lend
  "Loads pool reserves and, when an account is in scope, its positions and
  30-day interest history."
  [state]
  (let [address (bl-account/account-address state)
        stale? (not= (account-context/normalize-address address)
                     (get-in state [:borrow-lend :user-address]))]
    (into [(if stale?
             ;; A different account: drop the previous one's positions so its
             ;; Earn balance never shows under the new account.
             [:effects/save-many [[[:borrow-lend :requested-address] address]
                                  [[:borrow-lend :user-state] nil]
                                  [[:borrow-lend :user-address] nil]
                                  [[:borrow-lend :total-supplied-usd] 0]]]
             [:effects/save [:borrow-lend :requested-address] address])
           [:effects/api-fetch-borrow-lend-reserves]]
          (when (seq address)
            [[:effects/api-fetch-borrow-lend-user-state address]
             [:effects/api-fetch-borrow-lend-interest address]]))))

(defn- portfolio-overview-route?
  "The portfolio page shows the Earn balance; its optimizer sub-routes do not."
  [path]
  (let [p (normalize-route-path path)]
    (or (= "/portfolio" p)
        (and (str/starts-with? p "/portfolio/")
             (not (str/starts-with? p "/portfolio/optimize"))))))

(defn load-borrow-lend-route
  "Loads Earn data on the routes that show it: /earn and the portfolio page."
  [state path]
  (if (or (earn-route? path) (portfolio-overview-route? path))
    (load-borrow-lend state)
    []))

(defn set-borrow-lend-tab
  [_state tab]
  (let [tab* (keywordish tab)]
    [[:effects/save [:borrow-lend-ui :active-tab]
      (if (contains? valid-tabs tab*) tab* :markets)]]))

(defn open-borrow-lend-modal
  [state kind token]
  (let [kind* (keywordish kind)
        token* (bl-account/token-index token)
        kind** (if (and (= :borrow kind*) (bl-account/portfolio-margin? state))
                 :repay
                 kind*)]
    (if (and (contains? valid-modal-kinds kind**) (some? token*))
      [[:effects/save [:borrow-lend-ui :modal]
        {:open? true
         :kind kind**
         :token token*
         :amount ""
         :max? false
         :submitting? false
         :error nil}]]
      [])))

(defn close-borrow-lend-modal
  [_state]
  [[:effects/save-many [[[:borrow-lend-ui :modal :open?] false]
                        [[:borrow-lend-ui :modal :submitting?] false]
                        [[:borrow-lend-ui :modal :error] nil]]]])

(defn set-borrow-lend-modal-kind
  [state kind]
  (let [{:keys [token]} (get-in state [:borrow-lend-ui :modal])]
    (open-borrow-lend-modal state kind token)))

(defn set-borrow-lend-amount
  [_state value]
  [[:effects/save-many [[[:borrow-lend-ui :modal :amount] (str (or value ""))]
                        [[:borrow-lend-ui :modal :max?] false]
                        [[:borrow-lend-ui :modal :error] nil]]]])

(defn set-borrow-lend-amount-fraction
  "Fills the amount with `fraction` (0..1] of the modal's maximum. A fraction
  of 1 on withdraw or repay sends `amount: null`, which the exchange treats as
  the full position, so dust from accrued interest is not left behind."
  [state fraction]
  (let [{:keys [kind token]} (get-in state [:borrow-lend-ui :modal])
        max-amount (bl-account/modal-max-amount state kind token)
        f (if (number? fraction) (min 1 (max 0 fraction)) 1)
        full? (and (= 1 f) (contains? #{:withdraw :repay} kind))]
    (if (and (number? max-amount) (pos? max-amount))
      [[:effects/save-many [[[:borrow-lend-ui :modal :amount]
                             (bl-account/format-amount-input state token (* f max-amount))]
                            [[:borrow-lend-ui :modal :max?] full?]
                            [[:borrow-lend-ui :modal :error] nil]]]]
      [])))

(defn- guard-error
  [message]
  [[:effects/save [:borrow-lend-ui :modal :error] message]])

(defn- validation-error
  [state {:keys [kind token amount max?]}]
  (let [parsed (parse-utils/parse-localized-decimal amount (get-in state [:ui :locale]))
        max-amount (bl-account/modal-max-amount state kind token)]
    (cond
      (seq (account-context/mutations-blocked-message state))
      (account-context/mutations-blocked-message state)

      (nil? (account-context/owner-address state))
      "Connect your wallet first."

      (and (= :borrow kind) (bl-account/portfolio-margin? state))
      "Portfolio margin accounts borrow automatically. Manual borrow is off."

      max? nil

      (or (nil? parsed) (not (pos? parsed)))
      "Enter an amount greater than zero."

      (and (number? max-amount) (> parsed (* max-amount 1.000001)))
      (case kind
        :borrow "That is more than you can borrow against your collateral."
        :repay "That is more than you owe."
        :withdraw "That is more than you have supplied."
        "That is more than your available balance.")

      :else nil)))

(defn submit-borrow-lend
  [state]
  (let [{:keys [kind token amount max?] :as modal} (get-in state [:borrow-lend-ui :modal])]
    (if-let [message (or (when-not (and (contains? valid-modal-kinds kind) (some? token))
                           "Choose an asset first.")
                         (validation-error state modal))]
      (guard-error message)
      [[:effects/save-many [[[:borrow-lend-ui :modal :submitting?] true]
                            [[:borrow-lend-ui :modal :error] nil]]]
       [:effects/api-submit-borrow-lend
        {:kind kind
         :action {:type "borrowLend"
                  :operation (name kind)
                  :token token
                  :amount (when-not max?
                            (bl-account/wire-amount state token amount))}}]])))
