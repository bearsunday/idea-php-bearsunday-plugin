# BEAR.Sundayプラグイン デモアプリ

PhpStormでこの `demo-app/` をプロジェクトとして開くと、プラグイン機能を一通り試せます。リポジトリのルートではなく、このディレクトリを開いてください。

## 準備

```sh
composer install
```

## 機能別の試し方

| 機能 | デモファイル | 試すこと |
| --- | --- | --- |
| BEAR.Resource URI補完 | `src/Resource/App/UriDemo.php` | `uri('...')` の文字列内で補完を実行します。 |
| BEAR.Resourceジャンプ | `src/Resource/App/UriDemo.php`, `src/Resource/App/RelationDemo.php`, `src/Resource/App/Dashboard.php` | `app://self/...` や `/profile` で Cmd/Ctrl+B を押します。 |
| JSON Schemaジャンプ | `src/Resource/App/SchemaDemo.php`, `src/Resource/App/BodyTypeDemo.php` | `point.json` / `point-params.json` / `body-type-demo.json` で Cmd/Ctrl+B を押します。 |
| Link/Embed incoming gutter | `src/Resource/App/User.php`, `src/Resource/App/Profile.php`, `src/Resource/App/PointDto.php`, `src/Resource/Page/Index.php` | `onGet()` 左のBEARアイコンから参照元へ移動します。 |
| Twig/Qiq embedded template navigation | `App/Dashboard.html.twig`, `App/Dashboard.php` | `user` / `$this->user` のgutterまたは Cmd/Ctrl+B で埋め込み先テンプレートへ移動します。 |
| Input DTO抽出 | `src/Resource/App/Point.php` | `onGet(int $x, int $y)` にカーソルを置き、電球から `Extract Input DTO...` を実行します。試すだけならDTO名は `PointDemoInput` にし、実行後にUndoします。 |
| Ray.Aop interceptorジャンプ | `src/Resource/App/PointDto.php`, `src/Resource/App/BodyTypeDemo.php` | `#[DemoLogged]` / `#[Audited]` 左のRay.Aopアイコン、または `Go to Bound Interceptor` を実行します。 |
| Ray.MediaQuery SQLジャンプ | `src/Query/PointQueryInterface.php` | `#[DbQuery('point_distance')]` の `point_distance` で Cmd/Ctrl+B を押します。 |
| Ray.QueryModule SQLジャンプ | `src/Query/QueryModuleDemo.php`, `src/Query/LegacyPointQueryInterface.php` | `point_distance` で Cmd/Ctrl+B を押します。 |
| Aura.Routerジャンプ | `aura.route.php` | `'/index'` / `'/dashboard'` で Cmd/Ctrl+B を押します。 |
| Psalm body type PHPDoc生成 | `src/Resource/App/BodyTypeDemo.php`, `src/Resource/App/` | クラス上で **Generate BEAR body type** intentionを実行するか、Project Viewでフォルダを右クリックして同名アクションを実行します。配下のResourceObjectだけを一括処理します。 |

## Body type PHPDoc生成

`src/Resource/App/BodyTypeDemo.php` で **Generate BEAR body type** を実行するか、Project Viewで `src/Resource/App/` を右クリックして同名アクションを実行すると、`$this->body` への代入から class PHPDoc に `@psalm-type BodyTypeDemoBody = ...` / `@psalm-type BodyTypeDemoPostBody = ...` と `@property BodyTypeDemoBody|BodyTypeDemoPostBody|null $body` が追加されます。メソッド名なしの `Body` は GET 表現です。

スクリーンショット:

- `../docs/images/body-type-generator-before.png`
- `../docs/images/body-type-generator-after.png`
- `../docs/images/phpstorm-body-type-demo-real.png`

## ALPS プロファイル

`alps.json` と `profile.alps.xml` は同一内容の ALPS プロファイルです（JSON/XML 正規化のパリティ確認用に両形式を用意。ファイル名も検出パターン `alps.json` / `*.alps.xml` の両方をカバーします）。descriptor は `src/Resource` 配下の実リソース（User / Profile / Point / Dashboard）と、`Dashboard` の `#[Embed]` / `#[Link]` の rel（`goUser` / `goProfile`）に対応しています。コード側の rel が ALPS transition の `id` をそのまま名乗る、というのが BEAR の規約で、`bear_alps_transition_lookup` の `implementations` はこの一致で結びます。`Point` descriptor は `var/json_schema/point.json` へ `describedby` リンクを張っています。issue #28 の MCP ツール群のフィクスチャ・手動確認の土台です。

**このプロジェクトはプロファイルを2つ持つので、`profilePath` を省いた ALPS ツール呼び出しは `status=ambiguous` を返します**（どちらを読んだのかが答えから分からなくなるため、先着順では答えません）。手動確認では読みたい方を明示してください。

```
bear_alps_profile_read(profilePath: "alps.json")
bear_alps_descriptor_lookup(id: "Dashboard", profilePath: "alps.json")
bear_alps_transition_lookup(from: "Dashboard", profilePath: "alps.json")
```

XML 側を確かめたいときは `profilePath: "profile.alps.xml"` に差し替えると、同じ答えが返ることを確認できます。

## 動作確認用コマンド

```sh
composer app -- get 'app://self/point?x=6&y=8'
composer app -- get 'app://self/point-dto?x=5&y=12'
composer test
```
