(ns hyperopen.views.pnl-share.templates.neon-arrow
  "Design direction 2a: contour field, gradient numeral, neon arrow.

   Because the card is set in a monospaced face, every text run's width is
   arithmetic rather than measurement, so this whole template is a pure function
   from the card data map to hiccup."
  (:require [clojure.string :as str]
            [hyperopen.views.pnl-share.palette :as palette]
            [hyperopen.views.pnl-share.svg :as svg]
            [hyperopen.views.pnl-share.templates.neon-arrow-art :as art]))

(def ^:private pad-x 56)
(def ^:private mark-height 30)
(def ^:private wordmark-size 32)

(defn- wordmark
  [{:keys [loss-pill]} colours]
  (let [mark-width (art/brand-mark-width mark-height)
        text-x (+ pad-x mark-width 14)
        baseline 79
        split-x (+ text-x (svg/text-width "hyper" wordmark-size))
        pill-x (+ split-x (svg/text-width "open" wordmark-size) 22)]
    [:g
     (art/brand-mark {:x pad-x :y 54 :height mark-height
                      :colour-a (:mark-a colours) :colour-b (:mark-b colours)})
     (svg/text {:x text-x :y baseline :size wordmark-size :weight 500
                :fill (:text-hi colours)}
               "hyper")
     (svg/text {:x split-x :y baseline :size wordmark-size :weight 500
                :fill (:accent colours)}
               "open")
     (when loss-pill
       (let [pill-width (+ (svg/text-width loss-pill 13 0.18) 26)]
         [:g
          [:rect {:x pill-x :y 58 :width pill-width :height 30 :rx 15
                  :fill "none" :stroke (:loss-pill-border colours) :stroke-width 1}]
          (svg/text {:x (+ pill-x 13) :y 78 :size 13 :weight 500 :tracking 0.18
                     :fill (:loss-pill-text colours)}
                    loss-pill)]))]))

(defn- coin-row
  [{:keys [monogram coin-label dex-label leverage-label]} colours]
  (let [coin-x 116
        coin-end (+ coin-x (svg/text-width coin-label 42))
        [lev-chip lev-end] (when leverage-label
                             (svg/chip {:x (+ coin-end 16) :y 172 :height 36 :size 20
                                        :fill (:chip-bg colours)
                                        :stroke (:chip-border colours)
                                        :colour (:chip-text colours)
                                        :tracking 0.06}
                                       leverage-label))
        dex-x (+ (or lev-end (+ coin-end 16)) 10)
        [dex-chip _] (when dex-label
                       (svg/chip {:x dex-x :y 172 :height 36 :size 18
                                  :fill (:chip-bg colours)
                                  :stroke (:chip-border colours)
                                  :colour (:chip-text colours)
                                  :pad 14
                                  :tracking 0.06}
                                 dex-label))]
    [:g
     (svg/monogram-disc {:cx 80 :cy 190 :r 24 :letter monogram
                         :gradient-id "ho-pnl-monogram" :label-size 20
                         :rim (:disc-rim colours) :letter-fill (:disc-letter colours)})
     (svg/text {:x coin-x :y 204 :size 42 :weight 500 :fill (:text-hi colours)}
               coin-label)
     lev-chip
     dex-chip]))

(defn- hero
  [{:keys [roe-text roe-label]} colours]
  [:g
   (svg/text {:x pad-x :y 340 :size 132 :weight 500 :tracking -0.035
              :fill "url(#ho-pnl-hero)"}
             (or roe-text "--"))
   (svg/text {:x (+ pad-x 2) :y 374 :size 17 :tracking 0.34
              :fill (:hero-label colours)}
             roe-label)])

(defn- price-block
  [{:keys [x label value icon]} colours]
  [:g
   [:rect {:x x :y 398 :width 46 :height 46 :rx 12
           :fill "none" :stroke (:frame-border colours) :stroke-width 1}]
   icon
   (svg/text {:x (+ x 61) :y 418 :size 17 :fill (:text-mid colours)} label)
   (svg/text {:x (+ x 61) :y 447 :size 27 :weight 500 :fill (:text-hi colours)} value)])

(defn- price-pair
  [{:keys [entry-price-text mark-price-text winning?]} colours]
  (when (or entry-price-text mark-price-text)
    [:g
     (when entry-price-text
       (price-block {:x pad-x
                     :label "Entry Price"
                     :value entry-price-text
                     :icon (art/tag-icon {:x (+ pad-x 12) :y 410 :size 21
                                          :colour (:icon colours)})}
                   colours))
     (when (and entry-price-text mark-price-text)
       (svg/vertical-rule {:x 340 :y1 398 :y2 450 :stroke (:divider-strong colours)}))
     (when mark-price-text
       (price-block {:x (if entry-price-text 380 pad-x)
                     :label "Mark Price"
                     :value mark-price-text
                     :icon (art/trend-icon {:x (+ (if entry-price-text 380 pad-x) 12)
                                            :y 410 :size 21
                                            :colour (:icon colours)}
                                           winning?)}
                   colours))]))

(defn meta-line
  "The one-line summary under the rule. Only the parts that exist appear, so a
   position with no loaded opening fill simply reads shorter."
  [{:keys [size-text held-text funding-text]}]
  (let [parts (cond-> []
                size-text (conj size-text)
                held-text (conj (str "held " held-text))
                funding-text (conj (str "funding " funding-text)))]
    (when (seq parts)
      (str/join " · " parts))))

(defn- footer
  [{:keys [site-label handle-text timestamp-text] :as card} colours]
  (let [label-x 84
        label-width (svg/text-width site-label 19 0.06)
        rule-x (+ label-x label-width 18)
        meta (meta-line card)]
    [:g
     (svg/hairline {:x1 pad-x :x2 676 :y 518 :stroke (:divider colours)})
     (art/globe-icon {:x pad-x :y 536 :size 19 :colour (:accent colours)})
     (svg/text {:x label-x :y 551 :size 19 :tracking 0.06 :fill (:text-body colours)}
               site-label)
     (when meta
       [:g
        (svg/vertical-rule {:x rule-x :y1 538 :y2 554 :stroke (:divider-strong colours)})
        (svg/text {:x (+ rule-x 18) :y 551 :size 16 :fill (:text-mid colours)} meta)])
     (when handle-text
       (svg/text {:x 1024 :y 540 :size 16 :anchor "end" :fill (:text-body colours)}
                 handle-text))
     (when timestamp-text
       (svg/text {:x 1024 :y 562 :size 13 :anchor "end" :fill (:text-dim colours)}
                 timestamp-text))]))

(defn render
  [{:keys [winning? monogram-key alt-text] :as card}]
  (let [colours (palette/card-palette winning?)]
    (svg/card-root
     {:width art/card-width :height art/card-height :title alt-text}
     (art/defs colours winning?)
     [:defs (art/monogram-gradient-def
             (mapv palette/rgb (palette/monogram-gradient monogram-key)))]
     [:g {:clip-path "url(#ho-pnl-card-clip)"}
      (svg/rounded-plate {:width art/card-width :height art/card-height
                          :radius art/card-radius :fill (:plate colours)})
      [:rect {:x 0 :y 0 :width art/card-width :height art/card-height
              :fill "url(#ho-pnl-wash-main)"}]
      [:rect {:x 0 :y 0 :width art/card-width :height art/card-height
              :fill "url(#ho-pnl-wash-corner)"}]
      (art/contour-field colours)
      (when-not winning? (art/loss-rain colours))
      (art/arrow colours winning?)
      (wordmark card colours)
      (coin-row card colours)
      (hero card colours)
      (price-pair card colours)
      (footer card colours)]
     [:rect {:x 0.5 :y 0.5 :width (dec art/card-width) :height (dec art/card-height)
             :rx art/card-radius :fill "none"
             :stroke (:card-border colours) :stroke-width 1}])))
