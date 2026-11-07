"""Run an offline A/B on disposable copies of an existing client instance and save."""
import argparse
import ctypes
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
ARTIFACT = ROOT / 'build/reports/ae2-controller-reflection/artifact/vf-ae2-controller-experiment.jar'
JDK = Path.home() / '.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.17+10/bin'
EXCLUDE = {'saves', 'logs', 'crash-reports', 'screenshots', 'backups', 'PCL'}
EXPECTED_MODS = {
    '[应用能源2] appliedenergistics2-forge-15.4.12.jar': 'b9c5928893a20d3feb0b4393e2c23d756a16d9abee24d9c14c98e6096d53844d',
    '[AE2超频] ae2_overclocked-1.2.3-fix3.jar': '8aacfceedaaaa9eac4a5169228c550d52075bd69ada33f7e082a05fe12641759',
    'guideme-20.1.15.jar': 'cf8052ab3da121012efc59a20e731c24bfe7488dfa556c9b88809f42dea3b24a',
}


def hash_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''): digest.update(data)
    return digest.hexdigest()


def hash_world(root):
    digest = hashlib.sha256()
    for path in sorted(p for p in root.rglob('*') if p.is_file() and p.name != 'session.lock'):
        digest.update(path.relative_to(root).as_posix().encode() + b'\0' + bytes.fromhex(hash_file(path)))
    return digest.hexdigest()


def tree_size(root, ignored=()):
    total = 0
    for parent, dirs, files in os.walk(root, followlinks=False):
        if Path(parent) == root: dirs[:] = [d for d in dirs if d not in ignored]
        for name in dirs:
            path = Path(parent) / name
            if path.is_symlink() or path.is_junction(): raise RuntimeError('source contains a link or junction')
        for name in files:
            path = Path(parent) / name
            if path.is_symlink(): raise RuntimeError('source contains a symbolic link')
            size = path.stat().st_size
            total += ((size + 4095) // 4096) * 4096
    return total


def copy_with_reserve(source, destination):
    size = Path(source).stat().st_size
    if shutil.disk_usage(Path(destination).parent).free < size + 1_300_000_000:
        raise RuntimeError('copy would leave less than 1.3 GB free')
    return shutil.copy2(source, destination)


def available_memory():
    if os.name != 'nt': return os.sysconf('SC_AVPHYS_PAGES') * os.sysconf('SC_PAGE_SIZE')
    class MemoryStatus(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ulong), ('load', ctypes.c_ulong),
                    ('total_phys', ctypes.c_ulonglong), ('avail_phys', ctypes.c_ulonglong),
                    ('total_page', ctypes.c_ulonglong), ('avail_page', ctypes.c_ulonglong),
                    ('total_virtual', ctypes.c_ulonglong), ('avail_virtual', ctypes.c_ulonglong),
                    ('avail_extended', ctypes.c_ulonglong)]
    state = MemoryStatus(); state.length = ctypes.sizeof(state)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(state)): raise OSError('cannot query available memory')
    return state.avail_phys


def remove_child(game, child, expected_name):
    game = game.resolve(strict=True)
    child = child.resolve(strict=True)
    if not (game / '.codex-disposable-ae2').is_file() or child.name != expected_name or not child.is_relative_to(game):
        raise RuntimeError('refusing to remove unverified game child')
    shutil.rmtree(child)


def remove_owned(path, root):
    path, root = path.resolve(), root.resolve()
    if path.parent != root or not path.name.startswith(('snapshot-', 'game-')) or not (path / '.codex-disposable-ae2').is_file():
        raise RuntimeError('refusing to remove unverified directory: ' + str(path))
    shutil.rmtree(path)


def running_game_count():
    query = "(Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | Where-Object { $_.CommandLine -match 'bootstraplauncher' } | Measure-Object).Count"
    result = subprocess.run(['powershell.exe', '-NoProfile', '-Command', query], capture_output=True, text=True, check=True,
                            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    return int(result.stdout.strip() or '0')


def command(instance, game_dir, output, mode):
    mc = instance.parent.parent
    info = json.loads((instance / (instance.name + '.json')).read_text(encoding='utf-8'))
    def allowed(rules):
        ok = not rules
        for r in rules or []:
            system = r.get('os', {})
            if (not system.get('name') or system['name'] == 'windows') and not system.get('arch') and not r.get('features'):
                ok = r['action'] == 'allow'
        return ok
    cp = []
    for lib in info['libraries']:
        if not allowed(lib.get('rules')) or ('natives' in lib and 'artifact' not in lib.get('downloads', {})): continue
        artifact = lib.get('downloads', {}).get('artifact')
        if artifact and artifact.get('path'): relative = artifact['path']
        else:
            group, name, version, *classifier = lib['name'].split(':')
            relative = f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}{'-' + classifier[0] if classifier else ''}.jar"
        path = mc / 'libraries' / Path(relative)
        if path.is_file() and str(path) not in cp: cp.append(str(path))
    cp.append(str(instance / (instance.name + '.jar')))
    replacements = {'natives_directory': str(instance / (instance.name + '-natives')), 'launcher_name': 'vf-ae2-ab', 'launcher_version': '1',
                    'classpath': ';'.join(cp), 'library_directory': str(mc / 'libraries'), 'classpath_separator': ';', 'version_name': instance.name,
                    'auth_player_name': 'gengyoubo', 'game_directory': str(game_dir), 'assets_root': str(mc / 'assets'), 'assets_index_name': info['assets'],
                    'auth_uuid': '8922cbbc0c404262b0b8a153ec94ecdc', 'auth_access_token': '0', 'clientid': '0', 'auth_xuid': '0',
                    'user_type': 'legacy', 'version_type': 'release'}
    def fill(value): return re.sub(r'\$\{(\w+)\}', lambda m: replacements.get(m.group(1), m.group(0)), value)
    jvm = [fill(v) for v in info['arguments']['jvm'] if isinstance(v, str)]
    game = [fill(v) for v in info['arguments']['game'] if isinstance(v, str)]
    return ([str(JDK / 'java.exe'), '-XX:+UseG1GC', '-XX:-UseAdaptiveSizePolicy', '-XX:-OmitStackTraceInFastThrow',
             '-XX:+UnlockDiagnosticVMOptions', '-XX:+DebugNonSafepoints', '-XX:StartFlightRecording=name=ab,settings=profile,maxage=6m,disk=true',
             '-Dfml.ignoreInvalidMinecraftCertificates=True', '-Dfml.ignorePatchDiscrepancies=True', '-Dlog4j2.formatMsgNoLookups=true',
             '-Xmn1228m', '-Xmx8192m', '-Dvf.ae2.controllerFast=' + ('true' if mode == 'candidate' else 'false'),
             '-Dvulkanforge.disableAe2ControllerReflection=' + ('false' if mode == 'candidate' else 'true'),
             '-Dabprobe.dir=' + str(output)] + jvm + [info['mainClass']] + game + ['--width', '854', '--height', '480', '--quickPlaySingleplayer', '生存游玩'])


def summarize(path, start_ms, end_ms):
    with path.open(encoding='utf-8', newline='') as f:
        values = [int(row['tickNanos']) / 1e6 for row in csv.DictReader(f)
                  if start_ms <= int(row['wallMillis']) < end_ms and int(row['tickNanos']) > 0]
    if len(values) < 100: raise RuntimeError('not enough recorded server ticks')
    values.sort()
    def percentile(p): return values[max(0, (len(values) * p + 99) // 100 - 1)]
    return {'samples': len(values), 'mean_ms': statistics.mean(values), 'median_ms': statistics.median(values),
            'p95_ms': percentile(95), 'p99_ms': percentile(99), 'over_50ms': sum(x > 50 for x in values)}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--instance', type=Path, required=True)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--warmup', type=int, default=90)
    p.add_argument('--measure', type=int, default=120)
    p.add_argument('--modes', nargs='+', choices=('control', 'candidate'), default=['control', 'candidate'])
    args = p.parse_args()
    instance = args.instance.resolve()
    world = instance / 'saves' / '生存游玩'
    if args.warmup < 0 or args.measure < 30 or not ARTIFACT.is_file() or not args.probe.is_file() or not (world / 'level.dat').is_file():
        p.error('invalid warmup, measurement, experiment jar, probe or source world')
    for name, expected in EXPECTED_MODS.items():
        path = instance / 'mods' / name
        if not path.is_file() or hash_file(path) != expected: raise RuntimeError('unexpected source mod version: ' + name)
    if running_game_count(): raise RuntimeError('another Minecraft instance is running; refusing to copy its world')
    if available_memory() < 5_000_000_000: raise RuntimeError('less than 5 GB available physical memory')
    estimated = tree_size(instance, EXCLUDE) + 2 * tree_size(world)
    if shutil.disk_usage(ROOT).free < estimated + 1_500_000_000:
        raise RuntimeError('not enough space for one isolated game, two world copies and 1.5 GB reserve')
    if (instance / '.codex-disposable-ae2').exists(): raise RuntimeError('source already contains the private test marker')
    output = ROOT / 'build/reports/ae2-controller-pack' / str(uuid.uuid4())
    output.mkdir(parents=True)
    snapshot = output / 'snapshot-world'
    game_dir = output / 'game-instance'
    config_template = output / 'snapshot-config'
    print('Copying one isolated instance and a survival-world template...', flush=True)
    def ignore(parent, names):
        if Path(parent).resolve() == instance: return set(names) & EXCLUDE
        return {'session.lock'} & set(names)
    snapshot.mkdir()
    (snapshot / '.codex-disposable-ae2').write_text('Disposable snapshot.\n')
    try:
        shutil.copytree(world, snapshot / 'world', ignore=shutil.ignore_patterns('session.lock'), copy_function=copy_with_reserve)
        source_hash = hash_world(world)
        if hash_world(snapshot / 'world') != source_hash: raise RuntimeError('source world changed or copy differs')
        game_dir.mkdir()
        (game_dir / '.codex-disposable-ae2').write_text('Disposable test game.\n')
        shutil.copytree(instance, game_dir, ignore=ignore, dirs_exist_ok=True, copy_function=copy_with_reserve)
        for name, expected in EXPECTED_MODS.items():
            if hash_file(game_dir / 'mods' / name) != expected: raise RuntimeError('game mod differs from verified artifact: ' + name)
        config_template.mkdir()
        (config_template / '.codex-disposable-ae2').write_text('Disposable configuration template.\n')
        shutil.copytree(game_dir / 'config', config_template / 'config', copy_function=copy_with_reserve)
        mods = game_dir / 'mods'
        for source in (ARTIFACT, args.probe):
            if (mods / source.name).exists(): raise RuntimeError('test mod filename already exists')
            copy_with_reserve(source, mods / source.name)
        options = game_dir / 'options.txt'
        if options.exists():
            contents = options.read_text(encoding='utf-8')
            for key, value in (('enableVsync', 'false'), ('maxFps', '260'), ('pauseOnLostFocus', 'false')):
                expression = r'(?m)^' + key + r':.*$'
                contents = re.sub(expression, key + ':' + value, contents) if re.search(expression, contents) else contents + '\n' + key + ':' + value + '\n'
            options.write_text(contents, encoding='utf-8')
    except BaseException:
        if config_template.exists(): remove_owned(config_template, output)
        if game_dir.exists(): remove_owned(game_dir, output)
        remove_owned(snapshot, output)
        raise
    record = {'world_sha256': source_hash, 'experiment_sha256': hash_file(ARTIFACT),
              'note': 'Same isolated game reused; world and config reset, other runtime caches may evolve.', 'forks': []}
    process = None
    try:
        for number, mode in enumerate(args.modes, 1):
            if running_game_count(): raise RuntimeError('another Minecraft instance is running')
            case = output / f'{number:02d}-{mode}'; case.mkdir()
            print(f'Preparing {number}/{len(args.modes)}: {mode}', flush=True)
            if number > 1:
                if (game_dir / 'config').exists(): remove_child(game_dir, game_dir / 'config', 'config')
                shutil.copytree(config_template / 'config', game_dir / 'config', copy_function=copy_with_reserve)
            (game_dir / 'saves').mkdir(exist_ok=True)
            world_copy = game_dir / 'saves' / '生存游玩'
            if world_copy.exists(): raise RuntimeError('previous disposable world was not cleaned')
            shutil.copytree(snapshot / 'world', world_copy, ignore=shutil.ignore_patterns('session.lock'), copy_function=copy_with_reserve)
            if hash_world(world_copy) != source_hash: raise RuntimeError('case world differs from snapshot')
            log = game_dir / 'logs' / 'latest.log'
            if log.exists(): log.unlink()
            if shutil.disk_usage(ROOT).free < 1_300_000_000: raise RuntimeError('insufficient disk space before launch')
            if available_memory() < 5_000_000_000: raise RuntimeError('less than 5 GB available physical memory before launch')
            startup = subprocess.STARTUPINFO() if os.name == 'nt' else None
            if startup: startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW; startup.wShowWindow = 0
            with (case / 'stdout.log').open('wb') as stdout:
                process = subprocess.Popen(command(instance, game_dir, case, mode), cwd=game_dir, stdout=stdout, stderr=subprocess.STDOUT, startupinfo=startup)
                joined = None; begun = time.time(); last_report = begun
                try:
                    while process.poll() is None and joined is None and time.time() - begun < 900:
                        if shutil.disk_usage(ROOT).free < 1_000_000_000: raise RuntimeError('disk space fell below 1 GB')
                        if available_memory() < 1_500_000_000: raise RuntimeError('available physical memory fell below 1.5 GB')
                        if log.exists() and 'logged in with entity id' in log.read_text(encoding='utf-8', errors='replace'): joined = time.time()
                        if time.time() - last_report >= 30: print(f'{mode}: waiting for world ({int(time.time()-begun)} s)', flush=True); last_report = time.time()
                        time.sleep(2)
                    if joined is None or process.poll() is not None: raise RuntimeError('game did not enter the world: ' + str(case))
                    print(f'{mode}: world entered after {int(joined-begun)} s; warming up', flush=True)
                    while process.poll() is None and time.time() < joined + args.warmup:
                        if shutil.disk_usage(ROOT).free < 1_000_000_000: raise RuntimeError('disk space fell below 1 GB')
                        if available_memory() < 1_500_000_000: raise RuntimeError('available physical memory fell below 1.5 GB')
                        time.sleep(2)
                    if process.poll() is not None: raise RuntimeError('game exited during warmup')
                    start = int(time.time() * 1000); print(f'{mode}: measuring {args.measure} s', flush=True)
                    while process.poll() is None and time.time() * 1000 < start + args.measure * 1000:
                        if shutil.disk_usage(ROOT).free < 1_000_000_000: raise RuntimeError('disk space fell below 1 GB')
                        if available_memory() < 1_500_000_000: raise RuntimeError('available physical memory fell below 1.5 GB')
                        time.sleep(2)
                    end = int(time.time() * 1000)
                    if process.poll() is not None: raise RuntimeError('game exited during measurement')
                    dump = subprocess.run([str(JDK / 'jcmd.exe'), str(process.pid), 'JFR.dump', 'name=ab', 'filename=' + str(case / 'profile.jfr')],
                                          capture_output=True, text=True, errors='replace', timeout=90, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                    if dump.returncode or not (case / 'profile.jfr').is_file(): raise RuntimeError('JFR dump failed')
                    time.sleep(6)
                finally:
                    if process.poll() is None:
                        process.terminate()
                        try: process.wait(timeout=30)
                        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=30)
            if log.exists(): shutil.copy2(log, case / 'latest.log')
            contents = (case / 'latest.log').read_text(encoding='utf-8', errors='replace')
            launcher_log = (case / 'stdout.log').read_text(encoding='utf-8', errors='replace')
            if 'VF_AE2_CONTROLLER_EXPERIMENT enabled=' + ('true' if mode == 'candidate' else 'false') not in contents + launcher_log or 'VF AE2 controller reflection experiment installed' not in contents + launcher_log:
                raise RuntimeError('experiment did not load with the requested state')
            screens = case / 'screens.csv'
            if (case / 'probe-errors.txt').exists() or not screens.exists(): raise RuntimeError('measurement probe failed')
            state = None
            with screens.open(encoding='utf-8', newline='') as f:
                for line in f:
                    parts = line.strip().split(',', 1)
                    if len(parts) == 2 and parts[0].isdigit() and int(parts[0]) < end:
                        if int(parts[0]) >= start and parts[1] != 'none': raise RuntimeError('game screen opened during measurement')
                        if int(parts[0]) < start: state = parts[1]
            if state != 'none': raise RuntimeError('game was not in active play at measurement start')
            tick = summarize(case / 'ticks.csv', start, end)
            record['forks'].append({'mode': mode, 'joined_seconds': joined-begun, 'window_start_ms': start, 'window_end_ms': end, 'ticks': tick})
            (output / 'summary.json').write_text(json.dumps(record, indent=2), encoding='utf-8')
            print(mode, json.dumps(tick), flush=True)
            remove_child(game_dir, world_copy, '生存游玩')
    finally:
        if process is None or process.poll() is not None:
            if game_dir.exists():
                case = sorted(output.glob('[0-9][0-9]-*'))[-1] if list(output.glob('[0-9][0-9]-*')) else None
                log = game_dir / 'logs' / 'latest.log'
                if case is not None and log.is_file() and not (case / 'latest.log').exists(): shutil.copy2(log, case / 'latest.log')
                remove_owned(game_dir, output)
            remove_owned(config_template, output)
            remove_owned(snapshot, output)
    print('Results:', output, flush=True)


if __name__ == '__main__': main()
