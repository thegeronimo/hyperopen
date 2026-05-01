(ns hyperopen.portfolio.optimizer.actions.tracking)

(def manual-tracking-source-statuses
  #{:saved :computed})

(defn refresh-portfolio-optimizer-tracking
  [state]
  (if (contains? #{:executed :partially-executed :tracking}
                 (get-in state [:portfolio :optimizer :active-scenario :status]))
    [[:effects/refresh-portfolio-optimizer-tracking]]
    []))

(defn enable-portfolio-optimizer-manual-tracking
  [state]
  (if (and (contains? manual-tracking-source-statuses
                      (get-in state [:portfolio :optimizer :active-scenario :status]))
           (or (get-in state [:portfolio :optimizer :active-scenario :loaded-id])
               (get-in state [:portfolio :optimizer :draft :id])))
    [[:effects/enable-portfolio-optimizer-manual-tracking]]
    []))
