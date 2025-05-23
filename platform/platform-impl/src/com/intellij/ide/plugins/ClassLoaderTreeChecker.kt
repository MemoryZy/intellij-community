// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.plugins

import com.intellij.ide.plugins.cl.PluginClassLoader
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.containers.WeakList

private val LOG = logger<ClassLoaderTreeChecker>()

internal class ClassLoaderTreeChecker(private val unloadedMainDescriptor: IdeaPluginDescriptorImpl,
                                      private val classLoaders: WeakList<PluginClassLoader>) {
  fun checkThatClassLoaderNotReferencedByPluginClassLoader() {
    if (unloadedMainDescriptor.pluginClassLoader !is PluginClassLoader) {
      return
    }

    val pluginSet = PluginManagerCore.getPluginSet()
    for (it in pluginSet.enabledPlugins) {
      checkThatClassLoaderNotReferencedByPluginClassLoader(it)
    }
    @Suppress("TestOnlyProblems")
    for (it in pluginSet.getUnsortedEnabledModules()) {
      checkThatClassloaderNotReferenced(it)
    }
  }

  private fun checkThatClassLoaderNotReferencedByPluginClassLoader(descriptor: IdeaPluginDescriptorImpl) {
    checkThatClassloaderNotReferenced(descriptor)
    for (dependency in descriptor.dependencies) {
      checkThatClassLoaderNotReferencedByPluginClassLoader(dependency.subDescriptor ?: continue)
    }
  }

  private fun checkThatClassloaderNotReferenced(descriptor: IdeaPluginDescriptorImpl) {
    val classLoader = descriptor.pluginClassLoader as? PluginClassLoader ?: return
    if (descriptor !== unloadedMainDescriptor) {
      // unrealistic case, but who knows
      if (classLoaders.contains(classLoader)) {
        LOG.error("$classLoader must be unloaded but still referenced")
      }

      if (classLoader.pluginDescriptor === descriptor && classLoader.pluginId == unloadedMainDescriptor.pluginId) {
        LOG.error("Classloader of $descriptor must be nullified")
      }
    }

    @Suppress("TestOnlyProblems")
    // use exactly getAllParentsClassLoaders because it's a lazy list that may be recalculated
    // wrong invalidation of allParents may lead to leaking unloaded class loaders hanging inside PluginClassLoader.allParents (IJPL-171566)
    val parents = classLoader.getAllParentsClassLoaders()
    for (unloadedClassLoader in classLoaders) {
      if (parents.any { it === unloadedClassLoader }) {
        LOG.error("$classLoader references via parents $unloadedClassLoader that must be unloaded")
      }
    }

    @Suppress("TestOnlyProblems")
    for (parent in classLoader._getParents()) {
      if (parent.pluginId == unloadedMainDescriptor.pluginId) {
        LOG.error("$classLoader references via parents $parent that must be unloaded")
      }
    }
  }
}