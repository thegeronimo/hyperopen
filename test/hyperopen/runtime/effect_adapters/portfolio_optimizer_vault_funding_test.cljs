(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-vault-funding-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.application.vault-funding.run :as run]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer-vault-funding :as effect]
            [hyperopen.test-support.async :as async-support]))

(def owner "0x9999999999999999999999999999999999999999")

(defn- row
  [kind address usd]
  (run/step->row {:kind kind :address address :label address :usd usd}))

(defn- store-with
  [rows]
  (atom (-> {:wallet {:address owner :agent {:status :ready}}
             :router {:path "/portfolio/optimize/draft"}}
            (assoc-in contracts/ui-vault-funding-run-path (run/new-run rows "a1" 1 {:owner owner})))))

(defn- harness
  "Fake exchange: `answers` maps vault address → :ok | :err | :throw."
  [answers]
  (let [sent (atom [])
        toasts (atom [])
        refreshed (atom [])
        dispatched (atom [])]
    {:sent sent :toasts toasts :refreshed refreshed :dispatched dispatched
     :bind (fn [f]
             (binding [effect/*submit-vault-transfer!*
                       (fn [_store address action]
                         (swap! sent conj [address action])
                         (case (get answers (:vaultAddress action) :ok)
                           :ok (js/Promise.resolve {:status "ok"})
                           :err (js/Promise.resolve {:status "err" :response "Insufficient balance"})
                           :generic (js/Promise.resolve {:status "err" :response "Vault transfer error"})
                           :throw (js/Promise.reject (js/Error. "network down"))))
                       effect/*now-ms* (fn [] 99)
                       effect/*toast!* (fn [_ kind message] (swap! toasts conj [kind message]))
                       effect/*refresh-vault-equities!* (fn [_ address] (swap! refreshed conj address))
                       effect/*dispatch!* (fn [_ _ actions] (swap! dispatched into actions))]
               (f)))}))

(defn- statuses
  [store]
  (mapv (juxt :row-id :status) (get-in @store (conj contracts/ui-vault-funding-run-path :rows))))

(deftest sends-withdrawals-then-deposits-in-order-test
  (async done
    (let [store (store-with [(row :withdraw "0xb" 30) (row :deposit "0xa" 50) (row :deposit "0xc" 20)])
          {:keys [sent toasts refreshed dispatched bind]} (harness {})]
      (-> (bind #(effect/run-vault-funding-effect nil store))
          (.then (fn [_]
                   (is (= [[owner {:type "vaultTransfer" :vaultAddress "0xb" :isDeposit false :usd 30000000}]
                           [owner {:type "vaultTransfer" :vaultAddress "0xa" :isDeposit true :usd 50000000}]
                           [owner {:type "vaultTransfer" :vaultAddress "0xc" :isDeposit true :usd 20000000}]]
                          @sent)
                       "signed by the owner, one at a time, withdrawal first")
                   (is (= :done (get-in @store (conj contracts/ui-vault-funding-run-path :status))))
                   (is (= [[:success "Vault funding complete: 3 transfers sent."]] @toasts))
                   (is (= [owner] @refreshed) "holdings are re-read past the cache")
                   (is (= [[:actions/load-user-data owner]] @dispatched))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest failed-withdrawal-holds-the-deposits-test
  (async done
    (let [store (store-with [(row :withdraw "0xb" 30) (row :deposit "0xa" 50)])
          {:keys [sent bind]} (harness {"0xb" :err})]
      (-> (bind #(effect/run-vault-funding-effect nil store))
          (.then (fn [_]
                   (is (= ["0xb"] (mapv (comp :vaultAddress second) @sent)) "the deposit is not sent")
                   (is (= [["withdraw:0xb" :failed] ["deposit:0xa" :skipped]] (statuses store)))
                   (is (= "Insufficient balance"
                          (get-in @store (conj contracts/ui-vault-funding-run-path :rows 0 :error))))
                   (is (= :failed (get-in @store (conj contracts/ui-vault-funding-run-path :status))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest broken-off-request-is-unknown-not-failed-test
  (async done
    (let [store (store-with [(row :deposit "0xa" 50) (row :deposit "0xc" 20)])
          {:keys [toasts refreshed bind]} (harness {"0xa" :throw})]
      (-> (bind #(effect/run-vault-funding-effect nil store))
          (.then (fn [_]
                   (is (= [["deposit:0xa" :unknown] ["deposit:0xc" :done]] (statuses store))
                       "it may have landed, so it is never queued for a retry")
                   (is (re-find #"network down" (get-in @store (conj contracts/ui-vault-funding-run-path :rows 0 :error))))
                   (is (= [owner] @refreshed) "holdings are re-read so a re-plan sees what landed")
                   (is (= :partial (get-in @store (conj contracts/ui-vault-funding-run-path :status))))
                   (is (= :error (ffirst @toasts)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest stop-skips-what-has-not-started-test
  (async done
    (let [store (store-with [(row :deposit "0xa" 50) (row :deposit "0xc" 20)])
          {:keys [sent bind]} (harness {})
          stop-after-first (fn [submit!]
                             (fn [s address action]
                               (swap! store assoc-in (conj contracts/ui-vault-funding-run-path :abort-requested?) true)
                               (submit! s address action)))]
      (-> (bind #(binding [effect/*submit-vault-transfer!* (stop-after-first effect/*submit-vault-transfer!*)]
                   (effect/run-vault-funding-effect nil store)))
          (.then (fn [_]
                   (is (= 1 (count @sent)))
                   (is (= [["deposit:0xa" :done] ["deposit:0xc" :skipped]] (statuses store)))
                   (is (false? (get-in @store (conj contracts/ui-vault-funding-run-path :abort-requested?))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest exchange-refusal-is-failed-and-retryable-test
  (async done
    (let [store (store-with [(row :deposit "0xa" 50)])
          {:keys [bind]} (harness {"0xa" :err})]
      (-> (bind #(effect/run-vault-funding-effect nil store))
          (.then (fn [_]
                   (is (= [["deposit:0xa" :failed]] (statuses store)))
                   (is (= 1 (count (run/retryable-rows (get-in @store contracts/ui-vault-funding-run-path)))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest spectating-mid-run-stops-the-rest-test
  (async done
    (let [store (store-with [(row :deposit "0xa" 50) (row :deposit "0xc" 20)])
          {:keys [sent bind]} (harness {})
          spectate-after-first (fn [submit!]
                                 (fn [s address action]
                                   (swap! store assoc-in [:account-context :spectate-mode]
                                          {:active? true :address "0x7777777777777777777777777777777777777777"})
                                   (submit! s address action)))]
      (-> (bind #(binding [effect/*submit-vault-transfer!* (spectate-after-first effect/*submit-vault-transfer!*)]
                   (effect/run-vault-funding-effect nil store)))
          (.then (fn [_]
                   (is (= 1 (count @sent)) "only the transfer already in flight went out")
                   (is (= [["deposit:0xa" :done] ["deposit:0xc" :failed]] (statuses store)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest a-row-is-never-sent-twice-test
  (async done
    (let [store (store-with [(row :deposit "0xa" 50) (row :deposit "0xc" 20)])
          {:keys [sent bind]} (harness {})]
      (-> (bind #(js/Promise.all #js [(effect/run-vault-funding-effect nil store)
                                     (effect/run-vault-funding-effect nil store)]))
          (.then (fn [_]
                   (is (= ["0xa" "0xc"] (sort (mapv (comp :vaultAddress second) @sent)))
                       "two chains on one run send each transfer once")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest another-wallet-never-signs-test
  (async done
    (let [store (store-with [(row :deposit "0xa" 50)])
          {:keys [sent bind]} (harness {})]
      (swap! store assoc-in [:wallet :address] "0x8888888888888888888888888888888888888888")
      (-> (bind #(effect/run-vault-funding-effect nil store))
          (.then (fn [_]
                   (is (empty? @sent))
                   (is (= :failed (get-in @store (conj contracts/ui-vault-funding-run-path :status))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest locked-agent-never-sends-test
  (async done
    (let [store (store-with [(row :deposit "0xa" 50)])
          {:keys [sent bind]} (harness {})]
      (swap! store assoc-in [:wallet :agent :status] :locked)
      (-> (bind #(effect/run-vault-funding-effect nil store))
          (.then (fn [_]
                   (is (empty? @sent))
                   (is (= :failed (get-in @store (conj contracts/ui-vault-funding-run-path :status))))
                   (is (= [["deposit:0xa" :queued]] (statuses store)) "left queued for a retry")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest reasonless-refusal-says-so-test
  (async done
    (let [store (store-with [(row :deposit "0xa" 50)])
          {:keys [bind]} (harness {"0xa" :generic})]
      (-> (bind #(effect/run-vault-funding-effect nil store))
          (.then (fn [_]
                   (is (= [["deposit:0xa" :failed]] (statuses store)) "still a refusal: retryable")
                   (is (re-find #"without giving a reason" (get-in @store (conj contracts/ui-vault-funding-run-path :rows 0 :error))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest fetches-each-vaults-deposit-limit-test
  (async done
    (let [va "0x1111111111111111111111111111111111111111"
          vb "0x2222222222222222222222222222222222222222"
          store (atom (assoc-in {} contracts/last-successful-run-result-path
                                {:status :solved
                                 :instrument-ids [(str "vault:" va) (str "vault:" vb)]
                                 :target-weights [0.5 0.5]}))
          asked (atom [])]
      (-> (binding [effect/*now-ms* (fn [] 7)
                    effect/*request-vault-details!*
                    (fn [address _opts]
                      (swap! asked conj address)
                      (if (= address vb)
                        (js/Promise.reject (js/Error. "down"))
                        (js/Promise.resolve {:leader "0xL" :allow-deposits? true :leader-fraction 0.05
                                             :followers [{:leader? true :vault-equity 100}]})))]
            (effect/fetch-vault-funding-limits-effect nil store))
          (.then (fn [_]
                   (let [limits (get-in @store (conj contracts/ui-vault-funding-path :limits))]
                     (is (= [va vb] @asked) "one at a time")
                     (is (= :ready (:status limits)))
                     (is (zero? (get-in limits [:by-address va :max-deposit])))
                     (is (nil? (get-in limits [:by-address vb])) "a failed read leaves the vault unlimited"))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

