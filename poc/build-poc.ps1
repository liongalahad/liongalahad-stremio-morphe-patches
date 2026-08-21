[CmdletBinding()]
param(
    [string]$DecodedDirectory = (Join-Path $PSScriptRoot '..\build\stremio-x86_64'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\artifacts'),
    [string]$Abi
)

$ErrorActionPreference = 'Stop'

$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$java = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
$javac = 'C:\Program Files\Android\Android Studio\jbr\bin\javac.exe'
$jar = 'C:\Program Files\Android\Android Studio\jbr\bin\jar.exe'
$apktool = Join-Path $workspace 'tools\apktool_3.0.3.jar'
$buildTools = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\36.0.0'
$androidJar = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platforms\android-36\android.jar'
$keystore = Join-Path $env:USERPROFILE '.android\debug.keystore'
$launcherSourceDirectory = Join-Path $PSScriptRoot 'launcher-src\com\stremio\morphe'
$launcherSources = @(Get-ChildItem -LiteralPath $launcherSourceDirectory -Filter '*.java' -File | Select-Object -ExpandProperty FullName)

foreach ($requiredPath in @($java, $javac, $jar, $apktool, $DecodedDirectory, $buildTools, $androidJar, $keystore, $launcherSourceDirectory)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required path does not exist: $requiredPath"
    }
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path

if ([string]::IsNullOrWhiteSpace($Abi)) {
    $nativeDirectories = @(Get-ChildItem -LiteralPath (Join-Path $DecodedDirectory 'lib') -Directory)
    if ($nativeDirectories.Count -ne 1) {
        throw "Could not infer one ABI from $DecodedDirectory\lib. Pass -Abi explicitly."
    }
    $Abi = $nativeDirectories[0].Name
}

$manifest = Join-Path $DecodedDirectory 'AndroidManifest.xml'
$manifestText = Get-Content -Raw -LiteralPath $manifest
if ($manifestText -notmatch 'package="com\.stremio\.morphe"' -or
    $manifestText -notmatch 'android:label="Stremio Morphe"') {
    throw 'Decoded APK is missing the Stremio Morphe side-by-side identity patch.'
}

$artifactBase = "Stremio-Morphe-1.10.4-MultiAccount-SIDE-BY-SIDE-$Abi"
$unsigned = Join-Path $OutputDirectory "$artifactBase-unsigned.apk"
$aligned = Join-Path $OutputDirectory "$artifactBase-aligned.apk"
$signed = Join-Path $OutputDirectory "$artifactBase.apk"
$launcherBuild = Join-Path $workspace 'build\launcher'
$launcherClasses = Join-Path $launcherBuild 'classes'
$launcherDex = Join-Path $launcherBuild 'dex'
$launcherJar = Join-Path $launcherBuild 'profile-chooser.jar'
$launcherBuildFull = [IO.Path]::GetFullPath($launcherBuild)
$workspaceBuildFull = [IO.Path]::GetFullPath((Join-Path $workspace 'build'))

if (-not $launcherBuildFull.StartsWith($workspaceBuildFull + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean launcher output outside the workspace build directory: $launcherBuildFull"
}

if (Test-Path -LiteralPath $launcherBuild) {
    Remove-Item -LiteralPath $launcherBuild -Recurse -Force
}
New-Item -ItemType Directory -Path $launcherClasses, $launcherDex -Force | Out-Null

& $javac `
    -source 8 `
    -target 8 `
    -classpath $androidJar `
    -d $launcherClasses `
    $launcherSources
if ($LASTEXITCODE -ne 0) {
    throw 'javac failed to compile the TV profile chooser.'
}

& $jar cf $launcherJar -C $launcherClasses '.'
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to package the TV profile chooser classes.'
}

& (Join-Path $buildTools 'd8.bat') `
    --lib $androidJar `
    --min-api 24 `
    --output $launcherDex `
    $launcherJar
if ($LASTEXITCODE -ne 0) {
    throw 'D8 failed to build the TV profile chooser dex.'
}

Move-Item -LiteralPath (Join-Path $launcherDex 'classes.dex') -Destination (Join-Path $launcherDex 'classes10.dex')

& $java -jar $apktool b $DecodedDirectory -o $unsigned
if ($LASTEXITCODE -ne 0) {
    throw 'Apktool failed to rebuild the APK.'
}

Push-Location $launcherDex
try {
    & $jar uf $unsigned 'classes10.dex'
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to inject the TV profile chooser dex into the APK.'
    }
}
finally {
    Pop-Location
}

& (Join-Path $buildTools 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) {
    throw 'zipalign failed.'
}

& (Join-Path $buildTools 'apksigner.bat') sign `
    --ks $keystore `
    --ks-key-alias androiddebugkey `
    --ks-pass pass:android `
    --key-pass pass:android `
    --v4-signing-enabled false `
    --out $signed `
    $aligned
if ($LASTEXITCODE -ne 0) {
    throw 'apksigner failed.'
}

& (Join-Path $buildTools 'apksigner.bat') verify --verbose $signed
if ($LASTEXITCODE -ne 0) {
    throw 'The signed APK failed verification.'
}

$hash = (Get-FileHash -LiteralPath $signed -Algorithm SHA256).Hash
Write-Output "APK: $signed"
Write-Output "SHA-256: $hash"
