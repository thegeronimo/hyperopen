(ns hyperopen.views.staking.unstaking-panel
  "Renders HYPE that is sitting in the 7-day staking -> spot queue.

  This is deliberately a block rather than another key/value row: the amount is
  immobilised, and rendering it identically to the spendable rows above it is
  what made users believe the HYPE was available. The block is an always-present
  slot so the panel keeps a stable shape whether or not anything is in flight."
  (:require [hyperopen.staking.unstaking :as unstaking]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.staking.shared :as shared]))

(def ^:private block-title
  "Unstaking to Spot Balance")

(def ^:private day-ms
  86400000)

(defn- locked-pill []
  [:span {:class ["inline-flex"
                  "items-center"
                  "rounded-full"
                  "bg-ho-warn/15"
                  "px-2"
                  "py-0.5"
                  "text-xs"
                  "font-normal"
                  "uppercase"
                  "tracking-wide"
                  "leading-4"
                  "text-ho-warn"]
          :data-role "staking-unstaking-locked-pill"}
   "Locked"])

(defn- empty-block []
  [:div {:class ["flex" "items-start" "justify-between" "gap-3" "text-xs"]}
   [:span {:class ["text-ho-text-secondary" "leading-[15px]"]}
    block-title]
   [:span {:class ["num" "text-ho-text-secondary" "font-normal" "leading-[15px]"]}
    "None"]])

(defn- arrival-sentence
  [prefix arrives-at-ms remaining-ms]
  (when-let [stamp (fmt/format-local-date-time arrives-at-ms)]
    (if (and (number? remaining-ms) (pos? remaining-ms))
      (str prefix " " stamp " · about " (fmt/format-duration-approx remaining-ms) " left")
      (str prefix " " stamp " · arriving now"))))

(defn- elapsed-days-label
  [remaining-ms]
  (let [elapsed (-> (- unstaking/queue-duration-ms (or remaining-ms 0))
                    (/ day-ms)
                    js/Math.floor
                    (max 0)
                    (min 7))]
    (str elapsed " of 7 days elapsed")))

(defn- progress-bar
  [entry]
  (let [pct (* 100 (or (unstaking/queue-progress-fraction entry) 0))]
    [:div {:class ["space-y-1"]}
     [:div {:class ["h-1.5" "w-full" "overflow-hidden" "rounded-full" "bg-ho-surface"]
            :role "progressbar"
            :aria-label "Unstaking queue progress"
            :aria-valuemin "0"
            :aria-valuemax "100"
            :aria-valuenow (str (js/Math.round pct))
            :data-role "staking-unstaking-progress"}
      [:div {:class ["h-full" "rounded-full" "bg-ho-warn"]
             :style {:width (str pct "%")}}]]
     [:div {:class ["text-xs" "leading-4" "text-ho-text-secondary"]}
      (elapsed-days-label (:remaining-ms entry))]]))

(defn- primary-line
  [text]
  [:div {:class ["text-xs" "leading-4" "text-ho-text"]
         :data-role "staking-unstaking-arrival"}
   text])

(defn- secondary-line
  [text]
  [:div {:class ["text-xs" "leading-4" "text-ho-text-secondary"]}
   text])

(defn- queue-size-phrase
  [entry-count]
  (if (= 1 entry-count)
    "1 transfer in the queue"
    (str entry-count " transfers in the queue")))

(defn- known-timing-lines
  [{:keys [entries next-arrival-ms] :as model}]
  (let [next-entry (first (sort-by :arrives-at-ms entries))
        entry-count (or (:count model) (count entries))
        multiple? (> entry-count 1)
        last-stamp (when multiple?
                     (fmt/format-local-date-time (apply max (map :arrives-at-ms entries))))]
    [:div {:class ["space-y-1.5"]}
     (progress-bar next-entry)
     (primary-line (or (arrival-sentence (if multiple? "Next arrives" "Arrives")
                                         (or (:arrives-at-ms next-entry) next-arrival-ms)
                                         (:remaining-ms next-entry))
                       ""))
     (secondary-line (str (queue-size-phrase entry-count)
                          (when last-stamp
                            (str " · last arrives " last-stamp))))]))

(defn- estimated-timing-lines
  [{:keys [next-arrival-ms] :as model}]
  (let [entry-count (:count model)]
    [:div {:class ["space-y-1.5"]}
     (primary-line (str "Estimated to arrive around "
                        (or (fmt/format-local-date-time next-arrival-ms)
                            "an unknown time")))
     (secondary-line (str (when (and entry-count (pos? entry-count))
                            (str (queue-size-phrase entry-count) ". "))
                          "Your transfer history does not fully match the pending total, so treat this time as approximate."))]))

(defn- unknown-timing-lines
  [{:keys [count]}]
  [:div {:class ["space-y-1.5"]}
   (primary-line "Transfers to your spot balance take 7 days.")
   (secondary-line (str (when (and count (pos? count))
                          (str (queue-size-phrase count) ". "))
                        "The exact arrival time is not available right now."))])

(defn- timing-lines
  [{:keys [timing] :as model}]
  (case timing
    :known (known-timing-lines model)
    :estimated (estimated-timing-lines model)
    (unknown-timing-lines model)))

(defn- in-flight-block
  [{:keys [amount] :as model}]
  [:div {:class ["rounded-[10px]"
                 "border"
                 "border-ho-warn/40"
                 "bg-ho-warn/5"
                 "px-3"
                 "py-2.5"
                 "space-y-2"]}
   [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
    [:span {:class ["text-xs" "leading-[15px]" "text-ho-text-secondary"]}
     block-title]
    (locked-pill)]
   [:div {:class ["num" "text-base" "leading-5" "text-ho-text"]
          :data-role "staking-unstaking-amount"}
    (shared/format-balance-hype amount)]
   (secondary-line "Not tradable or transferable until it arrives.")
   (timing-lines model)])

(defn unstaking-block
  "Always-present slot describing the staking -> spot queue."
  [model]
  [:div {:data-role "staking-unstaking-block"}
   (if (:in-flight? model)
     (in-flight-block model)
     (empty-block))])
