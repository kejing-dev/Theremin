# Camera controls of the PICO emulator bottom toolbar through UI Automation (no mouse, no focus change).
#   uia_cam.ps1 status                      print the toggle state of the four camera-mode buttons
#   uia_cam.ps1 reset                       invoke camera_reset_button
#   uia_cam.ps1 set <button> on|off         e.g. set camera_rotation_button on
param([string]$Action = "status", [string]$P1 = "", [string]$P2 = "", [string]$Avd = "Pico_Emulator_6_1_fresh")
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
$AE = [System.Windows.Automation.AutomationElement]
$TS = [System.Windows.Automation.TreeScope]
$proc = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'qemu-system-x86_64.exe' -and $_.CommandLine -match ("-avd " + [regex]::Escape($Avd) + " ") }
if (-not $proc) { Write-Output "ERROR: no qemu process for AVD $Avd"; exit 2 }
$emuPid = [int]$proc.ProcessId
$pidCond = New-Object System.Windows.Automation.PropertyCondition($AE::ProcessIdProperty, $emuPid)
function FindByName([string]$name) {
  $c = New-Object System.Windows.Automation.AndCondition($pidCond, (New-Object System.Windows.Automation.PropertyCondition($AE::NameProperty, $name)))
  return $AE::RootElement.FindFirst($TS::Descendants, $c)
}
$buttons = @("horizontal_pan_button", "vertical_pan_button", "camera_pitch_button", "camera_rotation_button")
function Status() {
  foreach ($n in $buttons) {
    $e = FindByName $n
    if ($e) {
      $t = $null
      try { $t = $e.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern).Current.ToggleState } catch { $t = "n/a" }
      Write-Output ("{0}: {1}" -f $n, $t)
    } else { Write-Output ("{0}: not found" -f $n) }
  }
}
switch ($Action) {
  "status" { Status }
  "reset" {
    $e = FindByName "camera_reset_button"
    if (-not $e) { Write-Output "ERROR: camera_reset_button not found"; exit 3 }
    $e.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
    Write-Output "camera reset invoked"
  }
  "set" {
    $e = FindByName $P1
    if (-not $e) { Write-Output "ERROR: $P1 not found"; exit 3 }
    $tp = $e.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
    $want = $P2.ToLower()
    $cur = $tp.Current.ToggleState.ToString().ToLower()
    if (($want -eq "on" -and $cur -ne "on") -or ($want -eq "off" -and $cur -ne "off")) { $tp.Toggle(); Start-Sleep -Milliseconds 300 }
    Write-Output ("{0}: {1} -> {2}" -f $P1, $cur, $tp.Current.ToggleState)
    Status
  }
  default { Write-Output "usage: status | reset | set <button> on|off" }
}
