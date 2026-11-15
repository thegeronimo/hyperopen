(ns hyperopen.account.history.effects-funding-fetch-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account-tab-modules :as account-tab-modules]
            [hyperopen.account.history.actions :as history-actions]
            [hyperopen.account.history.effects :as history-effects]
            [hyperopen.account.history.test-support.fixtures :as fixtures]
            [hyperopen.api.default :as api]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.platform :as platform]
            [hyperopen.views.account-info.test-support.lazy-modules :as lazy-modules]
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

(deftest funding-history-flow-select-fetch-and-render-shows-rows-test
  (async done
    (let [filters {:coin-set #{}
                   :start-time-ms 0
                   :end-time-ms 2000000000000}
          store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
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
          fetch-effect (nth effects 2)
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
                     (let [panel (with-redefs [account-tab-modules/resolved-tab-renderer lazy-modules/tab-renderer
                                               account-tab-modules/tab-ready? lazy-modules/tab-ready?
                                               account-tab-modules/tab-loading? lazy-modules/tab-loading?
                                               account-tab-modules/tab-error lazy-modules/tab-error]
                                   (account-info-view/account-info-panel @store))
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
  (let [row (fixtures/info-funding-row 1700000000000 "BTC" "0.1000" "10" "0.0001")
        current-store (atom (-> (fixtures/base-history-state nil)
                                (assoc-in [:account-info :selected-tab] :funding-history)
                                (assoc-in [:account-info :funding-history :request-id] 5)
                                (assoc-in [:orders :fundings-raw] [row])
                                (assoc-in [:orders :fundings] [row])))
        stale-store (atom (-> (fixtures/base-history-state nil)
                              (assoc-in [:account-info :selected-tab] :funding-history)
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
    (let [existing-row (fixtures/info-funding-row 1700000000000 "ETH" "0.0500" "3" "0.0001")
          incoming-row (fixtures/info-funding-row 1700003600000 "BTC" "-0.1250" "-10" "-0.0003")
          filters {:coin-set #{"BTC"}
                   :start-time-ms 0
                   :end-time-ms 2000000000000}
          calls (atom [])
          current-store (atom (-> (fixtures/base-history-state "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                  (assoc-in [:account-info :selected-tab] :funding-history)
                                  (assoc-in [:account-info :funding-history :request-id] 9)
                                  (assoc-in [:account-info :funding-history :filters] filters)
                                  (assoc-in [:orders :fundings-raw] [existing-row])))
          stale-store (atom (-> (fixtures/base-history-state "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                (assoc-in [:account-info :selected-tab] :funding-history)
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
                     (is (= "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" (first (first @calls))))
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
    (let [current-store (atom (-> (fixtures/base-history-state "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                  (assoc-in [:account-info :selected-tab] :funding-history)
                                  (assoc-in [:account-info :funding-history :request-id] 11)))
          stale-store (atom (-> (fixtures/base-history-state "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                (assoc-in [:account-info :selected-tab] :funding-history)
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

(deftest api-fetch-user-funding-history-effect-skips-when-funding-tab-is-inactive-test
  (let [calls (atom 0)
        store (atom (-> (fixtures/base-history-state "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                        (assoc-in [:account-info :selected-tab] :balances)
                        (assoc-in [:account-info :funding-history :request-id] 42)
                        (assoc-in [:account-info :funding-history :loading?] true)))]
    (with-redefs [api/request-user-funding-history! (fn
                                                      ([_address]
                                                       (swap! calls inc)
                                                       (js/Promise.resolve []))
                                                      ([_address _opts]
                                                       (swap! calls inc)
                                                       (js/Promise.resolve [])))]
      (history-effects/api-fetch-user-funding-history-effect nil store 42)
      (is (= 0 @calls))
      (is (false? (get-in @store [:account-info :funding-history :loading?]))))))

(deftest api-fetch-user-funding-history-effect-prefers-hydrated-user-fundings-stream-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        row (fixtures/info-funding-row 1700003600000 "BTC" "-0.1250" "-10" "-0.0003")
        calls (atom 0)
        store (atom (-> (fixtures/base-history-state address)
                        (assoc-in [:account-info :selected-tab] :funding-history)
                        (assoc-in [:account-info :funding-history :request-id] 51)
                        (assoc-in [:account-info :funding-history :loading?] true)
                        (assoc-in [:account-info :funding-history :error] "stale")
                        (assoc-in [:orders :fundings-raw] [row])
                        (assoc-in [:websocket :health]
                                  {:transport {:state :connected
                                               :freshness :live}
                                   :streams {["userFundings" nil address nil nil]
                                             {:topic "userFundings"
                                              :status :n-a
                                              :subscribed? true
                                              :message-count 1
                                              :descriptor {:type "userFundings"
                                                           :user address}}}})))]
    (with-redefs [api/request-user-funding-history! (fn
                                                      ([_address]
                                                       (swap! calls inc)
                                                       (js/Promise.resolve []))
                                                      ([_address _opts]
                                                       (swap! calls inc)
                                                       (js/Promise.resolve [])))]
      (history-effects/api-fetch-user-funding-history-effect nil store 51)
      (is (= 0 @calls))
      (is (false? (get-in @store [:account-info :funding-history :loading?])))
      (is (nil? (get-in @store [:account-info :funding-history :error])))
      (is (= ["BTC"] (mapv :coin (get-in @store [:orders :fundings])))))))

(deftest api-fetch-user-funding-history-effect-falls-back-to-rest-when-stream-not-hydrated-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          row (fixtures/info-funding-row 1700003600000 "BTC" "-0.1250" "-10" "-0.0003")
          calls (atom 0)
          store (atom (-> (fixtures/base-history-state address)
                          (assoc-in [:account-info :selected-tab] :funding-history)
                          (assoc-in [:account-info :funding-history :request-id] 52)
                          (assoc-in [:account-info :funding-history :loading?] true)
                          (assoc-in [:websocket :health]
                                    {:transport {:state :connected
                                                 :freshness :live}
                                     :streams {["userFundings" nil address nil nil]
                                               {:topic "userFundings"
                                                :status :n-a
                                                :subscribed? true
                                                :message-count 0
                                                :descriptor {:type "userFundings"
                                                             :user address}}}})))]
      (with-redefs [platform/set-timeout! (fn [callback _ms]
                                            (callback)
                                            1234)
                    api/request-user-funding-history! (fn
                                                        ([_address]
                                                         (swap! calls inc)
                                                         (js/Promise.resolve [row]))
                                                        ([_address _opts]
                                                         (swap! calls inc)
                                                         (js/Promise.resolve [row])))]
        (-> (history-effects/api-fetch-user-funding-history-effect nil store 52)
            (.then (fn [_]
                     (is (= 1 @calls))
                     (is (false? (get-in @store [:account-info :funding-history :loading?])))
                     (is (= ["BTC"] (mapv :coin (get-in @store [:orders :fundings]))))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected error: " err))
                      (done))))))))

(deftest api-fetch-user-funding-history-effect-skips-rest-when-stream-hydrates-during-grace-window-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          row (fixtures/info-funding-row 1700003600000 "BTC" "-0.1250" "-10" "-0.0003")
          calls (atom 0)
          store (atom (-> (fixtures/base-history-state address)
                          (assoc-in [:account-info :selected-tab] :funding-history)
                          (assoc-in [:account-info :funding-history :request-id] 53)
                          (assoc-in [:account-info :funding-history :loading?] true)
                          (assoc-in [:orders :fundings-raw] [row])
                          (assoc-in [:websocket :health]
                                    {:transport {:state :connected
                                                 :freshness :live}
                                     :streams {["userFundings" nil address nil nil]
                                               {:topic "userFundings"
                                                :status :n-a
                                                :subscribed? true
                                                :message-count 0
                                                :descriptor {:type "userFundings"
                                                             :user address}}}})))]
      (with-redefs [platform/set-timeout! (fn [callback _ms]
                                            (swap! store assoc-in
                                                   [:websocket :health :streams
                                                    ["userFundings" nil address nil nil]
                                                    :message-count]
                                                   1)
                                            (callback)
                                            1234)
                    api/request-user-funding-history! (fn
                                                        ([_address]
                                                         (swap! calls inc)
                                                         (js/Promise.resolve [row]))
                                                        ([_address _opts]
                                                         (swap! calls inc)
                                                         (js/Promise.resolve [row])))]
        (-> (history-effects/api-fetch-user-funding-history-effect nil store 53)
            (.then (fn [_]
                     (is (= 0 @calls))
                     (is (false? (get-in @store [:account-info :funding-history :loading?])))
                     (is (nil? (get-in @store [:account-info :funding-history :error])))
                     (is (= ["BTC"] (mapv :coin (get-in @store [:orders :fundings]))))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected error: " err))
                      (done))))))))
