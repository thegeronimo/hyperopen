(ns hyperopen.wallet.address-watcher-lifecycle-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop :include-macros true]
            [hyperopen.account.context :as account-context]
            [hyperopen.wallet.address-watcher :as watcher]))

(def ^:private address-a
  "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private address-b
  "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(def ^:private address-c
  "0xcccccccccccccccccccccccccccccccccccccccc")

(defn- reset-watcher-state! []
  (reset! @#'hyperopen.wallet.address-watcher/address-watcher-state
          {:handlers []
           :current-address nil
           :watching? false
           :pending-subscription nil
           :ws-connected? false}))

(use-fixtures
  :each
  {:before (fn []
             (reset-watcher-state!))
   :after (fn []
            (reset-watcher-state!))})

(defn- lifecycle-state
  [mode]
  (case mode
    :disconnected {:wallet {:address nil}
                   :router {:path "/trade"}
                   :account-context {:spectate-mode {:active? false
                                                     :address nil}}}
    :connected-a {:wallet {:address address-a}
                  :router {:path "/trade"}
                  :account-context {:spectate-mode {:active? false
                                                    :address nil}}}
    :connected-b {:wallet {:address address-b}
                  :router {:path "/trade"}
                  :account-context {:spectate-mode {:active? false
                                                    :address nil}}}
    :spectate-a {:wallet {:address address-b}
                 :router {:path "/trade"}
                 :account-context {:spectate-mode {:active? true
                                                   :address address-a}}}
    :spectate-b {:wallet {:address address-a}
                 :router {:path "/trade"}
                 :account-context {:spectate-mode {:active? true
                                                   :address address-b}}}
    :trader-a {:wallet {:address address-b}
               :router {:path (str "/portfolio/trader/" address-a)}
               :account-context {:spectate-mode {:active? false
                                                 :address nil}}}
    :trader-c {:wallet {:address address-a}
               :router {:path (str "/portfolio/trader/" address-c)}
               :account-context {:spectate-mode {:active? false
                                                 :address nil}}}))

(defn- expected-effective-transitions
  [states]
  (->> (partition 2 1 states)
       (keep (fn [[old-state new-state]]
               (let [old-address (account-context/effective-account-address old-state)
                     new-address (account-context/effective-account-address new-state)]
                 (when (not= old-address new-address)
                   [old-address new-address]))))
       vec))

(defn- recorded-transitions-for-states
  [states]
  (let [calls (atom [])
        handler (reify watcher/IAddressChangeHandler
                  (on-address-changed [_ old-address new-address]
                    (swap! calls conj [old-address new-address]))
                  (get-handler-name [_]
                    "address-watcher-lifecycle-test-handler"))]
    (watcher/add-handler! handler)
    (watcher/on-websocket-connected!)
    (swap! @#'hyperopen.wallet.address-watcher/address-watcher-state
           assoc :watching? true)
    (doseq [[old-state new-state] (partition 2 1 states)]
      (@#'hyperopen.wallet.address-watcher/address-change-listener
       nil nil old-state new-state))
    @calls))

(defn- replayed-pending-transitions-for-states
  [states]
  (reset-watcher-state!)
  (let [calls (atom [])
        handler (reify watcher/IAddressChangeHandler
                  (on-address-changed [_ old-address new-address]
                    (swap! calls conj [old-address new-address]))
                  (get-handler-name [_]
                    "address-watcher-lifecycle-test-handler"))]
    (watcher/add-handler! handler)
    (swap! @#'hyperopen.wallet.address-watcher/address-watcher-state
           assoc :watching? true)
    (doseq [[old-state new-state] (partition 2 1 states)]
      (@#'hyperopen.wallet.address-watcher/address-change-listener
       nil nil old-state new-state))
    (watcher/on-websocket-connected!)
    {:calls @calls
     :pending-subscription (:pending-subscription
                            @#'hyperopen.wallet.address-watcher/address-watcher-state)}))

(defn- latest-effective-transition
  [states]
  (some-> (expected-effective-transitions states) last vector))

(deftest address-change-listener-transition-matrix-tracks-effective-account-across-modes-test
  (doseq [{:keys [label old-mode new-mode expected]} [{:label "connected -> spectate"
                                                       :old-mode :connected-a
                                                       :new-mode :spectate-b
                                                       :expected [address-a address-b]}
                                                      {:label "spectate -> connected"
                                                       :old-mode :spectate-b
                                                       :new-mode :connected-a
                                                       :expected [address-b address-a]}
                                                      {:label "spectate -> disconnected"
                                                       :old-mode :spectate-a
                                                       :new-mode :disconnected
                                                       :expected [address-a nil]}
                                                      {:label "connected -> disconnected"
                                                       :old-mode :connected-b
                                                       :new-mode :disconnected
                                                       :expected [address-b nil]}
                                                      {:label "trader route -> disconnected"
                                                       :old-mode :trader-c
                                                       :new-mode :disconnected
                                                       :expected [address-c nil]}]]
    (reset-watcher-state!)
    (is (= [expected]
           (recorded-transitions-for-states [(lifecycle-state old-mode)
                                             (lifecycle-state new-mode)]))
        label)))

(deftest address-change-listener-generative-sequences-follow-effective-account-identity-test
  (let [mode-gen (gen/elements [:disconnected
                                :connected-a
                                :connected-b
                                :spectate-a
                                :spectate-b
                                :trader-a
                                :trader-c])
        property (prop/for-all [modes (gen/vector mode-gen 2 40)]
                   (let [states (mapv lifecycle-state modes)]
                     (= (expected-effective-transitions states)
                        (recorded-transitions-for-states states))))
        result (tc/quick-check 120 property)]
    (is (:pass? result)
        (pr-str (dissoc result :result)))))

(deftest address-change-listener-replays-latest-pending-effective-transition-after-websocket-connect-test
  (let [mode-gen (gen/elements [:disconnected
                                :connected-a
                                :connected-b
                                :spectate-a
                                :spectate-b
                                :trader-a
                                :trader-c])
        property
        (prop/for-all [modes (gen/vector mode-gen 2 40)]
          (let [states (mapv lifecycle-state modes)
                {:keys [calls pending-subscription]}
                (replayed-pending-transitions-for-states states)]
            (and (= (or (latest-effective-transition states) [])
                    calls)
                 (nil? pending-subscription))))
        result (tc/quick-check 120 property)]
    (is (:pass? result)
        (pr-str (dissoc result :result)))))
