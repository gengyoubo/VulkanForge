"""Summarize sampled Mekanism transfer paths in JFR without exporting recording metadata."""
import argparse
from collections import Counter
import json
from pathlib import Path

from jfr_events import frame_names, iter_events


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path)
    args = parser.parse_args()
    prefixes = ('mekanism/common/content/network/', 'mekanism/common/lib/transmitter/',
                'mekanism/common/lib/distribution/', 'mekanism/common/util/EmitUtils.',
                'mekanism/common/tile/transmitter/')
    total = matches = truncated = 0
    methods, leaves, kinds = Counter(), Counter(), Counter()
    for event in iter_events(args.recording, 'jdk.ExecutionSample'):
        value = event['values']
        if (value.get('sampledThread') or {}).get('javaName') != 'Server thread':
            continue
        stack = value.get('stackTrace')
        if not stack or not stack.get('frames'):
            continue
        total += 1
        truncated += bool(stack.get('truncated'))
        frames = frame_names(stack)
        selected = [f for f in frames if f.startswith(prefixes)]
        if not selected:
            continue
        matches += 1
        leaves[frames[0]] += 1
        methods.update(set(selected))
        for key, fragments in {
            'energy': ('EnergyNetwork', 'UniversalCable', 'EnergyAcceptor'),
            'fluid': ('FluidNetwork', 'MechanicalPipe', 'FluidHandlerTarget'),
            'chemical': ('ChemicalNetwork', 'PressurizedTube', 'ChemicalHandlerTarget'),
            'items': ('InventoryNetwork', 'LogisticalTransporter', 'TransporterManager'),
            'capability_mentions': ('getCapability', 'CapabilityCache', 'AcceptorCache', 'LazyOptional'),
        }.items():
            if any(any(part in frame for part in fragments) for frame in frames):
                kinds[key] += 1
    print(json.dumps({'server_thread_samples': total, 'truncated_stacks': truncated,
                      'transfer_path_samples': matches, 'overlapping_category_counts': kinds,
                      'inclusive_methods': methods.most_common(35), 'sampled_leaves': leaves.most_common(20),
                      'note': 'CPU samples, not wall time; categories and method counts overlap. Missing samples do not prove no work.'}, indent=2))


if __name__ == '__main__':
    main()
