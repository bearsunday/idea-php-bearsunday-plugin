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
| BEAR.Resource PHPDoc annotation補完 | `src/Resource/App/DocblockAnnotationDemo.php` | `@Link(...)` のannotation名、引数名、`href` 値を補完します。 |
| JSON Schemaジャンプ | `src/Resource/App/SchemaDemo.php`, `src/Resource/App/BodyTypeDemo.php` | `point.json` / `point-params.json` / `body-type-demo.json` で Cmd/Ctrl+B を押します。 |
| Link/Embed incoming gutter | `src/Resource/App/User.php`, `src/Resource/App/Profile.php`, `src/Resource/App/PointDto.php`, `src/Resource/Page/Index.php` | `onGet()` 左のBEARアイコンから参照元へ移動します。 |
| Twig/Qiq embedded template navigation | `App/Dashboard.html.twig`, `App/Dashboard.php` | `user` / `$this->user` のgutterまたは Cmd/Ctrl+B で埋め込み先テンプレートへ移動します。 |
| Input DTO抽出 | `src/Resource/App/Point.php` | `onGet(int $x, int $y)` にカーソルを置き、`Extract Input DTO...` を実行します。試すだけならDTO名は `PointDemoInput` にし、実行後にUndoします。 |
| Ray.Aop interceptorジャンプ | `src/Resource/App/PointDto.php`, `src/Resource/App/BodyTypeDemo.php` | `#[DemoLogged]` / `#[Audited]` 左のRay.Aopアイコン、または `Go to Bound Interceptor` を実行します。 |
| Ray.MediaQuery SQLジャンプ | `src/Query/PointQueryInterface.php` | `#[DbQuery('point_distance')]` の `point_distance` で Cmd/Ctrl+B を押します。 |
| Ray.QueryModule SQLジャンプ | `src/Query/QueryModuleDemo.php`, `src/Query/LegacyPointQueryInterface.php` | `point_distance` で Cmd/Ctrl+B を押します。 |
| Aura.Routerジャンプ | `aura.route.php` | `'/index'` / `'/dashboard'` で Cmd/Ctrl+B を押します。 |
| Psalm body type PHPDoc生成 | `src/Resource/App/BodyTypeDemo.php` | **Generate BEAR body type** intentionを実行します。 |

## Body type PHPDoc生成

`src/Resource/App/BodyTypeDemo.php` で **Generate BEAR body type** を実行すると、`$this->body` への代入から class PHPDoc に `@psalm-type BodyTypeDemoBody = ...` / `@psalm-type BodyTypeDemoPostBody = ...` と `@property BodyTypeDemoBody|BodyTypeDemoPostBody|null $body` が追加されます。メソッド名なしの `Body` は GET 表現です。

スクリーンショット:

- `../docs/images/body-type-generator-before.png`
- `../docs/images/body-type-generator-after.png`
- `../docs/images/phpstorm-body-type-demo-real.png`

## 動作確認用コマンド

```sh
composer app -- get 'app://self/point?x=6&y=8'
composer app -- get 'app://self/point-dto?x=5&y=12'
composer test
```
