(ns hyperopen.api.market-metadata.perp-dexs)

(defn- parse-number
  [value]
  (cond
    (number? value) value
    (string? value) (let [parsed (js/parseFloat value)]
                      (when (not (js/isNaN parsed))
                        parsed))
    :else nil))

(defn- non-blank-text
  [value]
  (when (string? value)
    (let [text (.trim value)]
      (when (seq text)
        text))))

(defn normalize-perp-dex-payload
  [payload]
  (cond
    (map? payload)
    (let [full-name-by-name (or (:full-name-by-name payload)
                                (:perp-dex-full-name-by-name payload))]
      (cond-> {:dex-names (vec (or (:dex-names payload)
                                   (:perp-dexs payload)
                                   []))
               :fee-config-by-name (or (:fee-config-by-name payload)
                                       (:perp-dex-fee-config-by-name payload)
                                       {})}
        (seq full-name-by-name)
        (assoc :full-name-by-name full-name-by-name)))

    (sequential? payload)
    (reduce (fn [acc entry]
              (cond
                (string? entry)
                (update acc :dex-names conj entry)

                (map? entry)
                (let [name (:name entry)
                      scale (parse-number (or (:deployerFeeScale entry)
                                              (:deployer-fee-scale entry)))
                      full-name (non-blank-text (or (:fullName entry)
                                                    (:full-name entry)))]
                  (if (seq name)
                    (cond-> (update acc :dex-names conj name)
                      (number? scale)
                      (assoc-in [:fee-config-by-name name]
                                {:deployer-fee-scale scale})

                      full-name
                      (assoc-in [:full-name-by-name name] full-name))
                    acc))

                :else
                acc))
            {:dex-names []
             :fee-config-by-name {}}
            payload)

    :else
    {:dex-names []
     :fee-config-by-name {}}))

(defn payload->dex-names
  [payload]
  (:dex-names (normalize-perp-dex-payload payload)))
