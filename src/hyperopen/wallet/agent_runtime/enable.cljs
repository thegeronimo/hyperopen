(ns hyperopen.wallet.agent-runtime.enable
  (:require [hyperopen.wallet.agent-runtime.approval :as approval]
            [hyperopen.wallet.agent-runtime.errors :as errors]
            [hyperopen.wallet.agent-runtime.state-projection :as state]))

(defn- noop
  [& _]
  nil)

(defn- noop-promise
  [& _]
  (js/Promise.resolve nil))

(defn- fallback-local-protection-mode
  [local-protection-mode]
  (if (or (= :passkey local-protection-mode)
          (= "passkey" local-protection-mode))
    :passkey
    :plain))

(def passkey-setup-failed-hint
  "Try again, or turn off passkey protection in Trading Settings to remember the key without it.")

(defn- passkey-local-mode?
  [storage-mode local-protection-mode]
  (and (= :local storage-mode)
       (= :passkey local-protection-mode)))

(defn- cleanup-failed-persistence!
  [{:keys [owner-address
           storage-mode
           clear-agent-session-by-mode!
           clear-unlocked-session!]}]
  (when (seq owner-address)
    (when (fn? clear-agent-session-by-mode!)
      (clear-agent-session-by-mode! owner-address storage-mode))
    (when (fn? clear-unlocked-session!)
      (clear-unlocked-session! owner-address))))

(defn- ignore-delete-failure!
  [delete-promise]
  (if (some? delete-promise)
    (.catch delete-promise (fn [_] (js/Promise.resolve nil)))
    (js/Promise.resolve nil)))

(defn- persist-enabled-agent-session!
  [{:keys [owner-address
           agent-address
           private-key
           storage-mode
           local-protection-mode
           last-approved-at
           nonce-cursor
           passkey-lock-supported?
           create-locked-session!
           cache-unlocked-session!
           persist-passkey-session-metadata!
           delete-locked-session!
           persist-agent-session-by-mode!
           clear-agent-session-by-mode!
           clear-unlocked-session!
           persist-session-error
           current-operation?]
    :or {passkey-lock-supported? (constantly false)
         create-locked-session! noop-promise
         cache-unlocked-session! noop
         persist-passkey-session-metadata! (constantly false)
         delete-locked-session! noop-promise
         persist-session-error "Unable to persist agent credentials."
         current-operation? (constantly true)}}]
  (cond
    (not (current-operation?))
    (js/Promise.resolve nil)

    (passkey-local-mode? storage-mode local-protection-mode)
    (if-not (passkey-lock-supported?)
      (js/Promise.reject
       (errors/known-error "Passkey unlock is unavailable in this browser."))
      (-> (create-locked-session! {:wallet-address owner-address
                                   :session {:agent-address agent-address
                                             :private-key private-key
                                             :last-approved-at last-approved-at
                                             :nonce-cursor nonce-cursor}})
          (.catch
           (fn [err]
             (js/Promise.reject
              (errors/known-error
               (str (or (errors/runtime-error-message err)
                        "The passkey could not be set up.")
                    " "
                    passkey-setup-failed-hint)))))
          (.then
           (fn [{:keys [metadata session]}]
             (cond
               (not (current-operation?))
               (ignore-delete-failure! (delete-locked-session! owner-address))

               (persist-passkey-session-metadata! owner-address metadata)
               (do
                 (cache-unlocked-session! owner-address
                                          (assoc session
                                                 :storage-mode storage-mode
                                                 :local-protection-mode local-protection-mode))
                 {:storage-mode storage-mode
                  :local-protection-mode local-protection-mode
                  :agent-address agent-address
                  :last-approved-at last-approved-at
                  :nonce-cursor nonce-cursor})

               :else
               (-> (delete-locked-session! owner-address)
                   (.then (fn [_]
                            (js/Promise.reject
                             (errors/known-error persist-session-error))))))))))

    (persist-agent-session-by-mode!
     owner-address
     storage-mode
     {:agent-address agent-address
      :private-key private-key
      :last-approved-at last-approved-at
      :nonce-cursor nonce-cursor})
    (do
      (cache-unlocked-session! owner-address
                               {:agent-address agent-address
                                :private-key private-key
                                :last-approved-at last-approved-at
                                :nonce-cursor nonce-cursor
                                :storage-mode storage-mode
                                :local-protection-mode local-protection-mode})
      (js/Promise.resolve {:storage-mode storage-mode
                           :local-protection-mode local-protection-mode
                           :agent-address agent-address
                           :last-approved-at last-approved-at
                           :nonce-cursor nonce-cursor}))

    :else
    (do
      (cleanup-failed-persistence! {:owner-address owner-address
                                    :storage-mode storage-mode
                                    :clear-agent-session-by-mode! clear-agent-session-by-mode!
                                    :clear-unlocked-session! clear-unlocked-session!})
      (js/Promise.reject
       (errors/known-error persist-session-error)))))

(defn enable-agent-trading!
  [{:keys [store
           options
           create-agent-credentials!
           now-ms-fn
           normalize-storage-mode
           normalize-local-protection-mode
           ensure-device-label!
           passkey-lock-supported?
           create-locked-session!
           cache-unlocked-session!
           persist-passkey-session-metadata!
           delete-locked-session!
           default-signature-chain-id-for-environment
           build-approve-agent-action
           format-agent-name-with-valid-until
           approve-agent!
           persist-agent-session-by-mode!
           clear-agent-session-by-mode!
           clear-unlocked-session!
           runtime-error-message
           exchange-response-error]
    :or {normalize-storage-mode identity
         normalize-local-protection-mode fallback-local-protection-mode
         ensure-device-label! (constantly nil)
         passkey-lock-supported? (constantly false)
         create-locked-session! noop-promise
         cache-unlocked-session! noop
         persist-passkey-session-metadata! (constantly false)
         delete-locked-session! noop-promise
         runtime-error-message errors/runtime-error-message
         exchange-response-error errors/exchange-response-error}}]
  (let [{:keys [storage-mode local-protection-mode is-mainnet agent-name signature-chain-id]
         :or {storage-mode :local
              local-protection-mode :plain
              is-mainnet true
              agent-name nil
              signature-chain-id nil}} options
        owner-address (get-in @store [:wallet :address])]
    (if-not (seq owner-address)
      (state/set-agent-error! store "Connect your wallet before enabling trading.")
      (try
        (state/invalidate-operations! store
                                      :active-unlock-token
                                      :active-protection-token)
        (let [operation-token (state/next-operation-token! store :active-enable-token)
              current-operation? #(state/operation-current? store
                                                            :active-enable-token
                                                            operation-token)
              {:keys [private-key agent-address]} (create-agent-credentials!)
              normalized-storage-mode (normalize-storage-mode storage-mode)
              normalized-local-protection-mode
              (normalize-local-protection-mode local-protection-mode)
              device-label (ensure-device-label!)
              resolved-agent-name (or agent-name device-label)]
          (-> (approval/approve-agent-request!
               {:store store
                :owner-address owner-address
                :agent-address agent-address
                :private-key private-key
                :storage-mode normalized-storage-mode
                :is-mainnet is-mainnet
                :agent-name resolved-agent-name
                :signature-chain-id signature-chain-id
                :persist-session? false
                :missing-owner-error "Connect your wallet before enabling trading."
                :persist-session-error "Unable to persist agent credentials."
                :now-ms-fn now-ms-fn
                :normalize-storage-mode normalize-storage-mode
                :default-signature-chain-id-for-environment default-signature-chain-id-for-environment
                :build-approve-agent-action build-approve-agent-action
                :format-agent-name-with-valid-until format-agent-name-with-valid-until
                :approve-agent! approve-agent!
                :persist-agent-session-by-mode! persist-agent-session-by-mode!
                :runtime-error-message runtime-error-message
                :exchange-response-error exchange-response-error})
              (.then
               (fn [{:keys [last-approved-at nonce-cursor]}]
                 (persist-enabled-agent-session!
                  {:owner-address owner-address
                   :agent-address agent-address
                   :private-key private-key
                   :storage-mode normalized-storage-mode
                   :local-protection-mode normalized-local-protection-mode
                   :last-approved-at last-approved-at
                   :nonce-cursor nonce-cursor
                   :passkey-lock-supported? passkey-lock-supported?
                   :create-locked-session! create-locked-session!
                   :cache-unlocked-session! cache-unlocked-session!
                   :persist-passkey-session-metadata! persist-passkey-session-metadata!
                   :delete-locked-session! delete-locked-session!
                   :persist-agent-session-by-mode! persist-agent-session-by-mode!
                   :clear-agent-session-by-mode! clear-agent-session-by-mode!
                   :clear-unlocked-session! clear-unlocked-session!
                   :persist-session-error "Unable to persist agent credentials."
                   :current-operation? current-operation?})))
              (.then
               (fn [session]
                 (when (and session (current-operation?))
                   (state/apply-ready-agent-session! store
                                                     session
                                                     :active-enable-token))))
              (.catch
               (fn [err]
                 (when (current-operation?)
                   (state/set-agent-error!
                    store
                    (errors/known-or-runtime-error-message runtime-error-message err)))))))
        (catch :default err
          (state/set-agent-error! store (runtime-error-message err)))))))
