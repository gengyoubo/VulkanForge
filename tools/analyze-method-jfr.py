"""Per-thread JFR view of one method: samples, what lies directly below it, callers, and monitor blocking."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import json
from pathlib import Path
import re

from jfr_events import iter_events, frame_names

HIDDEN_SUFFIX = re.compile(r'\$\$Lambda\$\d+\+0x[0-9a-f]+/\d+')
DURATION = re.compile(r'PT(?:(\d+)M)?(?:([\d.]+)S)?')


def duration_ms(text):
    minutes, seconds = DURATION.fullmatch(text).groups()
    return (int(minutes or 0) * 60 + float(seconds or 0)) * 1000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path)
    parser.add_argument('method', help='frame suffix, e.g. LegacyClientData.materialize')
    parser.add_argument('--caller-prefix', default='', help='report the nearest caller whose name contains this')
    parser.add_argument('--monitor', help='monitor class suffix for jdk.JavaMonitorEnter, e.g. LegacyClientData')
    parser.add_argument('--start', help='ISO 8601 with UTC offset')
    parser.add_argument('--end', help='ISO 8601 with UTC offset')
    args = parser.parse_args()
    start = datetime.fromisoformat(args.start) if args.start else None
    end = datetime.fromisoformat(args.end) if args.end else None

    def in_window(value):
        stamp = datetime.fromisoformat(value['startTime'])
        return not (start and stamp < start or end and stamp >= end)

    totals, matched = Counter(), Counter()
    below, callers = defaultdict(Counter), defaultdict(Counter)
    for event in iter_events(args.recording, 'jdk.ExecutionSample'):
        value = event['values']
        if not in_window(value):
            continue
        thread, stack = (value.get('sampledThread') or {}).get('javaName'), value.get('stackTrace')
        if not stack or not stack['frames']:
            continue
        frames = [HIDDEN_SUFFIX.sub('$$Lambda', frame) for frame in frame_names(stack)]
        totals[thread] += 1
        index = next((i for i, frame in enumerate(frames) if frame.endswith(args.method)), None)
        if index is None:
            continue
        matched[thread] += 1
        below[thread][frames[index - 1] if index else '<self>'] += 1
        callers[thread][next((f for f in frames[index + 1:] if args.caller_prefix in f), '?')] += 1
    blocked = defaultdict(list)
    owners = defaultdict(Counter)
    if args.monitor:
        for event in iter_events(args.recording, 'jdk.JavaMonitorEnter'):
            value = event['values']
            monitor = value.get('monitorClass') or {}
            if not in_window(value) or not monitor.get('name', '').endswith(args.monitor):
                continue
            thread = (value.get('eventThread') or {}).get('javaName')
            blocked[thread].append(duration_ms(value['duration']))
            owners[thread][(value.get('previousOwner') or {}).get('javaName')] += 1
    print(json.dumps({
        'note': 'Java CPU samples, not frame or Tick time. Monitor events only cover blocks above the recording threshold.',
        'threads': {thread: {'samples': totals[thread], 'matched': count, 'percent': 100 * count / totals[thread],
                             'directly_below': below[thread].most_common(8), 'callers': callers[thread].most_common(8)}
                    for thread, count in matched.most_common(6)},
        'monitor_blocking': {thread: {'events': len(times), 'total_ms': sum(times), 'median_ms': sorted(times)[len(times) // 2],
                                      'max_ms': max(times), 'at_least_50ms': sum(t >= 50 for t in times),
                                      'previous_owners': owners[thread].most_common(5)}
                             for thread, times in sorted(blocked.items(), key=lambda item: -sum(item[1]))[:6]},
    }, indent=2))


if __name__ == '__main__':
    main()
