/*
 * Copyright 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.coredirectives;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.jssrc.restricted.JsExpr;
import com.google.template.soy.shared.AbstractSoyPrintDirectiveTestCase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for IdDirective.
 *
 */
@RunWith(JUnit4.class)
public class IdDirectiveTest extends AbstractSoyPrintDirectiveTestCase {

  @Test
  public void testApplyForTofu() {

    IdDirective idDirective = new IdDirective();
    assertTofuOutput("", "", idDirective);
    assertTofuOutput("identName", "identName", idDirective);
    // TODO: Why are we asserting that it does something obviously bad?
    assertTofuOutput("<>&'\" \\", "<>&'\" \\", idDirective);
  }

  @Test
  public void testApplyForJsSrc() {

    IdDirective idDirective = new IdDirective();
    JsExpr dataRef = new JsExpr("opt_data.myKey", Integer.MAX_VALUE);
    assertThat(idDirective.applyForJsSrc(dataRef, ImmutableList.<JsExpr>of()).getText())
        .isEqualTo("opt_data.myKey");
  }
}
