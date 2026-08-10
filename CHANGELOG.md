# Changelog

## [Unreleased]

### Added
- MCP tool `bear_resource_attribute_index` lists the PHP attributes the resource classes under `src/Resource` carry, one entry per class or `on*` method, with each attribute resolved through the file's `use` statements to the class it names rather than matched as text, and with the Ray.Aop interceptors a module binds to it with `annotatedWith()`.
- MCP tool `bear_di_binding_lookup` returns the Ray.Di bindings the modules under `src` declare — the implementation an interface is bound to, the `annotatedWith()` qualifier it is bound under, and the module file and line that binds it — so an agent can follow wiring a text search cannot reach. A binding the tool cannot name an implementation for (`toProvider`, `toConstructor`, `toInstance`, `toNull`, untargeted) is reported as `dynamic-unresolved` rather than dropped — which says the tool does not name it, not that none exists — and a binding whose qualifier the source does not state, a chain it could not follow, or a `rename()` it does not apply, lands in `unresolved` rather than silently narrowing the answer.

## [0.10]

### Added
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
- Build against PhpStorm 2026.2 (`platformVersion = 2026.2`)
- IntelliJ Platform Gradle Plugin updated to 2.18.1, Gradle to 9.5.0, Kotlin plugin to 2.4.10; JDK toolchain (Java 25) is auto-provisioned via the foojay resolver when not installed locally
- Replaced the remaining deprecated/internal API usages reported by the plugin verifier against IntelliJ IDEA 2026.2.1: `FilenameIndex.getFilesByName()` → `FilenameIndex.getVirtualFilesByName()` and internal `PhpType.from()` → public `new PhpType().add()` in the resource method type provider
- Java and Kotlin output now targets Java 21 explicitly (`--release` / `jvmTarget`), matching `since-build = 252`. The auto-provisioned Java 25 toolchain had been overriding both, leaving class files that the JBR bundled with PhpStorm 2025.2 and 2025.3 (Java 21) cannot load — something the plugin verifier does not report.
- The Gradle distribution the wrapper downloads is pinned by SHA-256 checksum.

### Fixed
- The plugin verifier reported the MCP toolset as calling `McpToolset` methods that PhpStorm 2025.2 to 2026.1 do not carry: Kotlin's JVM-default compatibility mode had emitted, in the toolset class, an override of each default method that calls `super`. Those overrides are no longer emitted, so every IDE dispatches to the defaults it actually has.
- A resource is no longer taken for a dependency's when the project itself sits under a directory named `vendor/`; the check now reads the path relative to the project directory rather than the absolute path.
- An ALPS descriptor sitting exactly at the 64-level nesting limit is no longer rejected for having no children of its own.

## [0.9]

### Added
- BEAR.Resource ↔ template navigation: jump between a resource class and its Twig/Qiq template from the Project View, editor, and editor-tab context menus (`Open Template` / `Open Resource`); hidden when no counterpart exists.
- `#[Embed]` template navigation: Cmd+click a Twig/Qiq variable (`{{ embedded }}` / `{{= $this->embedded }}`) to open the embedded template, with a gutter icon on the matching line (#18).
- Incoming Link/Embed relation gutter: BEAR.Resource methods now show incoming static `#[Link]` / `#[Embed]` relations from other resources and navigate back to the source attribute declaration.

### Changed
- Ray.Aop bound-interceptor gutter/action now uses a dedicated AOP icon instead of the BEAR resource icon.
- BEAR and Ray gutter icons now use transparent backgrounds.
- Incoming resource relation gutters moved from the resource class name to the target resource method (`#[Embed]` always maps to `onGet`; `#[Link]` maps from its `method` argument and defaults to `onGet`).

### Fixed
- `#[Link]` and `#[Embed]` no longer show the AOP bound-interceptor gutter/action, so relation attributes are not routed to framework interceptors.

## [0.8]

### Added
- Go to bound interceptor: bound Ray.Aop attributes (e.g. `#[Transactional]`) now show a BEAR gutter icon and can jump to interceptor class(es) bound in a module via `bindInterceptor()` from the icon or `Navigate > Go to Bound Interceptor` (#19). Standard PhpStorm declaration navigation remains available for the attribute class itself.

### Changed
- Replaced the deprecated `Project#getBaseDir()` with `ProjectUtil.guessProjectDir()` (with null guards) across the resource / router / SQL / JSON Schema goto handlers and the resource index
- Refreshed README and plugin description metadata, including current feature wording and Marketplace links
- Removed stale README TODOs and legacy Php Annotations Plugin references from public documentation
- Updated the MIT license notice to cover 2015-2026 Shingo Kumagai and contributors

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
