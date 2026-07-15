Get-ChildItem "builds\*.cs3" | ForEach-Object {
    $file = $_
    $hash = (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLower()
    $size = $file.Length
    Write-Host "FILE: $($file.Name)"
    Write-Host "SIZE: $size"
    Write-Host "HASH: sha256-$hash"
    Write-Host "---"
}
