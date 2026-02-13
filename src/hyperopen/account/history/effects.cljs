(ns hyperopen.account.history.effects
  (:require [clojure.string :as str]
            [hyperopen.api.default :as api]
            [hyperopen.account.history.actions :as account-history-actions]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.platform :as platform]))

(defn- format-funding-history-time [time-ms]
  (let [d (js/Date. time-ms)
        pad2 (fn [v] (.padStart (str v) 2 "0"))]
    (str (inc (.getMonth d))
         "/"
         (.getDate d)
         "/"
         (.getFullYear d)
         " - "
         (pad2 (.getHours d))
         ":"
         (pad2 (.getMinutes d))
         ":"
         (pad2 (.getSeconds d)))))

(defn- funding-position-side-label
  [position-side]
  (case position-side
    :long "Long"
    :short "Short"
    :flat "Flat"
    "Flat"))

(defn- csv-escape
  [value]
  (let [text (str (or value ""))]
    (if (or (str/includes? text ",")
            (str/includes? text "\"")
            (str/includes? text "\n"))
      (str "\""
           (str/replace text "\"" "\"\"")
           "\"")
      text)))

(defn- format-funding-history-size
  [row]
  (let [size (if (number? (:size-raw row)) (:size-raw row) 0)
        coin (or (:coin row) "-")]
    (str (.toLocaleString (js/Number. size)
                          "en-US"
                          #js {:minimumFractionDigits 3
                               :maximumFractionDigits 6})
         " "
         coin)))

(defn- format-funding-history-payment
  [row]
  (let [payment (if (number? (:payment-usdc-raw row)) (:payment-usdc-raw row) 0)]
    (str (.toLocaleString (js/Number. payment)
                          "en-US"
                          #js {:minimumFractionDigits 4
                               :maximumFractionDigits 6})
         " USDC")))

(defn- format-funding-history-rate
  [row]
  (let [rate (if (number? (:funding-rate-raw row)) (:funding-rate-raw row) 0)]
    (str (.toFixed (* 100 rate) 4) "%")))

(defn- funding-row->csv-line
  [row]
  (str/join ","
            (map csv-escape
                 [(format-funding-history-time (:time-ms row))
                  (:coin row)
                  (format-funding-history-size row)
                  (funding-position-side-label (:position-side row))
                  (format-funding-history-payment row)
                  (format-funding-history-rate row)])))

(defn- merge-and-project-funding-history
  [state rows]
  (let [filters (account-history-actions/funding-history-filters state)
        merged (funding-history/merge-funding-history-rows (get-in state [:orders :fundings-raw] [])
                                                           rows)
        projected (funding-history/filter-funding-history-rows merged filters)]
    (-> state
        (assoc-in [:account-info :funding-history :filters] filters)
        (assoc-in [:orders :fundings-raw] merged)
        (assoc-in [:orders :fundings] projected))))

(defn fetch-and-merge-funding-history!
  [store address opts]
  (when address
    (let [filters (account-history-actions/funding-history-filters @store)
          request-opts (merge {:priority :high}
                              filters
                              (or opts {}))]
      (-> (api/request-user-funding-history! address request-opts)
          (.then
           (fn [rows]
             (swap! store
                    (fn [state]
                      (if (= address (get-in state [:wallet :address]))
                        (-> (merge-and-project-funding-history state rows)
                            (assoc-in [:account-info :funding-history :error] nil))
                        state)))))
          (.catch
           (fn [err]
             (swap! store
                    (fn [state]
                      (if (= address (get-in state [:wallet :address]))
                        (assoc-in state [:account-info :funding-history :error] (str err))
                        state)))))))))

(defn api-fetch-user-funding-history-effect
  [_ store request-id]
  (let [address (get-in @store [:wallet :address])
        filters (account-history-actions/funding-history-filters @store)
        opts (merge {:priority :high}
                    filters)]
    (if-not address
      (swap! store
             (fn [state]
               (if (= request-id (account-history-actions/funding-history-request-id state))
                 (-> state
                     (assoc-in [:account-info :funding-history :loading?] false)
                     (assoc-in [:orders :fundings-raw] [])
                     (assoc-in [:orders :fundings] []))
                 state)))
      (-> (api/request-user-funding-history! address opts)
          (.then (fn [rows]
                   (swap! store
                          (fn [state]
                            (if (= request-id (account-history-actions/funding-history-request-id state))
                              (-> (merge-and-project-funding-history state rows)
                                  (assoc-in [:account-info :funding-history :loading?] false)
                                  (assoc-in [:account-info :funding-history :error] nil))
                              state)))))
          (.catch (fn [err]
                    (swap! store
                           (fn [state]
                             (if (= request-id (account-history-actions/funding-history-request-id state))
                               (-> state
                                   (assoc-in [:account-info :funding-history :loading?] false)
                                   (assoc-in [:account-info :funding-history :error] (str err)))
                               state)))))))))

(defn api-fetch-historical-orders-effect
  [_ store request-id]
  (let [address (get-in @store [:wallet :address])]
    (if-not address
      (swap! store
             (fn [state]
               (if (= request-id (account-history-actions/order-history-request-id state))
                 (-> state
                     (assoc-in [:account-info :order-history :loading?] false)
                     (assoc-in [:account-info :order-history :error] nil)
                     (assoc-in [:orders :order-history] []))
                 state)))
      (-> (api/request-historical-orders! address {:priority :high})
          (.then (fn [rows]
                   (swap! store
                          (fn [state]
                            (if (= request-id (account-history-actions/order-history-request-id state))
                              (-> state
                                  (assoc-in [:account-info :order-history :loading?] false)
                                  (assoc-in [:account-info :order-history :error] nil)
                                  (assoc-in [:orders :order-history] (vec (or rows []))))
                              state)))))
          (.catch (fn [err]
                    (swap! store
                           (fn [state]
                             (if (= request-id (account-history-actions/order-history-request-id state))
                               (-> state
                                   (assoc-in [:account-info :order-history :loading?] false)
                                   (assoc-in [:account-info :order-history :error] (str err)))
                               state)))))))))

(defn export-funding-history-csv-effect
  [_ _ rows]
  (let [rows* (vec (or rows []))
        header "Time,Coin,Size,Position Side,Payment,Rate"
        body (map funding-row->csv-line rows*)
        csv (str/join "\n" (cons header body))]
    (when (and (exists? js/document)
               (exists? js/URL))
      (let [blob (js/Blob. #js [csv] #js {:type "text/csv;charset=utf-8"})
            url (.createObjectURL js/URL blob)
            link (.createElement js/document "a")
            filename (str "funding-history-" (platform/now-ms) ".csv")]
        (set! (.-href link) url)
        (set! (.-download link) filename)
        (.appendChild (.-body js/document) link)
        (.click link)
        (.removeChild (.-body js/document) link)
        (.revokeObjectURL js/URL url)))))
