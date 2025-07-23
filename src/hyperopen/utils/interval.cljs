(ns hyperopen.utils.interval)

;; dispatch on the keyword (e.g. :1m, :5m, :1h…)
(defmulti interval-to-milliseconds
  "Return number of ms for a given interval keyword."
  identity
  :default :1d)

(defmethod interval-to-milliseconds :1m [_] (* 60 1000))
(defmethod interval-to-milliseconds :5m [_] (* 5 (interval-to-milliseconds :1m)))
(defmethod interval-to-milliseconds :15m [_] (* 15 (interval-to-milliseconds :1m)))
(defmethod interval-to-milliseconds :1h [_] (* 60 (interval-to-milliseconds :1m)))
(defmethod interval-to-milliseconds :4h [_] (* 4 (interval-to-milliseconds :1h)))
(defmethod interval-to-milliseconds :1d [_] (* 24 (interval-to-milliseconds :1h)))
(defmethod interval-to-milliseconds :3d [_] (* 3 (interval-to-milliseconds :1d)))
(defmethod interval-to-milliseconds :1w [_] (* 7 (interval-to-milliseconds :1d)))
(defmethod interval-to-milliseconds :1M [_] (* 30 (interval-to-milliseconds :1d)))