(ns hyperopen.schema.runtime-registration.pnl-share)

(def effect-binding-rows
  [[:effects/export-pnl-share-card-png :export-pnl-share-card-png]
   [:effects/copy-pnl-share-link :copy-pnl-share-link]
   [:effects/resolve-pnl-share-icon :resolve-pnl-share-icon]])

(def action-binding-rows
  [[:actions/open-pnl-share-card :open-pnl-share-card]
   [:actions/close-pnl-share-card :close-pnl-share-card]
   [:actions/set-pnl-share-option :set-pnl-share-option]
   [:actions/save-pnl-share-card-image :save-pnl-share-card-image]
   [:actions/copy-pnl-share-link :copy-pnl-share-link]
   [:actions/handle-pnl-share-card-keydown :handle-pnl-share-card-keydown]
   [:actions/set-pnl-share-icon :set-pnl-share-icon]])
