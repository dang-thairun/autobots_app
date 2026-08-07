#!/usr/bin/env bash
# Sync AutoBots gallery from Android → Mac (incremental).
# Sources:
#   - DCIM/AutoBots/{session}/          ← JPEGs
#   - Download/AutoBots/{session}/      ← session_log.txt (Android 10+ fallback)
#   - app cache (debug builds)          ← session_log.txt mirror
#
# Usage: ./sync_gallery.sh [destination_dir]

set -euo pipefail

DEST="${1:-$HOME/Downloads/AutoBots-export}"
SESSION_LOG="session_log.txt"

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

contains_name() {
  local needle="$1"
  shift
  local item
  for item in "$@"; do
    if [[ "$item" == "$needle" ]]; then
      return 0
    fi
  done
  return 1
}

sync_remote_root() {
  local remote_base="$1"
  local -a remote_names=()
  local -a local_names=()
  local name

  echo "==> $remote_base"

  if ! remote_dir_exists "$remote_base"; then
    echo "  (not found — skip)"
    return 0
  fi

  adb shell "ls -la '$remote_base'" | tr -d '\r' | sed 's/^/  /'
  echo

  while IFS= read -r name; do
    [[ -z "$name" ]] && continue
    remote_names+=("$name")
  done < <(list_remote_names "$remote_base")

  while IFS= read -r name; do
    [[ -z "$name" ]] && continue
    local_names+=("$name")
  done < <(list_names "$DEST")

  if [[ "${#remote_names[@]}" -eq 0 ]]; then
    echo "  (empty)"
    return 0
  fi

  local to_pull=()
  local to_update=()

  for name in "${remote_names[@]}"; do
    if contains_name "$name" "${local_names[@]+"${local_names[@]}"}"; then
      to_update+=("$name")
    else
      to_pull+=("$name")
    fi
  done

  if [[ "${#to_pull[@]}" -gt 0 ]]; then
    echo "  จะดึง (ยังไม่มี): ${to_pull[*]}"
  fi
  if [[ "${#to_update[@]}" -gt 0 ]]; then
    echo "  เช็คเพิ่ม (มีแล้ว): ${to_update[*]}"
  fi

  for name in "${to_pull[@]+"${to_pull[@]}"}"; do
    echo "  [$name]"
    local remote_path="$remote_base/$name"
    local local_path="$DEST/$name"
    if is_remote_dir "$remote_path"; then
      echo "    pull folder (new)"
      pull_remote_folder "$remote_path" "$local_path"
    else
      echo "    pull file (new)"
      pull_remote_file "$remote_path" "$local_path"
    fi
  done

  for name in "${to_update[@]+"${to_update[@]}"}"; do
    echo "  [$name]"
    if is_remote_dir "$remote_base/$name"; then
      sync_existing_folder "$remote_base" "$name"
    else
      local remote_path="$remote_base/$name"
      local local_path="$DEST/$name"
      if [[ -f "$local_path" ]]; then
        remote_mtime="$(remote_file_epoch "$remote_path")"
        local_mtime="$(local_file_epoch "$local_path")"
        if [[ "$remote_mtime" -le "$local_mtime" ]]; then
          echo "    skip (up to date): $name"
        else
          echo "    pull file (newer on device)"
          pull_remote_file "$remote_path" "$local_path"
        fi
      else
        echo "    pull file (new)"
        pull_remote_file "$remote_path" "$local_path"
      fi
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

  echo "==> session logs ($download_base)"
  local name
  while IFS= read -r name; do
    [[ -z "$name" ]] && continue
    local remote_log="$download_base/$name/$SESSION_LOG"
    local local_log="$DEST/$name/$SESSION_LOG"
    if ! adb shell "[ -f '$remote_log' ]" >/dev/null 2>&1; then
      continue
    fi
    if [[ -f "$local_log" ]]; then
      echo "  skip (have log): $name"
      continue
    fi
    mkdir -p "$DEST/$name"
    echo "  pull log: $name/$SESSION_LOG"
    pull_remote_file "$remote_log" "$local_log"
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

  echo "==> debug cache session logs"
  local folder
  for folder in $folders; do
    [[ -z "$folder" ]] && continue
    local dest="$DEST/$folder/$SESSION_LOG"
    if [[ -f "$dest" ]]; then
      echo "  skip (have log): $folder"
      continue
    fi
    mkdir -p "$DEST/$folder"
    if adb exec-out run-as com.autobots.camera cat "cache/autobots/logs/$folder/$SESSION_LOG" >"$dest" 2>/dev/null; then
      echo "  pull log: $folder/$SESSION_LOG"
    fi
  done
  echo
}

report_missing_logs() {
  local missing=0
  local dir name
  for dir in "$DEST"/*; do
    [[ -d "$dir" ]] || continue
    name="$(basename "$dir")"
    if compgen -G "$dir/"'*.jpg' >/dev/null && [[ ! -f "$dir/$SESSION_LOG" ]]; then
      echo "  warning: $name has photos but no $SESSION_LOG"
      missing=$((missing + 1))
    fi
  done
  if [[ "$missing" -eq 0 ]]; then
    echo "  all session folders with photos have $SESSION_LOG (or no photos yet)"
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
  for root in "${remote_roots[@]}"; do
    sync_remote_root "$root"
  done

  sync_session_logs
  sync_debug_session_logs

  echo "Done. Files are in: $DEST"
  echo
  echo "Session log check:"
  report_missing_logs
}

main "$@"
