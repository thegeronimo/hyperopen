(ns hyperopen.views.footer.build-badge
  (:require [clojure.string :as str]))

(def ^:private fresh-window-ms
  (* 6 60 60 1000))

(defn- non-blank
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- js-field
  [obj field]
  (when obj
    (aget obj field)))

(defn- raw-field
  [raw field]
  (if (map? raw)
    (get raw field)
    (js-field raw (name field))))

(defn- short-sha
  [sha]
  (when-let [text (non-blank sha)]
    (subs text 0 (min 7 (count text)))))

(defn- normalize-env
  [env]
  (let [env* (some-> env non-blank str/lower-case)]
    (if (#{"prod" "staging" "dev"} env*)
      env*
      "dev")))

(defn- date-ms
  [value]
  (when-let [text (non-blank value)]
    (let [date (js/Date. text)
          ms (.getTime date)]
      (when (js/Number.isFinite ms)
        ms))))

(defn- iso-now
  []
  (.toISOString (js/Date.)))

(defn normalize-build
  [raw fallback-build-id]
  (let [sha (or (non-blank (raw-field raw :sha))
                (non-blank fallback-build-id))]
    (when sha
      (let [deployed-at (or (non-blank (raw-field raw :deployedAt))
                            (non-blank (raw-field raw :deployed-at))
                            (iso-now))]
        {:sha sha
         :short (or (short-sha (raw-field raw :short))
                    (short-sha sha))
         :branch (or (non-blank (raw-field raw :branch)) "unknown")
         :message (or (non-blank (raw-field raw :message)) "Build metadata unavailable")
         :deployed-at deployed-at
         :env (normalize-env (raw-field raw :env))
         :region (or (non-blank (raw-field raw :region)) "global")}))))

(defn relative-time
  [now-ms deployed-at]
  (if-let [deployed-ms (date-ms deployed-at)]
    (let [elapsed-s (max 0 (js/Math.floor (/ (- now-ms deployed-ms) 1000)))]
      (cond
        (< elapsed-s 60)
        (str elapsed-s "s ago")

        (< elapsed-s 3600)
        (str (js/Math.floor (/ elapsed-s 60)) "m ago")

        (< elapsed-s 86400)
        (let [hours (js/Math.floor (/ elapsed-s 3600))
              minutes (js/Math.floor (/ (mod elapsed-s 3600) 60))]
          (if (pos? minutes)
            (str hours "h " minutes "m ago")
            (str hours "h ago")))

        :else
        (str (js/Math.floor (/ elapsed-s 86400)) "d ago")))
    "unknown"))

(defn- fresh?
  [now-ms deployed-at]
  (when-let [deployed-ms (date-ms deployed-at)]
    (< (- now-ms deployed-ms) fresh-window-ms)))

(defn- build-copy-payload
  [{:keys [sha env region deployed-at branch message]}]
  (str "Build: " sha "\n"
       "Env: " env " (" region ")\n"
       "Deployed: " deployed-at "\n"
       "Branch: " branch "\n"
       "Message: " message))

(defn- shell-for-event
  [event]
  (some-> event .-currentTarget (.closest "[data-role='footer-build-id-shell']")))

(defn- clear-hide-timer!
  [shell]
  (when-let [timer-id (some-> shell (aget "__hyperopenBuildHideTimer"))]
    (js/clearTimeout timer-id)
    (aset shell "__hyperopenBuildHideTimer" nil)))

(defn- set-open!
  [shell open?]
  (when shell
    (clear-hide-timer! shell)
    (.setAttribute shell "data-open" (if open? "true" "false"))))

(defn- open-from-event!
  [event]
  (set-open! (shell-for-event event) true))

(defn- close-from-event!
  [event]
  (when-let [shell (shell-for-event event)]
    (clear-hide-timer! shell)
    (let [timer-id (js/setTimeout
                    (fn []
                      (.setAttribute shell "data-open" "false")
                      (aset shell "__hyperopenBuildHideTimer" nil))
                    120)]
      (aset shell "__hyperopenBuildHideTimer" timer-id))))

(defn- focus-left-shell?
  [event shell]
  (let [next-target (.-relatedTarget event)]
    (not (and shell next-target (.contains shell next-target)))))

(defn- focusout-from-event!
  [event]
  (let [shell (shell-for-event event)]
    (when (focus-left-shell? event shell)
      (close-from-event! event))))

(defn- keydown-from-event!
  [event]
  (when (= "Escape" (.-key event))
    (.preventDefault event)
    (.stopPropagation event)
    (when-let [shell (shell-for-event event)]
      (set-open! shell false))))

(defn- copy-from-event!
  [payload]
  (fn [event]
    (.preventDefault event)
    (.stopPropagation event)
    (let [button (.-currentTarget event)
          clipboard (some-> js/globalThis .-navigator .-clipboard)
          write-text (some-> clipboard .-writeText)]
      (when (and clipboard write-text)
        (.writeText clipboard payload))
      (when-let [timer-id (aget button "__hyperopenBuildCopyTimer")]
        (js/clearTimeout timer-id))
      (.setAttribute button "data-copied" "true")
      (aset button "__hyperopenBuildCopyTimer"
            (js/setTimeout
             (fn []
               (.setAttribute button "data-copied" "false")
               (aset button "__hyperopenBuildCopyTimer" nil))
             1400)))))

(defn- icon-code
  []
  [:svg {:width 11
         :height 11
         :viewBox "0 0 12 12"
         :fill "none"
         :aria-hidden true}
   [:path {:d "M4 3 L1.5 6 L4 9 M8 3 L10.5 6 L8 9"
           :stroke "currentColor"
           :stroke-width "1.2"
           :stroke-linecap "round"
           :stroke-linejoin "round"}]])

(defn- icon-copy
  []
  [:svg {:width 11
         :height 11
         :viewBox "0 0 12 12"
         :fill "none"
         :aria-hidden true}
   [:rect {:x "3.5"
           :y "3.5"
           :width "6"
           :height "6.5"
           :rx "1"
           :stroke "currentColor"
           :stroke-width "1.1"}]
   [:path {:d "M2 7.5 V2.5 A1 1 0 0 1 3 1.5 H7.5"
           :stroke "currentColor"
           :stroke-width "1.1"}]])

(defn- icon-check
  []
  [:svg {:width 11
         :height 11
         :viewBox "0 0 12 12"
         :fill "none"
         :aria-hidden true}
   [:path {:d "M2.5 6.5 L5 9 L9.5 3.5"
           :stroke "currentColor"
           :stroke-width "1.6"
           :stroke-linecap "round"
           :stroke-linejoin "round"}]])

(defn render
  [{:keys [build now-ms]}]
  (when-let [{:keys [sha short env deployed-at] :as build*} build]
    (let [popover-id (str "footer-build-popover-" short)
          live? (fresh? now-ms deployed-at)
          copy-payload (build-copy-payload build*)]
      [:span {:class ["o-build-shell"]
              :data-role "footer-build-id-shell"
              :data-open "false"
              :on {:mouseenter open-from-event!
                   :mouseleave close-from-event!
                   :focusin open-from-event!
                   :focusout focusout-from-event!
                   :keydown keydown-from-event!}}
       [:button {:class ["o-build"]
                 :type "button"
                 :data-role "footer-build-id"
                 :aria-describedby popover-id
                 :aria-label (str "Build " short)}
        [:span {:class ["o-build-glyph"]} (icon-code)]
        [:span {:class ["o-build-short" "o-mono"]} short]]
       [:div {:class ["o-build-pop" "condensed"]
              :id popover-id
              :role "tooltip"
              :data-role "footer-build-id-tooltip"}
        [:div {:class ["c-head"]}
         [:span {:class ["o-bp-env" "o-mono" (str "env-" env)]
                 :data-role "footer-build-env"}
          [:span {:class ["dot"]}]
          env]
         (if live?
           [:span {:class ["o-bp-fresh"]
                   :data-role "footer-build-freshness"}
            "live"]
           [:span {:class ["c-stale" "o-mono"]
                   :data-role "footer-build-freshness"}
            "stale"])]
        [:div {:class ["c-sha" "o-mono"]
               :title sha
               :data-role "footer-build-sha"}
         sha]
        [:div {:class ["c-times"]
               :data-role "footer-build-deployed"}
         [:div {:class ["c-time-row"]}
          [:span {:class ["c-time-label" "o-mono"]} "DEPLOYED"]
          [:span {:class ["c-time-val" "o-mono"]} (relative-time now-ms deployed-at)]]]
        [:div {:class ["c-actions"]}
         [:button {:class ["c-action"]
                   :type "button"
                   :aria-label "Copy build info"
                   :data-role "footer-build-copy"
                   :data-copied "false"
                   :data-copy-payload copy-payload
                   :on {:click (copy-from-event! copy-payload)}}
         [:span {:class ["copy-default"]}
          (icon-copy)
          "copy build info"]
         [:span {:class ["copy-done"]}
          (icon-check)
           "copied"]]]]])))
