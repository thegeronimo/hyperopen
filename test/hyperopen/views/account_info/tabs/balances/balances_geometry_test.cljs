(ns hyperopen.views.account-info.tabs.balances.balances-geometry-test
  "The desktop Balances grid's width budget on /trade at 1280x800, where the
   account panel is 960 px wide and narrower than the table: every track up
   to and including Transfer must fit inside the panel at its minimum, so a
   row's moves are never cut off by the panel's edge (the header sits
   outside the rows' sideways scroller, so scrolling to them misaligns the
   columns)."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.account-info.tabs.balances.desktop :as balances-desktop]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]))

(def ^:private trade-panel-width-px
  "`[data-parity-id=\"account-tables\"]` on /trade at 1280x800 (Milestone 6
   headless probe)."
  960)

(def ^:private row-padding-px 12)  ; px-3

(def ^:private column-gap-px 16)   ; gap-x-4

(def ^:private transfer-column-index 6)

(def ^:private widest-move-label-px
  "\"To HyperEVM\" at 12 px, font-medium, with room to spare."
  84)

(defn- grid-template
  []
  (some #(when (str/starts-with? % "grid-cols-[") %)
        (get-in (balances-desktop/balance-row {:key "spot-0" :coin "USDC (Spot)"}) [1 :class])))

(defn- track-minimums
  "Each track's minimum in px, reading `var(--balances-coin-min,84px)` as
   `coin-min-px`."
  [template coin-min-px]
  (->> (str/split (subs template (count "grid-cols-[") (dec (count template))) #"_")
       (mapv (fn [track]
               (if (str/includes? track "--balances-coin-min")
                 coin-min-px
                 (js/parseInt (second (re-find #"minmax\((\d+)px" track)) 10))))))

(defn- chip-coin-min-px
  "The Coin minimum the Balances tab sets while place chips show."
  []
  (let [content (account-info-view/account-info-panel (fixture/state))
        root (hiccup/find-first-node content #(some? (get-in % [1 :style :--balances-coin-min])))]
    (js/parseInt (get-in root [1 :style :--balances-coin-min]) 10)))

(deftest transfer-column-fits-the-trade-panel-at-1280-test
  (let [template (grid-template)
        coin-min (chip-coin-min-px)
        mins (track-minimums template coin-min)
        transfer-start (+ row-padding-px
                          (reduce + (subvec mins 0 transfer-column-index))
                          (* transfer-column-index column-gap-px))
        transfer-end (+ transfer-start (nth mins transfer-column-index))]
    (is (= 9 (count mins)) "nine columns")
    (is (= 116 coin-min) "the tab widens the Coin track while place chips show")
    (is (<= transfer-end trade-panel-width-px)
        (str "the Transfer column ends at " transfer-end " px, past the " trade-panel-width-px
             " px /trade panel"))
    (is (>= (nth mins transfer-column-index) widest-move-label-px)
        "each move still fits its column on its own line")))
