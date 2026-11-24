[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$rocketRoot = Join-Path $projectRoot 'work/tools/rocketmq-all-5.3.4-bin-release'
$runtimeRoot = Join-Path $projectRoot 'work/rocketmq'
if (!(Test-Path -LiteralPath (Join-Path $rocketRoot 'lib/rocketmq-broker-5.3.4.jar'))) {
    throw '先执行 scripts/SetupRocketMq.ps1 准备已校验的 RocketMQ。'
}
New-Item -ItemType Directory -Force -Path $runtimeRoot, (Join-Path $runtimeRoot 'logs'), (Join-Path $runtimeRoot 'home') | Out-Null
$java = (Get-Command java -ErrorAction Stop).Source
$classes = Join-Path $runtimeRoot 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& javac -encoding UTF-8 -cp (Join-Path $rocketRoot 'lib/*') -d $classes `
    (Join-Path $PSScriptRoot 'LocalOnlyHaService.java') (Join-Path $PSScriptRoot 'LocalRocketMqBroker.java')
if ($LASTEXITCODE -ne 0) { throw '本地单 Broker 启动扩展编译失败。' }
$serviceDirectory = Join-Path $classes 'META-INF/service'
New-Item -ItemType Directory -Force -Path $serviceDirectory | Out-Null
[IO.File]::WriteAllText((Join-Path $serviceDirectory 'org.apache.rocketmq.store.ha.HAService'),
    "videosight.local.LocalOnlyHaService`n", [Text.UTF8Encoding]::new($false))
$nameserverConfig = Join-Path $runtimeRoot 'nameserver.properties'
$brokerConfig = Join-Path $runtimeRoot 'broker.properties'
@'
bindAddress=127.0.0.1
listenPort=9876
kvConfigPath=work/rocketmq/kvConfig.json
configStorePath=work/rocketmq/nameserver-runtime.properties
'@ | Set-Content -LiteralPath $nameserverConfig -Encoding ascii
@'
brokerClusterName=VideoSightLocal
brokerName=videosight-local
brokerId=0
namesrvAddr=127.0.0.1:9876
bindAddress=127.0.0.1
brokerIP1=127.0.0.1
brokerIP2=127.0.0.1
listenPort=10911
haListenPort=10912
autoCreateTopicEnable=false
brokerRole=ASYNC_MASTER
flushDiskType=SYNC_FLUSH
storePathRootDir=work/rocketmq/store
storePathCommitLog=work/rocketmq/store/commitlog
mappedFileSizeCommitLog=16777216
fileReservedTime=24
deleteWhen=04
'@ | Set-Content -LiteralPath $brokerConfig -Encoding ascii

function Start-LocalRocketProcess([string]$Name, [string]$MainClass, [string]$Configuration, [int]$HeapMb) {
    $pidPath = Join-Path $runtimeRoot "$Name.pid"
    if (Test-Path -LiteralPath $pidPath) {
        $existingId = [int]([IO.File]::ReadAllText($pidPath).Trim())
        $existing = Get-CimInstance Win32_Process -Filter "ProcessId=$existingId"
        if ($existing -and $existing.Name -eq 'java.exe' -and $existing.CommandLine.Contains($rocketRoot) -and
                $existing.CommandLine.Contains($MainClass)) {
            Write-Output "$Name 已运行，PID=$existingId"
            return
        }
    }
    $arguments = @("-Xms${HeapMb}m", "-Xmx${HeapMb}m", '-XX:MaxDirectMemorySize=256m',
        '--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.nio=ALL-UNNAMED',
        '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
        ('-Drocketmq.home.dir="' + $rocketRoot + '"'),
        ('-Drocketmq.log.root="' + (Join-Path $runtimeRoot 'logs') + '"'),
        ('-Duser.home="' + (Join-Path $runtimeRoot 'home') + '"'),
        '-cp', ('"' + $classes + ';' + (Join-Path $rocketRoot 'lib/*') + '"'), $MainClass, '-c', ('"' + $Configuration + '"'))
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runtimeRoot "$Name.stdout.log") `
        -RedirectStandardError (Join-Path $runtimeRoot "$Name.stderr.log") -PassThru
    [IO.File]::WriteAllText($pidPath, $process.Id.ToString())
    Write-Output "$Name 已发起启动，PID=$($process.Id)"
}

Start-LocalRocketProcess 'nameserver' 'org.apache.rocketmq.namesrv.NamesrvStartup' $nameserverConfig 128
Start-LocalRocketProcess 'broker' 'videosight.local.LocalRocketMqBroker' $brokerConfig 512

foreach ($port in @(9876, 10911)) {
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $client = [Net.Sockets.TcpClient]::new()
        try { $ready = $client.ConnectAsync('127.0.0.1', $port).Wait(500) -and $client.Connected }
        catch { $ready = $false } finally { $client.Dispose() }
        if ($ready) { break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw "本地队列端口 $port 尚未就绪，请查看 work/rocketmq 日志。" }
}
& $java ('-Duser.home=' + (Join-Path $runtimeRoot 'home')) '-cp' (Join-Path $rocketRoot 'lib/*') `
    org.apache.rocketmq.tools.command.MQAdminStartup updateTopic -n 127.0.0.1:9876 -b 127.0.0.1:10911 -t videosight-parse -r 1 -w 1
if ($LASTEXITCODE -ne 0) { throw '本地解析主题创建或核对失败。' }
Write-Output '本地队列已就绪；HA 复制服务由本地开发扩展保持关闭。'
