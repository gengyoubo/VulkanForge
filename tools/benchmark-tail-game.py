"""Actual Forge GL-tail A/B/C/D ablations with CPU stages and asynchronous GL timestamps."""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import statistics as st
import subprocess
import time

from gpu_benchmark_guard import Guard

ROOT = Path(__file__).resolve().parents[1]


def summarize(out):
    runs = json.loads((out / 'runs.json').read_text())
    assert (out / 'status.txt').read_text().strip() == 'OK'
    lines = ['# 真实 Forge GL Tail 消融', '',
             '固定平坦测试世界、相机、1920×1080 无边框、视距 8、Fancy、云关闭、HUD/手持物开启。VSync 关闭，260 为 Minecraft 不执行帧率限制的选项。不是用户原有约 250 FPS 场景的重放。',
             '同一 JVM 内按报告包含的配置分轮测试，多配置时随机顺序，每例先预热再计时；正式测量不读回图像、不排空 GPU。完整 CPU 原始帧与可用 GPU 查询保存在每例 frames.csv。',
             'A 正常；B 仅省略 VK 不透明/透明 Draw，保留剔除、清除、深度复制、合成与交接，是改变画面/负载的诊断下界；C 仅允许跳过 GameRenderer 中原版 desaturate 可选效果；D 使用普通原版 GL 地形，VK 后端仍初始化但不提交地形，不等同于没有 VK 设备的严格纯 GL 启动。',
             'BASE_GAME 没有可选后处理，C 应跳过零次；OPTIONAL_POST 显式加载原版两 Pass 去饱和链，C 测的是这条受控效果的省略，不能称为所有实际后处理的收益。Fabulous 等必要链不在跳过范围。',
             'CPU 区间为调用墙钟时间，可能含驱动或 GPU 等待；GL span 是单个 GL 时钟的时间戳跨度，可能含 CPU 供给、外部等待与调度间隙。VK GPU 段来自自己的时间戳。不跨 API 相减，嵌套区间不相加。', '']
    lines += ['新增 GL GPU 查询：' + ('关闭；n/a 表示没有采样，Vulkan 原有时间戳仍保留。' if all(r.get('gpu_capture_enabled', 1) == 0 for r in runs) else '开启；以实际完成样本数为准。'), '']
    summaries = []
    for phase in dict.fromkeys(r['phase'] for r in runs):
        cases = sorted({r['case'] for r in runs if r['phase'] == phase})
        lines += ['## ' + phase, '',
                  '| 例 | FPS 中位数 | 同轮相对 A | 帧周期 CPU P99 ms | GameRenderer CPU 均值 ms | Swap CPU 均值 ms | GL render→swap span ms |',
                  '|---|---:|---:|---:|---:|---:|---:|']
        for case in cases:
            rows = [r for r in runs if r['phase'] == phase and r['case'] == case]
            assert rows
            median = lambda key: st.median(r[key] for r in rows)
            paired = [(r['fps'] / next(a['fps'] for a in runs if a['phase'] == phase and a['case'] == 'A' and a['round'] == r['round']) - 1) * 100 for r in rows]
            item = dict(phase=phase, case=case, rounds=len(rows), fps=median('fps'), paired_fps_percent=st.median(paired),
                        fps_min=min(r['fps'] for r in rows), fps_max=max(r['fps'] for r in rows))
            summaries.append(item)
            gpu = f'{median("gl_span_render_to_swap_mean_ms"):.4f}' if 'gl_span_render_to_swap_mean_ms' in rows[0] else 'n/a'
            lines.append(f'| {case} | {item["fps"]:.2f} | {st.median(paired):+.2f}% | {median("cpu_frame_period_p99_ms"):.4f} | '
                         f'{median("cpu_game_render_mean_ms"):.4f} | {median("cpu_swap_buffers_mean_ms"):.4f} | {gpu} |')
        lines += ['', '| 例 | SharedRender CPU ms | VK fence CPU ms/帧 | VK record CPU ms/帧 | QueueSubmit CPU ms/帧 | GL 合成 GPU span ms | GL Post GPU span ms | VK opaque GPU ms | VK translucent GPU ms |',
                  '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
        for case in cases:
            rows = [r for r in runs if r['phase'] == phase and r['case'] == case]
            med = lambda key: st.median(r.get(key, 0) for r in rows)
            composite = st.median(r.get('gl_span_gl_opaque_composite_mean_ms',float('nan')) + r.get('gl_span_gl_translucent_composite_mean_ms',float('nan')) for r in rows)
            composite_text = f'{composite:.4f}' if math.isfinite(composite) else 'n/a'
            post_text = f'{med("gl_span_gl_post_chain_mean_ms"):.4f}' if 'gl_span_gl_post_chain_mean_ms' in rows[0] else 'n/a'
            lines.append(f'| {case} | {med("cpu_shared_render_mean_ms"):.4f} | {med("cpu_vk_fence_ms_per_frame"):.4f} | {med("cpu_vk_record_ms_per_frame"):.4f} | '
                         f'{med("cpu_vk_queue_submit_ms_per_frame"):.4f} | {composite_text} | {post_text} | '
                         f'{med("vk_opaque_gpu_mean_ms"):.4f} | {med("vk_translucent_gpu_mean_ms"):.4f} |')
        lines += ['', '| 轮 | ' + ' | '.join(case + ' FPS' for case in cases) + ' |', '|---|' + '---:|' * len(cases)]
        for round_number in sorted({r['round'] for r in runs if r['phase'] == phase}):
            values = [next(r['fps'] for r in runs if r['phase'] == phase and r['round'] == round_number and r['case'] == case) for case in cases]
            lines.append('| ' + str(round_number) + ' | ' + ' | '.join(f'{value:.2f}' for value in values) + ' |')
        lines.append('')
    lines += ['本报告包含配置：' + ', '.join(sorted({r['case'] for r in runs})) + '。所包含配置按定义逐例断言：A/B/C 每个测量帧一次地形提交、两次 GL 组调用；B 地形 Section Draw 为零；D 地形提交为零且原版 GL 地形 Draw 非零。上传批次另存，不计作地形提交。',
              'GPU 查询没有等待最后几个未完成槽，CSV 中以 NaN 明示不可用，summary 保存完成样本数和丢弃数。VK 快照只轮询已完成 fence，边界可能包含少量同配置预热帧；不将它当作逐帧严格关联的硬件轨迹。',
              '截图只作诊断，没有把 B/C 画面不同称为等价验证。逐例保留相机、目标尺寸、后处理执行/跳过次数、实际 Draw/交接/上传计数、最大 CPU 时间；不剔除慢帧。验证层状态见 backend.txt，计时关闭 validation。']
    (out / 'summary.json').write_text(json.dumps(summaries, indent=2))
    (out / 'summary.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home')
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--seconds', type=float, default=5)
    parser.add_argument('--warmup', type=float, default=3)
    parser.add_argument('--output', default='build/reports/tail-game-ablation')
    parser.add_argument('--summarize-only', action='store_true')
    parser.add_argument('--cpu-only', action='store_true')
    parser.add_argument('--cases', default='A,B,C,D')
    parser.add_argument('--phases', default='BASE_GAME,OPTIONAL_POST')
    parser.add_argument('--from-config', action='store_true')
    args = parser.parse_args()
    out = (ROOT / args.output).resolve()
    assert out.is_relative_to(ROOT)
    if args.summarize_only:
        summarize(out)
        return
    if not args.java_home:
        parser.error('--java-home is required for a run')
    if out.exists() and any(out.iterdir()):
        raise RuntimeError('Choose a fresh output directory')
    out.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env['JAVA_HOME'] = args.java_home
    path_key = next(k for k in env if k.lower() == 'path')
    env[path_key] = str(Path(args.java_home) / 'bin') + os.pathsep + env[path_key]
    gradle = ['cmd.exe', '/d', '/c', str(ROOT / 'gradlew.bat'), '--no-parallel']
    with (out / 'compile.log').open('w') as log:
        subprocess.run(gradle + ['compileSmokeJava', 'smokeTailDiagnosticsTransform'], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    spec = importlib.util.spec_from_file_location('archive_kernel', ROOT / 'tools/benchmark-interop-optimized.py')
    common = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(common)
    common.archive(out)
    manifest = json.loads((out / 'kernel-manifest.json').read_text())
    for source in [Path(__file__).resolve(), ROOT / 'build.gradle', ROOT / 'src/main/resources/META-INF/coremods.json']:
        relative = source.relative_to(ROOT)
        target = out / 'runtime-snapshot' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
        manifest['sha256'][str(relative)] = hashlib.sha256(source.read_bytes()).hexdigest()
    manifest['arguments'] = vars(args)
    manifest['started_at'] = dt.datetime.now(dt.timezone.utc).isoformat()
    (out / 'kernel-manifest.json').write_text(json.dumps(manifest, indent=2))
    command = gradle + ['-PtailAblation', f'-PtailBench.rounds={args.rounds}', f'-PtailBench.seconds={args.seconds}',
                        f'-PtailBench.cases={args.cases}', f'-PtailBench.cpuOnly={str(args.cpu_only).lower()}',
                        f'-PtailBench.phases={args.phases}',
                        f'-PtailBench.warmup={args.warmup}', f'-PtailBench.output={out}', 'runClient']
    if args.from_config: command.insert(-1, '-PglTailFromConfig')
    (out / 'command.json').write_text(json.dumps(command, indent=2))
    guard = Guard()
    monitor = child = None
    try:
        guard.idle()
        with (out / 'telemetry.csv').open('w') as telemetry, (out / 'game.log').open('w') as log:
            monitor = subprocess.Popen(['nvidia-smi', '--query-gpu=timestamp,index,utilization.gpu,memory.used,temperature.gpu,clocks.gr',
                                        '--format=csv,noheader,nounits', '--loop-ms=200'], stdout=telemetry, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW)
            child = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            started = time.monotonic()
            while child.poll() is None:
                if (out / 'pid.txt').exists():
                    guard.own = int((out / 'pid.txt').read_text())
                if time.monotonic() - started > 1000:
                    raise TimeoutError('Game ablation exceeded its timeout')
                time.sleep(.5)
            if child.returncode:
                raise RuntimeError('Game ablation failed; see ' + str(out / 'game.log'))
        rows = json.loads((out / 'runs.json').read_text())
        collisions = [event for event in guard.events if event['pid'] != guard.own and any(row['start_ms'] <= event['time'] <= row['end_ms'] for row in rows)]
        (out / 'concurrent-measurements.json').write_text(json.dumps(collisions, indent=2))
        if collisions:
            raise RuntimeError('Concurrent Java GPU workload detected; retain this run and retry in a fresh directory')
        summarize(out)
        print((out / 'summary.md').read_text(encoding='utf-8'), flush=True)
    finally:
        if child is not None and child.poll() is None:
            child.terminate()
            child.wait(timeout=30)
        if monitor is not None:
            monitor.terminate()
            monitor.wait(timeout=10)
        guard.close()
        (out / 'other-java-benchmarks.json').write_text(json.dumps(guard.events, indent=2))


if __name__ == '__main__':
    main()
