"""Find indicative transfer/capability stack samples in specific mod namespaces."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path

from jfr_events import frame_names, iter_events

MODS = {'mekanism': 'mekanism/', 'ae2': 'appeng/', 'create': 'com/simibubi/create/'}
TRANSFERS = ('.tickEmit', '.insertItem', '.extractItem', '.insertEnergy', '.extractEnergy',
             '.insertChemical', '.extractChemical', '.fill', '.drain')
CAPABILITIES = ('.getCapability', '/CapabilityUtils.', '/LazyOptional.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path)
    args = parser.parse_args()
    total = 0
    samples, methods, joint = Counter(), defaultdict(Counter), Counter()
    for event in iter_events(args.recording, 'jdk.ExecutionSample'):
        v = event['values']
        if (v.get('sampledThread') or {}).get('javaName') != 'Server thread':
            continue
        stack = v.get('stackTrace')
        if not stack or not stack.get('frames'):
            continue
        total += 1
        frames = frame_names(stack)
        if any(f.startswith('appeng/') for f in frames) and any('EnergyNetwork.tickEmit' in f for f in frames):
            joint.update(set(f for f in frames if f.startswith('appeng/')))
        for mod, prefix in MODS.items():
            if not any(f.startswith(prefix) for f in frames):
                continue
            samples[f'{mod}:all'] += 1
            for kind, terms in (('transfer', TRANSFERS), ('capability', CAPABILITIES)):
                hits = [f for f in frames if any(term in f for term in terms)]
                if hits:
                    samples[f'{mod}:{kind}'] += 1
                    methods[f'{mod}:{kind}'].update(set(hits))
    print(json.dumps({'server_thread_samples': total, 'overlapping_counts': dict(samples),
                      'matching_methods': {k: v.most_common(15) for k, v in methods.items()},
                      'ae2_methods_in_mekanism_energy_emission': joint.most_common(20),
                      'note': 'Heuristic inclusive CPU sample counts. Categories overlap; capability lookups need not be for transfer. Obfuscated/inlined methods and truncated stacks may be missed.'}, indent=2))


if __name__ == '__main__':
    main()
