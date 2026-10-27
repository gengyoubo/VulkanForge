"""Paired GL-VK input scheduling: flush real GL production before Vulkan CPU recording."""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import random
import statistics as stats
import subprocess
from pathlib import Path

import numpy as np
from gpu_benchmark_guard import Guard

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('three_modes', ROOT / 'tools/benchmark-three-modes.py')
three = importlib.util.module_from_spec(spec)
spec.loader.exec_module(three)
common = three.common
CASES = ['BASELINE', 'EARLY_GL_FLUSH']


def run(args, case, phase, folder, guard, preflight=False, version='1.4'):
    enabled = case == 'EARLY_GL_FLUSH'
    row = three.run(args.java, 'GLVK_INTERVAL', phase, folder, guard, args, preflight, version,
                    jvm_properties={'vulkanforge.earlyGlInputFlush': str(enabled).lower()})
    row.update(case=case, execution_api='GLVK_INTERVAL')
    return row


def validate(folder, reference, exact_reference, expected, case, phase):
    result = three.validate(folder, reference, expected, 'GLVK_INTERVAL', phase)
    audit = common.props(folder / 'pipeline-audit.txt')
    assert float(audit['COUNTER_18_PER_FRAME']) == int(case == 'EARLY_GL_FLUSH'), audit
    # Extra flush is tracked separately. Existing signal/wait/flush and submission counts stay fixed.
    groups = 2 if phase == 'TERRAIN_POST3' else 1
    for index in [12, 13, 14]:
        assert float(audit[f'COUNTER_{index}_PER_FRAME']) == groups, (index, audit)
    for name in ['scene', 'final', 'after-final']:
        color, depth = common.pixels(folder, name)
        ref_color, ref_depth = common.pixels(exact_reference, name)
        assert np.array_equal(color, ref_color) and np.array_equal(depth, ref_depth), (case, phase, name)
    profile = common.props(folder / 'phase-profile.txt')
    for name, count in [('VK_OPAQUE_MS', 1), ('VK_TRANSLUCENT_MS', 1), ('VK_CULL_MS', 1),
                        ('VK_DEPTH_COPY_MS', 1), ('VK_COMPOSITE_MS', 2),
                        ('VK_POST_MS', three.PHASES[phase])]:
        assert float(profile[name + '_SAMPLES_PER_FRAME']) == count, (name, profile)
    return result


def summarize(out, runs):
    lines = ['# GL 输入生产提前 Flush 实验', '',
             '同一 GLVK_INTERVAL 执行器、1920×1080、RGBA8/D32F、两帧槽。唯一变化是在既有 GL 颜色/深度清除后、Vulkan 地形 CPU 录制前增加一次 glFlush。',
             '不提前 semaphore signal，不改变租约、图像、绘制或所有权依赖；没有新增 Host Wait、探针提交或模拟 GL 工作。该实验不能代表提前 signal 的效果。',
             '每轮随机顺序、独立 JVM；FPS 包含最终排空。所有 RGBA/D32 在计时前后逐位匹配同轮基准；GL46 的既有误差另存 conformance.json。',
             'GPU ms 是同一 GL 时钟的完整跨度，可能随 flush 的时序变化；不将它解释为 GPU 活跃时间。以端到端 FPS 和 CPU P99 评估效果。', '']
    summary = []
    for phase in dict.fromkeys(row['phase'] for row in runs):
        lines += ['## ' + phase, '',
                  '| 时序 | FPS 中位数 | FPS 范围 | GL GPU span ms | CPU P99 ms | CPU Record ms | CPU Submit ms | 额外 Flush µs |',
                  '|---|---:|---:|---:|---:|---:|---:|---:|']
        for case in CASES:
            rows = [row for row in runs if row['phase'] == phase and row['case'] == case]
            folders = [out / f'round-{int(row["round"]):02d}' / phase / case for row in rows]
            profiles = [common.props(folder / 'phase-profile.txt') for folder in folders]
            audits = [common.props(folder / 'pipeline-audit.txt') for folder in folders]
            med = lambda key: stats.median(row[key] for row in rows)
            item = dict(phase=phase, case=case, rounds=len(rows), fps=med('FPS'),
                        fps_min=min(row['FPS'] for row in rows), fps_max=max(row['FPS'] for row in rows),
                        gpu_ms=med('GPU_MS'), cpu_p99_ms=med('CPU_P99_MS'),
                        cpu_max_ms=max(row['CPU_MAX_MS'] for row in rows),
                        cpu_record_ms=stats.median(float(p['CPU_RECORD_MS']) for p in profiles),
                        cpu_submit_ms=stats.median(float(p['CPU_QUEUE_SUBMIT_MS']) for p in profiles),
                        extra_flush_us=stats.median(float(a['COUNTER_19_PER_FRAME']) / 1000 for a in audits))
            summary.append(item)
            lines.append(f'| {case} | {item["fps"]:.3f} | {item["fps_min"]:.3f}–{item["fps_max"]:.3f} | '
                         f'{item["gpu_ms"]:.6f} | {item["cpu_p99_ms"]:.6f} | {item["cpu_record_ms"]:.6f} | '
                         f'{item["cpu_submit_ms"]:.6f} | {item["extra_flush_us"]:.3f} |')
        paired = []
        for before in [row for row in runs if row['phase'] == phase and row['case'] == 'BASELINE']:
            after = next(row for row in runs if row['phase'] == phase and row['case'] == 'EARLY_GL_FLUSH'
                         and row['round'] == before['round'])
            paired.append(dict(round=before['round'], fps_percent=(after['FPS'] / before['FPS'] - 1) * 100,
                               cpu_p99_ms_delta=after['CPU_P99_MS'] - before['CPU_P99_MS'],
                               gpu_ms_delta=after['GPU_MS'] - before['GPU_MS']))
        lines += ['', '| 轮次 | 配对 FPS 变化 | CPU P99 变化 ms | GPU span 变化 ms |', '|---|---:|---:|---:|']
        for pair in paired:
            lines.append(f'| {pair["round"]} | {pair["fps_percent"]:+.2f}% | {pair["cpu_p99_ms_delta"]:+.6f} | {pair["gpu_ms_delta"]:+.6f} |')
        lines += ['', f'同轮 FPS 变化中位数 {stats.median(p["fps_percent"] for p in paired):+.2f}%。', '']
    lines += ['COUNTER_0..17 沿用区间实验；COUNTER_18 是额外输入 flush 次数，COUNTER_19 是其 CPU 纳秒数，未计入 semaphore 组计数。',
              '没有窗口呈现、实体或模组中途 GL 回退。计时关闭 validation；像素和计数验证不等于通过同步验证层。默认关闭此实验，不因单轮或 GPU span 缩短而自动启用。']
    (out / 'summary.json').write_text(json.dumps(summary, indent=2))
    (out / 'summary.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', required=True)
    parser.add_argument('--rounds', type=int, default=5)
    parser.add_argument('--seconds', type=float, default=8)
    parser.add_argument('--warmup', type=int, default=5000)
    parser.add_argument('--post-only', action='store_true')
    parser.add_argument('--preflight-only', action='store_true')
    parser.add_argument('--output', default='build/reports/input-scheduling')
    args = parser.parse_args()
    if args.rounds < 1 or args.seconds <= 0 or args.warmup < 1:
        parser.error('rounds, seconds and warmup must be positive')
    out = (ROOT / args.output).resolve()
    assert out.is_relative_to(ROOT)
    if out.exists() and any(out.iterdir()):
        raise RuntimeError('Choose a fresh output directory')
    out.mkdir(parents=True, exist_ok=True)
    common.archive(out)
    manifest = json.loads((out / 'kernel-manifest.json').read_text())
    for source in [Path(__file__).resolve(), ROOT / 'tools/benchmark-three-modes.py', ROOT / 'build.gradle']:
        relative = source.relative_to(ROOT)
        target = out / 'runtime-snapshot' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
        manifest['sha256'][str(relative)] = hashlib.sha256(source.read_bytes()).hexdigest()
    manifest['started_at'] = dt.datetime.now(dt.timezone.utc).isoformat()
    manifest['arguments'] = vars(args)
    (out / 'kernel-manifest.json').write_text(json.dumps(manifest, indent=2))
    scene = ROOT / 'build/reports/offscreen-performance/matched-scene.bin'
    expected = scene.with_name('expected-workload.txt').read_text()
    (out / 'expected-workload.txt').write_text(expected)
    (out / 'scene-sha256.txt').write_text(hashlib.sha256(scene.read_bytes()).hexdigest())
    phases = ['TERRAIN_POST3'] if args.post_only else list(three.PHASES)
    guard = Guard()
    runs = []
    rng = random.Random(20261027)
    with (out / 'telemetry.csv').open('w') as log:
        monitor = None
        try:
            monitor = subprocess.Popen(['nvidia-smi', '--query-gpu=timestamp,index,utilization.gpu,memory.used,temperature.gpu,clocks.gr',
                                        '--format=csv,noheader,nounits', '--loop-ms=200'], stdout=log, stderr=subprocess.STDOUT)
            for phase in phases:
                reference = out / 'preflight' / phase / 'GL46_NATIVE'
                three.run(args.java, 'GL46_NATIVE', phase, reference, guard, args, True)
                three.validate(reference, reference, expected, 'GL46_NATIVE', phase)
                baseline = out / 'preflight' / phase / 'BASELINE'
                for case in CASES:
                    folder = out / 'preflight' / phase / case
                    run(args, case, phase, folder, guard, True)
                    validate(folder, reference, baseline, expected, case, phase)
                    print('PREFLIGHT PASS ' + phase + ' ' + case, flush=True)
                for version in ['1.2', '1.3']:
                    folder = out / 'api-preflight' / version / phase
                    run(args, 'EARLY_GL_FLUSH', phase, folder, guard, True, version)
                    validate(folder, reference, baseline, expected, 'EARLY_GL_FLUSH', phase)
                    print('API PREFLIGHT PASS ' + version + ' ' + phase, flush=True)
            (out / 'preflight-pass.txt').write_text('PASS actual workload, full RGBA/D32 bit-exact, unchanged submits/semaphores, exactly one extra flush, VK1.2/1.3/1.4\n')
            if args.preflight_only:
                return
            schedule = []
            for n in range(1, args.rounds + 1):
                jobs = [(phase, case) for phase in phases for case in CASES]
                rng.shuffle(jobs)
                schedule.append(dict(round=n, jobs=jobs))
                (out / 'schedule.json').write_text(json.dumps(schedule, indent=2))
                for phase, case in jobs:
                    folder = out / f'round-{n:02d}' / phase / case
                    row = run(args, case, phase, folder, guard)
                    validate(folder, out / 'preflight' / phase / 'GL46_NATIVE',
                             out / 'preflight' / phase / 'BASELINE', expected, case, phase)
                    row['round'] = n
                    runs.append(row)
                    (out / 'runs.json').write_text(json.dumps(runs, indent=2))
                    print(f'PASS {n} {phase} {case} FPS={row["FPS"]}', flush=True)
        finally:
            if monitor is not None:
                monitor.terminate()
                monitor.wait(timeout=10)
            guard.close()
            (out / 'other-java-benchmarks.json').write_text(json.dumps(guard.events, indent=2))
    summarize(out, runs)


if __name__ == '__main__':
    main()
