[CmdletBinding()]
param(
    # 已有下载全部校验通过时，不需要网络；仍会运行版本、语言和中文截图检查。
    [switch]$Offline
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$mediaRepository = Split-Path -Parent $PSScriptRoot
$mediaRoot = Join-Path $mediaRepository 'work/tools/media'
$downloadRoot = Join-Path $mediaRoot 'downloads'
$evidenceRoot = Join-Path $mediaRoot 'evidence'
New-Item -ItemType Directory -Force -Path $downloadRoot, $evidenceRoot | Out-Null

# 来源：ffmpeg.org/download.html 指向 gyan.dev；Tesseract 官方安装文档指向
# github.com/UB-Mannheim/tesseract/wiki，其链接为以下官方发行附件。
# 7-Zip 官网 download.html 指向 ip7z/7zip 官方发行；仅将安装包当归档提取。
# FFmpeg 摘要由构建方 .sha256 核对，三个 EXE 摘要由 GitHub release digest 核对。
# 语言数据固定官方 tessdata_fast 提交，避免 main 更新改变识别基线。
$artifacts = @(
    @{ Name = 'ffmpeg-9.0.2-essentials_build.zip'; Sha256 = '60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba'; Url = 'https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.zip' },
    @{ Name = 'tesseract-ocr-w64-setup-5.5.3.20260724.exe'; Sha256 = 'bee9e3434bd94fd65387d9be28cd467a41f61b1275383b55b0f59a1331270ae4'; Url = 'https://github.com/tesseract-ocr/tesseract/releases/download/5.5.3/tesseract-ocr-w64-setup-5.5.3.20260724.exe' },
    @{ Name = '7zr.exe'; Sha256 = 'e80db2dad715a6c5adabe292d31ed54e11f4e9b99ca349827b50573b175891bc'; Url = 'https://github.com/ip7z/7zip/releases/download/26.06/7zr.exe' },
    @{ Name = '7z2606-x64.exe'; Sha256 = '9b697f65ecc2d9b0152f5ee96d72dde522a7827690349d9307935dacf40f42ec'; Url = 'https://github.com/ip7z/7zip/releases/download/26.06/7z2606-x64.exe' },
    @{ Name = 'chi_sim.traineddata'; Sha256 = 'a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730'; Url = 'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/chi_sim.traineddata' },
    @{ Name = 'eng.traineddata'; Sha256 = '7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2'; Url = 'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/eng.traineddata' }
)

foreach ($artifact in $artifacts) {
    $destination = Join-Path $downloadRoot $artifact.Name
    if (!(Test-Path -LiteralPath $destination)) {
        if ($Offline) { throw "离线文件缺失：$($artifact.Name)" }
        $partial = "$destination.partial"
        & curl.exe --fail --silent --show-error --location --retry 2 --connect-timeout 15 --max-time 600 --output $partial $artifact.Url
        if ($LASTEXITCODE -ne 0) { throw "下载失败：$($artifact.Name)，未完成文件保留在 downloads。" }
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ine $artifact.Sha256) {
            throw "下载文件校验失败：$($artifact.Name)，不会执行该文件。"
        }
        Move-Item -LiteralPath $partial -Destination $destination
    }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ine $artifact.Sha256) {
        throw "现有文件校验失败：$($artifact.Name)，请核对来源后处理，不自动覆盖。"
    }
}

$archiveRoot = Join-Path $mediaRoot 'archive'
$ffmpegRoot = Join-Path $mediaRoot 'ffmpeg'
$tesseractRoot = Join-Path $mediaRoot 'tesseract'
$sevenZip = Join-Path $archiveRoot '7z.exe'
$ffmpeg = Join-Path $ffmpegRoot 'ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe'
$ffprobe = Join-Path $ffmpegRoot 'ffmpeg-9.0.2-essentials_build/bin/ffprobe.exe'
$tesseract = Join-Path $tesseractRoot 'tesseract.exe'
$tessdata = Join-Path $tesseractRoot 'tessdata'
if (!(Test-Path -LiteralPath $sevenZip)) {
    & (Join-Path $downloadRoot '7zr.exe') x (Join-Path $downloadRoot '7z2606-x64.exe') "-o$archiveRoot" -y | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '7-Zip 便携解包失败。' }
}
if (!(Test-Path -LiteralPath $ffmpeg) -or !(Test-Path -LiteralPath $ffprobe)) {
    & $sevenZip x (Join-Path $downloadRoot 'ffmpeg-9.0.2-essentials_build.zip') "-o$ffmpegRoot" -y | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'FFmpeg 解包失败。' }
}
if (!(Test-Path -LiteralPath $tesseract)) {
    & $sevenZip x (Join-Path $downloadRoot 'tesseract-ocr-w64-setup-5.5.3.20260724.exe') "-o$tesseractRoot" -y | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Tesseract 解包失败。' }
}
Copy-Item -LiteralPath (Join-Path $downloadRoot 'chi_sim.traineddata') -Destination (Join-Path $tessdata 'chi_sim.traineddata')
Copy-Item -LiteralPath (Join-Path $downloadRoot 'eng.traineddata') -Destination (Join-Path $tessdata 'eng.traineddata')

# 此 Windows 构建在处理含中文的绝对数据路径时会失败；固定工作目录，
# 只向 Tesseract 传入 ASCII 相对路径。结束时恢复调用者目录。
Push-Location -LiteralPath $mediaRepository
try {
$ffmpegVersion = @(& $ffmpeg -version 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg 版本检查失败。' }
$ffprobeVersion = @(& $ffprobe -version 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'FFprobe 版本检查失败。' }
$tesseractVersion = @(& $tesseract --version 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'Tesseract 版本检查失败。' }
$languages = @(& $tesseract --list-langs --tessdata-dir 'work/tools/media/tesseract/tessdata' 2>&1)
if ($LASTEXITCODE -ne 0 -or $languages -notcontains 'chi_sim' -or $languages -notcontains 'eng') {
    throw 'Tesseract 中英文语言检查失败。'
}
$ffmpegVersion | Set-Content -LiteralPath (Join-Path $evidenceRoot 'ffmpeg-version.txt') -Encoding utf8
$ffprobeVersion | Set-Content -LiteralPath (Join-Path $evidenceRoot 'ffprobe-version.txt') -Encoding utf8
$tesseractVersion | Set-Content -LiteralPath (Join-Path $evidenceRoot 'tesseract-version.txt') -Encoding utf8
$languages | Set-Content -LiteralPath (Join-Path $evidenceRoot 'tesseract-languages.txt') -Encoding utf8

# 程序绘制的清晰文字样例，只验证本地工具可以实际识别，不代替真实课程效果验收。
Add-Type -AssemblyName System.Drawing
$samplePath = Join-Path $evidenceRoot 'chinese-course.png'
$bitmap = New-Object System.Drawing.Bitmap 1440, 800
$bitmap.SetResolution(300, 300)
$drawing = [System.Drawing.Graphics]::FromImage($bitmap)
$font = New-Object System.Drawing.Font 'Microsoft YaHei', 38, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
try {
    $drawing.Clear([System.Drawing.Color]::White)
    $drawing.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $sampleLines = @('Java 后端课程', '数据库事务与消息队列', '上传成功以后，保存视频资产。', '识别文字需要核对原视频。', 'requestKey / taskId / assetId', 'SELECT * FROM video_asset;')
    for ($lineIndex = 0; $lineIndex -lt $sampleLines.Count; $lineIndex++) {
        $drawing.DrawString($sampleLines[$lineIndex], $font, [System.Drawing.Brushes]::Black, 70, (60 + $lineIndex * 105))
    }
    $bitmap.Save($samplePath, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $font.Dispose()
    $drawing.Dispose()
    $bitmap.Dispose()
}
$ocrOutputBase = Join-Path $evidenceRoot 'chinese-course-ocr'
& $tesseract 'work/tools/media/evidence/chinese-course.png' 'work/tools/media/evidence/chinese-course-ocr' --tessdata-dir 'work/tools/media/tesseract/tessdata' -l chi_sim+eng
if ($LASTEXITCODE -ne 0) { throw '中文截图 OCR 执行失败。' }
$recognizedText = Get-Content -LiteralPath "$ocrOutputBase.txt" -Raw -Encoding utf8
if ($recognizedText -notmatch '数据库' -or $recognizedText -notmatch '视频') {
    throw '中文截图 OCR 未识别到预期关键词，请检查 evidence 中的截图与结果。'
}

$receipt = [ordered]@{
    verifiedAt = (Get-Date).ToString('o')
    scope = '本机便携工具版本、语言列表与程序生成的中文截图识别；不代表真实课程视频识别效果。'
    ffmpeg = $ffmpeg
    ffprobe = $ffprobe
    tesseract = $tesseract
    tessdata = $tessdata
    tessdataCommit = '87416418657359cb625c412a48b6e1d6d41c29bd'
    sample = $samplePath
    ocrText = "$ocrOutputBase.txt"
    artifacts = $artifacts
}
$receipt | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'media-tools-receipt.json') -Encoding utf8
Write-Output "FFmpeg：$ffmpeg"
Write-Output "FFprobe：$ffprobe"
Write-Output "Tesseract：$tesseract"
Write-Output "语言数据：$tessdata"
Write-Output "实际检查结果：$evidenceRoot"
Write-Output '准备完成。未修改全局 PATH、注册表或系统服务；移除本项目 work/tools/media 即可撤销便携文件。'
} finally {
    Pop-Location
}
