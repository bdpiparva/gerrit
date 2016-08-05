// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.project;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.git.ProjectConfig;
import com.google.inject.Inject;
import com.google.inject.Provider;

import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

public class CommentLinkProvider implements Provider<List<CommentLinkInfo>> {
  private static final Logger log =
      LoggerFactory.getLogger(CommentLinkProvider.class);

  private final Config cfg;

  @Inject
  CommentLinkProvider(@GerritServerConfig Config cfg) {
    this.cfg = cfg;
  }

  @Override
  public List<CommentLinkInfo> get() {
    Set<String> subsections = cfg.getSubsections(ProjectConfig.COMMENTLINK);
    List<CommentLinkInfo> cls =
        Lists.newArrayListWithCapacity(subsections.size());
    for (String name : subsections) {
      try {
        CommentLinkInfo cl = ProjectConfig.buildCommentLink(cfg, name, true);
        if (cl.isOverrideOnly()) {
          log.warn("commentlink " + name + " empty except for \"enabled\"");
          continue;
        }
        cls.add(cl);
      } catch (IllegalArgumentException e) {
        log.warn("invalid commentlink: " + e.getMessage());
      }
    }
    return ImmutableList.copyOf(cls);
  }
}
