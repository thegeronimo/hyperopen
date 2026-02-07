function createNoopSeries() {
  return {
    applyOptions() {},
    setData() {},
  };
}

function createChart() {
  const crosshairHandlers = new Set();
  const timeScale = {
    fitContent() {},
    getVisibleLogicalRange() {
      return null;
    },
    setVisibleLogicalRange() {},
  };
  const panes = [{ setHeight() {} }, { setHeight() {} }];

  return {
    addSeries() {
      return createNoopSeries();
    },
    removeSeries() {},
    timeScale() {
      return timeScale;
    },
    panes() {
      return panes;
    },
    subscribeCrosshairMove(handler) {
      crosshairHandlers.add(handler);
    },
    unsubscribeCrosshairMove(handler) {
      crosshairHandlers.delete(handler);
    },
    remove() {
      crosshairHandlers.clear();
    },
  };
}

const AreaSeries = Symbol("AreaSeries");
const BarSeries = Symbol("BarSeries");
const BaselineSeries = Symbol("BaselineSeries");
const CandlestickSeries = Symbol("CandlestickSeries");
const HistogramSeries = Symbol("HistogramSeries");
const LineSeries = Symbol("LineSeries");

module.exports = {
  createChart,
  AreaSeries,
  BarSeries,
  BaselineSeries,
  CandlestickSeries,
  HistogramSeries,
  LineSeries,
};
