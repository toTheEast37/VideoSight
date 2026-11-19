[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$rocketRoot = Join-Path $projectRoot 'work/tools/rocketmq-all-5.3.4-bin-release'
$runtimeRoot = Join-Path $projectRoot 'work/rocketmq'
foreach ($name in @('broker', 'nameserver')) {
    $pidPath = Join-Path $runtimeRoot "$name.pid"
    if (!(Test-Path -LiteralPath $pidPath)) { continue }
    $candidateId = [int]([IO.File]::ReadAllText($pidPath).Trim())
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$candidateId"
    $class = if ($name -eq 'broker') { 'videosight.local.LocalRocketMqBroker' } else { 'org.apache.rocketmq.namesrv.NamesrvStartup' }
    if ($process -and $process.Name -eq 'java.exe' -and $process.CommandLine.Contains($rocketRoot) -and
            $process.CommandLine.Contains($class)) {
        # 只停止当前项目 PID 文件和命令行共同核对过的进程，不操作其他 Java 服务。
        Stop-Process -Id $candidateId
        Write-Output "$name 已停止，PID=$candidateId"
    } elseif ($process) { throw "$name PID 已被其他进程使用，拒绝停止。" }
}
