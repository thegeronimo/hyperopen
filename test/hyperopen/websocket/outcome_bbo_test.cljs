(ns hyperopen.websocket.outcome-bbo-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.outcome-listing]
            [hyperopen.asset-selector.query :as query]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.outcome-bbo :as outcome-bbo]))

(deftest bbo-message-parses-best-levels-test
  (is (= {:coin "#10" :bid "0.64000" :ask "0.66000"}
         (outcome-bbo/bbo-message->top
          {:channel "bbo" :data {:coin "#10" :time 1
                                 :bbo [{:px "0.64000" :sz "5" :n 1} {:px "0.66000" :sz "5" :n 1}]}})))
  (is (= {:coin "#10" :bid nil :ask "0.66"}
         (outcome-bbo/bbo-message->top {:channel "bbo" :data {:coin "#10" :bbo [nil {:px "0.66"}]}}))
      "an empty side is nil")
  (is (nil? (outcome-bbo/bbo-message->top {:channel "l2Book" :data {:coin "#10"}}))))

(deftest subscription-diff-test
  (is (= {:subscribe ["#30"] :unsubscribe ["#10"]}
         (outcome-bbo/subscription-diff #{"#10" "#20"} ["#20" "#30" nil]))))

(defn- contract
  [id]
  {:key (str "outcome:" id) :coin (str "#" (* 10 id)) :market-type :outcome
   :outcome-sides [{:coin (str "#" (* 10 id)) :side-index 0}
                   {:coin (str "#" (inc (* 10 id))) :side-index 1}]})

(defn- catalog-state
  [markets & {:keys [phase active] :or {phase :full}}]
  {:active-market active
   :asset-selector {:phase phase
                    :markets markets
                    :market-by-key (into {} (map (juxt :key identity)) markets)}})

(deftest top-of-book-coins-follow-the-open-event-and-the-catalog-test
  (let [question {:key "question:9" :coin "#10" :market-type :outcome
                  :outcome-sides (:outcome-sides (contract 1))
                  :question-options [{:sides (:outcome-sides (contract 1))}
                                     {:sides (:outcome-sides (contract 2))}]}
        markets [question (contract 3)]]
    (testing "every answer of the open event, side 0, only coins the catalog lists"
      (is (= ["#10" "#20"] (query/outcome-top-of-book-coins (catalog-state markets :active question))))
      (is (= ["#10" "#20"]
             (query/outcome-top-of-book-coins
              (catalog-state markets :active (update question :question-options conj {:sides [{:coin "#55550"}]}))))
          "a coin the loaded catalog does not list is never subscribed"))
    (testing "nothing before the full catalog has loaded"
      (is (= [] (query/outcome-top-of-book-coins (catalog-state markets :phase :minimum :active question)))))
    (testing "a perp selection subscribes nothing"
      (is (= [] (query/outcome-top-of-book-coins (catalog-state markets :active {:market-type :perp :coin "BTC"})))))))

(deftest top-of-book-coins-are-capped-test
  (let [contracts (mapv contract (range 1 100))
        question {:key "question:big" :coin "#10" :market-type :outcome
                  :outcome-sides (:outcome-sides (first contracts))
                  :question-options (mapv (fn [m] {:sides (:outcome-sides m)}) contracts)}
        coins (query/outcome-top-of-book-coins (catalog-state (conj contracts question) :active question))]
    (is (= query/outcome-top-of-book-limit (count coins)))
    (is (= 60 query/outcome-top-of-book-limit))))

;; ---- REST seeding (the venue's bbo only pushes on change) ---------------

(deftest snapshot-top-reads-the-best-levels-and-keeps-empty-sides-test
  (is (= {:bid "0.64" :ask "0.66"}
         (outcome-bbo/snapshot->top {:coin "#10" :levels [[{:px "0.64" :sz "5"} {:px "0.62" :sz "5"}]
                                                          [{:px "0.66" :sz "5"}]]})))
  (is (= {:bid nil :ask "0.66"} (outcome-bbo/snapshot->top {:levels [[] [{:px "0.66" :sz "5"}]]}))
      "a one-sided book is written as such")
  (is (= {:bid nil :ask nil} (outcome-bbo/snapshot->top {:levels [[] []]}))
      "an empty book is written as empty: No market / Not traded, not a dash")
  (is (nil? (outcome-bbo/snapshot->top nil)))
  (is (nil? (outcome-bbo/snapshot->top {:error "rate limited"}))))

(deftest seed-only-coins-without-a-top-of-book-test
  (let [fed (assoc-in (contract 1) [:outcome-sides 0 :bid] 0.4)
        state (catalog-state [fed (contract 2)])]
    (is (= ["#20"] (outcome-bbo/seed-coins state ["#10" "#20"])))
    (is (= ["#10"] (outcome-bbo/seed-coins (catalog-state [(contract 1)]) ["#10"])))))

(deftest a-seed-belongs-to-one-subscription-lifetime-test
  (let [rt (outcome-bbo/apply-subscription-change {} {:subscribe ["#10"] :unsubscribe []} ["#10"])
        gen (get-in rt [:generation "#10"])]
    (is (true? (outcome-bbo/accept-seed? rt "#10" gen)))
    (is (false? (outcome-bbo/accept-seed? (update rt :bbo-seen conj "#10") "#10" gen))
        "a newer bbo wins over a slower snapshot")
    (let [dropped (outcome-bbo/apply-subscription-change rt {:subscribe [] :unsubscribe ["#10"]} [])
          again (outcome-bbo/apply-subscription-change (update dropped :bbo-seen conj "#10")
                                                       {:subscribe ["#10"] :unsubscribe []} ["#10"])]
      (is (false? (outcome-bbo/accept-seed? dropped "#10" gen)) "an unsubscribed coin takes no seed")
      (is (= [] (:seed-queue dropped)))
      (is (false? (outcome-bbo/accept-seed? again "#10" gen)) "an old lifetime's seed is stale")
      (is (true? (outcome-bbo/accept-seed? again "#10" (get-in again [:generation "#10"])))
          "a re-subscribe starts a fresh lifetime that may be seeded again"))))

(deftest seeds-are-staggered-at-most-eight-at-a-time-test
  (let [requests (atom [])
        resolvers (atom [])
        coins (mapv #(str "#" (* 10 %)) (range 1 21))]
    (outcome-bbo/reset-runtime!)
    (with-redefs [ws-client/send-message! (fn [_] true)]
      (outcome-bbo/register-hooks! {:store (atom {})
                                    :resync! (fn [] nil)
                                    :request-snapshot! (fn [coin _]
                                                         (swap! requests conj coin)
                                                         (js/Promise. (fn [resolve _] (swap! resolvers conj resolve))))})
      (outcome-bbo/sync-subscriptions! coins (catalog-state (mapv contract (range 1 21))))
      (is (= outcome-bbo/max-concurrent-seeds (count @requests)))
      (is (= 8 outcome-bbo/max-concurrent-seeds))
      (is (= 12 (count (:seed-queue (outcome-bbo/runtime-snapshot)))))
      (outcome-bbo/sync-subscriptions! (take 10 coins) nil)
      (let [queue (:seed-queue (outcome-bbo/runtime-snapshot))]
        (is (seq queue))
        (is (every? (set (take 10 coins)) queue)
            "coins that left the screen leave the seed queue")))
    (outcome-bbo/reset-runtime!)))

(deftest every-displayed-answer-of-a-multi-answer-row-is-subscribed-test
  (let [sections [{:rows [{:key "question:1"
                           :answers [{:coin "#10" :side-index 0}
                                     {:coin "#20" :side-index 0}
                                     {:coin "#30" :side-index 0}]
                           :more 33}
                          {:key "outcome:5" :answers [{:coin "#50" :side-index 0} {:coin "#51" :side-index 1}]}]}]]
    (is (= #{"#10" "#20" "#30" "#50"}
           (hyperopen.asset-selector.outcome-listing/visible-coins sections ["question:1" "outcome:5"])))))
