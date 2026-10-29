(ns hyperopen.vol-range.infrastructure.campaign-capture-test
  "Campaign baseline capture at the first admitted placement (HO-SPEC-014
  v2.1 sections 7.4, 12.1): committed in the same journal transaction as
  the intent and grid, before anything is sent; never for a later
  placement, a replacement or a repair; never changing what is sent.
  Simulator only: nothing here reaches a real exchange."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.platform.locks :as locks]
            [hyperopen.vol-range.application.campaign :as campaign]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.replace-flow :as replace-flow]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.fixtures :as fixtures]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-cancel :as cancel]
            [hyperopen.vol-range.infrastructure.executor-place :as place]
            [hyperopen.vol-range.infrastructure.executor-replace :as replace]
            [hyperopen.vol-range.simulator :as sim]))

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

(defn- fresh []
  (locks/reset-in-tab-state!)
  (sim/new-sim))

(def k (campaign/campaign-key sim/scope))

(defn- logged-deps
  "Simulator deps that log every journal commit (record kinds), every send!
  call and every balance read (with the clock when it was requested).
  `:fail-intent-commit?` rejects the commit that carries the intent."
  [s log & [{:keys [fail-intent-commit?]}]]
  (let [d (sim/deps s)
        commit! (get-in d [:journal :commit!])
        send! (:send! d)
        post-info! (:post-info! d)]
    (-> d
        (assoc-in [:journal :commit!]
                  (fn [records]
                    (swap! log conj [:commit (mapv :kind records)])
                    (if (and fail-intent-commit? (some #(= :placement-intent (:kind %)) records))
                      (js/Promise.reject (js/Error. "QuotaExceededError"))
                      (commit! records))))
        (assoc :send! (fn [action opts] (swap! log conj [:send (:type action)]) (send! action opts)))
        (assoc :post-info! (fn [body opts]
                             (when (= "spotClearinghouseState" (get body "type"))
                               (swap! log conj [:balances (sim/now s)]))
                             (post-info! body opts))))))

(defn- place! [deps s] (place/place! deps {:frozen-plan (sim/frozen-plan s) :frozen-scope sim/scope}))

(defn- campaigns [s] (filterv #(= :campaign (:kind %)) (sim/records s)))
(defn- intents [s] (filterv #(= :placement-intent (:kind %)) (sim/records s)))
(defn- commits-with [log kind] (filterv (fn [[t kinds]] (and (= :commit t) (some #{kind} kinds))) log))
(defn- index-of [log pred] (first (keep-indexed (fn [i e] (when (pred e) i)) log)))

(deftest the-first-placement-commits-its-campaign-with-the-intent-before-send-test
  (async done
    (let [s (fresh)
          log (atom [])]
      (-> (place! (logged-deps s log) s)
          (.then
           (fn [{:keys [status grid]}]
             (is (= :live status))
             (let [[c :as cs] (campaigns s)
                   intent-commit (index-of @log (fn [[t kinds]] (and (= :commit t) (some #{:placement-intent} kinds))))
                   first-send (index-of @log #(= :send (first %)))
                   barrier-at (second (first (filter #(= :balances (first %)) @log)))]
               (is (= 1 (count cs)) "exactly one campaign")
               (is (= [[:commit [:grid :placement-intent :campaign]]] (commits-with @log :campaign))
                   "intent, grid and campaign in ONE commit")
               (is (< intent-commit first-send) "committed before send! is called")
               (is (= k (:campaign-key c)))
               (is (= {:q0 "150" :p0 "80" :p0-source :book-midpoint :p0-bid "79.99" :p0-ask "80.01" :c0 "0"}
                      (select-keys (:baseline c) [:q0 :p0 :p0-source :p0-bid :p0-ask :c0])))
               (testing "the boundary is the admission barrier read"
                 (let [{:keys [requested-at-ms received-at-ms barrier-id skew-ms]} (get-in c [:baseline :boundary])]
                   (is (= barrier-at requested-at-ms))
                   (is (< requested-at-ms received-at-ms))
                   (is (string? barrier-id))
                   (is (= 5000 skew-ms))
                   (is (= requested-at-ms (:t0-ms c)))))
               (is (= {:model 1 :precision 1 :estimator "parkinson-v1"} (:model-versions c)))
               (is (= [contracts/campaign-schema-version contracts/accounting-model-version]
                      [(:campaign-schema-version c) (:accounting-model-version c)]))
               (is (= (:grid-id grid) (:first-grid-id c)))
               (is (= (:operation-id (first (intents s))) (:first-operation-id c)))
               (is (= :first-placement (:started-by c))))
             (done)))
          (.catch (fail done))))))

(def ^:private snapshot-digest
  "The 13.2 seven-order action digest from the simulator, as before campaign
  capture existed: capture must never change what is signed."
  "vr-act-178def3b035941-858")

(deftest capture-never-changes-the-action-legs-or-reservations-test
  ;; Same venue, one journal empty and one with an open campaign in this
  ;; market: the intent (digest, legs, reservations) and the send are the same.
  (async done
    (let [a (fresh)
          b (sim/new-sim)
          existing (campaign/baseline-record {:campaign-id "cmp-existing" :scope sim/scope
                                              :read {:requested-at-ms 1 :received-at-ms 2 :barrier-id "rb-0"
                                                     :balances []}
                                              :bbo {:bid "79" :ask "81" :observed-at-ms 1} :now-ms 3})]
      (swap! b assoc-in [:journal (:record-id existing)] (pr-str existing))
      (-> (place! (sim/deps a) a)
          (.then (fn [_] (locks/reset-in-tab-state!) (place! (sim/deps b) b)))
          (.then
           (fn [_]
             (let [[ia] (intents a) [ib] (intents b)
                   pick #(select-keys % [:action-digest :legs :reservations :scope :scope-key :input-fingerprint])]
               (is (some? ia))
               (is (= (pick ia) (pick ib)) "identical intent with and without an existing campaign")
               (is (= (sim/order-sends a) (sim/order-sends b)) "identical action sent")
               (is (= [1 1] [(:nonce @a) (:nonce @b)]) "one signing counter claim each")
               (is (= 1 (count (campaigns a))))
               (is (= ["cmp-existing"] (mapv :campaign-id (campaigns b))) "the open campaign is reused")
               (is (= {:usdc "2889.1552" :base "30"} (:reservations ia)))
               (is (= snapshot-digest (:action-digest ia)) "the pre-capture snapshot"))
             (done)))
          (.catch (fail done))))))

(deftest a-rejected-commit-sends-nothing-and-keeps-no-campaign-test
  (async done
    (let [s (fresh)
          log (atom [])]
      (-> (place! (logged-deps s log {:fail-intent-commit? true}) s)
          (.then (fn [r]
                   (is (= :journal-unavailable (:status r)))
                   (is (not-any? #(= :send (first %)) @log) "send! is never called")
                   (is (empty? (sim/order-sends s)))
                   (is (empty? (campaigns s)))
                   (is (empty? (intents s)))
                   (done)))
          (.catch (fail done))))))

(deftest refused-placements-commit-no-campaign-test
  (async done
    (let [s (fresh)
          log (atom [])]
      (swap! s assoc :live {:monitoring-blocker :monitoring/stale})
      (-> (place! (logged-deps s log) s)
          (.then (fn [r]
                   (is (= :refused (:status r)) "the monitoring gate refuses before admission")
                   (swap! s assoc :live {})
                   (place/place! (logged-deps s log) {:frozen-plan (sim/frozen-plan s) :frozen-scope sim/scope
                                                      :predecessor {:grid-id "grid-nope" :frozen-cloids ["0x1"]}})))
          (.then (fn [r]
                   (is (= :blocked (:status r)))
                   (is (some #{:replace/predecessor-missing} (:errors r)) "the predecessor check refuses")
                   (is (empty? (filter #(= :commit (first %)) @log)) "nothing is committed")
                   (is (empty? (campaigns s)))
                   (done)))
          (.catch (fail done))))))

(deftest a-second-placement-and-a-repair-add-no-campaign-test
  (async done
    (let [s (fresh)
          log (atom [])]
      (sim/script! s {:kind :normal :reject-prices ["76.4"]})
      (-> (place! (logged-deps s log) s)
          (.then (fn [{:keys [grid]}] (ex/reconcile! (sim/deps s) grid)))
          (.then (fn [{:keys [grid]}]
                   (let [[candidate] (lifecycle/repair-candidates grid)]
                     (place/repair! (logged-deps s log)
                                    {:grid grid :legs [{:logical-id (:logical-id candidate) :price "76.3"
                                                        :size (:original-base candidate)}]}))))
          (.then (fn [{:keys [status grid]}]
                   (is (= :live status) "the repair was sent")
                   (is (= 2 (count (intents s))))
                   (is (= 1 (count (campaigns s))) "a repair adds no campaign")
                   (cancel/cancel! (sim/deps s) {:grid grid})))
          (.then (fn [{:keys [status grid]}]
                   (is (= :closed status))
                   ;; Releases the closed ladder's reservations.
                   (ex/reconcile! (sim/deps s) grid)))
          (.then (fn [_] (place! (logged-deps s log) s)))
          (.then (fn [{:keys [status] :as r}]
                   (is (= :live status) (str "a second ladder in the same market " (pr-str (select-keys r [:errors :error]))))
                   (is (= 3 (count (intents s))))
                   (is (= 1 (count (campaigns s))) "the open campaign is reused")
                   (is (= 1 (count (commits-with @log :campaign))))
                   (done)))
          (.catch (fail done))))))

(defn- replacement-draft []
  (-> fixtures/draft-13-2
      (assoc-in [:buy :quantity] "40")
      (assoc-in [:sell :quantity] "27")))

(deftest a-replacement-reuses-the-open-campaign-test
  ;; Spec 12.1: a campaign spans replacements.
  (async done
    (let [s (fresh)
          ctx (atom {})]
      (-> (place! (sim/deps s) s)
          (.then (fn [{:keys [grid]}] (ex/refresh-grid! (sim/deps s) grid)))
          (.then (fn [grid]
                   (let [frozen (replace-flow/freeze grid)
                         sell (:cloid (first (filter #(and (= :sell (:side %)) (= 0 (:rung-index %)))
                                                     (vals (:legs grid)))))]
                     (swap! ctx assoc :frozen frozen :grid-id (:grid-id grid))
                     (sim/advance! s 10)
                     (sim/fill! s sell "3")
                     (cancel/cancel! (sim/deps s) {:grid grid}))))
          (.then (fn [{:keys [grid]}] (replace/confirm! (sim/deps s) (assoc (:frozen @ctx) :grid grid))))
          (.then (fn [{:keys [status basis]}]
                   (is (= :ready status))
                   (swap! s assoc :draft (replacement-draft))
                   (place/place! (sim/deps s)
                                 {:frozen-plan (sim/frozen-plan s) :frozen-scope sim/scope
                                  :predecessor {:grid-id (:grid-id @ctx)
                                                :frozen-cloids (get-in @ctx [:frozen :frozen-cloids])
                                                :remaining (:remaining basis)
                                                :filled-during-update (:filled-during-update basis)
                                                :included-rejected []}})))
          (.then (fn [{:keys [status grid] :as r}]
                   (is (= :live status) (pr-str (select-keys r [:status :errors :error])))
                   (is (= (:grid-id @ctx) (get-in grid [:predecessor :grid-id])))
                   (is (= 2 (count (intents s))))
                   (is (= 1 (count (campaigns s))) "the replacement adds no campaign")
                   (done)))
          (.catch (fail done))))))
