#!/bin/bash

# Generate a new Android keystore for release signing
# Usage: ./generate-keystore.sh

KEYSTORE_FILE="release.keystore"
KEY_ALIAS="byd-dlink"
VALIDITY_DAYS=10000

echo "Generating Android keystore for BYD Dlink Customer..."
echo ""

# Prompt for passwords
read -s -p "Enter keystore password: " KEYSTORE_PASSWORD
echo ""
read -s -p "Confirm keystore password: " KEYSTORE_PASSWORD_CONFIRM
echo ""

if [ "$KEYSTORE_PASSWORD" != "$KEYSTORE_PASSWORD_CONFIRM" ]; then
    echo "Error: Passwords do not match"
    exit 1
fi

if [ ${#KEYSTORE_PASSWORD} -lt 6 ]; then
    echo "Error: Password must be at least 6 characters"
    exit 1
fi

# Generate keystore
keytool -genkey -v \
    -keystore "$KEYSTORE_FILE" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity "$VALIDITY_DAYS" \
    -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEYSTORE_PASSWORD" \
    -dname "CN=BYD Dlink Developer, OU=Development, O=BYD Dlink, L=Unknown, ST=Unknown, C=CN"

if [ $? -eq 0 ]; then
    echo ""
    echo "✓ Keystore generated successfully: $KEYSTORE_FILE"
    echo ""
    echo "Keystore details:"
    echo "  File: $KEYSTORE_FILE"
    echo "  Alias: $KEY_ALIAS"
    echo "  Password: $KEYSTORE_PASSWORD"
    echo ""
    echo "To convert to base64 for GitHub Secrets:"
    echo "  base64 -w 0 $KEYSTORE_FILE"
    echo ""
    echo "Required GitHub Secrets:"
    echo "  KEYSTORE_BASE64: $(base64 -w 0 "$KEYSTORE_FILE" | head -c 50)..."
    echo "  KEYSTORE_PASSWORD: $KEYSTORE_PASSWORD"
    echo "  KEY_ALIAS: $KEY_ALIAS"
    echo "  KEY_PASSWORD: $KEYSTORE_PASSWORD"
else
    echo "Error: Failed to generate keystore"
    exit 1
fi
