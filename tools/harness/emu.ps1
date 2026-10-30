# emu.ps1 - scripted input for ONE PICO emulator instance (selected by the qemu PID of -Avd).
#
# Safety contract (do not weaken):
#   * only PostMessage to windows that belong to the qemu PID of the chosen AVD
#   * never SendInput / mouse_event / SetCursorPos / SetForegroundWindow / SetFocus / ShowWindow
#   * the real cursor and the real focus are never touched
#
# Coordinates are framebuffer pixels of the console screenshot (2160 x 2158) unless -Raw is given.
# The fb -> window mapping is corrected with calib.json (written by calibrate.py); -NoCal ignores it.
#
# Actions (positional):  emu.ps1 <action> [p1] [p2] [p3] [p4] [-options]
#   info                      window geometry, mapping, calibration
#   wins                      every top-level / child window of the emulator PID
#   move  X Y                 move the simulated gaze point
#   down  X Y | up X Y        press / release (pinch) at X Y
#   click X Y                 focus-in (60 fps), wait, move, settle, press 150 ms, release   (-FastWaitMs, -SettleMs, -HoldMs)
#                             -NoFast: stay at 2-4 fps, press 2000 ms (clicks Compose buttons, never a spatial tap)
#   clicks X1 Y1 X2 Y2 ...    several pinch-taps in ONE process (double taps, tap sequences): one focus-in, then for
#                             every point move, settle, press, release; -GapMs between a release and the next press
#                             (default 120). A point that repeats the one before is pressed again without moving.
#                             (-SettleMs, -HoldMs, -GapMs, -FastWaitMs)
#   drag  X1 Y1 X2 Y2         focus-in, wait, move, settle, press, hold, interpolated moves, hold, release
#                             (-SettleMs, -FastWaitMs, -HoldMs, -Steps, -StepMs, -EndHoldMs, -Button, -NoFast;
#                              -PauseAtStep n -PauseMs m: the pointer rests m ms after the n-th step, then goes on;
#                              -Return [-ReturnMs m]: after the path (and m ms), back along it to the start pixel)
#   key   NAME                key down, hold, key up to the main window (-HoldMs, default 150)
#   keydown NAME | keyup NAME
#   wheel X Y [N] [-Notches -N]   mouse wheel at X Y (use -Notches for negative values)
#   wiggle X Y                small continuous moves around X Y for -Ms milliseconds (-StepMs between moves)
#   fast on|off               un-throttle the emulator (2-4 fps -> 60 fps) with a posted WM_SETFOCUS / throttle again
#   capwin HWND OUT.png       PrintWindow of one emulator window (works while it is covered)
#   rawclick HWND X Y         click at client pixels of one emulator window (toolbar windows)
#   rawmove  HWND X Y
#   msg HWND MSG WPARAM LPARAM   post one arbitrary message (numbers, 0x.. allowed)
param(
    [Parameter(Position = 0)][string]$Action = "info",
    [Parameter(Position = 1)][string]$P1 = "",
    [Parameter(Position = 2)][string]$P2 = "",
    [Parameter(Position = 3)][string]$P3 = "",
    [Parameter(Position = 4)][string]$P4 = "",
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest = @(),
    [int]$HoldMs = -1,
    [int]$GapMs = 120,
    [int]$SettleMs = 400,
    [int]$Steps = 8,
    [int]$StepMs = -1,
    [int]$EndHoldMs = 400,
    [int]$PauseAtStep = 0,
    [int]$PauseMs = 0,
    [switch]$Return,
    [int]$ReturnMs = 1000,
    [int]$Ms = 3000,
    [int]$FastWaitMs = 2500,
    [switch]$NoFast,
    [switch]$NoRearm,
    [int]$Notches = 0,
    [string]$Button = "left",
    [switch]$Raw,
    [switch]$NoCal,
    [string]$Avd = "PICO_6.1_Ruler",
    [string]$Calib = ""
)
$ErrorActionPreference = "Stop"
$src = @'
using System;
using System.Text;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
public static class EmuWin2 {
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr parent, EnumProc cb, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder sb, int max);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr hWnd, StringBuilder sb, int max);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr hWnd, ref POINT p);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool IsWindowEnabled(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern IntPtr GetWindow(IntPtr hWnd, uint cmd);
    [DllImport("user32.dll")] public static extern IntPtr GetParent(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint mapType);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdc, uint flags);
    [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr ctx);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }

    public static IntPtr Main = IntPtr.Zero;
    public static IntPtr Sub = IntPtr.Zero;
    public static List<IntPtr> Tops = new List<IntPtr>();

    public static void DpiAware() { try { SetThreadDpiAwarenessContext((IntPtr)(-4)); } catch (Exception) { } }

    public static uint PidOf(IntPtr h) { uint p; GetWindowThreadProcessId(h, out p); return p; }
    public static string Title(IntPtr h) { var t = new StringBuilder(256); GetWindowText(h, t, 256); return t.ToString(); }
    public static string Class(IntPtr h) { var t = new StringBuilder(256); GetClassName(h, t, 256); return t.ToString(); }

    public static void Find(uint pid) {
        Main = IntPtr.Zero; Sub = IntPtr.Zero; Tops.Clear();
        EnumWindows((h, l) => {
            if (PidOf(h) != pid) return true;
            Tops.Add(h);
            if (Title(h).StartsWith("PICO Emulator")) {
                Main = h;
                EnumChildWindows(h, (c, l2) => { if (Class(c) == "subWin") Sub = c; return true; }, IntPtr.Zero);
            }
            return true;
        }, IntPtr.Zero);
    }
    public static List<IntPtr> Children(IntPtr h) {
        var res = new List<IntPtr>();
        EnumChildWindows(h, (c, l2) => { res.Add(c); return true; }, IntPtr.Zero);
        return res;
    }
    public static string Describe(IntPtr h) {
        RECT r; GetWindowRect(h, out r); RECT c; GetClientRect(h, out c);
        POINT o = new POINT(); ClientToScreen(h, ref o);
        return String.Format("hwnd=0x{0:X} class='{1}' title='{2}' rect=({3},{4})-({5},{6}) client={7}x{8} clientOrigin=({9},{10}) visible={11} enabled={12} iconic={13} owner=0x{14:X}",
            h.ToInt64(), Class(h), Title(h), r.L, r.T, r.R, r.B, c.R, c.B, o.X, o.Y, IsWindowVisible(h), IsWindowEnabled(h), IsIconic(h), GetWindow(h, 4).ToInt64());
    }
    public static int[] Client(IntPtr h) { DpiAware(); RECT c; GetClientRect(h, out c); POINT o = new POINT(); ClientToScreen(h, ref o); return new int[] { c.R, c.B, o.X, o.Y }; }
    public static IntPtr LParam(int x, int y) { return (IntPtr)(((y & 0xFFFF) << 16) | (x & 0xFFFF)); }
    // Several WM_MOUSEMOVE posted within microseconds: they are all queued before Qt handles the first one.
    public static void MoveBurst(IntPtr h, int x, int y, long w) {
        DpiAware();
        for (int d = 4; d >= 0; d--) PostMessage(h, 0x0200, (IntPtr)w, LParam(x - d, y));
    }
    public static bool Post(IntPtr h, uint msg, long w, long l) { DpiAware(); return PostMessage(h, msg, (IntPtr)w, (IntPtr)l); }
    public static string Capture(IntPtr h, string path) {
        RECT r; GetWindowRect(h, out r);
        int w = r.R - r.L, hh = r.B - r.T;
        if (w <= 0 || hh <= 0) return "empty window";
        using (var bmp = new Bitmap(w, hh, PixelFormat.Format32bppArgb)) {
            bool ok;
            using (var g = Graphics.FromImage(bmp)) {
                IntPtr hdc = g.GetHdc();
                ok = PrintWindow(h, hdc, 2);
                g.ReleaseHdc(hdc);
            }
            bmp.Save(path, ImageFormat.Png);
            return String.Format("ok={0} {1}x{2} -> {3}", ok, w, hh, path);
        }
    }
}
'@
if (-not ("EmuWin2" -as [type])) { Add-Type -TypeDefinition $src -Language CSharp -ReferencedAssemblies System.Drawing }
[EmuWin2]::DpiAware()

function Num([string]$s) {
    if ($s -match '^0[xX][0-9a-fA-F]+$') { return [Convert]::ToInt64($s.Substring(2), 16) }
    return [double]::Parse($s, [Globalization.CultureInfo]::InvariantCulture)
}

$proc = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'qemu-system-x86_64.exe' -and $_.CommandLine -match ("-avd " + [regex]::Escape($Avd) + "( |$)") })
if ($proc.Count -ne 1) { Write-Output "ERROR: expected exactly one qemu process for AVD $Avd, found $($proc.Count)"; exit 2 }
$emuPid = [uint32]$proc[0].ProcessId
[EmuWin2]::Find($emuPid)
$main = [EmuWin2]::Main; $sub = [EmuWin2]::Sub
if ($main -eq [IntPtr]::Zero -or $sub -eq [IntPtr]::Zero) { Write-Output "ERROR: emulator window not found for pid $emuPid"; exit 3 }

function Post([IntPtr]$h, [uint32]$msg, [long]$w, [long]$l) {
    if ([EmuWin2]::PidOf($h) -ne $emuPid) { Write-Output ("ERROR: hwnd 0x{0:X} is not owned by pid {1}; refusing" -f $h.ToInt64(), $emuPid); exit 9 }
    [void][EmuWin2]::Post($h, $msg, $w, $l)
}

$mc = [EmuWin2]::Client($main); $sc = [EmuWin2]::Client($sub)
$subW = $sc[0]; $subH = $sc[1]; $subOffX = $sc[2] - $mc[2]; $subOffY = $sc[3] - $mc[3]
$fbW = 2160.0; $fbH = 2158.0

if ($Calib -eq "") { $Calib = Join-Path $PSScriptRoot "calib.json" }
$cal = $null
if (-not $NoCal -and (Test-Path $Calib)) { $cal = Get-Content $Calib -Raw | ConvertFrom-Json }

# framebuffer px -> main-window client px.
# With calib.json: x = ax*fbx + bx, y = ay*fby + by (fitted by calibrate.py from fiducials seen in both the console
# screenshot and the window). Without it: the framebuffer is stretched over the render child window ("subWin").
function Map([double]$fx, [double]$fy) {
    if ($cal -ne $null) {
        $x = $cal.ax * $fx + $cal.bx
        $y = $cal.ay * $fy + $cal.by
    } else {
        $x = $fx * $subW / $fbW + $subOffX
        $y = $fy * $subH / $fbH + $subOffY
    }
    return @([int][math]::Round($x), [int][math]::Round($y))
}
function Pt([string]$sx, [string]$sy) {
    if ($Raw) { return @([int](Num $sx), [int](Num $sy)) }
    return (Map (Num $sx) (Num $sy))
}

# Qt discards a button-less WM_MOUSEMOVE when it believes the pointer is outside its windows (always true for the
# first posted move, because the real cursor is elsewhere) or when the position did not change. Two different
# positions queued back to back make the later ones genuine MouseMoves (the WM_MOUSELEAVE that Windows queues in
# response to the first is processed only after them). They must be queued within microseconds, hence MoveBurst in C#:
# posting them one by one from PowerShell lost that race about half of the time (pinch landed on the previous target).
function MoveTo([IntPtr]$h, [int]$x, [int]$y, [long]$w) {
    if ([EmuWin2]::PidOf($h) -ne $emuPid) { Write-Output "ERROR: hwnd not owned by the emulator pid"; exit 9 }
    [EmuWin2]::MoveBurst($h, $x, $y, $w)
}
# 60 fps for the next few seconds: Qt must see a focus-in again (a genuine posted move ends with a Leave event, after
# which the emulator falls back to 2-4 fps within ~3 s). Real focus is not touched.
function Rearm() {
    if ($NoFast -or $NoRearm) { return }
    Post $main 0x0008 0 0; Post $main 0x0007 0 0
    Start-Sleep -Milliseconds $FastWaitMs
}
$WM_MOUSEMOVE = 0x0200; $WM_MOUSEWHEEL = 0x020A; $WM_KEYDOWN = 0x0100; $WM_KEYUP = 0x0101
switch ($Button) {
    "right"  { $dn = 0x0204; $upm = 0x0205; $mk = 2 }
    "middle" { $dn = 0x0207; $upm = 0x0208; $mk = 0x10 }
    default  { $dn = 0x0201; $upm = 0x0202; $mk = 1 }
}

$vkMap = @{ "up" = 0x26; "down" = 0x28; "left" = 0x25; "right" = 0x27; "space" = 0x20; "enter" = 0x0D; "esc" = 0x1B; "tab" = 0x09;
            "shift" = 0x10; "ctrl" = 0x11; "alt" = 0x12; "pgup" = 0x21; "pgdn" = 0x22; "home" = 0x24; "end" = 0x23; "back" = 0x08; "del" = 0x2E;
            "minus" = 0xBD; "plus" = 0xBB; "comma" = 0xBC; "period" = 0xBE }
function Vk([string]$name) {
    $n = $name.ToLower()
    if ($vkMap.ContainsKey($n)) { return $vkMap[$n] }
    if ($n -match '^f([0-9]{1,2})$') { return 0x6F + [int]$Matches[1] }
    if ($n.Length -eq 1) { return [int][char]($n.ToUpper()) }
    if ($n -match '^0x[0-9a-f]+$') { return [Convert]::ToInt32($n.Substring(2), 16) }
    Write-Output "ERROR: unknown key '$name'"; exit 5
}
function KeyL([int]$vk, [bool]$up) {
    $scan = [EmuWin2]::MapVirtualKey([uint32]$vk, 0)
    $l = 1 -bor ($scan -shl 16)
    if ($vk -ge 0x21 -and $vk -le 0x28 -or $vk -eq 0x2E) { $l = $l -bor 0x01000000 }
    if ($up) { $l = $l -bor 0xC0000000 }
    return [long]$l
}

switch ($Action) {
    "info" {
        Write-Output ("pid=$emuPid avd=$Avd")
        Write-Output ("main " + [EmuWin2]::Describe($main))
        Write-Output ("sub  " + [EmuWin2]::Describe($sub))
        if ($cal -ne $null) {
            Write-Output ("map  calibrated: client x = {0} * fbx + {1}, client y = {2} * fby + {3}   ({4})" -f $cal.ax, $cal.bx, $cal.ay, $cal.by, $Calib)
            if ($cal.main_w -ne $mc[0] -or $cal.main_h -ne $mc[1] -or $cal.sub_w -ne $subW -or $cal.sub_h -ne $subH) {
                Write-Output ("WARNING: window geometry changed since calibration (was main {0}x{1}, sub {2}x{3}); re-run calibrate" -f $cal.main_w, $cal.main_h, $cal.sub_w, $cal.sub_h)
            }
        } else {
            Write-Output ("map  UNCALIBRATED fallback: client x = fbx * {0:F5} + {1}, client y = fby * {2:F5} + {3}" -f ($subW / $fbW), $subOffX, ($subH / $fbH), $subOffY)
        }
        Write-Output ("geom main_client={0}x{1} sub_client={2}x{3} sub_offset=({4},{5})" -f $mc[0], $mc[1], $subW, $subH, $subOffX, $subOffY)
        Write-Output ("foreground hwnd=0x{0:X} (emulator main is foreground: {1})" -f [EmuWin2]::GetForegroundWindow().ToInt64(), ([EmuWin2]::GetForegroundWindow() -eq $main))
    }
    "wins" {
        foreach ($t in [EmuWin2]::Tops) {
            Write-Output ("TOP " + [EmuWin2]::Describe($t))
            foreach ($c in [EmuWin2]::Children($t)) { Write-Output ("   child " + [EmuWin2]::Describe($c)) }
        }
    }
    "move" {
        $p = Pt $P1 $P2
        MoveTo $main $p[0] $p[1] 0
        Write-Output ("move client=({0},{1}) fb=({2},{3})" -f $p[0], $p[1], $P1, $P2)
    }
    "down" {
        $p = Pt $P1 $P2; $lp = [EmuWin2]::LParam($p[0], $p[1]).ToInt64()
        MoveTo $main $p[0] $p[1] 0; Start-Sleep -Milliseconds $SettleMs
        Post $main $dn $mk $lp
        Write-Output ("down client=({0},{1}) fb=({2},{3})" -f $p[0], $p[1], $P1, $P2)
    }
    "up" {
        $p = Pt $P1 $P2; $lp = [EmuWin2]::LParam($p[0], $p[1]).ToInt64()
        Post $main $upm 0 $lp
        Write-Output ("up client=({0},{1}) fb=({2},{3})" -f $p[0], $p[1], $P1, $P2)
    }
    "blip" {
        # EXPERIMENT ONLY. Press + immediate release with no move before it: at 60 fps this pinches the PREVIOUS gaze
        # target (tap fires there) and only then moves the gaze to X Y. Kept to reproduce that finding.
        $p = Pt $P1 $P2; $lp = [EmuWin2]::LParam($p[0], $p[1]).ToInt64()
        Post $main $dn $mk $lp
        if ($HoldMs -gt 0) { Start-Sleep -Milliseconds $HoldMs }
        Post $main $upm 0 $lp
        Write-Output ("blip client=({0},{1}) fb=({2},{3}) hold={4}" -f $p[0], $p[1], $P1, $P2, $HoldMs)
    }
    "click" {
        # fast (default): focus-in (60 fps), wait, move, settle, press 150 ms. -NoFast: move, settle, press 2000 ms at 2-4 fps
        # (long presses click Compose buttons but are NOT spatial taps).
        if ($HoldMs -lt 0) { if ($NoFast) { $HoldMs = 2000 } else { $HoldMs = 150 } }
        $p = Pt $P1 $P2; $lp = [EmuWin2]::LParam($p[0], $p[1]).ToInt64()
        Rearm
        MoveTo $main $p[0] $p[1] 0; Start-Sleep -Milliseconds ([int]($SettleMs / 2))
        MoveTo $main $p[0] $p[1] 0; Start-Sleep -Milliseconds ([int]($SettleMs / 2))
        Post $main $dn $mk $lp; Start-Sleep -Milliseconds $HoldMs
        Post $main $upm 0 $lp
        Write-Output ("click client=({0},{1}) fb=({2},{3}) settle={4} hold={5} fast={6}" -f $p[0], $p[1], $P1, $P2, $SettleMs, $HoldMs, (-not $NoFast))
    }
    "clicks" {
        if ($HoldMs -lt 0) { $HoldMs = 150 }
        $coords = @(@($P1, $P2, $P3, $P4) + $Rest | Where-Object { $_ -ne $null -and $_ -ne "" })
        if ($coords.Count -lt 2 -or ($coords.Count % 2) -ne 0) { Write-Output "ERROR: clicks needs X Y pairs"; exit 6 }
        Rearm
        $said = ""; $last = $null
        for ($i = 0; $i -lt $coords.Count; $i += 2) {
            $p = Pt $coords[$i] $coords[$i + 1]; $lp = [EmuWin2]::LParam($p[0], $p[1]).ToInt64()
            if ($last -eq $null -or $last[0] -ne $p[0] -or $last[1] -ne $p[1]) {
                MoveTo $main $p[0] $p[1] 0; Start-Sleep -Milliseconds ([int]($SettleMs / 2))
                MoveTo $main $p[0] $p[1] 0; Start-Sleep -Milliseconds ([int]($SettleMs / 2))
            }
            Post $main $dn $mk $lp; Start-Sleep -Milliseconds $HoldMs
            Post $main $upm 0 $lp
            if ($i + 2 -lt $coords.Count) { Start-Sleep -Milliseconds $GapMs }
            $said += ("({0},{1})" -f $coords[$i], $coords[$i + 1]); $last = $p
        }
        Write-Output ("clicks n={0} fb={1} settle={2} hold={3} gap={4} fast={5}" -f ($coords.Count / 2), $said, $SettleMs, $HoldMs, $GapMs, (-not $NoFast))
    }
    "drag" {
        if ($HoldMs -lt 0) { if ($NoFast) { $HoldMs = 1500 } else { $HoldMs = 300 } }
        if ($StepMs -lt 0) { if ($NoFast) { $StepMs = 500 } else { $StepMs = 60 } }
        $a = Pt $P1 $P2; $b = Pt $P3 $P4
        $lp = [EmuWin2]::LParam($a[0], $a[1]).ToInt64()
        Rearm
        MoveTo $main $a[0] $a[1] 0; Start-Sleep -Milliseconds ([int]($SettleMs / 2))
        MoveTo $main $a[0] $a[1] 0; Start-Sleep -Milliseconds ([int]($SettleMs / 2))
        Post $main $dn $mk $lp; Start-Sleep -Milliseconds $HoldMs
        for ($i = 1; $i -le $Steps; $i++) {
            $x = [int][math]::Round($a[0] + ($b[0] - $a[0]) * $i / $Steps)
            $y = [int][math]::Round($a[1] + ($b[1] - $a[1]) * $i / $Steps)
            $lp = [EmuWin2]::LParam($x, $y).ToInt64()
            Post $main $WM_MOUSEMOVE $mk $lp; Start-Sleep -Milliseconds $StepMs
            if ($PauseAtStep -gt 0 -and $i -eq $PauseAtStep -and $PauseMs -gt 0) { Start-Sleep -Milliseconds $PauseMs }
        }
        if ($Return) {
            # back along the same path to the start pixel (round 5c: the out-and-back check)
            Start-Sleep -Milliseconds $ReturnMs
            for ($i = $Steps - 1; $i -ge 0; $i--) {
                $x = [int][math]::Round($a[0] + ($b[0] - $a[0]) * $i / $Steps)
                $y = [int][math]::Round($a[1] + ($b[1] - $a[1]) * $i / $Steps)
                $lp = [EmuWin2]::LParam($x, $y).ToInt64()
                Post $main $WM_MOUSEMOVE $mk $lp; Start-Sleep -Milliseconds $StepMs
            }
        }
        Start-Sleep -Milliseconds $EndHoldMs
        Post $main $upm 0 $lp
        Write-Output ("drag client=({0},{1})->({2},{3}) fb=({4},{5})->({6},{7}) steps={8} stepMs={9} hold={10} endHold={11} button={12} fast={13}" -f $a[0], $a[1], $b[0], $b[1], $P1, $P2, $P3, $P4, $Steps, $StepMs, $HoldMs, $EndHoldMs, $Button, (-not $NoFast))
    }
    "key" {
        if ($HoldMs -lt 0) { $HoldMs = 150 }
        $vk = Vk $P1
        Post $main $WM_KEYDOWN $vk (KeyL $vk $false); Start-Sleep -Milliseconds $HoldMs
        Post $main $WM_KEYUP $vk (KeyL $vk $true)
        Write-Output ("key {0} vk=0x{1:X} hold={2}" -f $P1, $vk, $HoldMs)
    }
    "keydown" { $vk = Vk $P1; Post $main $WM_KEYDOWN $vk (KeyL $vk $false); Write-Output ("keydown {0}" -f $P1) }
    "keyup"   { $vk = Vk $P1; Post $main $WM_KEYUP $vk (KeyL $vk $true); Write-Output ("keyup {0}" -f $P1) }
    "wheel" {
        $p = Pt $P1 $P2
        $sx = $p[0] + $mc[2]; $sy = $p[1] + $mc[3]          # WM_MOUSEWHEEL carries screen coordinates
        if ($Notches -eq 0 -and $P3 -ne "") { $Notches = [int](Num $P3) }
        $delta = $Notches * 120
        $w = ($delta -band 0xFFFF) -shl 16
        Post $main $WM_MOUSEMOVE 0 ([EmuWin2]::LParam($p[0], $p[1]).ToInt64()); Start-Sleep -Milliseconds 100
        Post $main $WM_MOUSEWHEEL $w ([EmuWin2]::LParam($sx, $sy).ToInt64())
        Write-Output ("wheel client=({0},{1}) delta={2}" -f $p[0], $p[1], $delta)
    }
    "wiggle" {
        if ($StepMs -lt 0) { $StepMs = 16 }
        $p = Pt $P1 $P2
        $sw = [Diagnostics.Stopwatch]::StartNew(); $n = 0
        while ($sw.ElapsedMilliseconds -lt $Ms) {
            $dx = ($n % 2) * 2
            Post $main $WM_MOUSEMOVE 0 ([EmuWin2]::LParam($p[0] + $dx, $p[1]).ToInt64())
            $n++; Start-Sleep -Milliseconds $StepMs
        }
        Write-Output ("wiggle client=({0},{1}) moves={2} ms={3}" -f $p[0], $p[1], $n, $sw.ElapsedMilliseconds)
    }
    "fast" {
        # The emulator renders at 2-4 fps while Qt believes the window has no focus. A posted WM_SETFOCUS makes Qt
        # believe it has (60 fps within ~2 s); the real foreground window and keyboard focus do not change.
        if ($P1 -eq "off") {
            Post $main 0x0008 0 0; Post $main 0x0006 0 0; Post $main 0x001C 0 0
            Write-Output "fast off: posted WM_KILLFOCUS, WM_ACTIVATE(0), WM_ACTIVATEAPP(0)"
        } else {
            Post $main 0x0008 0 0; Post $main 0x0007 0 0
            Write-Output "fast on: posted WM_KILLFOCUS + WM_SETFOCUS (real focus unchanged); 60 fps after ~3 s, until the next posted mouse move"
        }
    }
    "capwin" {
        $h = [IntPtr](Num $P1)
        if ([EmuWin2]::PidOf($h) -ne $emuPid) { Write-Output "ERROR: hwnd not owned by the emulator pid"; exit 9 }
        Write-Output ([EmuWin2]::Capture($h, $P2))
    }
    "rawclick" {
        if ($HoldMs -lt 0) { $HoldMs = 150 }
        $h = [IntPtr](Num $P1); $x = [int](Num $P2); $y = [int](Num $P3); $lp = [EmuWin2]::LParam($x, $y).ToInt64()
        MoveTo $h $x $y 0; Start-Sleep -Milliseconds 200
        MoveTo $h $x $y 0; Start-Sleep -Milliseconds 200
        Post $h $dn $mk $lp; Start-Sleep -Milliseconds $HoldMs
        Post $h $upm 0 $lp
        Write-Output ("rawclick hwnd=0x{0:X} client=({1},{2}) hold={3}" -f $h.ToInt64(), $P2, $P3, $HoldMs)
    }
    "rawmove" {
        $h = [IntPtr](Num $P1); $lp = [EmuWin2]::LParam([int](Num $P2), [int](Num $P3)).ToInt64()
        Post $h $WM_MOUSEMOVE 0 $lp
        Write-Output ("rawmove hwnd=0x{0:X} client=({1},{2})" -f $h.ToInt64(), $P2, $P3)
    }
    "msg" {
        $h = [IntPtr](Num $P1)
        Post $h ([uint32](Num $P2)) ([long](Num $P3)) ([long](Num $P4))
        Write-Output ("msg hwnd=0x{0:X} msg={1} w={2} l={3}" -f $h.ToInt64(), $P2, $P3, $P4)
    }
    default { Write-Output "unknown action '$Action'"; exit 4 }
}
