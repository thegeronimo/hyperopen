(ns hyperopen.domain.trading.indicators.math-kernel-bench-baselines)

(def kernel-benchmark-snapshot
  {:captured-at "2026-02-15"
   :soft-threshold-multiplier 2.5
   :hard-limit-ms 8000
   :kernels {:rolling-regression {:baseline-ms 1200
                                  :input-size 5000
                                  :period 30}
             :rolling-correlation {:baseline-ms 1200
                                   :input-size 5000
                                   :period 40}
             :zigzag-pivots {:baseline-ms 1000
                             :input-size 6000
                             :threshold 0.03}}})

(def strict-benchmark-env-key "HYPEROPEN_STRICT_KERNEL_BENCH")
