#!/usr/bin/env bash
# PDFchemy Universal Standalone AppImage Builder
set -e

echo "==================================================="
echo "Building PDFchemy Universal Linux AppImage"
echo "==================================================="

APP_DIR="build/AppDir"
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR/usr/bin"
mkdir -p "$APP_DIR/usr/lib/pdfchemy"
mkdir -p "$APP_DIR/usr/share/applications"
mkdir -p "$APP_DIR/usr/share/icons/hicolor/512x512/apps"

# 1. Build Compose Desktop raw binary distribution
./gradlew :desktop:createDistributable

# 2. Copy application files into AppDir
cp -r desktop/build/compose/binaries/main/app/* "$APP_DIR/usr/lib/pdfchemy/"
ln -s "/usr/lib/pdfchemy/bin/pdfchemy" "$APP_DIR/usr/bin/pdfchemy"

# 3. Desktop Entry and Icons
cp desktop/src/main/resources/linux/com.pdfchemy.PDFchemy.desktop "$APP_DIR/usr/share/applications/pdfchemy.desktop"
cp desktop/src/main/resources/icons/linux/icon.png "$APP_DIR/usr/share/icons/hicolor/512x512/apps/pdfchemy.png"
cp desktop/src/main/resources/linux/com.pdfchemy.PDFchemy.desktop "$APP_DIR/pdfchemy.desktop"
cp desktop/src/main/resources/icons/linux/icon.png "$APP_DIR/pdfchemy.png"

# 4. AppRun Entry Script
cat << 'EOF' > "$APP_DIR/AppRun"
#!/bin/sh
HERE="$(dirname "$(readlink -f "${0}")")"
exec "${HERE}/usr/lib/pdfchemy/bin/pdfchemy" "$@"
EOF
chmod +x "$APP_DIR/AppRun"

# 5. Download appimagetool if not present
if [ ! -f "appimagetool-x86_64.AppImage" ]; then
    echo "Downloading appimagetool..."
    wget -q "https://github.com/AppImage/AppImageKit/releases/download/13/appimagetool-x86_64.AppImage"
    EXPECTED_SHA256="df3baf5ca5facbecfea28400030560a631f49ce1742df152eb7a13d80e816a7f"
    ACTUAL_SHA256=$(sha256sum appimagetool-x86_64.AppImage | awk '{print $1}')
    if [ "$EXPECTED_SHA256" != "$ACTUAL_SHA256" ]; then
        echo "ERROR: appimagetool hash mismatch! Expected: $EXPECTED_SHA256, got: $ACTUAL_SHA256"
        rm appimagetool-x86_64.AppImage
        exit 1
    fi
    chmod +x appimagetool-x86_64.AppImage
fi

# 6. Generate final AppImage
ARCH=x86_64 ./appimagetool-x86_64.AppImage "$APP_DIR" "PDFchemy-x86_64.AppImage"

echo "==================================================="
echo "SUCCESS! Standalone AppImage created:"
echo "  $(pwd)/PDFchemy-x86_64.AppImage"
echo "==================================================="
