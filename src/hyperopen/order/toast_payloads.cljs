(ns hyperopen.order.toast-payloads
  (:require [clojure.string :as str]
            [hyperopen.domain.market.instrument :as instrument]
            [hyperopen.utils.formatting :as fmt]))

(def ^:private margin-ntli-scale
  1000000)

(def ^:private margin-amount-decimals
  2)

(def ^:private margin-fallback-message
  "Margin updated.")

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- cancel-success-toast-message
  [success-count]
  (if (= 1 success-count)
    "Order canceled."
    (str success-count " orders canceled.")))

(defn cancel-success-toast-payload
  [success-count]
  (let [headline (if (= 1 success-count)
                   "Order canceled"
                   (str success-count " orders canceled"))]
    {:toast-surface :order-canceled
     :headline headline
     :subline "Open orders updated"
     :message (cancel-success-toast-message success-count)}))

(defn twap-cancel-success-toast-payload
  []
  {:toast-surface :order-canceled
   :headline "TWAP terminated"
   :subline "Open orders updated"
   :message "TWAP terminated."})

(defn- margin-side-label
  [side]
  (case side
    :long "long"
    :short "short"
    nil))

(defn- margin-position-label
  [{:keys [coin side]}]
  (let [base (or (non-blank-text (instrument/base-symbol-from-value coin))
                 (non-blank-text coin))
        side-label (margin-side-label side)]
    (cond
      (and base side-label) (str base " " side-label)
      base base
      :else nil)))

(defn- margin-amount-label
  [ntli]
  (when (and (number? ntli)
             (not (zero? ntli)))
    (str (fmt/format-fixed-number (/ (js/Math.abs ntli) margin-ntli-scale)
                                  margin-amount-decimals)
         " USDC")))

(defn- margin-venue-label
  "Named-DEX positions can share a coin with a main-DEX position, so the venue
  is part of identifying which position moved."
  [{:keys [coin dex]}]
  (or (non-blank-text dex)
      (let [text (non-blank-text coin)]
        (when (and text (str/includes? text ":"))
          (non-blank-text (first (str/split text #":" 2)))))))

(defn- margin-headline
  "Lead with the position. The headline is truncated to one line, so putting the
  amount first would clip the position name on large amounts — exactly the part
  that tells a stack of these toasts apart."
  [amount-label position-label removed?]
  (let [verb (if removed? "removed" "added")]
    (cond
      (and position-label amount-label)
      (str position-label ": " verb " " amount-label)

      amount-label
      (str (str/capitalize verb) " " amount-label " of margin")

      position-label
      (str position-label ": margin updated")

      :else
      nil)))

(defn position-margin-success-toast-payload
  "Name the position a margin update landed on. Batch top-up and auto top-up
  submit one request per position, so several of these toasts can stack — an
  unqualified \"Margin updated.\" cannot be matched back to a row. Reads the
  submitted request rather than the open modal, which the background flows
  never populate and which the user can still edit mid-flight."
  [request]
  (let [ntli (get-in request [:action :ntli])
        position (:position request)
        headline (margin-headline (margin-amount-label ntli)
                                  (margin-position-label position)
                                  (and (number? ntli) (neg? ntli)))
        venue (margin-venue-label position)]
    (if headline
      {:headline headline
       :subline (if venue
                  (str "Isolated margin on " venue)
                  "Isolated margin")
       :message (str headline ".")}
      margin-fallback-message)))

(defn position-margin-failure-toast-payload
  "Failure sibling of [[position-margin-success-toast-payload]]: the batch and
  auto top-up flows can reject several positions in one pass, so each error
  toast names its position. The exchange error rides in :detail, which the
  toast card wraps instead of truncating."
  [request error-text]
  (let [position-label (margin-position-label (:position request))
        venue (margin-venue-label (:position request))
        error* (non-blank-text error-text)]
    (if position-label
      (cond-> {:headline (str position-label ": margin update failed")
               :subline (if venue
                          (str "Isolated margin on " venue)
                          "Isolated margin")
               :message (str position-label " margin update failed"
                             (when error* (str ": " error*)))}
        error* (assoc :detail error*))
      (str "Margin update failed" (when error* (str ": " error*))))))
