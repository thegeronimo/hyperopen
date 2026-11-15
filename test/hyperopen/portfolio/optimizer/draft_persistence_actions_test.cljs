(ns hyperopen.portfolio.optimizer.draft-persistence-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]))

(def ^:private btc-instrument
  {:instrument-id "perp:BTC"
   :market-type :perp
   :coin "BTC"
   :shortable? true
   :position-side :long})

(defn- persisted-draft
  []
  (assoc (optimizer-defaults/default-draft)
         :universe [btc-instrument]))

(defn- draft-record
  ([] (draft-record nil))
  ([overrides]
   (merge {:version 1
           :address "0x1111111111111111111111111111111111111111"
           :draft (persisted-draft)
           :saved-at-ms 1700000000000}
          overrides)))

(deftest restore-or-preseed-fires-on-fresh-optimize-new-draft-test
  (is (= [[:effects/restore-portfolio-optimizer-draft]]
         (actions/restore-or-preseed-portfolio-optimizer-draft
          {:portfolio {:optimizer {:draft nil}}}
          "/portfolio/optimize/new")))
  (is (= [[:effects/restore-portfolio-optimizer-draft]]
         (actions/restore-or-preseed-portfolio-optimizer-draft
          {:portfolio {:optimizer {:draft (optimizer-defaults/default-draft)}}}
          "/portfolio/optimize/new")))
  (is (= [[:effects/restore-portfolio-optimizer-draft]]
         (actions/restore-or-preseed-portfolio-optimizer-draft
          {:portfolio {:optimizer {:draft nil}}}
          "/portfolio/optimize"))))

(deftest restore-or-preseed-never-fires-on-touched-draft-or-other-routes-test
  (let [touched {:portfolio {:optimizer {:draft {:universe [btc-instrument]}}}}]
    (is (= [] (actions/restore-or-preseed-portfolio-optimizer-draft
               touched
               "/portfolio/optimize/new")))
    (is (= [] (actions/restore-or-preseed-portfolio-optimizer-draft
               {:portfolio {:optimizer {:draft nil}}}
               "/portfolio/optimize/some-scenario")))))

(deftest hydrate-applies-a-valid-record-to-an-untouched-draft-test
  (let [record (draft-record)
        effects (actions/hydrate-portfolio-optimizer-draft
                 {:portfolio {:optimizer {:draft nil}}}
                 record)
        [save-effect history-effect] effects
        path-values (into {} (second save-effect))]
    (is (= :effects/save-many (first save-effect)))
    (is (= (persisted-draft)
           (get path-values [:portfolio :optimizer :draft])))
    (is (= {:status :saved :at-ms 1700000000000}
           (get path-values [:portfolio :optimizer :draft-persist])))
    (is (= [:effects/load-portfolio-optimizer-history] history-effect))))

(deftest hydrate-never-clobbers-a-touched-draft-test
  (is (= [] (actions/hydrate-portfolio-optimizer-draft
             {:portfolio {:optimizer {:draft {:universe [btc-instrument]}}}}
             (draft-record)))))

(deftest hydrate-rejects-malformed-records-test
  (let [untouched {:portfolio {:optimizer {:draft nil}}}]
    (is (= [] (actions/hydrate-portfolio-optimizer-draft untouched nil)))
    (is (= [] (actions/hydrate-portfolio-optimizer-draft untouched {:draft "nope"})))
    (is (= [] (actions/hydrate-portfolio-optimizer-draft
               untouched
               (draft-record {:version 99}))))
    (is (= [] (actions/hydrate-portfolio-optimizer-draft
               untouched
               (draft-record {:draft {:universe [btc-instrument]}}))))
    (is (= [] (actions/hydrate-portfolio-optimizer-draft
               untouched
               (draft-record {:draft (assoc-in (persisted-draft)
                                                [:objective :kind]
                                                :unknown)}))))
    (is (= [] (actions/hydrate-portfolio-optimizer-draft
               untouched
               (draft-record {:draft (assoc (persisted-draft)
                                            :history-assumptions
                                            {" " {:behavior :conservative}})}))))))

(deftest hydrate-skips-history-load-for-an-empty-universe-test
  (let [record (draft-record {:draft (optimizer-defaults/default-draft)})
        cleared (assoc-in (optimizer-defaults/default-draft)
                          [:metadata :universe-source]
                          {:kind :custom})
        effects (actions/hydrate-portfolio-optimizer-draft
                 {:portfolio {:optimizer {:draft nil}}}
                 (assoc record :draft cleared))]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (ffirst effects)))))
