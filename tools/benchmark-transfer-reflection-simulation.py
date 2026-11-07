"""Run Java reflection metadata lookup simulation in independent JVMs."""
import argparse
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
LINE = re.compile(r'REFLECTION_BENCH scenario=(\w+) round=(\d+) original_ns=(\d+) cached_ns=(\d+) original_bytes=(-?\d+) cached_bytes=(-?\d+)')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--forks', type=int, default=3)
    args = p.parse_args()
    if args.forks < 1:
        p.error('forks must be positive')
    output = ROOT / 'build/reports/transfer-reflection-simulation' / str(uuid.uuid4())
    output.mkdir(parents=True)
    samples = {}
    for fork in range(1, args.forks + 1):
        print(f'Running reflection simulation fork {fork}/{args.forks}', flush=True)
        path = output / f'fork-{fork:02}.log'
        command = [str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')), 'benchmarkTransferReflectionSimulation', '-PvanillaRendererBench', '--console', 'plain']
        with path.open('w', encoding='utf-8') as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        log = path.read_text(encoding='utf-8', errors='replace')
        rows = LINE.findall(log)
        if result.returncode or 'BUILD SUCCESSFUL' not in log or 'REFLECTION_PARITY_PASS states=1000 fixtures=5' not in log or len(rows) != 24:
            raise RuntimeError('incomplete simulation: ' + str(path))
        for scenario, _, a, b, aa, ba in rows:
            samples.setdefault(scenario, []).append(tuple(map(int, (a, b, aa, ba))))
    if set(samples) != {'missing', 'public_host', 'private_field', 'self_reference'}:
        raise RuntimeError('missing scenario')
    summary = {}
    for scenario, rows in samples.items():
        summary[scenario] = {'samples': len(rows), **{key: statistics.median(row[i] for row in rows) for i, key in enumerate(('original_ns', 'cached_ns', 'original_bytes', 'cached_bytes'))}}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary, indent=2), '\nResults:', output, flush=True)


if __name__ == '__main__':
    main()
