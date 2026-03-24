(ns hyperopen.telemetry.console-warning
  (:require [clojure.string :as str]))

(def ^:private warning-text
  "Warning!")

(def ^:private warning-detail
  "You opened the browser console, a developer tool. Do not enter or paste code you do not understand. Never share your wallet or private data with anyone. If someone asked you to do this, it is likely a scam.")

(def ^:private ascii-banner
  (str " ('-. .-.              _ (`-.   ('-.  _  .-')   \n"
       "( OO )  /             ( (OO  )_(  OO)( \\( -O )  \n"
       ",--. ,--. ,--.   ,--._.`     (,------.,------.  \n"
       "|  | |  |  \\  `.'  /(__...--''|  .---'|   /`. ' \n"
       "|   .|  |.-')     /  |  /  | ||  |    |  /  | | \n"
       "|       (OO  \\   /   |  |_.' (|  '--. |  |_.' | \n"
       "|  .-.  ||   /  /\\_  |  .___.'|  .--' |  .  '.' \n"
       "|  | |  |`-./  /.__) |  |     |  `---.|  |\\  \\  \n"
       "`--' `--'  `--'      `--'     `------'`--' '--' \n"
       "                _ (`-.   ('-.       .-') _      \n"
       "               ( (OO  )_(  OO)     ( OO ) )     \n"
       " .-'),-----.  _.`     (,------.,--./ ,--,'      \n"
       "( OO'  .-.  '(__...--''|  .---'|   \\ |  |\\      \n"
       "/   |  | |  | |  /  | ||  |    |    \\|  | )     \n"
       "\\_) |  |\\|  | |  |_.' (|  '--. |  .     |/      \n"
       "  \\ |  | |  | |  .___.'|  .--' |  |\\    |       \n"
       "   `'  '-'  ' |  |     |  `---.|  | \\   |       \n"
       "     `-----'  `--'     `------'`--'  `--'"))

(def ^:private banner-base-style
  "font-family: Menlo, Monaco, Consolas, 'Courier New', monospace; font-size: 22px; font-weight: 700;")

(def ^:private banner-gradient-start-rgb
  [0 212 170])

(def ^:private banner-gradient-end-rgb
  [15 26 31])

(def ^:private warning-style
  "color: #ff6257; font-family: Menlo, Monaco, Consolas, 'Courier New', monospace; font-size: 78px; font-weight: 700; line-height: 1.05;")

(def ^:private warning-detail-style
  "color: #e9e9ef; font-family: Menlo, Monaco, Consolas, 'Courier New', monospace; font-size: 24px; font-weight: 600; line-height: 1.3;")

(defn- browser-console?
  []
  (and (some? (some-> js/globalThis .-document))
       (some? (some-> js/globalThis .-console .-log))))

(defn- lerp
  [a b t]
  (+ a (* (- b a) t)))

(defn- ease-out
  [t]
  (- 1 (js/Math.pow (- 1 t) 2)))

(defn- gradient-rgb
  [t]
  (let [t* (max 0 (min 1 t))
        eased-t (ease-out t*)
        [r-dark g-dark b-dark] banner-gradient-end-rgb
        [r-lite g-lite b-lite] banner-gradient-start-rgb
        r (js/Math.round (lerp r-dark r-lite eased-t))
        g (js/Math.round (lerp g-dark g-lite eased-t))
        b (js/Math.round (lerp b-dark b-lite eased-t))]
    [r g b]))

(defn- emit-banner!
  []
  (let [lines (str/split-lines ascii-banner)
        line-count (count lines)
        denominator (max 1 (dec line-count))
        styled-lines (keep (fn [[idx line]]
                             (when-not (str/blank? line)
                               (let [[r g b] (gradient-rgb (/ idx denominator))
                                     style (str banner-base-style
                                                "line-height: 22px;"
                                                "color: rgb(" r "," g "," b ");"
                                                "text-shadow: 0 1px 0 rgba(0,0,0,.45);")]
                                 [line style])))
                           (map-indexed vector lines))
        format-string (str/join "\n" (map (fn [[line _style]]
                                            (str "%c" line))
                                          styled-lines))
        styles (map second styled-lines)
        args (into-array (cons format-string styles))]
    (.apply (.-log js/console) js/console args)))

(defn emit-warning!
  []
  (when (browser-console?)
    (emit-banner!)
    (.log js/console (str "%c" warning-text) warning-style)
    (.log js/console (str "%c" warning-detail) warning-detail-style)))
