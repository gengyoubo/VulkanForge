"""Compile and run the transfer topology model against Mekanism's distribution classes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE_HASH = '3ce8808e150d224ae7ecb8d99fad80615d1e01b36a5362c4fffcd43385154c06'
LINE = re.compile(r'BENCH endpoints=(\d+) invalidateEvery=(\d+) round=(\d+) nested_ns=([\d.]+) flat_ns=([\d.]+)')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source-zip', type=Path, default=ROOT / 'build/reference/mekanism-50978fc-source.zip')
    p.add_argument('--forks', type=int, default=3)
    args = p.parse_args()
    if args.forks < 1 or not args.source_zip.is_file():
        p.error('provide an existing 50978fc source archive and a positive fork count')
    if hashlib.sha256(args.source_zip.read_bytes()).hexdigest() != SOURCE_HASH:
        p.error('source archive differs from the verified Mekanism 50978fc archive')
    out = ROOT / 'build/reports/mekanism-transfer-simulation'
    src, classes = out / 'source', out / 'classes'
    src.mkdir(parents=True, exist_ok=True)
    classes.mkdir(parents=True, exist_ok=True)
    names = ['Target.java', 'SplitInfo.java', 'IntegerSplitInfo.java']
    with zipfile.ZipFile(args.source_zip) as archive:
        for name in names:
            entry = 'Mekanism-50978fc/src/main/java/mekanism/common/lib/distribution/' + name
            (src / name).write_bytes(archive.read(entry))
    java_home = os.environ.get('JAVA_HOME')
    exe = lambda name: str(Path(java_home) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))) if java_home else name
    subprocess.run([exe('javac'), '-d', str(classes), *(str(src / n) for n in names),
                    str(ROOT / 'tools/experiments/TransferTopologySimulation.java')], check=True)
    results = {}
    for fork in range(1, args.forks + 1):
        run = subprocess.run([exe('java'), '-Xms512m', '-Xmx512m', '-cp', str(classes), 'TransferTopologySimulation'],
                             text=True, capture_output=True, check=True)
        if 'PARITY_PASS ticks=10000' not in run.stdout:
            raise RuntimeError('missing parity result')
        (out / f'fork-{fork}.txt').write_text(run.stdout, encoding='utf-8')
        for line in run.stdout.splitlines():
            match = LINE.fullmatch(line)
            if match:
                endpoints, invalidation, _, nested, flat = match.groups()
                key = f'{endpoints}:{invalidation}'
                results.setdefault(key, []).append((float(nested), float(flat)))
        print(f'fork {fork}/{args.forks} complete', flush=True)
    summary = {}
    for key, pairs in results.items():
        nested = statistics.median(x for x, _ in pairs)
        flat = statistics.median(y for _, y in pairs)
        summary[key] = {'rounds': len(pairs), 'nested_median_ns': nested, 'flat_median_ns': flat,
                        'flat_relative_percent': (flat / nested - 1) * 100}
    (out / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
