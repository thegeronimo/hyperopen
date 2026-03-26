#!/usr/bin/env bb

(ns dev.formal-tooling-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests testing]]
            [tools.formal.core :as formal]))

(def vault-surface
  {:id "vault-transfer"
   :lean-module "Hyperopen.Formal.VaultTransfer"
   :status "modeled"
   :manifest "generated/vault-transfer.edn"
   :target-source "target/formal/vault-transfer-vectors.cljs"
   :committed-source "test/hyperopen/formal/vault_transfer_vectors.cljs"})

(def bootstrap-surface
  {:id "order-request-standard"
   :lean-module "Hyperopen.Formal.OrderRequest.Standard"
   :status "bootstrap"
   :manifest "generated/order-request-standard.edn"})

(defn delete-recursive!
  [file]
  (when (.exists file)
    (doseq [child (reverse (file-seq file))]
      (.delete child))))

(defn with-temp-root
  [f]
  (let [tmp-path (java.nio.file.Files/createTempDirectory "formal-tooling" (make-array java.nio.file.attribute.FileAttribute 0))
        root (.toFile tmp-path)]
    (try
      (f (.getCanonicalPath root))
      (finally
        (delete-recursive! root)))))

(defn write-file!
  [root relative-path text]
  (let [file (io/file root relative-path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file text)))

(deftest manifest-content-respects-surface-status-test
  (is (= "{:surface \"vault-transfer\" :module \"Hyperopen.Formal.VaultTransfer\" :status \"modeled\"}\n"
         (#'formal/manifest-content vault-surface)))
  (is (= "{:surface \"order-request-standard\" :module \"Hyperopen.Formal.OrderRequest.Standard\" :status \"bootstrap\"}\n"
         (#'formal/manifest-content bootstrap-surface))))

(deftest sync-generated-source-copies-transient-export-into-committed-namespace-test
  (with-temp-root
    (fn [root]
      (write-file! root "target/formal/vault-transfer-vectors.cljs" "generated")
      (with-redefs [tools.formal.core/repo-root (constantly (io/file root))]
        (#'formal/sync-generated-source! vault-surface)
        (is (= "generated"
               (slurp (io/file root "test/hyperopen/formal/vault_transfer_vectors.cljs"))))))))

(deftest verify-generated-source-detects-stale-committed-namespace-test
  (with-temp-root
    (fn [root]
      (write-file! root "target/formal/vault-transfer-vectors.cljs" "generated")
      (write-file! root "test/hyperopen/formal/vault_transfer_vectors.cljs" "stale")
      (with-redefs [tools.formal.core/repo-root (constantly (io/file root))]
        (is (thrown-with-msg?
             Exception
             #"Stale generated source"
             (#'formal/verify-generated-source! vault-surface)))))))

(deftest bootstrap-surface-skips-generated-source-checks-test
  (is (nil? (#'formal/verify-generated-source! bootstrap-surface)))
  (is (nil? (#'formal/sync-generated-source! bootstrap-surface))))

(deftest run-sync-and-verify-support-vault-generated-source-artifacts-test
  (with-temp-root
    (fn [root]
      (write-file! root "target/formal/vault-transfer-vectors.cljs" "generated")
      (write-file! root "test/hyperopen/formal/vault_transfer_vectors.cljs" "generated")
      (with-redefs [tools.formal.core/repo-root (constantly (io/file root))
                    tools.formal.core/ensure-lean-tools! (fn [] nil)
                    tools.formal.core/build-lean-workspace! (fn [] {:exit 0})
                    tools.formal.core/run-lean-entrypoint! (fn [_command _surface-id] {:exit 0})]
        (testing "sync writes manifest and committed source"
          (let [output (with-out-str
                         (formal/run! ["sync" "--surface" "vault-transfer"]))]
            (is (.contains output "Synced vault-transfer"))
            (is (= "generated"
                   (slurp (io/file root "test/hyperopen/formal/vault_transfer_vectors.cljs"))))
            (is (= "{:surface \"vault-transfer\" :module \"Hyperopen.Formal.VaultTransfer\" :status \"modeled\"}\n"
                   (slurp (io/file root "tools/formal/generated/vault-transfer.edn"))))))
        (testing "verify accepts current generated source"
          (let [output (with-out-str
                         (formal/run! ["verify" "--surface" "vault-transfer"]))]
            (is (.contains output "Verified vault-transfer"))))))))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (run-tests 'dev.formal-tooling-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
