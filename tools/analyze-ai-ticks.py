"""Summarize per-tick synchronous PathFinder wall time in instrumented development runs."""
import argparse
import csv
import json
import math
import statistics
from pathlib import Path


def percentile(values, p):
    return sorted(values)[max(0, math.ceil(len(values) * p) - 1)] if values else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv', type=Path)
    parser.add_argument('--expected-samples', type=int, required=True)
    args = parser.parse_args()
    with args.csv.open(encoding='utf-8', newline='') as source:
        rows = list(csv.DictReader(source))
    if args.expected_samples < 1 or len(rows) != args.expected_samples:
        raise SystemExit('Unexpected sample count')
    values = []
    for i, r in enumerate(rows, 1):
        if int(r['sample']) != i or r['search_instrumented'].lower() != 'true':
            raise SystemExit(f'Sample {i} is missing instrumentation or out of order')
        tick, calls, done, pops, incl, outer, depth_start, depth_end = (int(r[k]) for k in
            ('tick_duration_ns', 'search_calls', 'search_completed', 'search_heap_pops', 'search_inclusive_ns',
             'search_outer_ns', 'search_open_start', 'search_open_end'))
        if min(tick, calls, done, pops, incl, outer, depth_start, depth_end) < 0 or depth_start or depth_end or calls != done or outer > tick or outer > incl:
            raise SystemExit(f'Invalid or crossing search timing in sample {i}')
        values.append((tick, calls, pops, outer))
    if not sum(x[1] for x in values):
        raise SystemExit('No search calls in measurement window')
    slow = sorted(values, key=lambda x: x[0], reverse=True)[:max(1, math.ceil(len(values) * .05))]
    with_search = [x for x in values if x[1]]
    total_tick, total_outer = sum(x[0] for x in values), sum(x[3] for x in values)
    as_ms = lambda n: n / 1_000_000
    result = {'samples': len(values), 'ticks_with_search': len(with_search), 'search_calls': sum(x[1] for x in values),
              'heap_pops': sum(x[2] for x in values), 'tick_mean_ms': as_ms(statistics.mean(x[0] for x in values)),
              'search_outer_total_ms': as_ms(total_outer), 'search_outer_mean_per_tick_ms': as_ms(total_outer / len(values)),
              'search_outer_p95_ms': as_ms(percentile([x[3] for x in values], .95)),
              'search_outer_max_ms': as_ms(max(x[3] for x in values)),
              'search_outer_percent_of_event_interval': 100 * total_outer / total_tick if total_tick else None,
              'slowest_5pct': {'ticks': len(slow), 'ticks_with_search': sum(x[1] > 0 for x in slow),
                               'search_outer_total_ms': as_ms(sum(x[3] for x in slow)),
                               'search_outer_percent_of_event_interval': 100 * sum(x[3] for x in slow) / sum(x[0] for x in slow)},
              'note': 'Synchronous server-thread top-level PathFinder wall time, including instrumentation and scheduling; not full Tick time or causality.'}
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
