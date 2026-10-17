(ns hyperopen.trading.orderbook-price-pick
  "Setting the order form's limit price from a clicked order book level."
  (:require [clojure.string :as str]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-ownership :as ownership]
            [hyperopen.trading.order-form-transitions :as transitions]))

(defn- canonical-price
  "The level's raw price as the venue sent it, when it is a positive plain decimal, with
   trailing fraction zeros dropped (`82342.0` -> `82342`).

   The raw price is written to the form directly rather than through the typed-input
   path: that path reads the text in the user's locale, where `1.234` can mean 1234."
  [price]
  (let [text (some-> price str str/trim)]
    (when (and (seq text)
               (re-matches #"^\d+(?:\.\d+)?$" text)
               (pos? (js/parseFloat text)))
      (if (str/includes? text ".")
        (-> text (str/replace #"0+$" "") (str/replace #"\.$" ""))
        text))))

(defn set-order-price-from-orderbook
  "Transition that sets the limit price to `price`, switching a Market form to Limit
   first so the price applies. Other entry modes keep their order type. Returns nil
   for a price that is not a positive plain decimal."
  [state price]
  (when-let [px (canonical-price price)]
    (let [switched (when (= :market (:entry-mode (trading/order-form-draft state)))
                     (transitions/select-entry-mode state :limit))
          state* (cond-> state switched (merge switched))
          form (assoc (trading/order-form-draft state*) :price px)
          next-form (->> form
                         (transitions/reconcile-size-after-context-change state*)
                         (transitions/backfill-tpsl-triggers-from-offset-inputs state*))]
      (ownership/enforce-field-ownership
       state*
       {:order-form next-form
        :order-form-runtime (assoc (trading/order-form-runtime-state state*) :error nil)}))))
