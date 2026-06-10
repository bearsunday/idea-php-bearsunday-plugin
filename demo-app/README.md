# BEAR.Sundayプラグイン デモアプリ

PhpStormでこの `demo-app/` をプロジェクトとして開くと、プラグイン機能を一通り試せます。リポジトリのルートではなく、このディレクトリを開いてください。

## 準備

```sh
composer install
```

## 機能別の試し方

- **BEAR.Resource URI補完**: `src/Resource/App/RelationDemo.php` などの文字列内で `app://self/` を入力して補完を実行します。
- **BEAR.Resource annotation補完**: `src/Resource/App/RelationDemo.php` で `#[` や `src:` / `href:` の文字列内補完を実行します。
- **Resourceジャンプ**: `src/Resource/App/RelationDemo.php` の `app://self/point-dto` で Cmd/Ctrl+B を押します。
- **JSON Schemaジャンプ**: `src/Resource/App/SchemaDemo.php` の `point.json` / `point-params.json` の上で Cmd/Ctrl+B を押します。
- **Link/Embed incoming gutter**: `src/Resource/App/PointDto.php` と `src/Resource/Page/Index.php` を開き、`onGet()` の左に出るBEARアイコンから参照元へ移動します。
- **Input DTO抽出**: `src/Resource/App/Point.php` の `onGet(int $x, int $y)` にカーソルを置き、`Extract Input DTO...` を実行します。試すだけならDTO名は `PointDemoInput` にし、実行後にUndoします。
- **Ray.Aop interceptorジャンプ**: `src/Resource/App/PointDto.php` の `#[DemoLogged]` 左のRay.Aopアイコン、または `Go to Bound Interceptor` を実行します。
- **Ray.MediaQuery SQLジャンプ**: `src/Query/PointQueryInterface.php` の `#[DbQuery('point_distance')]` の `point_distance` で Cmd/Ctrl+B を押します。
- **Ray.QueryModule SQLジャンプ**: `src/Query/QueryModuleDemo.php` の `point_distance` で Cmd/Ctrl+B を押します。
- **Aura.Routerジャンプ**: `aura.route.php` の `'/index'` で Cmd/Ctrl+B を押します。

## 動作確認用コマンド

```sh
composer app -- get 'app://self/point?x=6&y=8'
composer app -- get 'app://self/point-dto?x=5&y=12'
composer test
```
