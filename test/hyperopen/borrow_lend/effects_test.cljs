(ns hyperopen.borrow-lend.effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.projections.borrow-lend :as projections]
            [hyperopen.borrow-lend.effects :as effects]))

(def owner "0x1111111111111111111111111111111111111111")
(def other "0x2222222222222222222222222222222222222222")

(defn- store-for [address]
  (atom {:wallet {:address address :connected? true}
         :borrow-lend-ui {:modal {:open? true :submitting? true}}}))

(defn- user-state-deps [store request!]
  {:store store
   :address owner
   :request-borrow-lend-user-state! request!
   :begin-borrow-lend-user-state-load projections/begin-borrow-lend-user-state-load
   :apply-borrow-lend-user-state-success projections/apply-borrow-lend-user-state-success
   :apply-borrow-lend-user-state-error projections/apply-borrow-lend-user-state-error})

(deftest fetch-user-state-applies-for-current-account-test
  (async done
    (let [store (store-for owner)
          payload {:positions [{:token 0 :supplied 1}] :health "healthy" :health-factor nil}]
      (-> (effects/api-fetch-borrow-lend-user-state!
           (user-state-deps store (fn [address _] (is (= owner address)) (js/Promise.resolve payload))))
          (.then (fn [_]
                   (is (= payload (get-in @store [:borrow-lend :user-state])))
                   (is (= owner (get-in @store [:borrow-lend :user-address])))
                   (is (false? (get-in @store [:borrow-lend :loading :user-state])))
                   (done)))))))

(deftest fetch-user-state-drops-stale-response-test
  (async done
    (let [store (store-for owner)]
      (-> (effects/api-fetch-borrow-lend-user-state!
           (user-state-deps store (fn [_ _]
                                    ;; The wallet switches while the request is in flight.
                                    (swap! store assoc-in [:wallet :address] other)
                                    (js/Promise.resolve {:positions [{:token 0 :supplied 9}]}))))
          (.then (fn [_]
                   (is (nil? (get-in @store [:borrow-lend :user-state])))
                   (done)))))))

(defn- interest-deps [store calls interest-pages ledger-pages]
  {:store store
   :address owner
   :request-borrow-lend-interest! (fn [_ start opts]
                                    (swap! calls conj [:interest start (:priority opts)])
                                    (js/Promise.resolve (get interest-pages start [])))
   :request-user-non-funding-ledger-updates! (fn [_ start end _]
                                               (swap! calls conj [:ledger start end])
                                               (js/Promise.resolve (get ledger-pages start [])))
   :normalize-supply-ledger-rows (fn [rows] (mapv :time rows))
   :begin-borrow-lend-interest-load projections/begin-borrow-lend-interest-load
   :apply-borrow-lend-interest-success projections/apply-borrow-lend-interest-success
   :apply-borrow-lend-interest-error projections/apply-borrow-lend-interest-error})

(deftest fetch-interest-pages-whole-history-then-ledger-test
  (async done
    (let [store (store-for owner)
          calls (atom [])
          a {:time-ms 10 :token "USDC"}
          b {:time-ms 20 :token "USDC"}
          b2 {:time-ms 20 :token "USDH"}
          c {:time-ms 30 :token "USDC"}
          ledger-1 {:time 12 :hash "0x1" :delta {:type "borrowLend" :token "USDC"}}
          ledger-2 {:time 15 :hash "0x2" :delta {:type "send" :token "USDC"}}]
      (-> (effects/api-fetch-borrow-lend-interest!
           (interest-deps store calls
                          ;; The second page starts at the first page's last
                          ;; timestamp, so the USDH row sharing it is kept.
                          {0 [a b] 20 [b b2 c] 30 [c]}
                          {10 [ledger-1 ledger-2] 15 [ledger-2]}))
          (.then (fn [_]
                   (is (= [[:interest 0 :low] [:interest 20 :low] [:interest 30 :low]
                           [:ledger 10 nil] [:ledger 15 nil]]
                          @calls))
                   (is (= [a b b2 c] (get-in @store [:borrow-lend :interest])))
                   (is (= [12 15] (get-in @store [:borrow-lend :ledger])))
                   (is (= owner (get-in @store [:borrow-lend :interest-address])))
                   (done)))))))

(deftest fetch-interest-skips-ledger-without-history-test
  (async done
    (let [store (store-for owner)
          calls (atom [])]
      (-> (effects/api-fetch-borrow-lend-interest! (interest-deps store calls {} {}))
          (.then (fn [_]
                   (is (= [[:interest 0 :low]] @calls))
                   (is (= [] (get-in @store [:borrow-lend :ledger])))
                   (done)))))))

(defn- submit-deps [store resp-or-error calls]
  {:store store
   :request {:kind :supply :action {:type "borrowLend" :operation "supply" :token 0 :amount "5"}}
   :dispatch! (fn [_ _ actions] (swap! calls conj [:dispatch actions]))
   :submit-borrow-lend! (fn [_ address action opts]
                          (swap! calls conj [:submit address action opts])
                          (if (instance? js/Error resp-or-error)
                            (js/Promise.reject resp-or-error)
                            (js/Promise.resolve resp-or-error)))
   :exchange-response-error (fn [resp] (get-in resp [:response]))
   :runtime-error-message (fn [err] (.-message err))
   :show-toast! (fn [_ kind message] (swap! calls conj [:toast kind message]))})

(deftest submit-success-test
  (async done
    (let [store (store-for owner)
          calls (atom [])]
      (-> (effects/api-submit-borrow-lend! (submit-deps store {:status "ok"} calls))
          (.then (fn [_]
                   (is (= [[:submit owner {:type "borrowLend" :operation "supply" :token 0 :amount "5"} {}]
                           [:toast :success "Supply submitted."]
                           [:dispatch [[:actions/load-borrow-lend] [:actions/load-user-data owner]]]]
                          @calls))
                   (is (false? (get-in @store [:borrow-lend-ui :modal :open?])))
                   (is (false? (get-in @store [:borrow-lend-ui :modal :submitting?])))
                   (done)))))))

(deftest submit-exchange-error-keeps-modal-open-test
  (async done
    (let [store (store-for owner)
          calls (atom [])]
      (-> (effects/api-submit-borrow-lend! (submit-deps store {:status "err" :response "Insufficient balance"} calls))
          (.then (fn [_]
                   (is (= "Supply failed: Insufficient balance" (get-in @store [:borrow-lend-ui :modal :error])))
                   (is (true? (get-in @store [:borrow-lend-ui :modal :open?])))
                   (is (= [:toast :error "Supply failed: Insufficient balance"] (last @calls)))
                   (done)))))))

(deftest submit-runtime-error-test
  (async done
    (let [store (store-for owner)
          calls (atom [])]
      (-> (effects/api-submit-borrow-lend! (submit-deps store (js/Error. "Agent session unavailable") calls))
          (.then (fn [_]
                   (is (= "Supply failed: Agent session unavailable"
                          (get-in @store [:borrow-lend-ui :modal :error])))
                   (done)))))))

(deftest submit-without-wallet-does-not-sign-test
  (async done
    (let [store (store-for nil)
          calls (atom [])]
      (-> (effects/api-submit-borrow-lend! (submit-deps store {:status "ok"} calls))
          (.then (fn [_]
                   (is (= [] @calls))
                   (is (= "Connect your wallet first." (get-in @store [:borrow-lend-ui :modal :error])))
                   (done)))))))
