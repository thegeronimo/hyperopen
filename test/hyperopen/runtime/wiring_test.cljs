(ns hyperopen.runtime.wiring-test
  (:require [clojure.set :as set]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.schema.runtime-registration-catalog :as runtime-registration-catalog]
            [hyperopen.runtime.wiring :as wiring]))

(defn- flatten-leaf-keys
  [node]
  (reduce-kv (fn [acc k v]
               (if (map? v)
                 (into acc (flatten-leaf-keys v))
                 (conj acc k)))
             #{}
             (or node {})))

(deftest runtime-effect-deps-uses-extracted-effect-adapter-overrides-test
  (let [deps (wiring/runtime-effect-deps)]
    (is (identical? effect-adapters/save
                    (get-in deps [:storage :save])))
    (is (identical? effect-adapters/persist-leaderboard-preferences-effect
                    (get-in deps [:storage :persist-leaderboard-preferences])))
    (is (identical? effect-adapters/sync-asset-selector-active-ctx-subscriptions
                    (get-in deps [:asset-selector :sync-asset-selector-active-ctx-subscriptions])))
    (is (identical? effect-adapters/load-trading-indicators-module-effect
                    (get-in deps [:navigation :load-trading-indicators-module])))
    (is (fn? (get-in deps [:navigation :load-surface-module])))
    (is (identical? effect-adapters/replace-shareable-route-query
                    (get-in deps [:navigation :replace-shareable-route-query])))
    (is (identical? effect-adapters/fetch-candle-snapshot
                    (get-in deps [:websocket :fetch-candle-snapshot])))
    (is (identical? effect-adapters/ws-reset-subscriptions
                    (get-in deps [:diagnostics :ws-reset-subscriptions])))
    (is (identical? effect-adapters/api-fetch-predicted-fundings-effect
                    (get-in deps [:api :api-fetch-predicted-fundings])))
    (is (identical? effect-adapters/api-fetch-leaderboard-effect
                    (get-in deps [:api :api-fetch-leaderboard])))
    (is (identical? effect-adapters/api-fetch-vault-index-effect
                    (get-in deps [:api :api-fetch-vault-index])))
    (is (identical? effect-adapters/api-fetch-vault-index-with-cache-effect
                    (get-in deps [:api :api-fetch-vault-index-with-cache])))
    (is (identical? effect-adapters/api-fetch-vault-ledger-updates-effect
                    (get-in deps [:api :api-fetch-vault-ledger-updates])))
    (is (identical? effect-adapters/api-fetch-staking-validator-summaries-effect
                    (get-in deps [:api :api-fetch-staking-validator-summaries])))
    (is (identical? effect-adapters/run-portfolio-optimizer-effect
                    (get-in deps [:portfolio-optimizer :run-portfolio-optimizer])))
    (is (identical? effect-adapters/load-portfolio-optimizer-history-effect
                    (get-in deps [:portfolio-optimizer :load-portfolio-optimizer-history])))
    (is (identical? effect-adapters/load-portfolio-optimizer-scenario-index-effect
                    (get-in deps [:portfolio-optimizer :load-portfolio-optimizer-scenario-index])))
    (is (identical? effect-adapters/load-portfolio-optimizer-scenario-effect
                    (get-in deps [:portfolio-optimizer :load-portfolio-optimizer-scenario])))
    (is (identical? effect-adapters/archive-portfolio-optimizer-scenario-effect
                    (get-in deps [:portfolio-optimizer :archive-portfolio-optimizer-scenario])))
    (is (identical? effect-adapters/duplicate-portfolio-optimizer-scenario-effect
                    (get-in deps [:portfolio-optimizer :duplicate-portfolio-optimizer-scenario])))
    (is (identical? effect-adapters/save-portfolio-optimizer-scenario-effect
                    (get-in deps [:portfolio-optimizer :save-portfolio-optimizer-scenario])))
    (is (identical? action-adapters/enable-agent-trading
                    (get-in deps [:wallet :enable-agent-trading])))))

(deftest runtime-action-deps-uses-extracted-action-adapter-overrides-test
  (let [deps (wiring/runtime-action-deps)]
    (is (identical? action-adapters/init-websockets
                    (get-in deps [:core :init-websockets])))
    (is (identical? action-adapters/reconnect-websocket-action
                    (get-in deps [:core :reconnect-websocket-action])))
    (is (identical? action-adapters/refresh-asset-markets
                    (get-in deps [:asset-selector :refresh-asset-markets])))
    (is (identical? action-adapters/load-vault-route-action
                    (get-in deps [:vaults :load-vault-route])))
    (is (identical? action-adapters/load-funding-comparison-route-action
                    (get-in deps [:funding-comparison :load-funding-comparison-route])))
    (is (identical? action-adapters/load-leaderboard-route-action
                    (get-in deps [:leaderboard :load-leaderboard-route])))
    (is (identical? action-adapters/load-staking-route-action
                    (get-in deps [:staking :load-staking-route])))
    (is (identical? action-adapters/navigate
                    (get-in deps [:core :navigate])))
    (is (identical? action-adapters/run-portfolio-optimizer-action
                    (get-in deps [:portfolio-optimizer :run-portfolio-optimizer])))
    (is (identical? action-adapters/set-portfolio-optimizer-objective-kind-action
                    (get-in deps [:portfolio-optimizer :set-portfolio-optimizer-objective-kind])))
    (is (identical? action-adapters/set-portfolio-optimizer-objective-parameter-action
                    (get-in deps [:portfolio-optimizer :set-portfolio-optimizer-objective-parameter])))
    (is (identical? action-adapters/set-portfolio-optimizer-execution-assumption-action
                    (get-in deps [:portfolio-optimizer :set-portfolio-optimizer-execution-assumption])))
    (is (identical? action-adapters/set-portfolio-optimizer-instrument-filter-action
                    (get-in deps [:portfolio-optimizer :set-portfolio-optimizer-instrument-filter])))
    (is (identical? action-adapters/set-portfolio-optimizer-asset-override-action
                    (get-in deps [:portfolio-optimizer :set-portfolio-optimizer-asset-override])))
    (is (identical? action-adapters/set-portfolio-optimizer-universe-search-query-action
                    (get-in deps [:portfolio-optimizer :set-portfolio-optimizer-universe-search-query])))
    (is (identical? action-adapters/add-portfolio-optimizer-universe-instrument-action
                    (get-in deps [:portfolio-optimizer :add-portfolio-optimizer-universe-instrument])))
    (is (identical? action-adapters/remove-portfolio-optimizer-universe-instrument-action
                    (get-in deps [:portfolio-optimizer :remove-portfolio-optimizer-universe-instrument])))
    (is (identical? action-adapters/set-portfolio-optimizer-universe-from-current-action
                    (get-in deps [:portfolio-optimizer :set-portfolio-optimizer-universe-from-current])))
    (is (identical? action-adapters/load-portfolio-optimizer-history-from-draft-action
                    (get-in deps [:portfolio-optimizer :load-portfolio-optimizer-history-from-draft])))
    (is (identical? action-adapters/save-portfolio-optimizer-scenario-from-current-action
                    (get-in deps [:portfolio-optimizer :save-portfolio-optimizer-scenario-from-current])))
    (is (identical? action-adapters/load-portfolio-optimizer-route-action
                    (get-in deps [:portfolio-optimizer :load-portfolio-optimizer-route])))
    (is (identical? action-adapters/archive-portfolio-optimizer-scenario-action
                    (get-in deps [:portfolio-optimizer :archive-portfolio-optimizer-scenario])))
    (is (identical? action-adapters/duplicate-portfolio-optimizer-scenario-action
                    (get-in deps [:portfolio-optimizer :duplicate-portfolio-optimizer-scenario])))
    (is (identical? action-adapters/run-portfolio-optimizer-from-draft-action
                    (get-in deps [:portfolio-optimizer :run-portfolio-optimizer-from-draft])))))

(deftest runtime-registration-deps-builds-effect-and-action-handlers-test
  (let [deps (wiring/runtime-registration-deps)]
    (is (fn? (:register-effects! deps)))
    (is (fn? (:register-actions! deps)))
    (is (fn? (:register-system-state! deps)))
    (is (fn? (:register-placeholders! deps)))
    (is (identical? action-adapters/navigate
                    (get-in deps [:action-handlers :navigate])))
    (is (identical? effect-adapters/save
                    (get-in deps [:effect-handlers :save])))
    (is (fn? (get-in deps [:effect-handlers :load-surface-module])))
    (is (identical? action-adapters/run-portfolio-optimizer-action
                    (get-in deps [:action-handlers :run-portfolio-optimizer])))
    (is (identical? effect-adapters/run-portfolio-optimizer-effect
                    (get-in deps [:effect-handlers :run-portfolio-optimizer])))
    (is (identical? effect-adapters/load-portfolio-optimizer-history-effect
                    (get-in deps [:effect-handlers :load-portfolio-optimizer-history])))
    (is (identical? effect-adapters/load-portfolio-optimizer-scenario-index-effect
                    (get-in deps [:effect-handlers :load-portfolio-optimizer-scenario-index])))
    (is (identical? effect-adapters/load-portfolio-optimizer-scenario-effect
                    (get-in deps [:effect-handlers :load-portfolio-optimizer-scenario])))
    (is (identical? effect-adapters/archive-portfolio-optimizer-scenario-effect
                    (get-in deps [:effect-handlers :archive-portfolio-optimizer-scenario])))
    (is (identical? effect-adapters/duplicate-portfolio-optimizer-scenario-effect
                    (get-in deps [:effect-handlers :duplicate-portfolio-optimizer-scenario])))
    (is (identical? effect-adapters/save-portfolio-optimizer-scenario-effect
                    (get-in deps [:effect-handlers :save-portfolio-optimizer-scenario])))))

(deftest runtime-action-deps-cover-catalog-handler-keys-test
  (let [action-deps (wiring/runtime-action-deps)
        available-handler-keys (flatten-leaf-keys action-deps)
        required-handler-keys (runtime-registration-catalog/action-handler-keys)
        missing (set/difference required-handler-keys available-handler-keys)]
    (is (empty? missing)
        (str "Runtime action deps missing catalog handler keys: "
             (pr-str missing)))))

(deftest runtime-effect-deps-cover-catalog-handler-keys-test
  (let [effect-deps (wiring/runtime-effect-deps)
        available-handler-keys (flatten-leaf-keys effect-deps)
        required-handler-keys (runtime-registration-catalog/effect-handler-keys)
        missing (set/difference required-handler-keys available-handler-keys)]
    (is (empty? missing)
        (str "Runtime effect deps missing catalog handler keys: "
             (pr-str missing)))))
