"""Summarize save/load and allocation indicators without exposing JFR JVM arguments or paths."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re

from jfr_events import frame_names, iter_events

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
    events = ('jdk.ExecutionSample', 'jdk.ObjectAllocationSample', 'jdk.FileRead', 'jdk.FileWrite', 'jdk.GCPhasePause')
    count, categories, tops, io, gc = Counter(), defaultdict(Counter), defaultdict(Counter), defaultdict(list), []
    fragments = {'chunk_serializer': ('/chunk/storage/ChunkSerializer.',),
                 'region_file': ('/chunk/storage/RegionFile.',),
                 'nbt': ('/nbt/',), 'zlib': ('java/util/zip/Deflater', 'java/util/zip/Inflater'),
                 'chunk_map': ('/server/level/ChunkMap.',), 'chunk_cache': ('/server/level/ServerChunkCache.',)}
    for event in iter_events(args.recording, *events):
        kind, v = event['type'], event['values']
        if kind in ('jdk.FileRead', 'jdk.FileWrite'):
            thread = (v.get('eventThread') or {}).get('javaName')
            io[(kind, 'server' if thread == 'Server thread' else 'other')].append(ms(v.get('duration')))
        elif kind == 'jdk.GCPhasePause':
            gc.append(ms(v.get('duration')))
        else:
            thread = (v.get('sampledThread' if kind == 'jdk.ExecutionSample' else 'eventThread') or {}).get('javaName')
            group = 'server' if thread == 'Server thread' else 'other'
            stack = v.get('stackTrace')
            if not stack or not stack.get('frames'):
                continue
            count[(kind, group)] += 1
            frames = frame_names(stack)
            for name, parts in fragments.items():
                if any(any(part in f for part in parts) for f in frames):
                    categories[(kind, group)][name] += 1
                    if name in ('chunk_serializer', 'nbt', 'region_file', 'zlib'):
                        tops[(kind, group, name)][frames[0]] += 1
    print(json.dumps({'sample_counts': {':'.join(k): v for k, v in count.items()},
                      'inclusive_category_samples': {':'.join(k): dict(v) for k, v in categories.items()},
                      'sampled_leaf_methods': {':'.join(k): v.most_common(8) for k, v in tops.items()},
                      'recorded_file_io_events': {':'.join(k): {'count': len(v), 'total_ms': round(sum(v), 3), 'max_ms': round(max(v), 3)} for k, v in io.items()},
                      'recorded_gc_pause_events': {'count': len(gc), 'total_ms': round(sum(gc), 3), 'max_ms': round(max(gc), 3) if gc else 0},
                      'note': 'Sampling categories overlap; file events may be thresholded. Neither counts nor durations attribute a full Tick or prove storage-device latency.'}, indent=2))


if __name__ == '__main__':
    main()
