#!/usr/bin/env bash
set -euo pipefail

VERSION="1.61.0"
ARTIFACT="jdt-language-server-${VERSION}-202609031315.tar.gz"
URL="https://download.eclipse.org/jdtls/milestones/${VERSION}/${ARTIFACT}"
SHA256="338e7e73d61836651ba2453919a0d34fa763eb4e7c03342092309bffb8934c64"

INSTALL_DIR="${HOME}/.opencode/bin/jdtls"
PIN_FILE="${INSTALL_DIR}/.opencode-pin"
SCRATCH="$(pwd)/.tmp/jdtls"

FORCE=0
if [[ "${1:-}" == "--force" ]]; then
  FORCE=1
fi

if [[ -f "${PIN_FILE}" ]] && [[ "$(cat "${PIN_FILE}")" == "${VERSION}" ]] && [[ "${FORCE}" -eq 0 ]]; then
  echo "jdtls ${VERSION} already installed at ${INSTALL_DIR}"
  exit 0
fi

mkdir -p "${SCRATCH}"
ARCHIVE="${SCRATCH}/${ARTIFACT}"

echo "Downloading ${URL}"
curl -fsSL "${URL}" -o "${ARCHIVE}"

echo "Verifying SHA-256"
printf '%s  %s\n' "${SHA256}" "${ARCHIVE}" | shasum -a 256 --check -

echo "Extracting"
STAGE="${SCRATCH}/stage"
mkdir -p "${STAGE}"
tar -xzf "${ARCHIVE}" -C "${STAGE}"

TOP_LEVEL_COUNT="$(find "${STAGE}" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')"
TOP_LEVEL_DIR="$(find "${STAGE}" -mindepth 1 -maxdepth 1 -type d -print -quit)"

rm -rf "${INSTALL_DIR}"
if [[ "${TOP_LEVEL_COUNT}" -eq 1 && -n "${TOP_LEVEL_DIR}" ]]; then
  mv "${TOP_LEVEL_DIR}" "${INSTALL_DIR}"
else
  mkdir -p "${INSTALL_DIR}"
  find "${STAGE}" -mindepth 1 -maxdepth 1 -exec mv {} "${INSTALL_DIR}/" \;
fi
echo "${VERSION}" > "${PIN_FILE}"

echo "Installed jdtls ${VERSION} at ${INSTALL_DIR}"
