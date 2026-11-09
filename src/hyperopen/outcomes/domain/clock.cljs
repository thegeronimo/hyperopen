(ns hyperopen.outcomes.domain.clock
  "When an outcome event happens. Each template carries its own time field —
   a game's `scheduledStart`, a price contract's `time`, a rate decision's
   `scheduledDecision`, a season's `resolutionDeadline` — so the clock is chosen
   per template and then said relative to now (\"Starts in 7h 34m\")."
  (:require [clojure.string :as str]))

(def ^:private minute-ms 60000)
(def ^:private hour-ms (* 60 minute-ms))
(def ^:private two-days-ms (* 48 hour-ms))

(def live-window-ms
  "How long after a scheduled start a game reads as Live before it reads as
   awaiting its result. The feed has no end time, so this is a fixed window."
  (* 4 hour-ms))

(defn parse-time-ms
  "Parse a template time `YYYYMMDD-HHMM` (always UTC) into epoch ms."
  [value]
  (when-let [[_ yyyy mm dd hh minute]
             (and (string? value)
                  (re-matches #"(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})" (str/trim value)))]
    (js/Date.UTC (js/parseInt yyyy 10)
                 (dec (js/parseInt mm 10))
                 (js/parseInt dd 10)
                 (js/parseInt hh 10)
                 (js/parseInt minute 10))))

(defn clock-spec
  "A clock of `kind` (:starts :settles :ends :decision :resolves :listing) at a
   template time, with an optional resolution deadline. Nil without a time."
  [kind at deadline]
  (let [at-ms (if (number? at) at (parse-time-ms at))
        deadline-ms (if (number? deadline) deadline (parse-time-ms deadline))]
    (when (number? at-ms)
      (cond-> {:kind kind
               :at-ms at-ms}
        (number? deadline-ms) (assoc :deadline-ms deadline-ms)))))

(defn duration-text
  "Compact time left: 42m, 7h 34m, 12d."
  [ms]
  (let [minutes (max 1 (js/Math.floor (/ ms minute-ms)))]
    (cond
      (< minutes 60) (str minutes "m")
      (< minutes (* 48 60)) (let [hours (quot minutes 60)
                                  remainder (rem minutes 60)]
                              (if (zero? remainder)
                                (str hours "h")
                                (str hours "h " remainder "m")))
      :else (str (quot minutes (* 24 60)) "d"))))

(defn- intl-format
  [ms opts]
  (.format (js/Intl.DateTimeFormat. "en-US" (clj->js opts)) (js/Date. ms)))

(defn absolute-text
  "The viewer's local time with its zone: \"Thu, Nov 12, 2:45 PM EST\".
   `:time-zone` pins the zone (tests use UTC)."
  ([ms] (absolute-text ms nil))
  ([ms {:keys [time-zone]}]
   (when (number? ms)
     (intl-format ms (cond-> {:weekday "short"
                              :month "short"
                              :day "numeric"
                              :hour "numeric"
                              :minute "2-digit"
                              :timeZoneName "short"}
                       time-zone (assoc :timeZone time-zone))))))

(defn- local-year
  [ms time-zone]
  (intl-format ms (cond-> {:year "numeric"}
                    time-zone (assoc :timeZone time-zone))))

(defn date-text
  "A local calendar date, with the year only when it is not the current one:
   \"Nov 30\", \"Jun 30, 2027\"."
  ([ms now-ms] (date-text ms now-ms nil))
  ([ms now-ms {:keys [time-zone]}]
   (when (number? ms)
     (let [with-year? (and (number? now-ms)
                           (not= (local-year ms time-zone)
                                 (local-year now-ms time-zone)))]
       (intl-format ms (cond-> {:month "short" :day "numeric"}
                         with-year? (assoc :year "numeric")
                         time-zone (assoc :timeZone time-zone)))))))

(defn- countdown
  "\"Starts in 7h 34m\" inside two days, \"Starts Nov 12\" beyond."
  [verb until-ms at-ms now-ms opts]
  (if (< until-ms two-days-ms)
    (str verb " in " (duration-text until-ms))
    (str verb " " (date-text at-ms now-ms opts))))

(defn- upcoming-phase
  [until-ms]
  (if (< until-ms hour-ms) :soon :upcoming))

(defn clock-view
  "Describe `clock` relative to `now-ms`: {:phase :label :absolute}. Phases are
   :upcoming, :soon (under an hour), :live (a game in progress) and :awaiting
   (past its time, result not yet published)."
  ([clock now-ms] (clock-view clock now-ms nil))
  ([{:keys [kind at-ms deadline-ms]} now-ms opts]
   (when (and (keyword? kind) (number? at-ms) (number? now-ms))
     (let [until-ms (- at-ms now-ms)
           upcoming? (pos? until-ms)
           absolute (absolute-text at-ms opts)
           deadline-date (date-text deadline-ms now-ms opts)
           awaiting {:phase :awaiting
                     :label (if deadline-date
                              (str "Awaiting result · by " deadline-date)
                              "Awaiting result")
                     :absolute absolute}
           ahead (fn [label]
                   {:phase (upcoming-phase until-ms)
                    :label label
                    :absolute absolute})]
       (case kind
         :starts (cond
                   upcoming? (ahead (countdown "Starts" until-ms at-ms now-ms opts))
                   (< (- until-ms) live-window-ms) {:phase :live :label "Live" :absolute absolute}
                   :else awaiting)
         :settles (if upcoming?
                    (ahead (countdown "Settles" until-ms at-ms now-ms opts))
                    {:phase :awaiting :label "Settling" :absolute absolute})
         :ends (if upcoming?
                 (ahead (countdown "Ends" until-ms at-ms now-ms opts))
                 awaiting)
         :decision (if upcoming?
                     (ahead (countdown "Decision" until-ms at-ms now-ms opts))
                     awaiting)
         :resolves (if upcoming?
                     (ahead (str "Resolves by " (date-text at-ms now-ms opts)))
                     {:phase :awaiting
                      :label "Past deadline · awaiting result"
                      :absolute absolute})
         :listing (if upcoming?
                    (ahead (str "If listed by " (date-text at-ms now-ms opts)))
                    awaiting)
         nil)))))
