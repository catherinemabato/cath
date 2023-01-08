#!/usr/bin/env python3
"""Adds license() rules to a repository.

This tool modifies the BUILD files in a source tree to add license targets.
The resulting structure will normally be

- A license target in the top level BUILD file
- All other build files point to the top level license target via
  package.default_applicable_licenses.

The intended use is to modify a repository after downloading, but before
returning from the repository rule defining it.

"""

import argparse
from collections.abc import Sequence
import os
import re
import sys


PACKAGE_RE = re.compile(r'^package\((?P<decls>[^)]*)\)', flags=re.MULTILINE)

def trim_extension(name: str) -> str:
    """Trim the well known package extenstions."""
    for ext in ('.deb', '.jar', '.tar', '.tar.bz2', '.tar.gz', '.tgz', '.zip'):
        if name.endswith(ext):
            return name[:-len(ext)]
    return name


def guess_package_name_and_version(url: str) -> str:
    """Guess a package name from a URL."""
    # .../rules_x-2.3.5.tgz => rules_x
    basename = trim_extension(url.split('/')[-1])
    name = basename
    version = None
    parts = basename.split('-')
    if parts[-1][0].isdigit():
      name = '-'.join(parts[0:-1])
      version = parts[-1]
    print(url, '=>', name, version)
    return name, version


class BuildRewriter(object):

    def __init__(self,
                 top: str,
                 copyright_notice: str = None,
                 license_file: str = None,
                 license_kinds: Sequence[str] = [],
                 package_name: str = None,
                 package_url: str = None,
                 package_version: str = None):
        if package_url:
            p_name, p_version = guess_package_name_and_version(package_url)
        else:
            p_name = None
            p_version = None
        self.top = top
        self.copyright_notice = copyright_notice
        self.license_file = license_file
        self.license_kinds = license_kinds
        self.package_name = package_name or p_name
        self.package_url = package_url
        self.package_version = package_version or p_version

        # Collected by scanning the tree
        self.top_build = None
        self.other_builds = []
        self.license_files = []

    def print(self):
        print('top:', self.top)
        print('license_file:', self.license_file)
        print('license_kinds:', self.license_kinds)
        print('package_name:', self.package_name)
        print('package_url:', self.package_url)
        print('package_version:', self.package_version)
        print('top_build:', self.top_build)
        print('other_builds:', self.other_builds)

    def read_source_tree(self):
        # Gather BUILD and license files
        for root, dirs, files in os.walk(self.top):
            # Do not traverse into .git tree
            if '.git' in dirs:
                dirs.remove('.git')
            found_license = False
            notices = []
            for f in files:
                if f in ('BUILD', 'BUILD.bazel'):
                    build_path = os.path.join(root, f)
                    if root == self.top: 
                        self.top_build = build_path
                    else:
                        self.other_builds.append(build_path)
                if f.upper().startswith('LICENSE'):
                    found_license = True
                    self.license_files.append(os.path.join(root, f))
                if f.upper().startswith('NOTICE'):
                    notices.append(os.path.join(root, f))

                # FUTURE: Make this extensible so that users can add their own
                # scanning code. To modif

            if not found_license and notices:
                self.license_files.extend(notices)

    def point_to_top_level_license(self, build_file: str):
        # Points the BUILD file at a path to the top level liceense declaration
        with open(build_file, 'r') as inp:
            content = inp.read()
        new_content = add_default_applicable_licenses(content, "//:license")
        if content != new_content:
            os.remove(build_file)
            with open(build_file, 'w') as out:
               out.write(new_content)

    def select_license_file(self):
        if self.license_file:
            return
        if len(self.license_files) == 1:
            self.license_file = self.license_files[0]
            return
        print('Warning: package %s at %s contains multiple potential license files.' %
              (self.package_name, self.top))
        print('  ', str(self.license_files))

    def create_license_target(self) -> str:
        """Creates the text of a license() target for this package."""

        target = [
            'license(',
            '    name = "license",',
        ]
        if self.copyright_notice:
            target.append('    copyright_notice = "%s",' % self.copyright_notice)
        if self.license_file:
            target.append('    license_text = "%s",' % self.license_file)
        if self.license_kinds:
            target.append('    license_kinds = [')
            for kind in self.license_kinds:
                target.append('        "%s",' % kind)
            target.append('    ],')
        if self.package_name:
            target.append('    package_name = "%s",' % self.package_name)
        if self.package_version:
            target.append('    package_version = "%s",' % self.package_version)
        if self.package_url:
            target.append('    package_url = "%s",' % self.package_url)
        target.append(')')
        return '\n'.join(target)


def add_default_applicable_licenses(content: str, license_label: str) -> str:
    """Add a default_applicable_licenses clause to the package().

    Do not add if there already is one.
    Move package() to the first non-load statement.
    """
    # Build what the package statement should contain
    dal = 'default_applicable_licenses="%s"' % license_label
    m = PACKAGE_RE.search(content)
    if m:
        decls = m.group('decls')
        if decls.find('default_applicable_licenses') >= 0:
            # Do nothing
            return content

        package_decl = '\n'.join([
            'package(',
            '    ' + decls.strip().rstrip(',') + ',',
            '    ' + dal,
            ')'])
        span = m.span(0)
        content = content[0:span[0]] + content[span[1]:]
    else:
        package_decl = 'package(%s)' % dal

    # Now splice it into the correct place. That is always before
    # any existing rules.
    ret = []
    package_added = False
    for line in content.split('\n'):
        if not package_added:
            t = line.strip()
            if (t
                and not line.startswith(' ')
                and not t.startswith('#')
                and not t.startswith(')')
                and not t.startswith('load')):
                  ret.append('')
                  ret.append(package_decl)
                  package_added = True
        ret.append(line)
    return '\n'.join(ret)


def add_license(build_file: str, license_target: str):
    # Points the BUILD file at a path to the top level liceense declaration
    with open(build_file, 'r') as inp:
        content = inp.read()

    # Do not overwrite an existing one
    # TBD: We obviously have to be able to.
    if '\nlicense(' in content:  # )
        return 

    new_content = add_default_applicable_licenses(content, "//:license")
    # Now splice it into the correct place. That is always before
    # any existing rules.
    ret = []
    license_added = False
    for line in new_content.split('\n'):
        if not license_added:
            t = line.strip()
            if (t
                and not line.startswith(' ')
                and not t.startswith('#')
                and not t.startswith(')')
                and not t.startswith('load')
                and not t.startswith('package')):
                  ret.append('')
                  ret.append(license_target)
                  license_added = True
        ret.append(line)
    new_content = '\n'.join(ret)

    if content != new_content:
        os.remove(build_file)
        with open(build_file, 'w') as out:
           out.write(new_content)



def main(argv: Sequence[str]) -> None:
    parser = argparse.ArgumentParser(description='Add license targets')
    parser.add_argument('--top', type=str, help='Top of source tree')
    parser.add_argument(
        '--copyright_notice', type=str,
        help='One line copyright notice.')
    parser.add_argument(
        '--license_file', type=str,
        help='Felative path from top of source tree to LICENSE file.' +
             ' If not specified, try to auto-detect it.')
    parser.add_argument(
        '--license_kind', type=str, action="append",
        help='The label of a license kind.')
    parser.add_argument(
        '--package_name', type=str, 
        help='The package name. If not specified, try to extract from package_url.')
    parser.add_argument('--package_url', type=str, help='The package URL')
    parser.add_argument(
        '--package_version', type=str, 
        help='The package version. If not specified, try to extract from package_url.')

    args = parser.parse_args()

    rewriter = BuildRewriter(
        top=args.top or '.',
        copyright_notice = args.copyright_notice,
        license_file = args.license_file,
        license_kinds = args.license_kind,
        package_url = args.package_url,
        package_name = args.package_name,
        package_version = args.package_version)
    rewriter.read_source_tree()
    rewriter.select_license_file()
    print(rewriter.create_license_target())

    for build_file in rewriter.other_builds:
        rewriter.point_to_top_level_license(build_file)
    rewriter.print()

if __name__ == '__main__':
    os.system('date >>/tmp/finalize.txt')
    main(sys.argv)
