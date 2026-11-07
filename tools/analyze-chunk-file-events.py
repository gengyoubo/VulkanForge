"""Aggregate thresholded JFR file events by region-file extension without revealing paths."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re

from jfr_events import iter_events

TIME = re.compile(r'^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$')


def ms(value):
    m = TIME.fullmatch(value or '')
    if not m:
        return 0.0
    h, minutes, seconds = m.groups()
    return (int(h or 0) * 3600 + int(minutes or 0) * 60 + float(seconds or 0)) * 1000


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('recording', type=Path)
    args = p.parse_args()
    groups = defaultdict(list)
    for event in iter_events(args.recording, 'jdk.FileRead', 'jdk.FileWrite'):
        value = event['values']
        path = (value.get('path') or '').lower().replace('\\', '/')
        category = 'region' if path.endswith(('.mca', '.mcc')) and '/region/' in path else 'other_or_unknown'
        thread = (value.get('eventThread') or {}).get('javaName')
        groups[(event['type'], category, 'server' if thread == 'Server thread' else 'other')].append(ms(value.get('duration')))
    print(json.dumps({':'.join(key): {'count': len(durations), 'total_ms': round(sum(durations), 3), 'max_ms': round(max(durations), 3)}
                      for key, durations in groups.items()}, indent=2))


if __name__ == '__main__':
    main()
