# DEPRECATION NOTICE

GerritForge has decided to [change the license to BSL](https://gitenterprise.me/2025/09/30/re-licensing-gerritforge-plugins-welcome-to-gerrit-enterprise/)
therefore the Apache 2.0 version of this plugin is deprecated.
The recommended version of the git-refs-filter plugin is on [GitHub](https://github.com/GerritForge/git-refs-filter)
and the development continues on [GerritHub.io](https://review.gerrithub.io/admin/repos/GerritForge/git-refs-filter,general).

# Git Ref filter module for Gerrit (DEPRECATED)

Gerrit lib module to allow filtering out refs in the Git advertizing
protocol phase.

## When to use git-refs-filter vs. Git's hideRefs or Gerrit's ACLs

Gerrit ACLs, Git's hideRefs and git-refs-filter are all tools for hiding refs from being visible
to a remote Git client. However, the three tools have different scope and performance implications.

### Git's hideRefs: the fastest

Use the [Git's hideRefs](https://git-scm.com/docs/git-config/2.17.0#Documentation/git-config.txt-receivehideRefs)
when *all Git clients* need to be blocked from accessing some refs.

This method to hide refs is the *fastest* possible and has no performance implications.

### git-refs-filter

The git-refs-filter is mostly used with CI systems to avoid the overloading of advertising and
downloading a significant number of refs that would slowdown both Gerrit server and the Git client.

It is more flexible than Git's hideRefs because allows to define a limited group of users for hiding
refs; it also allows to filter Gerrit NoteDb's `/meta` refs which would otherwise require a complex
set of ACLs or plugins.

git-refs-filter is slightly slower than using Git's hideRefs and it does require the configuration
of the change_notes cache in `gerrit.config` to avoid potentially high overhead.

Additionally, this plugin uses an in-memory cache to store previously computed
open/close change statuses to avoid processing them over and over again.

Explicit invalidation of such cache is not necessary, since the change revision
is part of the cache key, so that previous entries automatically become obsolete
once a change status is updated.

### Gerrit ACLs

Use the Gerrit ACLs when you need to hide some of the refs on a per-project basis or when
it is needed a very sophisticated pattern-matching of refs to be excluded.

This is the slowest way to hide refs and needs to be used only when a per-project ACLs policy
is required.

## How to build

Build this module as it was a Gerrit plugin:

- Clone Gerrit source tree
- Clone the git-refs-filter source tree
- Link the ```git-refs-filter``` directory to Gerrit ```/plugins/git-refs-filter```
- From Gerrit source tree run ```bazel build plugins/git-refs-filter```
- And for running tests ```bazel test plugins/git-refs-filter:git_refs_filter_tests```
- The ```git-refs-filter.jar``` module is generated under ```/bazel-genfiles/plugins/git-refs-filter/```

## How install

Copy ```git-refs-filter.jar``` library to Gerrit ```/lib``` and add the following
one extra settings to ```gerrit.config```:

```
[gerrit]
  installModule = com.googlesource.gerrit.modules.gitrefsfilter.RefsFilterModule
```

## How to configure filtering

The refsfilter module defines a new global capability called "Filter out closed changes refs".
By default the capability isn't assigned to any user or group, thus the module installation
has no side effects.

Filtering a closed change refs has the following meaning:
- Merged changes and all their patch-sets older than the [grace time](#grace-time-for-closed-changes)
- Abandoned changes and all their patch-sets older than the [grace time](#grace-time-for-closed-changes)
- Corrupted changes and all their patch-sets older than the [grace time](#grace-time-for-closed-changes)
- All '/meta' refs of all changes
- All non-published edits of any changes

It is also possible to define additional refs prefixes to be hidden or explicitly shown,
using a similar syntax to the [hideRefs](https://git-scm.com/docs/git-config/2.17.0#Documentation/git-config.txt-receivehideRefs)
setting, adding a set of `git-refs-filter.hideRefs` configuration settings in
`gerrit.config`.

Example of how to hide all `refs/backup/*` and `refs/sandbox/*` from being advertised
but still show `refs/sandbox/mines/`:

````
[git-refs-filter]
  hideRefs = refs/backup/
  hideRefs = refs/sandbox/
  hideRefs = !refs/sandbox/mine/
```

To enable a group of users of getting a "filtered list" of refs (e.g. CI jobs):
- Define a new group of users (e.g. Builders)
- Add a user to that group (e.g. Add 'jenkins' to the Builders group)
- Go to the All-Projects ACLs, add the "Filter out closed changes refs" and assign to the group (e.g. Builders)

*NOTE* Gerrit makes a super-simplified ACL evaluation if all the projects are globally readable (e.g. project has
a READ rule to refs/*). To enable the closed changes filtering you need to disable any global read rule
for the group that needs refs filtering.

### Grace time for closed changes

The refsfilter allows to define `git-refs-filter: grace time [sec] for closed changes`
project configuration parameter. This parameter controls the size of the grace
time window in seconds. All closed changes newer than the grace time will not
be filtered out. Value can be defined per project or can be inherited from its parents.

Default value: 86400

Example of setting the grace time parameter in `project.config`:

```
[plugin "gerrit"]
  gitRefFilterClosedChangesGraceTimeSec = 3600
```