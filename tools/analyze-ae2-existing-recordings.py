"""Read bounded-memory AE2 counts from already recorded MFCL A/B measurement windows."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LINE = re.compile(r'(\w+)=(\d+)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cases', type=Path, nargs='+', help='case folders containing run.json and profile.jfr')
    args = parser.parse_args()
    out = ROOT / 'build/reports/ae2-controller-reflection/jfr-analysis'
    out.mkdir(parents=True, exist_ok=True)
    home = os.environ.get('JAVA_HOME')
    def tool(name): return str(Path(home) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))) if home else name
    subprocess.run([tool('javac'), '-d', str(out), str(ROOT / 'tools/experiments/Ae2RecordingAnalysis.java')], check=True)
    results = []
    for folder in args.cases:
        run = json.loads((folder / 'run.json').read_text(encoding='utf-8'))
        start, end = run['windowStartMillis'], run['windowEndMillis']
        if not isinstance(start, int) or not isinstance(end, int) or start >= end: raise RuntimeError('invalid window')
        value = subprocess.check_output([tool('java'), '-Xms32m', '-Xmx192m', '-cp', str(out), 'Ae2RecordingAnalysis',
                                         str(folder / 'profile.jfr'), str(start), str(end)], text=True)
        if not value.startswith('AE2_RECORDING '): raise RuntimeError('analysis failed')
        results.append({'case': folder.name, 'window_start_ms': start, 'window_end_ms': end,
                        'counts': {k: int(v) for k, v in LINE.findall(value)}})
    (out / 'summary.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
    print(json.dumps(results, indent=2))


if __name__ == '__main__': main()
