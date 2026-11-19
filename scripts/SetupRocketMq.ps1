param()
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$toolRoot = Join-Path $projectRoot 'work/tools'
$downloadRoot = Join-Path $toolRoot 'downloads'
New-Item -ItemType Directory -Force -Path $downloadRoot | Out-Null
$version = '5.3.4'
$archiveName = "rocketmq-all-$version-bin-release.zip"
$archivePath = Join-Path $downloadRoot $archiveName
$hashPath = "$archivePath.sha512"
$source = "https://downloads.apache.org/rocketmq/$version/$archiveName"
$hashSource = "https://dist.apache.org/repos/dist/release/rocketmq/$version/$archiveName.sha512"
if (!(Test-Path -LiteralPath $hashPath)) { Invoke-WebRequest -Uri $hashSource -OutFile $hashPath -TimeoutSec 60 }
$hashText = [IO.File]::ReadAllText($hashPath)
$expectedHash = ($hashText -split ':', 2)[-1] -replace '\s', ''
if ($expectedHash -notmatch '^[0-9A-Fa-f]{128}$') { throw '无法读取 Apache 官方 SHA-512 校验值。' }
if (!(Test-Path -LiteralPath $archivePath) -or (Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash -ne $expectedHash) {
    $partialPath = "$archivePath.partial"
    & curl.exe --fail --location --silent --show-error --connect-timeout 15 --max-time 300 --retry 1 --continue-at - --output $partialPath $source
    if ($LASTEXITCODE -ne 0) { throw 'RocketMQ 下载尚未完成，partial文件可在下次续传。' }
    if ((Get-FileHash -LiteralPath $partialPath -Algorithm SHA512).Hash -ne $expectedHash) { throw 'RocketMQ 下载校验失败。' }
    Move-Item -LiteralPath $partialPath -Destination $archivePath -Force
}
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash -ne $expectedHash) {
    throw 'RocketMQ 下载校验失败，请保留文件供排查，不能继续使用。'
}
$installRoot = Join-Path $toolRoot "rocketmq-all-$version-bin-release"
if (!(Test-Path -LiteralPath (Join-Path $installRoot 'lib/rocketmq-broker-5.3.4.jar'))) {
    # 展开前核对每个条目只能落在预期工具目录中。
    $zip = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        foreach ($entry in $zip.Entries) {
            $resolved = [IO.Path]::GetFullPath((Join-Path $toolRoot $entry.FullName))
            if (!$resolved.StartsWith($toolRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                throw '压缩包包含越界路径，已停止。'
            }
        }
    } finally { $zip.Dispose() }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $toolRoot -Force
}
Write-Output "RocketMQ $version 已校验并准备于项目 work/tools。"
