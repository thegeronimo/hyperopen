(ns hyperopen.borrow-lend.defaults
  "Default app-state buckets for Earn (HyperCore borrow/lend).")

(defn ui-state
  []
  {:active-tab :markets
   :modal {:open? false
           :kind nil
           :token nil
           :amount ""
           :max? false
           :submitting? false
           :error nil}})

(defn state
  []
  {:reserves []
   :user-state nil
   :user-address nil
   :total-supplied-usd 0
   :interest []
   :interest-address nil
   :loading {:reserves false :user-state false :interest false}
   :errors {:reserves nil :user-state nil :interest nil}
   :loaded-at-ms {:reserves nil :user-state nil :interest nil}})
