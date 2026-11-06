"""Run opt-in AI scenarios against disposable copies of a stopped development world."""
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
        digest.update(hashlib.sha256(file.read_bytes()).digest())
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template', type=Path, default=ROOT / 'run-server-baseline')
    parser.add_argument('--canary', type=Path, required=True, help='existing Canary Forge 1.20.1 JAR')
    parser.add_argument('--warmup', type=int, default=200)
    parser.add_argument('--samples', type=int, default=600)
    parser.add_argument('--scenario', choices=('all', 'wander', 'obstacles', 'villager_poi', 'villager_dense', 'changed_wolf', 'changed_dense'), default='all')
    parser.add_argument('--changed-jar', type=Path, help='installed Changed 0.15.7 JAR, required for changed_wolf')
    parser.add_argument('--count-nodes', action='store_true', help='instrument PathFinder searches and heap pops; timing is perturbed')
    parser.add_argument('--phase-profile', action='store_true', help='instrument selected entity, goal, push and collision methods')
    parser.add_argument('--goal-detail', action='store_true', help='attribute Goal callbacks by class; diagnostic overhead can be substantial')
    parser.add_argument('--compare-instrumentation', action='store_true', help='run alternating instrumented and uninstrumented copies')
    parser.add_argument('--rounds', type=int, default=1)
    args = parser.parse_args()
    template, canary = args.template.resolve(), args.canary.resolve()
    if args.warmup < 0 or args.samples < 1 or args.rounds < 1 or not canary.is_file():
        parser.error('invalid tick counts or Canary JAR')
    if sum((args.count_nodes, args.phase_profile, args.goal_detail)) > 1:
        parser.error('choose one kind of instrumentation')
    if args.count_nodes and args.compare_instrumentation:
        parser.error('choose --count-nodes or --compare-instrumentation')
    if hashlib.sha1(canary.read_bytes()).hexdigest() != '898abd9e337ef1c677ce93f5589497a09c7e9850':
        parser.error('installed Canary differs from the pinned 0.3.3 artifact')
    if not (template / 'world' / 'level.dat').is_file() or not (template / 'server.properties').is_file():
        parser.error('template must contain a stopped server world and server.properties')
    eula = template / 'eula.txt'
    if not eula.is_file() or not any(s.strip().lower() == 'eula=true' for s in eula.read_text().splitlines()):
        parser.error('template must contain a user-accepted eula.txt')
    output = ROOT / 'build' / 'reports' / 'server-ai' / str(uuid.uuid4())
    output.mkdir(parents=True, exist_ok=False)
    world_hash = tree_hash(template / 'world')
    manifest = {'world_sha256': world_hash, 'canary_sha256': hashlib.sha256(canary.read_bytes()).hexdigest(),
                'warmup': args.warmup, 'samples': args.samples, 'rounds': args.rounds,
                'compare_instrumentation': args.compare_instrumentation, 'instrument_kind': 'goal_detail' if args.goal_detail else 'phases' if args.phase_profile else 'pathfinder', 'cases': []}
    scenarios = ('wander', 'obstacles', 'villager_poi') if args.scenario == 'all' else (args.scenario,)
    if args.scenario.startswith('changed_') and (not args.changed_jar or not args.changed_jar.is_file()):
        parser.error('--changed-jar is required for the Changed entity scenario')
    if args.scenario.startswith('changed_') and hashlib.sha256(args.changed_jar.read_bytes()).hexdigest() != '18577d384c774852973e8eb08388a2f332f3ecb11d8e298267ef9f9b8a23af5a':
        parser.error('installed Changed JAR differs from the pinned 0.15.7 artifact')
    runs = [(round_no, scenario, instrumented) for round_no in range(1, args.rounds + 1) for scenario in scenarios
            for instrumented in ((False, True) if round_no % 2 else (True, False))] if args.compare_instrumentation else [
                (round_no, scenario, args.count_nodes or args.phase_profile or args.goal_detail) for round_no in range(1, args.rounds + 1) for scenario in scenarios]
    for round_no, scenario, instrumented in runs:
        folder = output / f'round-{round_no:02d}' / scenario / ('instrumented' if instrumented else 'control')
        folder.mkdir(parents=True)
        shutil.copytree(template / 'world', folder / 'world', ignore=shutil.ignore_patterns('session.lock'))
        if tree_hash(folder / 'world') != world_hash:
            raise RuntimeError('world copy differs from template')
        shutil.copy2(eula, folder / 'eula.txt')
        shutil.copy2(template / 'server.properties', folder / 'server.properties')
        (folder / '.vulkanforge-ai-disposable').write_text('Synthetic arena; disposable copy only.\n', encoding='utf-8')
        cmd = [str(ROOT / 'gradlew.bat' if os.name == 'nt' else ROOT / 'gradlew'),
               'runServer', '-PserverBaseline', '-PvanillaRendererBench', '-PserverBaselineCanary',
               '-PserverBaseline.dir=' + str(folder), '-PserverBaseline.aiScenario=' + scenario,
               '-PserverBaseline.warmupTicks=' + str(args.warmup), '-PserverBaseline.sampleTicks=' + str(args.samples),
               '-PserverBaseline.stopAfterSample=true', '-PserverBaseline.jfr', '--console', 'plain']
        if instrumented:
            cmd.append('-PserverBaseline.goalDetails' if args.goal_detail else '-PserverBaseline.countPhases' if args.phase_profile else '-PserverBaseline.countNodes')
        if scenario.startswith('changed_'):
            cmd.append('-PserverBaselineChanged')
            manifest['changed_sha256'] = hashlib.sha256(args.changed_jar.read_bytes()).hexdigest()
        print('Running', scenario, 'instrumented=' + str(instrumented), folder, flush=True)
        with (folder / 'gradle.log').open('w', encoding='utf-8') as log:
            result = subprocess.run(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        text = (folder / 'gradle.log').read_text(encoding='utf-8', errors='replace')
        match = re.search(r'AI_WORKLOAD_RESULT scenario=(\w+) ticks=(\d+) requests=(\d+) nonNull=(\d+) reachable=(\d+) pathNodes=(\d+) installed=(\d+)', text)
        csvs = list((folder / 'logs' / 'vulkanforge' / 'server-baseline').glob('ticks-*.csv'))
        if result.returncode or 'Server baseline complete:' not in text or 'All dimensions are saved' not in text or len(csvs) != 1 or not match:
            raise RuntimeError(f'Incomplete run; inspect {folder / "gradle.log"}')
        if 'Loaded configuration file for Canary:' not in text:
            raise RuntimeError('Canary did not load; inspect ' + str(folder / 'gradle.log'))
        if ('VulkanForge development AI search counters installed' in text) != (instrumented and not args.phase_profile and not args.goal_detail):
            raise RuntimeError('Unexpected search instrumentation state: ' + str(folder / 'gradle.log'))
        if ('VulkanForge development entity phase hooks installed' in text) != (instrumented and args.phase_profile):
            raise RuntimeError('Unexpected entity instrumentation state: ' + str(folder / 'gradle.log'))
        if ('VulkanForge development goal detail hooks installed' in text) != (instrumented and args.goal_detail):
            raise RuntimeError('Unexpected goal instrumentation state: ' + str(folder / 'gradle.log'))
        vals = [int(v) for v in match.groups()[1:]]
        mob_count = 16 if scenario == 'villager_dense' else 8 if scenario in ('villager_poi', 'changed_wolf') else 32
        if match.group(1) != scenario or vals[0] != mob_count * (args.warmup + args.samples):
            raise RuntimeError('Unexpected entity Tick count: ' + match.group(0))
        if vals[5] == 0 or (scenario == 'obstacles' or scenario.startswith('changed_')) and (vals[1] == 0 or vals[3] == 0):
            raise RuntimeError('No verified navigation activity: ' + match.group(0))
        summary = json.loads(subprocess.check_output([sys.executable, str(ROOT / 'tools' / 'analyze-server-baseline.py'),
                                                      str(csvs[0]), '--expected-samples', str(args.samples)], text=True))
        record = dict(round=round_no, scenario=scenario, instrumented=instrumented, entity_ticks=vals[0], requests=vals[1], non_null=vals[2], reachable=vals[3],
                      path_nodes=vals[4], installed_paths=vals[5], tick_summary=summary)
        if scenario.startswith('villager_'):
            poi = re.search(r'AI_POI_RESULT homeOwners=(\d+) jobOwners=(\d+) homeTicks=(\d+) jobTicks=(\d+)', text)
            if not poi or int(poi.group(1)) == 0:
                raise RuntimeError('No confirmed villager home POI activity: ' + str(folder / 'gradle.log'))
            record['poi'] = dict(zip(('home_owners', 'job_owners', 'home_ticks', 'job_ticks'), map(int, poi.groups())))
        if instrumented and not args.phase_profile and not args.goal_detail:
            node_match = re.search(r'AI_SEARCH_RESULT searches=(\d+) completed=(\d+) heapPops=(\d+) elapsedNs=(\d+) maxSearchNs=(\d+) maxPops=(\d+) openDepth=(\d+)', text)
            if not node_match or int(node_match.group(1)) == 0 or int(node_match.group(3)) == 0 or 'VulkanForge development AI search counters installed' not in text:
                raise RuntimeError('Search-node instrument did not observe work: ' + str(folder / 'gradle.log'))
            values = list(map(int, node_match.groups()))
            if values[0] != values[1] or values[6] != 0:
                raise RuntimeError('Incomplete search timing (exception or unclosed call): ' + str(folder / 'gradle.log'))
            record['pathfinder'] = dict(zip(('searches', 'completed', 'heap_pops', 'elapsed_ns', 'max_search_ns', 'max_pops', 'open_depth'), values))
            record['search_ticks'] = json.loads(subprocess.check_output([sys.executable, str(ROOT / 'tools' / 'analyze-ai-ticks.py'),
                                                                         str(csvs[0]), '--expected-samples', str(args.samples)], text=True))
        if instrumented and args.phase_profile:
            record['phase_ticks'] = json.loads(subprocess.check_output([sys.executable, str(ROOT / 'tools' / 'analyze-entity-phases.py'),
                                                                        str(csvs[0]), '--expected-samples', str(args.samples)], text=True))
        if instrumented and args.goal_detail:
            matches = re.findall(r'GOAL_DETAIL type=([^ ]+) op=(\d+) calls=(\d+) elapsedNs=(\d+)', text)
            if not matches:
                raise RuntimeError('No Goal callback details recorded: ' + str(folder / 'gradle.log'))
            record['goal_details'] = [dict(type=t, operation=int(op), calls=int(calls), elapsed_ns=int(ns)) for t, op, calls, ns in matches]
        manifest['cases'].append(record)
        (output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
        print(json.dumps(record, indent=2), flush=True)
    print('Results:', output, flush=True)


if __name__ == '__main__':
    main()
