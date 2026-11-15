(ns hyperopen.portfolio.optimizer.infrastructure.draft-autosave-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.optimizer.infrastructure.draft-autosave :as draft-autosave]))

(def ^:private address
  "0x1111111111111111111111111111111111111111")

(def ^:private btc-instrument
  {:instrument-id "perp:BTC"
   :market-type :perp
   :coin "BTC"
   :shortable? true
   :position-side :long})

(defn- touched-draft
  []
  (assoc (optimizer-defaults/default-draft)
         :universe [btc-instrument]))

(defn- fake-timers
  "Deterministic timer seam: scheduling stores the callback; (flush!) runs it."
  []
  (let [scheduled (atom nil)]
    {:set-timeout-fn (fn [f _ms] (reset! scheduled f) ::timer)
     :clear-timeout-fn (fn [_id] (reset! scheduled nil))
     :flush! (fn [] (when-let [f @scheduled]
                      (reset! scheduled nil)
                      (f)))
     :scheduled? (fn [] (some? @scheduled))}))

(defn- install!
  [store timers saves]
  (draft-autosave/note-persisted! nil)
  (draft-autosave/install-draft-autosave-watcher!
   {:store store
    :save-draft! (fn [addr record]
                   (swap! saves conj [addr record])
                   (js/Promise.resolve true))
    :now-ms-fn (constantly 1700000000000)
    :set-timeout-fn (:set-timeout-fn timers)
    :clear-timeout-fn (:clear-timeout-fn timers)}))

(deftest autosave-persists-a-touched-draft-per-wallet-test
  (async done
    (let [store (atom {:wallet {:address address}})
          timers (fake-timers)
          saves (atom [])]
      (install! store timers saves)
      (swap! store assoc-in [:portfolio :optimizer :draft] (touched-draft))
      (is ((:scheduled? timers)) "edit schedules a debounced flush")
      ((:flush! timers))
      (-> (js/Promise.resolve)
          (.then (fn []
                   (is (= 1 (count @saves)))
                   (let [[addr record] (first @saves)]
                     (is (= address addr))
                     (is (= 1 (:version record)))
                     (is (= (touched-draft) (:draft record)))
                     (is (= 1700000000000 (:saved-at-ms record))))
                   (is (= {:status :saved :at-ms 1700000000000}
                          (get-in @store [:portfolio :optimizer :draft-persist])))
                   (remove-watch store :hyperopen.portfolio.optimizer.infrastructure.draft-autosave/draft-autosave)
                   (done)))))))

(deftest autosave-skips-untouched-drafts-and-noop-changes-test
  (let [store (atom {:wallet {:address address}})
        timers (fake-timers)
        saves (atom [])]
    (install! store timers saves)
    (swap! store assoc-in [:portfolio :optimizer :draft]
           (optimizer-defaults/default-draft))
    (is (not ((:scheduled? timers))))
    (let [restored (touched-draft)]
      (draft-autosave/note-persisted! restored)
      (swap! store assoc-in [:portfolio :optimizer :draft] restored)
      (is (not ((:scheduled? timers)))))
    (remove-watch store :hyperopen.portfolio.optimizer.infrastructure.draft-autosave/draft-autosave)))

(deftest autosave-skips-flush-after-a-wallet-switch-test
  (let [store (atom {:wallet {:address address}})
        timers (fake-timers)
        saves (atom [])]
    (install! store timers saves)
    (swap! store assoc-in [:portfolio :optimizer :draft] (touched-draft))
    (is ((:scheduled? timers)))
    (swap! store assoc-in [:wallet :address]
           "0x2222222222222222222222222222222222222222")
    ((:flush! timers))
    (is (= [] @saves))
    (remove-watch store :hyperopen.portfolio.optimizer.infrastructure.draft-autosave/draft-autosave)))

(deftest holdings-arrival-dispatches-restore-or-preseed-test
  (let [dispatches (atom [])
        store (atom {:router {:path "/portfolio/optimize/new"}
                     :portfolio {:optimizer {:draft nil}}})]
    (draft-autosave/install-holdings-preseed-watcher!
     {:store store
      :dispatch! (fn [_store _event actions]
                   (swap! dispatches conj actions))})
    (swap! store assoc-in [:webdata2 :clearinghouseState]
           {:marginSummary {:accountValue "1000"}})
    (is (= [[[:actions/restore-or-preseed-portfolio-optimizer-draft
              "/portfolio/optimize/new"]]]
           @dispatches))
    (swap! store assoc-in [:webdata2 :clearinghouseState :assetPositions] [])
    (is (= 1 (count @dispatches)))
    (remove-watch store :hyperopen.portfolio.optimizer.infrastructure.draft-autosave/holdings-preseed)))

(deftest holdings-arrival-fires-per-source-test
  (let [dispatches (atom [])
        store (atom {:router {:path "/portfolio/optimize/new"}
                     :portfolio {:optimizer {:draft nil}}})]
    (draft-autosave/install-holdings-preseed-watcher!
     {:store store
      :dispatch! (fn [_store _event actions]
                   (swap! dispatches conj actions))})
    (swap! store assoc-in [:spot :clearinghouse-state :balances]
           [{:coin "PURR" :total "10"}])
    (is (= 1 (count @dispatches)) "spot arrival fires once")
    (swap! store assoc-in [:webdata2 :clearinghouseState]
           {:marginSummary {:accountValue "1000"}})
    (is (= 2 (count @dispatches)) "later perp arrival fires again")
    (remove-watch store :hyperopen.portfolio.optimizer.infrastructure.draft-autosave/holdings-preseed)))

(deftest holdings-arrival-respects-route-and-touched-draft-gates-test
  (let [dispatches (atom [])
        install! (fn [store]
                   (draft-autosave/install-holdings-preseed-watcher!
                    {:store store
                     :dispatch! (fn [_ _ actions]
                                  (swap! dispatches conj actions))})
                   store)]
    (let [store (install! (atom {:router {:path "/trade"}
                                 :portfolio {:optimizer {:draft nil}}}))]
      (swap! store assoc-in [:webdata2 :clearinghouseState] {:ok true})
      (remove-watch store :hyperopen.portfolio.optimizer.infrastructure.draft-autosave/holdings-preseed))
    (let [store (install! (atom {:router {:path "/portfolio/optimize/new"}
                                 :portfolio {:optimizer
                                             {:draft {:universe [btc-instrument]}}}}))]
      (swap! store assoc-in [:webdata2 :clearinghouseState] {:ok true})
      (remove-watch store :hyperopen.portfolio.optimizer.infrastructure.draft-autosave/holdings-preseed))
    (is (= [] @dispatches))))

(deftest identity-arrival-restores-draft-test
  (let [dispatches (atom [])
        store (atom {:router {:path "/portfolio/optimize/new"}
                     :portfolio {:optimizer {:draft nil}}})]
    (draft-autosave/install-identity-restore-watcher!
     {:store store
      :dispatch! (fn [_store _event actions]
                   (swap! dispatches conj actions))})
    (swap! store assoc-in [:wallet :address] address)
    (is (= [[[:actions/restore-or-preseed-portfolio-optimizer-draft
              "/portfolio/optimize/new"]]]
           @dispatches))
    (swap! store assoc :unrelated 1)
    (is (= 1 (count @dispatches)))
    (remove-watch store :hyperopen.portfolio.optimizer.infrastructure.draft-autosave/identity-restore)))

(deftest identity-arrival-respects-touched-draft-and-route-gates-test
  (let [dispatches (atom [])
        install! (fn [store]
                   (draft-autosave/install-identity-restore-watcher!
                    {:store store
                     :dispatch! (fn [_ _ actions]
                                  (swap! dispatches conj actions))})
                   store)]
    (let [store (install! (atom {:router {:path "/portfolio/optimize/new"}
                                 :portfolio {:optimizer {:draft (touched-draft)}}}))]
      (swap! store assoc-in [:wallet :address] address)
      (is (empty? @dispatches))
      (remove-watch store :hyperopen.portfolio.optimizer.infrastructure.draft-autosave/identity-restore))
    (let [store (install! (atom {:router {:path "/trade"}
                                 :portfolio {:optimizer {:draft nil}}}))]
      (swap! store assoc-in [:wallet :address] address)
      (is (empty? @dispatches))
      (remove-watch store :hyperopen.portfolio.optimizer.infrastructure.draft-autosave/identity-restore))))

(deftest assumption-library-gap-dispatches-hydrate-test
  (let [dispatches (atom [])
        store (atom {:wallet {:address address}
                     :portfolio {:optimizer {:draft {:universe [btc-instrument]
                                                     :history-assumptions {}}}}})]
    (draft-autosave/install-assumption-library-hydrate-watcher!
     {:store store
      :dispatch! (fn [_store _e actions] (swap! dispatches conj actions))})
    (swap! store assoc-in [:portfolio :optimizer :assumption-library]
           {"perp:BTC" {:instrument-id "perp:BTC"
                        :entry {:behavior :conservative}
                        :reference-instruments []
                        :updated-at-ms 1}})
    (is (= [[[:actions/hydrate-portfolio-optimizer-history-assumption-library]]]
           @dispatches)
        "The mirror arriving over an uncovered universe member triggers hydrate.")
    (swap! store assoc-in [:portfolio :optimizer :draft :history-assumptions]
           {"perp:BTC" {:behavior :conservative}})
    (swap! store assoc :unrelated 1)
    (is (= 1 (count @dispatches))
        "No gap, no dispatch - including on unrelated state changes.")
    (swap! store assoc-in [:portfolio :optimizer :assumption-library "perp:NEW"]
           {:instrument-id "perp:NEW"
            :entry {:behavior :proxy}
            :reference-instruments []
            :updated-at-ms 2})
    (is (= 1 (count @dispatches)))
    (swap! store update-in [:portfolio :optimizer :draft :universe]
           conj {:instrument-id "perp:NEW"})
    (is (= 2 (count @dispatches))
        "Adding a remembered asset to the universe reopens the gap and re-dispatches.")))

(deftest view-library-gap-dispatches-hydrate-test
  (let [dispatches (atom [])
        store (atom {:wallet {:address address}
                     :portfolio {:optimizer
                                 {:draft {:universe [btc-instrument]
                                          :return-model {:kind :black-litterman
                                                         :views []}}}}})]
    (draft-autosave/install-view-library-hydrate-watcher!
     {:store store
      :dispatch! (fn [_store _e actions] (swap! dispatches conj actions))})
    (swap! store assoc-in [:portfolio :optimizer :view-library]
           {"perp:BTC" {:instrument-id "perp:BTC"
                        :return 0.2
                        :confidence-level :high
                        :updated-at-ms 1}})
    (is (= [[[:actions/hydrate-portfolio-optimizer-view-library]]]
           @dispatches)
        "The mirror arriving over an uncovered universe member triggers hydrate.")
    (swap! store assoc-in [:portfolio :optimizer :draft :return-model :views]
           [{:kind :absolute :instrument-id "perp:BTC" :return 0.2}])
    (swap! store assoc :unrelated 1)
    (is (= 1 (count @dispatches))
        "No gap, no dispatch - including on unrelated state changes.")
    (swap! store assoc-in [:portfolio :optimizer :view-library "perp:NEW"]
           {:instrument-id "perp:NEW"
            :return -0.1
            :confidence-level :low
            :updated-at-ms 2})
    (is (= 1 (count @dispatches)))
    (swap! store update-in [:portfolio :optimizer :draft :universe]
           conj {:instrument-id "perp:NEW"})
    (is (= 2 (count @dispatches))
        "Re-adding a remembered asset reopens the gap and re-dispatches.")
    (swap! store assoc-in [:portfolio :optimizer :draft :return-model]
           {:kind :historical-mean})
    (swap! store update-in [:portfolio :optimizer :draft :universe]
           conj {:instrument-id "perp:BTC2"})
    (is (= 2 (count @dispatches)))))

(def ^:private loaded-history-data
  {:api-v2-history {:status :ok
                    :series-by-instrument
                    {"perp:BTC" {:instrument-id "hl:perp:BTC"
                                 :points [{:time-ms 1000 :close 100}]}}}
   :warnings []
   :loaded-at-ms 5000})

(defn- install-history-cache!
  [store timers saves]
  (draft-autosave/install-history-cache-watcher!
   {:store store
    :save-history-cache! (fn [addr record]
                           (swap! saves conj [addr record])
                           (js/Promise.resolve true))
    :now-ms-fn (constantly 1700000000000)
    :set-timeout-fn (:set-timeout-fn timers)
    :clear-timeout-fn (:clear-timeout-fn timers)}))

(deftest history-cache-watcher-persists-loaded-bundle-per-wallet-test
  (async done
    (let [store (atom {:wallet {:address address}})
          timers (fake-timers)
          saves (atom [])]
      (install-history-cache! store timers saves)
      (swap! store assoc-in [:portfolio :optimizer :history-data]
             loaded-history-data)
      (is ((:scheduled? timers)) "a load completion schedules a debounced write")
      ((:flush! timers))
      (-> (js/Promise.resolve)
          (.then (fn []
                   (is (= 1 (count @saves)))
                   (let [[addr record] (first @saves)]
                     (is (= address addr))
                     (is (= 1 (:version record)))
                     (is (= 1700000000000 (:saved-at-ms record)))
                     (is (= #{"perp:BTC"}
                            (set (keys (get-in record
                                               [:history-data
                                                :api-v2-history
                                                :series-by-instrument]))))))
                   (remove-watch store :hyperopen.portfolio.optimizer.infrastructure.draft-autosave/history-cache-autosave)
                   (done)))))))

(deftest history-cache-watcher-skips-hydrated-and-no-wallet-states-test
  (let [store (atom {:wallet {:address address}})
        timers (fake-timers)
        saves (atom [])]
    (install-history-cache! store timers saves)
    (swap! store assoc-in [:portfolio :optimizer :history-data]
           (assoc loaded-history-data :restored-from-cache? true))
    ((:flush! timers))
    (is (empty? @saves))
    (remove-watch store :hyperopen.portfolio.optimizer.infrastructure.draft-autosave/history-cache-autosave)
    (let [anonymous (atom {})]
      (install-history-cache! anonymous timers saves)
      (swap! anonymous assoc-in [:portfolio :optimizer :history-data]
             loaded-history-data)
      ((:flush! timers))
      (is (empty? @saves))
      (remove-watch anonymous :hyperopen.portfolio.optimizer.infrastructure.draft-autosave/history-cache-autosave))))
