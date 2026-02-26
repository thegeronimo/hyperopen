(ns hyperopen.account.history.effects-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [goog.object :as gobj]
            [hyperopen.account.history.actions :as history-actions]
            [hyperopen.account.history.effects :as history-effects]
            [hyperopen.api.default :as api]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.views.account-info-view :as account-info-view]))

(defn- apply-save-many-effect!
  [store effect]
  (doseq [[path value] (second effect)]
    (swap! store assoc-in path value)))

(defn- collect-strings
  [node]
  (cond
    (string? node)
    [node]

    (vector? node)
    (let [children (if (map? (second node))
                     (nnext node)
                     (next node))]
      (mapcat collect-strings children))

    (seq? node)
    (mapcat collect-strings node)

    :else
    []))

(defn- has-own?
  [obj key]
  (.call (.-hasOwnProperty js/Object.prototype) obj key))

(defn- history-filters
  []
  {:coin-set #{}
   :start-time-ms 0
   :end-time-ms 2000000000000})

(defn- base-history-state
  ([]
   (base-history-state "0xabc"))
  ([address]
   (let [filters (history-filters)]
     {:wallet {:address address}
      :account-info {:selected-tab :balances
                     :funding-history {:filters filters
                                       :draft-filters filters
                                       :sort {:column "Time"
                                              :direction :desc}
                                       :filter-open? false
                                       :page-size 50
                                       :page 1
                                       :page-input "1"
                                       :loading? true
                                       :error "stale-funding-error"
                                       :request-id 0}
                     :order-history {:sort {:column "Time"
                                            :direction :desc}
                                     :status-filter :all
                                     :filter-open? false
                                     :page-size 50
                                     :page 1
                                     :page-input "1"
                                     :loading? true
                                     :error "stale-order-error"
                                     :request-id 0}}
      :orders {:fundings-raw []
               :fundings []
               :order-history []}})))

(defn- info-funding-row
  [time-ms coin usdc signed-size funding-rate]
  (funding-history/normalize-info-funding-row
   {:time time-ms
    :delta {:type "funding"
            :coin coin
            :usdc usdc
            :szi signed-size
            :fundingRate funding-rate}}))

(deftest funding-history-flow-select-fetch-and-render-shows-rows-test
  (async done
    (let [filters {:coin-set #{}
                   :start-time-ms 0
                   :end-time-ms 2000000000000}
          store (atom {:wallet {:address "0xabc"}
                       :account-info {:selected-tab :balances
                                      :loading false
                                      :error nil
                                      :funding-history {:filters filters
                                                        :draft-filters filters
                                                        :sort {:column "Time"
                                                               :direction :desc}
                                                        :filter-open? false
                                                        :page-size 50
                                                        :page 1
                                                        :page-input "1"
                                                        :loading? false
                                                        :error nil
                                                        :request-id 0}}
                       :account {:mode :classic
                                 :abstraction-raw nil}
                       :asset-selector {:market-by-key {}}
                       :orders {:open-orders []
                                :open-orders-snapshot []
                                :open-orders-snapshot-by-dex {}
                                :fills []
                                :fundings-raw []
                                :fundings []
                                :order-history []
                                :ledger []}
                       :webdata2 {}})
          effects (history-actions/select-account-info-tab @store :funding-history)
          save-effect (first effects)
          fetch-effect (second effects)
          request-id (second fetch-effect)
          funding-row (funding-history/normalize-info-funding-row
                       {:time 1700000000000
                        :delta {:type "funding"
                                :coin "HYPE"
                                :usdc "0.3500"
                                :szi "25.0"
                                :fundingRate "0.0002"}})]
      (apply-save-many-effect! store save-effect)
      (with-redefs [api/request-user-funding-history! (fn
                                                        ([_address]
                                                         (js/Promise.resolve [funding-row]))
                                                        ([_address _opts]
                                                         (js/Promise.resolve [funding-row])))]
        (-> (history-effects/api-fetch-user-funding-history-effect nil store request-id)
            (.then (fn [_]
                     (let [panel (account-info-view/account-info-panel @store)
                           strings (set (collect-strings panel))]
                       (is (= :funding-history (get-in @store [:account-info :selected-tab])))
                       (is (= 1 (count (get-in @store [:orders :fundings]))))
                       (is (= "HYPE" (get-in @store [:orders :fundings 0 :coin])))
                       (is (contains? strings "HYPE"))
                       (is (contains? strings "Long"))
                       (is (not (contains? strings "No funding history")))
                       (done))))
            (.catch (fn [err]
                      (is false (str "Unexpected error: " err))
                      (done))))))))

(deftest api-fetch-user-funding-history-effect-no-address-clears-only-current-request-test
  (let [row (info-funding-row 1700000000000 "BTC" "0.1000" "10" "0.0001")
        current-store (atom (-> (base-history-state nil)
                                (assoc-in [:account-info :funding-history :request-id] 5)
                                (assoc-in [:orders :fundings-raw] [row])
                                (assoc-in [:orders :fundings] [row])))
        stale-store (atom (-> (base-history-state nil)
                              (assoc-in [:account-info :funding-history :request-id] 6)
                              (assoc-in [:orders :fundings-raw] [row])
                              (assoc-in [:orders :fundings] [row])))
        stale-before @stale-store]
    (history-effects/api-fetch-user-funding-history-effect nil current-store 5)
    (is (false? (get-in @current-store [:account-info :funding-history :loading?])))
    (is (= [] (get-in @current-store [:orders :fundings-raw])))
    (is (= [] (get-in @current-store [:orders :fundings])))
    (history-effects/api-fetch-user-funding-history-effect nil stale-store 5)
    (is (= stale-before @stale-store))))

(deftest api-fetch-user-funding-history-effect-success-applies-only-current-request-test
  (async done
    (let [existing-row (info-funding-row 1700000000000 "ETH" "0.0500" "3" "0.0001")
          incoming-row (info-funding-row 1700003600000 "BTC" "-0.1250" "-10" "-0.0003")
          filters {:coin-set #{"BTC"}
                   :start-time-ms 0
                   :end-time-ms 2000000000000}
          calls (atom [])
          current-store (atom (-> (base-history-state "0xabc")
                                  (assoc-in [:account-info :funding-history :request-id] 9)
                                  (assoc-in [:account-info :funding-history :filters] filters)
                                  (assoc-in [:orders :fundings-raw] [existing-row])))
          stale-store (atom (-> (base-history-state "0xabc")
                                (assoc-in [:account-info :funding-history :request-id] 10)
                                (assoc-in [:account-info :funding-history :filters] filters)
                                (assoc-in [:orders :fundings-raw] [existing-row])))
          stale-before @stale-store]
      (with-redefs [api/request-user-funding-history! (fn
                                                        ([_address]
                                                         (js/Promise.resolve [incoming-row]))
                                                        ([_address opts]
                                                         (swap! calls conj [_address opts])
                                                         (js/Promise.resolve [incoming-row])))]
        (-> (js/Promise.all
             #js [(history-effects/api-fetch-user-funding-history-effect nil current-store 9)
                  (history-effects/api-fetch-user-funding-history-effect nil stale-store 9)])
            (.then (fn [_]
                     (is (= 2 (count @calls)))
                     (is (= "0xabc" (first (first @calls))))
                     (is (= {:priority :high
                             :coin-set #{"BTC"}
                             :start-time-ms 0
                             :end-time-ms 2000000000000}
                            (second (first @calls))))
                     (is (false? (get-in @current-store [:account-info :funding-history :loading?])))
                     (is (nil? (get-in @current-store [:account-info :funding-history :error])))
                     (is (= ["BTC"]
                            (mapv :coin (get-in @current-store [:orders :fundings]))))
                     (is (= ["BTC" "ETH"]
                            (mapv :coin (get-in @current-store [:orders :fundings-raw]))))
                     (is (= stale-before @stale-store))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected error: " err))
                      (done))))))))

(deftest api-fetch-user-funding-history-effect-error-applies-only-current-request-test
  (async done
    (let [current-store (atom (-> (base-history-state "0xabc")
                                  (assoc-in [:account-info :funding-history :request-id] 11)))
          stale-store (atom (-> (base-history-state "0xabc")
                                (assoc-in [:account-info :funding-history :request-id] 12)))
          stale-before @stale-store]
      (with-redefs [api/request-user-funding-history! (fn
                                                        ([_address]
                                                         (js/Promise.reject (js/Error. "funding-boom")))
                                                        ([_address _opts]
                                                         (js/Promise.reject (js/Error. "funding-boom"))))]
        (-> (js/Promise.all
             #js [(history-effects/api-fetch-user-funding-history-effect nil current-store 11)
                  (history-effects/api-fetch-user-funding-history-effect nil stale-store 11)])
            (.then (fn [_]
                     (is (false? (get-in @current-store [:account-info :funding-history :loading?])))
                     (is (str/includes?
                          (get-in @current-store [:account-info :funding-history :error])
                          "funding-boom"))
                     (is (= stale-before @stale-store))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected error: " err))
                      (done))))))))

(deftest fetch-and-merge-funding-history-no-address-is-noop-test
  (let [store (atom (base-history-state nil))
        calls (atom 0)]
    (with-redefs [api/request-user-funding-history! (fn
                                                      ([_address]
                                                       (swap! calls inc)
                                                       (js/Promise.resolve []))
                                                      ([_address _opts]
                                                       (swap! calls inc)
                                                       (js/Promise.resolve [])))]
      (is (nil? (history-effects/fetch-and-merge-funding-history! store nil nil)))
      (is (= 0 @calls)))))

(deftest fetch-and-merge-funding-history-success-merges-and-projects-current-address-only-test
  (async done
    (let [existing-row (info-funding-row 1700000000000 "ETH" "0.0500" "3" "0.0001")
          incoming-row (info-funding-row 1700003600000 "BTC" "-0.1250" "-10" "-0.0003")
          filters {:coin-set #{"BTC"}
                   :start-time-ms 0
                   :end-time-ms 2000000000000}
          calls (atom [])
          current-store (atom (-> (base-history-state "0xabc")
                                  (assoc-in [:account-info :funding-history :filters] filters)
                                  (assoc-in [:orders :fundings-raw] [existing-row])))
          stale-store (atom (-> (base-history-state "0xdef")
                                (assoc-in [:account-info :funding-history :filters] filters)
                                (assoc-in [:orders :fundings-raw] [existing-row])))
          stale-before @stale-store]
      (with-redefs [api/request-user-funding-history! (fn
                                                        ([_address]
                                                         (js/Promise.resolve [incoming-row]))
                                                        ([_address opts]
                                                         (swap! calls conj [_address opts])
                                                         (js/Promise.resolve [incoming-row])))]
        (-> (js/Promise.all
             #js [(history-effects/fetch-and-merge-funding-history! current-store "0xabc" {:priority :low
                                                                                            :tag :current})
                  (history-effects/fetch-and-merge-funding-history! stale-store "0xabc" {:tag :stale})])
            (.then (fn [_]
                     (is (= 2 (count @calls)))
                     (is (= {:priority :low
                             :coin-set #{"BTC"}
                             :start-time-ms 0
                             :end-time-ms 2000000000000
                             :tag :current}
                            (second (first @calls))))
                     (is (nil? (get-in @current-store [:account-info :funding-history :error])))
                     (is (= ["BTC"]
                            (mapv :coin (get-in @current-store [:orders :fundings]))))
                     (is (= ["BTC" "ETH"]
                            (mapv :coin (get-in @current-store [:orders :fundings-raw]))))
                     (is (= stale-before @stale-store))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected error: " err))
                      (done))))))))

(deftest fetch-and-merge-funding-history-error-sets-current-address-error-only-test
  (async done
    (let [current-store (atom (base-history-state "0xabc"))
          stale-store (atom (base-history-state "0xdef"))
          stale-before @stale-store]
      (with-redefs [api/request-user-funding-history! (fn
                                                        ([_address]
                                                         (js/Promise.reject (js/Error. "merge-failure")))
                                                        ([_address _opts]
                                                         (js/Promise.reject (js/Error. "merge-failure"))))]
        (-> (js/Promise.all
             #js [(history-effects/fetch-and-merge-funding-history! current-store "0xabc" nil)
                  (history-effects/fetch-and-merge-funding-history! stale-store "0xabc" nil)])
            (.then (fn [_]
                     (is (str/includes?
                          (get-in @current-store [:account-info :funding-history :error])
                          "merge-failure"))
                     (is (= stale-before @stale-store))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected error: " err))
                      (done))))))))

(deftest api-fetch-historical-orders-effect-no-address-clears-only-current-request-test
  (let [current-store (atom (-> (base-history-state nil)
                                (assoc-in [:account-info :order-history :request-id] 3)
                                (assoc-in [:orders :order-history] [{:id "old"}])))
        stale-store (atom (-> (base-history-state nil)
                              (assoc-in [:account-info :order-history :request-id] 4)
                              (assoc-in [:orders :order-history] [{:id "old"}])))
        stale-before @stale-store]
    (history-effects/api-fetch-historical-orders-effect nil current-store 3)
    (is (false? (get-in @current-store [:account-info :order-history :loading?])))
    (is (nil? (get-in @current-store [:account-info :order-history :error])))
    (is (nil? (get-in @current-store [:account-info :order-history :loaded-at-ms])))
    (is (nil? (get-in @current-store [:account-info :order-history :loaded-for-address])))
    (is (= [] (get-in @current-store [:orders :order-history])))
    (history-effects/api-fetch-historical-orders-effect nil stale-store 3)
    (is (= stale-before @stale-store))))

(deftest api-fetch-historical-orders-effect-success-applies-only-current-request-test
  (async done
    (let [rows (list {:oid "a"} {:oid "b"})
          current-store (atom (-> (base-history-state "0xabc")
                                  (assoc-in [:account-info :order-history :request-id] 20)))
          stale-store (atom (-> (base-history-state "0xabc")
                                (assoc-in [:account-info :order-history :request-id] 21)))
          stale-before @stale-store]
      (with-redefs [api/request-historical-orders! (fn
                                                     ([_address]
                                                      (js/Promise.resolve rows))
                                                     ([_address _opts]
                                                      (js/Promise.resolve rows)))]
        (-> (js/Promise.all
             #js [(history-effects/api-fetch-historical-orders-effect nil current-store 20)
                  (history-effects/api-fetch-historical-orders-effect nil stale-store 20)])
            (.then (fn [_]
                     (is (false? (get-in @current-store [:account-info :order-history :loading?])))
                     (is (nil? (get-in @current-store [:account-info :order-history :error])))
                     (is (number? (get-in @current-store [:account-info :order-history :loaded-at-ms])))
                     (is (= "0xabc" (get-in @current-store [:account-info :order-history :loaded-for-address])))
                     (is (vector? (get-in @current-store [:orders :order-history])))
                     (is (= rows (seq (get-in @current-store [:orders :order-history]))))
                     (is (= stale-before @stale-store))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected error: " err))
                      (done))))))))

(deftest api-fetch-historical-orders-effect-error-applies-only-current-request-test
  (async done
    (let [current-store (atom (-> (base-history-state "0xabc")
                                  (assoc-in [:account-info :order-history :request-id] 30)))
          stale-store (atom (-> (base-history-state "0xabc")
                                (assoc-in [:account-info :order-history :request-id] 31)))
          stale-before @stale-store]
      (with-redefs [api/request-historical-orders! (fn
                                                     ([_address]
                                                      (js/Promise.reject (js/Error. "orders-boom")))
                                                     ([_address _opts]
                                                      (js/Promise.reject (js/Error. "orders-boom"))))]
        (-> (js/Promise.all
             #js [(history-effects/api-fetch-historical-orders-effect nil current-store 30)
                  (history-effects/api-fetch-historical-orders-effect nil stale-store 30)])
            (.then (fn [_]
                     (is (false? (get-in @current-store [:account-info :order-history :loading?])))
                     (is (str/includes?
                          (get-in @current-store [:account-info :order-history :error])
                          "orders-boom"))
                     (is (= stale-before @stale-store))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected error: " err))
                      (done))))))))

(deftest export-funding-history-csv-effect-builds-and-downloads-csv-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        orig-url (.-URL js/globalThis)
        had-url? (has-own? js/globalThis "URL")
        orig-blob (.-Blob js/globalThis)
        had-blob? (has-own? js/globalThis "Blob")
        csv-text (atom nil)
        append-count (atom 0)
        remove-count (atom 0)
        click-count (atom 0)
        revoked-url (atom nil)
        link (js-obj)]
    (try
      (set! (.-click link) (fn [] (swap! click-count inc)))
      (set! (.-Blob js/globalThis)
            (fn [parts _opts]
              (js-obj "parts" parts)))
      (set! (.-URL js/globalThis)
            (js-obj
             "createObjectURL" (fn [blob]
                                 (reset! csv-text (aget (gobj/get blob "parts") 0))
                                 "blob://funding-history")
             "revokeObjectURL" (fn [url]
                                 (reset! revoked-url url))))
      (set! (.-document js/globalThis)
            (js-obj
             "createElement" (fn [_tag] link)
             "body" (js-obj
                     "appendChild" (fn [el]
                                     (is (identical? link el))
                                     (swap! append-count inc))
                     "removeChild" (fn [el]
                                     (is (identical? link el))
                                     (swap! remove-count inc)))))
      (history-effects/export-funding-history-csv-effect
       nil nil
       [{:time-ms 1700000000000
         :coin "BTC, \"perp\"\nX"
         :size-raw 1234.5
         :position-side :mystery
         :payment-usdc-raw 1.23456
         :funding-rate-raw 0.00012}])
      (is (= 1 @append-count))
      (is (= 1 @remove-count))
      (is (= 1 @click-count))
      (is (= "blob://funding-history" @revoked-url))
      (is (string? @csv-text))
      (is (str/includes? @csv-text "Time,Coin,Size,Position Side,Payment,Rate"))
      (is (str/includes? @csv-text "\"BTC, \"\"perp\"\"\nX\""))
      (is (str/includes? @csv-text ",Flat,"))
      (is (str/includes? @csv-text "USDC"))
      (is (str/includes? @csv-text "%"))
      (is (str/starts-with? (.-download link) "funding-history-"))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))
        (if had-url?
          (set! (.-URL js/globalThis) orig-url)
          (js-delete js/globalThis "URL"))
        (if had-blob?
          (set! (.-Blob js/globalThis) orig-blob)
          (js-delete js/globalThis "Blob"))))))

(deftest export-funding-history-csv-effect-without-document-noops-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")]
    (try
      (js-delete js/globalThis "document")
      (is (nil? (history-effects/export-funding-history-csv-effect nil nil [])))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))))))

(deftest export-funding-history-csv-effect-covers-side-labels-and-fallback-formatting-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        orig-url (.-URL js/globalThis)
        had-url? (has-own? js/globalThis "URL")
        orig-blob (.-Blob js/globalThis)
        had-blob? (has-own? js/globalThis "Blob")
        csv-text (atom nil)]
    (try
      (set! (.-Blob js/globalThis)
            (fn [parts _opts]
              (js-obj "parts" parts)))
      (set! (.-URL js/globalThis)
            (js-obj
             "createObjectURL" (fn [blob]
                                 (reset! csv-text (aget (gobj/get blob "parts") 0))
                                 "blob://funding-history-extra")
             "revokeObjectURL" (fn [_] nil)))
      (set! (.-document js/globalThis)
            (js-obj
             "createElement" (fn [_tag]
                               (js-obj "click" (fn [] nil)))
             "body" (js-obj
                     "appendChild" (fn [_] nil)
                     "removeChild" (fn [_] nil))))
      (history-effects/export-funding-history-csv-effect
       nil nil
       [{:time-ms 1700000000000
         :coin nil
         :size-raw "oops"
         :position-side :long
         :payment-usdc-raw "oops"
         :funding-rate-raw "oops"}
        {:time-ms 1700000001000
         :coin "ETH"
         :size-raw 1.25
         :position-side :short
         :payment-usdc-raw 0.45
         :funding-rate-raw 0.00034}])
      (is (str/includes? @csv-text ",Long,"))
      (is (str/includes? @csv-text ",Short,"))
      (is (str/includes? @csv-text ",,0.000 -,Long,0.0000 USDC,0.0000%"))
      (is (str/includes? @csv-text ",ETH,1.250 ETH,Short,0.4500 USDC,0.0340%"))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))
        (if had-url?
          (set! (.-URL js/globalThis) orig-url)
          (js-delete js/globalThis "URL"))
        (if had-blob?
          (set! (.-Blob js/globalThis) orig-blob)
          (js-delete js/globalThis "Blob"))))))

(deftest export-funding-history-csv-effect-without-url-noops-test
  (let [orig-document (.-document js/globalThis)
        had-document? (has-own? js/globalThis "document")
        orig-url (.-URL js/globalThis)
        had-url? (has-own? js/globalThis "URL")
        create-count (atom 0)]
    (try
      (set! (.-document js/globalThis)
            (js-obj
             "createElement" (fn [_tag]
                               (swap! create-count inc)
                               (js-obj "click" (fn [] nil)))
             "body" (js-obj
                     "appendChild" (fn [_] nil)
                     "removeChild" (fn [_] nil))))
      (js-delete js/globalThis "URL")
      (is (nil? (history-effects/export-funding-history-csv-effect nil nil [])))
      (is (= 0 @create-count))
      (finally
        (if had-document?
          (set! (.-document js/globalThis) orig-document)
          (js-delete js/globalThis "document"))
        (if had-url?
          (set! (.-URL js/globalThis) orig-url)
          (js-delete js/globalThis "URL"))))))
