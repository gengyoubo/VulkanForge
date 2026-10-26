"""Run after gradlew.bat offscreenBenchmarkArgs. Sequential GPU runs with NVIDIA telemetry."""
import argparse
import csv
import datetime as dt
import json
import os
from pathlib import Path
import re
import statistics as stats
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / 'bin/java.exe'))
    parser.add_argument('--seconds', type=float, default=6)
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--warmup', type=int, default=2000)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    out = root / 'build/reports/offscreen-performance'
    out.mkdir(parents=True, exist_ok=True)
    telemetry_file = out / 'nvidia-telemetry.csv'
    fields = 'timestamp,index,name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw,clocks.gr'
    records = []
    # This is our telemetry child only. No other NVIDIA/user processes are stopped.
    with telemetry_file.open('w', encoding='utf-8') as sink:
        monitor = subprocess.Popen(['nvidia-smi', f'--query-gpu={fields}', '--format=csv,noheader,nounits', '--loop-ms=200'], stdout=sink, stderr=subprocess.STDOUT)
        try:
            time.sleep(2)
            for api in ['VK1.2', 'GL4.6', 'VK1.3', 'GL4.3', 'VK1.4', 'GL4.5', 'GL4.4']:
                print('RUNNING ' + api, flush=True)
                is_vk = api.startswith('VK')
                api_dir = out / (('vk-' if is_vk else 'gl-') + api[2:])
                api_dir.mkdir(exist_ok=True)
                runtime = root / 'vulkan-lab/build/trace/bench.args' if is_vk else out / 'gl.args'
                command = [args.java, '@' + str(runtime), '-Dbench.width=1920', '-Dbench.height=1080', '-Dbench.radius=12',
                           '-Dbench.frames=3000', f'-Dbench.warmup={args.warmup}', f'-Dbench.runs={args.runs}', f'-Dbench.seconds={args.seconds}']
                if is_vk:
                    command += ['-Dvulkanforge.vulkanVersion=' + api[2:], '-Dbench.output=' + str(api_dir), 'github.com.gengyoubo.vulkanforge.lab.OffscreenBenchmark']
                else:
                    command += ['github.com.gengyoubo.vulkanforge.client.GlOffscreenBenchmark', api[2:], str(out / 'scene.bin')]
                with (api_dir / 'stdout.txt').open('w', encoding='utf-8') as log:
                    process = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=180)
                text = (api_dir / 'stdout.txt').read_text(encoding='utf-8')
                if process.returncode:
                    raise RuntimeError(f'{api} failed: {text[-4000:]}')
                rows = []
                for line in text.splitlines():
                    if not line.startswith('BENCH_RESULT '):
                        continue
                    row = dict(re.findall(r'(\w+)=([^\s]+)', line))
                    for key in ['run', 'width', 'height', 'frames', 'START_MS', 'END_MS']:
                        row[key] = int(row[key])
                    for key in ['seconds', 'FPS', 'GPU_MS']:
                        row[key] = float(row[key])
                    rows.append(row)
                    print(line, flush=True)
                if len(rows) != args.runs:
                    raise AssertionError(f'{api}: expected {args.runs} completed runs, got {len(rows)}')
                records.extend(rows)
                (out / 'runs.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
        finally:
            monitor.terminate()
            monitor.wait(timeout=10)
    telemetry = []
    for row in csv.reader(telemetry_file.read_text(encoding='utf-8').splitlines()):
        try:
            timestamp = dt.datetime.strptime(row[0].strip(), '%Y/%m/%d %H:%M:%S.%f').timestamp() * 1000
            telemetry.append(dict(time=timestamp, name=row[2].strip(), gpu=float(row[3]), memory=float(row[4]), total=float(row[5]), temperature=float(row[6]), power=float(row[7]), clock=float(row[8])))
        except (ValueError, IndexError):
            pass
    summary = []
    for api in ['VK1.2', 'VK1.3', 'VK1.4', 'GL4.3', 'GL4.4', 'GL4.5', 'GL4.6']:
        rows = [row for row in records if row['API'] == api]
        samples = [sample for sample in telemetry if any(row['START_MS'] + 1000 <= sample['time'] <= row['END_MS'] for row in rows)]
        if not samples:
            raise AssertionError('No in-window NVIDIA telemetry for ' + api)
        summary.append(dict(api=api, fps_median=stats.median(row['FPS'] for row in rows), fps_min=min(row['FPS'] for row in rows), fps_max=max(row['FPS'] for row in rows),
                            gpu_ms_median=stats.median(row['GPU_MS'] for row in rows), gpu_percent_mean=stats.mean(s['gpu'] for s in samples), gpu_percent_peak=max(s['gpu'] for s in samples),
                            vram_mib_peak=max(s['memory'] for s in samples), temperature_c_peak=max(s['temperature'] for s in samples), power_w_mean=stats.mean(s['power'] for s in samples),
                            gpu_clock_mhz_mean=stats.mean(s['clock'] for s in samples), telemetry_samples=len(samples)))
    (out / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    scene = (out / 'scene.txt').read_text(encoding='utf-8').strip()
    lines = ['# 离屏性能实测', '', f'GPU: {telemetry[0]["name"]}，显存 {telemetry[0]["total"]:.0f} MiB。',
             f'1920×1080，固定地形与水面，radius=12（25×25 区块列）；{scene}。',
             f'每版本预热 {args.warmup} 帧，{args.runs} 轮，每轮至少 {args.seconds:g} 秒 / 3000 帧；表中 FPS 和 GPU 时间为轮次中位数。', '',
             '| API | 离屏 FPS | 三轮范围 | GPU 时间 ms/帧 | GPU 占用均值 | GPU 占用峰值 | 整卡显存峰值 MiB |',
             '| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    for row in summary:
        lines.append(f'| {row["api"]} | {row["fps_median"]:.0f} | {row["fps_min"]:.0f}–{row["fps_max"]:.0f} | {row["gpu_ms_median"]:.3f} | {row["gpu_percent_mean"]:.1f}% | {row["gpu_percent_peak"]:.0f}% | {row["vram_mib_peak"]:.0f} |')
    lines += ['', '无可见窗口、Surface、Swapchain 或屏幕呈现；测量区间前后等待 GPU 完成，排除启动、加载、预热和像素读回。',
              'GPU 占用来自 nvidia-smi 每 200 ms 的整卡采样，排除每轮最初 1 秒；显存是整卡占用，包含桌面及其他应用。',
              'GL GPU 时间来自逐帧 GL_TIME_ELAPSED 查询；Vulkan 时间为现有独立通道时间戳合计。时间区间可能包含等待，不能用其推算硬件占用。',
              '各版本采用生产后端：GL 使用原始四边形、单 FBO；Vulkan 使用三角形地形、独立透明目标、深度复制及 GL/VK 共享信号量往返。因此这是当前实现的吞吐量对比。',
              '固定相机；GL Hi-Z 与原版遮挡图关闭，以匹配 Vulkan 视锥剔除；校验层和 GL debug 输出关闭。',
              '这里没有 Minecraft 游戏逻辑、实体、HUD、区块生成和显示链路，离屏 FPS 不等于实际游戏 FPS。', '',
              '复现：`gradlew.bat offscreenBenchmarkArgs`，然后 `python tools/benchmark-offscreen-versions.py --java <JDK17/bin/java.exe>`。']
    (out / 'summary.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print('\n'.join(lines), flush=True)


if __name__ == '__main__':
    main()
