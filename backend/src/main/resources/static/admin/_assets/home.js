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

    // A second series, when the card carries one: workforce planning is
    // planned against actual, and the comparison is the whole point of it.
    const datasets = [{ data: values, backgroundColor: BLUE, borderRadius: 6,
                        maxBarThickness: 34, label: card.dataset.series || '' }];
    if (card.dataset.values2) {
      let second;
      try {
        second = JSON.parse(card.dataset.values2);
      } catch (error) {
        second = null;
      }
      if (second) {
        datasets.push({ data: second, backgroundColor: PALETTE[1], borderRadius: 6,
                        maxBarThickness: 34, label: card.dataset.series2 || '' });
      }
    }

    new Chart(canvas, {
      type: 'bar',
      data: { labels, datasets },
      options: {
        ...cartesian,
        indexAxis: horizontal ? 'y' : 'x',
        plugins: datasets.length > 1
          ? { ...base.plugins,
              legend: { display: true, position: 'bottom',
                        labels: { boxWidth: 10, boxHeight: 10, usePointStyle: true, padding: 14 } } }
          : cartesian.plugins,
        scales: horizontal
          ? { x: { beginAtZero: true, grid: { color: grid }, border: { display: false },
                   ticks: { precision: 0, maxTicksLimit: 5 } },
              y: { grid: { display: false } } }
          : cartesian.scales,
      },
    });
  }

  document.querySelectorAll('.home-chart-card[data-chart]').forEach(build);

  // The banner carousel, as dashboard/pages/home/assets/home.js drives it:
  // the track slides by whole panes and the dots pick one. The interval is
  // legacy's six seconds, and it stops while the pointer is over the banner
  // so a CTA cannot slide out from under a click.
  const track = document.getElementById('bannerTrack');
  const dotsWrap = document.getElementById('bannerDots');
  if (track && dotsWrap) {
    const dots = dotsWrap.querySelectorAll('button');
    const count = dots.length;
    let at = 0;

    function go(index) {
      at = (index + count) % count;
      // Always negative: .home-banner-track is direction:ltr in the copied
      // stylesheet precisely so the slide maths does not depend on the page.
      track.style.transform = 'translateX(-' + at * 100 + '%)';
      dots.forEach((dot, other) => dot.classList.toggle('active', other === at));
    }

    dots.forEach((dot) => {
      dot.addEventListener('click', () => go(parseInt(dot.dataset.i, 10)));
    });

    if (count > 1 && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      let timer = setInterval(() => go(at + 1), 6000);
      const wrap = document.getElementById('bannerCarousel');
      if (wrap) {
        wrap.addEventListener('mouseenter', () => clearInterval(timer));
        wrap.addEventListener('mouseleave', () => {
          timer = setInterval(() => go(at + 1), 6000);
        });
      }
    }
  }
})();
