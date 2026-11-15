(ns hyperopen.views.degen.illustrations
  "Original full-color mascots for the degen experience, drawn as inline
   SVG. No third-party artwork or binary assets — the cast is drawn from
   scratch in code.

   Illustration colors are intentionally hardcoded: per docs/THEMING.md,
   illustrations are not a theme concern, so this namespace carries a
   dev/theme_color_baseline.edn entry instead of semantic tokens. The
   feeling-gauge dial is UI (not a character) and stays token-colored."
  )

(def ^:private ink "#1c2127")
(def ^:private frog-green "#4fa353")
(def ^:private frog-dark "#2c5a31")
(def ^:private eye-white "#f6f3e8")
(def ^:private lip-red "#c0563f")
(def ^:private lip-dark "#8e3527")
(def ^:private shirt-blue "#2e5d8f")
(def ^:private shiba-tan "#e0aa5e")
(def ^:private shiba-dark "#8a5a25")
(def ^:private shiba-cream "#f7e7c3")
(def ^:private whale-blue "#5ba7e6")
(def ^:private whale-belly "#d9ecfb")
(def ^:private whale-dark "#2c4a66")
(def ^:private shade-black "#14171c")

(defn pepe
  "Smug full-color frog, head and shirt collar."
  [size-class]
  [:svg {:viewBox "0 0 120 104"
         :class [size-class]
         :role "img"
         :aria-label "smug frog mascot"
         :fill "none"}
   [:path {:d "M12 97 C 34 83, 86 83, 108 97 L 108 104 L 12 104 Z"
           :fill shirt-blue}]
   [:ellipse {:cx "60" :cy "60" :rx "50" :ry "37"
              :fill frog-green :stroke frog-dark :stroke-width "3"}]
   [:ellipse {:cx "38" :cy "30" :rx "17" :ry "14"
              :fill frog-green :stroke frog-dark :stroke-width "3"}]
   [:ellipse {:cx "82" :cy "28" :rx "17" :ry "14"
              :fill frog-green :stroke frog-dark :stroke-width "3"}]
   [:ellipse {:cx "38" :cy "35" :rx "13" :ry "8.5" :fill eye-white}]
   [:ellipse {:cx "82" :cy "33" :rx "13" :ry "8.5" :fill eye-white}]
   [:path {:d "M23 31 Q 38 37 53 31"
           :stroke frog-dark :stroke-width "2.5" :stroke-linecap "round"}]
   [:path {:d "M67 29 Q 82 35 97 29"
           :stroke frog-dark :stroke-width "2.5" :stroke-linecap "round"}]
   [:circle {:cx "41" :cy "37" :r "3.4" :fill ink}]
   [:circle {:cx "85" :cy "35" :r "3.4" :fill ink}]
   [:ellipse {:cx "53" :cy "57" :rx "1.8" :ry "1.2" :fill frog-dark}]
   [:ellipse {:cx "63" :cy "56.5" :rx "1.8" :ry "1.2" :fill frog-dark}]
   [:path {:d "M14 68 C 40 80, 84 80, 106 62 C 107.5 66, 106.5 69.5, 104 72 C 82 87, 38 87, 15.5 74 Z"
           :fill lip-red :stroke lip-dark :stroke-width "2.5"
           :stroke-linejoin "round"}]
   [:path {:d "M17 72.5 C 43 84.5, 81 84.5, 103 67"
           :stroke lip-dark :stroke-width "2" :stroke-linecap "round"}]])

(defn doge
  "Full-color shiba face; `:shades?` swaps the eyes for dealer shades
   (Shill of the Day)."
  ([size-class] (doge size-class {}))
  ([size-class {:keys [shades?]}]
   [:svg {:viewBox "0 0 120 110"
          :class [size-class]
          :role "img"
          :aria-label "shiba mascot"
          :fill "none"}
    [:path {:d "M30 36 L 22 8 L 54 24 Z"
            :fill shiba-tan :stroke shiba-dark :stroke-width "3"
            :stroke-linejoin "round"}]
    [:path {:d "M90 36 L 98 8 L 66 24 Z"
            :fill shiba-tan :stroke shiba-dark :stroke-width "3"
            :stroke-linejoin "round"}]
    [:path {:d "M34 28 L 30 16 L 46 24 Z" :fill shiba-cream}]
    [:path {:d "M86 28 L 90 16 L 74 24 Z" :fill shiba-cream}]
    [:ellipse {:cx "60" :cy "66" :rx "46" :ry "38"
               :fill shiba-tan :stroke shiba-dark :stroke-width "3"}]
    [:ellipse {:cx "60" :cy "80" :rx "28" :ry "19" :fill shiba-cream}]
    [:ellipse {:cx "25" :cy "66" :rx "9" :ry "7" :fill shiba-cream}]
    [:ellipse {:cx "95" :cy "66" :rx "9" :ry "7" :fill shiba-cream}]
    (if shades?
      [:g
       [:rect {:x "26" :y "46" :width "31" :height "13" :rx "4" :fill shade-black}]
       [:rect {:x "63" :y "46" :width "31" :height "13" :rx "4" :fill shade-black}]
       [:path {:d "M57 51 L 63 51 M26 52 L 18 48 M94 52 L 102 48"
               :stroke shade-black :stroke-width "3" :stroke-linecap "round"}]
       [:path {:d "M31 50 L 38 50" :stroke "#5b6470" :stroke-width "2"
               :stroke-linecap "round"}]]
      [:g
       [:path {:d "M34 46 Q 40 42 46 45"
               :stroke ink :stroke-width "3" :stroke-linecap "round"}]
       [:path {:d "M74 45 Q 80 42 86 46"
               :stroke ink :stroke-width "3" :stroke-linecap "round"}]
       [:circle {:cx "40" :cy "54" :r "4.5" :fill ink}]
       [:circle {:cx "80" :cy "54" :r "4.5" :fill ink}]
       [:circle {:cx "41.5" :cy "52.5" :r "1.4" :fill eye-white}]
       [:circle {:cx "81.5" :cy "52.5" :r "1.4" :fill eye-white}]])
    [:path {:d "M55 71 Q 60 69 65 71 Q 64 77 60 78 Q 56 77 55 71 Z" :fill ink}]
    [:path {:d "M60 79 Q 58 85 52 85 M60 79 Q 62 85 68 85"
            :stroke ink :stroke-width "2.5" :stroke-linecap "round"}]
    [:circle {:cx "44" :cy "86" :r "1.1" :fill shiba-dark}]
    [:circle {:cx "48" :cy "91" :r "1.1" :fill shiba-dark}]
    [:circle {:cx "72" :cy "91" :r "1.1" :fill shiba-dark}]
    [:circle {:cx "76" :cy "86" :r "1.1" :fill shiba-dark}]]))

(defn whale
  "Full-color whale with a spout and shades (Whale Watch)."
  [size-class]
  [:svg {:viewBox "0 0 120 90"
         :class [size-class]
         :role "img"
         :aria-label "whale mascot"
         :fill "none"}
   [:path {:d "M44 18 C 40 10, 32 8, 26 10 M44 18 C 44 8, 52 4, 58 6"
           :stroke whale-blue :stroke-width "4" :stroke-linecap "round"}]
   [:path {:d "M98 56 C 106 50, 112 48, 116 50 C 113 56, 113 62, 116 68 C 108 68, 102 64, 98 60 Z"
           :fill whale-blue :stroke whale-dark :stroke-width "3"
           :stroke-linejoin "round"}]
   [:path {:d "M16 50 C 14 30, 36 22, 56 26 C 84 30, 96 44, 98 56 C 99 66, 92 74, 80 76 C 56 80, 24 76, 16 62 Z"
           :fill whale-blue :stroke whale-dark :stroke-width "3"
           :stroke-linejoin "round"}]
   [:path {:d "M20 62 C 36 72, 62 74, 80 70 C 72 77, 40 78, 22 68 Z"
           :fill whale-belly}]
   [:rect {:x "28" :y "38" :width "15" :height "10" :rx "3" :fill shade-black}]
   [:rect {:x "47" :y "37" :width "15" :height "10" :rx "3" :fill shade-black}]
   [:path {:d "M43 42 L 47 42 M62 41 L 70 39"
           :stroke shade-black :stroke-width "2.5" :stroke-linecap "round"}]
   [:path {:d "M30 56 C 36 60, 44 60, 50 57"
           :stroke whale-dark :stroke-width "2.5" :stroke-linecap "round"}]])

(defn gauge-angle
  "Needle angle in degrees for unrealized PNL; +/-75 full scale at +/-$500,
   0 for nil/zero. Pure for tests."
  [unrealized-pnl]
  (if-not (number? unrealized-pnl)
    0
    (-> (* (/ unrealized-pnl 500) 75)
        (max -75)
        (min 75))))

(defn feeling-gauge-dial
  "Semicircular sell->warn->buy gauge; needle position derives from real
   PNL via gauge-angle. Token classes color the arc segments (this is UI
   chrome, not a character, so it stays themeable)."
  [unrealized-pnl]
  (let [angle (gauge-angle unrealized-pnl)]
    [:svg {:viewBox "0 0 100 58"
           :class ["w-24"]
           :role "img"
           :aria-label "feeling gauge"
           :fill "none"
           :data-role "degen-feeling-dial"}
     [:path {:d "M10 50 A 40 40 0 0 1 26 18"
             :class ["text-ho-sell"]
             :stroke "currentColor"
             :stroke-width "8"
             :stroke-linecap "round"}]
     [:path {:d "M31 14 A 40 40 0 0 1 69 14"
             :class ["text-ho-warn"]
             :stroke "currentColor"
             :stroke-width "8"
             :stroke-linecap "round"}]
     [:path {:d "M74 18 A 40 40 0 0 1 90 50"
             :class ["text-ho-buy"]
             :stroke "currentColor"
             :stroke-width "8"
             :stroke-linecap "round"}]
     [:g {:class ["text-ho-text"]
          :transform (str "rotate(" angle " 50 50)")}
      [:path {:d "M50 50 L 50 20"
              :stroke "currentColor"
              :stroke-width "3.5"
              :stroke-linecap "round"}]
      [:circle {:cx "50" :cy "50" :r "4" :fill "currentColor"}]]]))
