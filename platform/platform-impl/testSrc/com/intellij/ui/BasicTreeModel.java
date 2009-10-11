/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package com.intellij.ui;

import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;

public abstract class BasicTreeModel implements TreeModel {
  public boolean isLeaf(Object node) {
      return getChildCount(node) == 0;
  }

  public void valueForPathChanged(TreePath path, Object newValue) {
  }

  public void addTreeModelListener(TreeModelListener l) {
  }

  public void removeTreeModelListener(TreeModelListener l) {
  }

  public static int getIndex(Object[] children, Object child) {
    for (int i = 0; i < children.length; i++) {
      Object objectInstance = children[i];
      if (objectInstance == child)
        return i;
    }
    return -1;
  }
}
