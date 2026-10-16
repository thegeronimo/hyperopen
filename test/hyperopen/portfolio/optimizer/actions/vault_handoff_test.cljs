(ns hyperopen.portfolio.optimizer.actions.vault-handoff-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.actions.vault-handoff :as vault-handoff]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]))

(def addr-a "0x1000000000000000000000000000000000000001")
(def addr-b "0x2000000000000000000000000000000000000002")
(def addr-child "0x3000000000000000000000000000000000000003")

(defn- state
  []
  {:vaults {:merged-index-rows [{:vault-address addr-a :name "Alpha" :tvl 500000
                                 :relationship {:type :normal}}
                                {:vault-address addr-b :name "Beta" :tvl 200000
                                 :relationship {:type :normal}}
                                {:vault-address addr-child :name "Child" :tvl 1
                                 :relationship {:type :child}}]}
   :portfolio {:optimizer {:draft (assoc (optimizer-defaults/default-draft)
                                         :name "Old draft"
                                         :universe [{:instrument-id "perp:BTC"}])
                           :active-scenario {:loaded-id "scn-1" :status :loaded}}}})

(defn- saved-paths
  [effects]
  (into {} (second (first effects))))

(deftest vault-instruments-resolve-through-the-index-test
  (let [instruments (vault-handoff/vault-instruments (state) [(.toUpperCase addr-b) addr-a addr-child "0xunknown" addr-a])]
    (is (= [(str "vault:" addr-b) (str "vault:" addr-a)] (mapv :instrument-id instruments))
        "order kept, child/unknown/duplicate vaults skipped, addresses case-folded")
    (is (every? #(= :vault (:market-type %)) instruments))
    (is (every? #(false? (:shortable? %)) instruments))
    (is (= addr-a (:vault-address (second instruments))))))

(deftest seed-replaces-the-working-draft-test
  (let [effects (vault-handoff/seed-portfolio-optimizer-from-vaults
                 (state)
                 {:addresses [addr-a addr-b]
                  :name "Vaults · Calm"
                  :source-label "Calm"
                  :view-id "view-1"
                  :objective :equal-risk
                  :vault-allocation? true})
        paths (saved-paths effects)
        draft (get paths contracts/draft-path)]
    (is (= :effects/save-many (ffirst effects)))
    (is (= "Vaults · Calm" (:name draft)))
    (is (= [(str "vault:" addr-a) (str "vault:" addr-b)] (mapv :instrument-id (:universe draft))))
    (is (= {:kind :equal-risk} (:objective draft)))
    (is (= {:kind :vault-view :label "Calm" :count 2 :view-id "view-1"}
           (get-in draft [:metadata :universe-source])))
    (testing "Vault allocation rules"
      (is (= {:long-only? true :gross-max 1.0 :net-min 1.0 :net-max 1.0 :max-asset-weight 0.25}
             (select-keys (:constraints draft) [:long-only? :gross-max :net-min :net-max :max-asset-weight]))))
    (is (nil? (get-in paths [contracts/active-scenario-path :loaded-id])) "a loaded scenario is detached")
    (is (true? (get paths contracts/draft-dirty-path)))
    (is (not (optimizer-defaults/untouched-draft? draft))
        "the route's draft restore only fills an untouched draft, so it cannot clobber this one")
    (is (some #(= :effects/save-many (first %)) effects))))

(deftest seed-keeps-defaults-without-vault-allocation-test
  (let [draft (get (saved-paths (vault-handoff/seed-portfolio-optimizer-from-vaults
                                 (state) {:addresses [addr-a] :objective :bogus}))
                   contracts/draft-path)]
    (is (= 2.0 (get-in draft [:constraints :gross-max])))
    (is (false? (get-in draft [:constraints :long-only?])))
    (is (= {:kind :equal-risk} (:objective draft)) "unknown goals fall back to Equal risk")))

(deftest seed-is-a-no-op-without-resolvable-vaults-test
  (is (= [] (vault-handoff/seed-portfolio-optimizer-from-vaults (state) {:addresses ["0xnope"]}))))

(deftest seed-caps-the-universe-test
  (let [rows (mapv (fn [i] {:vault-address (str "0x" i) :name (str "V" i) :tvl i :relationship {:type :normal}})
                   (range 60))
        draft (get (saved-paths (vault-handoff/seed-portfolio-optimizer-from-vaults
                                 {:vaults {:merged-index-rows rows}}
                                 {:addresses (mapv #(str "0x" %) (range 60))}))
                   contracts/draft-path)]
    (is (= vault-handoff/max-vaults (count (:universe draft))))))
