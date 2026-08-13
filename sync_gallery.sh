#!/usr/bin/env bash
# Sync AutoBots gallery from Android → Mac (incremental).
# Sources:
#   - DCIM/AutoBots/{session}/       ← JPEGs
#   - Download/AutoBots/{session}/   ← session_log.txt + perf_report.json
#   - app cache (debug builds)       ← mirrors of both files
#
# Session folder names carry the app version: ext_v0_1_3_11082026_1228 (import),
# v0_1_3_20260811_143052 (live). Folders from builds before that sit alongside them.
#
# Usage:
#   ./sync_gallery.sh [destination_dir]
#   ./sync_gallery.sh --logs-only [destination_dir]   # skip JPEGs — fast analyze loop
#   ./sync_gallery.sh --logcat [destination_dir]      # also dump the CamPerf logcat buffer

set -euo pipefail

LOGS_ONLY=0
GRAB_LOGCAT=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --logs-only) LOGS_ONLY=1; shift ;;
    --logcat) GRAB_LOGCAT=1; shift ;;
    -h|--help)
      sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) break ;;
  esac
done

DEST="${1:-$HOME/Downloads/AutoBots-export}"
SESSION_LOG="session_log.txt"
PERF_REPORT="perf_report.json"
# Every per-session text artifact the app writes. Pulled from Downloads and, as a
# fallback, from the app cache mirror when the MediaStore write did not land.
SESSION_FILES=("$SESSION_LOG" "$PERF_REPORT")

die() {
  echo "error: $*" >&2
  exit 1
}

require_adb_device() {
  local count
  count="$(adb devices | awk 'NR > 1 && $2 == "device" { c++ } END { print c + 0 }')"
  if [[ "$count" -eq 0 ]]; then
    die "no adb device — connect phone and run: adb devices"
  fi
  if [[ "$count" -gt 1 ]]; then
    die "multiple adb devices — set ANDROID_SERIAL to pick one"
  fi
}

remote_dir_exists() {
  adb shell "[ -d '$1' ]" >/dev/null 2>&1
}

resolve_remote_roots() {
  local pair base path
  local -a roots=()
  for pair in "DCIM/AutoBots" "Download/AutoBots"; do
    for base in "/storage/emulated/0" "/sdcard"; do
      path="$base/$pair"
      if remote_dir_exists "$path"; then
        roots+=("$path")
        break
      fi
    done
  done
  if [[ "${#roots[@]}" -eq 0 ]]; then
    return 1
  fi
  printf '%s\n' "${roots[@]}"
}

list_names() {
  local dir="$1"
  if [[ ! -d "$dir" ]]; then
    return 0
  fi
  find "$dir" -mindepth 1 -maxdepth 1 \( -type d -o -type f \) -print \
    | sed "s|.*/||" \
    | sort
}

list_remote_names() {
  local remote="$1"
  adb shell "ls -1 '$remote' 2>/dev/null" | tr -d '\r' | sed '/^$/d' | sort
}

is_remote_dir() {
  adb shell "[ -d '$1' ]" >/dev/null 2>&1
}

local_folder_latest_epoch() {
  local dir="$1"
  if [[ ! -d "$dir" ]]; then
    echo 0
    return
  fi
  find "$dir" -type f -exec stat -f '%m' {} + 2>/dev/null \
    | sort -n \
    | tail -1 \
    || echo 0
}

remote_folder_latest_epoch() {
  local remote_dir="$1"
  adb shell "find '$remote_dir' -type f -exec stat -c '%Y' {} + 2>/dev/null" \
    | tr -d '\r' \
    | sort -n \
    | tail -1 \
    || echo 0
}

remote_file_epoch() {
  adb shell "stat -c '%Y' '$1' 2>/dev/null" | tr -d '\r' || echo 0
}

local_file_epoch() {
  local f="$1"
  if [[ -f "$f" ]]; then
    stat -f '%m' "$f"
  else
    echo 0
  fi
}

pull_remote_file() {
  local remote_file="$1"
  local local_file="$2"
  mkdir -p "$(dirname "$local_file")"
  adb pull "$remote_file" "$local_file"
}

pull_remote_folder() {
  local remote_dir="$1"
  local local_dir="$2"
  mkdir -p "$(dirname "$local_dir")"
  adb pull "$remote_dir/" "$local_dir/"
}

sync_existing_folder() {
  local remote_base="$1"
  local name="$2"
  local remote_dir="$remote_base/$name"
  local local_dir="$DEST/$name"
  local pulled=0
  local skipped=0
  local file remote_file local_file remote_mtime local_mtime
  local all_present=1

  while IFS= read -r file; do
    [[ -z "$file" ]] && continue
    if [[ ! -f "$local_dir/$file" ]]; then
      all_present=0
      break
    fi
  done < <(list_remote_names "$remote_dir")

  if [[ "$all_present" -eq 1 ]]; then
    local remote_latest local_latest
    remote_latest="$(remote_folder_latest_epoch "$remote_dir")"
    local_latest="$(local_folder_latest_epoch "$local_dir")"
    if [[ "$local_latest" -gt 0 && "$remote_latest" -le "$local_latest" ]]; then
      echo "  skip (up to date): $name"
      return 0
    fi
  fi

  while IFS= read -r file; do
    [[ -z "$file" ]] && continue
    remote_file="$remote_dir/$file"
    local_file="$local_dir/$file"

    if [[ -f "$local_file" ]]; then
      remote_mtime="$(remote_file_epoch "$remote_file")"
      local_mtime="$(local_file_epoch "$local_file")"
      if [[ "$remote_mtime" -le "$local_mtime" ]]; then
        skipped=$((skipped + 1))
        continue
      fi
    fi

    echo "  pull: $name/$file"
    pull_remote_file "$remote_file" "$local_file"
    pulled=$((pulled + 1))
  done < <(list_remote_names "$remote_dir")

  if [[ "$pulled" -eq 0 ]]; then
    echo "  skip (up to date): $name ($skipped files)"
  fi
}

sync_remote_root() {
  local remote_base="$1"
  local -a session_paths=()
  local relpath

  echo "==> $remote_base"

  if ! remote_dir_exists "$remote_base"; then
    echo "  (not found — skip)"
    return 0
  fi

  while IFS= read -r relpath; do
    [[ -z "$relpath" ]] && continue
    session_paths+=("$relpath")
  done < <(list_remote_names "$remote_base")

  if [[ "${#session_paths[@]}" -eq 0 ]]; then
    echo "  (empty)"
    return 0
  fi

  echo "  ${#session_paths[@]} session folder(s): ${session_paths[*]}"

  for relpath in "${session_paths[@]}"; do
    local remote_path="$remote_base/$relpath"
    local local_path="$DEST/$relpath"
    echo "  [$relpath]"
    if ! is_remote_dir "$remote_path"; then
      # A loose file directly under the album root — pull it as-is.
      if [[ -f "$local_path" ]] &&
        [[ "$(remote_file_epoch "$remote_path")" -le "$(local_file_epoch "$local_path")" ]]; then
        echo "    skip (up to date)"
      else
        echo "    pull file"
        pull_remote_file "$remote_path" "$local_path"
      fi
      continue
    fi
    if [[ -d "$local_path" ]]; then
      sync_existing_folder "$remote_base" "$relpath"
    else
      echo "    pull folder (new)"
      pull_remote_folder "$remote_path" "$local_path"
    fi
  done
  echo
}

sync_session_logs() {
  local download_base=""
  for base in "/storage/emulated/0/Download/AutoBots" "/sdcard/Download/AutoBots"; do
    if remote_dir_exists "$base"; then
      download_base="$base"
      break
    fi
  done
  if [[ -z "$download_base" ]]; then
    return 0
  fi

  echo "==> session logs + perf reports ($download_base)"
  local relpath artifact
  while IFS= read -r relpath; do
    [[ -z "$relpath" ]] && continue
    for artifact in "${SESSION_FILES[@]}"; do
      local remote_file="$download_base/$relpath/$artifact"
      local local_file="$DEST/$relpath/$artifact"
      if ! adb shell "[ -f '$remote_file' ]" >/dev/null 2>&1; then
        continue
      fi
      # Reports are rewritten per run, so refresh whenever the device copy is newer.
      if [[ -f "$local_file" ]]; then
        local remote_mtime local_mtime
        remote_mtime="$(remote_file_epoch "$remote_file")"
        local_mtime="$(local_file_epoch "$local_file")"
        if [[ "$remote_mtime" -le "$local_mtime" ]]; then
          echo "  skip (up to date): $relpath/$artifact"
          continue
        fi
      fi
      mkdir -p "$DEST/$relpath"
      echo "  pull: $relpath/$artifact"
      pull_remote_file "$remote_file" "$local_file"
    done
  done < <(list_remote_names "$download_base")
  echo
}

sync_debug_session_logs() {
  if ! adb shell run-as com.autobots.camera true >/dev/null 2>&1; then
    return 0
  fi

  local folders
  folders="$(adb exec-out run-as com.autobots.camera ls cache/autobots/logs 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "$folders" ]]; then
    return 0
  fi

  local -a relpaths=()
  local name
  for name in $folders; do
    [[ -z "$name" ]] && continue
    relpaths+=("$name")
  done

  echo "==> debug cache mirrors"
  local relpath artifact
  for relpath in "${relpaths[@]}"; do
    mkdir -p "$DEST/$relpath"
    for artifact in "${SESSION_FILES[@]}"; do
      local dest="$DEST/$relpath/$artifact"
      if [[ -s "$dest" ]]; then
        echo "  skip (have): $relpath/$artifact"
        continue
      fi
      if adb exec-out run-as com.autobots.camera \
        cat "cache/autobots/logs/$relpath/$artifact" >"$dest" 2>/dev/null && [[ -s "$dest" ]]; then
        echo "  pull: $relpath/$artifact"
      else
        # run-as writes an empty file even when the source is missing.
        rm -f "$dest"
      fi
    done
  done
  echo
}

# The CamPerf logcat buffer holds stage tables from runs whose session never drained
# (crash, force-stop). Cheap insurance next to perf_report.json.
sync_logcat() {
  local out="$DEST/camperf_$(date +%Y%m%d_%H%M%S).txt"
  echo "==> logcat (CamPerf, VideoFrameProcessor, CapturePipeline)"
  mkdir -p "$DEST"
  if adb logcat -d -s CamPerf VideoFrameProcessor CapturePipeline VideoFrameSampler LocalDelivery \
    >"$out" 2>/dev/null && [[ -s "$out" ]]; then
    echo "  saved: $(basename "$out") ($(wc -l <"$out" | tr -d ' ') lines)"
  else
    rm -f "$out"
    echo "  (buffer empty — run the test, then sync before rebooting)"
  fi
  echo
}

report_missing_logs() {
  local missing=0
  local dir name artifact
  for dir in "$DEST"/*; do
    [[ -d "$dir" ]] || continue
    compgen -G "$dir/"'*.jpg' >/dev/null || continue
    name="${dir#"$DEST"/}"
    for artifact in "${SESSION_FILES[@]}"; do
      if [[ ! -s "$dir/$artifact" ]]; then
        echo "  warning: $name has photos but no $artifact"
        missing=$((missing + 1))
      fi
    done
  done
  if [[ "$missing" -eq 0 ]]; then
    echo "  every session folder with photos has ${SESSION_FILES[*]}"
  else
    echo
    echo "  perf_report.json needs a CAM_PERF build:"
    echo "    ./gradlew :androidApp:installDebug     (or -PcamPerf=true for release)"
  fi
}

main() {
  require_adb_device

  local -a remote_roots=()
  while IFS= read -r root; do
    [[ -z "$root" ]] && continue
    remote_roots+=("$root")
  done < <(resolve_remote_roots || true)

  if [[ "${#remote_roots[@]}" -eq 0 ]]; then
    die "AutoBots folders not found on device (DCIM/AutoBots or Download/AutoBots)"
  fi

  mkdir -p "$DEST"

  echo "Local export: $DEST"
  echo

  local root
  if [[ "$LOGS_ONLY" -eq 1 ]]; then
    echo "(--logs-only: skipping JPEG sync)"
    echo
  else
    for root in "${remote_roots[@]}"; do
      sync_remote_root "$root"
    done
  fi

  sync_session_logs
  sync_debug_session_logs
  if [[ "$GRAB_LOGCAT" -eq 1 ]]; then
    sync_logcat
  fi

  echo "Done. Files are in: $DEST"
  echo
  echo "Session log check:"
  report_missing_logs
}

main "$@"
