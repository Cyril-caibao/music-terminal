# 生成清理清单：每个位置保留当前版 + 最新 1 份 .prev
# 用法：pwsh -File make-cleanup-manifest.ps1
$ErrorActionPreference = 'Stop'
$root = 'D:\Terminal-Windows'
$outDir = Join-Path $root '_maintenance\20261117'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$targets = New-Object System.Collections.Generic.List[object]
function Add-Target($file, $group) {
    $targets.Add([PSCustomObject]@{
        Group = $group
        Path  = $file.FullName
        Bytes = $file.Length
        MB    = [math]::Round($file.Length / 1MB, 2)
        MTime = $file.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
    })
}

# 1) 整个 _prev_archive
Get-ChildItem -LiteralPath (Join-Path $root 'electron-dist\_prev_archive') -File -Force |
    ForEach-Object { Add-Target $_ '_prev_archive' }

# 2) 整个 installer\_prev
Get-ChildItem -LiteralPath (Join-Path $root 'installer\_prev') -File -Force |
    ForEach-Object { Add-Target $_ 'installer\_prev' }

$keeps = New-Object System.Collections.Generic.List[object]

# 3) dist\WebTerm.exe.prev*，保留最新 1 份
$distPrev = Get-ChildItem -LiteralPath (Join-Path $root 'dist') -File -Force |
    Where-Object { $_.Name -match '^WebTerm\.exe\.prev\d+$' }
$keepDist = $distPrev | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$distPrev | Where-Object { $_.FullName -ne $keepDist.FullName } |
    ForEach-Object { Add-Target $_ 'dist\WebTerm.exe.prev*' }
if ($keepDist) { $keeps.Add([PSCustomObject]@{ Group = 'dist'; Path = $keepDist.FullName; MTime = $keepDist.LastWriteTime }) }

# 4) resources\WebTerm.exe.prev*，保留最新 1 份
$resDir = Join-Path $root 'electron-dist\WebTerm-Electron-win32-x64\resources'
$resPrev = Get-ChildItem -LiteralPath $resDir -File -Force |
    Where-Object { $_.Name -match '^WebTerm\.exe\.prev\d+$' }
$keepRes = $resPrev | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$resPrev | Where-Object { $_.FullName -ne $keepRes.FullName } |
    ForEach-Object { Add-Target $_ 'resources\WebTerm.exe.prev*' }
if ($keepRes) { $keeps.Add([PSCustomObject]@{ Group = 'resources'; Path = $keepRes.FullName; MTime = $keepRes.LastWriteTime }) }

# 5) 额外：_staging 是安装包构建缓存，可重建；build 是 PyInstaller 缓存
Get-ChildItem -LiteralPath (Join-Path $root 'electron-dist\_staging') -Recurse -File -Force |
    ForEach-Object { Add-Target $_ '_staging (build cache)' }

# 计算 SHA256
$rows = New-Object System.Collections.Generic.List[object]
$i = 0
foreach ($t in $targets) {
    $i++
    Write-Progress -Activity 'SHA256' -Status $t.Path -PercentComplete (($i / $targets.Count) * 100)
    $h = (Get-FileHash -LiteralPath $t.Path -Algorithm SHA256).Hash
    $rows.Add([PSCustomObject]@{
        sha256 = $h
        bytes  = $t.Bytes
        MB     = $t.MB
        mtime  = $t.MTime
        group  = $t.Group
        path   = $t.Path
    })
}
Write-Progress -Activity 'SHA256' -Completed

$csv = Join-Path $outDir 'delete-manifest.csv'
$rows | Export-Csv -LiteralPath $csv -NoTypeInformation -Encoding utf8BOM

$keepCsv = Join-Path $outDir 'keep-list.csv'
$keeps | ForEach-Object {
    [PSCustomObject]@{
        group = $_.Group
        path  = $_.Path
        mtime = $_.MTime.ToString('yyyy-MM-dd HH:mm:ss')
    }
} | Export-Csv -LiteralPath $keepCsv -NoTypeInformation -Encoding utf8BOM

$total = ($rows | Measure-Object bytes -Sum).Sum
$summary = @(
    "生成时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
    "待删除文件: $($rows.Count) 个",
    "待删除合计: $([math]::Round($total/1GB,2)) GB ($total 字节)",
    "保留文件:",
    ($keeps | ForEach-Object { "  $($_.Group) -> $($_.Path)" }),
    "",
    "清单: $csv"
) -join "`r`n"
[IO.File]::WriteAllText((Join-Path $outDir 'summary.txt'), $summary, (New-Object Text.UTF8Encoding($true)))
Write-Output $summary
