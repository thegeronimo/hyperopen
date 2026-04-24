(ns hyperopen.portfolio.optimizer.actions)

(defn run-portfolio-optimizer
  [_state request request-signature]
  [[:effects/run-portfolio-optimizer request request-signature]])
