(ns hyperopen.vol-range.simulator
  "Deterministic exchange, journal and clock simulator for Vol Range lifecycle
  scenarios (HO-SPEC-014 section 14.2). One atom holds the venue's orders,
  fills and balances, the journal rows, the clock and a queue of scripted
  send behaviours. `deps` wires it into the executor exactly as the runtime
  will: same delivery contract, the real admission coordinator."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [hyperopen.api.trading.action-coordinator :as coord]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.fixtures :as fixtures]))

(def coin "SYN/USDC")

(def scope
  {:network :hyperliquid-mainnet :owner-address "0xabc" :account-address "0xabc" :vault-address nil
   :market-type :spot :dex nil :spot-pair-index 1 :asset-id 10001 :coin coin
   :base-token-id nil :quote-token-id nil :symbol "SYN" :quote-symbol "USDC" :sz-decimals 2
   :metadata-version nil})

(defn- d [x] (precision/parse-decimal (str x)))
(defn- ds [x] (precision/decimal->string x))

(defn new-sim []
  (atom {:clock 1700000000000 :orders {} :fills [] :next-oid 9000 :next-tid 1 :ids 0 :suffix 0
         :base "150" :usdc "3000" :script [] :journal {} :journal-fail? false :sends [] :nonce 0
         :bbo-at nil :live {}}))

(defn now [sim] (:clock @sim))
(defn tick! [sim] (:clock (swap! sim update :clock inc)))
(defn advance! [sim ms] (swap! sim update :clock + ms))
(defn script! [sim & behaviours] (swap! sim update :script into behaviours))

;; ---------------------------------------------------------------------------
;; Venue
;; ---------------------------------------------------------------------------

(defn- open-orders [sim]
  (->> (vals (:orders @sim)) (filter #(= "open" (:status %))) (sort-by :oid)))

(defn- order-row [o] (select-keys o [:coin :side :limitPx :sz :origSz :oid :cloid :timestamp]))

(defn- holds [sim]
  (reduce (fn [{:keys [usdc base]} o]
            (if (= "B" (:side o))
              {:usdc (precision/add usdc (precision/mul (d (:limitPx o)) (d (:sz o)))) :base base}
              {:usdc usdc :base (precision/add base (d (:sz o)))}))
          {:usdc (d "0") :base (d "0")}
          (open-orders sim)))

(defn rest-order!
  "Put a resting order on the venue directly (e.g. from another device)."
  [sim {:keys [side price size cloid]}]
  (let [oid (:next-oid @sim)
        o {:coin coin :side (if (= :buy side) "B" "A") :limitPx price :sz size :origSz size
           :oid oid :cloid cloid :timestamp (now sim) :status "open"}]
    (swap! sim #(-> % (update :next-oid inc) (assoc-in [:orders oid] o)))
    oid))

(defn order-by-cloid [sim cloid]
  (first (filter #(= cloid (:cloid %)) (vals (:orders @sim)))))

(defn fill!
  "Fill `sz` of the resting order with `cloid` at its limit price."
  [sim cloid sz]
  (let [o (order-by-cloid sim cloid)
        left (precision/sub (d (:sz o)) (d sz))
        notional (precision/mul (d (:limitPx o)) (d sz))
        buy? (= "B" (:side o))
        tid (:next-tid @sim)]
    (swap! sim
           (fn [s]
             (-> s
                 (update :next-tid inc)
                 (assoc-in [:orders (:oid o) :sz] (ds left))
                 (cond-> (precision/zero-dec? left) (assoc-in [:orders (:oid o) :status] "filled"))
                 (update :base #(ds ((if buy? precision/add precision/sub) (d %) (d sz))))
                 (update :usdc #(ds ((if buy? precision/sub precision/add) (d %) notional)))
                 (update :fills conj {:coin coin :px (:limitPx o) :sz sz :side (:side o) :time (:clock s)
                                      :oid (:oid o) :cloid (:cloid o) :tid tid :fee "0" :feeToken "USDC"
                                      :builderFee "0" :crossed false :hash "0x0"}))))))

(defn post-info [sim]
  (fn [body _opts]
    (js/Promise.resolve
     (case (get body "type")
       "spotClearinghouseState"
       (let [{:keys [usdc base]} (holds sim)]
         {:balances [{:coin "USDC" :total (:usdc @sim) :hold (ds usdc)}
                     {:coin "SYN" :total (:base @sim) :hold (ds base)}]})
       "frontendOpenOrders" (mapv order-row (open-orders sim))
       "l2Book" (when-not (:book-down? @sim)
                  {:coin coin :time (now sim) :levels [[{:px "79.99" :sz "100" :n 1}] [{:px "80.01" :sz "100" :n 1}]]})
       "orderStatus"
       (let [id (get body "oid")
             o (if (number? id) (get-in @sim [:orders id]) (order-by-cloid sim id))]
         (if o
           {:status "order" :order {:order (order-row o) :status (:status o) :statusTimestamp (now sim)}}
           {:status "unknownOid"}))
       ;; Like the venue: ascending by time, at most a page of rows.
       "userFillsByTime"
       (->> (:fills @sim)
            (filter #(<= (get body "startTime") (:time %) (get body "endTime")))
            (sort-by :time)
            (take (or (:fills-page-limit @sim) 2000))
            vec)
       nil))))

(defn- apply-order-action! [sim action {:keys [reject-prices]}]
  {:status "ok"
   :response {:type "order"
              :data {:statuses
                     (mapv (fn [{:keys [b p s c]}]
                             (if (contains? (set reject-prices) p)
                               {:error "Post only order would have immediately matched, bbo was 76.39@76.41. asset=10001"}
                               {:resting {:oid (rest-order! sim {:side (if b :buy :sell) :price p :size s
                                                                 :cloid c})
                                          :cloid c}}))
                           (:orders action))}}})

(defn- apply-cancel-action! [sim action]
  {:status "ok"
   :response {:type (:type action)
              :data {:statuses
                     (mapv (fn [{:keys [o cloid]}]
                             (let [order (if o (get-in @sim [:orders o]) (order-by-cloid sim cloid))]
                               (if (= "open" (:status order))
                                 (do (swap! sim assoc-in [:orders (:oid order) :status] "canceled")
                                     "success")
                                 {:error "Order was never placed, already canceled, or filled. asset=10001"})))
                           (:cancels action))}}})

(defn- deliver! [sim action behaviour]
  (swap! sim update :sends conj action)
  (let [apply! #(if (= "order" (:type action))
                  (apply-order-action! sim action behaviour)
                  (apply-cancel-action! sim action))]
    (case (:kind behaviour)
      :timeout-after-apply (do (apply!) {:delivery :unknown :error "No exchange response before the timeout"
                                         :attempt-recorded? true})
      :timeout-lost {:delivery :unknown :error "No exchange response before the timeout" :attempt-recorded? true}
      :crash (do (apply!) (js/Promise. (fn [_ _] nil)))
      {:delivery :response :body (apply!) :http-status 200 :attempt-recorded? true})))

(defn send
  "The `delivery/sign-and-post-with-delivery!` contract: attempt record
  committed, then the final guard, then HTTP. `:crash-before-attempt` never
  settles and never records an attempt."
  [sim]
  (fn [action {:keys [expires-after on-send-attempt! before-send!]}]
    (let [behaviour (or (first (:script @sim)) {:kind :normal})
          _ (swap! sim update :script #(vec (rest %)))
          nonce (:nonce (swap! sim update :nonce inc))]
      (case (:kind behaviour)
        :crash-before-attempt (js/Promise. (fn [_ _] nil))
        ;; The delivery's pre-send timeout fired while the attempt commit was
        ;; still pending; the commit lands afterwards and nothing is sent.
        :late-attempt (do (js/setTimeout #(on-send-attempt! {:nonce nonce :expires-after expires-after}) 5)
                          (js/Promise.resolve {:delivery :not-sent :phase :pre-send-timeout :nonce nonce
                                               :error "Timed out before sending." :attempt-recorded? false}))
        (-> (js/Promise.resolve nil)
            (.then (fn [_] (on-send-attempt! {:nonce nonce :expires-after expires-after})))
            (.then (fn [_]
                     (-> (js/Promise.resolve (when-let [f (:before-guard behaviour)] (f sim)))
                         (.then (fn [_] (if before-send! (before-send! {:nonce nonce}) true)))
                         (.then (fn [ok]
                                  (if (true? ok)
                                    (deliver! sim action behaviour)
                                    {:delivery :not-sent :phase :final-guard :error (str ok) :nonce nonce
                                     :attempt-recorded? true})))))
                   (fn [e] {:delivery :not-sent :phase :send-attempt-record :error (str e) :nonce nonce
                            :attempt-recorded? false})))))))

;; ---------------------------------------------------------------------------
;; Journal
;; ---------------------------------------------------------------------------

(defn- encode [r] (binding [*print-length* nil *print-level* nil] (pr-str r)))

(defn journal [sim]
  {:commit! (fn [records]
              (if (:journal-fail? @sim)
                (js/Promise.reject (js/Error. "QuotaExceededError"))
                (do (swap! sim update :journal into (map (fn [r] [(:record-id r) (encode r)])) records)
                    (js/Promise.resolve true))))
   :load-all! (fn []
                (if (or (:journal-fail? @sim) (:journal-load-fail? @sim))
                  (js/Promise.reject (js/Error. "IndexedDB unavailable"))
                  (js/Promise.resolve {:records (mapv reader/read-string (vals (:journal @sim)))
                                       :unreadable []})))})

(defn records [sim] (mapv reader/read-string (vals (:journal @sim))))

;; ---------------------------------------------------------------------------
;; Live inputs and deps
;; ---------------------------------------------------------------------------

(defn live-input [sim]
  (let [t (now sim)]
    {:plan-id nil :scope scope :draft fixtures/draft-13-2
     :market (fixtures/market-snapshot {:coin coin
                                        :bbo {:bid "79.99" :ask "80.01" :observed-at-ms (or (:bbo-at @sim) t)}})
     :fees (assoc fixtures/synthetic-fees :loaded-at-ms t)
     :pending-intents [] :clock t :model-version 1 :precision-version 1}))

(defn live [sim]
  (merge {:input (live-input sim) :raw-account-mode "default" :read-only? false
          :execution-enabled? true :agent-ready? true}
         (:live @sim)))

(defn frozen-plan
  "The reviewed plan for the current venue state (spec 13.2 fixture)."
  [sim]
  (let [{:keys [usdc base]} (holds sim)
        read {:account-address "0xabc" :complete? true :received-at-ms (now sim)
              :balances [{:coin "USDC" :total (:usdc @sim) :hold (ds usdc)}
                         {:coin "SYN" :total (:base @sim) :hold (ds base)}]
              :open-orders (mapv order-row (open-orders sim))}
        account (snapshots/account-snapshot-from-read scope read {:raw-account-mode "default"})]
    (planner/prepare-plan (assoc (live-input sim) :plan-id "plan-1" :account account :remediations? false))))

(defn deps [sim]
  {:post-info! (post-info sim)
   :journal (journal sim)
   :send! (send sim)
   :admission! (fn [opts body!]
                 (coord/with-account-admission! (assoc opts :allow-in-tab-coordination? true) body!))
   :live! (fn [] (live sim))
   :now-ms! (fn [] (tick! sim))
   :random-id! (fn [prefix] (str prefix "-" (:ids (swap! sim update :ids inc))))
   :cloid-suffixes! (fn [n] (vec (repeatedly n #(.padStart (.toString (:suffix (swap! sim update :suffix inc)) 16)
                                                           24 "0"))))
   :network :hyperliquid-mainnet})

(defn order-sends [sim] (filterv #(= "order" (:type %)) (:sends @sim)))
(defn cancel-sends [sim] (filterv #(str/starts-with? (:type %) "cancel") (:sends @sim)))
