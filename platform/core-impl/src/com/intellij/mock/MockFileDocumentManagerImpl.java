// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.mock;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

public class MockFileDocumentManagerImpl extends FileDocumentManager {
  private static final Key<VirtualFile> MOCK_VIRTUAL_FILE_KEY = Key.create("MockVirtualFile");
  private final Function<? super CharSequence, ? extends Document> myFactory;
  private final @Nullable Key<Document> myCachedDocumentKey;

  public MockFileDocumentManagerImpl(@Nullable Key<Document> cachedDocumentKey,
                                     @NotNull Function<? super CharSequence, ? extends Document> factory) {
    myFactory = factory;
    myCachedDocumentKey = cachedDocumentKey;
  }

  private static final Key<Document> MOCK_DOC_KEY = Key.create("MOCK_DOC_KEY");

  private static boolean isBinaryWithoutDecompiler(VirtualFile file) {
    final FileType ft = file.getFileType();
    return ft.isBinary() && BinaryFileTypeDecompilers.getInstance().forFileType(ft) == null;
  }

  @Override
  public Document getDocument(@NotNull VirtualFile file) {
    if (file.isDirectory() || isBinaryWithoutDecompiler(file)) return null;
    return ConcurrencyUtil.computeIfAbsent(file, MOCK_DOC_KEY, () -> {
      CharSequence text = LoadTextUtil.loadText(file);
      Document document = myFactory.fun(text);
      document.putUserData(MOCK_VIRTUAL_FILE_KEY, file);
      return document;
    });
  }

  @Override
  public Document getCachedDocument(@NotNull VirtualFile file) {
    if (myCachedDocumentKey != null) {
      return file.getUserData(myCachedDocumentKey);
    }
    return file.getUserData(MOCK_DOC_KEY);
  }

  @Override
  public VirtualFile getFile(@NotNull Document document) {
    return document.getUserData(MOCK_VIRTUAL_FILE_KEY);
  }

  @Override
  public void saveAllDocuments() {
  }

  @Override
  public void saveDocuments(@NotNull Predicate<? super Document> filter) {
  }

  @Override
  public void saveDocument(@NotNull Document document) {
  }

  @Override
  public void saveDocumentAsIs(@NotNull Document document) {
  }

  @Override
  public Document @NotNull [] getUnsavedDocuments() {
    return Document.EMPTY_ARRAY;
  }

  @Override
  public boolean isDocumentUnsaved(@NotNull Document document) {
    return false;
  }

  @Override
  public boolean isFileModified(@NotNull VirtualFile file) {
    return false;
  }

  @Override
  public boolean isPartialPreviewOfALargeFile(@NotNull Document document) {
    return false;
  }

  @Override
  public void reloadFromDisk(@NotNull Document document) {
  }

  @Override
  public void reloadFromDisk(@NotNull Document document, @Nullable Project project) {
  }

  @Override
  public void reloadFiles(final VirtualFile @NotNull ... files) {
  }

  @Override
  public @NotNull String getLineSeparator(VirtualFile file, Project project) {
    return "";
  }

  @Override
  public boolean requestWriting(@NotNull Document document, @Nullable Project project) {
    return true;
  }
}
