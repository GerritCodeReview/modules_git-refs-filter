# Git Ref filter module for Gerrit

Gerrit lib module to allow filtering out refs in the Git advertizing
protocol phase.

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
- Merged changes and all their patch-sets
- Abandoned changes and all their patch-sets
- Corrupted changes and all their patch-sets
- All '/meta' refs of all changes
- All non-published edits of any changes

Is is also possible to define additional refs prefixes to be hidden or explicitly shown,
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

