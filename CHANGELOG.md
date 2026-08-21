# Changelog

## [Unreleased]

### Added
- BEAR.Resource ↔ template navigation: jump between a resource class and its Twig/Qiq template from the Project View, editor, and editor-tab context menus (`Open Template` / `Open Resource`); hidden when no counterpart exists.
- `#[Embed]` template navigation: Cmd+click a Twig/Qiq variable (`{{ embedded }}` / `{{= $this->embedded }}`) to open the embedded template, with a gutter icon on the matching line (#18).
- Go to bound interceptor: bound Ray.Aop attributes (e.g. `#[Transactional]`) now show a BEAR gutter icon and can jump to interceptor class(es) bound in a module via `bindInterceptor()` from the icon or `Navigate > Go to Bound Interceptor` (#19). Standard PhpStorm declaration navigation remains available for the attribute class itself.
- Incoming Link/Embed relation gutter: BEAR.Resource methods now show incoming static `#[Link]` / `#[Embed]` relations from other resources and navigate back to the source attribute declaration.
- MCP tools for ALPS profiles: `bear_alps_profile_read`, `bear_alps_descriptor_lookup`, `bear_alps_transition_lookup` and `bear_alps_links_resolve` answer read-only questions about the project's ALPS profiles (JSON and XML) through the bundled MCP server, matching transitions against `#[Link]` / `#[Embed]` declarations (#28).
- MCP tools for resources and contracts: `bear_resource_describe`, `bear_schema_lookup`, `bear_apidoc_operation_lookup`, `bear_contract_compare` and `bear_alps_links_suggest` answer read-only questions about resource classes, their JSON Schema files, the generated OpenAPI document, and the fields the schema and the ALPS profile each name (#28).
- MCP tool `bear_resource_body_shape` returns the Psalm shape of the body a resource method assigns to `$this->body` (branches when it differs per path), and `bear_contract_compare` now compares that body against the JSON Schema and the ALPS profile as a third side (`onlyInBody`) (#28).
- Every MCP tool that resolves a resource class answers `status=index_not_ready` when finding it needs the indexes and they are still building, instead of `not_found` — "ask again" and "not here" are different answers. A resource at its conventional path is found by looking and still answers. `bear_alps_transition_lookup` keeps answering from the profile and marks only the code side (`implementationsUnavailable`).
- MCP answers that carry a file verbatim — the JSON Schema in `bear_schema_lookup`, the operation body in `bear_apidoc_operation_lookup`, the descriptor tree in `bear_alps_profile_read` — leave it out above a size limit and set `truncated: true`, keeping the summary fields and the path so the caller can open the file itself.

### Changed
- Minimum supported PhpStorm version is now **2025.2** (`since-build = 252`), the release that bundles the MCP server the tools plug into
- Resource URI type inference no longer triggers a synchronous VFS refresh, which the platform forbids under a read lock off the EDT. A resource file created outside the IDE now resolves once the file system event reaches the IDE rather than on the next inference.
- `bear_alps_transition_lookup` matches an ALPS transition to the `#[Link]` / `#[Embed]` declarations that implement it by the transition's `id` (`#[Link(rel: 'goUser')]` implements `id="goUser"`), compared exactly, rather than by the ALPS `rel` attribute, which does not identify a transition and which real profiles do not carry. A transition a state names by reference (`{"href": "#goUser"}`) is reported under that state, marked `via: "href"`.
- Every ALPS tool answers `status=ambiguous` with the candidate paths when more than one profile in the project matches, instead of answering from whichever the file system offered first. Naming a `profilePath` answers from that one. `bear_contract_compare` marks only its ALPS side.
- A profile that cannot be read at all answers `engine_unavailable` rather than `parse_error`, which had said the profile is malformed when nothing had read it.
- Ray.Aop bound-interceptor gutter/action now uses a dedicated AOP icon instead of the BEAR resource icon.
- BEAR and Ray gutter icons now use transparent backgrounds.
- Incoming resource relation gutters moved from the resource class name to the target resource method (`#[Embed]` always maps to `onGet`; `#[Link]` maps from its `method` argument and defaults to `onGet`).
- Replaced the deprecated `Project#getBaseDir()` with `ProjectUtil.guessProjectDir()` (with null guards) across the resource / router / SQL / JSON Schema goto handlers and the resource index
- Replaced the remaining deprecated/internal API usages reported by the plugin verifier against IntelliJ IDEA 2026.2.1: `FilenameIndex.getFilesByName()` → `FilenameIndex.getVirtualFilesByName()` and internal `PhpType.from()` → public `new PhpType().add()` in the resource method type provider
- Refreshed README and plugin description metadata, including current feature wording and Marketplace links
- Removed stale README TODOs and legacy Php Annotations Plugin references from public documentation
- Updated the MIT license notice to cover 2015-2026 Shingo Kumagai and contributors

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
