(ns hyperopen.portfolio.optimizer.actions.draft-options)

(def objective-models
  {:minimum-variance {:kind :minimum-variance}
   :max-sharpe {:kind :max-sharpe}
   :equal-risk {:kind :equal-risk}
   :inverse-volatility {:kind :inverse-volatility}
   :target-volatility {:kind :target-volatility
                       :target-volatility 0.2}
   :target-return {:kind :target-return
                   :target-return 0.15}})

(def return-models
  {:historical-mean {:kind :historical-mean}
   :ew-mean {:kind :ew-mean
             :alpha 0.015159678336035098}
   :black-litterman {:kind :black-litterman
                     :views []}})

(def risk-models
  {:diagonal-shrink {:kind :diagonal-shrink}
   :ledoit-wolf {:kind :diagonal-shrink}
   :ledoit-wolf-dense {:kind :ledoit-wolf-dense}
   :sample-covariance {:kind :sample-covariance}
   :mixed-frequency {:kind :mixed-frequency}})

(def ^:private max-sharpe-preset
  {:objective {:kind :max-sharpe}
   :return-model {:kind :black-litterman
                  :views []}})

(def setup-presets
  {:conservative {:objective {:kind :minimum-variance}
                  :return-model {:kind :historical-mean}}
   :max-sharpe max-sharpe-preset
   :risk-adjusted max-sharpe-preset
   :use-my-views max-sharpe-preset})

(def objective-menu-options
  {:minimum-volatility {:objective {:kind :minimum-variance}}
   :max-sharpe {:objective {:kind :max-sharpe}
                :return-model-kind :black-litterman}
   :equal-risk {:objective {:kind :equal-risk}}
   :inverse-volatility {:objective {:kind :inverse-volatility}}
   :target-volatility {:objective {:kind :target-volatility
                                   :target-volatility 0.12}}
   :maximum-return {:objective {:kind :target-return
                                :target-return 0.3}}})

(def numeric-constraint-keys
  #{:max-asset-weight
    :gross-min
    :gross-max
    :net-min
    :net-max
    :net-band-pct
    :dust-usdc
    :max-turnover
    :rebalance-tolerance})

(def clearable-numeric-constraint-keys
  #{:gross-min
    :max-turnover})

(def boolean-constraint-keys
  #{:long-only?
    :include-spot?})

(def numeric-objective-parameter-keys
  #{:target-return
    :target-volatility})

(def numeric-execution-assumption-keys
  #{:fallback-slippage-bps
    :manual-capital-usdc})

(def keyword-execution-assumption-keys
  #{:default-order-type
    :fee-mode})

(def instrument-filter-keys
  #{:allowlist
    :blocklist})

(def numeric-asset-override-keys
  #{:max-weight
    :perp-max-weight})

(def boolean-asset-override-keys
  #{:held-lock?})
