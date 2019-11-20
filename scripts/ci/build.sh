#!/bin/bash

# Copyright 2015 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -eu

# Main deploy functions for the continous build system
# Just source this file and use the various method:
#   bazel_build build bazel and run all its test
#   bazel_release use the artifact generated by bazel_build and push
#     them to github for a release and to GCS for a release candidate.
#     Also prepare an email for announcing the release.

# Load common.sh
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$(dirname ${SCRIPT_DIR})/release/common.sh"

if ! command -v gsutil &>/dev/null; then
  echo "Required tool 'gsutil' not found. Please install it:"
  echo "See https://cloud.google.com/sdk/downloads for instructions."
  exit 1
fi
if ! command -v github-release &>/dev/null; then
  echo "Required tool 'github-release' not found. Download it from here:"
  echo "https://github.com/c4milo/github-release/releases"
  echo "Just extract the archive and put the binary on your PATH."
  exit 1
fi
if ! command -v debsign &>/dev/null; then
  echo "Required tool 'debsign' not found. Please install it via apt-get:"
  echo "apt-get install devscripts"
  exit 1
fi
if ! command -v reprepro &>/dev/null; then
  echo "Required tool 'reprepro' not found. Please install it via apt-get:"
  echo "apt-get install reprepro"
  exit 1
fi
if ! command -v gpg &>/dev/null; then
  echo "Required tool 'gpg' not found. Please install it via apt-get:"
  echo "apt-get install gnupg"
  exit 1
fi
if ! command -v pandoc &>/dev/null; then
  echo "Required tool 'pandoc' not found. Please install it via apt-get:"
  echo "apt-get install pandoc"
  exit 1
fi
# if ! command -v ssmtp &>/dev/null; then
#   echo "Required tool 'ssmtp' not found. Please install it via apt-get:"
#   echo "apt-get install ssmtp"
#   exit 1
# fi

export APT_GPG_KEY_ID=$(gsutil cat gs://bazel-trusted-encrypted-secrets/release-key.gpg.id)

# Generate a string from a template and a list of substitutions.
# The first parameter is the template name and each subsequent parameter
# is taken as a couple: first is the string the substitute and the second
# is the result of the substitution.
function generate_from_template() {
  local value="$1"
  shift
  while (( $# >= 2 )); do
    value="${value//$1/$2}"
    shift 2
  done
  echo "${value}"
}

# Generate the email for the release.
# The first line of the output will be the recipient, the second line
# the mail subjects and the subsequent lines the mail, its content.
# If no planed release, then this function output will be empty.
function generate_email() {
  RELEASE_CANDIDATE_URL="https://releases.bazel.build/%release_name%/rc%rc%/index.html"
  RELEASE_URL="https://github.com/bazelbuild/bazel/releases/tag/%release_name%"

  local release_name=$(get_release_name)
  local rc=$(get_release_candidate)
  local args=(
      "%release_name%" "${release_name}"
      "%rc%" "${rc}"
      "%relnotes%" "# $(get_full_release_notes)"
  )
  if [ -n "${rc}" ]; then
    args+=(
        "%url%" "$(generate_from_template "${RELEASE_CANDIDATE_URL}" "${args[@]}")"
    )
    generate_from_template \
        "$(cat "${SCRIPT_DIR}/rc_email.txt")" \
        "${args[@]}"
  elif [ -n "${release_name}" ]; then
    args+=(
        "%url%" "$(generate_from_template "${RELEASE_URL}" "${args[@]}")"
    )
    generate_from_template \
        "$(cat "${SCRIPT_DIR}/release_email.txt")" "${args[@]}"
  fi
}

function get_release_page() {
    echo "# $(get_full_release_notes)"'

_Notice_: Bazel installers contain binaries licensed under the GPLv2 with
Classpath exception. Those installers should always be redistributed along with
the source code.

Some versions of Bazel contain a bundled version of OpenJDK. The license of the
bundled OpenJDK and other open-source components can be displayed by running
the command `bazel license`. The vendor and version information of the bundled
OpenJDK can be displayed by running the command `bazel info java-runtime`.
The binaries and source-code of the bundled OpenJDK can be
[downloaded from our mirror server](https://mirror.bazel.build/openjdk/index.html).

_Security_: All our binaries are signed with our
[public key](https://bazel.build/bazel-release.pub.gpg) 3D5919B448457EE0.
'
}

# Deploy a github release using a third party tool:
#   https://github.com/c4milo/github-release
# This methods expects the following arguments:
#   $1..$n files generated by package_build (should not contains the README file)
# Please set GITHUB_TOKEN to talk to the Github API.
function release_to_github() {
  local artifact_dir="$1"

  local release_name=$(get_release_name)
  local rc=$(get_release_candidate)

  if [ -n "${release_name}" ] && [ -z "${rc}" ]; then
    local github_token="$(gsutil cat gs://bazel-trusted-encrypted-secrets/github-trusted-token.enc | \
        gcloud kms decrypt --project bazel-public --location global --keyring buildkite --key github-trusted-token --ciphertext-file - --plaintext-file -)"

    GITHUB_TOKEN="${github_token}" github-release "bazelbuild/bazel" "${release_name}" "" "$(get_release_page)" "${artifact_dir}/*"
  fi
}

# Creates an index of the files contained in folder $1 in Markdown format.
function create_index_md() {
  # First, add the release notes
  get_release_page
  # Then, add the list of files
  echo
  echo "## Index of files"
  echo
  for f in $1/*.sha256; do  # just list the sha256 ones
    local filename=$(basename $f .sha256);
    echo " - [${filename}](${filename}) [[SHA-256](${filename}.sha256)] [[SIG](${filename}.sig)]"
  done
}

# Creates an index of the files contained in folder $1 in HTML format.
function create_index_html() {
  create_index_md "${@}" | pandoc -f markdown -t html
}

# Deploy a release candidate to Google Cloud Storage.
# It requires to have gsutil installed. You can force the path to gsutil
# by setting the GSUTIL environment variable.
# This methods expects the following arguments:
#   $1..$n files generated by package_build
function release_to_gcs() {
  local artifact_dir="$1"

  local release_name="$(get_release_name)"
  local rc="$(get_release_candidate)"

  if [ -n "${release_name}" ]; then
    local release_path="${release_name}/release"
    if [ -n "${rc}" ]; then
      release_path="${release_name}/rc${rc}"
    fi
    create_index_html "${artifact_dir}" > "${artifact_dir}/index.html"
    gsutil -m cp "${artifact_dir}/**" "gs://bazel/${release_path}"
  fi
}

function ensure_gpg_secret_key_imported() {
  if ! gpg --list-secret-keys | grep "${APT_GPG_KEY_ID}" > /dev/null; then
    keyfile=$(mktemp --tmpdir)
    chmod 0600 "${keyfile}"
    gsutil cat "gs://bazel-trusted-encrypted-secrets/release-key.gpg.enc" | \
        gcloud kms decrypt --location "global" --keyring "buildkite" --key "bazel-release-key" --ciphertext-file "-" --plaintext-file "${keyfile}"
    gpg --allow-secret-key-import --import "${keyfile}"
    rm -f "${keyfile}"
  fi

  # Make sure we use stronger digest algorithm。
  # We use reprepro to generate the debian repository,
  # but there's no way to pass flags to gpg using reprepro, so writting it into
  # ~/.gnupg/gpg.conf
  if ! grep "digest-algo sha256" ~/.gnupg/gpg.conf > /dev/null; then
    echo "digest-algo sha256" >> ~/.gnupg/gpg.conf
  fi
}

# Generate new content of Release file
function print_new_release_content() {
  local distribution="$1"
  # Print the headers of the original Release file
  head -n 7 dists/${1}/Release 2>>1
  metadata_files=("jdk1.8/binary-amd64/Packages" "jdk1.8/binary-amd64/Packages.gz" "jdk1.8/binary-amd64/Release" "jdk1.8/source/Sources.gz" "jdk1.8/source/Release")
  # Re-generate hashes for all metadata fiels
  echo MD5Sum:
   for file in ${metadata_files[*]}; do
    path="dists/${distribution}/$file"
    echo "" "$(md5sum ${path} | cut -d " " -f1)" "$(ls -l ${path} | cut -d " " -f5)" "$file"
   done
  echo SHA1:
   for file in ${metadata_files[*]}; do
    path="dists/${distribution}/$file"
    echo "" "$(sha1sum ${path} | cut -d " " -f1)" "$(ls -l ${path} | cut -d " " -f5)" "$file"
   done
  echo SHA256:
   for file in ${metadata_files[*]}; do
    path="dists/${distribution}/$file"
    echo "" "$(sha256sum ${path} | cut -d " " -f1)" "$(ls -l ${path} | cut -d " " -f5)" "$file"
   done
}

# Merge metadata with previous distribution
function merge_previous_dists() {
  local distribution="$1"
  # Download the metadata info from previous distrubution
  mkdir -p previous
  gsutil -m cp -r "gs://bazel-apt/dists" "./previous"

  # Merge Packages and Packages.gz file
  cat "previous/dists/${distribution}/jdk1.8/binary-amd64/Packages" >> "dists/${distribution}/jdk1.8/binary-amd64/Packages"
  gzip -9c "dists/${distribution}/jdk1.8/binary-amd64/Packages" > "dists/${distribution}/jdk1.8/binary-amd64/Packages.gz"

  # Merge Sources.gz file
  gunzip "previous/dists/${distribution}/jdk1.8/source/Sources.gz"
  gunzip "dists/${distribution}/jdk1.8/source/Sources.gz"
  cat "previous/dists/${distribution}/jdk1.8/source/Sources" >> "dists/${distribution}/jdk1.8/source/Sources"
  gzip -9c "dists/${distribution}/jdk1.8/source/Sources" > "dists/${distribution}/jdk1.8/source/Sources.gz"
  rm -f "dists/${distribution}/jdk1.8/source/Sources"

  # Update Release file
  print_new_release_content "${distribution}" > "dists/${distribution}/Release.new"
  mv "dists/${distribution}/Release.new" "dists/${distribution}/Release"

  # Generate new signatures for Release file
  rm -f "dists/stable/InRelease" "dists/stable/Release.gpg"
  gpg --output "dists/stable/InRelease" --clear-sign "dists/stable/Release"
  gpg --output "dists/stable/Release.gpg" --detach-sign "dists/stable/Release"
}

# Create a debian package with version in package name and add it to the repo
function add_versioned_deb_pkg() {
  local deb_pkg_name="$1"
  # Extract the original package
  mkdir -p deb-output
  dpkg-deb -R "${deb_pkg_name}" deb-output

  # Get bazel version
  bazel_version=$(grep "Version:" deb-output/DEBIAN/control | cut -d " " -f2)
  bazel_version=${bazel_version/\~/}

  # Change package name to bazel-{bazel_version}
  versioned_deb_pkg_name="bazel-${bazel_version}-versioned-package-amd64.deb"
  sed -i "s/Package:\ bazel/Package:\ bazel-${bazel_version}/g" "deb-output/DEBIAN/control"

  # Delete conffiles, bash completion files and bash wrapper to avoid conflict when mulitple versions are installed.
  rm "deb-output/DEBIAN/conffiles"
  rm -r "deb-output/etc"
  rm -f "deb-output/usr/bin/bazel"

  # Rename the actual Bazel binary to bazel-${bazel_version}
  mv "deb-output/usr/bin/bazel-real" "deb-output/usr/bin/bazel-${bazel_version}"

  # Re-pack the debian package and add it to the repo
  dpkg-deb -b deb-output "${versioned_deb_pkg_name}"
  reprepro -C jdk1.8 includedeb "${distribution}" "${versioned_deb_pkg_name}"
}

function create_apt_repository() {
  mkdir conf
  cat > conf/distributions <<EOF
Origin: Bazel Authors
Label: Bazel
Codename: stable
Architectures: amd64 source
Components: jdk1.8
Description: Bazel APT Repository
DebOverride: override.stable
DscOverride: override.stable
SignWith: ${APT_GPG_KEY_ID}

Origin: Bazel Authors
Label: Bazel
Codename: testing
Architectures: amd64 source
Components: jdk1.8
Description: Bazel APT Repository
DebOverride: override.testing
DscOverride: override.testing
SignWith: ${APT_GPG_KEY_ID}
EOF

  cat > conf/options <<EOF
verbose
ask-passphrase
basedir .
EOF

  # TODO(#2264): this is a quick workaround #2256, figure out a correct fix.
  cat > conf/override.stable <<EOF
bazel     Section     contrib/devel
bazel     Priority    optional
EOF
  cat > conf/override.testing <<EOF
bazel     Section     contrib/devel
bazel     Priority    optional
EOF

  ensure_gpg_secret_key_imported

  local distribution="$1"
  local deb_pkg_name="$2"
  local deb_dsc_name="$3"

  debsign -k "${APT_GPG_KEY_ID}" "${deb_dsc_name}"

  reprepro -C jdk1.8 includedeb "${distribution}" "${deb_pkg_name}"
  reprepro -C jdk1.8 includedsc "${distribution}" "${deb_dsc_name}"

  add_versioned_deb_pkg "${deb_pkg_name}"

  merge_previous_dists "${distribution}"

  gsutil -m cp -r dists pool "gs://bazel-apt"
}

function release_to_apt() {
  local artifact_dir="$1"

  local release_name="$(get_release_name)"
  local rc="$(get_release_candidate)"

  if [ -n "${release_name}" ]; then
    local release_label="$(get_full_release_name)"
    local deb_pkg_name="${release_name}/bazel_${release_label}-linux-x86_64.deb"
    local deb_dsc_name="${release_name}/bazel_${release_label}.dsc"
    local deb_tar_name="${release_name}/bazel_${release_label}.tar.gz"

    pushd "${artifact_dir}"
    if [ -n "${rc}" ]; then
      create_apt_repository testing "${deb_pkg_name}" "${deb_dsc_name}"
    else
      create_apt_repository stable "${deb_pkg_name}" "${deb_dsc_name}"
    fi
    popd
  fi
}

# A wrapper around the release deployment methods.
function deploy_release() {
  local release_label="$(get_full_release_name)"
  local release_name="$(get_release_name)"

  if [[ ! -d $1 ]]; then
    echo "Usage: deploy_release ARTIFACT_DIR"
    exit 1
  fi
  artifact_dir="$1"

  if [[ -z $release_name ]]; then
    echo "Could not get the release name - are you in a release branch directory?"
    exit 1
  fi

  ensure_gpg_secret_key_imported

  rm -f "${artifact_dir}"/*.{sha256,sig}
  for file in "${artifact_dir}"/*; do
    (cd "${artifact_dir}" && sha256sum "$(basename "${file}")" > "${file}.sha256")
    gpg --no-tty --detach-sign -u "${APT_GPG_KEY_ID}" "${file}"
  done

  apt_working_dir="$(mktemp -d --tmpdir)"
  echo "apt_working_dir = ${apt_working_dir}"
  mkdir "${apt_working_dir}/${release_name}"
  cp "${artifact_dir}/bazel_${release_label}-linux-x86_64.deb" "${apt_working_dir}/${release_name}"
  cp "${artifact_dir}/bazel_${release_label}.dsc" "${apt_working_dir}/${release_name}"
  cp "${artifact_dir}/bazel_${release_label}.tar.gz" "${apt_working_dir}/${release_name}"
  release_to_apt "${apt_working_dir}"

  gcs_working_dir="$(mktemp -d --tmpdir)"
  echo "gcs_working_dir = ${gcs_working_dir}"
  cp "${artifact_dir}"/* "${gcs_working_dir}"
  release_to_gcs "${gcs_working_dir}"

  github_working_dir="$(mktemp -d --tmpdir)"
  echo "github_working_dir = ${github_working_dir}"
  cp "${artifact_dir}"/* "${github_working_dir}"
  rm -f "${github_working_dir}/bazel_${release_label}"*.{dsc,tar.gz}{,.sha256,.sig}
  release_to_github "${github_working_dir}"
}
