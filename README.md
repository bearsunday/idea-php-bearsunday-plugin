# BEAR.Sunday PhpStorm Plugin

![Version](https://img.shields.io/jetbrains/plugin/v/8030-bear-sunday-plugin.svg)
![Download](https://img.shields.io/jetbrains/plugin/d/8030-bear-sunday-plugin.svg)

## Links

* [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/8030)

<!-- Plugin description -->
## Features

* BEAR.Resource URI completion
* BEAR.Resource goto from URIs such as `app://self/user` to `src/Resource/App/User.php`
* BEAR.Resource JSON Schema goto
* BEAR.Resource JSON Schema body key completion from `->body['<caret>']`
* Incoming Link/Embed relation gutter for BEAR.Resource methods
* `#[Embed]` template navigation: Cmd+click a Twig/Qiq variable to open the embedded template
* BEAR.Resource ↔ template navigation: jump between a resource class and its Twig/Qiq template from the context menu
* Extract BEAR.Resource parameters to Ray.InputQuery DTO
* Ray.Aop bound interceptor gutter icon and navigation from attributes such as `#[Transactional]`
* Ray.MediaQuery SQL goto
* Ray.QueryModule SQL goto
* Aura.Router goto BEAR.Resource
* Generate Psalm body type PHPDoc for BEAR.Resource body array shapes, including folder-level batch generation
* Generate JSON Schema files from BEAR.Resource body array shapes
* Infer concrete BEAR.Resource classes and method-specific body shapes from literal resource method calls
* Read-only MCP tools that let an AI agent ask the IDE about resource classes, the attributes they carry and the interceptors bound to them, the Ray.Di bindings the modules declare, `$this->body` shapes, JSON Schema files, the generated OpenAPI document and ALPS profiles, and compare what each of those three layers names

<!-- Plugin description end -->
## Feature demos

The `demo-app` directory is a small BEAR.Sunday application that demonstrates every plugin feature.
Open `demo-app` in the sandbox IDE with `./gradlew runIde`, wait for indexing, and use Cmd/Ctrl-click,
completion, line markers, or the editor intention on the files below.

| Feature | Demo entry point | What to try |
| --- | --- | --- |
| BEAR.Resource URI completion | `demo-app/src/Resource/App/UriDemo.php` | Invoke completion inside `uri('...')` arguments. |
| BEAR.Resource goto | `demo-app/src/Resource/App/UriDemo.php`, `demo-app/src/Resource/App/Dashboard.php` | Cmd/Ctrl-click `app://self/user` or `/profile` to jump to the resource class. |
| BEAR.Resource typed resource result | `demo-app/src/Resource/App/UriDemo.php`, `demo-app/src/Resource/App/User.php` | `get('app://self/user')` is inferred as the concrete `User` resource; `$user->body` is narrowed to the GET body shape, while `put()`/`post()` use their method-specific body shapes. |
| BEAR.Resource JSON Schema goto | `demo-app/src/Resource/App/BodyTypeDemo.php` | Cmd/Ctrl-click `body-type-demo.json` to open `demo-app/var/json_schema/body-type-demo.json`. |
| Incoming Link/Embed relation gutter | `demo-app/src/Resource/App/User.php`, `demo-app/src/Resource/App/Profile.php` | Use the gutter to find incoming relations from `Dashboard.php`. |
| Embedded template navigation for Twig/Qiq | `demo-app/App/Dashboard.html.twig`, `demo-app/App/Dashboard.php` | Cmd/Ctrl-click or use the gutter on `user` / `$this->user` to jump to the embedded user template. |
| Extract BEAR.Resource parameters to Ray.InputQuery DTO | `demo-app/src/Resource/App/Point.php` | Place the caret on `onGet(int $x, int $y)`, open the lightbulb, and run **Extract Input DTO...**. Compare with `PointDto.php` + `PointInput.php`. |
| Ray.Aop bound interceptor navigation | `demo-app/src/Resource/App/BodyTypeDemo.php` | Use the gutter/action on `#[Audited]` to jump to `AuditInterceptor.php`, bound in `AopDemoModule.php`. |
| Ray.MediaQuery SQL goto | `demo-app/src/Query/PointQueryInterface.php` | Cmd/Ctrl-click `point_distance` in `#[DbQuery(...)]` to open `demo-app/var/db/sql/point_distance.sql`. |
| Ray.QueryModule SQL goto | `demo-app/src/Query/LegacyPointQueryInterface.php` | Cmd/Ctrl-click `point_distance` in `@Query("point_distance")`. |
| Aura.Router goto BEAR.Resource | `demo-app/aura.route.php` | Cmd/Ctrl-click `/index` or `/dashboard` to jump to the matching Page resource. |
| Generate Psalm body type PHPDoc | `demo-app/src/Resource/App/BodyTypeDemo.php` or `demo-app/src/Resource/App/` | Run **Generate BEAR body type** on one resource class, or from the Project View folder popup to process every ResourceObject under the selected folder. |
| Generate body JSON Schema | `demo-app/src/Resource/App/BodyTypeDemo.php` | Run **Generate BEAR body JSON Schema**; it writes `var/json_schema/body-type-demo.json` without a method name in the file. |

### Body type generator output

Running **Generate BEAR body type** adds named Psalm array-shape aliases to the ResourceObject PHPDoc.
The GET body uses the conventional methodless name (`ArticleBody`), while other methods include the
HTTP method (`ArticlePostBody`, `ArticlePutBody`, ...). The `$body` property is declared as a union so
Psalm, PHPStan, and PhpStorm can narrow the shape by resource method.

```diff
 use BEAR\Resource\ResourceObject;

+/**
+ * @psalm-type ArticleBody = array{
+ *     id: int,
+ *     title: string,
+ *     tags: list<string>
+ * }
+ * @psalm-type ArticlePostBody = array{
+ *     status: string,
+ *     id: int
+ * }
+ * @property ArticleBody|ArticlePostBody|null $body
+ */
 final class Article extends ResourceObject
 {
     public function onGet(): static
```

Running **Generate BEAR body JSON Schema** uses the same inferred GET body shape and writes the
project's conventional methodless schema path, for example `var/json_schema/article.json`.

```diff
+{
+  "$schema": "https://json-schema.org/draft/2020-12/schema",
+  "type": "object",
+  "properties": {
+    "id": {
+      "type": "integer"
+    },
+    "title": {
+      "type": "string"
+    },
+    "tags": {
+      "type": "array",
+      "items": {
+        "type": "string"
+      }
+    }
+  },
+  "required": [
+    "id",
+    "title",
+    "tags"
+  ]
+}
```

### Body type generator screenshots

Before running the intention:

![Generate BEAR body type before](docs/images/body-type-generator-before.png)

After generation:

![Generate BEAR body type after](docs/images/body-type-generator-after.png)

Actual sandbox PhpStorm screenshot:

![PhpStorm demo app screenshot](docs/images/phpstorm-body-type-demo-real.png)

## MCP tools

The plugin registers a read-only toolset on the MCP server bundled with PhpStorm, so an AI agent can ask the
IDE for facts about the project instead of inferring them from a text search. Nothing the agent asks for
changes the project: the tools return facts, links and suggestions, and the agent writes any diff itself.

Enable the server under **Settings > Tools > MCP Server**, then connect your client from the same page
(Auto-Configure), or manually with the URL shown there:

```sh
claude mcp add --transport sse --scope user phpstorm http://127.0.0.1:<port>/sse
```

| Tool | Answers |
|---|---|
| `bear_resource_describe` | The class, `on*` methods with parameters and attributes, and the `#[Link]` / `#[Embed]` relations of a resource URI |
| `bear_resource_body_shape` | The Psalm shape a resource method assigns to `$this->body`, including unsaved editor changes |
| `bear_resource_attribute_index` | The PHP attributes each resource class and `on*` method carries under `src/Resource`, resolved through the file's `use` statements to the class they name, with the Ray.Aop interceptors bound to each by `annotatedWith()` |
| `bear_app_context_list` | The contexts the application actually boots under, read from the arguments its own entry points and tests pass, with the file and line each is written at |
| `bear_di_binding_lookup` | The Ray.Di bindings the modules under `src` declare: which implementation an interface is bound to, under which `annotatedWith()` qualifier, and the module file and line that binds it |
| `bear_di_module_tree_read` | The tree of modules a context string installs, in Ray.Di's own priority order, with the file and line each `install()` is written at -- and, with `diagram=true`, a Mermaid drawing of the same tree |
| `bear_aop_pointcut_lookup` | Which Ray.Aop interceptors wrap a class's methods, and the pointcut that binds each one, evaluated as Ray.Aop's own matchers evaluate it |
| `bear_di_object_graph` | What a class is actually built out of in one context: the object graph Ray.Di would assemble, resolved from the source -- `print_o`'s question without booting anything -- and, with `diagram=true`, a Mermaid drawing of the same graph |
| `bear_schema_lookup` | The JSON Schema files of a resource, from the `#[JsonSchema]` attribute or the `var/json_schema` convention |
| `bear_apidoc_operation_lookup` | Operations in the OpenAPI document generated by bear/api-doc |
| `bear_alps_profile_read` | A whole ALPS profile, normalized the same way whether it is JSON or XML |
| `bear_alps_descriptor_lookup` | One ALPS descriptor by id or href |
| `bear_alps_transition_lookup` | ALPS transitions, with the `#[Link]` / `#[Embed]` declarations that implement them |
| `bear_alps_links_resolve` | Where an ALPS profile's links point, and whether the target exists |
| `bear_alps_links_suggest` | Links the project's conventions imply but the profile does not declare |
| `bear_contract_compare` | The field names the JSON Schema, the ALPS descriptor and the inferred body each carry, and which fields only one of them names |

The comparison is presence-only: it reports which side names a field, never whether the sides agree on its
type or meaning.

The attribute index matches by resolved class, not by text: the same short name aliases different classes in
different files. Its interceptor list covers `annotatedWith()` bindings only, so an empty list means no such
binding names the attribute, not that no interceptor runs on the method.

The binding lookup answers the question a text search cannot: an injected `#[Named('category')]
SurrogateKeyInterface` names neither the implementation class nor anything inside it, so grepping for either
misses the wiring entirely.

It reads every `bind()` chain under the root it is given, which defaults to `src` — **not** the whole project,
so a binding declared inside a framework package is only found when `moduleRoot` names that package's
directory. Within the root it collects every chain regardless of which application context installs it, so a
binding that another module later overrides is still listed.

Every binding says how Ray.Di gave it its target and how far this could follow that:

* `resolution: static` — the source itself names the implementation class. That is `bind()->to()`,
  `toConstructor()` (whose first argument is the class Ray.Di builds; only the arguments handed to that
  constructor are wired at build time), and an untargeted bind — `bind()` with no target at all, which binds
  a concrete class to itself. The untargeted one needs the project index to tell a concrete class from an
  interface, which Ray.Di binds to nothing; while the index is building it is reported as unresolved instead.
* `resolution: dynamic-unresolved` — the implementation is decided when the application is built, not where
  the binding is written: `toProvider()` (a factory class produces it), `toInstance()` (an object built on
  the spot) and `toNull()` (a do-nothing stand-in). These say the tool does not name the implementation, not
  that none exists — the class their argument does name is reported under `targetClass`.

A binding a filter cannot be applied to, because the element being filtered is the one whose value the source
does not state (`annotatedWith($this->qualifier)`, or `annotatedWith("{$this->prefix}_dsn")`, where the source
holds a template and the name is whatever the property held), lands in `unresolved` with a `reason` instead of
being silently excluded. So do `rename()` calls, which move an existing binding to another qualifier or to
another interface: this version reports them rather than applying them.

One Ray.Di construct is not read at all, and the tool says so rather than answering as if it were absent:
`MultiBinder`, which collects several implementations of one interface. `bindInterceptor()`, which weaves
Ray.Aop interceptors onto methods, is answered by `bear_aop_pointcut_lookup` instead.

A context is a string no file declares in one place -- it is written as an argument, at each entry point --
so `bear_app_context_list` is where to start when another tool wants one. It reads the two shapes an app
names one in: `(new Bootstrap())('prod-hal-app', $GLOBALS, $_SERVER)`, and `Injector::getInstance('app')` as
a test writes it. A ternary states two contexts and its condition states neither, so
`PHP_SAPI === 'cli-server' ? 'hal-app' : 'prod-hal-app'` names two. A context named by a variable states none
at all, and is counted under `argumentsUnreadable` rather than guessed at.

The module tree answers what a context installs, which no single file states either: a context string is
split into segments, each segment resolves to a module class, and each of those installs more. Every mark the
walk could not resolve -- a segment nothing answers to, an `install()` whose argument is not a class name --
is carried in the answer rather than dropped, because a tree drawn without them reads as one that resolved
cleanly.

The pointcut lookup answers the question the `#[Attribute]` gutter cannot: a pointcut such as
`bindInterceptor(annotatedWith(Cacheable::class), startsWith('onPut'), [CommandInterceptor::class])` names the
method it wraps by the spelling of its name, so nothing written at `onPut()` says an interceptor runs around
it. Unlike the other tools it evaluates rather than reports, so it evaluates only the seven matchers
`Ray\Aop\Matcher` declares, exactly as Ray.Aop's own matcher classes do; everything it cannot decide is
reported under `unevaluated` with a reason rather than settled by a guess. It takes either a context or a
directory and refuses to default: nearly every pointcut in a BEAR app is declared in `vendor`, so a scan of
`src` would answer "nothing wraps this method" about a method three interceptors wrap.

The object graph answers what the binding lookup deliberately leaves open: of several bindings of one key,
which one an application actually gets, and what the class behind it needs in turn. Walking a graph means
choosing an edge, so the choice is made here, by Ray.Di's own merge -- a later `bind()` in one module replaces
an earlier one, a module's own bind beats one from a module it installs, and of two installed modules the one
installed first wins, with `override()` reversing that last rule. The bindings that lost are named on the node
under `shadowedBy` rather than dropped.

A node is a container key spelled the way Ray.Di spells it, `"{type}-{name}"` -- the string
`Container::getDependency()` is given -- which is why `#[Named('dsn')] string $dsn` is keyed `"-dsn"`: a scalar
names no class to bind. A qualifier is the FIRST attribute a parameter carries and no other, so
`#[Other] #[Named('x')] $foo` is bound under no name at all, exactly as `Name::withAttributes()` reads it.

Two keys are answered for without any module binding them, because Ray.Di binds them in PHP rather than in a
module: `InjectorInterface`, which `Injector::__construct()` binds after the modules have built the container,
and `InjectionPointInterface`. They carry `resolution: builtin`, since calling either unbound would report a
failure no application has -- and every `ProviderInterface` in `bear/resource` takes an injector.

With neither `className` nor `uri` it starts from the application class, `{AppNamespace}\Module\App` -- not
from `AppInterface`, though that is what the bootstrap resolves. `AppMetaModule` binds that interface with
`->to($this->appMeta->name . '\Module\App')`, a class name built while the application runs, so the binding
names no class a reader of the source can follow and a graph started there is one node long. The class it
names is knowable all the same.

An `unbound` node is not a gap in the answer. Ray.Di binds an unbound concrete class on the spot only at the
entry, where `Injector::getInstance()` catches `Untargeted`; below it `Arguments::getParameter()` lets
`Unbound` out, and the compiled path throws the same, so an unbound key in the middle of a graph is what the
application would throw -- unless the edge says the parameter has a default or the setter is
`#[Inject(optional: true)]`. It is certain only when every binding in the context could be filed under a key:
a module that binds in a loop states its qualifier in a variable, and the node carries `keysUnreadable` saying
how many such bindings there are, any of which may be the one it was looking for.

The commonest of those loops is no longer one of them. Every BEAR application installs its constants through a
module that binds the entries of an array it was handed:

```php
$this->install(new NamedModule(['S3_BUCKET' => (string) getenv('S3_BUCKET'), ...]));
```

Read as the one chain it is written as, that binds under a qualifier the source states nowhere, and every name
in the array came back as bound by nobody. But the two halves together do state them: the VALUES are calls no
reading of the source can evaluate, while the KEYS are string literals sitting in the installing module's own
file, and a key is all the container needs. So the chain is read once per entry, with that entry's key standing
for the loop variable and nothing else substituted -- this says who sets a name, never what the value is.

Such a node names both the files it takes to read it: `moduleClass` is the module the bind is written in,
`filePath` and `line` are the array entry, and `installedBy` is the module whose `install()` brought the two
together. Two installs of one such module are two containers, not one, so the earlier install keeps a key they
both bind -- and the later one is named under `shadowedBy` rather than dropped.

The module is found by its shape and not by its name: nothing matches on `NamedModule`, so a module of one's
own written the same way is read the same way. An entry whose key is not a literal, and an install whose array
is a variable, are counted exactly as the whole loop used to be -- as `keysUnreadable` on the node and
`installArgumentsUnreadable` in the scan -- because an expansion that quietly skipped them would make an
`unbound` answer surer than the reading is.

It is not `print_o`, and does not pretend to be: `print_o` walks the properties of a live object, so a property
assigned inside `onGet()` is in its picture, while this walks injection points, so only what Ray.Di puts there
is in this one. `MultiBinder`, `#[Set]` and `#[Assisted]` are not followed, a `rename()` is reported rather
than applied, and interception does not appear at all, because an interceptor wraps a node without changing
what is injected into it.

## Module tree tool window

**View > Tool Windows > BEAR Module Tree** draws the tree of a context as a diagram. It renders exactly what
`bear_di_module_tree_read` answers and adds no facts of its own, so the picture and what an agent is told
cannot disagree. The context field offers the contexts `bear_app_context_list` found, and stays editable for
one it did not. Hovering a box shows the class in full and the file it is written in; clicking one opens that
class.

## Requirements

* PhpStorm 2025.2 or later (the MCP tools use the MCP server bundled from that release)
* JDK 21 for building

## Libraries

* URI-Template Library (`com.damnhandy:handy-uri-templates:2.1.8`)
* Apache Commons Text (`org.apache.commons:commons-text:1.12.0`)

## Demo app

`demo-app/` is a small BEAR.Sunday application for trying this plugin's BEAR.Resource, Ray.InputQuery, and Ray.MediaQuery support in PhpStorm. It is a local demo fixture for the plugin, not a standalone package release.

## Build

```sh
./gradlew buildPlugin
```

## Run in sandbox PhpStorm

```sh
./gradlew runIde
```

## Test

```sh
./gradlew test
```

## License

MIT License. See [LICENSE](LICENSE).
