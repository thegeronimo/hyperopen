(ns hyperopen.api.errors
  (:require [clojure.string :as str]))

(def categories
  #{:domain :validation :transport :protocol :unexpected})

(defn- http-status
  [err]
  (cond
    (map? err)
    (or (:status err)
        (:http-status err))

    :else
    nil))

(defn- status->category
  [status]
  (cond
    (not (number? status)) nil
    (= status 400) :validation
    (= status 422) :validation
    (<= 400 status 499) :protocol
    (<= 500 status 599) :transport
    :else :unexpected))

(defn- transport-message?
  [text]
  (let [text* (some-> text str/lower-case)]
    (boolean
     (or (str/includes? text* "network")
         (str/includes? text* "fetch")
         (str/includes? text* "timeout")
         (str/includes? text* "offline")))))

(defn classify-error
  [err]
  (let [explicit (when (map? err) (:category err))
        explicit* (when (contains? categories explicit) explicit)
        from-status (status->category (http-status err))
        msg (str err)]
    (or explicit*
        from-status
        (when (transport-message? msg) :transport)
        :unexpected)))

(defn error-message
  [err]
  (let [msg (cond
              (map? err) (or (:message err)
                             (:error err))
              :else nil)]
    (if (seq (str msg))
      (str msg)
      (str err))))

(defn normalize-error
  [err]
  {:category (classify-error err)
   :message (error-message err)
   :cause err})
