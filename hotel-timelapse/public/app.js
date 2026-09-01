/* hotel-timelapse -- plays a property's month one day at a time.
   Classic script, no modules: module scripts are blocked under file://, and
   double-clicking public/index.html has to work. See BUILD_SPEC.md. */
(function () {
  'use strict';

  var SCHEMA = 'hotel-timelapse/property@1';
  var MS_PER_DAY_AT_1X = 400;
  var SPEEDS = [0.5, 1, 2, 4];

  var SERIES = [
    { key: 'occupancy', label: 'Occupancy', color: 'var(--occupancy)', kind: 'percent', digits: 1 },
    { key: 'adr',       label: 'ADR',       color: 'var(--adr)',       kind: 'currency', digits: 2 },
    { key: 'revpar',    label: 'RevPAR',    color: 'var(--revpar)',    kind: 'currency', digits: 2 }
  ];

  var doc = null;
  var currency = 'USD';
  var state = { i: 0, playing: false, speed: 1, metric: 'occupancy', hover: -1 };
  var timer = null;

  var $ = function (id) { return document.getElementById(id); };

  /* ---------- formatting ---------- */

  function money(v, digits) {
    return new Intl.NumberFormat(undefined, {
      style: 'currency', currency: currency,
      minimumFractionDigits: digits, maximumFractionDigits: digits
    }).format(v);
  }

  function count(v) { return new Intl.NumberFormat().format(v); }

  function pct(v, digits) { return (v * 100).toFixed(digits) + '%'; }

  function formatValue(kind, v, digits) {
    if (kind === 'percent') return pct(v, digits);
    if (kind === 'currency') return money(v, digits);
    return count(v);
  }

  /* Parse YYYY-MM-DD as a local date. new Date('2026-06-01') is UTC midnight,
     which renders as the previous day west of Greenwich. */
  function parseDate(iso) {
    var p = iso.split('-');
    return new Date(+p[0], +p[1] - 1, +p[2]);
  }

  function longDate(iso) {
    return parseDate(iso).toLocaleDateString(undefined, { month: 'long', day: 'numeric', year: 'numeric' });
  }

  function shortDate(iso) {
    return parseDate(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  }

  /* ---------- loading ---------- */

  function bootstrap() {
    var params = new URLSearchParams(location.search);
    var src = params.get('data') || 'property.demo.json';

    var inline = window.HOTEL_TIMELAPSE_DATA || null;

    if (typeof fetch !== 'function' || location.protocol === 'file:') {
      // file:// forbids fetching a sibling file; the generated sidecar is the
      // same document, written by the same run of the generator.
      if (inline) return start(inline, 'property.demo.js (offline copy)');
      return fail('This page needs its data file, and a page opened straight from disk is not allowed to read one.',
        src, 'Run:  python3 -m http.server 8000 --directory public   then open http://localhost:8000');
    }

    fetch(src)
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status + ' ' + r.statusText);
        return r.json();
      })
      .then(function (json) { start(json, src); })
      .catch(function (err) {
        if (inline && !params.get('data')) return start(inline, 'property.demo.js (offline copy)');
        fail('The data file could not be read: ' + err.message, src,
          'Check the file exists and is valid JSON.');
      });
  }

  function fail(what, source, hint) {
    $('app').hidden = true;
    var box = $('failure');
    box.hidden = false;
    $('failure-what').textContent = what;
    $('failure-source').textContent = source ? 'source: ' + source : '';
    $('failure-hint').textContent = hint || '';
  }

  /* ---------- validation ---------- */

  var MONEY_TOL = 0.5;
  var RATIO_TOL = 5e-4;

  function validate(d) {
    if (!d || typeof d !== 'object') return 'The file did not contain a JSON object.';
    if (d.schema !== SCHEMA) return 'Wrong schema: expected "' + SCHEMA + '", found "' + d.schema + '".';
    if (!d.property || typeof d.property.rooms !== 'number') return 'The document has no property.rooms.';
    if (!Array.isArray(d.days) || d.days.length === 0) return 'The document has no days to play.';

    var rooms = d.property.rooms;
    var previous = null;

    for (var i = 0; i < d.days.length; i++) {
      var r = d.days[i];
      var at = 'day ' + (i + 1) + ' (' + r.date + ')';

      if (typeof r.date !== 'string') return 'Missing date on day ' + (i + 1) + '.';
      var day = parseDate(r.date);
      if (isNaN(day)) return 'Unreadable date at ' + at + '.';
      if (previous) {
        var gap = Math.round((day - previous) / 86400000);
        if (gap !== 1) return 'Days must run consecutively: ' + at + ' follows a ' + gap + '-day step.';
      }
      previous = day;

      if (r.rooms_sold > rooms) return 'Oversold at ' + at + ': ' + r.rooms_sold + ' of ' + rooms + ' rooms.';
      if (Math.abs(r.occupancy - r.rooms_sold / rooms) > RATIO_TOL) return 'occupancy does not match rooms_sold / rooms_available at ' + at + '.';
      if (Math.abs(r.room_revenue - r.rooms_sold * r.adr) > MONEY_TOL) return 'room_revenue does not match rooms_sold x adr at ' + at + '.';
      if (Math.abs(r.revpar - r.room_revenue / rooms) > MONEY_TOL) return 'revpar does not match room_revenue / rooms_available at ' + at + '.';

      var seg = r.segments || {};
      var sum = Object.keys(seg).reduce(function (a, k) { return a + seg[k]; }, 0);
      if (sum !== r.rooms_sold) return 'segments sum to ' + sum + ' but ' + r.rooms_sold + ' rooms were sold at ' + at + '.';
    }
    return null;
  }

  /* ---------- start ---------- */

  function start(d, source) {
    var problem = validate(d);
    if (problem) return fail(problem, source, 'The four data invariants are listed in BUILD_SPEC.md.');

    doc = d;
    currency = (d.property && d.property.currency) || 'USD';
    $('failure').hidden = true;
    $('app').hidden = false;

    $('property-name').textContent = d.property.name || 'Property';
    $('property-locale').textContent = d.property.locale || '';
    $('property-rooms').textContent = count(d.property.rooms) + ' rooms';
    $('period-label').textContent = longDate(d.days[0].date) + ' – ' + longDate(d.days[d.days.length - 1].date);
    $('synthetic-badge').hidden = d.synthetic !== true;
    $('foot').textContent = (d.synthetic === true ? d.notice + ' ' : '') + 'Source: ' + source + '.';

    $('scrub').max = String(d.days.length - 1);
    $('table-caption').textContent = 'Days played so far, of ' + d.days.length + '.';

    buildSeriesSwitch();
    buildSpeeds();
    wireTransport();
    render();

    window.addEventListener('resize', drawChart);
  }

  /* ---------- controls ---------- */

  function buildSeriesSwitch() {
    var host = document.querySelector('.series-switch');
    host.innerHTML = '';
    SERIES.forEach(function (s) {
      var b = document.createElement('button');
      b.type = 'button';
      b.setAttribute('aria-pressed', String(s.key === state.metric));
      b.dataset.key = s.key;
      // The metric name is always present as text: light-mode aqua is below
      // 3:1 on the surface, so the swatch never carries identity alone.
      b.innerHTML = '<span class="swatch" style="background:' + s.color + '"></span>' + s.label;
      b.addEventListener('click', function () {
        state.metric = s.key;
        buildSeriesSwitch();
        render();
      });
      host.appendChild(b);
    });
  }

  function buildSpeeds() {
    var host = document.querySelector('.speeds');
    host.innerHTML = '';
    SPEEDS.forEach(function (sp) {
      var b = document.createElement('button');
      b.type = 'button';
      b.textContent = sp + '×';
      b.setAttribute('aria-pressed', String(sp === state.speed));
      b.addEventListener('click', function () {
        state.speed = sp;
        buildSpeeds();
        if (state.playing) { stop(); play(); }
      });
      host.appendChild(b);
    });
  }

  function wireTransport() {
    $('btn-play').addEventListener('click', function () { state.playing ? stop() : play(); });
    $('btn-prev').addEventListener('click', function () { stop(); step(-1); });
    $('btn-next').addEventListener('click', function () { stop(); step(1); });

    $('scrub').addEventListener('input', function (e) {
      stop();
      state.i = +e.target.value;
      render();
    });

    $('btn-table').addEventListener('click', function () {
      var panel = $('table-panel');
      var open = panel.hidden;
      panel.hidden = !open;
      this.setAttribute('aria-expanded', String(open));
      this.textContent = open ? 'Hide data table' : 'Show data table';
    });

    document.addEventListener('keydown', function (e) {
      var t = e.target;
      // A focused slider or text field owns its own keys entirely; a focused
      // button only owns the keys that activate it, so Home/End/arrows still
      // drive the transport after someone has clicked play.
      if (t.matches('input, select, textarea')) return;
      if (t.matches('button') && (e.key === ' ' || e.key === 'Enter')) return;
      if (e.key === ' ') { e.preventDefault(); state.playing ? stop() : play(); }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); stop(); step(-1); }
      else if (e.key === 'ArrowRight') { e.preventDefault(); stop(); step(1); }
      else if (e.key === 'Home') { e.preventDefault(); stop(); state.i = 0; render(); }
      else if (e.key === 'End') { e.preventDefault(); stop(); state.i = doc.days.length - 1; render(); }
    });

    wireHover();
  }

  function step(n) {
    state.i = Math.min(Math.max(state.i + n, 0), doc.days.length - 1);
    render();
  }

  function play() {
    if (state.i >= doc.days.length - 1) state.i = 0;   // replay from the top
    state.playing = true;
    $('btn-play').innerHTML = '&#10073;&#10073;';
    $('btn-play').setAttribute('aria-label', 'Pause');
    tick();
    render();
  }

  function tick() {
    timer = setTimeout(function () {
      if (state.i >= doc.days.length - 1) { stop(); return; }
      state.i += 1;
      render();
      tick();
    }, MS_PER_DAY_AT_1X / state.speed);
  }

  function stop() {
    state.playing = false;
    clearTimeout(timer);
    $('btn-play').innerHTML = '&#9654;';
    $('btn-play').setAttribute('aria-label', 'Play');
    render();
  }

  /* ---------- render ---------- */

  function activeSeries() {
    return SERIES.filter(function (s) { return s.key === state.metric; })[0];
  }

  function render() {
    var row = doc.days[state.i];
    var s = activeSeries();

    $('day-dow').textContent = parseDate(row.date).toLocaleDateString(undefined, { weekday: 'long' });
    $('day-date').textContent = longDate(row.date);

    $('hero-label').textContent = s.label;
    $('hero-value').textContent = formatValue(s.kind, row[s.key], s.digits);
    $('chart-title').textContent = s.label + ', day by day';

    var others = SERIES.filter(function (x) { return x.key !== s.key; });
    var pieces = others.map(function (x) {
      return '<dt>' + x.label + '</dt><dd>' + formatValue(x.kind, row[x.key], x.digits) + '</dd>';
    });
    pieces.push('<dt>Rooms sold</dt><dd>' + count(row.rooms_sold) + ' / ' + count(row.rooms_available) + '</dd>');
    $('alongside').innerHTML = pieces.join('');

    $('scrub').value = String(state.i);
    renderMtd();
    renderAnnotations();
    renderTable();
    drawChart();
  }

  function renderMtd() {
    var sold = 0, revenue = 0, available = 0;
    for (var i = 0; i <= state.i; i++) {
      sold += doc.days[i].rooms_sold;
      revenue += doc.days[i].room_revenue;
      available += doc.days[i].rooms_available;
    }
    var occ = sold / available;
    var adr = sold ? revenue / sold : 0;
    var revpar = revenue / available;
    $('mtd-list').innerHTML =
      '<div><dt>Occupancy</dt><dd>' + pct(occ, 1) + '</dd></div>' +
      '<div><dt>ADR</dt><dd>' + money(adr, 2) + '</dd></div>' +
      '<div><dt>RevPAR</dt><dd>' + money(revpar, 2) + '</dd></div>' +
      '<div><dt>Room revenue</dt><dd>' + money(revenue, 0) + '</dd></div>' +
      '<div><dt>Rooms sold</dt><dd>' + count(sold) + '</dd></div>';
  }

  function renderAnnotations() {
    var upTo = doc.days[state.i].date;
    var seen = (doc.annotations || []).filter(function (a) { return a.date <= upTo; });
    $('annotations').innerHTML = seen.length
      ? seen.map(function (a) { return '<span>' + shortDate(a.date) + ' — ' + escapeHtml(a.label) + '</span>'; }).join('')
      : '';
  }

  function renderTable() {
    var body = $('data-table').querySelector('tbody');
    var rows = [];
    for (var i = 0; i <= state.i; i++) {
      var r = doc.days[i];
      rows.push('<tr data-current="' + (i === state.i) + '">' +
        '<td>' + r.date + '</td><td>' + r.dow + '</td>' +
        '<td>' + count(r.rooms_sold) + '</td>' +
        '<td>' + pct(r.occupancy, 1) + '</td>' +
        '<td>' + money(r.adr, 2) + '</td>' +
        '<td>' + money(r.revpar, 2) + '</td>' +
        '<td>' + money(r.room_revenue, 0) + '</td></tr>');
    }
    body.innerHTML = rows.join('');
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  /* ---------- chart ---------- */

  var PAD = { t: 14, r: 18, b: 34, l: 62 };
  var geom = null;

  /* Zero-baselined so the area wash means what it looks like; the top is the
     period's own maximum rounded up, and it never rescales during playback. */
  function scaleFor(key) {
    var max = 0;
    doc.days.forEach(function (r) { if (r[key] > max) max = r[key]; });
    var raw = max / 5;
    var mag = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10));
    var norm = raw / mag;
    var step = (norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 2.5 ? 2.5 : norm <= 5 ? 5 : 10) * mag;
    var top = Math.ceil(max / step) * step;
    var ticks = [];
    for (var v = 0; v <= top + step / 2; v += step) ticks.push(+v.toFixed(10));
    return { top: top, ticks: ticks };
  }

  function drawChart() {
    var svg = $('chart');
    var wrap = $('chart-wrap');
    var w = wrap.clientWidth || 900;
    var h = 320;
    svg.setAttribute('viewBox', '0 0 ' + w + ' ' + h);
    svg.setAttribute('width', w);
    svg.setAttribute('height', h);

    var s = activeSeries();
    var days = doc.days;
    var n = days.length;
    var sc = scaleFor(s.key);

    // A 62px gutter is a third of a phone's plot. Tick labels are at most
    // "100%" or "$400", so 44 is enough where width is scarce.
    var pad = { t: PAD.t, r: PAD.r, b: PAD.b, l: w < 520 ? 44 : PAD.l };
    var plotW = w - pad.l - pad.r;
    var plotH = h - pad.t - pad.b;
    var band = plotW / n;
    var x = function (i) { return pad.l + band * (i + 0.5); };
    var y = function (v) { return pad.t + plotH * (1 - v / sc.top); };
    geom = { x: x, band: band, n: n, y: y, pad: pad };

    var out = [];

    // Friday and Saturday, the shape every hotel month is built around.
    for (var i = 0; i < n; i++) {
      var wd = parseDate(days[i].date).getDay();
      if (wd === 5 || wd === 6) {
        out.push('<rect x="' + (x(i) - band / 2).toFixed(2) + '" y="' + pad.t + '" width="' + band.toFixed(2) +
          '" height="' + plotH + '" fill="var(--grid)" opacity="0.45"/>');
      }
    }

    sc.ticks.forEach(function (t) {
      out.push('<line x1="' + pad.l + '" y1="' + y(t).toFixed(2) + '" x2="' + (w - pad.r) + '" y2="' + y(t).toFixed(2) +
        '" stroke="' + (t === 0 ? 'var(--axis)' : 'var(--grid)') + '" stroke-width="1"/>');
      out.push('<text x="' + (pad.l - 8) + '" y="' + (y(t) + 4).toFixed(2) + '" text-anchor="end" ' +
        'font-size="11" fill="var(--ink-muted)">' + tickLabel(s, t) + '</text>');
    });

    var every = Math.max(1, Math.ceil(n / Math.max(3, Math.floor(plotW / 30))));
    var marks = [];
    for (var d = 0; d < n; d += every) marks.push(d);
    // The last day always gets a label, but not shoulder to shoulder with the
    // one before it -- "28 30" collides on a phone.
    if (marks[marks.length - 1] !== n - 1) {
      if (n - 1 - marks[marks.length - 1] < every * 0.7) marks.pop();
      marks.push(n - 1);
    }
    marks.forEach(function (d) {
      out.push('<text x="' + x(d).toFixed(2) + '" y="' + (h - pad.b + 16) + '" text-anchor="middle" ' +
        'font-size="11" fill="var(--ink-muted)">' + parseDate(days[d].date).getDate() + '</text>');
    });

    // Annotation ticks sit on the axis from the first frame, unlabelled, so a
    // viewer can see something is coming before it arrives.
    (doc.annotations || []).forEach(function (a) {
      var idx = indexOfDate(a.date);
      if (idx < 0) return;
      var reached = idx <= state.i;
      out.push('<rect x="' + (x(idx) - 1).toFixed(2) + '" y="' + (pad.t + plotH + 2) + '" width="2" height="7" rx="1" fill="' +
        (reached ? 'var(--ink-2)' : 'var(--axis)') + '"/>');
    });

    var pts = [];
    for (var k = 0; k <= state.i; k++) pts.push([x(k), y(days[k][s.key])]);

    if (pts.length) {
      var line = pts.map(function (p, j) { return (j ? 'L' : 'M') + p[0].toFixed(2) + ' ' + p[1].toFixed(2); }).join(' ');
      var base = pad.t + plotH;
      out.push('<path d="' + line + ' L' + pts[pts.length - 1][0].toFixed(2) + ' ' + base + ' L' + pts[0][0].toFixed(2) + ' ' + base + ' Z" fill="' + s.color + '" opacity="0.10"/>');
      out.push('<path d="' + line + '" fill="none" stroke="' + s.color + '" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>');

      var last = pts[pts.length - 1];
      out.push('<circle cx="' + last[0].toFixed(2) + '" cy="' + last[1].toFixed(2) + '" r="4.5" fill="' + s.color + '" stroke="var(--surface)" stroke-width="2"/>');
      out.push('<line x1="' + last[0].toFixed(2) + '" y1="' + pad.t + '" x2="' + last[0].toFixed(2) + '" y2="' + base + '" stroke="' + s.color + '" stroke-width="1" opacity="0.35"/>');
    }

    if (state.hover >= 0 && state.hover <= state.i) {
      var hx = x(state.hover), hy = y(days[state.hover][s.key]);
      out.push('<circle cx="' + hx.toFixed(2) + '" cy="' + hy.toFixed(2) + '" r="5" fill="none" stroke="' + s.color + '" stroke-width="2"/>');
    }

    svg.innerHTML = out.join('');
    svg.setAttribute('aria-label', s.label + ' for ' + doc.property.name + ', ' +
      (state.i + 1) + ' of ' + n + ' days played. Full figures are in the data table.');
  }

  function tickLabel(s, v) {
    if (s.kind === 'percent') return Math.round(v * 100) + '%';
    return money(v, 0);
  }

  function indexOfDate(iso) {
    for (var i = 0; i < doc.days.length; i++) if (doc.days[i].date === iso) return i;
    return -1;
  }

  /* ---------- hover ---------- */

  function wireHover() {
    var wrap = $('chart-wrap');
    var tip = $('tooltip');

    function showAt(clientX) {
      if (!geom) return;
      var box = wrap.getBoundingClientRect();
      var i = Math.round((clientX - box.left - geom.pad.l) / geom.band - 0.5);
      if (i < 0 || i > state.i) return hideTip();

      state.hover = i;
      var r = doc.days[i];
      tip.innerHTML = '<p class="tt-date">' + r.dow + ' ' + shortDate(r.date) + '</p>' +
        SERIES.map(function (x) {
          return '<div class="tt-row"><span>' + x.label + '</span><b>' + formatValue(x.kind, r[x.key], x.digits) + '</b></div>';
        }).join('') +
        '<div class="tt-row"><span>Rooms sold</span><b>' + count(r.rooms_sold) + '</b></div>';
      tip.hidden = false;

      // Keep the bubble inside the chart on a narrow screen.
      var half = tip.offsetWidth / 2;
      tip.style.left = Math.min(Math.max(geom.x(i), half + 2), wrap.clientWidth - half - 2) + 'px';
      tip.style.top = (geom.y(r[state.metric]) - 14) + 'px';
      drawChart();
    }

    wrap.addEventListener('mousemove', function (e) { showAt(e.clientX); });
    wrap.addEventListener('mouseleave', hideTip);

    // A phone has no hover. Tap a played day to read it; tap anywhere else to
    // put the bubble away.
    wrap.addEventListener('pointerdown', function (e) {
      if (e.pointerType === 'mouse') return;
      showAt(e.clientX);
    });

    document.addEventListener('pointerdown', function (e) {
      if (e.pointerType !== 'mouse' && !wrap.contains(e.target)) hideTip();
    });

    function hideTip() {
      if (state.hover === -1) return;
      state.hover = -1;
      tip.hidden = true;
      drawChart();
    }
  }

  bootstrap();
})();
