/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.psi.impl.source.tree;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.tree.IElementType;

import static com.intellij.psi.xml.XmlElementType.XML_FILE;

public class XmlFileElement extends FileElement {
  private static final Logger LOG = Logger.getInstance(XmlFileElement.class);

  public XmlFileElement(CharSequence text) {
    super(XML_FILE, text);
  }

  public XmlFileElement(IElementType type, CharSequence text) {
    super(type, text);
  }
}
