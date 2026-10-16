(ns hyperopen.views.active-asset.icon-button-test
  (:require [cljs.test :refer-macros [deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.system :as app-system]
            [hyperopen.views.active-asset.icon-button :as icon-button]
            [hyperopen.views.active-asset.test-support :as support]))

(deftest asset-icon-spot-includes-chevron-test
  (let [spot-market {:key "spot:@1"
                     :coin "@1"
                     :symbol "HYPE/USDC"
                     :base "HYPE"
                     :market-type :spot}
        icon-node (icon-button/asset-button spot-market false #{} #{})
        img-attrs (->> (support/find-img-nodes icon-node)
                       (map second))
        img-srcs (->> img-attrs
                      (map :src)
                      (remove nil?)
                      set)
        img-classes (->> img-attrs
                         (mapcat #(support/class-values (:class %)))
                         set)
        icon-layer (first (support/find-nodes-with-style-key icon-node :background-image))
        background-image (get-in icon-layer [1 :style :background-image])
        strings (set (support/collect-strings icon-node))
        path-ds (set (support/collect-path-ds icon-node))]
    (is (= :button (first icon-node)))
    (is (not (contains? strings "HYPE")))
    (is (contains? img-srcs "https://app.hyperliquid.xyz/coins/HYPE_spot.svg"))
    (is (not (contains? img-classes "bg-white")))
    (is (contains? img-classes "opacity-0"))
    (is (= "url('https://app.hyperliquid.xyz/coins/HYPE_spot.svg')"
           background-image))
    (is (contains? path-ds "M19 9l-7 7-7-7"))))

(deftest active-asset-trigger-does-not-apply-hover-highlight-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp}
        icon-node (icon-button/asset-button market false #{} #{})
        button-classes (set (support/class-values (get-in icon-node [1 :class])))]
    (is (not (contains? button-classes "hover:bg-base-300")))))

(deftest active-asset-trigger-applies-outcome-hover-glow-when-requested-test
  (let [market {:key "outcome:#0"
                :coin "#0"
                :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                :market-type :outcome}
        icon-node (icon-button/asset-button market false #{} #{} {:outcome-hover-glow? true})
        button-classes (set (support/class-values (get-in icon-node [1 :class])))]
    (is (contains? button-classes "border-transparent"))
    (is (contains? button-classes "px-2"))
    (is (contains? button-classes "py-1.5"))
    (is (contains? button-classes "group-hover/outcome-name:border-[#2dd4bf]/35"))
    (is (contains? button-classes "group-hover/outcome-name:shadow-[0_0_14px_rgba(45,212,191,0.14)]"))))

(deftest asset-icon-renders-neutral-surface-while-probing-and-registers-render-hook-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp}
        icon-node (icon-button/asset-button market false #{} #{})
        img-node (first (support/find-img-nodes icon-node))
        attrs (second img-node)
        classes (set (support/class-values (:class attrs)))
        icon-layer (first (support/find-nodes-with-style-key icon-node :background-image))
        background-image (get-in icon-layer [1 :style :background-image])
        on-map (:on attrs)
        strings (set (support/collect-strings icon-node))]
    (is (some? img-node))
    (is (not (contains? strings "BTC")))
    (is (not (contains? classes "bg-white")))
    (is (contains? classes "opacity-0"))
    (is (= "url('https://app.hyperliquid.xyz/coins/BTC.svg')"
           background-image))
    (is (= [[:actions/mark-loaded-asset-icon "perp:BTC"]]
           (:load on-map)))
    (is (= [[:actions/mark-missing-asset-icon "perp:BTC"]]
           (:error on-map)))
    (is (fn? (:replicant/on-render attrs)))))

(deftest asset-icon-probe-hook-dispatches-loaded-for-complete-images-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp}
        icon-node (icon-button/asset-button market false #{} #{})
        probe-attrs (-> icon-node support/find-img-nodes first second)
        probe-keys (vec (keys probe-attrs))
        on-render (:replicant/on-render probe-attrs)
        remembered (atom nil)
        dispatched (atom [])
        store (atom {:asset-selector {:loaded-icons #{}
                                      :missing-icons #{}}})
        {node :node listeners :listeners} (support/fake-image-node {:complete? true
                                                                    :natural-width 48})]
    (with-redefs [app-system/store store
                  nxr/dispatch (fn [_ _ actions]
                                 (reset! dispatched actions))]
      (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                  :replicant/node node
                  :replicant/remember (fn [memory]
                                        (reset! remembered memory))}))
    (is (= [[:actions/mark-loaded-asset-icon "perp:BTC"]]
           @dispatched))
    (is (= #{} (get-in @store [:asset-selector :loaded-icons])))
    (is (= #{} (get-in @store [:asset-selector :missing-icons])))
    (is (some? (:status* @remembered)))
    (is (< (.indexOf probe-keys :on)
           (.indexOf probe-keys :src)))
    (is (empty? @listeners))))

(deftest asset-icon-probe-hook-dispatches-missing-for-complete-broken-images-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp}
        icon-node (icon-button/asset-button market false #{} #{})
        probe-attrs (-> icon-node support/find-img-nodes first second)
        probe-keys (vec (keys probe-attrs))
        on-render (:replicant/on-render probe-attrs)
        dispatched (atom [])
        store (atom {:asset-selector {:loaded-icons #{}
                                      :missing-icons #{}}})
        {node :node} (support/fake-image-node {:complete? true
                                               :natural-width 0})]
    (with-redefs [app-system/store store
                  nxr/dispatch (fn [_ _ actions]
                                 (reset! dispatched actions))]
      (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                  :replicant/node node
                  :replicant/remember (fn [_] nil)}))
    (is (= [[:actions/mark-missing-asset-icon "perp:BTC"]]
           @dispatched))
    (is (= #{} (get-in @store [:asset-selector :loaded-icons])))
    (is (= #{} (get-in @store [:asset-selector :missing-icons])))
    (is (< (.indexOf probe-keys :on)
           (.indexOf probe-keys :src)))))

(deftest asset-icon-probe-hook-dispatches-loaded-when-update-sees-already-complete-image-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp}
        icon-node (icon-button/asset-button market false #{} #{})
        probe-attrs (-> icon-node support/find-img-nodes first second)
        on-render (:replicant/on-render probe-attrs)
        remembered (atom nil)
        dispatched (atom [])
        store (atom {:asset-selector {:loaded-icons #{}
                                      :missing-icons #{}}})
        {node :node} (support/fake-image-node {:complete? false
                                               :natural-width 0})]
    (with-redefs [app-system/store store
                  nxr/dispatch (fn [_ _ actions]
                                 (swap! dispatched conj actions))]
      (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                  :replicant/node node
                  :replicant/remember (fn [memory]
                                        (reset! remembered memory))})
      (aset node "complete" true)
      (aset node "naturalWidth" 48)
      (on-render {:replicant/life-cycle :replicant.life-cycle/update
                  :replicant/node node
                  :replicant/memory @remembered
                  :replicant/remember (fn [memory]
                                        (reset! remembered memory))}))
    (is (= [[[:actions/mark-loaded-asset-icon "perp:BTC"]]]
           @dispatched))))

(deftest asset-icon-renders-visible-image-when-icon-is-marked-loaded-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp}
        icon-node (icon-button/asset-button market false #{} #{"perp:BTC"})
        img-nodes (support/find-img-nodes icon-node)
        img-node (first img-nodes)
        attrs (second img-node)
        attr-keys (vec (keys attrs))
        classes (set (support/class-values (:class attrs)))
        strings (set (support/collect-strings icon-node))]
    (is (= 1 (count img-nodes)))
    (is (= "https://app.hyperliquid.xyz/coins/BTC.svg"
           (:src attrs)))
    (is (contains? classes "object-contain"))
    (is (not (contains? classes "bg-white")))
    (is (not (contains? classes "opacity-0")))
    (is (not (contains? strings "BTC")))
    (is (< (.indexOf attr-keys :on)
           (.indexOf attr-keys :src)))
    (is (empty? (support/find-nodes-with-style-key icon-node :background-image)))))

(deftest asset-icon-falls-back-to-monogram-when-both-icon-sources-are-missing-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :base "BTC"
                :market-type :perp}
        icon-node (icon-button/asset-button market false #{"perp:BTC" "perp:BTC#entropy"} #{})
        img-nodes (support/find-img-nodes icon-node)
        strings (set (support/collect-strings icon-node))]
    (is (empty? img-nodes))
    (is (contains? strings "BTC"))))

(deftest asset-icon-tries-entropy-logo-after-hyperliquid-icon-is-missing-test
  (let [market {:key "perp:io:PRL"
                :coin "io:PRL"
                :symbol "PRL-USDC"
                :base "PRL"
                :dex "io"
                :market-type :perp}
        entropy-src "https://assets.entropy.trade/logos/io%3APRL.svg"
        probing (icon-button/asset-button market false #{"perp:io:PRL"} #{})
        probe-img (first (support/find-img-nodes probing))
        loaded (icon-button/asset-button market false #{"perp:io:PRL"} #{"perp:io:PRL#entropy"})
        loaded-img (first (support/find-img-nodes loaded))]
    (is (= entropy-src (get-in probe-img [1 :src])))
    (is (= {:load [[:actions/mark-loaded-asset-icon "perp:io:PRL#entropy"]]
            :error [[:actions/mark-missing-asset-icon "perp:io:PRL#entropy"]]}
           (get-in probe-img [1 :on]))
        "the fallback reports under its own status key")
    (is (not (contains? (set (support/collect-strings probing)) "PRL"))
        "no monogram while the fallback is still being tried")
    (is (= entropy-src (get-in loaded-img [1 :src])))))

(deftest icon-source-orders-hyperliquid-then-entropy-then-monogram-test
  (let [perp {:coin "io:PRL" :base "PRL" :dex "io" :market-type :perp}
        spot {:coin "@107" :symbol "HYPE/USDC" :base "HYPE" :market-type :spot}]
    (is (= {:src "https://app.hyperliquid.xyz/coins/io:PRL.svg" :status-key "perp:io:PRL"}
           (icon-button/icon-source perp "perp:io:PRL" #{})))
    (is (= {:src "https://assets.entropy.trade/logos/io%3APRL.svg" :status-key "perp:io:PRL#entropy"}
           (icon-button/icon-source perp "perp:io:PRL" #{"perp:io:PRL"})))
    (is (= {:exhausted? true}
           (icon-button/icon-source perp "perp:io:PRL" #{"perp:io:PRL" "perp:io:PRL#entropy"})))
    (is (= {:exhausted? true} (icon-button/icon-source spot "spot:@107" #{"spot:@107"}))
        "spot icons have no Entropy fallback")))

(deftest asset-icon-probe-reports-again-when-the-node-switches-source-test
  (let [dispatches (atom [])
        memory (atom nil)
        {node :node} (support/fake-image-node {:complete? true :natural-width 0})
        run! (fn [key src]
               ((icon-button/attach-asset-icon-probe key src)
                {:replicant/life-cycle :replicant.life-cycle/update
                 :replicant/node node
                 :replicant/memory @memory
                 :replicant/remember #(reset! memory %)}))]
    (with-redefs [nxr/dispatch (fn [_ _ actions] (swap! dispatches into actions))
                  app-system/store (atom {})]
      (run! "perp:io:PRL" "https://app.hyperliquid.xyz/coins/io:PRL.svg")
      (run! "perp:io:PRL#entropy" "https://assets.entropy.trade/logos/io%3APRL.svg"))
    (is (= [[:actions/mark-missing-asset-icon "perp:io:PRL"]
            [:actions/mark-missing-asset-icon "perp:io:PRL#entropy"]]
           @dispatches))))

(deftest asset-icon-renders-namespaced-icon-for-component-markets-test
  (let [market {:key "perp:xyz:XYZ100"
                :coin "xyz:XYZ100"
                :symbol "XYZ100-USDC"
                :base "XYZ100"
                :dex "xyz"
                :market-type :perp}
        icon-node (icon-button/asset-button market false #{} #{})
        img-node (first (support/find-img-nodes icon-node))
        attrs (second img-node)]
    (is (some? img-node))
    (is (= "https://app.hyperliquid.xyz/coins/xyz:XYZ100.svg"
           (:src attrs)))))

(deftest asset-icon-renders-cross-dex-alias-icon-when-primary-key-missing-test
  (let [market {:key "perp:cash:MSFT"
                :coin "cash:MSFT"
                :symbol "MSFT-USDT0"
                :base "MSFT"
                :dex "cash"
                :market-type :perp}
        icon-node (icon-button/asset-button market false #{} #{})
        img-node (first (support/find-img-nodes icon-node))
        attrs (second img-node)]
    (is (some? img-node))
    (is (= "https://app.hyperliquid.xyz/coins/xyz:MSFT.svg"
           (:src attrs)))))

(deftest asset-icon-renders-underlying-icon-for-outcome-markets-test
  (let [market {:key "outcome:0"
                :coin "#0"
                :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                :title "BTC above 78213 on May 3 at 2:00 AM?"
                :base "BTC"
                :underlying "BTC"
                :market-type :outcome}
        icon-node (icon-button/asset-button market false #{} #{})
        img-node (first (support/find-img-nodes icon-node))
        attrs (second img-node)
        icon-layer (first (support/find-nodes-with-style-key icon-node :background-image))
        background-image (get-in icon-layer [1 :style :background-image])]
    (is (some? img-node))
    (is (= "https://app.hyperliquid.xyz/coins/BTC.svg"
           (:src attrs)))
    (is (= "url('https://app.hyperliquid.xyz/coins/BTC.svg')"
           background-image))))
