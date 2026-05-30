# Changelog

## [Unreleased]

### Added
- Goto bound interceptor: Ctrl/Cmd-click a Ray.Aop binding attribute (e.g. `#[Transactional]`) to jump to the interceptor class(es) bound to it in a module via `bindInterceptor()` (#19)

### Changed
- Replaced the deprecated `Project#getBaseDir()` with `ProjectUtil.guessProjectDir()` (with null guards) across the resource / router / SQL / JSON Schema goto handlers and the resource index

### Fixed
- Resource URI goto failed for camelCase URIs (e.g. `app://self/blogPosting` no longer resolves to `Blogposting`); inner capitals are now preserved (#11)

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
