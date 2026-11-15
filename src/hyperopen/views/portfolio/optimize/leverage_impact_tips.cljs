(ns hyperopen.views.portfolio.optimize.leverage-impact-tips
  "The leverage-impact panel's accessible info-tip primitive and its static
  tip copy, split from leverage-impact-panel when the current-book tile
  comparators pushed the panel past the namespace-size cap.

  Honesty contract carried by this copy (see leverage-impact-panel): the
  model is a closed-form lognormal — not a simulation, with no funding /
  execution / liquidation modeling — so the copy must never claim simulated
  paths, cash costs, or a liquidation probability the model does not
  compute."
  (:require ["lucide/dist/esm/icons/info.js" :default lucide-info]
            [hyperopen.views.portfolio.optimize.setup-controls :as controls]))

(defn info-tip
  "Accessible hover/focus info-tip matching the optimizer idiom (setup_universe /
  setup_controls): a focusable info glyph wrapped in a `group relative`, with an
  absolutely-positioned `role=tooltip` card that opacity-toggles on
  group-hover / group-focus-within. `position` (:start default / :center / :end)
  aligns the card so edge tiles don't overflow the panel."
  ([tooltip-id copy] (info-tip tooltip-id copy :start))
  ([tooltip-id copy position]
   (let [position-classes (case position
                            :center ["left-1/2" "-translate-x-1/2"]
                            :end ["right-0"]
                            ["left-0"])]
     [:span {:class ["optimizer-leverage-impact-tip" "group" "relative"
                     "inline-flex" "items-center" "align-middle"]}
      [:span {:class ["optimizer-leverage-impact-tip-glyph" "inline-flex"
                      "cursor-help" "text-trading-muted/70"
                      "hover:text-trading-text" "focus:text-trading-text"
                      "focus:outline-none"]
              :tabindex 0
              :role "img"
              :aria-label "More information"
              :aria-describedby tooltip-id
              :data-role (str tooltip-id "-trigger")}
       (controls/lucide-icon lucide-info ["w-3" "h-3"])]
      [:span {:class (into ["optimizer-leverage-impact-tip-card" "pointer-events-none"
                            "absolute" "top-[calc(100%+6px)]" "z-40"
                            "w-[min(22rem,calc(100vw-2rem))]" "border"
                            "border-base-300" "bg-base-100" "px-2.5" "py-2"
                            "font-sans" "text-[0.7rem]" "font-normal" "normal-case"
                            "leading-[1.5]" "tracking-normal" "text-trading-muted"
                            "opacity-0" "shadow-[0_12px_32px_rgba(0,0,0,0.5)]"
                            "transition-opacity" "duration-150"
                            "group-hover:opacity-100" "group-focus-within:opacity-100"]
                           position-classes)
              :id tooltip-id
              :role "tooltip"
              :data-role tooltip-id}
       copy]])))

(def copy
  {:panel
   (str "Modeled one-year account-equity outcomes for the current and target "
        "books, scaled from each book's estimated return and volatility. A "
        "closed-form lognormal model over a 365-day horizon — not a "
        "simulation. Funding, execution costs, and margin or liquidation "
        "mechanics are not modeled.")
   :modeled
   (str "Calculated from a model, not observed results. Every number here "
        "follows from the estimated return and volatility for each portfolio.")
   :mean
   (str "The average across the whole modeled distribution. Rare, very large "
        "gains pull it well above the median, so it overstates a typical path "
        "— the median is the more representative outcome.")
   :p5
   (str "A severe downside, not the worst case: 5% of modeled outcomes finish "
        "below this and 95% above. Worse outcomes are possible — just rarer "
        "than one in twenty.")
   :terminal
   (str "The share of the modeled distribution that finishes the year at or "
        "below half your starting equity. An end-of-year outcome — a book can "
        "fall past −50% mid-year and still recover above it (see the touching "
        "−50% odds).")
   :touch
   (str "The chance the modeled path drops at least 50% below its starting "
        "equity at any point in the year, even if it later recovers. A levered "
        "book is usually force-liquidated before a drawdown this deep "
        "completes, so read this as a floor on ruin risk — not a liquidation "
        "probability, which would need maintenance margins this result does "
        "not carry.")
   :distribution
   (str "The modeled spread of one-year target ending equity — a lognormal "
        "density on a log-scaled dollar axis, so equal spacing means equal "
        "multiples, not equal dollars (the mean can look only slightly right "
        "of the median while being far larger). Markers: 5th percentile (5% "
        "finish below), median (half below, half above), and mean (the "
        "average, lifted by rare large gains).")})
