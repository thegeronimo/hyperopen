(ns hyperopen.views.ui.dismissible-tooltip
  "Escape hides a hover or keyboard-focus tooltip without moving the pointer
   or focus, as WCAG 1.4.13 asks of content that covers other content.

   Put `group-attrs` on the element whose hover or focus shows the tooltip
   (the Tailwind `group`). On Escape it marks itself
   `data-tooltip-dismissed=\"true\"`, and the tooltip hides with
   `hidden-when-dismissed-class`, or, inside a named group, the literal
   `group-data-[tooltip-dismissed=true]/<name>:!invisible` (Tailwind only
   generates class names it finds spelled out in the source).

   The key reaches the group itself while focus is inside it; while only
   the pointer is over it the key goes to the document, so a document
   listener lives exactly as long as the hover. The mark clears when the
   pointer or focus arrives again, and when both have left.

   The handlers touch only the DOM node's own attribute: the tooltip's
   visibility is presentation, not app state.")

(def dismissed-attribute
  "data-tooltip-dismissed")

(def hidden-when-dismissed-class
  "Hides a tooltip inside an unnamed `group` once dismissed."
  "group-data-[tooltip-dismissed=true]:!invisible")

(def ^:private listener-property
  "__hyperopenTooltipEscape")

(defn- escape?
  [event]
  (= "Escape" (.-key event)))

(defn- dismiss!
  [node]
  (.setAttribute node dismissed-attribute "true"))

(defn- restore!
  [node]
  (.removeAttribute node dismissed-attribute))

(defn- stop-listening!
  [node]
  (when-let [listener (aget node listener-property)]
    (.removeEventListener js/document "keydown" listener)
    (aset node listener-property nil)))

(defn- focus-inside?
  [node]
  (let [active (some-> js/globalThis .-document .-activeElement)]
    (boolean (and active (.contains node active)))))

(defn- on-keydown!
  [event]
  (when (escape? event)
    (dismiss! (.-currentTarget event))))

(defn- on-mouseenter!
  [event]
  (let [node (.-currentTarget event)]
    (restore! node)
    (stop-listening! node)
    (let [listener (fn [key-event]
                     (cond
                       ;; The group left the page mid-hover (no mouseleave).
                       (not (.-isConnected node)) (stop-listening! node)
                       (escape? key-event) (dismiss! node)))]
      (aset node listener-property listener)
      (.addEventListener js/document "keydown" listener))))

(defn- on-mouseleave!
  [event]
  (let [node (.-currentTarget event)]
    (stop-listening! node)
    ;; A tooltip dismissed while focused stays dismissed until focus moves.
    (when-not (focus-inside? node)
      (restore! node))))

(defn- on-focusin!
  [event]
  (restore! (.-currentTarget event)))

(defn- on-focusout!
  [event]
  (let [node (.-currentTarget event)
        next-target (.-relatedTarget event)]
    (when-not (and next-target (.contains node next-target))
      (restore! node))))

(defn group-attrs
  "Attributes for the tooltip's group element (merge them in)."
  []
  {:on {:keydown on-keydown!
        :mouseenter on-mouseenter!
        :mouseleave on-mouseleave!
        :focusin on-focusin!
        :focusout on-focusout!}})

