"""Compare a development-only Mekanism frequency guard on disposable structural-block worlds."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]


def tree_hash(root):
    digest = hashlib.sha256()
    for p in sorted(f for f in root.rglob('*') if f.is_file() and f.name != 'session.lock'):
        digest.update(p.relative_to(root).as_posix().encode() + b'\0')
        digest.update(hashlib.sha256(p.read_bytes()).digest())
    return digest.hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--template', type=Path, default=ROOT / 'run-server-baseline')
    ap.add_argument('--mekanism-jar', type=Path, required=True)
    ap.add_argument('--canary', type=Path, required=True)
    ap.add_argument('--warmup', type=int, default=200)
    ap.add_argument('--samples', type=int, default=600)
    ap.add_argument('--rounds', type=int, default=2)
    args = ap.parse_args()
    template, mekanism, canary = args.template.resolve(), args.mekanism_jar.resolve(), args.canary.resolve()
    if args.warmup < 0 or args.samples < 1 or args.rounds < 1 or not mekanism.is_file() or not canary.is_file():
        ap.error('invalid paths or tick counts')
    if hashlib.sha1(canary.read_bytes()).hexdigest() != '898abd9e337ef1c677ce93f5589497a09c7e9850':
        ap.error('Canary differs from pinned 0.3.3')
    if hashlib.sha256(mekanism.read_bytes()).hexdigest() != '3b0d191fa4a45e662f9725991d76a30192f30186abb2ba694b873b5f7b9358d2':
        ap.error('Mekanism differs from the pinned 10.4.16.80 artifact')
    if not (template / 'world' / 'level.dat').is_file() or not (template / 'server.properties').is_file():
        ap.error('template must be a stopped development world')
    eula = template / 'eula.txt'
    if not eula.is_file() or not any(x.strip().lower() == 'eula=true' for x in eula.read_text().splitlines()):
        ap.error('template must contain the accepted EULA')
    output = ROOT / 'build' / 'reports' / 'mekanism-frequency' / str(uuid.uuid4())
    output.mkdir(parents=True, exist_ok=False)
    world_hash = tree_hash(template / 'world')
    manifest = {'world_sha256': world_hash, 'mekanism_sha256': hashlib.sha256(mekanism.read_bytes()).hexdigest(),
                'canary_sha256': hashlib.sha256(canary.read_bytes()).hexdigest(),
                'warmup': args.warmup, 'samples': args.samples, 'rounds': args.rounds, 'cases': []}
    for round_no in range(1, args.rounds + 1):
        for fast in ((False, True) if round_no % 2 else (True, False)):
            folder = output / f'round-{round_no:02d}' / ('guard' if fast else 'control')
            folder.mkdir(parents=True)
            shutil.copytree(template / 'world', folder / 'world', ignore=shutil.ignore_patterns('session.lock'))
            if tree_hash(folder / 'world') != world_hash:
                raise RuntimeError('world copy differs from template')
            shutil.copy2(eula, folder / 'eula.txt')
            shutil.copy2(template / 'server.properties', folder / 'server.properties')
            (folder / '.vulkanforge-mekanism-disposable').write_text('Disposable synthetic structures.\n', encoding='utf-8')
            cmd = [str(ROOT / 'gradlew.bat' if os.name == 'nt' else ROOT / 'gradlew'), 'runServer',
                   '-PserverBaseline', '-PvanillaRendererBench', '-PserverBaselineCanary', '-PserverBaselineMekanism',
                   '-PserverBaseline.dir=' + str(folder), '-PserverBaseline.mekScenario=structures',
                   '-PserverBaseline.warmupTicks=' + str(args.warmup), '-PserverBaseline.sampleTicks=' + str(args.samples),
                   '-PserverBaseline.stopAfterSample=true', '-PserverBaseline.jfr', '-PserverBaseline.debugSafepoints', '--console', 'plain']
            if fast: cmd.append('-PserverBaseline.mekanismFast')
            print('Running round', round_no, 'guard=', fast, folder, flush=True)
            with (folder / 'gradle.log').open('w', encoding='utf-8') as log:
                result = subprocess.run(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=600)
            log = (folder / 'gradle.log').read_text(encoding='utf-8', errors='replace')
            csvs = list((folder / 'logs' / 'vulkanforge' / 'server-baseline').glob('ticks-*.csv'))
            start = re.search(r'MEK_WORKLOAD count=(\d+) emptyFrequency=(\d+) trackedFrequency=(\d+)', log)
            finish = re.search(r'MEK_WORKLOAD_RESULT expected=(\d+) present=(\d+)', log)
            if result.returncode or 'Server baseline complete:' not in log or 'All dimensions are saved' not in log or len(csvs) != 1 or not start or not finish:
                raise RuntimeError('Incomplete run: ' + str(folder / 'gradle.log'))
            if 'Loaded configuration file for Canary:' not in log or 'MEK_FREQUENCY_FLAGS_PASS' not in log:
                raise RuntimeError('Missing Canary or flag checks: ' + str(folder / 'gradle.log'))
            if ('VulkanForge experimental Mekanism empty frequency guard installed' in log) != fast:
                raise RuntimeError('Unexpected guard state: ' + str(folder / 'gradle.log'))
            if int(start.group(1)) != 1024 or int(start.group(2)) == 0 or finish.group(1) != finish.group(2):
                raise RuntimeError('Unexpected workload count: ' + str(folder / 'gradle.log'))
            summary = json.loads(subprocess.check_output([sys.executable, str(ROOT / 'tools' / 'analyze-server-baseline.py'),
                                                          str(csvs[0]), '--expected-samples', str(args.samples)], text=True))
            record = {'round': round_no, 'guard': fast, 'placed': int(start.group(1)),
                      'empty_frequency': int(start.group(2)), 'tracked_frequency': int(start.group(3)), 'tick_summary': summary}
            manifest['cases'].append(record)
            (output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
            print(json.dumps(record, indent=2), flush=True)
    print('Results:', output, flush=True)


if __name__ == '__main__':
    main()
