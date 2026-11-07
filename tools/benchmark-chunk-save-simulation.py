"""Run independent JVM forks of the synthetic Minecraft-NBT save benchmark."""
import argparse
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
VALUES = re.compile(r'(\w+)=([^\s]+)')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--forks', type=int, default=3)
    args = p.parse_args()
    if args.forks < 1:
        p.error('forks must be positive')
    output = ROOT / 'build/reports/chunk-save-simulation' / str(uuid.uuid4())
    output.mkdir(parents=True)
    runs = []
    for fork in range(1, args.forks + 1):
        print(f'Running Java NBT simulation fork {fork}/{args.forks}', flush=True)
        path = output / f'fork-{fork:02}.log'
        cmd = [str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')), 'benchmarkChunkSaveSimulation',
               '-PvanillaRendererBench', '--console', 'plain']
        with path.open('w', encoding='utf-8') as log:
            result = subprocess.run(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        log = path.read_text(encoding='utf-8', errors='replace')
        records = {'result': {}, 'io': {}, 'gc_mixed': {}, 'roundtrip': {}}
        for line in log.splitlines():
            prefix = line.split(' ', 1)[0]
            if prefix not in ('RESULT', 'IO', 'GC_MIXED', 'ROUNDTRIP_PASS'):
                continue
            row = dict(VALUES.findall(line))
            bucket = {'RESULT':'result', 'IO':'io', 'GC_MIXED':'gc_mixed', 'ROUNDTRIP_PASS':'roundtrip'}[prefix]
            key = row['blocks'] + (':' + row['mode'] if prefix == 'RESULT' else '')
            records[bucket][key] = {k: int(v) if v.isdigit() else v for k, v in row.items()}
        if result.returncode or 'BUILD SUCCESSFUL' not in log or set(records['result']) != {f'{n}:{m}' for n in (0, 64, 512) for m in ('staged', 'stream')} or any(set(records[k]) != {'0','64','512'} for k in ('io', 'gc_mixed', 'roundtrip')):
            raise RuntimeError('incomplete simulation: ' + str(path))
        runs.append(records)
    summary = {'forks': args.forks, 'runs': runs, 'median_of_fork_medians': {}}
    for key in runs[0]['result']:
        summary['median_of_fork_medians'][key] = {
            metric: statistics.median(run['result'][key][metric] for run in runs)
            for metric in ('build_p50_ns', 'encode_p50_ns', 'compress_p50_ns', 'total_p50_ns', 'total_p95_ns', 'allocated_p50_bytes')}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary['median_of_fork_medians'], indent=2), '\nResults:', output, flush=True)


if __name__ == '__main__':
    main()
