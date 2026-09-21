#!/bin/bash
# Setup script: Download and configure JDK 21 for building the plugin

set -e

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}Setting up JDK 21 for BEAR.Sunday IntelliJ Plugin build...${NC}"

# Create installation directory
JAVA_INSTALL_DIR="$HOME/java-installs"
mkdir -p "$JAVA_INSTALL_DIR"
cd "$JAVA_INSTALL_DIR"

# Check if already installed
if [ -d "jdk-21.0.5+11/Contents/Home" ]; then
    echo -e "${GREEN}✓ JDK 21 already installed at $JAVA_INSTALL_DIR/jdk-21.0.5+11${NC}"
    export JAVA_HOME="$JAVA_INSTALL_DIR/jdk-21.0.5+11/Contents/Home"
    $JAVA_HOME/bin/java -version
    exit 0
fi

# Download JDK 21 (Temurin) - macOS x64
JDK_ARCHIVE="OpenJDK21U-jdk_x64_mac_hotspot_21.0.5_11.tar.gz"
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/$JDK_ARCHIVE"

if [ ! -f "$JDK_ARCHIVE" ]; then
    echo -e "${BLUE}Downloading JDK 21 (Temurin)...${NC}"
    curl -L -O "$JDK_URL"
    echo -e "${GREEN}✓ Downloaded${NC}"
else
    echo -e "${GREEN}✓ JDK archive already present${NC}"
fi

# Extract
echo -e "${BLUE}Extracting JDK 21...${NC}"
tar -xzf "$JDK_ARCHIVE"
echo -e "${GREEN}✓ Extracted${NC}"

# Verify installation
export JAVA_HOME="$JAVA_INSTALL_DIR/jdk-21.0.5+11/Contents/Home"
echo -e "${BLUE}Verifying JDK installation:${NC}"
$JAVA_HOME/bin/java -version

echo ""
echo -e "${GREEN}✓ Setup complete!${NC}"
echo ""
echo "To build the plugin, run:"
echo "  export JAVA_HOME='$JAVA_HOME'"
echo "  ./gradlew buildPlugin"
echo ""
echo "Or add to your shell profile (~/.zshrc or ~/.bash_profile):"
echo "  export JAVA_HOME='$JAVA_HOME'"
