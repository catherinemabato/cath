// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.actions;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.cmdline.Label;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test for {@link Actions}. */
@RunWith(JUnit4.class)
public class ActionsTest {

  @Test
  public void testEscapeLabelGolden() {
    // Fix a particular encoding in case users hardcode paths generated by it.
    assertThat(Actions.escapeLabel(Label.parseCanonicalUnchecked("//pa-t_h/to/pkg:dir/na-m_e")))
        .isEqualTo("pa-t_Uh_Sto_Spkg_Cdir_Sna-m_Ue");
    assertThat(Actions.escapeLabel(Label.parseCanonicalUnchecked("//:name"))).isEqualTo("_Cname");
  }

  @Test
  public void testEscapeLabelDifferentRepos() {
    // Fix a particular encoding in case users hardcode paths generated by it.
    assertThat(Actions.escapeLabel(Label.parseCanonicalUnchecked("@@repo_1//:target")))
        .isNotEqualTo(Actions.escapeLabel(Label.parseCanonicalUnchecked("@@repo_2//:target")));
  }
}
