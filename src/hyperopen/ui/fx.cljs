(ns hyperopen.ui.fx
  "Ephemeral DOM celebration effects for the degen experience: emoji
   confetti on fills and the LIQUIDATED skull overlay. Pure presentation
   side effects — elements are created outside the render tree, clean
   themselves up, and intercept no interaction except the overlay's own
   dismiss. Emoji-only pieces keep the color-literal ratchet clean.
   Everything no-ops without a DOM (node tests)."
  )

(def ^:private confetti-emoji
  ["🚀" "🌕" "💰" "🐸" "💎" "🙌" "✨"])

(def ^:private rekt-quips
  ["It was never about the money."
   "Zoom out. Way out."
   "This is financial advice in reverse."
   "gm. (gone money)"])

(defn- doc []
  (when (exists? js/document)
    js/document))

(defn- ensure-fx-layer!
  [document]
  (or (.getElementById document "degen-fx-layer")
      (let [layer (.createElement document "div")]
        (set! (.-id layer) "degen-fx-layer")
        (set! (.-className layer)
              "fixed inset-0 overflow-hidden pointer-events-none z-[9998]")
        (.appendChild (.-body document) layer)
        layer)))

(defn confetti!
  "Drop a burst of emoji confetti; pieces remove themselves."
  ([] (confetti! {}))
  ([{:keys [pieces] :or {pieces 36}}]
   (when-some [document (doc)]
     (let [layer (ensure-fx-layer! document)]
       (dotimes [_ pieces]
         (let [el (.createElement document "span")
               style (.-style el)]
           (set! (.-className el) "degen-confetti-piece")
           (set! (.-textContent el)
                 (nth confetti-emoji
                      (js/Math.floor (* (js/Math.random)
                                        (count confetti-emoji)))))
           (set! (.-left style) (str (* 100 (js/Math.random)) "vw"))
           (set! (.-fontSize style)
                 (str (+ 14 (* 18 (js/Math.random))) "px"))
           (set! (.-animationDuration style)
                 (str (+ 1.8 (* 1.6 (js/Math.random))) "s"))
           (set! (.-animationDelay style)
                 (str (* 0.4 (js/Math.random)) "s"))
           (.setProperty style "--degen-drift"
                         (str (- (* 240 (js/Math.random)) 120) "px"))
           (.setProperty style "--degen-spin"
                         (str (- (* 1080 (js/Math.random)) 540) "deg"))
           (.appendChild layer el)
           (js/setTimeout #(.remove el) 4200)))
       nil))))

(defn rekt-overlay!
  "Full-screen LIQUIDATED skull moment; click (or 8s) dismisses."
  ([] (rekt-overlay! {}))
  ([{:keys [quip]}]
   (when-some [document (doc)]
     (some-> (.getElementById document "degen-rekt-overlay") (.remove))
     (let [overlay (.createElement document "div")
           quip* (or quip
                     (nth rekt-quips
                          (js/Math.floor (* (js/Math.random)
                                            (count rekt-quips)))))]
       (set! (.-id overlay) "degen-rekt-overlay")
       (set! (.-className overlay)
             "fixed inset-0 z-[9999] flex items-center justify-center bg-ho-bg/85 cursor-pointer")
       (set! (.-innerHTML overlay) "")
       (let [box (.createElement document "div")
             skull (.createElement document "div")
             title (.createElement document "div")
             sub (.createElement document "div")
             quip-el (.createElement document "div")
             cope (.createElement document "button")]
         (set! (.-className box)
               "degen-rekt-box flex flex-col items-center gap-2 rounded-xl border-2 border-ho-sell bg-ho-surface px-10 py-8 text-center")
         (set! (.-className skull) "text-6xl leading-none")
         (set! (.-textContent skull) "💀")
         (set! (.-className title)
               "text-2xl font-black tracking-[0.1em] text-ho-sell")
         (set! (.-textContent title) "LIQUIDATED")
         (set! (.-className sub) "text-sm text-ho-text-secondary")
         (set! (.-textContent sub) "Your position is gone.")
         (set! (.-className quip-el) "text-sm italic text-ho-text-dim")
         (.setProperty (.-style quip-el) "font-family" "var(--font-marker)")
         (set! (.-textContent quip-el) (str "\"" quip* "\""))
         (set! (.-type cope) "button")
         (set! (.-className cope)
               "mt-2 rounded-lg bg-ho-buy px-4 py-2 text-sm font-bold text-ho-bg")
         (set! (.-textContent cope) "COPE & CONTINUE")
         (.appendChild box skull)
         (.appendChild box title)
         (.appendChild box sub)
         (.appendChild box quip-el)
         (.appendChild box cope)
         (.appendChild overlay box))
       (.addEventListener overlay "click" (fn [_] (.remove overlay)))
       (.appendChild (.-body document) overlay)
       (js/setTimeout #(.remove overlay) 8000)
       nil))))
