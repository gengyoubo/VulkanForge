"""Summarize server-thread execution and allocation samples in a JFR time window."""
import argparse
from collections import Counter
from datetime import datetime
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jfr', type=Path)
    parser.add_argument('--start', required=True, help='ISO 8601 timestamp with UTC offset')
    parser.add_argument('--end', required=True, help='ISO 8601 timestamp with UTC offset')
    args = parser.parse_args()
    start, end = datetime.fromisoformat(args.start), datetime.fromisoformat(args.end)
    if start.tzinfo is None or end.tzinfo is None or start >= end:
        parser.error('timestamps must have offsets and start must precede end')
    java_home = os.environ.get('JAVA_HOME')
    executable = str(Path(java_home) / 'bin' / ('jfr.exe' if os.name == 'nt' else 'jfr')) if java_home else 'jfr'
    data = subprocess.check_output([executable, 'print', '--json', '--events',
                                    'jdk.ExecutionSample,jdk.ObjectAllocationSample', str(args.jfr)])
    events = json.loads(data)['recording']['events']
    counts = {'jdk.ExecutionSample': Counter(), 'jdk.ObjectAllocationSample': Counter()}
    totals = Counter()
    for event in events:
        kind, value = event['type'], event['values']
        if kind not in counts:
            continue
        timestamp = datetime.fromisoformat(value['startTime'])
        thread = value.get('sampledThread' if kind == 'jdk.ExecutionSample' else 'eventThread')
        trace = value.get('stackTrace')
        if not (start <= timestamp < end) or not thread or thread['javaName'] != 'Server thread' or not trace:
            continue
        totals[kind] += 1
        methods = {frame['method']['type']['name'] + '.' + frame['method']['name'] for frame in trace['frames']}
        counts[kind].update(methods)
    print(json.dumps({'start': args.start, 'end': args.end,
                      'note': 'Inclusive stack sample counts, not CPU time or exact allocation volume.',
                      'execution_samples': totals['jdk.ExecutionSample'],
                      'execution_inclusive': counts['jdk.ExecutionSample'].most_common(30),
                      'allocation_samples': totals['jdk.ObjectAllocationSample'],
                      'allocation_inclusive': counts['jdk.ObjectAllocationSample'].most_common(30)}, indent=2))


if __name__ == '__main__':
    main()
