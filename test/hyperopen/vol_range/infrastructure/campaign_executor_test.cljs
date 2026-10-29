(ns hyperopen.vol-range.infrastructure.campaign-executor-test
  "Start new tracking from now (HO-SPEC-014 v2.1 sections 12.1, 12.6) with
  the simulator only: it runs inside the account admission section, refuses
  on an unresolved send attempt, an incomplete read or a held section, and
  commits the new campaign with the old one's closure in one transaction.
  It never sends, signs or claims a signing counter: nothing here reaches a
  real exchange."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.api.trading.action-coordinator :as coord]
            [hyperopen.platform.locks :as locks]
            [hyperopen.vol-range.application.campaign :as campaign]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.infrastructure.campaign-executor :as campaign-executor]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-place :as place]
            [hyperopen.vol-range.simulator :as sim]))

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

(defn- fresh []
  (locks/reset-in-tab-state!)
  (sim/new-sim))

(def k (campaign/campaign-key sim/scope))

(defn- logged-deps
  "Simulator deps that log journal commits, and whose send path throws: a
  restart must never reach it."
  [s log]
  (let [d (sim/deps s)
        commit! (get-in d [:journal :commit!])]
    (-> d
        (assoc-in [:journal :commit!] (fn [records] (swap! log conj (mapv :kind records)) (commit! records)))
        (assoc :send! (fn [& _] (throw (js/Error. "send! must never be called")))
               :cloid-suffixes! (fn [& _] (throw (js/Error. "no order ids for a restart")))))))

(defn- place! [s] (place/place! (sim/deps s) {:frozen-plan (sim/frozen-plan s) :frozen-scope sim/scope}))
(defn- restart! [deps] (campaign-executor/start-tracking! deps {:campaign-key k :account-address "0xABC"}))
(defn- campaigns [s] (filterv #(= :campaign (:kind %)) (sim/records s)))
(defn- closures [s] (filterv #(= :campaign-closed (:kind %)) (sim/records s)))

(deftest a-restart-closes-the-open-campaign-and-starts-its-successor-test
  (async done
    (let [s (fresh)
          log (atom [])]
      (-> (place! s)
          (.then (fn [r]
                   (is (= :live (:status r)) "a live ladder keeps running and does not block")
                   (restart! (logged-deps s log))))
          (.then
           (fn [{:keys [status campaign closure] :as r}]
             (is (= :ok status) (pr-str r))
             (let [[old] (remove #(= (:campaign-id campaign) (:campaign-id %)) (campaigns s))]
               (is (= 2 (count (campaigns s))))
               (is (= [[:campaign :campaign-closed]] @log) "successor and closure in ONE commit")
               (is (= [closure] (closures s)))
               (is (= (:campaign-id old) (:campaign-id closure)))
               (is (= (:campaign-id campaign) (:successor-campaign-id closure)))
               (is (= (:t0-ms campaign) (:ended-at-ms closure)) "the old campaign ends where the new one starts")
               (is (< (:t0-ms old) (:t0-ms campaign)))
               (is (= :user-restart (:started-by campaign)))
               (is (= :user-restart (:reason closure)))
               (is (= (:campaign-id old) (:supersedes-campaign-id campaign)))
               (is (nil? (:first-operation-id campaign)))
               (is (nil? (:first-grid-id campaign)))
               (is (= k (:campaign-key campaign)))
               (is (= {:q0 "150" :p0 "80" :p0-source :book-midpoint :c0 "0"}
                      (select-keys (:baseline campaign) [:q0 :p0 :p0-source :c0])))
               (is (= (get-in campaign [:baseline :boundary :requested-at-ms]) (:t0-ms campaign)))
               (is (= (:boundary-skew-ms contracts/report-policy) (get-in campaign [:baseline :boundary :skew-ms])))
               (is (= (:campaign-id campaign)
                      (:campaign-id (campaign/open-campaign (sim/records s) k)))
                   "exactly one open campaign: the successor"))
             (is (= 1 (count (sim/order-sends s))) "nothing new was sent")
             (is (= 1 (:nonce @s)) "no signing counter was claimed")
             (done)))
          (.catch (fail done))))))

(deftest a-held-admission-section-returns-busy-test
  (async done
    (let [s (fresh)
          release (atom nil)
          held (js/Promise. (fn [resolve _] (reset! release resolve)))]
      (-> (place! s)
          (.then (fn [_]
                   ;; Another placement holds this account's admission section.
                   (coord/with-account-admission! {:network :hyperliquid-mainnet :account-address "0xabc"
                                                   :allow-in-tab-coordination? true}
                                                  (fn [] held))
                   (restart! (sim/deps s))))
          (.then (fn [r]
                   (is (= {:status :busy} r))
                   (is (= 1 (count (campaigns s))))
                   (is (empty? (closures s)))
                   (@release nil)
                   (done)))
          (.catch (fail done))))))

(deftest an-unresolved-send-attempt-refuses-the-restart-test
  (async done
    (let [s (fresh)]
      (sim/script! s {:kind :timeout-lost})
      (-> (place! s)
          (.then (fn [r]
                   (is (= :checking (:status r)) (pr-str (:status r)))
                   (restart! (sim/deps s))))
          (.then (fn [r]
                   (is (= :refused (:status r)))
                   (is (= [:campaign/unresolved-attempt] (:errors r)))
                   (testing "the same rule as placement admission"
                     (let [journal {:records (sim/records s) :unreadable []}
                           scope-key (contracts/scope-key sim/scope)]
                       (is (some #{:grid/unresolved-attempt} (ex/placement-blockers journal scope-key)))
                       (is (= [:campaign/unresolved-attempt] (campaign-executor/restart-blockers journal k)))))
                   (is (= 1 (count (campaigns s))))
                   (is (empty? (closures s)))
                   (done)))
          (.catch (fail done))))))

(deftest incomplete-reads-refuse-the-restart-test
  (async done
    (let [s (fresh)]
      (-> (place! s)
          (.then (fn [_]
                   (swap! s assoc :book-down? true)
                   (restart! (sim/deps s))))
          (.then (fn [r]
                   (is (= {:status :refused :errors [:guard/book-unavailable]} r))
                   (swap! s assoc :book-down? false)
                   (let [d (sim/deps s)
                         post-info! (:post-info! d)]
                     (restart! (assoc d :post-info! (fn [body opts]
                                                      (if (= "spotClearinghouseState" (get body "type"))
                                                        (js/Promise.resolve nil)
                                                        (post-info! body opts))))))))
          (.then (fn [r]
                   (is (= {:status :refused :errors [:guard/account-unavailable]} r))
                   (is (= 1 (count (campaigns s))))
                   (is (empty? (closures s)) "nothing is committed")
                   (done)))
          (.catch (fail done))))))

(deftest no-open-campaign-and-storage-failures-test
  (async done
    (let [s (fresh)]
      (-> (restart! (sim/deps s))
          (.then (fn [r]
                   (is (= {:status :refused :errors [:campaign/not-found]} r) "nothing to restart")
                   (swap! s assoc :journal-load-fail? true)
                   (restart! (sim/deps s))))
          (.then (fn [r]
                   (is (= :journal-unavailable (:status r)))
                   (is (empty? (campaigns s)))
                   (done)))
          (.catch (fail done))))))
