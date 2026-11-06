"""Optional low-rate NVIDIA telemetry for diagnostic runs, not enabled in FPS-only comparisons."""
import argparse
import csv
from pathlib import Path
import shutil
import subprocess
import time

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--seconds',type=float,default=180)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
binary=shutil.which('nvidia-smi')
if not binary:
    raise SystemExit('nvidia-smi unavailable; GPU clocks were not measured')
fields='timestamp,index,name,clocks.current.graphics,clocks.current.memory,utilization.gpu,temperature.gpu,power.draw,pstate'
args.output.parent.mkdir(parents=True,exist_ok=True)
with args.output.open('w',encoding='utf-8',newline='') as stream:
    writer=csv.writer(stream);writer.writerow(['epoch_ms','query_ns']+fields.split(','))
    deadline=time.monotonic()+args.seconds
    while time.monotonic()<deadline:
        start=time.monotonic_ns();epoch=time.time_ns()//1_000_000
        result=subprocess.run([binary,'--query-gpu='+fields,'--format=csv,noheader,nounits'],capture_output=True,text=True,
                              creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0),timeout=10)
        elapsed=time.monotonic_ns()-start
        if result.returncode:
            raise SystemExit(result.stderr)
        for row in csv.reader(result.stdout.splitlines()):
            writer.writerow([epoch,elapsed]+[cell.strip() for cell in row])
        stream.flush();time.sleep(1)
