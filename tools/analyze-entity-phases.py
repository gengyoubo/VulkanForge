"""Summarize inclusive, overlapping entity method timings from development Tick CSV."""
import argparse
import csv
import json
import math
from pathlib import Path


NAMES = ('server_tick_non_passenger', 'goal_selector_full', 'goal_tick_running', 'living_push_entities', 'entity_collide')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv', type=Path)
    parser.add_argument('--expected-samples', type=int, required=True)
    args = parser.parse_args()
    with args.csv.open(encoding='utf-8', newline='') as source:
        rows = list(csv.DictReader(source))
    if args.expected_samples < 1 or len(rows) != args.expected_samples:
        raise SystemExit('Unexpected sample count')
    ticks, phases = [], [[] for _ in NAMES]
    for number, row in enumerate(rows, 1):
        if int(row['sample']) != number or row['phase_instrumented'].lower() != 'true':
            raise SystemExit('Missing instrumentation or misordered sample ' + str(number))
        tick = int(row['tick_duration_ns'])
        if tick <= 0:
            raise SystemExit('Invalid Tick duration')
        ticks.append(tick)
        for i in range(len(NAMES)):
            calls, done, elapsed = (int(row[f'phase{i}_{suffix}']) for suffix in ('calls', 'completed', 'ns'))
            if min(calls, done, elapsed) < 0 or calls != done:
                raise SystemExit(f'Phase {i} incomplete at sample {number}')
            phases[i].append((calls, elapsed))
    if not sum(x[0] for x in phases[0]):
        raise SystemExit('No entity tick calls recorded')
    slow = sorted(range(len(ticks)), key=lambda i: ticks[i], reverse=True)[:max(1, math.ceil(len(ticks) * .05))]
    result = {'samples': len(ticks), 'tick_mean_ms': sum(ticks) / len(ticks) / 1e6,
              'note': 'Inclusive wall timings overlap and may nest; do not add phase values. Instrumentation, scheduling and GC are included; event interval is not the full Tick.',
              'phases': {}}
    for name, values in zip(NAMES, phases):
        total = sum(x[1] for x in values)
        result['phases'][name] = {'calls': sum(x[0] for x in values), 'ticks_with_calls': sum(x[0] > 0 for x in values),
                                  'total_ms': total / 1e6, 'mean_ms_per_tick': total / len(ticks) / 1e6,
                                  'percent_of_event_interval': 100 * total / sum(ticks),
                                  'slowest_5pct_total_ms': sum(values[i][1] for i in slow) / 1e6,
                                  'slowest_5pct_percent_of_event_interval': 100 * sum(values[i][1] for i in slow) / sum(ticks[i] for i in slow)}
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
