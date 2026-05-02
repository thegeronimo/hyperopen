(ns hyperopen.views.leaderboard.styles)

(def leaderboard-background-style
  {:background-image "radial-gradient(circle at 15% 0%, rgba(0, 212, 170, 0.10), transparent 35%), radial-gradient(circle at 85% 100%, rgba(0, 212, 170, 0.08), transparent 40%)"})

(def workspace-shell-classes
  ["rounded-xl"
   "border"
   "border-base-300/80"
   "bg-base-100/95"
   "overflow-hidden"])

(def control-shell-classes
  ["rounded-xl"
   "border"
   "border-base-300/80"
   "bg-base-100/95"
   "p-2.5"
   "md:p-3"])

(def focus-visible-ring-classes
  ["focus:outline-none"
   "focus:ring-2"
   "focus:ring-[#66e3c5]/45"
   "focus:ring-offset-1"
   "focus:ring-offset-base-100"
   "focus-visible:outline-none"
   "focus-visible:ring-2"
   "focus-visible:ring-[#66e3c5]/45"
   "focus-visible:ring-offset-1"
   "focus-visible:ring-offset-base-100"])

(def focus-reset-classes
  ["focus:outline-none"
   "focus:ring-0"
   "focus:ring-offset-0"
   "focus-visible:outline-none"
   "focus-visible:ring-0"
   "focus-visible:ring-offset-0"])

(declare trader-chip)
