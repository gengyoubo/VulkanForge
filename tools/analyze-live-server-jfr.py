"""Estimate sampled server-thread CPU hotspots from a Java Flight Recorder file."""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path

from jfr_events import iter_events, frame_names


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path)
    parser.add_argument('--start', help='optional ISO 8601 timestamp including UTC offset')
    parser.add_argument('--end', help='optional ISO 8601 timestamp including UTC offset')
    args = parser.parse_args()
    start = datetime.fromisoformat(args.start) if args.start else None
    end = datetime.fromisoformat(args.end) if args.end else None
    if any(t is not None and t.tzinfo is None for t in (start, end)):
        parser.error('timestamps must include UTC offsets')
    inclusive, leaf = Counter(), Counter()
    server_count = tick_count = support_count = truncated = 0
    support = {'net/minecraft/world/level/CollisionGetter.findSupportingBlock',
               'net/minecraft/world/level/CollisionGetter.m_285750_'}
    tick = {'net/minecraft/server/MinecraftServer.tickServer', 'net/minecraft/server/MinecraftServer.m_5705_',
            'net/minecraft/server/MinecraftServer.tickChildren', 'net/minecraft/server/MinecraftServer.m_5703_'}
    for event in iter_events(args.recording, 'jdk.ExecutionSample'):
        v = event['values']
        stamp = datetime.fromisoformat(v['startTime'])
        thread, trace = v.get('sampledThread'), v.get('stackTrace')
        if start and stamp < start or end and stamp >= end:
            continue
        if not thread or thread.get('javaName') != 'Server thread' or not trace:
            continue
        server_count += 1
        if trace.get('truncated'):
            truncated += 1
        methods = frame_names(trace)
        if not methods:
            continue
        if tick.intersection(methods):
            tick_count += 1
        if support.intersection(methods):
            support_count += 1
        inclusive.update(set(methods))
        leaf[methods[0]] += 1
    result = {'server_thread_samples': server_count, 'samples_with_tick_frame': tick_count,
              'samples_with_support_frame': support_count, 'truncated_server_stacks': truncated,
              'support_percent_of_server_samples': 100 * support_count / server_count if server_count else None,
              'note': 'CPU execution samples, not elapsed Tick time or exact method timings. Inlining and truncated stacks can hide frames.',
              'top_server_stack_frames': inclusive.most_common(35), 'top_server_leaf_methods': leaf.most_common(25)}
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
