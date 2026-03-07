(ns hyperopen.vaults.infrastructure.persistence-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.vaults.infrastructure.persistence :as persistence]))

(deftest read-and-restore-vaults-snapshot-range-normalize-local-storage-preference-test
  (with-redefs [platform/local-storage-get (fn [_] "allTime")]
    (is (= :all-time (persistence/read-vaults-snapshot-range))))
  (with-redefs [platform/local-storage-get (fn [_] "not-a-range")]
    (is (= :month (persistence/read-vaults-snapshot-range))))
  (let [store (atom {:vaults-ui {:snapshot-range :month}})]
    (with-redefs [platform/local-storage-get (fn [_] "allTime")]
      (persistence/restore-vaults-snapshot-range! store))
    (is (= :all-time (get-in @store [:vaults-ui :snapshot-range])))))

(deftest snapshot-range-save-effect-uses-stable-storage-key-test
  (is (= [:effects/local-storage-set "vaults-snapshot-range" "week"]
         (persistence/snapshot-range-save-effect :week))))
