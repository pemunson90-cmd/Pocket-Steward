#!/usr/bin/env bash
# Regenerates the RAR/7z inspection fixtures from synthetic content written below.
# Tools used for the committed fixtures: RAR 6.23 (Ubuntu multiverse package rar 2:6.23-1~22.04.1; RAR 7 cannot write RAR4)
# and 7-Zip 23.01 (Ubuntu package 7zip). No user files are involved; every member is
# generated text or zero/pseudo-random filler created by this script.
# Output is not byte-reproducible (archivers embed timestamps); SHA256SUMS records the committed bytes.
set -euo pipefail
cd "$(dirname "$0")"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
out=$PWD
export TZ=UTC LANG=C.UTF-8 LC_ALL=C.UTF-8
touch_all() { find "$1" -exec touch -h -d '2026-10-01 12:00:00' {} +; }

# Nested project/release tree used by the filing regression.
p="$work/project"
mkdir -p "$p/NSTL/NSTL-1.4.0-dev18/notes" "$p/NSTL/NSTL-1.4.0-dev18/release" "$p/NSTL/NSTL-1.4.0-dev18/empty-dir"
printf '# NSTL 1.4.0-dev18\nSynthetic fixture text.\n' > "$p/NSTL/NSTL-1.4.0-dev18/README.md"
printf 'Synthetic build notes.\n' > "$p/NSTL/NSTL-1.4.0-dev18/notes/build-notes.txt"
printf 'Synthetic placeholder; not an APK.\n' > "$p/NSTL/NSTL-1.4.0-dev18/release/NSTL-1.4.0-dev18.apk"
: > "$p/NSTL/NSTL-1.4.0-dev18/notes/empty.txt"
touch_all "$p"
(cd "$p" && rar a -ma5 -rr1% -idq "$out/project-release-rar5.rar" NSTL)
(cd "$p" && rar a -ma4 -idq "$out/project-release-rar4.rar" NSTL)
(cd "$p" && rar a -ma5 -s -idq "$out/project-release-rar5-solid.rar" NSTL)
(cd "$p" && 7z a -bd -bso0 "$out/project-release.7z" NSTL)
(cd "$p" && 7z a -bd -bso0 -mhc=off "$out/project-release-plain-header.7z" NSTL)
# Encrypted headers: names are unavailable without a password (password is fixture-only).
(cd "$p" && rar a -ma5 -hpfixture -idq "$out/encrypted-headers-rar5.rar" NSTL)
(cd "$p" && rar a -ma4 -hpfixture -idq "$out/encrypted-headers-rar4.rar" NSTL)
(cd "$p" && 7z a -bd -bso0 -pfixture -mhe=on "$out/encrypted-headers.7z" NSTL)
# Encrypted contents with visible names.
(cd "$p" && rar a -ma5 -pfixture -idq "$out/encrypted-content-rar5.rar" NSTL)
(cd "$p" && rar a -ma4 -pfixture -idq "$out/encrypted-content-rar4.rar" NSTL)
(cd "$p" && 7z a -bd -bso0 -pfixture -mhe=off "$out/encrypted-content.7z" NSTL)

# Multipart volumes: 24 KiB of seeded pseudo-random filler stored uncompressed, 10 KiB volumes.
m="$work/multi"; mkdir -p "$m/Volumes"
python3 -c "import random,sys; r=random.Random(7); sys.stdout.buffer.write(bytes(r.getrandbits(8) for _ in range(24576)))" > "$m/Volumes/filler.bin"
printf 'Synthetic.\n' > "$m/Volumes/after.txt"
touch_all "$m"
(cd "$m" && rar a -ma5 -m0 -v10k -idq "$work/multi5.rar" Volumes/filler.bin Volumes/after.txt && cp "$work/multi5.part1.rar" "$out/multipart-rar5.part1.rar")
(cd "$m" && rar a -ma4 -m0 -v10k -vn -idq "$work/multi4.rar" Volumes/filler.bin Volumes/after.txt && cp "$work/multi4.rar" "$out/multipart-rar4.rar")

# Unicode member names exercise RAR4 encoded names, RAR5 UTF-8 and 7z UTF-16.
u="$work/unicode"; mkdir -p "$u/Проект/データ"
printf 'Synthetic.\n' > "$u/Проект/データ/заметки-ノート.txt"
touch_all "$u"
(cd "$u" && rar a -ma4 -idq "$out/unicode-rar4.rar" Проект)
(cd "$u" && rar a -ma5 -idq "$out/unicode-rar5.rar" Проект)
(cd "$u" && 7z a -bd -bso0 "$out/unicode.7z" Проект)

# 2,001 empty members prove the entry limit reports partial coverage.
e="$work/many"; mkdir -p "$e/many"
for i in $(seq -w 0 2000); do : > "$e/many/f$i"; done
touch_all "$e"
(cd "$e" && rar a -ma5 -idq "$out/entries-2001-rar5.rar" many)
(cd "$e" && rar a -ma4 -idq "$out/entries-2001-rar4.rar" many)
(cd "$e" && 7z a -bd -bso0 "$out/entries-2001.7z" many)

# 64 MiB of zeros compresses to a few KiB; inspection must not decode it.
z="$work/zeros"; mkdir -p "$z/Bomb"
head -c 67108864 /dev/zero > "$z/Bomb/zeros.bin"
touch_all "$z"
(cd "$z" && rar a -ma5 -m5 -idq "$out/payload-64mib-zeros-rar5.rar" Bomb)
(cd "$z" && 7z a -bd -bso0 -mx=9 "$out/payload-64mib-zeros.7z" Bomb)

# Member path longer than 500 characters.
l="$work/long"; d="$l/Long"
for i in $(seq 1 6); do d="$d/$(printf 'segment%02d-%090d' "$i" 0)"; done
mkdir -p "$d"; printf 'Synthetic.\n' > "$d/leaf.txt"
touch_all "$l"
(cd "$l" && rar a -ma5 -idq "$out/long-name-rar5.rar" Long)
(cd "$l" && 7z a -bd -bso0 "$out/long-name.7z" Long)

(cd "$out" && sha256sum *.rar *.7z > SHA256SUMS)
