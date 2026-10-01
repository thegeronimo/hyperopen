(ns hyperopen.funding.application.hyperevm-run-state
  "Store writes shared by the HyperEVM Transfer effects: the modal's run,
   post-transfer refreshes, and arrival tracking.

   Every write into the modal's run goes through `update-run!`, which checks
   the run's flow id first. A flow whose modal was closed, or reopened on a
   new draft, therefore never writes into it; its result reaches the user
   through toasts and `[:hyperevm :in-flight]` instead.

   Collaborators (`:dispatch!`, `:set-timeout-fn`, `:now-ms-fn`,
   `:refresh-spot-clearinghouse!`, `:log-fn`) are injected and optional: a
   missing timer only skips the delayed steps (the \"slow\" flip and spot
   re-reads)."
  (:require [hyperopen.funding.domain.transfer-run :as transfer-run]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]))

(def run-path
  [:funding-ui :modal :transfer-evm])

(def fast-poll-ms
  "How long HyperEVM balances are read every few seconds after a move."
  60000)

(def spot-refresh-delays-ms
  "When Spot is read again while a move into Spot is arriving. HyperCore
   credits it a few seconds after the HyperEVM receipt."
  [2000 5000 10000 20000 40000])

(defonce ^:private flow-counter
  (atom 0))

(defn now
  [{:keys [now-ms-fn]}]
  (if (fn? now-ms-fn) (now-ms-fn) (js/Date.now)))

(defn next-flow-id
  "A new flow id: the injected `:next-flow-id!`, else a clock-and-counter id."
  [{:keys [next-flow-id!] :as deps}]
  (if (fn? next-flow-id!)
    (next-flow-id!)
    (str "hyperevm-" (now deps) "-" (swap! flow-counter inc))))

(defn- transfer-mode?
  [modal]
  (let [mode (:mode modal)]
    (= "transfer" (cond
                    (keyword? mode) (name mode)
                    (string? mode) mode
                    :else nil))))

(defn run-current?
  "Whether the open modal still shows the run of `flow-id`."
  [state flow-id]
  (and (some? flow-id)
       (true? (get-in state [:funding-ui :modal :open?]))
       (= flow-id (get-in state (conj run-path :flow-id)))))

(defn start-run!
  "Show `run` in the modal, when it is open in Transfer mode with no run."
  [store run]
  (swap! store
         (fn [state]
           (let [modal (get-in state [:funding-ui :modal])]
             (if (and (true? (:open? modal))
                      (transfer-mode? modal)
                      (nil? (:transfer-evm modal)))
               (update-in state [:funding-ui :modal] assoc
                          :transfer-evm run
                          :submitting? true
                          :error nil)
               state)))))

(defn update-run!
  "Apply `f` to the run of `flow-id` (and merge `modal-changes` into the
   modal) only while the modal still shows that run."
  ([store flow-id f]
   (update-run! store flow-id f nil))
  ([store flow-id f modal-changes]
   (swap! store
          (fn [state]
            (if (run-current? state flow-id)
              (cond-> (update-in state run-path f)
                (seq modal-changes) (update-in [:funding-ui :modal] merge modal-changes))
              state)))))

(def finished-modal-changes
  {:submitting? false :error nil})

(defn log-error!
  "Log `err` through the injected `:log-fn`, if any."
  [{:keys [log-fn]} label err]
  (when (fn? log-fn)
    (log-fn (str "HyperEVM transfer: " label " failed:") err))
  nil)

(defn follow-up!
  "Run `f`, work that follows a move that went through (refreshes, toasts,
   arrival tracking). An error there is logged and swallowed: it must never
   reach the run, which would offer to send the funds again."
  [deps label f]
  (try
    (f)
    (catch :default err
      (log-error! deps label err))))

(defn dispatch-actions!
  [{:keys [store dispatch!]} actions]
  (when (and (fn? dispatch!) (seq actions))
    (dispatch! store nil actions)))

(defn refresh-hyperevm-after-transfer!
  "Read HyperEVM balances now (forced) and every few seconds for a minute."
  [deps]
  (dispatch-actions! deps [[:actions/refresh-hyperevm-balances
                            {:force? true
                             :fast-poll-ms fast-poll-ms
                             :now-ms (now deps)}]]))

(defn refresh-core-activation!
  "A move into HyperCore can activate the account (the first USDC in), so
   read the bridge balance and activation again."
  [deps token-index]
  (when (and (int? token-index) (not (neg? token-index)))
    (dispatch-actions! deps [[:actions/refresh-hyperevm-bridge-capacity token-index]])))

(defn refresh-spot!
  "Re-read `owner`'s Spot balances, only while Spot shows them. With a
   subaccount selected or an address spectated, `[:spot :clearinghouse-state]`
   holds that account's balances, and the owner's must never land there.
   (The injected refresh checks again when the reply arrives.)"
  [{:keys [store refresh-spot-clearinghouse!]} owner]
  (when (and (fn? refresh-spot-clearinghouse!)
             (string? owner)
             (transfer-run/spot-shows-owner? @store owner))
    (refresh-spot-clearinghouse! store owner {:priority :high :force-refresh? true})))

(defn set-wallet-chain!
  "Record the chain a verified switch left the wallet on (the chainChanged
   listener may be bound to another provider)."
  [store chain-id]
  (when (string? chain-id)
    (swap! store assoc-in [:wallet :chain-id] chain-id)))

(defn cache-chain-switch-unsupported!
  "Remember that the connected wallet provider cannot switch to HyperEVM.
   The provider key is a string, so this is a store swap, never an
   `:effects/save` path."
  [store]
  (swap! store (fn [state]
                 (update-in state (transfer-state/capabilities-path state)
                            #(assoc (if (map? %) % {}) :chain-switch :unsupported)))))

(defn track-arrival!
  "Watch the destination of a finished move until it shows the expected
   amount (`transfer-run/arrived?`), then mark the run `:arrived`. After
   `arrival-slow-ms` a run still arriving reads `:slow`; the watch keeps
   going (a late arrival still flips it) until `arrival-watch-ms`. A move
   into Spot also re-reads Spot a few times, since HyperCore credits it a
   little after the receipt.

   The watch serves the modal's run only (it toasts nothing), so it stops
   as soon as the modal no longer shows that run (closed, Done, Back to
   edit, a new draft): it runs on every store write. A move into Spot still
   gets its first re-read. Returns an atom that is true once tracking
   stopped, or nil when there is nothing to track."
  [{:keys [store set-timeout-fn] :as deps} flow-id arrival]
  (when (map? arrival)
    (let [watch-key [::arrival flow-id]
          done? (atom false)
          finish! (fn []
                    (when-not @done?
                      (reset! done? true)
                      (remove-watch store watch-key)))
          check! (fn [state]
                   (when-not @done?
                     (cond
                       (not (run-current? state flow-id))
                       (finish!)

                       (transfer-run/arrived? state arrival)
                       (do (finish!)
                           (update-run! store flow-id #(transfer-run/arrived % (now deps)))))))
          spot? (= :spot (:location arrival))
          owner (:owner arrival)]
      (add-watch store watch-key (fn [_ _ _ new-state] (check! new-state)))
      (check! @store)
      (when spot?
        (refresh-spot! deps owner))
      (when (fn? set-timeout-fn)
        (set-timeout-fn (fn []
                          (when-not @done?
                            (update-run! store flow-id transfer-run/slow)))
                        transfer-run/arrival-slow-ms)
        (set-timeout-fn finish! transfer-run/arrival-watch-ms)
        (when spot?
          (doseq [delay-ms spot-refresh-delays-ms]
            (set-timeout-fn (fn []
                              (when-not @done?
                                (refresh-spot! deps owner)))
                            delay-ms))))
      done?)))
