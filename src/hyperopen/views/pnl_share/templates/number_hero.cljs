(ns hyperopen.views.pnl-share.templates.number-hero
  "Design direction 1a: editorial whitespace, one enormous figure, the stats as
   a footer rule. It draws no illustration, so every colour comes straight from
   the --ho-* vocabulary and the card looks native under any theme."
  (:require [hyperopen.views.pnl-share.palette :as palette]
            [hyperopen.views.pnl-share.svg :as svg]
            [hyperopen.views.pnl-share.templates.neon-arrow-art :as art]))

(def card-width 1080)
(def card-height 608)
(def ^:private card-radius 18)
(def ^:private pad-x 48)
(def ^:private stat-pitch 140)

(defn- defs
  [colours monogram-pair]
  [:defs
   [:clipPath {:id "ho-pnl-hero-clip"}
    [:rect {:x 0 :y 0 :width card-width :height card-height :rx card-radius}]]
   [:pattern {:id "ho-pnl-hero-dots"
              :x 0 :y 0 :width 16 :height 16
              :patternUnits "userSpaceOnUse"}
    [:circle {:cx 8 :cy 8 :r 1 :fill (:dot-field colours)}]]
   (svg/radial-wash "ho-pnl-hero-wash"
                    {:cx 842 :cy 316 :rx 626 :ry 353
                     :inner (:dot-wash colours)
                     :outer (:dot-wash-far colours)})
   (art/monogram-gradient-def monogram-pair)])

(defn- header
  [{:keys [handle-text timestamp-text]} colours]
  (let [text-x pad-x
        split-x (+ text-x (svg/text-width "hyper" 19))]
    [:g
     (svg/text {:x text-x :y 67 :size 19 :weight 500 :fill (:text-hi colours)} "hyper")
     (svg/text {:x split-x :y 67 :size 19 :weight 500 :fill (:accent colours)} "open")
     (when handle-text
       (svg/text {:x (- card-width pad-x) :y 62 :size 16 :anchor "end"
                  :fill (:text-body colours)}
                 handle-text))
     (when timestamp-text
       (svg/text {:x (- card-width pad-x) :y 82 :size 13 :anchor "end"
                  :fill (:text-dim colours)}
                 timestamp-text))]))

(defn- coin-row
  [{:keys [monogram coin-label dex-label leverage-label icon-data-uri]} colours]
  (let [coin-x 86
        coin-end (+ coin-x (svg/text-width coin-label 21))
        [lev-chip lev-end] (when leverage-label
                             (svg/chip {:x (+ coin-end 14) :y 202 :height 26 :size 13
                                        :pad 10 :tracking 0.08
                                        :fill (:chip-bg colours)
                                        :stroke (:chip-border colours)
                                        :colour (:accent colours)}
                                       leverage-label))
        [dex-chip _] (when dex-label
                       (svg/chip {:x (+ (or lev-end (+ coin-end 14)) 8) :y 202
                                  :height 26 :size 13 :pad 10 :tracking 0.08
                                  :fill (:chip-bg colours)
                                  :stroke (:chip-border colours)
                                  :colour (:accent colours)}
                                 dex-label))]
    [:g
     (svg/coin-disc {:cx 61 :cy 214 :r 13 :letter monogram
                         :gradient-id "ho-pnl-monogram" :label-size 12
                    :clip-id "ho-pnl-hero-coin-clip" :icon-data-uri icon-data-uri
                         :rim (:disc-rim colours) :letter-fill (:disc-letter colours)})
     (svg/text {:x coin-x :y 221 :size 21 :fill (:text-hi colours)} coin-label)
     lev-chip
     dex-chip]))

(defn- hero
  [{:keys [roe-text pnl-text roe-label-compact summary-line]} colours]
  (let [figure (or roe-text "--")
        figure-width (svg/text-width figure 168 -0.055)
        side-x (+ pad-x figure-width 40)]
    [:g
     (svg/text {:x pad-x :y 392 :size 168 :weight 500 :tracking -0.055
                :fill (:accent colours)}
               figure)
     (when pnl-text
       [:g
        (svg/text {:x side-x :y 356 :size 12 :tracking 0.14 :fill (:text-dim colours)}
                  roe-label-compact)
        (svg/text {:x side-x :y 388 :size 30 :fill (:accent-bright colours)}
                  pnl-text)])
     (when summary-line
       (svg/text {:x pad-x :y 430 :size 16 :fill (:text-mid colours)} summary-line))]))

(defn stat-cells
  "The footer stats, in a fixed order, skipping any the card has no honest value
   for. Returns [label value] pairs so the layout and the tests agree on which
   cells exist."
  [{:keys [entry-price-text mark-price-text size-text held-text funding-text]}]
  (cond-> []
    entry-price-text (conj ["ENTRY" entry-price-text])
    mark-price-text (conj ["MARK" mark-price-text])
    size-text (conj ["SIZE" size-text])
    held-text (conj ["HELD" held-text])
    funding-text (conj ["FUNDING" funding-text])))

(defn- footer
  [{:keys [join-label] :as card} colours]
  (into [:g
         (svg/hairline {:x1 pad-x :x2 (- card-width pad-x) :y 480
                        :stroke (:divider colours)})
         (svg/text {:x (- card-width pad-x) :y 506 :size 12 :tracking 0.12
                    :anchor "end" :fill (:text-dim colours)}
                   "TRADE IT YOURSELF")
         (svg/text {:x (- card-width pad-x) :y 532 :size 16 :anchor "end"
                    :fill (:accent colours)}
                   join-label)]
        (map-indexed
         (fn [index [label value]]
           (let [x (+ pad-x (* index stat-pitch))]
             [:g
              (svg/text {:x x :y 506 :size 12 :tracking 0.12 :fill (:text-dim colours)}
                        label)
              (svg/text {:x x :y 532 :size 18 :fill (:text-body colours)} value)])))
        (stat-cells card)))

(defn render
  [{:keys [winning? monogram-key alt-text] :as card}]
  (let [colours (palette/token-palette winning?)
        monogram-pair (mapv palette/rgb (palette/monogram-gradient monogram-key))]
    (svg/card-root
     {:width card-width :height card-height :title alt-text}
     (defs colours monogram-pair)
     [:g {:clip-path "url(#ho-pnl-hero-clip)"}
      (svg/rounded-plate {:width card-width :height card-height
                          :radius card-radius :fill (:plate colours)})
      [:rect {:x 0 :y 0 :width card-width :height card-height
              :fill "url(#ho-pnl-hero-dots)"}]
      [:rect {:x 0 :y 0 :width card-width :height card-height
              :fill "url(#ho-pnl-hero-wash)"}]
      (header card colours)
      (coin-row card colours)
      (hero card colours)
      (footer card colours)]
     [:rect {:x 0.5 :y 0.5 :width (dec card-width) :height (dec card-height)
             :rx card-radius :fill "none"
             :stroke (:card-border colours) :stroke-width 1}])))
