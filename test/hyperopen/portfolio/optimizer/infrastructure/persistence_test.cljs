(ns hyperopen.portfolio.optimizer.infrastructure.persistence-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [hyperopen.core-bootstrap.test-support.browser-mocks :as browser-mocks]
            [hyperopen.platform.indexed-db :as indexed-db]
            [hyperopen.portfolio.optimizer.infrastructure.persistence :as persistence]
            [hyperopen.test-support.async :as async-support]))

(def ^:private wallet-address
  "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(deftest persistence-key-helpers-are-stable-and-scoped-test
  (is (= (str "scenario-index::" wallet-address)
         (persistence/scenario-index-key (str/upper-case wallet-address))))
  (is (= (str "draft::" wallet-address)
         (persistence/draft-key wallet-address)))
  (is (= "scenario::scn_01"
         (persistence/scenario-key " scn_01 ")))
  (is (= "tracking::scn_01"
         (persistence/tracking-key "scn_01")))
  (is (nil? (persistence/scenario-index-key "not-an-address")))
  (is (nil? (persistence/scenario-key "   "))))

(deftest portfolio-optimizer-store-roundtrips-scenarios-drafts-and-tracking-test
  (async done
    (browser-mocks/with-test-indexed-db
      (fn []
        (let [scenario {:id "scn_01"
                        :status :saved
                        :config {:objective {:kind :max-sharpe}}}
              scenario-index {:ordered-ids ["scn_01"]
                              :by-id {"scn_01" {:name "Core run"}}}
              draft {:name "Draft"
                     :objective {:kind :minimum-variance}}
              tracking {:scenario-id "scn_01"
                        :snapshots [{:weight-drift-rms 0.02}]}
              fail! (async-support/unexpected-error done)]
          (-> (js/Promise.all
               #js [(persistence/save-scenario-index! wallet-address scenario-index)
                    (persistence/save-scenario! "scn_01" scenario)
                    (persistence/save-draft! wallet-address draft)
                    (persistence/save-tracking! "scn_01" tracking)])
              (.then (fn [results]
                       (is (= [true true true true]
                              (vec (array-seq results))))
                       (js/Promise.all
                        #js [(persistence/load-scenario-index! wallet-address)
                             (persistence/load-scenario! "scn_01")
                             (persistence/load-draft! wallet-address)
                             (persistence/load-tracking! "scn_01")])))
              (.then (fn [records]
                       (let [[loaded-index loaded-scenario loaded-draft loaded-tracking]
                             (vec (array-seq records))]
                         (is (= scenario-index loaded-index))
                         (is (= scenario loaded-scenario))
                         (is (= draft loaded-draft))
                         (is (= tracking loaded-tracking))
                         (persistence/delete-draft! wallet-address))))
              (.then (fn [deleted?]
                       (is (true? deleted?))
                       (persistence/load-draft! wallet-address)))
              (.then (fn [missing-draft]
                       (is (nil? missing-draft))
                       (done)))
              (.catch fail!)))))))

(deftest indexed-db-app-store-list-includes-portfolio-optimizer-store-test
  (async done
    (browser-mocks/with-test-indexed-db
      (fn []
        (let [fail! (async-support/unexpected-error done)]
          (-> (indexed-db/put-json! indexed-db/portfolio-optimizer-store
                                    "scenario::probe"
                                    {:ok true})
              (.then (fn [persisted?]
                       (is (true? persisted?))
                       (indexed-db/get-json! indexed-db/portfolio-optimizer-store
                                             "scenario::probe")))
              (.then (fn [record]
                       (is (= {:ok true} record))
                       (done)))
              (.catch fail!)))))))
