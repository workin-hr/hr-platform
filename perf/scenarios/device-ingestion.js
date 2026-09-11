// Surface 3: the ZKTeco push receiver.
//
// The one surface with a hard, known cost: a pairing pass issues roughly nine
// statements per punch (PairingQueryBudgetTest ratchets the exact figure), and a terminal
// returning from an outage delivers its whole buffer at once, capped at
// app.devices.ingest.max-records-per-upload. This measures the delivery, which
// is the part a terminal waits on and re-sends if it times out.
import http from 'k6/http';
import { check } from 'k6';
import { BASE, RAMP } from './lib.js';

export const options = {
  stages: RAMP,
  thresholds: {
    // A terminal that does not get an answer re-sends the batch, so a slow
    // receiver turns into more load rather than less.
    // Ratchet, from a measured baseline: 12,634 requests at 20 VUs with
    // 50-record batches gave p95=159ms, avg=58ms, 0 failures (2026-09-10,
    // laptop). 400 leaves room for a busier machine and for a larger
    // PERF_BATCH; 2000 was a guess.
    'http_req_duration{scenario:default}': ['p(95)<400'],
    'http_req_failed': ['rate<0.01'],
  },
};

const SERIAL = __ENV.PERF_SERIAL || 'PERF-LOAD-1';

// The payload is FIXED on purpose -- same base timestamp, pins 7000+(i%50) --
// so every iteration re-sends the same 50 records and the application
// deduplicates them. That is the re-send a terminal really does perform after
// an outage, and it is what the header above says this measures.
//
// It is NOT the insert path: measured, a full ramp stores 50 rows in total,
// while the same ramp with per-iteration timestamps stores 317,300. If you want
// the write cost, vary `base` by __VU and __ITER -- and then do not compare the
// result with the ratchet below, which was set against this payload.
function attlogBatch(records) {
  const lines = [];
  const base = Date.parse('2025-06-02T08:00:00Z');
  for (let i = 0; i < records; i++) {
    const at = new Date(base + i * 60000).toISOString().slice(0, 19).replace('T', ' ');
    // pin \t time \t status \t verify \t workcode \t reserved \t reserved
    lines.push(`${7000 + (i % 50)}\t${at}\t0\t1\t\t0\t0`);
  }
  return lines.join('\n');
}

export default function () {
  // Handshake first, as a real terminal does: it is also the cheapest request
  // here, so a rise in ITS latency points at the app, not at the batch size.
  const handshake = http.get(`${BASE}/iclock/cdata?SN=${SERIAL}&options=all&pushver=2.4.0`);
  check(handshake, { 'handshake answered': (r) => r.status === 200 });

  const upload = http.post(
    `${BASE}/iclock/cdata?SN=${SERIAL}&table=ATTLOG&Stamp=${Date.now()}`,
    attlogBatch(Number(__ENV.PERF_BATCH || 50)),
    { headers: { 'Content-Type': 'text/plain' } },
  );
  check(upload, {
    // An unclaimed serial is refused, which is correct and would make this
    // scenario measure the refusal path instead. Fail loudly rather than
    // report a fast number for the wrong thing.
    'upload accepted (claim PERF-LOAD-1 first)': (r) => r.status === 200,
  });
}
