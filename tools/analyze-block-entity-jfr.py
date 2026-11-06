"""Attribute server-thread JFR samples to Tick phases, block entity tickers and Mekanism's tickServer."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import json
from pathlib import Path
import re

from jfr_events import iter_events, frame_names

BLOCK_ENTITY_PHASE = '/Level.m_46463_'  # Level.tickBlockEntities
BLOCK_ENTITY_TICK = '$BoundTickingBlockEntity.m_142224_'
MEKANISM_TICK = 'mekanism/common/tile/base/TileEntityMekanism.tickServer'
# First match wins, so each sample lands in exactly one phase. Only these phases may be summed.
PHASES = (('player_tick_via_network', '/ServerConnectionListener.m_9721_'),
          ('block_entities', BLOCK_ENTITY_PHASE),
          ('entities', '/EntityTickList.m_156910_'),
          ('chunk_cache', '/ServerChunkCache.m_201698_'),
          ('server_level_other', '/ServerLevel.m_8793_'),
          ('tick_children_other', '/MinecraftServer.m_5703_'),
          ('tick_server_other', '/MinecraftServer.m_5705_'))
NAMESPACES = {'mekanism': 'mekanism/', 'ae2': 'appeng/', 'create': 'com/simibubi/create/',
              'changed': 'net/ltxprogrammer/changed/', 'mfcl': 'github/com/gengyoubo/mfcl/',
              'legacy_ce': 'github/com/gengyoubo/CE/'}
HIDDEN_SUFFIX = re.compile(r'\$\$Lambda\$(\d+)\+0x[0-9a-f]+/\d+')
MACHINE_CLASS = re.compile(r'/(TileEntity(?!Mekanism\.|Multiblock\.|ConfigurableMachine\.|Transmitter\.'
                           r'|StructuralMultiblock\.|Updateable\.)\w+|\w+MultiblockData)\.')


def find(frames, text):
    return next((i for i, frame in enumerate(frames) if text in frame), None)


def percent(count, total):
    return 100 * count / total if total else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path)
    parser.add_argument('--start', help='ISO 8601 with UTC offset')
    parser.add_argument('--end', help='ISO 8601 with UTC offset')
    args = parser.parse_args()
    start = datetime.fromisoformat(args.start) if args.start else None
    end = datetime.fromisoformat(args.end) if args.end else None
    if any(x is not None and x.tzinfo is None for x in (start, end)) or start and end and start >= end:
        parser.error('timestamps must have offsets and be ordered')
    total = truncated = 0
    first = last = None
    phases, phase_namespace, namespaces, ticker_callees = Counter(), defaultdict(Counter), Counter(), Counter()
    leaves, tick_callees, tick_sites = Counter(), Counter(), Counter()
    lambdas, lambda_sites, lambda_classes = Counter(), defaultdict(Counter), defaultdict(Counter)
    bins = defaultdict(Counter)
    for event in iter_events(args.recording, 'jdk.ExecutionSample'):
        value = event['values']
        stamp = datetime.fromisoformat(value['startTime'])
        if start and stamp < start or end and stamp >= end:
            continue
        thread, stack = value.get('sampledThread'), value.get('stackTrace')
        if not thread or thread.get('javaName') != 'Server thread' or not stack or not stack['frames']:
            continue
        frames = frame_names(stack)
        total += 1
        truncated += bool(stack.get('truncated'))
        leaves[frames[0]] += 1
        first = min(first, stamp) if first else stamp
        last = max(last, stamp) if last else stamp
        period = stamp.replace(second=(stamp.second // 10) * 10, microsecond=0).isoformat()
        bins[period]['server_samples'] += 1
        phase = next((name for name, marker in PHASES if find(frames, marker) is not None), 'outside_tick')
        phases[phase] += 1
        bins[period][phase] += 1
        for name, prefix in NAMESPACES.items():
            if any(prefix in frame for frame in frames):
                namespaces[name] += 1
                phase_namespace[name][phase] += 1
        ticker = find(frames, BLOCK_ENTITY_TICK)
        if ticker is not None:
            callee = frames[ticker - 1] if ticker else '<self>'
            ticker_callees[HIDDEN_SUFFIX.sub('$$Lambda', callee)] += 1
        tick = find(frames, MEKANISM_TICK)
        if tick is None:
            continue
        bci = stack['frames'][tick].get('bytecodeIndex')
        site = f'{"leaf" if tick == 0 else "call"}@{bci}'
        tick_sites[site] += 1
        tick_callees[HIDDEN_SUFFIX.sub('$$Lambda', frames[tick - 1]) if tick else '<leaf>'] += 1
        caller = frames[tick + 1] if tick + 1 < len(frames) else ''
        matched = HIDDEN_SUFFIX.search(caller)
        if matched:
            # Each registered type has its own ticker lambda, so this stands in for the machine type.
            key = caller[:matched.start()].rsplit('/', 1)[-1] + '#' + matched.group(1)
            lambdas[key] += 1
            lambda_sites[key][site] += 1
            lambda_classes[key].update(m.group(1) for frame in frames[:tick] for m in [MACHINE_CLASS.search(frame)] if m)
    print(json.dumps({
        'first_server_sample': first.isoformat() if first else None,
        'last_server_sample': last.isoformat() if last else None,
        'server_samples': total, 'truncated_server_stacks': truncated,
        'note': 'Java CPU execution samples, not Tick wall time. Phases are exclusive; namespaces and callees '
                'are inclusive and overlap. Without -XX:+DebugNonSafepoints, leaf attribution inside JIT-compiled '
                'code snaps to the nearest debug record, so a method can absorb samples of code inlined into it.',
        'exclusive_phases': {k: {'samples': v, 'percent': percent(v, total)} for k, v in phases.most_common()},
        'namespace_presence': {k: {'samples': v, 'percent': percent(v, total), 'by_phase': phase_namespace[k].most_common()}
                               for k, v in namespaces.most_common()},
        'block_entity_tick_samples': sum(ticker_callees.values()),
        'block_entity_tick_direct_callees': ticker_callees.most_common(30),
        'mekanism_tick_server': {
            'samples': sum(tick_sites.values()), 'percent': percent(sum(tick_sites.values()), total),
            'sites': tick_sites.most_common(), 'direct_callees': tick_callees.most_common(30),
            'by_ticker_lambda': [{'lambda': k, 'samples': v, 'sites': lambda_sites[k].most_common(6),
                                  'classes_seen_below': lambda_classes[k].most_common(4)}
                                 for k, v in lambdas.most_common(25)]},
        'top_leaf_methods': leaves.most_common(25),
        'ten_second_bins': dict(sorted(bins.items())),
    }, indent=2))


if __name__ == '__main__':
    main()
