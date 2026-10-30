# Switches the PICO emulator's interaction mode through UI Automation (no mouse, no focus change).
# Works on the emulator window of the AVD given by -Avd only (default PICO_6.1_Ruler).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File emu_mode.ps1 -Mode eye|left|right|status
#   eye   = "Eye Gesture Mode" (mouse = gaze, click = pinch; the mode every other harness command assumes)
#   left  = "Left Controller Mode",  right = "Right Controller Mode"
#   status = print the tool-bar buttons and the guest controller state, change nothing
# How it works: the tool bar is a Qt window that exposes UI Automation elements. `ctrl_dropdown_button`
# (InvokePattern) opens a QMenu whose three QAction items are MenuItems with an InvokePattern.
# Posted mouse clicks cannot choose a menu item (they only close the menu); Invoke can.
param(
    [string]$Mode = "status",
    [string]$Avd = "PICO_6.1_Ruler",
    [string]$Serial = "emulator-5556"
)
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
$AE = [System.Windows.Automation.AutomationElement]
$TS = [System.Windows.Automation.TreeScope]

$proc = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'qemu-system-x86_64.exe' -and $_.CommandLine -match ("-avd " + [regex]::Escape($Avd) + " ") }
if (-not $proc) { Write-Output "ERROR: no qemu process for AVD $Avd"; exit 2 }
$emuPid = [int]$proc.ProcessId
$avdName = (adb -s $Serial emu avd name | Select-Object -First 1).Trim()
if ($avdName -ne $Avd) { Write-Output "ERROR: $Serial runs '$avdName', expected '$Avd'"; exit 2 }

$pidCond = New-Object System.Windows.Automation.PropertyCondition($AE::ProcessIdProperty, $emuPid)
function FindByName([string]$name) {
    $c = New-Object System.Windows.Automation.AndCondition($pidCond, (New-Object System.Windows.Automation.PropertyCondition($AE::NameProperty, $name)))
    return $AE::RootElement.FindFirst($TS::Descendants, $c)
}
function GuestState() {
    $s = adb -s $Serial shell "dumpsys pxrcontrollerservice 2>/dev/null | grep -E 'handler|state' | head -4"
    return (($s -join ' ') -replace '\s+', ' ').Trim()
}

if ($Mode -eq "status") {
    foreach ($n in @("interaction_button", "ctrl_dropdown_button", "controller_button", "keyboard_button")) {
        $e = FindByName $n
        if ($e) {
            $tg = $null
            try { $tg = $e.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern).Current.ToggleState } catch {}
            Write-Output ("{0}: rect={1} toggle={2}" -f $n, $e.Current.BoundingRectangle, $tg)
        } else { Write-Output ("{0}: not found" -f $n) }
    }
    Write-Output ("guest: " + (GuestState))
    exit 0
}

$item = switch ($Mode) {
    "eye"   { "Eye Gesture Mode" }
    "left"  { "Left Controller Mode" }
    "right" { "Right Controller Mode" }
    default { Write-Output "unknown mode '$Mode' (eye|left|right|status)"; exit 4 }
}

$dd = FindByName "ctrl_dropdown_button"
if (-not $dd) { Write-Output "ERROR: ctrl_dropdown_button not found"; exit 3 }
# Invoke() returns only after the menu closed in some Qt versions, so open it from a background job.
$job = Start-Job -ArgumentList $emuPid -ScriptBlock {
    param($emuPid)
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    $AE = [System.Windows.Automation.AutomationElement]
    $c = New-Object System.Windows.Automation.AndCondition(
        (New-Object System.Windows.Automation.PropertyCondition($AE::ProcessIdProperty, [int]$emuPid)),
        (New-Object System.Windows.Automation.PropertyCondition($AE::NameProperty, "ctrl_dropdown_button")))
    $dd = $AE::RootElement.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $c)
    $dd.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
}
$el = $null
for ($i = 0; $i -lt 20 -and -not $el; $i++) { Start-Sleep -Milliseconds 300; $el = FindByName $item }
if (-not $el) { Write-Output "ERROR: menu item '$item' not found (menu did not open)"; Remove-Job $job -Force; exit 5 }
$el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
Start-Sleep -Milliseconds 2500
Remove-Job $job -Force -ErrorAction SilentlyContinue
Write-Output ("mode -> {0}; guest: {1}" -f $item, (GuestState))
