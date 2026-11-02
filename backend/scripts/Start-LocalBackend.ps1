[CmdletBinding()]
param(
    [switch]$RestrictedOwner
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Local development only: keep the application on its committed loopback address.
# Public/LAN binding, test credentials, and shared databases are intentionally absent.
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$operatorDirectory = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'STOG\local-backend'
$credentialPath = Join-Path $operatorDirectory 'stog_backend_app.password.clixml'
$restrictedOwnerPath = Join-Path $operatorDirectory 'restricted-owner-user-id.clixml'
$mediaRoot = Join-Path $operatorDirectory 'media'
$jdk = 'C:\Program Files\Java\jdk-17'
$environment = [ordered]@{
    # Override inherited Spring environment only for this dedicated local launcher.
    SERVER_ADDRESS = '127.0.0.1'
    SERVER_PORT = '8080'
    SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5432/stog_backend_dev'
    SPRING_DATASOURCE_USERNAME = 'stog_backend_app'
    SPRING_DATASOURCE_PASSWORD = $null
    SPRING_DATASOURCE_HIKARI_SCHEMA = 'stog_app'
    SPRING_FLYWAY_DEFAULT_SCHEMA = 'stog_app'
    SPRING_FLYWAY_SCHEMAS = 'stog_app'
    SPRING_FLYWAY_CREATE_SCHEMAS = 'false'
    STOG_MEDIA_ROOT = $mediaRoot
    JAVA_HOME = $jdk
}
$oldEnvironment = @{}
$plainPassword = $null
$plainRestrictedOwner = $null
$passwordPointer = [IntPtr]::Zero
$restrictedOwnerPointer = [IntPtr]::Zero
. (Join-Path $PSScriptRoot 'LocalPrivateDirectory.ps1')

if ($RestrictedOwner -and -not (Test-Path -LiteralPath $restrictedOwnerPath -PathType Leaf)) {
    throw "Protected restricted-owner setting is missing at $restrictedOwnerPath. Run Initialize-RestrictedOwner.ps1 once."
}
if (-not (Test-Path -LiteralPath $credentialPath -PathType Leaf)) {
    throw "Protected local backend credential is missing at $credentialPath. Run Initialize-LocalBackend.ps1 once."
}
if (-not (Test-Path -LiteralPath (Join-Path $jdk 'bin\java.exe') -PathType Leaf)) {
    throw "Required project JDK is missing at $jdk."
}
Ensure-PrivateLocalDirectory $operatorDirectory
Ensure-PrivateLocalDirectory $mediaRoot

try {
    if ($RestrictedOwner) {
        $secureRestrictedOwner = Import-Clixml -LiteralPath $restrictedOwnerPath
        if ($secureRestrictedOwner -isnot [Security.SecureString]) { throw 'Protected restricted-owner setting has an invalid format.' }
        $restrictedOwnerPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureRestrictedOwner)
        $plainRestrictedOwner = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($restrictedOwnerPointer)
        if ($plainRestrictedOwner -cnotmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') {
            throw 'Protected restricted-owner setting is not a canonical UUID.'
        }
        $environment.STOG_AUTH_RESTRICTED_OWNER_ENABLED = 'true'
        $environment.STOG_AUTH_RESTRICTED_OWNER_USER_ID = $plainRestrictedOwner
    }

    $securePassword = Import-Clixml -LiteralPath $credentialPath
    if ($securePassword -isnot [Security.SecureString]) { throw 'Protected credential has an invalid format.' }
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $environment.SPRING_DATASOURCE_PASSWORD = $plainPassword

    foreach ($name in $environment.Keys) {
        $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $environment[$name], 'Process')
    }
    $oldEnvironment['PATH'] = [Environment]::GetEnvironmentVariable('PATH', 'Process')
    [Environment]::SetEnvironmentVariable('PATH', (Join-Path $jdk 'bin') + ';' + $oldEnvironment['PATH'], 'Process')

    Push-Location $root
    try {
        & (Join-Path $root 'gradlew.bat') --no-daemon -p backend bootRun
        if ($LASTEXITCODE -ne 0) { throw "Local backend exited with code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $environment.Keys) {
        if ($oldEnvironment.ContainsKey($name)) {
            [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process')
        }
    }
    if ($oldEnvironment.ContainsKey('PATH')) {
        [Environment]::SetEnvironmentVariable('PATH', $oldEnvironment['PATH'], 'Process')
    }
    $plainPassword = $null
    $plainRestrictedOwner = $null
    $environment.SPRING_DATASOURCE_PASSWORD = $null
    if ($environment.Contains('STOG_AUTH_RESTRICTED_OWNER_USER_ID')) {
        $environment.STOG_AUTH_RESTRICTED_OWNER_USER_ID = $null
    }
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    if ($restrictedOwnerPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($restrictedOwnerPointer)
    }
}
