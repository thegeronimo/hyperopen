(ns hyperopen.runtime.effect-adapters.pnl-share
  "Browser-side effects for the PnL share card: rasterize the on-screen card
   into a PNG and hand it to the platform, and copy the sharer's referral link.

   The pure decisions live in `hyperopen.pnl-share.actions` and the rasterizer
   in `hyperopen.pnl-share.raster`; only DOM, clock and clipboard side effects
   live here. Kept out of `hyperopen.runtime.effect-adapters` because that
   namespace already carries a size exception."
  (:require [hyperopen.platform :as platform]
            [hyperopen.pnl-share.naming :as naming]
            [hyperopen.pnl-share.raster :as raster]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.wallet.copy-feedback-runtime :as copy-feedback-runtime]))

(def card-selector
  "[data-role='pnl-share-card-svg']")

(def card-fonts
  "JetBrains Mono is self-hosted and same-origin, so it can be fetched and
   base64-inlined into the SVG. A rasterized SVG cannot load a font by URL, so
   without this the exported PNG would silently fall back to a generic face."
  [{:family "JetBrains Mono" :weight 400 :url "/fonts/JetBrainsMono-Regular.woff2"}
   {:family "JetBrains Mono" :weight 500 :url "/fonts/JetBrainsMono-Medium.woff2"}])

(defn- show-feedback!
  [store kind message]
  (let [runtime runtime-state/runtime]
    (copy-feedback-runtime/clear-wallet-copy-feedback-timeout-in-runtime!
     runtime
     platform/clear-timeout!)
    (copy-feedback-runtime/set-wallet-copy-feedback! store kind message)
    (copy-feedback-runtime/schedule-wallet-copy-feedback-clear!
     {:store store
      :runtime runtime
      :clear-wallet-copy-feedback! copy-feedback-runtime/clear-wallet-copy-feedback!
      :clear-wallet-copy-feedback-timeout!
      #(copy-feedback-runtime/clear-wallet-copy-feedback-timeout-in-runtime!
        runtime
        platform/clear-timeout!)
      :wallet-copy-feedback-duration-ms runtime-state/wallet-copy-feedback-duration-ms
      :set-timeout-fn platform/set-timeout!})))

(defn- card-node
  []
  (when (exists? js/document)
    (.querySelector js/document card-selector)))

(defn- shareable-file?
  [file]
  (boolean
   (when-let [navigator (some-> js/globalThis .-navigator)]
     (and (fn? (.-share navigator))
          (fn? (.-canShare navigator))
          (.canShare navigator #js {:files #js [file]})))))

(defn download-blob!
  [blob file-name]
  (let [url (.createObjectURL js/URL blob)
        link (.createElement js/document "a")]
    (set! (.-href link) url)
    (set! (.-download link) file-name)
    (.appendChild (.-body js/document) link)
    (.click link)
    (.removeChild (.-body js/document) link)
    (.revokeObjectURL js/URL url)
    true))

(defn deliver-png!
  "Hands the PNG to the platform: the native share sheet where the browser
   offers one -- the only reliable path on iOS Safari, where an anchor download
   of a blob often does nothing -- and a blob download everywhere else."
  [blob file-name]
  (let [file (js/File. #js [blob] file-name #js {:type "image/png"})]
    (if (shareable-file? file)
      (-> (.share (.-navigator js/globalThis) #js {:files #js [file] :title "Share card"})
          (.catch (fn [_] (download-blob! blob file-name))))
      (js/Promise.resolve (download-blob! blob file-name)))))

(defn export-pnl-share-card-png
  ([ctx store args]
   (export-pnl-share-card-png ctx store args {}))
  ([_ store {:keys [coin side]} {:keys [now-ms render-png! deliver! node]}]
   (let [render (or render-png! raster/render-png)
         deliver (or deliver! deliver-png!)
         file-name (naming/card-file-name coin side (or now-ms (js/Date.now)))
         target (or node (card-node))]
     (if (nil? target)
       (do
         (show-feedback! store :error "The card is not ready yet. Try again in a moment.")
         false)
       (-> (render target #js {:scale 2 :fonts (clj->js card-fonts)})
           (.then (fn [result] (deliver (.-blob result) file-name)))
           (.catch (fn [error]
                     (show-feedback! store
                                     :error
                                     (or (some-> error .-message)
                                         "Couldn't build the image."))
                     false)))))))

(defn copy-pnl-share-link
  ([ctx store code]
   (copy-pnl-share-link ctx store code {}))
  ([_ store code {:keys [origin copy-link!]}]
   (let [origin* (or origin
                     (some-> js/globalThis .-location .-origin)
                     "https://hyperopen.xyz")
         referral? (boolean (seq code))
         link (if referral?
                (str origin* "/join/" code)
                origin*)
         runtime runtime-state/runtime
         copy! (or copy-link! copy-feedback-runtime/copy-share-link!)]
     (copy! {:store store
             :url link
             :referral? referral?
             :set-wallet-copy-feedback! copy-feedback-runtime/set-wallet-copy-feedback!
             :clear-wallet-copy-feedback! copy-feedback-runtime/clear-wallet-copy-feedback!
             :clear-wallet-copy-feedback-timeout!
             #(copy-feedback-runtime/clear-wallet-copy-feedback-timeout-in-runtime!
               runtime
               platform/clear-timeout!)
             :schedule-wallet-copy-feedback-clear!
             (fn [store*]
               (copy-feedback-runtime/schedule-wallet-copy-feedback-clear!
                {:store store*
                 :runtime runtime
                 :clear-wallet-copy-feedback! copy-feedback-runtime/clear-wallet-copy-feedback!
                 :clear-wallet-copy-feedback-timeout!
                 #(copy-feedback-runtime/clear-wallet-copy-feedback-timeout-in-runtime!
                   runtime
                   platform/clear-timeout!)
                 :wallet-copy-feedback-duration-ms runtime-state/wallet-copy-feedback-duration-ms
                 :set-timeout-fn platform/set-timeout!}))
             :log-fn (fn [& _] nil)}))))
