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
* Incoming Link/Embed relation gutter for BEAR.Resource methods
* Ray.Aop bound interceptor gutter icon and navigation from attributes such as `#[Transactional]`
* Ray.MediaQuery SQL goto
* Ray.QueryModule SQL goto
* Aura.Router goto BEAR.Resource

<!-- Plugin description end -->
## Requirements

* PhpStorm 2025.1 or later
* JDK 21 for building

## Libraries

* URI-Template Library (`com.damnhandy:handy-uri-templates:2.1.8`)
* Apache Commons Text (`org.apache.commons:commons-text:1.12.0`)

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
