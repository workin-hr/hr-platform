// The home page's charts.
//
// The series are carried on `data-` attributes rather than in an inline
// <script>: JTE escapes an attribute value, so a company named `</script>` or
// `"` is a string in a JSON array and nothing else. Nothing here is templated.
//
// Chart.js is served from /admin/_assets, not a CDN. Same reason as the fonts:
// an admin panel should not need a third party to draw, and an air-gapped or
// blocked deployment renders the same as any other.
(function () {
  if (typeof Chart === 'undefined') {
    return;
  }

  const styles = getComputedStyle(document.body);
  const ink = styles.getPropertyValue('--app-text').trim() || '#1a1d24';
  const muted = styles.getPropertyValue('--app-text-muted').trim() || '#6b7280';
  const grid = 'rgba(15, 35, 70, .08)';

  Chart.defaults.font.family = styles.fontFamily;
  Chart.defaults.color = muted;
  Chart.defaults.borderColor = grid;

  // One palette, ordered so adjacent slices stay distinguishable and so a
  // single-series bar chart is always the brand blue rather than a random pick.
  const BLUE = '#185FA5';
  const PALETTE = ['#185FA5', '#7c3aed', '#0d9488', '#f59e0b', '#ec4899',
                   '#0ea5e9', '#84cc16', '#a3a3a3', '#ef4444', '#14b8a6'];

  const base = {
    responsive: true,
    maintainAspectRatio: false,
    animation: { duration: 320 },
    plugins: {
      legend: { display: false },
      tooltip: {
        backgroundColor: ink, padding: 10, cornerRadius: 8, displayColors: false,
        titleFont: { weight: '600' },
      },
    },
  };

  const cartesian = {
    ...base,
    scales: {
      x: { grid: { display: false }, ticks: { autoSkip: true, maxRotation: 0 } },
      y: { beginAtZero: true, grid: { color: grid }, border: { display: false },
           ticks: { precision: 0, maxTicksLimit: 5 } },
    },
  };

  function build(card) {
    const canvas = card.querySelector('canvas');
    if (!canvas) {
      return;
    }
    let labels;
    let values;
    try {
      labels = JSON.parse(card.dataset.labels || '[]');
      values = JSON.parse(card.dataset.values || '[]');
    } catch (error) {
      return;
    }
    if (!labels.length) {
      return;
    }

    const kind = card.dataset.chart || 'bar';
    if (kind === 'doughnut') {
      new Chart(canvas, {
        type: 'doughnut',
        data: { labels, datasets: [{ data: values, backgroundColor: PALETTE, borderWidth: 0 }] },
        options: {
          ...base,
          cutout: '62%',
          plugins: {
            ...base.plugins,
            legend: { display: true, position: 'bottom',
                      labels: { boxWidth: 10, boxHeight: 10, usePointStyle: true, padding: 14 } },
          },
        },
      });
      return;
    }

    if (kind === 'line') {
      new Chart(canvas, {
        type: 'line',
        data: {
          labels,
          datasets: [{
            data: values, borderColor: BLUE, backgroundColor: 'rgba(24,95,165,.10)',
            fill: true, tension: 0.35, borderWidth: 2,
            pointRadius: 2, pointHoverRadius: 5, pointBackgroundColor: BLUE,
          }],
        },
        options: cartesian,
      });
      return;
    }

    // Horizontal for a category axis with long Arabic labels, vertical
    // otherwise: a rotated Arabic label is unreadable, and the department and
    // branch names are the long ones.
    const horizontal = card.dataset.axis === 'y';
    new Chart(canvas, {
      type: 'bar',
      data: { labels, datasets: [{ data: values, backgroundColor: BLUE, borderRadius: 6,
                                   maxBarThickness: 34 }] },
      options: {
        ...cartesian,
        indexAxis: horizontal ? 'y' : 'x',
        scales: horizontal
          ? { x: { beginAtZero: true, grid: { color: grid }, border: { display: false },
                   ticks: { precision: 0, maxTicksLimit: 5 } },
              y: { grid: { display: false } } }
          : cartesian.scales,
      },
    });
  }

  document.querySelectorAll('.home-chart-card[data-chart]').forEach(build);
})();
