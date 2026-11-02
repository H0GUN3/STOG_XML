Set-StrictMode -Version Latest

function Ensure-PrivateLocalDirectory([Parameter(Mandatory = $true)][string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        [IO.Directory]::CreateDirectory($Path) | Out-Null
    }
    $root = Get-Item -LiteralPath $Path -Force
    if (-not $root.PSIsContainer -or ($root.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Private local path must be a normal directory.'
    }
    $items = @($root) + @(Get-ChildItem -LiteralPath $Path -Force -Recurse)
    if ($items | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
        throw 'Private local directory tree must not contain reparse points.'
    }

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $currentSid = $identity.User.Value
    $allowed = @($currentSid, 'S-1-5-18', 'S-1-5-32-544')
    foreach ($item in @($root) + @(Get-ChildItem -LiteralPath $Path -Force -Recurse | Sort-Object FullName -Descending)) {
        if ($item.PSIsContainer) {
            $acl = [IO.Directory]::GetAccessControl(
                $item.FullName, [Security.AccessControl.AccessControlSections]::Access)
        } else {
            $acl = [IO.File]::GetAccessControl(
                $item.FullName, [Security.AccessControl.AccessControlSections]::Access)
        }
        $acl.SetAccessRuleProtection($true, $false)
        foreach ($existingSid in @($acl.Access | ForEach-Object {
            $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier])
        } | Sort-Object Value -Unique)) {
            $acl.PurgeAccessRules($existingSid)
        }
        if ($item.PSIsContainer) {
            $inheritance = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit
        } else {
            $inheritance = [Security.AccessControl.InheritanceFlags]::None
        }
        foreach ($sidValue in $allowed) {
            $sid = [Security.Principal.SecurityIdentifier]::new($sidValue)
            $rule = [Security.AccessControl.FileSystemAccessRule]::new(
                $sid,
                [Security.AccessControl.FileSystemRights]::FullControl,
                $inheritance,
                [Security.AccessControl.PropagationFlags]::None,
                [Security.AccessControl.AccessControlType]::Allow)
            [void]$acl.AddAccessRule($rule)
        }
        if ($item.PSIsContainer) {
            [IO.Directory]::SetAccessControl($item.FullName, $acl)
        } else {
            [IO.File]::SetAccessControl($item.FullName, $acl)
        }
    }

    foreach ($item in @($root) + @(Get-ChildItem -LiteralPath $Path -Force -Recurse)) {
        $acl = Get-Acl -LiteralPath $item.FullName
        foreach ($rule in $acl.Access) {
            $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
            if (($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) -or ($allowed -notcontains $sid)) {
                throw 'Private local directory ACL contains an unexpected principal or deny rule.'
            }
        }
        $currentRule = $acl.Access | Where-Object {
            $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -eq $currentSid
        }
        $currentHasFullControl = $false
        foreach ($rule in @($currentRule)) {
            if (($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq [Security.AccessControl.FileSystemRights]::FullControl) {
                $currentHasFullControl = $true
            }
        }
        if (-not $currentHasFullControl) {
            throw 'Private local directory ACL does not grant the current user full control.'
        }
    }
}
