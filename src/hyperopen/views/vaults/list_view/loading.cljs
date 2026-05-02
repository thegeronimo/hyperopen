(ns hyperopen.views.vaults.list-view.loading)

(def loading-skeleton-row-count
  5)

(def max-stable-loading-row-count
  10)

(defn- skeleton-block
  [extra-classes]
  [:span {:class (into ["block"
                        "h-3.5"
                        "rounded"
                        "bg-base-300/70"
                        "animate-pulse"]
                       extra-classes)}])

(defn desktop-loading-row
  [idx]
  [:tr {:class ["border-b" "border-base-300/40"]
        :data-role "vault-loading-row"
        :data-index idx}
   [:td {:class ["px-3" "py-3"]} (skeleton-block ["w-40"])]
   [:td {:class ["px-3" "py-3"]} (skeleton-block ["w-24"])]
   [:td {:class ["px-3" "py-3"]} (skeleton-block ["w-14"])]
   [:td {:class ["px-3" "py-3"]} (skeleton-block ["w-20"])]
   [:td {:class ["px-3" "py-3"]} (skeleton-block ["w-24"])]
   [:td {:class ["px-3" "py-3"]} (skeleton-block ["w-10"])]
   [:td {:class ["px-3" "py-3" "text-right"]} (skeleton-block ["ml-auto" "w-20"])]])

(defn mobile-loading-card
  [idx]
  [:div {:class ["rounded-xl"
                 "border"
                 "border-base-300"
                 "bg-base-100"
                 "p-3"
                 "space-y-3"]
         :data-role "vault-loading-card"
         :data-index idx}
   [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
    (skeleton-block ["w-28"])
    (skeleton-block ["w-20"])]
   [:div {:class ["grid" "grid-cols-2" "gap-2"]}
    (skeleton-block ["w-20"])
    (skeleton-block ["w-20"])
    (skeleton-block ["w-24"])
    (skeleton-block ["w-16"])]])
