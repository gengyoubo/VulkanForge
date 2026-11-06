"""Run an exploratory Forge/VulkanForge vs Radium comparison from identical world copies."""
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
    for file in sorted(p for p in root.rglob('*') if p.is_file() and p.name != 'session.lock'):
        digest.update(file.relative_to(root).as_posix().encode('utf-8') + b'\0')
        digest.update(bytes.fromhex(hashlib.sha256(file.read_bytes()).hexdigest()))
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template', type=Path, default=ROOT / 'run-server-baseline')
    parser.add_argument('--warmup', type=int, default=200)
    parser.add_argument('--samples', type=int, default=600)
    parser.add_argument('--rounds', type=int, default=1)
    args = parser.parse_args()
    if args.warmup < 0 or args.samples < 1 or args.rounds < 1:
        parser.error('invalid tick counts')
    template = args.template.resolve()
    if not (template / 'world' / 'level.dat').is_file():
        parser.error('template must contain a stopped server world')
    if not (template / 'eula.txt').is_file() or not any(line.strip().lower() == 'eula=true' for line in (template / 'eula.txt').read_text().splitlines()):
        parser.error('template must contain a user-accepted eula.txt')
    output = ROOT / 'build' / 'reports' / 'server-comparison' / str(uuid.uuid4())
    output.mkdir(parents=True, exist_ok=False)
    world_hash = tree_hash(template / 'world')
    manifest = {'template_world_sha256': world_hash, 'warmup_ticks': args.warmup,
                'sample_ticks': args.samples, 'rounds': args.rounds, 'radium_curseforge_file': 9434151,
                'java_home': os.environ.get('JAVA_HOME'), 'runs': []}
    cases = [(round_no, name) for round_no in range(1, args.rounds + 1)
             for name in (('baseline', 'radium') if round_no % 2 else ('radium', 'baseline'))]
    for round_no, name in cases:
        folder = output / f'round-{round_no:02d}' / name
        folder.mkdir(parents=True)
        shutil.copytree(template / 'world', folder / 'world', ignore=shutil.ignore_patterns('session.lock'))
        if tree_hash(folder / 'world') != world_hash:
            raise RuntimeError('copied world does not match template')
        for file in ('eula.txt', 'server.properties'):
            shutil.copy2(template / file, folder / file)
        command = [str(ROOT / 'gradlew.bat' if os.name == 'nt' else ROOT / 'gradlew'),
                   'runServer', '-PserverBaseline', '-PvanillaRendererBench',
                   '-PserverBaseline.dir=' + str(folder),
                   '-PserverBaseline.warmupTicks=' + str(args.warmup),
                   '-PserverBaseline.sampleTicks=' + str(args.samples),
                   '-PserverBaseline.stopAfterSample=true', '-PserverBaseline.entityWorkload=true',
                   '-PserverBaseline.jfr', '--console', 'plain']
        if name == 'radium':
            command.append('-PserverBaselineRadium')
        print(f'Running {name}: {folder}', flush=True)
        with (folder / 'gradle.log').open('w', encoding='utf-8') as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        logs = (folder / 'gradle.log').read_text(encoding='utf-8', errors='replace')
        csv_files = list((folder / 'logs' / 'vulkanforge' / 'server-baseline').glob('ticks-*.csv'))
        if result.returncode or 'Server baseline complete:' not in logs or 'All dimensions are saved' not in logs or len(csv_files) != 1:
            raise RuntimeError(f'{name} did not complete cleanly; inspect {folder / "gradle.log"}')
        radium_loaded = 'Loaded configuration file for Radium:' in logs
        if radium_loaded != (name == 'radium'):
            raise RuntimeError(f'{name} has unexpected Radium load status; inspect {folder / "gradle.log"}')
        tick_match = re.search(r'Synthetic workload cow ticks: (\d+)', logs)
        expected_entity_ticks = 160 * (args.warmup + args.samples)
        if not tick_match or int(tick_match.group(1)) != expected_entity_ticks:
            raise RuntimeError(f'{name} did not tick all workload entities as expected; inspect {folder / "gradle.log"}')
        summary = subprocess.check_output([sys.executable, str(ROOT / 'tools' / 'analyze-server-baseline.py'), str(csv_files[0]), '--expected-samples', str(args.samples)], text=True)
        record = {'round': round_no, 'case': name, 'entity_ticks': int(tick_match.group(1)),
                  'summary': json.loads(summary), 'csv': str(csv_files[0])}
        manifest['runs'].append(record)
        (output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
        print(json.dumps(record, indent=2), flush=True)
    print(f'Results: {output}', flush=True)


if __name__ == '__main__':
    main()
