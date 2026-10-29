"""Summarize real-game VK counters and sampled CPU/GPU intervals (standard library only)."""
import argparse
import csv
import json
from pathlib import Path
import statistics


def analyze(folder):
    rows = json.loads((folder / 'results.json').read_text(encoding='utf-8'))
    summaries = []
    for row in rows:
        frames = row['frames']
        counters = row.get('nativeDiagnostics', {})
        summary = {key: row[key] for key in ['round', 'frames', 'fps', 'frameP50Ms', 'frameP99Ms']}
        summary['allocatedBytesPerFrame'] = row.get('renderThreadAllocatedBytes', 0) / frames
        summary['renderThreadCpuMsPerFrame'] = row.get('renderThreadCpuNs', 0) / frames / 1e6
        summary['msPerFrame'] = {key: value / frames / 1e6 for key, value in counters.items() if key.endswith('Ns')}
        summary['countsPerFrame'] = {key: counters.get(key, 0) / frames for key in [
            'draws', 'terrainDraws', 'sceneSubmits', 'presentSubmits', 'frameSplits',
            'splitResidentBudget', 'splitDrawLimit', 'splitVertexLimit', 'residentUnreclaimable']}
        summaries.append(summary)
    for source in sorted(folder.glob('timeline-*.csv')):
        if not source.stem.removeprefix('timeline-').isdigit():
            continue
        with source.open(encoding='utf-8', newline='') as stream:
            samples = [{key: int(value) for key, value in row.items()} for row in csv.DictReader(stream)]
        intervals = []
        for before, after in zip(samples, samples[1:]):
            frames = after['sampleFrame'] - before['sampleFrame']
            if frames <= 0:
                continue
            interval = dict(startFrame=before['sampleFrame'], endFrame=after['sampleFrame'],
                            startEpochMs=before['epochMs'], endEpochMs=after['epochMs'])
            for key in after:
                if key.endswith('Ns'):
                    interval[key + 'PerFrameMs'] = (after[key] - before.get(key, 0)) / frames / 1e6
            for key in ['frameSplits', 'splitResidentBudget', 'splitDrawLimit', 'splitVertexLimit']:
                interval[key] = after.get(key, 0) - before.get(key, 0)
            intervals.append(interval)
        if intervals:
            with source.with_name(source.stem + '-intervals.csv').open('w', encoding='utf-8', newline='') as stream:
                writer = csv.DictWriter(stream, fieldnames=list(intervals[0]))
                writer.writeheader()
                writer.writerows(intervals)
    result = dict(folder=str(folder), medianFps=statistics.median(r['fps'] for r in rows), rounds=summaries,
                  notes=['Profiling overhead is included; these are diagnostic runs, not paired FPS comparisons.',
                         'Inclusive CPU durations overlap. GPU counters are collected later at existing fences.',
                         'Memory gauges and framesInFlight must be read as absolute values from backend.txt, not interval deltas.'])
    (folder / 'profile-summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folders', nargs='+', type=Path)
    args = parser.parse_args()
    for folder in args.folders:
        print(json.dumps(analyze(folder), ensure_ascii=False, indent=2))
