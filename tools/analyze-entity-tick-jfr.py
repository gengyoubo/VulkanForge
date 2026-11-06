"""Classify Java execution samples without treating overlapping stacks as exclusive Tick time."""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path

from jfr_events import iter_events, frame_names


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path)
    parser.add_argument('--start', required=True)
    parser.add_argument('--end', required=True)
    args = parser.parse_args()
    start, end = datetime.fromisoformat(args.start), datetime.fromisoformat(args.end)
    if start.tzinfo is None or end.tzinfo is None or start >= end:
        parser.error('start and end must include offsets and be ordered')
    entity_tick ={'net/minecraft/server/level/ServerLevel.m_8647_', 'net/minecraft/server/level/ServerLevel.tickNonPassenger',
                   'net/minecraft/server/level/ServerLevel.m_8662_', 'net/minecraft/server/level/ServerLevel.tickPassenger',
                   'net/minecraft/world/entity/LivingEntity.m_8119_', 'net/minecraft/world/entity/LivingEntity.tick',
                   'net/minecraft/world/entity/Mob.m_8119_', 'net/minecraft/world/entity/Mob.tick',
                   'net/minecraft/world/entity/Mob.m_8107_', 'net/minecraft/world/entity/Mob.aiStep',
                   'net/minecraft/world/entity/LivingEntity.m_8107_', 'net/minecraft/world/entity/LivingEntity.aiStep'}
    collision_methods = {'net/minecraft/world/entity/Entity.m_20272_', 'net/minecraft/world/entity/Entity.collide',
                         'net/minecraft/world/entity/Entity.m_198894_', 'net/minecraft/world/entity/Entity.collideBoundingBox',
                         'net/minecraft/world/level/CollisionGetter.m_183134_', 'net/minecraft/world/level/CollisionGetter.getEntityCollisions',
                         'net/minecraft/world/level/CollisionGetter.m_186434_', 'net/minecraft/world/level/CollisionGetter.getBlockCollisions'}
    counts, leaves = Counter(), Counter()
    details = {name: Counter() for name in ('entity_tick', 'goal', 'entity_section_query', 'collision_explicit', 'block_collisions')}
    overlaps = Counter()
    samples = truncated = 0
    for event in iter_events(args.recording, 'jdk.ExecutionSample'):
        value = event['values']; stamp = datetime.fromisoformat(value['startTime'])
        thread, stack = value.get('sampledThread'), value.get('stackTrace')
        if not start <= stamp < end or not thread or thread.get('javaName') != 'Server thread' or not stack:
            continue
        frames = frame_names(stack)
        if not frames:
            continue
        samples += 1; truncated += bool(stack.get('truncated')); leaves[frames[0]] += 1
        frame_set = set(frames)
        groups = {
            'entity_tick': {x for x in frames if x in entity_tick},
            'goal': {x for x in frames if '/world/entity/ai/goal/' in x},
            'entity_section_query': {x for x in frames if '/world/level/entity/EntitySection' in x},
            'collision_explicit': {x for x in frames if x in collision_methods or '/entity/CanaryEntityCollisions.' in x or '/entity/LithiumEntityCollisions.' in x},
            'block_collisions': {x for x in frames if '/world/level/BlockCollisions.' in x},
        }
        for name, matching in groups.items():
            if matching: counts[name] += 1; details[name].update(matching)
        for left, right in (('goal', 'entity_tick'), ('collision_explicit', 'entity_section_query'), ('block_collisions', 'entity_tick')):
            if groups[left] and groups[right]: overlaps[left + '&' + right] += 1
    result = {'start': args.start, 'end': args.end, 'samples': samples, 'truncated': truncated,
              'note': 'Inclusive server-thread Java CPU samples, not wall time. Groups overlap; incomplete stacks and mod overrides may be missed.',
              'groups': {key: {'samples': counts[key], 'percent': 100 * counts[key] / samples if samples else None,
                                'methods': details[key].most_common(12)} for key in details},
              'observed_overlaps': dict(overlaps), 'top_leaf_methods': leaves.most_common(25)}
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
