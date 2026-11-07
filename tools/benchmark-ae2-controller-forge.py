"""Test the opt-in AE2-controller experiment with verified local mod jars and disposable worlds."""
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
JARS = (
    ('[应用能源2] appliedenergistics2-forge-15.4.12.jar', 'vf-local-ae2', '15.4.12', 'b9c5928893a20d3feb0b4393e2c23d756a16d9abee24d9c14c98e6096d53844d'),
    ('[AE2超频] ae2_overclocked-1.2.3-fix3.jar', 'vf-local-ae2oc', '1.2.3-fix3', '8aacfceedaaaa9eac4a5169228c550d52075bd69ada33f7e082a05fe12641759'),
    ('guideme-20.1.15.jar', 'vf-local-guideme', '20.1.15', 'cf8052ab3da121012efc59a20e731c24bfe7488dfa556c9b88809f42dea3b24a'),
)
ROW = re.compile(r'AE2_CONTROLLER_BENCH scenario=(\w+) round=(\d+) control_ns=(\d+) candidate_ns=(\d+) control_bytes=(-?\d+) candidate_bytes=(-?\d+)')


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def world_hash(root):
    h = hashlib.sha256()
    for path in sorted(p for p in root.rglob('*') if p.is_file() and p.name != 'session.lock'):
        h.update(path.relative_to(root).as_posix().encode() + b'\0' + bytes.fromhex(digest(path)))
    return h.hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--mods', type=Path, required=True)
    p.add_argument('--template', type=Path, default=ROOT / 'run-server-baseline')
    p.add_argument('--forks', type=int, default=3)
    args = p.parse_args()
    if args.forks < 1 or not (args.template / 'world' / 'level.dat').is_file() or not (args.template / 'server.properties').is_file():
        p.error('provide a stopped development world and positive fork count')
    if not (args.template / 'eula.txt').is_file() or 'eula=true' not in (args.template / 'eula.txt').read_text().lower():
        p.error('template must contain an accepted EULA')
    for filename, _, _, expected in JARS:
        source = args.mods / filename
        if not source.is_file() or digest(source) != expected:
            p.error('missing or unexpected mod artifact: ' + filename)
    for filename, artifact, version, expected in JARS:
        dest = ROOT / 'build/dependency-cache/maven/maven/modrinth' / artifact / version
        dest.mkdir(parents=True, exist_ok=True)
        jar = dest / f'{artifact}-{version}.jar'
        if jar.exists() and digest(jar) != expected:
            raise RuntimeError('refusing to overwrite different cached artifact: ' + str(jar))
        if not jar.exists(): shutil.copy2(args.mods / filename, jar)
        pom = dest / f'{artifact}-{version}.pom'
        data = ('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
                f'<groupId>maven.modrinth</groupId><artifactId>{artifact}</artifactId><version>{version}</version></project>')
        if pom.exists() and pom.read_text(encoding='utf-8') != data:
            raise RuntimeError('refusing to overwrite different cached POM: ' + str(pom))
        if not pom.exists(): pom.write_text(data, encoding='utf-8')
    base_hash = world_hash(args.template / 'world')
    output = ROOT / 'build/reports/ae2-controller-reflection' / str(uuid.uuid4())
    output.mkdir(parents=True)
    results = []
    for fork in range(1, args.forks + 1):
        folder = output / f'fork-{fork:02d}'
        folder.mkdir()
        shutil.copytree(args.template / 'world', folder / 'world', ignore=shutil.ignore_patterns('session.lock'))
        if world_hash(folder / 'world') != base_hash: raise RuntimeError('world copy differs from template')
        for name in ('eula.txt', 'server.properties'): shutil.copy2(args.template / name, folder / name)
        (folder / '.vulkanforge-ae2-disposable').write_text('Disposable development verification.\n')
        cmd = [str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')), 'runServer', '-PserverBaseline',
               '-PvanillaRendererBench', '-PserverBaselineCanary', '-PserverBaselineAe2Fixture',
               '-PserverBaseline.ae2ControllerReflection', '-PserverBaseline.dir=' + str(folder),
               '-PserverBaseline.mekScenario=ae2-controller-verify', '-PserverBaseline.warmupTicks=20',
               '-PserverBaseline.sampleTicks=50', '-PserverBaseline.stopAfterSample=true', '--console', 'plain']
        print(f'Running isolated AE2 controller fork {fork}/{args.forks}', flush=True)
        with (folder / 'gradle.log').open('w', encoding='utf-8') as log:
            process = subprocess.run(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        log = (folder / 'gradle.log').read_text(encoding='utf-8', errors='replace')
        rows = ROW.findall(log)
        if process.returncode or 'AE2_CONTROLLER_REFLECTION_PASS' not in log or 'VF AE2 controller reflection experiment installed' not in log or 'All dimensions are saved' not in log or 'BUILD SUCCESSFUL' not in log or len(rows) != 18:
            raise RuntimeError('incomplete verification: ' + str(folder / 'gradle.log'))
        grouped = {}
        for scenario, *numbers in rows: grouped.setdefault(scenario, []).append(tuple(map(int, numbers)))
        if set(grouped) != {'recursive', 'public', 'energy_simulate'} or any(len(x) != 6 for x in grouped.values()):
            raise RuntimeError('unexpected benchmark scenarios: ' + str(folder / 'gradle.log'))
        results.append(grouped)
    scenarios = {}
    for scenario in ('recursive', 'public', 'energy_simulate'):
        groups = [fork[scenario] for fork in results]
        flattened = [row for group in groups for row in group]
        scenarios[scenario] = {
            'per_fork': [{'control_ns': statistics.median(r[1] for r in group), 'candidate_ns': statistics.median(r[2] for r in group),
                          'control_bytes': statistics.median(r[3] for r in group), 'candidate_bytes': statistics.median(r[4] for r in group)} for group in groups],
            'paired_change_median_percent': statistics.median((r[2] / r[1] - 1) * 100 for r in flattened)}
    summary = {'forks': args.forks, 'world_sha256': base_hash, 'scenarios': scenarios}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary, indent=2), '\nResults:', output, flush=True)


if __name__ == '__main__': main()
