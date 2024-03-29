// Copyright (C) 2018 The Android Open Source Project
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

package com.googlesource.gerrit.libmodule.plugins.test;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.WaitUtil.waitUntil;
import static com.googlesource.gerrit.modules.gitrefsfilter.ChangesTsCache.CHANGES_CACHE_TS;
import static com.googlesource.gerrit.modules.gitrefsfilter.OpenChangesCache.OPEN_CHANGES_CACHE;

import com.google.common.cache.LoadingCache;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.NoHttpd;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.RefNames;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.googlesource.gerrit.modules.gitrefsfilter.ChangeCacheKey;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.util.FS;
import org.junit.Before;
import org.junit.Test;

@NoHttpd
@Sandboxed
public class GitRefsFilterTest extends AbstractGitDaemonTest {
  @Inject private RequestScopeOperations requestScopeOperations;

  @Inject
  private @Named(OPEN_CHANGES_CACHE) LoadingCache<ChangeCacheKey, Boolean> changeOpenCache;

  @Inject
  private @Named(CHANGES_CACHE_TS) LoadingCache<ChangeCacheKey, Long> changesTsCache;

  private static final int CLOSED_CHANGES_GRACE_TIME_SEC = 5;

  private static final Duration TEST_PATIENCE_TIME =
      Duration.ofSeconds(CLOSED_CHANGES_GRACE_TIME_SEC + 1);

  private volatile Exception getRefsException = null;

  @Before
  public void setup() throws Exception {
    createFilteredRefsGroup();
    setProjectClosedChangesGraceTime(project, Duration.ofSeconds(CLOSED_CHANGES_GRACE_TIME_SEC));
  }

  @Test
  public void testUserWithFilterOutCapabilityShouldNotSeeAbandonedChangesRefs() throws Exception {
    Timestamp changeTs = gApi.changes().id(createChangeAndAbandon()).get().updated;

    waitUntil(() -> getRefsUnchecked(user).isEmpty(), TEST_PATIENCE_TIME);
    checkGetRefsIsSuccessful();

    Timestamp filterCutoffTs =
        Timestamp.from(
            Instant.now()
                .truncatedTo(ChronoUnit.SECONDS)
                .minusSeconds(CLOSED_CHANGES_GRACE_TIME_SEC));

    assertThat(changeTs.before(filterCutoffTs)).isTrue();
  }

  @Test
  public void testUserWithFilterOutCapabilityShouldSeeJustClosedChangesRefs() throws Exception {
    createChangeAndAbandon();

    assertThat(getRefs(cloneProjectChangesRefs(user))).isNotEmpty();
  }

  @Test
  public void testUserWithFilterOutCapabilityShouldNotSeeUserEdits() throws Exception {
    createChange();
    int changeNum = changeNumOfRef(getChangesRefsAs(admin).get(0));
    gApi.changes().id(changeNum).edit().create();

    assertThat(
            fetchAllRefs(user)
                .filter((ref) -> ref.startsWith(RefNames.REFS_USERS))
                .collect(Collectors.toSet()))
        .isEmpty();
  }

  @Test
  public void testUserWithFilterOutCapabilityShouldSeeOpenChangesRefs() throws Exception {
    createChange();

    assertThat(getRefs(cloneProjectChangesRefs(user))).isNotEmpty();
  }

  @Test
  public void testAdminUserShouldSeeAbandonedChangesRefsAfterGracePeriod() throws Exception {
    createChangeAndAbandon();

    waitUntil(() -> getRefsUnchecked(user).isEmpty(), TEST_PATIENCE_TIME);

    assertThat(getRefs(cloneProjectChangesRefs(admin))).isNotEmpty();
  }

  @Test
  @GerritConfig(name = "git-refs-filter.hideRefs", value = "refs/heads/sandbox/")
  public void testUserWithHideRefsShouldNotSeeSandboxBranches() throws Exception {
    String sandboxPrefix = "refs/heads/sandbox/";
    requestScopeOperations.setApiUser(admin.id());
    createBranch(BranchNameKey.create(project, "sandbox/foo"));

    assertThat(getRefs(cloneProjectRefs(admin, "+refs/heads/*:refs/heads/*"), sandboxPrefix))
        .isNotEmpty();
    assertThat(getRefs(cloneProjectRefs(user, "+refs/heads/*:refs/heads/*"), sandboxPrefix))
        .isEmpty();
  }

  @Test
  @GerritConfig(
      name = "git-refs-filter.hideRefs",
      values = {"refs/heads/sandbox/", "!refs/heads/sandbox/mine"})
  public void testUserWithHideRefsShouldSeeItsOwnSandboxBranch() throws Exception {
    String sandboxPrefix = "refs/heads/sandbox/";
    requestScopeOperations.setApiUser(admin.id());
    createBranch(BranchNameKey.create(project, "sandbox/mine"));

    assertThat(getRefs(cloneProjectRefs(user, "+refs/heads/*:refs/heads/*"), sandboxPrefix))
        .isNotEmpty();
  }

  @Test
  public void testShouldCacheChangeIsClosedWhenAbandoned() throws Exception {
    Change.Id changeId = Change.id(createChangeAndAbandon());
    Ref metaRef = getMetaId(changeId);

    getRefs(cloneProjectChangesRefs(user));

    assertThat(changeOpenCache.asMap().size()).isEqualTo(1);

    Map.Entry<ChangeCacheKey, Boolean> cacheEntry =
        new ArrayList<>(changeOpenCache.asMap().entrySet()).get(0);

    assertThat(cacheEntry.getKey().project()).isEqualTo(project);
    assertThat(cacheEntry.getKey().changeId()).isEqualTo(changeId);
    assertThat(cacheEntry.getKey().changeRevision()).isEqualTo(metaRef.getObjectId());
    assertThat(cacheEntry.getKey().repo()).isNotNull();
    assertThat(cacheEntry.getValue()).isFalse();
  }

  @Test
  public void testShouldCacheChangeKeyContainRepoAfterDeserializing() throws Exception {
    Change.Id changeId = Change.id(createChangeAndAbandon());
    getRefs(cloneProjectChangesRefs(user));

    assertThat(changeOpenCache.asMap().size()).isEqualTo(1);

    Map.Entry<ChangeCacheKey, Boolean> cacheEntry =
        new ArrayList<>(changeOpenCache.asMap().entrySet()).get(0);

    assertThat(cacheEntry.getKey().repo()).isNotNull();
  }

  @Test
  public void testShouldCacheWhenChangeIsOpen() throws Exception {
    createChange();
    List<Ref> refs = getRefs(cloneProjectChangesRefs(user));

    assertThat(refs).isNotEmpty();

    Change.Id changeId = Change.id(changeNumOfRef(refs.get(0)));

    assertThat(changeOpenCache.asMap().size()).isEqualTo(1);

    Map.Entry<ChangeCacheKey, Boolean> cacheEntry =
        new ArrayList<>(changeOpenCache.asMap().entrySet()).get(0);

    assertThat(cacheEntry.getKey().project()).isEqualTo(project);
    assertThat(cacheEntry.getKey().changeId()).isEqualTo(changeId);
    assertThat(cacheEntry.getKey().changeRevision()).isEqualTo(getMetaId(changeId).getObjectId());
    assertThat(cacheEntry.getValue()).isTrue();
  }

  @Test
  public void testShouldCacheChangeTsWhenAbandoned() throws Exception {
    Change.Id changeId = Change.id(createChangeAndAbandon());
    Ref metaRef = getMetaId(changeId);

    getRefs(cloneProjectChangesRefs(user));

    assertThat(changesTsCache.asMap().size()).isEqualTo(1);

    Map.Entry<ChangeCacheKey, Long> cacheEntry =
        new ArrayList<>(changesTsCache.asMap().entrySet()).get(0);

    assertThat(cacheEntry.getKey().project()).isEqualTo(project);
    assertThat(cacheEntry.getKey().changeId()).isEqualTo(changeId);
    assertThat(cacheEntry.getKey().changeRevision()).isEqualTo(metaRef.getObjectId());
    assertThat(cacheEntry.getValue())
        .isEqualTo(gApi.changes().id(changeId.get()).get().updated.getTime());
  }

  private List<Ref> getRefsUnchecked(TestAccount user) {
    try {
      return super.getRefs(cloneProjectChangesRefs(user));
    } catch (Exception e) {
      getRefsException = e;
      return new ArrayList<>();
    }
  }

  private void checkGetRefsIsSuccessful() throws Exception {
    if (getRefsException != null) {
      throw getRefsException;
    }
  }

  protected Stream<String> fetchAllRefs(TestAccount testAccount) throws Exception {
    DfsRepositoryDescription desc = new DfsRepositoryDescription("clone of " + project.get());

    FS fs = FS.detect();
    fs.setUserHome(null);

    InMemoryRepository dest =
        new InMemoryRepository.Builder().setRepositoryDescription(desc).setFS(fs).build();
    Config cfg = dest.getConfig();
    String uri = registerRepoConnection(project, testAccount);
    cfg.setString("remote", "origin", "url", uri);
    cfg.setString("remote", "origin", "fetch", "+refs/*:refs/remotes/origin/*");
    TestRepository<InMemoryRepository> testRepo = GitUtil.newTestRepository(dest);
    FetchResult result = testRepo.git().fetch().setRemote("origin").call();
    return result.getAdvertisedRefs().stream().map(Ref::getName);
  }

  protected List<Ref> getRefs(TestRepository<InMemoryRepository> repo, String prefix)
      throws IOException {
    return repo.getRepository().getRefDatabase().getRefsByPrefix(prefix);
  }

  private Ref getMetaId(Change.Id changeId) throws Exception {
    try (Repository r = repoManager.openRepository(project)) {
      return r.exactRef(RefNames.changeMetaRef(changeId));
    }
  }
}
