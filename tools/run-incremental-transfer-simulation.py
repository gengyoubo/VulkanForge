"""Compare topology traversal strategies with Mekanism distribution and fastutil."""
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
FASTUTIL_HASH = '9578bf2a1700cf20d21746a2ee89e57ba1abbd37fa9feda68ff5e9a28473a7f9'
LINE = re.compile(r'BENCH endpoints=(\d+) period=(\d+) structural=(true|false) round=(\d+) mode=(\w+) ns=([\d.]+)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-zip', type=Path, default=ROOT / 'build/reference/mekanism-50978fc-source.zip')
    parser.add_argument('--fastutil', type=Path, required=True)
    parser.add_argument('--forks', type=int, default=3)
    args = parser.parse_args()
    for path, expected in ((args.source_zip, SOURCE_HASH), (args.fastutil, FASTUTIL_HASH)):
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            parser.error(f'missing or unrecognized input: {path}')
    if args.forks < 1:
        parser.error('fork count must be positive')
    out = ROOT / 'build/reports/mekanism-transfer-incremental'
    src, classes = out / 'source', out / 'classes'
    src.mkdir(parents=True, exist_ok=True)
    classes.mkdir(parents=True, exist_ok=True)
    names = ['Target.java', 'SplitInfo.java', 'IntegerSplitInfo.java']
    with zipfile.ZipFile(args.source_zip) as archive:
        for name in names:
            (src / name).write_bytes(archive.read('Mekanism-50978fc/src/main/java/mekanism/common/lib/distribution/' + name))
    java_home = os.environ.get('JAVA_HOME')
    def exe(name):
        return str(Path(java_home) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))) if java_home else name
    subprocess.run([exe('javac'), '-cp', str(args.fastutil), '-d', str(classes), *(str(src / n) for n in names),
                    str(ROOT / 'tools/experiments/TransferTopologySimulation.java'),
                    str(ROOT / 'tools/experiments/IncrementalTransferSimulation.java')], check=True)
    samples = {}
    for fork in range(1, args.forks + 1):
        cp = os.pathsep.join((str(classes), str(args.fastutil.resolve())))
        run = subprocess.run([exe('java'), '-Xms512m', '-Xmx512m', '-cp', cp, 'IncrementalTransferSimulation'],
                             text=True, capture_output=True, check=True)
        if 'PARITY_PASS steps=20000 modes=4' not in run.stdout:
            raise RuntimeError('missing parity result')
        (out / f'fork-{fork}.txt').write_text(run.stdout, encoding='utf-8')
        for line in run.stdout.splitlines():
            match = LINE.fullmatch(line)
            if match:
                endpoints, period, structural, _, mode, ns = match.groups()
                samples.setdefault(f'{endpoints}:{period}:{structural}', {}).setdefault(mode, []).append(float(ns))
        print(f'fork {fork}/{args.forks} complete', flush=True)
    expected_scenarios = {'16:0:false', '16:1:false', '64:0:false', '512:0:false',
                          '512:32:false', '512:1:false', '512:32:true', '512:1:true'}
    if set(samples) != expected_scenarios:
        raise RuntimeError('missing or unexpected benchmark scenarios')
    summary = {}
    for key, groups in samples.items():
        if set(groups) != {'NESTED', 'REBUILD', 'INCREMENTAL', 'ADAPTIVE'} or any(len(x) != 6 * args.forks for x in groups.values()):
            raise RuntimeError('incomplete timing samples: ' + key)
        medians = {mode: statistics.median(values) for mode, values in groups.items()}
        summary[key] = {'median_ns': medians, 'vs_nested_percent': {mode: (value / medians['NESTED'] - 1) * 100 for mode, value in medians.items()}}
    (out / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
