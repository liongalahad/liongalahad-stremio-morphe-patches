[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OriginalApk,

    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'

$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$original = (Resolve-Path -LiteralPath $OriginalApk).Path
$buildRoot = Join-Path $workspace 'build'
$configuration = Get-Content -Raw -LiteralPath (Join-Path $workspace 'checksums.json') | ConvertFrom-Json
$hash = (Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash
$assetProperty = $configuration.assets.PSObject.Properties[$hash]

if ($null -eq $assetProperty) {
    throw "Unsupported original APK SHA-256: $hash"
}

$asset = $assetProperty.Value
$abi = [string]$asset.abi
$java = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
$apktool = Join-Path $workspace 'tools\apktool_3.0.3.jar'
$multiAccountPatch = Join-Path $workspace 'patches\multi-account.patch'
$sideBySidePatch = Join-Path $workspace 'patches\side-by-side-installation.patch'
$assembler = Join-Path $workspace 'poc\build-poc.ps1'

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $workspace 'artifacts'
}

foreach ($requiredPath in @($java, $apktool, $multiAccountPatch, $sideBySidePatch, $assembler)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required path does not exist: $requiredPath"
    }
}

New-Item -ItemType Directory -Path $buildRoot, $OutputDirectory -Force | Out-Null
$decoded = Join-Path $buildRoot ("repro-{0}-{1}" -f $abi, [Guid]::NewGuid().ToString('N'))

& $java -jar $apktool d -f $original -o $decoded
if ($LASTEXITCODE -ne 0) {
    throw 'Apktool failed to decode the original APK.'
}

Push-Location $decoded
try {
    & git apply --check --ignore-space-change $multiAccountPatch
    if ($LASTEXITCODE -ne 0) {
        throw 'The multi-account patch is incompatible with this decoded APK.'
    }
    & git apply --ignore-space-change $multiAccountPatch
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to apply the multi-account patch.'
    }

    & git apply --check --ignore-space-change $sideBySidePatch
    if ($LASTEXITCODE -ne 0) {
        throw 'The side-by-side patch is incompatible with the multi-account output.'
    }
    & git apply --ignore-space-change $sideBySidePatch
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to apply the side-by-side installation patch.'
    }
}
finally {
    Pop-Location
}

& $assembler -DecodedDirectory $decoded -OutputDirectory $OutputDirectory -Abi $abi
if ($LASTEXITCODE -ne 0) {
    throw 'Stremio Morphe assembly failed.'
}
