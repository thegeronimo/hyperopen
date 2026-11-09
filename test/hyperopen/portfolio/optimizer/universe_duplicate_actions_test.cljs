(ns hyperopen.portfolio.optimizer.universe-duplicate-actions-test
  "The same-stock guard on the Universe panel's add: a second listing of a held
  underlying asks first, and the confirm's swap / both / keep do what they say."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.actions :as actions]))

(def ^:private prompt-path [:portfolio-ui :optimizer :universe-duplicate-prompt])
(def ^:private acknowledged-path [:portfolio-ui :optimizer :universe-duplicate-acknowledged])
(def ^:private universe-path [:portfolio :optimizer :draft :universe])

(defn- hip3-perp
  [dex base quote]
  {:key (str "perp:" dex ":" base) :market-type :perp :coin (str dex ":" base)
   :dex dex :symbol (str base "-" quote) :base base :quote quote})

(def ^:private catalog
  (into {} (map (juxt :key identity))
        [(hip3-perp "xyz" "NVDA" "USDC")
         (hip3-perp "flx" "NVDA" "USDH")
         (hip3-perp "cash" "NVDA" "USDT0")
         (hip3-perp "xyz" "NVDL" "USDC")
         {:key "perp:SOL" :market-type :perp :coin "SOL" :symbol "SOL-USDC"
          :base "SOL" :quote "USDC"}]))

(defn- state
  ([] (state {}))
  ([ui]
   {:asset-selector {:market-by-key catalog}
    :portfolio-ui {:optimizer ui}
    :portfolio {:optimizer
                {:draft {:universe [{:instrument-id "perp:SOL" :market-type :perp
                                     :coin "SOL" :symbol "SOL-USDC" :base "SOL"
                                     :position-side :long}
                                    {:instrument-id "perp:xyz:NVDA" :market-type :perp
                                     :coin "xyz:NVDA" :dex "xyz" :symbol "NVDA-USDC"
                                     :base "NVDA" :quote "USDC" :shortable? true
                                     :position-side :short}
                                    {:instrument-id "perp:ETH" :market-type :perp
                                     :coin "ETH" :position-side :long}]
                         :constraints {:long-only? false}}}}}))

(defn- saved
  [effects path]
  (some (fn [effect]
          (case (first effect)
            :effects/save (when (= path (second effect)) (nth effect 2))
            :effects/save-many (some (fn [[p v]] (when (= path p) v)) (second effect))
            nil))
        effects))

(defn- universe-ids
  [effects]
  (mapv :instrument-id (saved effects universe-path)))

(deftest plain-add-of-a-second-listing-opens-the-confirm-test
  (is (= [[:effects/save prompt-path "perp:flx:NVDA"]]
         (actions/add-portfolio-optimizer-universe-instrument (state) "perp:flx:NVDA")))
  (testing "a different stock adds straight away"
    (is (= ["perp:SOL" "perp:xyz:NVDA" "perp:ETH" "perp:xyz:NVDL"]
           (universe-ids (actions/add-portfolio-optimizer-universe-instrument
                          (state) "perp:xyz:NVDL")))))
  (testing "once acknowledged for NVDA, another NVDA listing adds without asking"
    (is (= ["perp:SOL" "perp:xyz:NVDA" "perp:ETH" "perp:cash:NVDA"]
           (universe-ids (actions/add-portfolio-optimizer-universe-instrument
                          (state {:universe-duplicate-acknowledged ["NVDA"]})
                          "perp:cash:NVDA"))))))

(deftest swap-replaces-in-place-and-keeps-the-side-test
  (let [effects (actions/add-portfolio-optimizer-universe-instrument
                 (state {:universe-duplicate-prompt "perp:flx:NVDA"})
                 "perp:flx:NVDA" "swap")
        universe (saved effects universe-path)]
    (is (= ["perp:SOL" "perp:flx:NVDA" "perp:ETH"] (mapv :instrument-id universe)))
    (is (= :short (:position-side (second universe))))
    (is (nil? (saved effects prompt-path)))
    (is (= 1 (count (filter #(= :effects/save-many (first %)) effects)))
        "one coalesced save, so the swap lands as a single state change")))

(deftest both-adds-and-stops-asking-for-that-stock-test
  (let [effects (actions/add-portfolio-optimizer-universe-instrument
                 (state {:universe-duplicate-prompt "perp:flx:NVDA"})
                 "perp:flx:NVDA" "both")]
    (is (= ["perp:SOL" "perp:xyz:NVDA" "perp:ETH" "perp:flx:NVDA"] (universe-ids effects)))
    (is (= ["NVDA"] (saved effects acknowledged-path)))
    (is (nil? (saved effects prompt-path)))))

(deftest keep-only-closes-the-confirm-test
  (is (= [[:effects/save prompt-path nil]]
         (actions/add-portfolio-optimizer-universe-instrument
          (state {:universe-duplicate-prompt "perp:flx:NVDA"})
          "perp:flx:NVDA" "keep"))))

(deftest keyboard-answers-an-open-confirm-test
  (let [open (state {:universe-duplicate-prompt "perp:flx:NVDA"
                     :universe-search-active-index 0})]
    (testing "Enter takes the default, swap"
      (is (= ["perp:SOL" "perp:flx:NVDA" "perp:ETH"]
             (universe-ids (actions/handle-portfolio-optimizer-universe-search-keydown
                            open "Enter" ["perp:cash:NVDA"])))))
    (testing "Escape keeps the current listing"
      (is (= [[:effects/save prompt-path nil]]
             (actions/handle-portfolio-optimizer-universe-search-keydown
              open "Escape" ["perp:cash:NVDA"]))))
    (testing "moving the cursor closes it"
      (is (= [:effects/save prompt-path nil]
             (last (actions/handle-portfolio-optimizer-universe-search-keydown
                    open "ArrowDown" ["perp:cash:NVDA" "perp:xyz:NVDL"])))))))

(deftest add-all-skips-same-stock-listings-test
  (let [effects (actions/add-portfolio-optimizer-universe-matches
                 (state)
                 ["perp:flx:NVDA" "perp:xyz:NVDL" "perp:cash:NVDA"])]
    (is (= ["perp:SOL" "perp:xyz:NVDA" "perp:ETH" "perp:xyz:NVDL"] (universe-ids effects)))))

(deftest draft-popover-add-and-run-is-not-guarded-test
  ;; The results-page add-asset popover renders no confirm, so it must add.
  (let [effects (actions/add-portfolio-optimizer-universe-instrument-and-run
                 (state) "perp:flx:NVDA")]
    (is (some #(= "perp:flx:NVDA" %) (universe-ids effects)))))
