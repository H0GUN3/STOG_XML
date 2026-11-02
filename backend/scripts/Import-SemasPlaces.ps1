[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ZipPath,
    [string]$Entry,
    [ValidateRange(1,10000)][int]$MaximumRows
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
foreach($name in @('STOG_IMPORT_JDBC_URL','STOG_IMPORT_DB_USERNAME','STOG_IMPORT_DB_PASSWORD','STOG_IMPORT_CONFIRM_TARGET')){
    if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name,'Process'))){throw "$name is required"}
}
$resolved=(Resolve-Path -LiteralPath $ZipPath).Path
$old=@{}
foreach($name in @('STOG_IMPORT_ZIP','STOG_IMPORT_ENTRY','STOG_IMPORT_MAX_ROWS','JAVA_HOME')){$old[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
    $env:STOG_IMPORT_ZIP=$resolved
    $env:STOG_IMPORT_ENTRY=$Entry
    $env:STOG_IMPORT_MAX_ROWS=if($PSBoundParameters.ContainsKey('MaximumRows')){$MaximumRows.ToString()}else{$null}
    $env:JAVA_HOME='C:\Program Files\Java\jdk-17'
    Push-Location $root
    try{& .\gradlew.bat -p backend --no-daemon importSemasPlaces; if($LASTEXITCODE-ne0){throw "SEMAS import command failed with exit $LASTEXITCODE"}}
    finally{Pop-Location}
}finally{foreach($name in $old.Keys){[Environment]::SetEnvironmentVariable($name,$old[$name],'Process')}}
