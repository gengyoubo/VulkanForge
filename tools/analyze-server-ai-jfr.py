"""Classify sampled server-thread stacks for AI, pathfinding and collision research."""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path

from jfr_events import iter_events, frame_names

GROUPS = {
    'pathfinding_navigation': ('/world/level/pathfinder/', '/world/entity/ai/navigation/'),
    'ai_goals': ('/world/entity/ai/goal/',),
    'ai_brain_behavior': ('/world/entity/ai/behavior/', '/world/entity/ai/Brain'),
    'entity_section_queries': ('/world/level/entity/EntitySection',),
    'block_collision_iterator': ('/world/level/BlockCollisions.',),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path)
    parser.add_argument('--start', required=True, help='ISO 8601 timestamp with UTC offset')
    parser.add_argument('--end', required=True, help='ISO 8601 timestamp with UTC offset')
    args = parser.parse_args()
    start, end = datetime.fromisoformat(args.start), datetime.fromisoformat(args.end)
    if start.tzinfo is None or end.tzinfo is None or start >= end:
        parser.error('timestamps must have offsets, with start before end')
    count = truncated = 0
    groups = Counter()
    methods = {name: Counter() for name in GROUPS}
    for event in iter_events(args.recording, 'jdk.ExecutionSample'):
        value = event['values']
        time = datetime.fromisoformat(value['startTime'])
        thread, stack = value.get('sampledThread'), value.get('stackTrace')
        if not (start <= time < end) or not thread or thread.get('javaName') != 'Server thread' or not stack:
            continue
        count += 1
        truncated += bool(stack.get('truncated'))
        frames = set(frame_names(stack))
        for name, patterns in GROUPS.items():
            matched = {frame for frame in frames if any(pattern in frame for pattern in patterns)}
            if matched:
                groups[name] += 1
                methods[name].update(matched)
    result = {'start': args.start, 'end': args.end, 'server_thread_samples': count,
              'truncated_server_stacks': truncated,
              'note': 'Inclusive sampled stacks, not Tick time; groups may overlap, JIT/incomplete stacks and mod AI can be missed.',
              'groups': {name: {'samples': groups[name], 'percent': 100 * groups[name] / count if count else None,
                                 'methods': methods[name].most_common(15)} for name in GROUPS}}
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
