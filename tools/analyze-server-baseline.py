"""Summarize a development server tick CSV without external packages."""
import argparse
import csv
import json
import math
import statistics
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv', type=Path)
    parser.add_argument('--expected-samples', type=int, required=True)
    args = parser.parse_args()
    if args.expected_samples < 1:
        parser.error('--expected-samples must be positive')
    with args.csv.open(newline='', encoding='utf-8') as source:
        rows = list(csv.DictReader(source))
    if len(rows) != args.expected_samples:
        raise SystemExit(f'Incomplete run: got {len(rows)} samples, expected {args.expected_samples}')
    values = []
    for index, row in enumerate(rows, 1):
        if int(row['sample']) != index or int(row['tick_duration_ns']) < 0:
            raise SystemExit(f'Invalid sample {index}')
        values.append(int(row['tick_duration_ns']) / 1_000_000)
    ordered = sorted(values)
    percentile = lambda p: ordered[math.ceil(p * len(ordered)) - 1]
    print(json.dumps({'samples': len(values), 'mean_ms': statistics.mean(values),
                      'median_ms': statistics.median(values), 'p95_ms': percentile(.95),
                      'p99_ms': percentile(.99), 'max_ms': max(values),
                      'over_50ms': sum(v > 50 for v in values)}, indent=2))


if __name__ == '__main__':
    main()
