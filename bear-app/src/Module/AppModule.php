<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Module;

use BEAR\Package\AbstractAppModule;
use BEAR\Package\PackageModule;
use Koriym\EnvJson\EnvJson;
use Ray\AuraSqlModule\AuraSqlModule;
use Ray\MediaQuery\MediaQuerySqlModule;

use function dirname;

final class AppModule extends AbstractAppModule
{
    protected function configure(): void
    {
        (new EnvJson())->load(dirname(__DIR__, 2));
        $this->install(new PackageModule());
        $this->install(new AuraSqlModule('sqlite::memory:'));
        $this->install(new MediaQuerySqlModule(
            interfaceDir: dirname(__DIR__) . '/Query',
            sqlDir: dirname(__DIR__, 2) . '/var/sql',
        ));
    }
}
