// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.postfix.templates;

import com.intellij.codeInsight.template.postfix.templates.editable.JavaEditablePostfixTemplate;
import com.intellij.codeInsight.template.postfix.templates.editable.JavaPostfixTemplateExpressionCondition;
import com.intellij.openapi.project.DumbAware;
import com.intellij.pom.java.LanguageLevel;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

public class LocalDateToJavaSqlDatePostfixTemplate extends JavaEditablePostfixTemplate implements DumbAware {
  public LocalDateToJavaSqlDatePostfixTemplate(@NotNull JavaPostfixTemplateProvider provider) {
    super("toSqlDate",
          "java.sql.Date.valueOf($EXPR$)$END$",
          "java.sql.Date.valueOf(exp)",
          Collections.singleton(
            new JavaPostfixTemplateExpressionCondition.JavaPostfixTemplateExpressionFqnCondition("java.time.LocalDate")),
          LanguageLevel.JDK_1_8, false, provider);
  }

  @Override
  public boolean isBuiltin() {
    return true;
  }
}
