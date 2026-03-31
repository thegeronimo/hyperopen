#!/usr/bin/env bash
set -euo pipefail

JAVA_VERSION="${JAVA_VERSION:-21}"
JAVA_CACHE_ROOT="${HOME}/.local/java"
JAVA_HOME_DEFAULT="${JAVA_CACHE_ROOT}/jdk-${JAVA_VERSION}"

install_java() {
  local version="$1"
  local cache_root="$2"
  local java_home="$3"
  local download_url="https://api.adoptium.net/v3/binary/latest/${version}/ga/linux/x64/jdk/hotspot/normal/eclipse"
  local temp_dir
  local archive_path
  local extracted_dir

  mkdir -p "${cache_root}"
  temp_dir="$(mktemp -d)"
  archive_path="${temp_dir}/jdk.tar.gz"

  echo "[pages-build] Downloading Temurin JDK ${version}..."
  curl --fail --silent --show-error --location "${download_url}" -o "${archive_path}"
  tar -xzf "${archive_path}" -C "${temp_dir}"

  extracted_dir="$(
    find "${temp_dir}" -mindepth 1 -maxdepth 1 -type d | head -n 1
  )"

  if [ -z "${extracted_dir}" ]; then
    echo "[pages-build] Failed to extract JDK ${version}." >&2
    rm -rf "${temp_dir}"
    exit 1
  fi

  rm -rf "${java_home}"
  mv "${extracted_dir}" "${java_home}"
  rm -rf "${temp_dir}"
}

if command -v java >/dev/null 2>&1; then
  echo "[pages-build] Using system Java."
else
  if [ ! -x "${JAVA_HOME_DEFAULT}/bin/java" ]; then
    install_java "${JAVA_VERSION}" "${JAVA_CACHE_ROOT}" "${JAVA_HOME_DEFAULT}"
  fi

  export JAVA_HOME="${JAVA_HOME_DEFAULT}"
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

echo "[pages-build] Node: $(node --version)"
echo "[pages-build] npm: $(npm --version)"
echo "[pages-build] Java: $(java --version | head -n 1)"

npm run build
