(ns hyperopen.views.footer-build-id-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.footer-view :as footer-view]))

(defn- find-node [pred node]
  (cond
    (vector? node)
    (or (when (pred node) node)
        (some #(find-node pred %) (rest node)))

    (seq? node)
    (some #(find-node pred %) node)

    :else nil))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (rest node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- node-text [node]
  (str/join " " (collect-strings node)))

(defn- find-node-by-data-role
  [node data-role]
  (find-node #(and (vector? %)
                   (= data-role (get-in % [1 :data-role])))
             node))

(defn- with-global-property
  [property value f]
  (let [descriptor (js/Object.getOwnPropertyDescriptor js/globalThis property)
        had-property? (some? descriptor)]
    (js/Object.defineProperty js/globalThis
                              property
                              #js {:value value
                                   :configurable true
                                   :writable true})
    (try
      (f)
      (finally
        (if had-property?
          (js/Object.defineProperty js/globalThis property descriptor)
          (js/Reflect.deleteProperty js/globalThis property))))))

(defn- base-state []
  {:websocket {:health {:generated-at-ms 10000
                        :transport {:state :connected
                                    :freshness :live
                                    :last-recv-at-ms 9500
                                    :expected-traffic? true}
                        :groups {:orders_oms {:worst-status :idle}
                                 :market_data {:worst-status :live}
                                 :account {:worst-status :n-a}}
                        :streams {}}}
   :websocket-ui {:diagnostics-open? false}})

(defn- with-global-build-id
  [build-id f]
  (with-global-property "HYPEROPEN_BUILD_ID" build-id f))

(defn- with-global-build
  [build f]
  (with-global-property "HYPEROPEN_BUILD" build f))

(deftest footer-renders-short-build-id-when-global-build-id-is-present-test
  (with-global-build-id
    "999fe1a1234567890"
    (fn []
      (let [view (footer-view/footer-view (base-state))
            utility-links (find-node-by-data-role view "footer-utility-links")
            build-id-node (find-node-by-data-role utility-links "footer-build-id")
            tooltip-node (find-node-by-data-role utility-links "footer-build-id-tooltip")
            sha-node (find-node-by-data-role tooltip-node "footer-build-sha")
            copy-node (find-node-by-data-role tooltip-node "footer-build-copy")
            commit-link-node (find-node-by-data-role tooltip-node "footer-build-commit-link")]
        (is (some? utility-links))
        (is (some? build-id-node))
        (is (some? tooltip-node))
        (is (= "999fe1a" (node-text build-id-node)))
        (is (nil? (get-in build-id-node [1 :title])))
        (is (= "tooltip" (get-in tooltip-node [1 :role])))
        (is (= "999fe1a1234567890" (get-in sha-node [1 :title])))
        (is (= "Copy build info" (get-in copy-node [1 :aria-label])))
        (is (nil? commit-link-node))
        (is (str/includes? (node-text tooltip-node) "dev"))
        (is (str/includes? (node-text tooltip-node) "DEPLOYED"))))))

(deftest footer-renders-condensed-build-popover-from-build-metadata-test
  (with-global-build
    #js {:sha "f18fbc2a3b00e4e324b39796ffdc1a9cd9cff7e619c"
         :short "f18fbc2"
         :branch "main"
         :message "perf(portfolio): memoize returns chart series"
         :deployedAt "2026-04-17T14:28:00.000Z"
         :env "prod"
         :region "global"}
    (fn []
      (let [view (footer-view/footer-view (base-state))
            utility-links (find-node-by-data-role view "footer-utility-links")
            build-id-node (find-node-by-data-role utility-links "footer-build-id")
            tooltip-node (find-node-by-data-role utility-links "footer-build-id-tooltip")
            env-node (find-node-by-data-role tooltip-node "footer-build-env")
            sha-node (find-node-by-data-role tooltip-node "footer-build-sha")
            deployed-node (find-node-by-data-role tooltip-node "footer-build-deployed")
            copy-node (find-node-by-data-role tooltip-node "footer-build-copy")
            commit-link-node (find-node-by-data-role tooltip-node "footer-build-commit-link")]
        (is (= "f18fbc2" (node-text build-id-node)))
        (is (= "footer-build-popover-f18fbc2" (get-in tooltip-node [1 :id])))
        (is (= "footer-build-popover-f18fbc2" (get-in build-id-node [1 :aria-describedby])))
        (is (= "prod" (str/lower-case (node-text env-node))))
        (is (= "f18fbc2a3b00e4e324b39796ffdc1a9cd9cff7e619c" (get-in sha-node [1 :title])))
        (is (str/includes? (node-text deployed-node) "DEPLOYED"))
        (is (= "Copy build info" (get-in copy-node [1 :aria-label])))
        (is (str/includes? (get-in copy-node [1 :data-copy-payload])
                           "Build: f18fbc2a3b00e4e324b39796ffdc1a9cd9cff7e619c"))
        (is (str/includes? (get-in copy-node [1 :data-copy-payload])
                           "Env: prod (global)"))
        (is (str/includes? (get-in copy-node [1 :data-copy-payload])
                           "Message: perf(portfolio): memoize returns chart series"))
        (is (nil? commit-link-node))))))
