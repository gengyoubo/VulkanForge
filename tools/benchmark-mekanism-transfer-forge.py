"""Verify and time the development-only transfer cache in disposable Forge servers."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
PATTERN = re.compile(r'MEK_TRANSFER_BENCH endpoints=(\d+) round=(\d+) control_ns=(\d+) candidate_ns=(\d+)')


def world_hash(root):
    h = hashlib.sha256()
    for path in sorted(p for p in root.rglob('*') if p.is_file() and p.name != 'session.lock'):
        h.update(path.relative_to(root).as_posix().encode() + b'\0')
        h.update(hashlib.sha256(path.read_bytes()).digest())
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template', type=Path, default=ROOT / 'run-server-baseline')
    parser.add_argument('--forks', type=int, default=3)
    args = parser.parse_args()
    template = args.template.resolve()
    if args.forks < 1 or not (template / 'world' / 'level.dat').is_file() or not (template / 'server.properties').is_file():
        parser.error('provide a stopped development world and a positive fork count')
    if not (template / 'eula.txt').is_file() or 'eula=true' not in (template / 'eula.txt').read_text().lower():
        parser.error('template must contain an accepted EULA')
    digest = world_hash(template / 'world')
    output = ROOT / 'build/reports/mekanism-transfer-forge' / str(uuid.uuid4())
    output.mkdir(parents=True)
    results = {}
    for fork in range(1, args.forks + 1):
        folder = output / f'fork-{fork:02d}'
        folder.mkdir()
        shutil.copytree(template / 'world', folder / 'world', ignore=shutil.ignore_patterns('session.lock'))
        if world_hash(folder / 'world') != digest:
            raise RuntimeError('world copy differs from template')
        for name in ('eula.txt', 'server.properties'):
            shutil.copy2(template / name, folder / name)
        (folder / '.vulkanforge-mekanism-disposable').write_text('Disposable development verification.\n')
        cmd = [str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')), 'runServer',
               '-PserverBaseline', '-PvanillaRendererBench', '-PserverBaselineCanary', '-PserverBaselineMekanism',
               '-PserverBaseline.transferCache', '-PserverBaseline.dir=' + str(folder),
               '-PserverBaseline.mekScenario=transfer-cache-verify', '-PserverBaseline.warmupTicks=20',
               '-PserverBaseline.sampleTicks=50', '-PserverBaseline.stopAfterSample=true', '--console', 'plain']
        print(f'Running isolated Forge fork {fork}/{args.forks}', flush=True)
        with (folder / 'gradle.log').open('w', encoding='utf-8') as log:
            run = subprocess.run(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        log = (folder / 'gradle.log').read_text(encoding='utf-8', errors='replace')
        required = ['MEK_TRANSFER_CACHE_PASS', 'VF transfer cache mutation hooks installed',
                    'VF transfer cache emit hook installed: mekanism.common.content.network.FluidNetwork',
                    'VF transfer cache emit hook installed: mekanism.common.content.network.EnergyNetwork',
                    'VF transfer cache emit hook installed: mekanism.common.content.network.BoxedChemicalNetwork',
                    'Loaded configuration file for Canary:', 'All dimensions are saved', 'BUILD SUCCESSFUL']
        if run.returncode or any(item not in log for item in required):
            raise RuntimeError('incomplete verification: ' + str(folder / 'gradle.log'))
        records = PATTERN.findall(log)
        if len(records) != 12 or {int(e) for e, _, _, _ in records} != {40, 256}:
            raise RuntimeError('incomplete benchmark: ' + str(folder / 'gradle.log'))
        for endpoints, _, control, candidate in records:
            results.setdefault(endpoints, []).append((int(control), int(candidate)))
    summary = {'world_sha256': digest, 'forks': args.forks, 'scenarios': {}}
    for endpoints, pairs in results.items():
        a = statistics.median(x for x, _ in pairs)
        b = statistics.median(y for _, y in pairs)
        summary['scenarios'][endpoints] = {'pairs': len(pairs), 'control_median_ns': a, 'candidate_median_ns': b,
                                           'candidate_change_percent': (b / a - 1) * 100,
                                           'paired_change_median_percent': statistics.median((y / x - 1) * 100 for x, y in pairs)}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary, indent=2), '\nResults:', output, flush=True)


if __name__ == '__main__':
    main()
