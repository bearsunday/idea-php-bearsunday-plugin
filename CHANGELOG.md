# Changelog

## [Unreleased]

### Added
- Go to bound interceptor: bound Ray.Aop attributes (e.g. `#[Transactional]`) now show a BEAR gutter icon and can jump to interceptor class(es) bound in a module via `bindInterceptor()` from the icon or `Navigate > Go to Bound Interceptor` (#19). Standard PhpStorm declaration navigation remains available for the attribute class itself.
- Incoming Link/Embed relation gutter: BEAR.Resource methods now show incoming static `#[Link]` / `#[Embed]` relations from other resources and navigate back to the source attribute declaration.

### Changed
- Ray.Aop bound-interceptor gutter/action now uses a dedicated AOP icon instead of the BEAR resource icon.
- BEAR and Ray gutter icons now use transparent backgrounds.
- Incoming resource relation gutters moved from the resource class name to the target resource method (`#[Embed]` always maps to `onGet`; `#[Link]` maps from its `method` argument and defaults to `onGet`).
- Replaced the deprecated `Project#getBaseDir()` with `ProjectUtil.guessProjectDir()` (with null guards) across the resource / router / SQL / JSON Schema goto handlers and the resource index

### Fixed
- Resource URI goto failed for camelCase URIs (e.g. `app://self/blogPosting` no longer resolves to `Blogposting`); inner capitals are now preserved (#11)
- `#[Link]` and `#[Embed]` no longer show the AOP bound-interceptor gutter/action, so relation attributes are not routed to framework interceptors.

## [0.7]

### Changed
- Migrated to IntelliJ Platform Gradle Plugin 2.x (`org.jetbrains.intellij.platform` 2.2.1)
- Minimum supported PhpStorm version is now **2025.1** (`since-build = 251`)
- Java version updated to 21 (required by PhpStorm 2025.1+)
- Gradle updated to 8.12

### Added
- Unit tests for `Settings`, `Resource`, `UriUtil`, and `RouterUtil`
- `RouterUtil` extracted from `RouterGotoDeclarationHandler` for testability
- `commons-text` dependency (fixes `NoClassDefFoundError: org/apache/commons/text/WordUtils` on resource URI goto)

### Fixed
- Resource URI goto crashed with `NoClassDefFoundError` because `commons-text` was not bundled
- `idea.bear.sunday-annotation.xml` had an invalid `url` attribute that caused a plugin descriptor warning

## [0.6]

- Add JSON Schema path
- Resource URI goto improvements
