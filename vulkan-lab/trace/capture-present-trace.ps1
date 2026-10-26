#Requires -RunAsAdministrator
<#
  Records one WPR GPU timeline (present.wprp) per presentation path, for GPUView / WPA / PresentMon --etl_file:
    vulkan-swapchain.etl   bare Vulkan swapchain, clear + present, IMMEDIATE / 3 images   (PresentBenchmark mode B)
    gl-swap.etl            GL clear + SwapBuffers, swap interval 0                        (mode C)
    vulkanforge-world.etl  VulkanForge path: Vulkan world -> GL -> Vulkan swapchain         (mode A, bench.world)
  Then a second pass per path with PresentMon live (not concurrent with WPR, to keep the two ETW sessions apart):
    <name>-presentmon.csv  per-present mode (composed copy / hardware flip / hybrid cross-adapter), API, GPU, display times
  vulkan-swapchain also writes vulkan-swapchain-timeline.csv for: python analyze-present-trace.py <etl> --timeline <csv>
  Run from an elevated PowerShell. First, in a normal shell: gradlew -p vulkan-lab traceArgs
  Each window takes over the screen for a few seconds. Do not touch the mouse/keyboard while recording.
#>
param(
    [string]$Java = 'C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\java.exe',
    [string[]]$Only = @(),
    [string]$PresentMon = (Join-Path (Split-Path -Parent $PSScriptRoot) 'build\tools\PresentMon-2.6.0-x64.exe'),
    [switch]$SkipPresentMon
)
# Continue: Windows PowerShell turns native stderr (JDK/LWJGL warnings, wpr -cancel) into errors; failures are checked explicitly.
$ErrorActionPreference = 'Continue'
$Only = @($Only | ForEach-Object { $_ -split ',' } | Where-Object { $_ }) # -File passes "a,b" as one string.
$lab = Split-Path -Parent $PSScriptRoot
$argsFile = Join-Path $lab 'build\trace\bench.args'
$out = Join-Path $lab 'build\traces'
if (-not (Test-Path $argsFile)) { throw "Missing $argsFile - run 'gradlew -p vulkan-lab traceArgs' in a normal (non-elevated) shell first." }
if (-not (Test-Path $Java)) { throw "Java 17+ not found at $Java (pass -Java)." }
New-Item -ItemType Directory -Force $out | Out-Null
$wprProfile = (Join-Path $PSScriptRoot 'present.wprp') + '!VulkanForgePresent'
$main = 'github.com.gengyoubo.vulkanforge.lab.PresentBenchmark'

$runs = [ordered]@{
    # The timeline (per-frame acquire/submit/present system times) belongs to the WPR pass; the PresentMon pass drops it.
    'vulkan-swapchain'  = @('-Dbench.mode=swapchain', '-Dvulkanforge.presentMode=immediate', '-Dvulkanforge.swapchainImages=3', '-Dbench.frames=1500',
                            "-Dbench.timeline=$(Join-Path $out 'vulkan-swapchain-timeline.csv')")
    'gl-swap'           = @('-Dbench.mode=gl', '-Dbench.frames=3000')
    'vulkanforge-world' = @('-Dbench.world=true', '-Dvulkanforge.presentMode=immediate', '-Dvulkanforge.swapchainImages=3', '-Dvulkanforge.asyncAcquire=false', '-Dbench.frames=1500')
}
Push-Location $lab
try {
    foreach ($name in $runs.Keys) {
        if ($Only.Count -gt 0 -and $Only -notcontains $name) { continue }
        $etl = Join-Path $out "$name.etl"
        Write-Host "=== $name -> $etl"
        & wpr.exe -cancel 2>$null | Out-Null
        & wpr.exe -start $wprProfile -filemode
        if ($LASTEXITCODE -ne 0) { throw "wpr -start failed ($LASTEXITCODE)" }
        try {
            & $Java "@$argsFile" @($runs[$name]) $main 2>&1 | Where-Object { $_ -match 'BENCH|SWAPCHAIN|GL_SWAP|Exception|Error' } | ForEach-Object { Write-Host $_ }
        } finally {
            & wpr.exe -stop $etl "VulkanForge $name"
            if ($LASTEXITCODE -ne 0) { throw "wpr -stop failed ($LASTEXITCODE)" }
        }
        Write-Host ("    {0:N0} MB" -f ((Get-Item $etl).Length / 1MB))
        # Self-check: without DxgKrnl events the trace has no GPU queues (the first capture recorded none).
        $summary = Join-Path $env:TEMP "vulkanforge-$name-summary.txt"
        & tracerpt.exe $etl -summary $summary -o (Join-Path $env:TEMP "vulkanforge-$name-dump.csv") -of CSV -y 2>&1 | Out-Null
        $dxg = 0; Get-Content $summary | Where-Object { $_ -match 'Microsoft-Windows-DxgKrnl' } | ForEach-Object { if ($_ -match '^\|\s*(\d+)') { $dxg += [int]$Matches[1] } }
        Remove-Item (Join-Path $env:TEMP "vulkanforge-$name-dump.csv") -ErrorAction SilentlyContinue
        if ($dxg -gt 0) { Write-Host ("    DxgKrnl events: {0:N0}" -f $dxg) } else { Write-Host '    WARNING: no DxgKrnl events in this trace' -ForegroundColor Yellow }
    }
    if (-not $SkipPresentMon) {
        if (-not (Test-Path $PresentMon)) { throw "PresentMon not found at $PresentMon (pass -PresentMon or -SkipPresentMon)." }
        foreach ($name in $runs.Keys) {
            if ($Only.Count -gt 0 -and $Only -notcontains $name) { continue }
            $csv = Join-Path $out "$name-presentmon.csv"
            Write-Host "=== $name -> $csv"
            & $PresentMon --terminate_existing_session --session_name VulkanForgePM 2>$null | Out-Null
            $pm = Start-Process -FilePath $PresentMon -PassThru -WindowStyle Hidden -ArgumentList @('--process_name', 'java.exe', '--output_file', "`"$csv`"",
                '--track_hybrid_present', '--write_display_metadata', '--qpc_time_ms', '--no_console_stats', '--stop_existing_session', '--session_name', 'VulkanForgePM')
            Start-Sleep -Seconds 2 # Let the ETW session start before the benchmark's first present.
            try {
                & $Java "@$argsFile" @($runs[$name] | Where-Object { $_ -notlike '-Dbench.timeline=*' }) $main 2>&1 | Where-Object { $_ -match 'BENCH|SWAPCHAIN|GL_SWAP|Exception|Error' } | ForEach-Object { Write-Host $_ }
            } finally {
                & $PresentMon --terminate_existing_session --session_name VulkanForgePM 2>$null | Out-Null
                if (-not $pm.WaitForExit(15000)) { $pm.Kill() }
            }
            if (Test-Path $csv) { Write-Host ("    {0:N0} rows" -f ((Get-Content $csv | Measure-Object -Line).Lines - 1)) } else { Write-Host '    (no CSV written)' }
        }
    }
} finally { Pop-Location }
Write-Host "Done. Traces in $out"
