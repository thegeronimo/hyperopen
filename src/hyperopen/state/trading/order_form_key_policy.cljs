(ns hyperopen.state.trading.order-form-key-policy)

(def ui-owned-order-form-keys
  [:entry-mode
   :ui-leverage
   :size-input-mode
   :size-input-source
   :size-display])

(def legacy-order-form-ui-flag-keys
  [:pro-order-type-dropdown-open?
   :size-unit-dropdown-open?
   :tpsl-unit-dropdown-open?
   :tif-dropdown-open?
   :price-input-focused?
   :tpsl-panel-open?])

(def legacy-order-form-runtime-keys
  [:submitting?
   :error])

(def legacy-order-form-compatibility-keys
  (into [] (concat legacy-order-form-ui-flag-keys
                   legacy-order-form-runtime-keys)))

(def deprecated-canonical-order-form-keys
  (into [] (concat ui-owned-order-form-keys
                   legacy-order-form-compatibility-keys)))

(def order-form-ui-state-keys
  (set (concat ui-owned-order-form-keys
               legacy-order-form-ui-flag-keys)))

(def ui-owned-order-form-key-set
  (set ui-owned-order-form-keys))

(def legacy-order-form-compatibility-key-set
  (set legacy-order-form-compatibility-keys))

(def deprecated-canonical-order-form-key-set
  (set deprecated-canonical-order-form-keys))

(def canonical-write-blocked-order-form-paths
  (set (map vector deprecated-canonical-order-form-keys)))

(defn ui-owned-order-form-key?
  [key]
  (contains? ui-owned-order-form-key-set key))

(defn legacy-order-form-compatibility-key?
  [key]
  (contains? legacy-order-form-compatibility-key-set key))

(defn deprecated-canonical-order-form-key?
  [key]
  (contains? deprecated-canonical-order-form-key-set key))

(defn canonical-write-blocked-order-form-path?
  [path]
  (contains? canonical-write-blocked-order-form-paths path))

(defn order-form-ui-overrides-from-form
  [form]
  (select-keys (or form {}) ui-owned-order-form-keys))

(defn strip-ui-owned-order-form-keys
  [form]
  (reduce dissoc (or form {}) ui-owned-order-form-keys))

(defn strip-legacy-order-form-compatibility-keys
  [form]
  (reduce dissoc (or form {}) legacy-order-form-compatibility-keys))

(defn strip-deprecated-canonical-order-form-keys
  [form]
  (reduce dissoc (or form {}) deprecated-canonical-order-form-keys))
