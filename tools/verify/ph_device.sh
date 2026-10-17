#!/bin/bash
# Helpers for driving the PH scenario matrix on the Oppo over adb.
export PATH="$PATH:$LOCALAPPDATA/Android/Sdk/platform-tools"
export MSYS_NO_PATHCONV=1
D=3B166N000CZ00000
SP="C:/Users/Kabir/AppData/Local/Temp/claude/C--Users-Kabir--gemini-antigravity-scratch-Power-Media-Player/db3e6f9d-cdfd-44a6-8ebf-dc4493d52814/scratchpad"
LOG=/sdcard/Android/data/com.powermediaplayer/files/diag/log-current.txt
NOTES="$SP/ui_notes.json"
[ -f "$NOTES" ] || echo '{}' > "$NOTES"

dump() { local i; for i in 1 2 3 4 5; do adb -s $D shell "rm -f /sdcard/u.xml; uiautomator dump /sdcard/u.xml >/dev/null 2>&1; cat /sdcard/u.xml 2>/dev/null" > "$SP/cur.xml"; if grep -q "<hierarchy" "$SP/cur.xml"; then return 0; fi; sleep 1; done; return 1; }
speed_label() { dump; python -X utf8 -c "
import re
x=open(r'$SP/cur.xml',encoding='utf-8',errors='replace').read()
m=re.search(r'text=\"(\d(?:\.\d+)?x)\"',x); print(m.group(1) if m else '?')"; }
# open the speed chips (tap the clickable current-speed label) and pick $1 (e.g. 1.25x)
pick_speed() { dump; local c; c=$(python -X utf8 -c "
import re
x=open(r'$SP/cur.xml',encoding='utf-8',errors='replace').read()
for m in re.finditer(r'<node[^>]*?text=\"(\d(?:\.\d+)?x)\"[^>]*?clickable=\"true\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x):
    x1,y1,x2,y2=map(int,m.groups()[1:]); print((x1+x2)//2,(y1+y2)//2); break"); [ -z "$c" ] && { echo "speed label not found"; return 1; }; tap $c; sleep 1.2; dump; local p; p=$(find_node "$1"); [ -z "$p" ] && { echo "chip $1 not found"; return 1; }; tap $p; sleep 1.5; }
player_title() { dump; python -X utf8 -c "
import re
x=open(r'$SP/cur.xml',encoding='utf-8',errors='replace').read()
for m in re.finditer(r'<node[^>]*?text=\"([^\"]{4,})\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x):
    t,x1,y1,x2,y2=m.groups()
    if 600<int(y1)<1200 and int(x2)-int(x1)>250: print(t[:60]); break"; }

# centre of the first node whose text or content-desc equals $1 (optional max y $2)
find_node() { python -X utf8 -c "
import re,sys
x=open(r'$SP/cur.xml',encoding='utf-8',errors='replace').read()
maxy=int('${2:-2900}')
for m in re.finditer(r'<node[^>]*?text=\"([^\"]*)\"[^>]*?content-desc=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x):
    t,d,x1,y1,x2,y2=m.groups()
    if (t==sys.argv[1] or d==sys.argv[1]) and int(y1)<maxy: print((int(x1)+int(x2))//2,(int(y1)+int(y2))//2); break
" "$1"; }

# first node whose text STARTS WITH $1 → prints the full text
text_starting() { python -X utf8 -c "
import re,sys
x=open(r'$SP/cur.xml',encoding='utf-8',errors='replace').read()
for m in re.finditer(r'<node[^>]*?text=\"([^\"]*)\"',x):
    if m.group(1).startswith(sys.argv[1]): print(m.group(1)); break
" "$1"; }

tap() { adb -s $D shell input tap $1 $2; }
tap_node() { local c; c=$(find_node "$1" "${2:-2900}"); if [ -n "$c" ]; then tap $c; return 0; else echo "NODE NOT FOUND: $1"; return 1; fi; }
longpress() { adb -s $D shell input swipe $1 $2 $1 $2 1200; }
scroll_down() { adb -s $D shell input swipe 720 2300 720 1100 400; sleep 1.2; }
scroll_up() { adb -s $D shell input swipe 720 1100 720 2300 400; sleep 1.2; }
tab() { case $1 in player) tap 109 2976;; library) tap 353 2976;; lastplayed) tap 597 2976;; cloud) tap 841 2976;; settings) tap 1329 2976;; esac; sleep 2.5; }

# scroll until a node with text/desc $1 is visible (max $2 passes, default 8)
scroll_to() { local i; for i in $(seq 1 ${2:-8}); do dump; if [ -n "$(find_node "$1" 2850)" ]; then return 0; fi; scroll_down; done; dump; [ -n "$(find_node "$1" 2850)" ]; }

# type a string slowly (ColorOS IME auto-spaces after '.' when typed fast)
type_slow() { local s="$1" i ch; for ((i=0;i<${#s};i++)); do ch="${s:$i:1}"; case "$ch" in " ") adb -s $D shell input keyevent 62;; *) adb -s $D shell input text "$ch";; esac; sleep 0.3; done; }

note() { python -X utf8 -c "
import json,sys
p=r'$NOTES'; d=json.load(open(p,encoding='utf-8'))
v=sys.argv[2]
try: v=json.loads(v)
except Exception: pass
d[sys.argv[1]]=v; json.dump(d,open(p,'w',encoding='utf-8'),indent=1)
" "$1" "$2"; }

diag_tail() { adb -s $D shell "grep -aE '$1' $LOG | tail -${2:-3}" | cut -c1-${3:-200}; }
diag_count() { adb -s $D shell "grep -acE '$1' $LOG" | tr -d '\r '; }
logcat_grep() { grep -aE "$1" "$SP/logcat_matrix.txt" | tail -${2:-3} | cut -c1-200; }

# podcast status line (text under the Add row)
pod_status() { dump; python -X utf8 -c "
import re
x=open(r'$SP/cur.xml',encoding='utf-8',errors='replace').read()
starts=('Subscribed:','Added ','Fetching','Saving','Feed returned','That URL','Network error','Refreshing','Up to date','Could not refresh','Checking','Still adding','Unsubscribed','Downloaded:','Deleted download','No subscriptions','Download failed','Download cancelled')
for m in re.finditer(r'<node[^>]*?text=\"([^\"]*)\"',x):
    t=m.group(1)
    if any(t.startswith(p) for p in starts) or re.match(r'^\d+ new episode',t): print(t); break
"; }

pull_db() { local f; for f in power_media_player.db power_media_player.db-wal power_media_player.db-shm; do adb -s $D exec-out "run-as com.powermediaplayer cat databases/$f" > "$SP/db/$f" 2>/dev/null; done; }
dbq() { pull_db; python -X utf8 -c "
import sqlite3,sys
c=sqlite3.connect(r'$SP/db/power_media_player.db')
for r in c.execute(sys.argv[1]): print(r)
" "$1"; }

# reveal a row inside the height-capped show list: scroll the outer list to the first show, then drag inside the list
show_row() { local name="$1"; scroll_to "Fore Play" 8 || return 1; local c y; c=$(find_node "Fore Play"); set -- $c; y=$2; local i; for i in 1 2 3; do dump; if [ -n "$(find_node "$name" 2650)" ]; then return 0; fi; adb -s $D shell input swipe 700 $((y+650)) 700 $((y-50)) 500; sleep 1.2; done; dump; [ -n "$(find_node "$name" 2650)" ]; }

# type a url, then remove any IME-inserted ". " space inside the field (cursor back, delete)
type_url() { type_slow "$1"; sleep 0.6; dump; local shown; shown=$(text_starting http); if [[ "$shown" == *". "* ]]; then local after=${shown##*. }; local n=${#after}; adb -s $D shell input keyevent KEYCODE_MOVE_END; local i; for ((i=0;i<n;i++)); do adb -s $D shell input keyevent KEYCODE_DPAD_LEFT; sleep 0.08; done; adb -s $D shell input keyevent KEYCODE_DEL; sleep 0.5; dump; shown=$(text_starting http); fi; echo "field: $shown"; }

# reveal a row of the capped show list by dragging inside the list area under the "Podcasts (N)" header
reveal_show() { local name="$1" c y i; scroll_up; scroll_up; scroll_up; scroll_to "RSS feed URL or search term" 10 || return 1; c=$(find_node "RSS feed URL or search term"); set -- $c; y=$2; if [ "$y" -gt 1500 ]; then scroll_down; dump; c=$(find_node "RSS feed URL or search term"); set -- $c; y=$2; fi; for i in 1 2 3; do dump; if [ -n "$(find_node "$name" 2650)" ]; then return 0; fi; adb -s $D shell input swipe 700 $((y+820)) 700 $((y+300)) 600; sleep 1.2; done; dump; [ -n "$(find_node "$name" 2650)" ]; }
expand_show() { reveal_show "$1" || return 1; dump; if [ -z "$(find_node "Episodes · $1" 2650)" ]; then tap_node "$1" 2650; sleep 1.5; fi; }

# find with retries (dumps can fail/blank during animations); optional max y
find_retry() { local i c; for i in 1 2 3 4; do dump; c=$(find_node "$1" "${2:-2900}"); [ -n "$c" ] && { echo "$c"; return 0; }; sleep 1; done; return 1; }
# scroll until the node is visible ABOVE the mini-player (y<2650), then tap it
tap_row() { local i c; for i in 1 2 3 4 5 6 7 8; do dump; c=$(find_node "$1" 2650); if [ -n "$c" ]; then tap $c; return 0; fi; if [ -n "$(find_node "$1" 2900)" ]; then adb -s $D shell input swipe 720 2000 720 1500 400; sleep 1.2; else scroll_down; fi; done; echo "ROW NOT FOUND: $1"; return 1; }
