#!/bin/sh
# Optional: the Pre's own typeface for a build of your own.
#
# Prelude is HP's, under the "Open webOS Software Font License", which allows it on webOS
# devices and Open webOS derivatives only. So it is not in this repository, and an APK built
# with it is for your own use, not for passing on. Without it the keyboard uses Android's bold
# sans and works the same.
#
# This takes Prelude-Bold.ttf and the licence from LuneOS's luna-init, which ships them.
set -eu
dest="$(dirname "$0")/../app/src/main/assets/fonts"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -sSfL https://github.com/webOS-ports/luna-init/raw/HEAD/files/conf/fonts/fonts.tgz -o "$tmp/fonts.tgz"
tar -xzf "$tmp/fonts.tgz" -C "$tmp" Prelude-Bold.ttf Prelude_Font_LICENSE.txt
mkdir -p "$dest"
cp "$tmp/Prelude-Bold.ttf" "$tmp/Prelude_Font_LICENSE.txt" "$dest/"
echo "Prelude-Bold.ttf -> $dest (read Prelude_Font_LICENSE.txt there)"
